//! H.264 through Media Foundation: the machine's hardware encoder when there is one (AMD,
//! NVIDIA and Intel all ship encoder transforms), Microsoft's software one otherwise.

use std::mem::ManuallyDrop;
use std::time::{Duration, Instant};

use windows::core::{Interface, GUID};
use windows::Win32::Foundation::HMODULE;
use windows::Win32::System::Variant::{VARIANT, VARIANT_0, VARIANT_0_0, VARIANT_0_0_0, VT_UI4};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE};
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11Multithread, D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
    D3D11_SDK_VERSION,
};
use windows::Win32::System::Com::{CoInitializeEx, CoTaskMemFree, COINIT_MULTITHREADED};

use crate::{nal_types, Config, Encoded, Error};

fn trace(what: &str) {
    if std::env::var_os("CASTBAY_TRACE").is_some() {
        eprintln!("[encode] {what}");
    }
}

fn err(what: &str, e: windows::core::Error) -> Error {
    Error(format!("{what}: {e}"))
}

/// Which encoder transform to use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Hardware,
    Software,
}

pub struct MfEncoder {
    transform: IMFTransform,
    events: Option<IMFMediaEventGenerator>,
    codec: Option<ICodecAPI>,
    _device: Option<(ID3D11Device, IMFDXGIDeviceManager)>,
    config: Config,
    pub name: String,
    pub kind: Kind,
    need_input: u32,
    provides_samples: bool,
    out_size: u32,
    parameter_sets: Vec<u8>,
    pending_pts: std::collections::VecDeque<i64>,
    frame_index: i64,
}

// The transform is used from one thread at a time.
unsafe impl Send for MfEncoder {}

fn set_u32(codec: &ICodecAPI, api: &GUID, value: u32) {
    let variant = VARIANT {
        Anonymous: VARIANT_0 {
            Anonymous: ManuallyDrop::new(VARIANT_0_0 {
                vt: VT_UI4,
                wReserved1: 0,
                wReserved2: 0,
                wReserved3: 0,
                Anonymous: VARIANT_0_0_0 { ulVal: value },
            }),
        },
    };
    // Encoders ignore settings they do not know.
    let _ = unsafe { codec.SetValue(api, &variant) };
}

/// Two 32-bit values packed in one 64-bit attribute (frame size, frame rate, aspect ratio).
fn set_pair(attributes: &IMFMediaType, key: &GUID, high: u32, low: u32) -> windows::core::Result<()> {
    unsafe { attributes.SetUINT64(key, ((high as u64) << 32) | low as u64) }
}

