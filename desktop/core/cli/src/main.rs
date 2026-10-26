//! `castbay-cast`: a command-line sender used for tests and benchmarks.

use std::time::{Duration, Instant};

use castbay_capture::Target;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("list") => list(),
        Some("bench-capture") => bench_capture(&args[1..]),
        Some("bench-encode") => bench_encode(&args[1..]),
        Some("cast") => cast(&args[1..]),
        Some("capture-test") => capture_test(&args[1..]),
        Some("discover") => discover(&args[1..]),
        _ => {
            eprintln!("usage: castbay-cast list | discover | capture-test [--window] [--seconds S] [--png FILE] | cast (--host H --port P | --receiver NAME) [--code auto|DIGITS|prompt] [--password PW] [--identity DIR] ... | bench-capture|bench-encode [--monitor N | --window TITLE] [--seconds S] [--fps F] [--bitrate BPS] [--sw]");
            std::process::exit(2);
        }
    }
}

fn list() {
    match (castbay_capture::monitors(), castbay_capture::window_titles()) {
        (Ok(monitors), Ok(windows)) => {
            monitors.iter().for_each(|m| println!("monitor {m}"));
            windows.iter().for_each(|w| println!("window {w}"));
            if monitors.is_empty() && windows.is_empty() {
                println!("nothing to list here: on this system the desktop asks the person what to share");
            }
        }
        (Err(error), _) | (_, Err(error)) => eprintln!("{error}"),
    }
}

/// Captures for a few seconds and says what arrived (and saves the last picture as a PNG): the
/// capture alone, without encoding or the network.
fn capture_test(args: &[String]) {
    use std::sync::{Arc, Mutex};
    let target = if args.iter().any(|a| a == "--window") { Target::Window(String::new()) } else { Target::Monitor(0) };
    let seconds: u64 = option(args, "--seconds").and_then(|v| v.parse().ok()).unwrap_or(5);
    castbay_capture::set_data_dir(&data_dir(args));
    let seen = Arc::new(Mutex::new((0u64, 0u32, 0u32, None::<Vec<u8>>)));
    let collect = seen.clone();
    let started = Instant::now();
    let result = castbay_capture::run(
        &target,
        || false,
        move |frame| {
            let mut s = collect.lock().unwrap();
            s.0 += 1;
            if s.0 == 1 {
                println!("first picture after {:.1} s: {}x{} (stride {})", started.elapsed().as_secs_f64(), frame.width, frame.height, frame.stride);
            }
            // Keep a tightly packed copy of the latest picture for the PNG.
            let mut packed = Vec::with_capacity(frame.width as usize * frame.height as usize * 4);
            for row in frame.data.chunks(frame.stride).take(frame.height as usize) {
                packed.extend_from_slice(&row[..frame.width as usize * 4]);
            }
            s.1 = frame.width;
            s.2 = frame.height;
            s.3 = Some(packed);
            frame.timestamp < Duration::from_secs(seconds)
        },
    );
    let s = seen.lock().unwrap();
    println!("ended: {result:?}; {} pictures in {:.1} s ({:.1} per second)", s.0, started.elapsed().as_secs_f64(), s.0 as f64 / seconds as f64);
    if let (Some(path), Some(bgra)) = (option(args, "--png"), &s.3) {
        write_png(path, s.1, s.2, bgra).expect("could not write the PNG");
        println!("saved {path}");
    }
}

