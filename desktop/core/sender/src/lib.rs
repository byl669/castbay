//! Casting a screen or window to a CastBay receiver: captures, converts, encodes and sends,
//! repeating the last picture on a static screen and replacing a broken connection. The
//! command line and the app both drive this.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

pub use castbay_capture::Target;
pub use castbay_encode::ffmpeg::Codec;

#[cfg(any(windows, target_os = "linux"))]
mod engine;
#[cfg(any(windows, target_os = "linux"))]
pub use engine::available_encoders;
#[cfg(windows)]
mod power;
#[cfg(target_os = "linux")]
#[path = "power_linux.rs"]
mod power;
#[cfg(any(windows, target_os = "linux"))]
mod rate;

/// Everything needed to start a cast.
pub struct Options {
    pub addr: SocketAddr,
    /// The receiver's id when known (from discovery); pins its key on first contact.
    pub receiver_id: Option<String>,
    /// This computer's name, as the receiver shows it.
    pub name: String,
    pub password: Option<String>,
    pub target: Target,
    pub fps: u32,
    pub bitrate_bps: u32,
    /// The largest picture sent: a bigger screen is scaled down to fit (and a window that changes
    /// size is fitted into the size the cast started with, with black bars).
    pub max_size: (u32, u32),
    /// The encoder to use; None picks the first that works: AMD, NVIDIA, then x264 on the CPU.
    pub codec: Option<Codec>,
    /// Where the identity and the pinned receivers live.
    pub data_dir: PathBuf,
    /// Send the sound the PC is playing too (needs the in-process encoders, feature `ffmpeg-lib`).
    pub audio: bool,
    /// Stop by itself after this long (tests and benchmarks); None casts until stopped.
    pub max_duration: Option<Duration>,
}

/// What the person at the keyboard sees while casting.
#[derive(Debug, Clone, PartialEq)]
pub enum Status {
    Connecting,
    /// The receiver asked for a pairing code (it shows one on its screen).
    NeedsCode,
    Casting { receiver: String, width: u32, height: u32 },
    /// The connection broke; a new one is being made.
    Reconnecting(String),
    Reconnected,
    /// The receiver's once-a-second report.
    Receiver { decode_ms: i64, dropped: u64, fps: f64 },
    /// The bitrate in use changed (the connection could not keep up, or has recovered), in bit/s.
    Bitrate(u32),
    /// What is cast went away (the window was closed): the cast goes on with the last picture
    /// and waits for [`Switcher::switch`] to name something else.
    SourceLost,
    /// A new target was asked for and is being captured now.
    Switched,
    /// The new target could not be captured; the cast goes on with the old one (or keeps waiting).
    SwitchFailed(String),
    /// The PC's screen is locked (true) or unlocked again (false): Windows gives nothing to capture
    /// meanwhile, so the picture stays as it was.
    ScreenLocked(bool),
    /// The cast is over; the text says why.
    Ended(String),
}

/// How a finished cast went.
#[derive(Debug, Default, Clone)]
pub struct Report {
    pub units_sent: u64,
    pub units_dropped: u64,
    pub repeated_pictures: u64,
    pub reconnects: u64,
    pub bytes: u64,
    pub seconds: f64,
    /// Capture-to-ready-to-send delays, sorted (milliseconds).
    pub lags_ms: Vec<f64>,
    pub last_receiver_report: Option<(i64, u64, f64)>,
    /// Pictures the capture delivered, and how many went to the encoder.
    pub pictures_seen: u64,
    pub pictures_taken: u64,
    /// AAC frames sent (0 without audio).
    pub audio_frames: u64,
    /// The bitrate at the end, and how many times it was changed on the way.
    pub final_bitrate_bps: u32,
    pub bitrate_changes: u32,
    /// The encoder that was used: `amf`, `nvenc`, `x264` (empty if none opened).
    pub encoder: String,
    pub ended: String,
}

/// Why a cast could not start or had to stop. `code` is stable (for the app to word it for a
/// person); `detail` is for logs.
#[derive(Debug, Clone)]
pub struct Failure {
    /// `busy`, `blocked`, `approval_needed`, `locked`, `wrong_password`, `unsupported`,
    /// `wrong_code`, `code_required`, `receiver_changed`, `network`, `protocol`, `no_picture`,
    /// `encoder`, `capture`, `stopped` or `other`.
    pub code: String,
    pub detail: String,
}

impl Failure {
    pub fn new(code: &str, detail: impl Into<String>) -> Failure {
        Failure { code: code.into(), detail: detail.into() }
    }
}

impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.detail)
    }
}

