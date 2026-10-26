//! Keeping the PC awake and noticing the lock screen, on Linux (systemd-logind).

use std::process::{Child, Command, Stdio};

/// While alive, the PC does not go idle or sleep: a `systemd-inhibit` child holds the lock.
pub struct Awake(Option<Child>);

impl Awake {
    pub fn new() -> Awake {
        let child = Command::new("systemd-inhibit")
            .args(["--what=idle:sleep", "--who=CastBay Desktop", "--why=Casting the screen", "--mode=block", "sleep", "infinity"])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .ok();
        Awake(child)
    }
}

impl Drop for Awake {
    fn drop(&mut self) {
        if let Some(child) = &mut self.0 {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

/// Whether the session is locked, as the desktop told logind.
pub fn screen_locked() -> bool {
    let Ok(id) = std::env::var("XDG_SESSION_ID") else { return false };
    Command::new("loginctl")
        .args(["show-session", &id, "-p", "LockedHint", "--value"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim() == "yes")
        .unwrap_or(false)
}
