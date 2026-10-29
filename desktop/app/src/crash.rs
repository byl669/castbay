//! Knowing that the previous run ended badly, so the next start can offer to send a report about it
//! (or send it, if the person turned that on). Two signs: a panic leaves the text of what happened
//! in `crash.txt` (the panic hook writes it before the program goes), and a cast leaves
//! `casting.marker` while it runs: one found at the next start means the program ended during a
//! cast without anyone stopping it (a crash in a library, being killed, a power cut).

use std::path::{Path, PathBuf};

const CRASH: &str = "crash.txt";
const MARKER: &str = "casting.marker";
/// A panic's text is kept this long at most (the report has a limit of its own).
const MAX_CRASH_BYTES: usize = 30_000;

/// What the previous run left behind.
#[derive(Debug, Clone, PartialEq)]
pub struct Pending {
    /// `panic` or `unfinished`.
    pub kind: &'static str,
    pub text: String,
}

/// From now on a panic writes its message, place and call stack to `crash.txt` in `dir` before
/// the usual message.
pub fn install_hook(dir: PathBuf) {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let backtrace = std::backtrace::Backtrace::force_capture();
        let thread = std::thread::current();
        let text = format!("panic in thread {}: {info}\n{backtrace}", thread.name().unwrap_or("?"));
        let _ = std::fs::create_dir_all(&dir);
        let _ = std::fs::write(dir.join(CRASH), &text.as_bytes()[..text.len().min(MAX_CRASH_BYTES)]);
        previous(info);
    }));
}

/// A cast starts (`true`) or ends (`false`).
pub fn mark_casting(dir: &Path, on: bool) {
    if on {
        let _ = std::fs::create_dir_all(dir);
        let _ = std::fs::write(dir.join(MARKER), b"");
    } else {
        let _ = std::fs::remove_file(dir.join(MARKER));
    }
}

/// What the previous run left, and then nothing: the files are removed, the text is the caller's.
pub fn take(dir: &Path) -> Option<Pending> {
    let panic = std::fs::read(dir.join(CRASH)).ok().map(|b| String::from_utf8_lossy(&b).into_owned());
    let unfinished = dir.join(MARKER).exists();
    let _ = std::fs::remove_file(dir.join(CRASH));
    let _ = std::fs::remove_file(dir.join(MARKER));
    match (panic, unfinished) {
        (Some(text), _) => Some(Pending { kind: "panic", text }),
        (None, true) => Some(Pending {
            kind: "unfinished",
            text: "The previous run ended during a cast without CastBay stopping it (a crash, the program being closed from outside, or the computer shutting down).".into(),
        }),
        (None, false) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("castbay-crash-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_clean_run_leaves_nothing() {
        let dir = temp("clean");
        assert_eq!(take(&dir), None);
        mark_casting(&dir, true);
        mark_casting(&dir, false);
        assert_eq!(take(&dir), None);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_cast_that_never_ended_is_found_once() {
        let dir = temp("unfinished");
        mark_casting(&dir, true);
        let pending = take(&dir).unwrap();
        assert_eq!(pending.kind, "unfinished");
        assert_eq!(take(&dir), None, "taking it clears it");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_panic_text_wins_over_the_marker_and_is_capped() {
        let dir = temp("panic");
        std::fs::write(dir.join(CRASH), "panic in thread main: boom").unwrap();
        mark_casting(&dir, true);
        let pending = take(&dir).unwrap();
        assert_eq!((pending.kind, pending.text.as_str()), ("panic", "panic in thread main: boom"));
        assert_eq!(take(&dir), None);
        let _ = std::fs::remove_dir_all(dir);
    }
}
