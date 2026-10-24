//! `castbay-cast`: a command-line sender used for tests and benchmarks.

use std::time::{Duration, Instant};

use castbay_capture::Target;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("list") => list(),
        Some("bench-capture") => bench_capture(&args[1..]),
        Some("bench-encode") => bench_encode(&args[1..]),
        Some("send") => send(&args[1..]),
        _ => {
            eprintln!("usage: castbay-cast list | send --host H [--port P] ... | bench-capture|bench-encode [--monitor N | --window TITLE] [--seconds S] [--fps F] [--bitrate BPS] [--sw]");
            std::process::exit(2);
        }
    }
}

#[cfg(windows)]
fn list() {
    match castbay_capture::windows::monitors() {
        Ok(items) => items.iter().for_each(|m| println!("monitor {m}")),
        Err(error) => eprintln!("{error}"),
    }
}

#[cfg(not(windows))]
fn list() {
    eprintln!("capture is only implemented on Windows so far");
}

fn option<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).map(String::as_str)
}

#[cfg(windows)]
fn bench_capture(args: &[String]) {
    let target = match option(args, "--window") {
        Some(title) => Target::Window(title.to_string()),
        None => Target::Monitor(option(args, "--monitor").and_then(|v| v.parse().ok()).unwrap_or(0)),
    };
    let seconds: u64 = option(args, "--seconds").and_then(|v| v.parse().ok()).unwrap_or(10);
    println!("capturing {target:?} for {seconds} s");

    #[derive(Default)]
    struct Stats {
        frames: u64,
        bytes: u64,
        size: (u32, u32),
        max_gap: Duration,
        touch: Duration,
        last: Option<Instant>,
    }
    let started = Instant::now();
    let stats = std::sync::Arc::new(std::sync::Mutex::new(Stats::default()));
    let shared = stats.clone();
    let limit = Duration::from_secs(seconds);
    let result = castbay_capture::windows::run(&target, move |frame| {
        let now = Instant::now();
        let mut state = shared.lock().unwrap();
        // The cost of getting at the picture on the CPU: touch every row once.
        let t = Instant::now();
        let mut sum = 0u8;
        for row in frame.data.chunks(frame.stride).take(frame.height as usize) {
            sum = sum.wrapping_add(row[0]);
        }
        std::hint::black_box(sum);
        state.touch += t.elapsed();
        state.frames += 1;
        state.bytes += frame.data.len() as u64;
        state.size = (frame.width, frame.height);
        if let Some(last) = state.last {
            state.max_gap = state.max_gap.max(now.saturating_duration_since(last));
        }
        state.last = Some(now);
        frame.timestamp < limit
    });
    let Stats { frames, bytes, size, max_gap, touch, .. } = std::mem::take(&mut *stats.lock().unwrap());
    let elapsed = started.elapsed().as_secs_f64();
    println!("ended: {result:?}");
    println!("frames: {frames} in {elapsed:.1} s = {:.1} fps", frames as f64 / elapsed);
    println!("size: {}x{}, {:.0} MB/s of pixels", size.0, size.1, bytes as f64 / elapsed / 1e6);
    println!("longest gap between frames: {} ms", max_gap.as_millis());
    println!("row touch per frame: {:.2} ms", touch.as_secs_f64() * 1000.0 / frames.max(1) as f64);
}

#[cfg(not(windows))]
fn bench_capture(_: &[String]) {
    eprintln!("capture is only implemented on Windows so far");
}

/// CPU time this process has used so far.
#[cfg(windows)]
fn cpu_time() -> Duration {
    use windows::Win32::Foundation::FILETIME;
    use windows::Win32::System::Threading::{GetCurrentProcess, GetProcessTimes};
    let (mut create, mut exit, mut kernel, mut user) = (FILETIME::default(), FILETIME::default(), FILETIME::default(), FILETIME::default());
    unsafe {
        let _ = GetProcessTimes(GetCurrentProcess(), &mut create, &mut exit, &mut kernel, &mut user);
    }
    let ticks = |t: FILETIME| ((t.dwHighDateTime as u64) << 32) | t.dwLowDateTime as u64;
    Duration::from_nanos((ticks(kernel) + ticks(user)) * 100)
}

