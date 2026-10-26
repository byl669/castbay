//! CastBay Desktop: finds CastBay receivers on the network and casts a screen or window to one.
//! The window is a thin shell over castbay-sender; everything here is plumbing between the page
//! and the engine.

#![cfg_attr(all(not(debug_assertions), windows), windows_subsystem = "windows")]

use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use castbay_sender::{Codec, Options, Status, Stopper, Target};
use serde::{Deserialize, Serialize};
use serde_json::json;
use tauri::{AppHandle, Emitter, Manager, State};

/// Appends a line to the log file (kept to about 1 MB, plus the previous file).
fn log(app: &AppHandle, line: &str) {
    use std::io::Write;
    let Ok(dir) = app.path().app_log_dir() else { return };
    let _ = std::fs::create_dir_all(&dir);
    let file = dir.join("castbay-desktop.log");
    if std::fs::metadata(&file).map(|m| m.len() > 1_000_000).unwrap_or(false) {
        let _ = std::fs::rename(&file, dir.join("castbay-desktop.old.log"));
    }
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(file) {
        let _ = writeln!(f, "{} {line}", utc(secs));
    }
}

/// `2026-10-26 11:30:05Z` from Unix seconds.
fn utc(secs: u64) -> String {
    let (days, rest) = ((secs / 86400) as i64, secs % 86400);
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z.rem_euclid(146097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + (month <= 2) as i64;
    format!("{year:04}-{month:02}-{day:02} {:02}:{:02}:{:02}Z", rest / 3600, rest % 3600 / 60, rest % 60)
}

#[derive(Default)]
struct Casting {
    stopper: Mutex<Option<Stopper>>,
    /// Where the page's answer to "type the code on the TV" goes.
    code: Mutex<Option<Sender<Option<String>>>>,
}

#[derive(Serialize)]
struct ReceiverView {
    id: String,
    name: String,
    ip: String,
    port: u16,
    /// `open`, `ask` or `pin`.
    pair: String,
    password: bool,
}

#[derive(Serialize)]
struct Sources {
    monitors: Vec<String>,
    windows: Vec<String>,
}

#[derive(Deserialize)]
struct StartArgs {
    id: String,
    ip: String,
    port: u16,
    password: Option<String>,
    /// `monitor` or `window`.
    kind: String,
    /// A monitor index, or part of a window title.
    value: String,
    /// `smooth`, `balanced` or `saver`.
    quality: String,
    /// `auto`, `amf`, `nvenc` or `x264`.
    encoder: String,
    /// Send the PC's sound too.
    audio: bool,
}

/// Looks for receivers for a few seconds.
#[tauri::command]
async fn discover() -> Result<Vec<ReceiverView>, String> {
    let found = tauri::async_runtime::spawn_blocking(|| castbay_link::browse(Duration::from_secs(3)))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?;
    let mut views: Vec<ReceiverView> = found
        .into_iter()
        .filter_map(|r| {
            let ip = r.addresses.iter().find(|a| a.is_ipv4()).or(r.addresses.first())?;
            Some(ReceiverView {
                id: r.id,
                name: r.name,
                ip: ip.to_string(),
                port: r.port,
                pair: format!("{:?}", r.pair).to_lowercase(),
                password: r.password,
            })
        })
        .collect();
    views.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(views)
}

/// The monitors and windows that can be cast.
#[tauri::command]
fn sources() -> Result<Sources, String> {
    Ok(Sources {
        monitors: castbay_capture::windows::monitors().map_err(|e| e.to_string())?,
        windows: castbay_capture::windows::windows().map_err(|e| e.to_string())?,
    })
}

fn status_json(status: &Status) -> serde_json::Value {
    match status {
        Status::Connecting => json!({"kind": "connecting"}),
        Status::NeedsCode => json!({"kind": "needs_code"}),
        Status::Casting { receiver, width, height } => json!({"kind": "casting", "receiver": receiver, "width": width, "height": height}),
        Status::Reconnecting(reason) => json!({"kind": "reconnecting", "reason": reason}),
        Status::Reconnected => json!({"kind": "reconnected"}),
        Status::Receiver { decode_ms, dropped, fps } => json!({"kind": "stats", "decode_ms": decode_ms, "dropped": dropped, "fps": fps}),
        Status::Ended(reason) => json!({"kind": "ended", "reason": reason}),
    }
}

#[tauri::command]
fn start_cast(args: StartArgs, app: AppHandle, state: State<'_, Casting>) -> Result<(), String> {
    if state.stopper.lock().unwrap().is_some() {
        return Err("already casting".into());
    }
    let target = match args.kind.as_str() {
        "window" => Target::Window(args.value.clone()),
        _ => Target::Monitor(args.value.parse().unwrap_or(0)),
    };
    let (fps, bitrate_bps) = match args.quality.as_str() {
        "smooth" => (60, 10_000_000),
        "saver" => (30, 4_000_000),
        _ => (30, 8_000_000),
    };
    let codec = match args.encoder.as_str() {
        "auto" => None,
        name => Some(Codec::parse(name).ok_or("unknown encoder")?),
    };
    let ip: std::net::IpAddr = args.ip.parse().map_err(|_| "bad address".to_string())?;
    let data_dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    let options = Options {
        addr: std::net::SocketAddr::new(ip, args.port),
        receiver_id: Some(args.id),
        name: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Computer".into()),
        password: args.password.filter(|p| !p.is_empty()),
        target,
        fps,
        bitrate_bps,
        codec,
        audio: args.audio,
        max_size: (1920, 1080),
        data_dir,
        max_duration: None,
    };

    let (code_tx, code_rx) = channel::<Option<String>>();
    *state.code.lock().unwrap() = Some(code_tx);
    let ask = Box::new(move |_expected: &str| code_rx.recv_timeout(Duration::from_secs(180)).ok().flatten());
    let emitter = app.clone();
    let on_status = Arc::new(move |status: Status| {
        match &status {
            Status::Receiver { .. } => {}
            other => log(&emitter, &format!("{other:?}")),
        }
        let _ = emitter.emit("status", status_json(&status));
    });
    log(&app, &format!("start: {}:{} {} {} {} {}", args.ip, args.port, args.kind, args.quality, args.encoder, args.value));
    let handle = castbay_sender::start(options, on_status, ask);
    *state.stopper.lock().unwrap() = Some(handle.stopper());

    // Waits for the end, tells the page, and frees the slot for the next cast.
    let app = app.clone();
    std::thread::spawn(move || {
        let outcome = handle.join();
        let state = app.state::<Casting>();
        *state.stopper.lock().unwrap() = None;
        *state.code.lock().unwrap() = None;
        if let Err(failure) = outcome {
            log(&app, &format!("failed: {} ({})", failure.code, failure.detail));
            let _ = app.emit("status", json!({"kind": "failed", "code": failure.code, "reason": failure.detail}));
        }
    });
    Ok(())
}

/// The page's answer to the pairing code prompt; None means the person gave up.
#[tauri::command]
fn submit_code(code: Option<String>, state: State<'_, Casting>) {
    if let Some(tx) = state.code.lock().unwrap().as_ref() {
        let _ = tx.send(code.map(|c| c.trim().to_string()));
    }
}

/// Forgets a receiver's pinned key, for when it was reset and is the same device again.
#[tauri::command]
fn forget_receiver(id: String, app: AppHandle) -> Result<(), String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    castbay_link::Pins::load(&dir).forget(&id).map_err(|e| e.to_string())?;
    log(&app, &format!("forgot receiver {id}"));
    Ok(())
}

