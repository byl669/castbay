//! The sound the PC plays, on Linux (PipeWire). Same interface as the Windows module; the real
//! capture comes in a later step.

use crate::Error;

pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: u32 = 2;

/// Delivers the audio the default output plays to `on_samples` until `stop` says so.
pub fn run_loopback(_stop: &dyn Fn() -> bool, _on_samples: impl FnMut(&[f32])) -> Result<(), Error> {
    Err(Error::Platform("capturing the sound on Linux is not built yet".into()))
}
