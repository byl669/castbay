//! Screen capture on Linux. Wayland allows only the desktop portal's ScreenCast with PipeWire; that
//! comes in a later step, this file only gives the platform interface its place.

use crate::{Ended, Error, Frame, Target};

pub fn run(_target: &Target, _on_frame: impl FnMut(Frame<'_>) -> bool + Send + 'static) -> Result<Ended, Error> {
    Err(Error::Platform("screen capture on Linux is not built yet".into()))
}
