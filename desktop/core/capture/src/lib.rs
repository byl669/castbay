//! Screen and window capture. Platform code lives in its own module; nothing here knows about
//! encoding or the network.

use std::fmt;
use std::time::Duration;

#[cfg(windows)]
pub mod windows;

#[cfg(windows)]
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