#[cfg(windows)]
fn bench_encode(args: &[String]) {
    use castbay_encode::windows::{Kind, MfEncoder};
    use castbay_encode::{bgra_to_nv12, Config};

    let target = match option(args, "--window") {
        Some(title) => Target::Window(title.to_string()),
        None => Target::Monitor(option(args, "--monitor").and_then(|v| v.parse().ok()).unwrap_or(0)),
    };
    let seconds: u64 = option(args, "--seconds").and_then(|v| v.parse().ok()).unwrap_or(10);
    let fps: u32 = option(args, "--fps").and_then(|v| v.parse().ok()).unwrap_or(30);
    let bitrate: u32 = option(args, "--bitrate").and_then(|v| v.parse().ok()).unwrap_or(8_000_000);
    let kind = if args.iter().any(|a| a == "--sw") { Kind::Software } else { Kind::Hardware };
    let codec = option(args, "--encoder").map(|n| castbay_encode::ffmpeg::Codec::parse(n).expect("encoder: amf, nvenc, qsv or x264"));
    println!("encoding {target:?} for {seconds} s at {fps} fps, {} kbit/s, {}", bitrate / 1000, codec.map(|c| format!("ffmpeg {c:?}")).unwrap_or(format!("Media Foundation {kind:?}")));

    #[derive(Default)]
    struct Stats {
        offered: u64,
        encoded: u64,
        units: u64,
        keys: u64,
        bytes: u64,
        convert: Duration,
        encode: Duration,
        worst_encode: Duration,
        size: (u32, u32),
        name: String,
        error: Option<String>,
        child_cpu: Duration,
    }
    let stats = std::sync::Arc::new(std::sync::Mutex::new(Stats::default()));
    let shared = stats.clone();
    let limit = Duration::from_secs(seconds);
    let interval = Duration::from_micros(1_000_000 / fps as u64);
    enum Any {
        Mf(MfEncoder),
        Ff(castbay_encode::ffmpeg::FfmpegEncoder),
    }
    let mut encoder: Option<Any> = None;
    let mut nv12: Vec<u8> = Vec::new();
    let mut next = Duration::ZERO;
    let cpu_before = cpu_time();
    let started = Instant::now();
    let result = castbay_capture::windows::run(&target, move |frame| {
        let mut st = shared.lock().unwrap();
        st.offered += 1;
        if frame.timestamp < next {
            return frame.timestamp < limit;
        }
        next = next.max(frame.timestamp.saturating_sub(interval)) + interval;
        let (w, h) = ((frame.width & !1) as usize, (frame.height & !1) as usize);
        if encoder.is_none() {
            let config = Config { width: w as u32, height: h as u32, fps, bitrate_bps: bitrate, gop: fps * 2 };
            let made = match codec {
                Some(c) => castbay_encode::ffmpeg::FfmpegEncoder::new(&config, c).map(Any::Ff),
                None => MfEncoder::new(config, kind).map(Any::Mf),
            };
            match made {
                Ok(e) => {
                    st.name = match &e {
                        Any::Mf(m) => m.name.clone(),
                        Any::Ff(f) => f.name.clone(),
                    };
                    st.size = (w as u32, h as u32);
                    nv12 = vec![0; w * h * 3 / 2];
                    encoder = Some(e);
                }
                Err(e) => {
                    st.error = Some(e.to_string());
                    return false;
                }
            }
        }
        let t = Instant::now();
        bgra_to_nv12(frame.data, frame.stride, w, h, &mut nv12);
        st.convert += t.elapsed();
        let t = Instant::now();
        let pts = frame.timestamp.as_micros() as i64;
        let result = match encoder.as_mut().unwrap() {
            Any::Mf(e) => e.encode(&nv12, pts),
            Any::Ff(e) => e.encode(&nv12, pts),
        };
        let took = t.elapsed();
        st.encode += took;
        st.worst_encode = st.worst_encode.max(took);
        st.encoded += 1;
        match result {
            Ok(units) => {
                for unit in units {
                    st.units += 1;
                    st.bytes += unit.data.len() as u64;
                    st.keys += unit.key as u64;
                }
            }
            Err(e) => {
                st.error = Some(e.to_string());
                return false;
            }
        }
        if frame.timestamp >= limit {
            if let Some(Any::Ff(e)) = &encoder {
                st.child_cpu = e.child_cpu();
            }
        }
        frame.timestamp < limit
    });
    let elapsed = started.elapsed().as_secs_f64();
    let st_cpu = stats.lock().unwrap().child_cpu;
    let cpu = cpu_time() - cpu_before + st_cpu;
    let st = stats.lock().unwrap();
    println!("ended: {result:?}");
    if let Some(e) = &st.error {
        println!("error: {e}");
    }
    println!("encoder: {} ({}x{})", st.name, st.size.0, st.size.1);
    println!("pictures offered by capture: {}, encoded: {}, units out: {}, key frames: {}", st.offered, st.encoded, st.units, st.keys);
    println!("rate: {:.1} fps in, {:.1} fps out, {:.2} Mbit/s", st.encoded as f64 / elapsed, st.units as f64 / elapsed, st.bytes as f64 * 8.0 / elapsed / 1e6);
    let n = st.encoded.max(1) as f64;
    println!("per picture: convert {:.2} ms, encode {:.2} ms (worst {:.1} ms)", st.convert.as_secs_f64() * 1000.0 / n, st.encode.as_secs_f64() * 1000.0 / n, st.worst_encode.as_secs_f64() * 1000.0);
    println!("CPU (this process + ffmpeg): {:.0} % of one core, {:.0} % of this machine ({} threads)", cpu.as_secs_f64() / elapsed * 100.0, cpu.as_secs_f64() / elapsed * 100.0 / std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1) as f64, std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1));
}

