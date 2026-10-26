//! While a cast runs the PC should neither fall asleep nor switch its screen off, and a locked
//! screen (which Windows does not let anything capture) should be told to the person.

use windows::Win32::System::Power::{SetThreadExecutionState, ES_CONTINUOUS, ES_DISPLAY_REQUIRED, ES_SYSTEM_REQUIRED};
use windows::Win32::System::StationsAndDesktops::{CloseDesktop, OpenInputDesktop, DESKTOP_CONTROL_FLAGS, DESKTOP_SWITCHDESKTOP};

/// Keeps the PC awake for as long as it lives, on the thread that made it (that is how Windows
/// scopes the request); what Windows lists under `powercfg /requests`.
pub struct Awake;

impl Awake {
    pub fn new() -> Awake {
        unsafe {
            SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);
        }
        Awake
    }
}

impl Drop for Awake {
    fn drop(&mut self) {
        unsafe {
            SetThreadExecutionState(ES_CONTINUOUS);
        }
    }
}

/// Whether the input desktop is not ours to look at: the lock screen, a password prompt or the
/// sign-in screen run on a desktop of their own, and opening it fails then.
pub fn screen_locked() -> bool {
    unsafe {
        match OpenInputDesktop(DESKTOP_CONTROL_FLAGS(0), false, DESKTOP_SWITCHDESKTOP) {
            Ok(desktop) => {
                let _ = CloseDesktop(desktop);
                false
            }
            Err(_) => true,
        }
    }
}