/// Opens a release page of this project in the browser (and nothing else).
#[tauri::command]
fn open_release(url: String) -> Result<(), String> {
    if !url.starts_with("https://github.com/weenas/castbay/releases/") || url.chars().any(|c| c.is_control() || c == '"' || c == ' ') {
        return Err("not a release page of this project".into());
    }
    std::process::Command::new("rundll32").args(["url.dll,FileProtocolHandler", &url]).spawn().map_err(|e| e.to_string())?;
    Ok(())
}

/// Shows the folder with the log file.
#[tauri::command]
fn open_logs(app: AppHandle) -> Result<(), String> {
    let dir = app.path().app_log_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::process::Command::new("explorer").arg(&dir).spawn().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn stop_cast(state: State<'_, Casting>) {
    if let Some(stopper) = state.stopper.lock().unwrap().as_ref() {
        stopper.stop();
    }
    if let Some(tx) = state.code.lock().unwrap().as_ref() {
        let _ = tx.send(None);
    }
}

fn main() {
    tauri::Builder::default()
        .manage(Casting::default())
        .invoke_handler(tauri::generate_handler![discover, sources, start_cast, submit_code, stop_cast, forget_receiver, open_logs, open_release])
        .run(tauri::generate_context!())
        .expect("the CastBay Desktop window failed to start");
}

#[cfg(test)]
mod tests {
    use super::utc;

    #[test]
    fn dates_come_out_right() {
        assert_eq!(utc(0), "1970-01-01 00:00:00Z");
        assert_eq!(utc(951_782_400), "2000-02-29 00:00:00Z");
        assert_eq!(utc(1_800_000_000), "2027-01-15 08:00:00Z");
    }
}
