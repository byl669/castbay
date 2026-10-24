//! `castbay-cast`: a command-line sender used for tests and benchmarks.

use std::time::{Duration, Instant};

use castbay_capture::Target;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("list") => list(),
        Some("bench-capture") => bench_capture(&args[1..]),
        _ => {
            eprintln!("usage: castbay-cast list | bench-capture [--monitor N | --window TITLE] [--seconds S]");
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