impl MfEncoder {
    pub fn new(config: Config, kind: Kind) -> Result<Self, Error> {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            trace("MFStartup");
            MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(|e| err("MFStartup", e))?;
            trace("enumerate");

            let output = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_H264 };
            let flags = match kind {
                Kind::Hardware => MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER,
                Kind::Software => MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER,
            };
            let mut list: *mut Option<IMFActivate> = std::ptr::null_mut();
            let mut count = 0u32;
            MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, flags, None, Some(&output), &mut list, &mut count)
                .map_err(|e| err("MFTEnumEx", e))?;
            if count == 0 || list.is_null() {
                return Err(Error(format!("no {kind:?} H.264 encoder found")));
            }
            let activates = std::slice::from_raw_parts(list, count as usize);
            let mut chosen: Option<(IMFTransform, String)> = None;
            for activate in activates.iter().flatten() {
                let mut name = String::new();
                let mut buffer = [0u16; 256];
                let mut length = 0u32;
                if activate.GetString(&MFT_FRIENDLY_NAME_Attribute, &mut buffer, Some(&mut length)).is_ok() {
                    name = String::from_utf16_lossy(&buffer[..length as usize]);
                }
                match activate.ActivateObject::<IMFTransform>() {
                    Ok(t) => {
                        chosen = Some((t, name));
                        break;
                    }
                    Err(_) => continue,
                }
            }
            for activate in activates.iter().flatten() {
                let _ = activate.ShutdownObject();
            }
            CoTaskMemFree(Some(list as *const _));
            trace("activated");
            let (transform, name) = chosen.ok_or_else(|| Error("no encoder could be created".into()))?;

            let attributes = transform.GetAttributes().ok();
            let is_async = attributes
                .as_ref()
                .map(|a| a.GetUINT32(&MF_TRANSFORM_ASYNC).unwrap_or(0) == 1)
                .unwrap_or(false);
            if is_async {
                if let Some(a) = &attributes {
                    a.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1).map_err(|e| err("async unlock", e))?;
                }
            }
            if let Some(a) = &attributes {
                let _ = a.SetUINT32(&MF_LOW_LATENCY, 1);
            }
            let events = if is_async { transform.cast::<IMFMediaEventGenerator>().ok() } else { None };

            trace(&format!("async={is_async}"));
            // Hardware encoders want a Direct3D device to work on; AMD's one crashes without it.
            let knobs = std::env::var("CASTBAY_KNOBS").unwrap_or_default();
            let device = if kind == Kind::Hardware && !knobs.contains('d') { Some(Self::attach_device(&transform)?) } else { None };
            trace("device attached");
            // The output type first: encoders choose their input types from it.
            let out_type = MFCreateMediaType().map_err(|e| err("output type", e))?;
            out_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video).map_err(|e| err("major", e))?;
            out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264).map_err(|e| err("subtype", e))?;
            out_type.SetUINT32(&MF_MT_AVG_BITRATE, config.bitrate_bps).map_err(|e| err("bitrate", e))?;
            set_pair(&out_type, &MF_MT_FRAME_SIZE, config.width, config.height).map_err(|e| err("size", e))?;
            set_pair(&out_type, &MF_MT_FRAME_RATE, config.fps, 1).map_err(|e| err("rate", e))?;
            set_pair(&out_type, &MF_MT_PIXEL_ASPECT_RATIO, 1, 1).map_err(|e| err("aspect", e))?;
            out_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32).map_err(|e| err("interlace", e))?;
            out_type.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_High.0 as u32).map_err(|e| err("profile", e))?;
            transform.SetOutputType(0, &out_type, 0).map_err(|e| err("SetOutputType", e))?;

            let in_type = MFCreateMediaType().map_err(|e| err("input type", e))?;
            in_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video).map_err(|e| err("major", e))?;
            in_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12).map_err(|e| err("subtype", e))?;
            set_pair(&in_type, &MF_MT_FRAME_SIZE, config.width, config.height).map_err(|e| err("size", e))?;
            set_pair(&in_type, &MF_MT_FRAME_RATE, config.fps, 1).map_err(|e| err("rate", e))?;
            set_pair(&in_type, &MF_MT_PIXEL_ASPECT_RATIO, 1, 1).map_err(|e| err("aspect", e))?;
            in_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32).map_err(|e| err("interlace", e))?;
            in_type.SetUINT32(&MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709.0 as u32).ok();
            in_type.SetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235.0 as u32).ok();
            trace("output type set");
            transform.SetInputType(0, &in_type, 0).map_err(|e| err("SetInputType", e))?;

            trace("input type set");
            let codec = transform.cast::<ICodecAPI>().ok();
            if let Some(codec) = &codec {
                set_u32(codec, &CODECAPI_AVLowLatencyMode, 1);
                set_u32(codec, &CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR.0 as u32);
                set_u32(codec, &CODECAPI_AVEncCommonMeanBitRate, config.bitrate_bps);
                set_u32(codec, &CODECAPI_AVEncMPVGOPSize, config.gop);
                set_u32(codec, &CODECAPI_AVEncMPVDefaultBPictureCount, 0);
                set_u32(codec, &CODECAPI_AVEncCommonRealTime, 1);
            }


            let info = transform.GetOutputStreamInfo(0).map_err(|e| err("output info", e))?;
            trace(&format!("stream info flags={:#x} size={}", info.dwFlags, info.cbSize));
            let provides_samples = info.dwFlags
                & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32)
                != 0;

            trace("flush");
            if !knobs.contains('f') {
                transform.ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0).ok();
            }
            trace("begin");
            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0).map_err(|e| err("begin streaming", e))?;
            trace("start of stream");
            if !knobs.contains('s') {
                transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0).map_err(|e| err("start of stream", e))?;
            }

            trace("streaming");
            Ok(MfEncoder {
                transform,
                events,
                codec,
                _device: device,
                config,
                name,
                kind,
                need_input: 0,
                provides_samples,
                out_size: info.cbSize.max(1 << 20),
                parameter_sets: Vec::new(),
                pending_pts: Default::default(),
                frame_index: 0,
            })
        }
    }

    unsafe fn attach_device(transform: &IMFTransform) -> Result<(ID3D11Device, IMFDXGIDeviceManager), Error> {
        let mut device: Option<ID3D11Device> = None;
        D3D11CreateDevice(
            None,
            D3D_DRIVER_TYPE_HARDWARE,
            HMODULE::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
            None,
            D3D11_SDK_VERSION,
            Some(&mut device),
            None,
            None,
        )
        .map_err(|e| err("D3D11CreateDevice", e))?;
        let device = device.ok_or_else(|| Error("no D3D11 device".into()))?;
        if let Ok(multithread) = device.cast::<ID3D11Multithread>() {
            let _ = multithread.SetMultithreadProtected(true);
        }
        let mut token = 0u32;
        let mut manager: Option<IMFDXGIDeviceManager> = None;
        MFCreateDXGIDeviceManager(&mut token, &mut manager).map_err(|e| err("MFCreateDXGIDeviceManager", e))?;
        let manager = manager.ok_or_else(|| Error("no device manager".into()))?;
        manager.ResetDevice(&device, token).map_err(|e| err("ResetDevice", e))?;
        transform
            .ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, manager.as_raw() as usize)
            .map_err(|e| err("SET_D3D_MANAGER", e))?;
        Ok((device, manager))
    }

    /// Asks for a key frame on the next picture.
    pub fn request_keyframe(&mut self) {
        if let Some(codec) = &self.codec {
            set_u32(codec, &CODECAPI_AVEncVideoForceKeyFrame, 1);
        }
    }

    /// Encodes one NV12 picture and returns the access units that came out (normally one).
    pub fn encode(&mut self, nv12: &[u8], pts_us: i64) -> Result<Vec<Encoded>, Error> {
        let mut out = Vec::new();
        unsafe {
            let length = nv12.len() as u32;
            let buffer = MFCreateMemoryBuffer(length).map_err(|e| err("buffer", e))?;
            let mut data = std::ptr::null_mut();
            buffer.Lock(&mut data, None, None).map_err(|e| err("lock", e))?;
            std::ptr::copy_nonoverlapping(nv12.as_ptr(), data, nv12.len());
            buffer.Unlock().map_err(|e| err("unlock", e))?;
            buffer.SetCurrentLength(length).map_err(|e| err("length", e))?;
            let sample = MFCreateSample().map_err(|e| err("sample", e))?;
            sample.AddBuffer(&buffer).map_err(|e| err("add buffer", e))?;
            sample.SetSampleTime(pts_us * 10).map_err(|e| err("time", e))?;
            sample.SetSampleDuration(10_000_000 / self.config.fps as i64).map_err(|e| err("duration", e))?;

            if self.events.is_some() {
                // Asynchronous (hardware) transform: feed it when it asks, collect what it hands out.
                self.pump(&mut out, Duration::from_millis(100), true)?;
                if self.need_input == 0 {
                    return Err(Error("encoder did not ask for input".into()));
                }
                self.need_input -= 1;
                self.transform.ProcessInput(0, &sample, 0).map_err(|e| err("ProcessInput", e))?;
                self.pending_pts.push_back(pts_us);
                self.frame_index += 1;
                // Low-latency encoders answer each picture with one unit: wait briefly for it.
                self.pump(&mut out, Duration::from_millis(60), false)?;
            } else {
                self.transform.ProcessInput(0, &sample, 0).map_err(|e| err("ProcessInput", e))?;
                self.pending_pts.push_back(pts_us);
                self.frame_index += 1;
                self.drain_sync(&mut out)?;
            }
        }
        Ok(out)
    }

    /// Handles transform events until `deadline`; with `until_input` it stops once the
    /// transform wants input, otherwise once an output came out.
    unsafe fn pump(&mut self, out: &mut Vec<Encoded>, limit: Duration, until_input: bool) -> Result<(), Error> {
        let started = Instant::now();
        let events = self.events.clone().unwrap();
        loop {
            match events.GetEvent(MF_EVENT_FLAG_NO_WAIT) {
                Ok(event) => {
                    let kind = event.GetType().unwrap_or(0);
                    if kind == METransformNeedInput.0 as u32 {
                        self.need_input += 1;
                        if until_input {
                            return Ok(());
                        }
                    } else if kind == METransformHaveOutput.0 as u32 {
                        if let Some(unit) = self.take_output()? {
                            out.push(unit);
                            if !until_input {
                                return Ok(());
                            }
                        }
                    }
                }
                Err(_) => {
                    if started.elapsed() > limit {
                        return Ok(());
                    }
                    std::thread::sleep(Duration::from_micros(300));
                }
            }
        }
    }

    unsafe fn drain_sync(&mut self, out: &mut Vec<Encoded>) -> Result<(), Error> {
        while let Some(unit) = self.take_output()? {
            out.push(unit);
        }
        Ok(())
    }

    /// Fetches one output sample, or None when the transform has nothing (yet).
    unsafe fn take_output(&mut self) -> Result<Option<Encoded>, Error> {
        let mut buffers = [MFT_OUTPUT_DATA_BUFFER::default()];
        if !self.provides_samples {
            let sample = MFCreateSample().map_err(|e| err("out sample", e))?;
            let buffer = MFCreateMemoryBuffer(self.out_size).map_err(|e| err("out buffer", e))?;
            sample.AddBuffer(&buffer).map_err(|e| err("add", e))?;
            buffers[0].pSample = ManuallyDrop::new(Some(sample));
        }
        let mut status = 0u32;
        match self.transform.ProcessOutput(0, &mut buffers, &mut status) {
            Ok(()) => {}
            Err(e) if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => return Ok(None),
            Err(e) if e.code() == MF_E_TRANSFORM_STREAM_CHANGE => {
                // The output type changed (usually the first time); take the new one and go on.
                if let Ok(t) = self.transform.GetOutputAvailableType(0, 0) {
                    let _ = self.transform.SetOutputType(0, &t, 0);
                }
                return Ok(None);
            }
            Err(e) => return Err(err("ProcessOutput", e)),
        }
        let sample = ManuallyDrop::take(&mut buffers[0].pSample);
        let _events = ManuallyDrop::take(&mut buffers[0].pEvents);
        let Some(sample) = sample else { return Ok(None) };
        let buffer = sample.ConvertToContiguousBuffer().map_err(|e| err("contiguous", e))?;
        let mut data = std::ptr::null_mut();
        let mut length = 0u32;
        buffer.Lock(&mut data, None, Some(&mut length)).map_err(|e| err("lock out", e))?;
        let mut bytes = std::slice::from_raw_parts(data, length as usize).to_vec();
        buffer.Unlock().ok();

        let types = nal_types(&bytes);
        let key = types.contains(&5);
        if types.contains(&7) && types.contains(&8) {
            // Remember the parameter sets (everything in front of the IDR slice).
            if let Some(pos) = find_nal(&bytes, 5) {
                self.parameter_sets = bytes[..pos].to_vec();
            }
        } else if key && !self.parameter_sets.is_empty() {
            let mut with = self.parameter_sets.clone();
            with.extend_from_slice(&bytes);
            bytes = with;
        }
        let pts_us = self.pending_pts.pop_front().unwrap_or(0);
        Ok(Some(Encoded { data: bytes, key, pts_us }))
    }
}

/// The offset of the start code in front of the first NAL unit of `nal_type`.
fn find_nal(data: &[u8], nal_type: u8) -> Option<usize> {
    let mut i = 0;
    while i + 3 < data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && data[i + 3] & 0x1F == nal_type {
            return Some(if i > 0 && data[i - 1] == 0 { i - 1 } else { i });
        }
        i += 1;
    }
    None
}

impl Drop for MfEncoder {
    fn drop(&mut self) {
        unsafe {
            let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
            let _ = MFShutdown();
        }
    }
}
