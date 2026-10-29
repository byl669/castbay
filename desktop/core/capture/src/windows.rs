//! Windows Graphics Capture, through the `windows-capture` crate for now.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use windows_capture::capture::{Context, GraphicsCaptureApiHandler};
use windows_capture::frame::Frame as WcFrame;
use windows_capture::graphics_capture_api::InternalCaptureControl;
use windows_capture::monitor::Monitor;
use windows_capture::settings::{
    ColorFormat, CursorCaptureSettings, DirtyRegionSettings, DrawBorderSettings,
    MinimumUpdateIntervalSettings, SecondaryWindowSettings, Settings,
};
use windows_capture::window::Window;

use crate::{Ended, Error, Frame, Target};

type Callback = Box<dyn FnMut(Frame<'_>) -> bool + Send>;

struct Flags {
    callback: Callback,
    started: Instant,
    closed: Arc<AtomicBool>,
    /// The capture is over: the callback said stop, or the source closed.
    done: Arc<AtomicBool>,
}

struct Handler {
    flags: Flags,
}

impl GraphicsCaptureApiHandler for Handler {
    type Flags = Arc<std::sync::Mutex<Option<Flags>>>;
    type Error = Box<dyn std::error::Error + Send + Sync>;

    fn new(ctx: Context<Self::Flags>) -> Result<Self, Self::Error> {
        let flags = ctx.flags.lock().unwrap().take().ok_or("capture flags missing")?;
        Ok(Handler { flags })
    }

    fn on_frame_arrived(
        &mut self,
        frame: &mut WcFrame,
        control: InternalCaptureControl,
    ) -> Result<(), Self::Error> {
        let width = frame.width();
        let height = frame.height();
        let mut buffer = frame.buffer()?;
        let stride = buffer.row_pitch() as usize;
        let data = buffer.as_raw_buffer();
        let keep_going = (self.flags.callback)(Frame {
            width,
            height,
            stride,
            data,
            timestamp: self.flags.started.elapsed(),
        });
        if !keep_going {
            self.flags.done.store(true, Ordering::SeqCst);
            control.stop();
        }
        Ok(())
    }

    fn on_closed(&mut self) -> Result<(), Self::Error> {
        self.flags.closed.store(true, Ordering::SeqCst);
        self.flags.done.store(true, Ordering::SeqCst);
        Ok(())
    }
}

/// Captures `target` until `on_frame` returns false, `stop` says so (looked at every 30 ms, so a
/// window that shows nothing new can be left too) or the source closes; frames are only valid
/// inside the callback.
pub fn run(
    target: &Target,
    stop: impl Fn() -> bool + Send + 'static,
    on_frame: impl FnMut(Frame<'_>) -> bool + Send + 'static,
) -> Result<Ended, Error> {
    let closed = Arc::new(AtomicBool::new(false));
    let done = Arc::new(AtomicBool::new(false));
    let flags = Arc::new(std::sync::Mutex::new(Some(Flags {
        callback: Box::new(on_frame),
        started: Instant::now(),
        closed: closed.clone(),
        done: done.clone(),
    })));

    macro_rules! start {
        ($item:expr) => {{
            let settings = Settings::new(
                $item,
                CursorCaptureSettings::WithCursor,
                DrawBorderSettings::WithoutBorder,
                SecondaryWindowSettings::Default,
                MinimumUpdateIntervalSettings::Default,
                DirtyRegionSettings::Default,
                ColorFormat::Bgra8,
                flags,
            );
            Handler::start_free_threaded(settings).map_err(|e| Error::Platform(e.to_string()))?
        }};
    }

    let control = match target {
        Target::Monitor(index) => {
            let monitors = Monitor::enumerate().map_err(|e| Error::Platform(e.to_string()))?;
            let monitor = monitors
                .get(*index)
                .copied()
                .ok_or_else(|| Error::NotFound(format!("monitor {index}")))?;
            start!(monitor)
        }
        Target::Window(title) => {
            let window = Window::from_contains_name(title)
                .map_err(|_| Error::NotFound(format!("window containing \"{title}\"")))?;
            start!(window)
        }
    };

    // The capture runs on a thread of its own; this one waits for its end or for the stop.
    while !done.load(Ordering::SeqCst) && !stop() {
        std::thread::sleep(Duration::from_millis(30));
    }
    let _ = control.stop();

    Ok(if closed.load(Ordering::SeqCst) { Ended::SourceClosed } else { Ended::Stopped })
}

/// Names of the monitors, for the picker and the CLI.
pub fn monitors() -> Result<Vec<String>, Error> {
    let monitors = Monitor::enumerate().map_err(|e| Error::Platform(e.to_string()))?;
    Ok(monitors
        .iter()
        .enumerate()
        .map(|(i, m)| {
            let (w, h) = (m.width().unwrap_or(0), m.height().unwrap_or(0));
            let hz = m.refresh_rate().unwrap_or(0);
            format!("{i}: {w}x{h} @ {hz} Hz")
        })
        .collect())
}

/// Titles of the windows that can be captured, for the picker.
pub fn windows() -> Result<Vec<String>, Error> {
    let windows = Window::enumerate().map_err(|e| Error::Platform(e.to_string()))?;
    let mut titles: Vec<String> = windows
        .iter()
        .filter(|w| w.is_valid())
        .filter_map(|w| w.title().ok())
        .map(|t| t.trim().to_string())
        // Console windows are titled with their program's path, and the input method has a window of its own.
        .filter(|t| !t.is_empty() && !t.to_lowercase().ends_with(".exe") && t != "Windows 输入体验" && t != "Program Manager")
        .collect();
    titles.sort();
    titles.dedup();
    Ok(titles)
}

#[allow(dead_code)]
fn _unused(_: Duration) {}