#[cfg(not(windows))]
fn bench_encode(_: &[String]) {
    eprintln!("encoding is only implemented on Windows so far");
}

/// Captures, encodes and sends raw Annex B over TCP to the spike receiver (no security: a
/// measuring tool only). Repeats the last picture when the screen is static.
#[cfg(windows)]
fn send(args: &[String]) {
    use castbay_encode::ffmpeg::{Codec, FfmpegEncoder};
    use castbay_encode::{bgra_to_nv12, Config};
    use std::io::Write;
    use std::sync::{Arc, Mutex};

    let Some(host) = option(args, "--host") else {
        eprintln!("send needs --host");
        std::process::exit(2);
    };
    let port: u16 = option(args, "--port").and_then(|v| v.parse().ok()).unwrap_or(7600);
    let target = match option(args, "--window") {
        Some(title) => Target::Window(title.to_string()),
        None => Target::Monitor(option(args, "--monitor").and_then(|v| v.parse().ok()).unwrap_or(0)),
    };
    let seconds: u64 = option(args, "--seconds").and_then(|v| v.parse().ok()).unwrap_or(30);
    let fps: u32 = option(args, "--fps").and_then(|v| v.parse().ok()).unwrap_or(30);
    let bitrate: u32 = option(args, "--bitrate").and_then(|v| v.parse().ok()).unwrap_or(8_000_000);
    let codec = Codec::parse(option(args, "--encoder").unwrap_or("amf")).expect("encoder: amf, nvenc, qsv or x264");
    let socket = std::net::TcpStream::connect((host, port)).expect("cannot connect to the receiver");
    socket.set_nodelay(true).ok();
    println!("sending {target:?} to {host}:{port} for {seconds} s at {fps} fps, {} kbit/s, {codec:?}", bitrate / 1000);

    struct State {
        encoder: Option<FfmpegEncoder>,
        nv12: Vec<u8>,
        have_picture: bool,
        last: Instant,
        socket: std::net::TcpStream,
        /// When each queued picture was captured, in the order they went into the encoder.
        captured: std::collections::VecDeque<Instant>,
        lags_ms: Vec<f64>,
        sent: u64,
        repeats: u64,
        bytes: u64,
        failed: bool,
        size: (usize, usize),
    }
    let started = Instant::now();
    let interval = Duration::from_micros(1_000_000 / fps as u64);
    let state = Arc::new(Mutex::new(State {
        encoder: None,
        nv12: Vec::new(),
        have_picture: false,
        last: started,
        socket,
        captured: Default::default(),
        lags_ms: Vec::new(),
        sent: 0,
        repeats: 0,
        bytes: 0,
        failed: false,
        size: (0, 0),
    }));

    // Pushes the current NV12 picture into the encoder and sends whatever came out.
    fn push(st: &mut State, captured: Instant, started: Instant, fps: u32, bitrate: u32, codec: Codec, repeat: bool) {
        if st.encoder.is_none() {
            let config = Config { width: st.size.0 as u32, height: st.size.1 as u32, fps, bitrate_bps: bitrate, gop: fps * 2 };
            match FfmpegEncoder::new(&config, codec) {
                Ok(e) => st.encoder = Some(e),
                Err(e) => {
                    eprintln!("{e}");
                    st.failed = true;
                    return;
                }
            }
        }
        let pts = captured.duration_since(started).as_micros() as i64;
        let nv12 = std::mem::take(&mut st.nv12);
        let result = st.encoder.as_mut().unwrap().encode(&nv12, pts);
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
                    if st.socket.write_all(&unit.data).is_err() {
                        st.failed = true;
                        return;
                    }
                    st.sent += 1;
                }
            }
            Err(e) => {
                eprintln!("{e}");
                st.failed = true;
            }
        }
    }

    // Static screens deliver few pictures: send the last one again so the stream stays alive.
    let repeater = {
        let state = state.clone();
        std::thread::spawn(move || loop {
            std::thread::sleep(interval / 2);
            let mut st = state.lock().unwrap();
            if st.failed || started.elapsed() > Duration::from_secs(seconds) + Duration::from_secs(1) {
                return;
            }
            if st.have_picture && st.last.elapsed() > interval * 3 / 2 {
                push(&mut st, Instant::now(), started, fps, bitrate, codec, true);
            }
        })
    };

    let shared = state.clone();
    let limit = Duration::from_secs(seconds);
    let result = castbay_capture::windows::run(&target, move |frame| {
        let mut st = shared.lock().unwrap();
        if st.failed {
            return false;
        }
        if st.last.elapsed() + interval / 4 >= interval || !st.have_picture {
            let (w, h) = ((frame.width & !1) as usize, (frame.height & !1) as usize);
            if st.size != (w, h) {
                st.size = (w, h);
                st.nv12 = vec![0; w * h * 3 / 2];
            }
            let mut nv12 = std::mem::take(&mut st.nv12);
            bgra_to_nv12(frame.data, frame.stride, w, h, &mut nv12);
            st.nv12 = nv12;
            st.have_picture = true;
            push(&mut st, Instant::now(), started, fps, bitrate, codec, false);
        }
        frame.timestamp < limit && !st.failed
    });
    let _ = repeater.join();
    let mut st = state.lock().unwrap();
    let elapsed = started.elapsed().as_secs_f64();
    println!("ended: {result:?}");
    println!("units sent: {} ({} repeated pictures), {:.2} Mbit/s, {:.1} fps", st.sent, st.repeats, st.bytes as f64 * 8.0 / elapsed / 1e6, st.sent as f64 / elapsed);
    st.lags_ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
    if !st.lags_ms.is_empty() {
        let at = |q: f64| st.lags_ms[((st.lags_ms.len() - 1) as f64 * q) as usize];
        println!("captured to ready to send: median {:.0} ms, 95th {:.0} ms, worst {:.0} ms (includes about one frame of waiting for the next delimiter)", at(0.5), at(0.95), at(1.0));
    }
}

#[cfg(not(windows))]
fn send(_: &[String]) {
    eprintln!("sending is only implemented on Windows so far");
}
