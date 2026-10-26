//! The casting pipeline (Windows): a capture thread feeds pictures to the encoder, a repeater
//! keeps a static screen alive, a reconnector replaces a broken connection.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use castbay_capture::Target;
use castbay_encode::ffmpeg::{Codec, FfmpegEncoder};
use castbay_encode::scale::{bgra_to_nv12_fit, fit_size};
use castbay_encode::{Config, Encoded};
use castbay_link::{Event, SendResult, Session};

use crate::rate::RateControl;
use crate::{Failure, OnStatus, Options, Report, Status};

/// The encoder library inside this process when it was built in, else an FFmpeg child process.
enum Enc {
    Process(FfmpegEncoder),
    #[cfg(feature = "ffmpeg-lib")]
    Library(castbay_encode::inproc::InProcessEncoder),
}

impl Enc {
    /// Opens the chosen encoder, or with None the first that works: AMD, NVIDIA, then x264.
    fn open(config: &Config, codec: Option<Codec>) -> Result<Enc, castbay_encode::Error> {
        match codec {
            Some(codec) => Enc::new(config, codec),
            None => {
                let mut last = None;
                for codec in [Codec::Amf, Codec::Nvenc, Codec::X264] {
                    match Enc::new(config, codec) {
                        Ok(enc) => return Ok(enc),
                        Err(e) => last = Some(e),
                    }
                }
                Err(last.unwrap())
            }
        }
    }

    fn new(config: &Config, codec: Codec) -> Result<Enc, castbay_encode::Error> {
        #[cfg(feature = "ffmpeg-lib")]
        if std::env::var_os("CASTBAY_FFMPEG_PROCESS").is_none() {
            return castbay_encode::inproc::InProcessEncoder::new(config, codec).map(Enc::Library);
        }
        FfmpegEncoder::new(config, codec).map(Enc::Process)
    }

    fn encode(&mut self, nv12: &[u8], pts: i64, key: bool) -> Result<Vec<Encoded>, castbay_encode::Error> {
        match self {
            // A child process cannot be told to make a key frame; it makes them on its own timer.
            Enc::Process(e) => {
                let _ = key;
                e.encode(nv12, pts)
            }
            #[cfg(feature = "ffmpeg-lib")]
            Enc::Library(e) => e.encode(nv12, pts, key).map(|u| u.into_iter().collect()),
        }
    }
}

/// AAC-LC encoding for the cast's audio; only the in-process encoder build has one.
trait Aac: Send {
    fn config(&self) -> Vec<u8>;
    fn frame_samples(&self) -> usize;
    fn encode(&mut self, interleaved: &[f32]) -> Option<Vec<u8>>;
}

#[cfg(feature = "ffmpeg-lib")]
impl Aac for castbay_encode::inproc::AacEncoder {
    fn config(&self) -> Vec<u8> {
        castbay_encode::inproc::AacEncoder::config(self)
    }
    fn frame_samples(&self) -> usize {
        castbay_encode::inproc::AacEncoder::frame_samples(self)
    }
    fn encode(&mut self, interleaved: &[f32]) -> Option<Vec<u8>> {
        castbay_encode::inproc::AacEncoder::encode(self, interleaved).ok().flatten()
    }
}

fn open_aac() -> Option<Box<dyn Aac>> {
    #[cfg(feature = "ffmpeg-lib")]
    {
        castbay_encode::inproc::AacEncoder::new(castbay_capture::audio::SAMPLE_RATE, castbay_capture::audio::CHANNELS, 128_000)
            .ok()
            .map(|e| Box::new(e) as Box<dyn Aac>)
    }
    #[cfg(not(feature = "ffmpeg-lib"))]
    {
        None
    }
}

