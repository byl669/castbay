//! Casting a screen or window to a CastBay receiver: captures, converts, encodes and sends,
//! repeating the last picture on a static screen and replacing a broken connection. The
//! command line and the app both drive this.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

pub use castbay_capture::Target;
pub use castbay_encode::ffmpeg::Codec;

#[cfg(windows)]
mod engine;

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
    /// The encoder to use; None picks the first that works: AMD, NVIDIA, then x264 on the CPU.
    pub codec: Option<Codec>,
    /// Where the identity and the pinned receivers live.
    pub data_dir: PathBuf,
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
    pub ended: String,
}

pub type OnStatus = Arc<dyn Fn(Status) + Send + Sync>;

/// A running cast.
pub struct Handle {
    stop: Arc<std::sync::atomic::AtomicBool>,
    result: std::sync::mpsc::Receiver<Result<Report, String>>,
    finished: std::cell::Cell<Option<Result<Report, String>>>,
}

/// Ends a cast from anywhere (the handle itself is busy being waited on).
#[derive(Clone)]
pub struct Stopper(Arc<std::sync::atomic::AtomicBool>);

impl Stopper {
    pub fn stop(&self) {
        self.0.store(true, std::sync::atomic::Ordering::SeqCst);
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

    /// Waits for the cast to end and returns how it went.
    pub fn join(self) -> Result<Report, String> {
        if let Some(done) = self.finished.take() {
            return done;
        }
        self.result.recv().unwrap_or_else(|_| Err("the cast thread crashed".into()))
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
#[cfg(windows)]
pub fn start(options: Options, on_status: OnStatus, code: Box<dyn FnMut(&str) -> Option<String> + Send>) -> Handle {
    let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let flag = stop.clone();
    let (tx, result) = std::sync::mpsc::channel();
    std::thread::Builder::new()
        .name("castbay-cast".into())
        .spawn(move || engine::run(options, on_status, code, flag, tx))
        .expect("a thread");
    Handle { stop, result, finished: std::cell::Cell::new(None) }
}

#[cfg(not(windows))]
pub fn start(_: Options, _: OnStatus, _: Box<dyn FnMut(&str) -> Option<String> + Send>) -> Handle {
    let (tx, result) = std::sync::mpsc::channel();
    let _ = tx.send(Err("casting is only implemented on Windows so far".to_string()));
    Handle { stop: Arc::new(std::sync::atomic::AtomicBool::new(true)), result, finished: std::cell::Cell::new(None) }
}
