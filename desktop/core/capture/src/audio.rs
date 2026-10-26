//! System audio: what the default output device is playing (WASAPI loopback), as 48 kHz stereo
//! 32-bit float samples, interleaved. The platform converts whatever the device uses.

use std::time::Duration;

use windows::Win32::Media::Audio::{
    eConsole, eRender, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator, MMDeviceEnumerator, AUDCLNT_BUFFERFLAGS_SILENT,
    AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM, AUDCLNT_STREAMFLAGS_LOOPBACK, AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
    WAVEFORMATEX,
};
use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_ALL, COINIT_MULTITHREADED};

use crate::Error;

pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: u32 = 2;

fn platform(e: windows::core::Error) -> Error {
    Error::Platform(e.to_string())
}

/// Delivers the audio the default output plays to `on_samples` (interleaved stereo floats, in
/// bursts of a few milliseconds) until `stop` is set. Silence is delivered as zeros while
/// something is playing; while nothing plays the device delivers nothing at all. Returns an
/// error when the device goes away (the caller starts it again).
pub fn run_loopback(stop: &dyn Fn() -> bool, mut on_samples: impl FnMut(&[f32])) -> Result<(), Error> {
    unsafe {
        let initialised = CoInitializeEx(None, COINIT_MULTITHREADED).is_ok();
        let result = capture(stop, &mut on_samples);
        if initialised {
            CoUninitialize();
        }
        result
    }
}

unsafe fn capture(stop: &dyn Fn() -> bool, on_samples: &mut dyn FnMut(&[f32])) -> Result<(), Error> {
    let enumerator: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).map_err(platform)?;
    let device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole).map_err(platform)?;
    let client: IAudioClient = device.Activate(CLSCTX_ALL, None).map_err(platform)?;
    let format = WAVEFORMATEX {
        wFormatTag: 3, // WAVE_FORMAT_IEEE_FLOAT
        nChannels: CHANNELS as u16,
        nSamplesPerSec: SAMPLE_RATE,
        nAvgBytesPerSec: SAMPLE_RATE * CHANNELS * 4,
        nBlockAlign: (CHANNELS * 4) as u16,
        wBitsPerSample: 32,
        cbSize: 0,
    };
    client
        .Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
            2_000_000, // 200 ms
            0,
            &format,
            None,
        )
        .map_err(platform)?;
    let capture: IAudioCaptureClient = client.GetService().map_err(platform)?;
    client.Start().map_err(platform)?;

    let mut silence: Vec<f32> = Vec::new();
    let result = (|| -> Result<(), Error> {
        while !stop() {
            std::thread::sleep(Duration::from_millis(5));
            while capture.GetNextPacketSize().map_err(platform)? > 0 {
                let (mut data, mut frames, mut flags) = (std::ptr::null_mut::<u8>(), 0u32, 0u32);
                capture.GetBuffer(&mut data, &mut frames, &mut flags, None, None).map_err(platform)?;
                let samples = frames as usize * CHANNELS as usize;
                if flags & (AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0 || data.is_null() {
                    silence.clear();
                    silence.resize(samples, 0.0);
                    on_samples(&silence);
                } else {
                    on_samples(std::slice::from_raw_parts(data as *const f32, samples));
                }
                capture.ReleaseBuffer(frames).map_err(platform)?;
            }
        }
        Ok(())
    })();
    let _ = client.Stop();
    result
}