/// Captures what the PC plays, encodes it and sends it on the current connection until the end.
/// A device that goes away (the default output changed) is picked up again a second later.
fn run_audio(mut enc: Box<dyn Aac>, session_ref: Arc<Mutex<Option<Session>>>, over: impl Fn() -> bool, started: Instant, counter: Arc<std::sync::atomic::AtomicU64>) {
    let samples_per_frame = enc.frame_samples();
    let frame_len = samples_per_frame * castbay_capture::audio::CHANNELS as usize;
    let frame_us = samples_per_frame as u64 * 1_000_000 / castbay_capture::audio::SAMPLE_RATE as u64;
    let base_us = started.elapsed().as_micros() as u64;
    let mut pending: Vec<f32> = Vec::with_capacity(frame_len * 4);
    let mut sent: u64 = 0;
    while !over() {
        let _ = castbay_capture::audio::run_loopback(&over, |samples| {
            pending.extend_from_slice(samples);
            // Never more than about a second behind: an encoder that cannot keep up loses sound, not time.
            if pending.len() > frame_len * 48 {
                let drop = pending.len() - frame_len * 4;
                pending.drain(..drop);
            }
            while pending.len() >= frame_len {
                let chunk: Vec<f32> = pending.drain(..frame_len).collect();
                if let Some(frame) = enc.encode(&chunk) {
                    if let Some(link) = session_ref.lock().unwrap().as_ref() {
                        link.send_audio(base_us + sent * frame_us, &frame);
                    }
                    sent += 1;
                    counter.store(sent, Ordering::Relaxed);
                }
            }
        });
        for _ in 0..10 {
            if over() {
                return;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

struct State {
    encoder: Option<Enc>,
    nv12: Vec<u8>,
    have_picture: bool,
    last: Instant,
    captured: VecDeque<Instant>,
    lags_ms: Vec<f64>,
    sent: u64,
    dropped: u64,
    repeats: u64,
    bytes: u64,
    reconnects: u64,
    /// The cast cannot go on; the text says why.
    failed: Option<Failure>,
    /// The receiver ended the cast on purpose.
    ended: Option<String>,
    /// The connection broke; the reconnect thread is getting a new one.
    lost: Option<String>,
    /// The receiver asked for a key frame.
    want_key: bool,
    last_report: Option<(i64, u64, f64)>,
    /// Picks the bitrate; a change reopens the encoder (which starts with a key frame).
    rate: RateControl,
    announce_bitrate: Option<u32>,
    bitrate_changes: u32,
    simulated: bool,
    /// Pictures the capture delivered, and how many of them went to the encoder.
    frames_seen: u64,
    frames_taken: u64,
}

/// Pushes the current picture into the encoder and sends what came out.
fn push(st: &mut State, link: &Session, captured: Instant, started: Instant, config: &Config, codec: Option<Codec>, repeat: bool) {
    if st.lost.is_some() {
        return;
    }
    if st.encoder.is_none() {
        let config = Config { bitrate_bps: st.rate.current(), ..config.clone() };
        match Enc::open(&config, codec) {
            Ok(e) => st.encoder = Some(e),
            Err(e) => {
                st.failed = Some(Failure::new("encoder", e.to_string()));
                return;
            }
        }
    }
    let pts = captured.duration_since(started).as_micros() as i64;
    let nv12 = std::mem::take(&mut st.nv12);
    let result = st.encoder.as_mut().unwrap().encode(&nv12, pts, link.wants_key_frame() || st.want_key);
    st.want_key = false;
    st.nv12 = nv12;
    st.captured.push_back(captured);
    st.last = Instant::now();
    if repeat {
        st.repeats += 1;
    }
    match result {
        Ok(units) => {
            for unit in units {
                if let Some(at) = st.captured.pop_front() {
                    st.lags_ms.push(at.elapsed().as_secs_f64() * 1000.0);
                }
                st.bytes += unit.data.len() as u64;
                match link.send_video(unit.pts_us as u64, unit.key, &unit.data) {
                    SendResult::Sent => st.sent += 1,
                    SendResult::Dropped => {
                        st.dropped += 1;
                        st.rate.dropped(Instant::now());
                    }
                    SendResult::Closed => {
                        st.lost.get_or_insert_with(|| "connection lost".into());
                    }
                }
            }
        }
        Err(e) => st.failed = Some(Failure::new("encoder", e.to_string())),
    }
    // For trying the response without a bad network: a burst of drops five seconds in.
    if !st.simulated && std::env::var_os("CASTBAY_SIMULATE_CONGESTION").is_some() && started.elapsed() > Duration::from_secs(5) {
        st.simulated = true;
        for _ in 0..8 {
            st.rate.dropped(Instant::now());
        }
    }
    if let Some(bps) = st.rate.adjust(Instant::now()) {
        st.encoder = None;
        st.want_key = true;
        st.announce_bitrate = Some(bps);
        st.bitrate_changes += 1;
    }
}

/// Runs the cast and delivers its result through `done`. The thread that calls this ends up
/// running the capture itself (the capture library wants to own its thread's message loop), so
/// a supervisor thread watches for the end and reports it.
pub fn run(options: Options, on_status: OnStatus, code: Box<dyn FnMut(&str) -> Option<String> + Send>, stop: Arc<AtomicBool>, done: std::sync::mpsc::Sender<Result<Report, Failure>>) {
    if let Err(e) = setup_and_run(options, on_status, code, stop, done.clone()) {
        let _ = done.send(Err(e));
    }
}

fn setup_and_run(options: Options, on_status: OnStatus, code: Box<dyn FnMut(&str) -> Option<String> + Send>, stop: Arc<AtomicBool>, done: std::sync::mpsc::Sender<Result<Report, Failure>>) -> Result<(), Failure> {
    let Options { addr, receiver_id, name, password, target, fps, bitrate_bps, codec, audio, max_size, data_dir, max_duration } = options;
    castbay_capture::set_data_dir(&data_dir);
    on_status(Status::Connecting);
    let runtime = tokio::runtime::Runtime::new().map_err(|e| Failure::new("other", e.to_string()))?;

    // The first picture fixes the size, so the offer waits for it.
    let first = Arc::new(Mutex::new(None::<(u32, u32)>));
    {
        let (first, target) = (first.clone(), target.clone());
        std::thread::spawn(move || {
            let _ = castbay_capture::run(&target, || false, move |frame| {
                *first.lock().unwrap() = Some((frame.width & !1, frame.height & !1));
                false
            });
        });
    }
    let waited = Instant::now();
    let (width, height) = loop {
        if let Some((w, h)) = *first.lock().unwrap() {
            break fit_size(w, h, max_size.0, max_size.1);
        }
        if stop.load(Ordering::SeqCst) {
            return Err(Failure::new("stopped", "stopped"));
        }
        if waited.elapsed() > Duration::from_secs(10) {
            return Err(Failure::new("no_picture", "no picture from the screen (is it locked, or the window gone?)"));
        }
        std::thread::sleep(Duration::from_millis(50));
    };

    let identity = castbay_link::Identity::load_or_create(&data_dir).map_err(Failure::from)?;
    let mut pins = castbay_link::Pins::load(&data_dir);
    let aac = if audio { open_aac() } else { None };
    let audio_offer = aac.as_ref().map(|enc| castbay_link::AudioOffer {
        sample_rate: castbay_capture::audio::SAMPLE_RATE,
        channels: castbay_capture::audio::CHANNELS,
        config_hex: enc.config().iter().map(|b| format!("{b:02x}")).collect(),
    });
    let link_config = castbay_link::Config {
        name,
        password,
        width,
        height,
        fps,
        bitrate_bps,
        source: if matches!(target, Target::Window(_)) { "window" } else { "screen" }.into(),
        audio: audio_offer,
    };
    let mut ask = {
        let (on_status, mut code) = (on_status.clone(), code);
        move |expected: &str| -> Option<String> {
            on_status(Status::NeedsCode);
            code(expected)
        }
    };
    let session = runtime
        .block_on(castbay_link::connect(addr, &identity, &mut pins, receiver_id.as_deref(), &link_config, &mut ask))
        .map_err(Failure::from)?;
    on_status(Status::Casting { receiver: session.receiver_name.clone(), width, height });
    // Awake for as long as this thread (which ends up running the capture) casts.
    let _awake = crate::power::Awake::new();
    let audio_accepted = session.audio_accepted;
    on_status(Status::Bitrate(bitrate_bps));

    let started = Instant::now();
    let interval = Duration::from_micros(1_000_000 / fps as u64);
    let encoder_config = Config { width, height, fps, bitrate_bps, gop: fps * 2 };
    let finish = Arc::new(AtomicBool::new(false));
    let over = {
        let (stop, finish) = (stop.clone(), finish.clone());
        move || stop.load(Ordering::SeqCst) || finish.load(Ordering::SeqCst) || max_duration.is_some_and(|d| started.elapsed() > d)
    };
    let session_ref = Arc::new(Mutex::new(Some(session)));
    let state = Arc::new(Mutex::new(State {
        encoder: None,
        nv12: vec![0; (width * height * 3 / 2) as usize],
        have_picture: false,
        last: Instant::now(),
        captured: VecDeque::new(),
        lags_ms: Vec::new(),
        sent: 0,
        dropped: 0,
        repeats: 0,
        bytes: 0,
        reconnects: 0,
        failed: None,
        ended: None,
        lost: None,
        want_key: false,
        last_report: None,
        rate: RateControl::new(bitrate_bps, Instant::now()),
        announce_bitrate: None,
        bitrate_changes: 0,
        simulated: false,
        frames_seen: 0,
        frames_taken: 0,
    }));

    let audio_frames = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let audio_thread = match aac {
        Some(enc) if audio_accepted => {
            let (session_ref, over, counter) = (session_ref.clone(), over.clone(), audio_frames.clone());
            Some(std::thread::spawn(move || run_audio(enc, session_ref, over, started, counter)))
        }
        _ => None,
    };

    // Tells the window when the screen gets locked or unlocked.
    let lock_watcher = {
        let (over, on_status) = (over.clone(), on_status.clone());
        std::thread::spawn(move || {
            let mut locked = false;
            while !over() {
                let now = crate::power::screen_locked();
                if now != locked {
                    locked = now;
                    on_status(Status::ScreenLocked(locked));
                }
                std::thread::sleep(Duration::from_millis(500));
            }
        })
    };

    // Reads what the receiver says, and repeats the last picture on a static screen so the
    // stream stays alive.
    let repeater = {
        let (state, session_ref, config, over, on_status) = (state.clone(), session_ref.clone(), encoder_config.clone(), over.clone(), on_status.clone());
        std::thread::spawn(move || loop {
            std::thread::sleep(interval / 4);
            let mut st = state.lock().unwrap();
            if over() || st.failed.is_some() || st.ended.is_some() {
                return;
            }
            let mut guard = session_ref.lock().unwrap();
            let Some(link) = guard.as_mut() else { continue };
            while let Some(event) = link.next_event() {
                match event {
                    Event::Closed(reason) if matches!(reason.as_str(), "stopped_by_receiver" | "replaced" | "receiver_stopped") => st.ended = Some(reason),
                    Event::Closed(reason) => {
                        st.lost.get_or_insert(reason);
                    }
                    Event::Stats { decode_ms, dropped, fps } => {
                        st.last_report = Some((decode_ms, dropped, fps));
                        on_status(Status::Receiver { decode_ms, dropped, fps });
                    }
                    Event::KeyFrameRequested => st.want_key = true,
                }
            }
            if st.have_picture && st.last.elapsed() >= interval {
                push(&mut st, link, Instant::now(), started, &config, codec, true);
                if let Some(bps) = st.announce_bitrate.take() {
                    on_status(Status::Bitrate(bps));
                }
            }
        })
    };

    // A broken connection is replaced by a new one (the pairing is remembered, so no code is
    // needed); the encoder starts over, which begins the new stream with a key frame.
    let reconnector = {
        let (state, session_ref, handle, over, on_status) = (state.clone(), session_ref.clone(), runtime.handle().clone(), over.clone(), on_status.clone());
        std::thread::spawn(move || {
            let mut pins = pins;
            loop {
                std::thread::sleep(Duration::from_millis(200));
                let reason = {
                    let st = state.lock().unwrap();
                    if over() || st.failed.is_some() || st.ended.is_some() {
                        return;
                    }
                    match &st.lost {
                        Some(reason) => reason.clone(),
                        None => continue,
                    }
                };
                if let Some(old) = session_ref.lock().unwrap().take() {
                    handle.block_on(old.close());
                }
                on_status(Status::Reconnecting(reason));
                let mut none = |_: &str| -> Option<String> { None };
                let mut renewed = None;
                for wait in [1u64, 2, 3, 5, 5, 5, 5, 5, 5, 5] {
                    for _ in 0..wait * 10 {
                        if over() {
                            return;
                        }
                        std::thread::sleep(Duration::from_millis(100));
                    }
                    match handle.block_on(castbay_link::connect(addr, &identity, &mut pins, receiver_id.as_deref(), &link_config, &mut none)) {
                        Ok(session) => {
                            renewed = Some(session);
                            break;
                        }
                        Err(_) => {}
                    }
                }
                let mut st = state.lock().unwrap();
                match renewed {
                    Some(session) => {
                        *session_ref.lock().unwrap() = Some(session);
                        st.encoder = None;
                        st.captured.clear();
                        st.lost = None;
                        st.want_key = true;
                        st.reconnects += 1;
                        on_status(Status::Reconnected);
                    }
                    None => st.failed = Some(Failure::new("network", "the receiver could not be reached again")),
                }
            }
        })
    };

    // A supervisor waits for the end, tears down and reports; this thread runs the capture.
    let capture_done = Arc::new(AtomicBool::new(false));
    let supervisor = {
        let (state, session_ref, over, capture_done, finish, on_status, stop) = (state.clone(), session_ref.clone(), over.clone(), capture_done.clone(), finish.clone(), on_status.clone(), stop.clone());
        std::thread::spawn(move || {
            loop {
                std::thread::sleep(Duration::from_millis(100));
                let st = state.lock().unwrap();
                if over() || st.failed.is_some() || st.ended.is_some() || capture_done.load(Ordering::SeqCst) {
                    break;
                }
            }
            finish.store(true, Ordering::SeqCst);
            let _ = repeater.join();
            let _ = reconnector.join();
            if let Some(thread) = audio_thread {
                let _ = thread.join();
            }
            let _ = lock_watcher.join();
            // The capture ends with its next picture; a screen that delivers none is left behind.
            for _ in 0..30 {
                if capture_done.load(Ordering::SeqCst) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(100));
            }
            if let Some(link) = session_ref.lock().unwrap().take() {
                runtime.block_on(link.close());
            }
            let _ = done.send(report(&state, started, &stop, &on_status, audio_frames.load(Ordering::Relaxed)));
        })
    };
    let result = {
        let (state, session_ref, over) = (state.clone(), session_ref.clone(), over.clone());
        let capture_status = on_status.clone();
        let stop_when = over.clone();
        castbay_capture::run(&target, move || stop_when(), move |frame| {
            if over() {
                return false;
            }
            let mut st = state.lock().unwrap();
            if st.failed.is_some() || st.ended.is_some() {
                return false;
            }
            st.frames_seen += 1;
            if st.last.elapsed() + interval / 4 >= interval || !st.have_picture {
                if frame.width >= 2 && frame.height >= 2 {
                    let mut nv12 = std::mem::take(&mut st.nv12);
                    bgra_to_nv12_fit(frame.data, frame.stride, frame.width as usize, frame.height as usize, width as usize, height as usize, &mut nv12);
                    st.nv12 = nv12;
                    st.have_picture = true;
                    st.frames_taken += 1;
                    if let Some(link) = session_ref.lock().unwrap().as_ref() {
                        push(&mut st, link, Instant::now(), started, &encoder_config, codec, false);
                        if let Some(bps) = st.announce_bitrate.take() {
                            capture_status(Status::Bitrate(bps));
                        }
                    }
                }
            }
            true
        })
    };
    match result {
        Ok(castbay_capture::Ended::SourceClosed) => {
            state.lock().unwrap().ended.get_or_insert_with(|| "the captured window or screen went away".into());
        }
        Err(e) => {
            state.lock().unwrap().failed.get_or_insert_with(|| Failure::new("capture", e.to_string()));
        }
        Ok(_) => {}
    }
    capture_done.store(true, Ordering::SeqCst);
    let _ = supervisor.join();
    Ok(())
}

/// Builds the report of a finished cast and announces its end.
fn report(state: &Arc<Mutex<State>>, started: Instant, stop: &Arc<AtomicBool>, on_status: &OnStatus, audio_frames: u64) -> Result<Report, Failure> {
    let mut st = state.lock().unwrap();
    let ended = if let Some(failure) = st.failed.clone() {
        failure.detail
    } else if let Some(reason) = st.ended.clone() {
        reason
    } else if stop.load(Ordering::SeqCst) {
        "stopped".into()
    } else {
        "finished".into()
    };
    on_status(Status::Ended(ended.clone()));
    let mut lags = std::mem::take(&mut st.lags_ms);
    lags.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let report = Report {
        units_sent: st.sent,
        units_dropped: st.dropped,
        repeated_pictures: st.repeats,
        reconnects: st.reconnects,
        bytes: st.bytes,
        seconds: started.elapsed().as_secs_f64(),
        lags_ms: lags,
        last_receiver_report: st.last_report,
        pictures_seen: st.frames_seen,
        pictures_taken: st.frames_taken,
        audio_frames,
        final_bitrate_bps: st.rate.current(),
        bitrate_changes: st.bitrate_changes,
        ended: ended.clone(),
    };
    match &st.failed {
        Some(reason) => Err(reason.clone()),
        None => Ok(report),
    }
}
