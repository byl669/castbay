//! Screen and window capture. Platform code lives in its own module; nothing here knows about
//! encoding or the network.

use std::fmt;
use std::time::Duration;

#[cfg(windows)]
pub mod windows;

#[cfg(target_os = "linux")]
mod linux;

// What the system plays, as 48 kHz stereo floats: one module per platform under one name.
#[cfg(windows)]
pub mod audio;
#[cfg(target_os = "linux")]
#[path = "audio_linux.rs"]
pub mod audio;

/// What to capture.
#[derive(Debug, Clone)]
pub enum Target {
    /// A monitor, by index in the platform's enumeration (0 is the primary one).
    Monitor(usize),
    /// A top-level window whose title contains the text.
    Window(String),
}

/// One captured picture, BGRA with 4 bytes per pixel and rows `stride` bytes apart.
pub struct Frame<'a> {
    pub width: u32,
    pub height: u32,
    pub stride: usize,
    pub data: &'a [u8],
    /// Time since the capture started.
    pub timestamp: Duration,
}

#[derive(Debug)]
pub enum Error {
    /// No monitor or window matches the target.
    NotFound(String),
    /// The platform refused or failed.
    Platform(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::NotFound(what) => write!(f, "not found: {what}"),
            Error::Platform(what) => write!(f, "capture failed: {what}"),
        }
    }
}

impl std::error::Error for Error {}

/// Why a capture ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ended {
    /// The caller asked to stop.
    Stopped,
    /// The captured window or monitor went away.
    SourceClosed,
}

/// Captures `target` until `on_frame` returns false or the source closes; frames are only valid
/// inside the callback. The same call on every platform.
pub fn run(target: &Target, on_frame: impl FnMut(Frame<'_>) -> bool + Send + 'static) -> Result<Ended, Error> {
    #[cfg(windows)]
    {
        windows::run(target, on_frame)
    }
    #[cfg(target_os = "linux")]
    {
        linux::run(target, on_frame)
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        let _ = (target, on_frame);
        Err(Error::Platform("screen capture is not implemented on this system".into()))
    }
}

/// Names of the monitors, for a picker (empty where the system does its own picking).
pub fn monitors() -> Result<Vec<String>, Error> {
    #[cfg(windows)]
    {
        windows::monitors()
    }
    #[cfg(not(windows))]
    {
        Ok(Vec::new())
    }
}

/// Titles of the windows that can be captured, for a picker (empty where the system does its own picking).
pub fn window_titles() -> Result<Vec<String>, Error> {
    #[cfg(windows)]
    {
        windows::windows()
    }
    #[cfg(not(windows))]
    {
        Ok(Vec::new())
    }
}