impl From<castbay_link::Error> for Failure {
    fn from(e: castbay_link::Error) -> Failure {
        use castbay_link::Error as E;
        let code = match &e {
            E::Rejected(reason) | E::PairingFailed(reason) => reason.clone(),
            E::CodeRequired => "code_required".into(),
            E::ReceiverChanged => "receiver_changed".into(),
            E::Protocol(_) => "protocol".into(),
            E::Io(_) => "network".into(),
        };
        Failure { code, detail: e.to_string() }
    }
}

pub type OnStatus = Arc<dyn Fn(Status) + Send + Sync>;

/// A running cast.
pub struct Handle {
    stop: Arc<std::sync::atomic::AtomicBool>,
    switch: Arc<std::sync::Mutex<Option<Target>>>,
    result: std::sync::mpsc::Receiver<Result<Report, Failure>>,
    finished: std::cell::Cell<Option<Result<Report, Failure>>>,
}

/// Ends a cast from anywhere (the handle itself is busy being waited on).
#[derive(Clone)]
pub struct Stopper(Arc<std::sync::atomic::AtomicBool>);

impl Stopper {
    pub fn stop(&self) {
        self.0.store(true, std::sync::atomic::Ordering::SeqCst);
    }
}

/// Changes what a cast in progress captures, from anywhere: the receiver stays connected and the
/// picture goes on at the size it started with (a different shape gets black bars).
#[derive(Clone)]
pub struct Switcher(Arc<std::sync::Mutex<Option<Target>>>);

impl Switcher {
    /// Asks the cast to capture `target` from now on. [`Status::Switched`] or
    /// [`Status::SwitchFailed`] follows.
    pub fn switch(&self, target: Target) {
        *self.0.lock().unwrap() = Some(target);
    }
}

impl Handle {
    /// Asks the cast to end; [`Handle::join`] waits for it.
    pub fn stop(&self) {
        self.stop.store(true, std::sync::atomic::Ordering::SeqCst);
    }

    pub fn stopper(&self) -> Stopper {
        Stopper(self.stop.clone())
    }

    pub fn switcher(&self) -> Switcher {
        Switcher(self.switch.clone())
    }

    /// Waits for the cast to end and returns how it went.
    pub fn join(self) -> Result<Report, Failure> {
        if let Some(done) = self.finished.take() {
            return done;
        }
        self.result.recv().unwrap_or_else(|_| Err(Failure::new("other", "the cast thread crashed")))
    }

    /// Whether the cast has ended (without waiting).
    pub fn is_finished(&self) -> bool {
        match self.result.try_recv() {
            Ok(done) => {
                self.finished.set(Some(done));
                true
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => false,
            Err(_) => true,
        }
    }
}

/// Starts casting on its own threads. `code` is asked for the pairing code when the receiver
/// needs one (the person reads it off the TV).
#[cfg(any(windows, target_os = "linux"))]
pub fn start(options: Options, on_status: OnStatus, code: Box<dyn FnMut(&str) -> Option<String> + Send>) -> Handle {
    let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let flag = stop.clone();
    let (tx, result) = std::sync::mpsc::channel();
    let switch = Arc::new(std::sync::Mutex::new(None));
    let switch_for_engine = switch.clone();
    std::thread::Builder::new()
        .name("castbay-cast".into())
        .spawn(move || engine::run(options, on_status, code, flag, switch_for_engine, tx))
        .expect("a thread");
    Handle { stop, switch, result, finished: std::cell::Cell::new(None) }
}

#[cfg(not(any(windows, target_os = "linux")))]
pub fn start(_: Options, _: OnStatus, _: Box<dyn FnMut(&str) -> Option<String> + Send>) -> Handle {
    let (tx, result) = std::sync::mpsc::channel();
    let _ = tx.send(Err(Failure::new("other", "casting is not implemented on this system")));
    Handle { stop: Arc::new(std::sync::atomic::AtomicBool::new(true)), switch: Arc::new(std::sync::Mutex::new(None)), result, finished: std::cell::Cell::new(None) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn link_errors_become_codes_the_app_can_word() {
        use castbay_link::Error as E;
        assert_eq!(Failure::from(E::Rejected("busy".into())).code, "busy");
        assert_eq!(Failure::from(E::PairingFailed("wrong_code".into())).code, "wrong_code");
        assert_eq!(Failure::from(E::CodeRequired).code, "code_required");
        assert_eq!(Failure::from(E::ReceiverChanged).code, "receiver_changed");
        assert_eq!(Failure::from(E::Io("x".into())).code, "network");
        assert_eq!(Failure::from(E::Protocol("x".into())).code, "protocol");
    }
}
