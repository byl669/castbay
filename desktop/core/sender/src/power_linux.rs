//! Keeping the PC awake and noticing the lock screen, on Linux. The real version (logind and the
//! desktop portal) comes in a later step; for now nothing is held and the screen is never "locked".

pub struct Awake;

impl Awake {
    pub fn new() -> Awake {
        Awake
    }
}

pub fn screen_locked() -> bool {
    false
}