/// A PNG with no compression (stored blocks): enough to look at a picture, with no dependency.
fn write_png(path: &str, width: u32, height: u32, bgra: &[u8]) -> std::io::Result<()> {
    fn crc32(data: &[u8]) -> u32 {
        let mut crc = !0u32;
        for &b in data {
            crc ^= b as u32;
            for _ in 0..8 {
                crc = if crc & 1 != 0 { (crc >> 1) ^ 0xEDB8_8320 } else { crc >> 1 };
            }
        }
        !crc
    }
    fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let mut body = kind.to_vec();
        body.extend_from_slice(data);
        out.extend_from_slice(&body);
        out.extend_from_slice(&crc32(&body).to_be_bytes());
    }
    let mut raw = Vec::with_capacity((width as usize * 3 + 1) * height as usize);
    for row in bgra.chunks(width as usize * 4).take(height as usize) {
        raw.push(0);
        for px in row.chunks_exact(4) {
            raw.extend_from_slice(&[px[2], px[1], px[0]]);
        }
    }
    let mut zlib = vec![0x78, 0x01];
    let mut chunks = raw.chunks(65535).peekable();
    while let Some(block) = chunks.next() {
        zlib.push(chunks.peek().is_none() as u8);
        zlib.extend_from_slice(&(block.len() as u16).to_le_bytes());
        zlib.extend_from_slice(&(!(block.len() as u16)).to_le_bytes());
        zlib.extend_from_slice(block);
    }
    let (mut a, mut b) = (1u32, 0u32);
    for &byte in &raw {
        a = (a + byte as u32) % 65521;
        b = (b + a) % 65521;
    }
    zlib.extend_from_slice(&((b << 16) | a).to_be_bytes());
    let mut png = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut header = Vec::new();
    header.extend_from_slice(&width.to_be_bytes());
    header.extend_from_slice(&height.to_be_bytes());
    header.extend_from_slice(&[8, 2, 0, 0, 0]);
    chunk(&mut png, b"IHDR", &header);
    chunk(&mut png, b"IDAT", &zlib);
    chunk(&mut png, b"IEND", &[]);
    std::fs::write(path, png)
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

/// Looks for receivers on the network.
fn discover(args: &[String]) {
    let seconds: u64 = option(args, "--seconds").and_then(|v| v.parse().ok()).unwrap_or(3);
    match castbay_link::browse(Duration::from_secs(seconds)) {
        Ok(found) if found.is_empty() => println!("no receivers found"),
        Ok(found) => {
            for r in found {
                println!("{} (id {}): {:?} port {}, verification {:?}{}", r.name, r.id, r.addresses, r.port, r.pair, if r.password { ", password" } else { "" });
            }
        }
        Err(e) => eprintln!("{e}"),
    }
}

/// Where the identity and the pinned receivers live.
fn data_dir(args: &[String]) -> std::path::PathBuf {
    if let Some(dir) = option(args, "--identity") {
        return dir.into();
    }
    if let Some(appdata) = std::env::var_os("APPDATA") {
        return std::path::PathBuf::from(appdata).join("CastBay Desktop");
    }
    // Elsewhere: the XDG data folder.
    let share = std::env::var_os("XDG_DATA_HOME")
        .map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(|| ".".into());
    share.join("castbay-desktop")
}

