//! In-process FFmpeg encoding (libx264, AMD AMF, NVIDIA NVENC) through a small C shim, so
//! pictures need no pipe and key frames can be requested per picture.

use std::ffi::{c_char, c_int, CString};

use crate::{ffmpeg::Codec, Config, Encoded, Error};

#[repr(C)]
struct CbEnc {
    _private: [u8; 0],
}

extern "C" {
    fn cb_enc_open(
        name: *const c_char,
        width: c_int,
        height: c_int,
        fps: c_int,
        bitrate: c_int,
        gop: c_int,
        err: *mut c_char,
        errlen: c_int,
    ) -> *mut CbEnc;
    fn cb_enc_encode(
        e: *mut CbEnc,
        nv12: *const u8,
        pts_us: i64,
        force_key: c_int,
        out: *mut *const u8,
        out_len: *mut c_int,
        key: *mut c_int,
        out_pts: *mut i64,
    ) -> c_int;
    fn cb_enc_close(e: *mut CbEnc);
}

pub struct InProcessEncoder {
    enc: *mut CbEnc,
    frame_len: usize,
}

// The encoder is used from one thread at a time.
unsafe impl Send for InProcessEncoder {}

impl InProcessEncoder {
    pub fn new(config: &Config, codec: Codec) -> Result<Self, Error> {
        let name = match codec {
            Codec::Amf => "h264_amf",
            Codec::Nvenc => "h264_nvenc",
            Codec::X264 => "libx264",
            Codec::Qsv => return Err(Error("Intel Quick Sync is not built into the in-process encoder yet".into())),
        };
        let cname = CString::new(name).unwrap();
        let mut err = [0 as c_char; 256];
        let enc = unsafe {
            cb_enc_open(
                cname.as_ptr(),
                config.width as c_int,
                config.height as c_int,
                config.fps as c_int,
                config.bitrate_bps as c_int,
                config.gop as c_int,
                err.as_mut_ptr(),
                err.len() as c_int,
            )
        };
        if enc.is_null() {
            let text = unsafe { std::ffi::CStr::from_ptr(err.as_ptr()) }.to_string_lossy().into_owned();
            return Err(Error(format!("{name}: {text}")));
        }
        Ok(Self { enc, frame_len: (config.width * config.height * 3 / 2) as usize })
    }

    /// Encodes one NV12 picture. `force_key` makes it an IDR picture. Returns the access unit
    /// that is ready, if any (none while the encoder is still filling).
    pub fn encode(&mut self, nv12: &[u8], pts_us: i64, force_key: bool) -> Result<Option<Encoded>, Error> {
        if nv12.len() < self.frame_len {
            return Err(Error("picture is smaller than the configured size".into()));
        }
        let (mut out, mut len, mut key, mut out_pts) = (std::ptr::null(), 0, 0, 0i64);
        let r = unsafe {
            cb_enc_encode(self.enc, nv12.as_ptr(), pts_us, force_key as c_int, &mut out, &mut len, &mut key, &mut out_pts)
        };
        match r {
            1 => Ok(Some(Encoded {
                data: unsafe { std::slice::from_raw_parts(out, len as usize) }.to_vec(),
                key: key != 0,
                pts_us: out_pts,
            })),
            0 => Ok(None),
            code => Err(Error(format!("encode failed ({code})"))),
        }
    }
}

impl Drop for InProcessEncoder {
    fn drop(&mut self) {
        unsafe { cb_enc_close(self.enc) }
    }
}