/// Captures, encodes and casts to a CastBay receiver over CastBay Link (the engine is
/// castbay-sender; this is its command line).
#[cfg(any(windows, target_os = "linux"))]
fn cast(args: &[String]) {
    use castbay_sender::{Codec, Options, Status};
    use std::sync::Arc;

    let target = match option(args, "--window") {
        Some(title) => Target::Window(title.to_string()),
        None => Target::Monitor(option(args, "--monitor").and_then(|v| v.parse().ok()).unwrap_or(0)),
    };
    let seconds: u64 = option(args, "--seconds").and_then(|v| v.parse().ok()).unwrap_or(30);
    let fps: u32 = option(args, "--fps").and_then(|v| v.parse().ok()).unwrap_or(30);
    let bitrate: u32 = option(args, "--bitrate").and_then(|v| v.parse().ok()).unwrap_or(8_000_000);
    let codec = Codec::parse(option(args, "--encoder").unwrap_or("amf")).expect("encoder: amf, nvenc, qsv or x264");

    // Which receiver: by address, or found by name.
    let (addr, receiver_id) = match (option(args, "--host"), option(args, "--receiver")) {
        (Some(host), _) => {
            let port: u16 = option(args, "--port").and_then(|v| v.parse().ok()).expect("--host needs --port");
            (std::net::SocketAddr::new(host.parse().expect("an IP address"), port), option(args, "--id").map(str::to_string))
        }
        (None, Some(name)) => {
            let found = castbay_link::browse(Duration::from_secs(4)).expect("discovery");
            let r = found.into_iter().find(|r| r.name.contains(name)).unwrap_or_else(|| {
                eprintln!("no receiver named like \"{name}\" found");
                std::process::exit(1);
            });
            let ip = r.addresses.iter().find(|a| a.is_ipv4()).or(r.addresses.first()).copied().expect("an address");
            println!("found {} at {ip}:{}", r.name, r.port);
            (std::net::SocketAddr::new(ip, r.port), Some(r.id))
        }
        _ => {
            eprintln!("cast needs --host H --port P, or --receiver NAME");
            std::process::exit(2);
        }
    };

    let code_option = option(args, "--code").map(str::to_string);
    let code = move |expected: &str| -> Option<String> {
        match code_option.as_deref() {
            Some("auto") => Some(expected.to_string()),
            Some(digits) if digits != "prompt" => Some(digits.to_string()),
            _ => {
                eprint!("The TV shows a code. Type it: ");
                let mut line = String::new();
                std::io::stdin().read_line(&mut line).ok()?;
                Some(line.trim().to_string())
            }
        }
    };
    let options = Options {
        addr,
        receiver_id,
        name: option(args, "--name").map(str::to_string).unwrap_or_else(|| std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Computer".into())),
        password: option(args, "--password").map(str::to_string),
        target: target.clone(),
        fps,
        bitrate_bps: bitrate,
        codec: Some(codec),
        audio: args.iter().any(|a| a == "--audio"),
        max_size: (option(args, "--max-width").and_then(|v| v.parse().ok()).unwrap_or(1920), option(args, "--max-height").and_then(|v| v.parse().ok()).unwrap_or(1080)),
        data_dir: data_dir(args),
        max_duration: Some(Duration::from_secs(seconds)),
    };
    let on_status = Arc::new(move |status: Status| match status {
        Status::Casting { receiver, width, height } => {
            println!("casting {target:?} ({width}x{height}) to {receiver} for {seconds} s at {fps} fps, {} kbit/s, {codec:?}", bitrate / 1000)
        }
        Status::Reconnecting(reason) => println!("connection lost ({reason}); reconnecting"),
        Status::Reconnected => println!("reconnected"),
        _ => {}
    });
    let handle = castbay_sender::start(options, on_status, Box::new(code));
    let report = match handle.join() {
        Ok(report) => report,
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(1);
        }
    };
    println!("ended: {}", report.ended);
    println!(
        "units sent: {} ({} dropped, {} repeated pictures), {:.2} Mbit/s, {:.1} fps",
        report.units_sent, report.units_dropped, report.repeated_pictures, report.bytes as f64 * 8.0 / report.seconds / 1e6, report.units_sent as f64 / report.seconds
    );
    if report.audio_frames > 0 {
        println!("audio: {} AAC frames sent", report.audio_frames);
    }
    println!("capture delivered {} pictures, {} went to the encoder", report.pictures_seen, report.pictures_taken);
    if report.bitrate_changes > 0 {
        println!("bitrate changed {} time(s), now {:.1} Mbit/s", report.bitrate_changes, report.final_bitrate_bps as f64 / 1e6);
    }
    if report.reconnects > 0 {
        println!("reconnected {} time(s)", report.reconnects);
    }
    if let Some((decode_ms, dropped, fps)) = report.last_receiver_report {
        println!("receiver reports: decode {decode_ms} ms, {dropped} dropped, {fps:.1} fps");
    }
    if !report.lags_ms.is_empty() {
        let at = |q: f64| report.lags_ms[((report.lags_ms.len() - 1) as f64 * q) as usize];
        println!("captured to ready to send: median {:.0} ms, 95th {:.0} ms, worst {:.0} ms", at(0.5), at(0.95), at(1.0));
    }
}

#[cfg(not(any(windows, target_os = "linux")))]
fn cast(_: &[String]) {
    eprintln!("casting is not implemented on this system");
}
