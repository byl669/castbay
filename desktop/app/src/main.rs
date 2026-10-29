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
mod diagnostics;
mod settings;
mod update;

use settings::Settings;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, WindowEvent};

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

/// The tray menu's entries, kept to change their words when the language changes.
struct TrayItems {
    open: MenuItem<tauri::Wry>,
    stop: MenuItem<tauri::Wry>,
    quit: MenuItem<tauri::Wry>,
}

fn tray_words(zh: bool) -> [&'static str; 3] {
    if zh { ["打开 CastBay Desktop", "停止投屏", "退出"] } else { ["Open CastBay Desktop", "Stop casting", "Quit"] }
}

/// The person's choices, loaded at start and rewritten when they change.
struct SettingsState(Mutex<Settings>);

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
    /// The system asks the person what to share (Wayland), so there is nothing to pick here.
    system_picker: bool,
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
        monitors: castbay_capture::monitors().map_err(|e| e.to_string())?,
        windows: castbay_capture::window_titles().map_err(|e| e.to_string())?,
        system_picker: cfg!(not(windows)),
    })
}

fn status_json(status: &Status) -> serde_json::Value {
    match status {
        Status::Connecting => json!({"kind": "connecting"}),
        Status::NeedsCode => json!({"kind": "needs_code"}),
        Status::Casting { receiver, width, height } => json!({"kind": "casting", "receiver": receiver, "width": width, "height": height}),
        Status::Reconnecting(reason) => json!({"kind": "reconnecting", "reason": reason}),
        Status::Reconnected => json!({"kind": "reconnected"}),
        Status::Bitrate(bps) => json!({"kind": "bitrate", "bps": bps}),
        Status::ScreenLocked(locked) => json!({"kind": "locked", "locked": locked}),
        Status::Receiver { decode_ms, dropped, fps } => json!({"kind": "stats", "decode_ms": decode_ms, "dropped": dropped, "fps": fps}),
        Status::Ended(reason) => json!({"kind": "ended", "reason": reason}),
    }
}

#[tauri::command]
fn start_cast(args: StartArgs, app: AppHandle, state: State<'_, Casting>, settings: State<'_, SettingsState>) -> Result<(), String> {
    let settings = settings.0.lock().unwrap().clone();
    if state.stopper.lock().unwrap().is_some() {
        return Err("already casting".into());
    }
    let target = match args.kind.as_str() {
        "window" => Target::Window(args.value.clone()),
        _ => Target::Monitor(args.value.parse().unwrap_or(0)),
    };
    let (fps, bitrate_bps) = settings.quality_params();
    let codec = match settings.encoder.as_str() {
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
        audio: settings.send_audio,
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
    log(&app, &format!("start: {}:{} {} {} {} {}", args.ip, args.port, args.kind, format!("{}fps {}Mbit/s", settings.fps, settings.bitrate_mbps), settings.encoder, if args.kind == "window" { "" } else { args.value.as_str() }));
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

/// What the person chose so far.
#[tauri::command]
fn get_settings(settings: State<'_, SettingsState>) -> Settings {
    settings.0.lock().unwrap().clone()
}

/// Changes the fields named in `patch` and keeps them; returns all settings as they are now.
#[tauri::command]
fn set_settings(patch: serde_json::Value, app: AppHandle, settings: State<'_, SettingsState>) -> Result<Settings, String> {
    let mut current = settings.0.lock().unwrap();
    let mut next = current.clone();
    next.apply(&patch)?;
    next.save(&app.path().app_data_dir().map_err(|e| e.to_string())?)?;
    if next.language != current.language {
        if let Some(items) = app.try_state::<TrayItems>() {
            let [open, stop, quit] = tray_words(next.chinese(chinese()));
            let _ = items.open.set_text(open);
            let _ = items.stop.set_text(stop);
            let _ = items.quit.set_text(quit);
        }
    }
    *current = next.clone();
    Ok(next)
}

#[derive(Serialize)]
struct PairedView {
    id: String,
    name: String,
    /// When it was last connected to, Unix seconds (0 when unknown).
    seen: u64,
}

/// The receivers this PC has paired with.
#[tauri::command]
fn list_paired(app: AppHandle) -> Result<Vec<PairedView>, String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    Ok(castbay_link::Pins::load(&dir).list().into_iter().map(|r| PairedView { id: r.id, name: r.name, seen: r.seen }).collect())
}

/// Forgets a receiver's pinned key, for when it was reset and is the same device again.
#[tauri::command]
fn forget_receiver(id: String, app: AppHandle) -> Result<(), String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    castbay_link::Pins::load(&dir).forget(&id).map_err(|e| e.to_string())?;
    log(&app, &format!("forgot receiver {id}"));
    Ok(())
}

/// Whether `url` is one of this project's pages the program opens: a release page on GitHub or a
/// page of the website (the changelog), with nothing in it that could be taken for more.
fn project_page(url: &str) -> bool {
    (url.starts_with("https://github.com/weenas/castbay/releases/") || url.starts_with("https://castbay.weenas.com/"))
        && !url.chars().any(|c| c.is_control() || c == '"' || c == ' ' || c == '\\')
}

/// Opens a release page or a page of the website in the browser (and nothing else).
#[tauri::command]
fn open_release(url: String) -> Result<(), String> {
    if !project_page(&url) {
        return Err("not a page of this project".into());
    }
    open_with_system(&url)
}

/// Hands a web address or folder to whatever the system opens it with.
fn open_with_system(target: &str) -> Result<(), String> {
    #[cfg(windows)]
    let mut command = {
        let mut c = std::process::Command::new("rundll32");
        c.args(["url.dll,FileProtocolHandler", target]);
        c
    };
    #[cfg(not(windows))]
    let mut command = {
        let mut c = std::process::Command::new("xdg-open");
        c.arg(target);
        c
    };
    command.spawn().map_err(|e| e.to_string())?;
    Ok(())
}

/// The encoders that open on this computer, as the names settings.json uses (the first call
/// tries each, which takes a moment; the answer is kept).
#[tauri::command]
async fn encoders() -> Vec<&'static str> {
    found_encoders().await
}

async fn found_encoders() -> Vec<&'static str> {
    static FOUND: std::sync::OnceLock<Vec<&'static str>> = std::sync::OnceLock::new();
    if let Some(found) = FOUND.get() {
        return found.clone();
    }
    let found = tauri::async_runtime::spawn_blocking(|| {
        castbay_sender::available_encoders()
            .into_iter()
            .map(|codec| match codec {
                Codec::Amf => "amf",
                Codec::Nvenc => "nvenc",
                Codec::Qsv => "qsv",
                Codec::X264 => "x264",
            })
            .collect::<Vec<_>>()
    })
    .await
    .unwrap_or_default();
    FOUND.get_or_init(|| found).clone()
}

/// The person's home folder, to be taken out of what is sent (diagnostics::scrub).
fn home_dir() -> String {
    std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).unwrap_or_default()
}

/// What the Diagnostics page and the report say about this computer and the settings: labels and
/// values, no names of receivers, windows or people.
async fn device_info(app: &AppHandle) -> Vec<(String, String)> {
    let settings = app.state::<SettingsState>().0.lock().unwrap().clone();
    let paired = app.path().app_data_dir().map(|d| castbay_link::Pins::load(&d).list().len()).unwrap_or(0);
    let encoders = found_encoders().await;
    let sound = if settings.send_audio { "with sound" } else { "no sound" };
    [
        ("CastBay Desktop", env!("CARGO_PKG_VERSION").to_string()),
        ("System", diagnostics::system_line()),
        ("Architecture", std::env::consts::ARCH.to_string()),
        ("WebView", tauri::webview_version().unwrap_or_default()),
        ("Encoders that open here", if encoders.is_empty() { "none".into() } else { encoders.join(", ") }),
        ("Settings", format!("{} fps, {} Mbit/s, encoder {}, {sound}, language {}", settings.fps, settings.bitrate_mbps, settings.encoder, settings.language)),
        ("Paired receivers", paired.to_string()),
    ]
    .into_iter()
    .map(|(label, value)| (label.to_string(), value))
    .collect()
}

/// The Diagnostics page: this computer's facts and the end of the log (scrubbed, as it would be sent).
#[tauri::command]
async fn diagnostics_view(app: AppHandle) -> Result<serde_json::Value, String> {
    let info = device_info(&app).await;
    let lines = diagnostics::read_log(&app.path().app_log_dir().map_err(|e| e.to_string())?);
    let log = diagnostics::scrub(&diagnostics::newest(&lines, 40_000), &home_dir());
    Ok(json!({"info": info, "log": log}))
}

/// Uploads a problem report (only ever called from the button on the Diagnostics page, after it
/// said what is sent): the report's ID, or why it failed.
#[tauri::command]
async fn upload_report(app: AppHandle) -> Result<String, String> {
    let info = device_info(&app).await;
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let mut head = format!("CastBay Desktop problem report, {}\n", utc(secs));
    for (label, value) in &info {
        head.push_str(&format!("{label}: {value}\n"));
    }
    let lines = diagnostics::read_log(&app.path().app_log_dir().map_err(|e| e.to_string())?);
    let text = diagnostics::report_text(&head, &lines, &home_dir());
    let dir = app.path().app_cache_dir().map_err(|e| e.to_string())?.join("report");
    let result = tauri::async_runtime::spawn_blocking(move || diagnostics::upload(&text, env!("CARGO_PKG_VERSION"), &dir))
        .await
        .map_err(|e| e.to_string())?;
    match result {
        Ok(id) => {
            log(&app, &format!("problem report uploaded: {id}"));
            Ok(id)
        }
        Err(refusal) => {
            log(&app, &format!("problem report not uploaded: {}", refusal.text()));
            Err(refusal.text())
        }
    }
}

/// Downloads and installs a newer version (see `update`). `Ok("installing")`: Windows is replacing
/// this program and starts it again; `Ok("opened")`: the system's installer was opened (Linux).
/// `Err("manual")`: this is not an installed copy (the portable zip, a development build), so the
/// page offers the release page instead; `Err("busy")`: a cast is running.
#[tauri::command]
async fn install_update(version: String, app: AppHandle) -> Result<String, String> {
    if app.state::<Casting>().stopper.lock().unwrap().is_some() {
        return Err("busy".into());
    }
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let installed = if cfg!(windows) {
        std::env::var_os("LOCALAPPDATA").is_some_and(|dir| exe.starts_with(std::path::Path::new(&dir).join("Programs").join("CastBay Desktop")))
    } else {
        exe == std::path::Path::new("/usr/bin/castbay-desktop")
    };
    if !installed {
        return Err("manual".into());
    }
    let dir = app.path().app_cache_dir().map_err(|e| e.to_string())?.join("update");
    log(&app, &format!("update: fetching {version}"));
    let file = tauri::async_runtime::spawn_blocking(move || update::fetch(&version, cfg!(windows), chinese(), &dir))
        .await
        .map_err(|e| e.to_string())?
        .inspect_err(|e| log(&app, &format!("update: failed: {e}")))?;
    log(&app, &format!("update: {} checked", file.display()));
    #[cfg(windows)]
    {
        update::install(&file, &exe)?;
        quit(&app);
        Ok("installing".into())
    }
    #[cfg(not(windows))]
    {
        // The password box of the system, then apt installs it; the program starts again from the new files.
        let deb = file.clone();
        let result = tauri::async_runtime::spawn_blocking(move || update::install(&deb)).await.map_err(|e| e.to_string())?;
        match result {
            Ok(update::Installed::Yes) => {
                log(&app, "update: installed");
                update::start_again(&exe)?;
                quit(&app);
                Ok("installing".into())
            }
            Ok(update::Installed::Cancelled) => Err("cancelled".into()),
            Ok(update::Installed::NoPrompt) => {
                // No way to ask for the password here: the system's installer opens the file.
                open_with_system(&file.to_string_lossy())?;
                Ok("opened".into())
            }
            Err(e) => {
                log(&app, &format!("update: install failed: {e}"));
                Err(e)
            }
        }
    }
}

/// Shows the folder with the log file.
#[tauri::command]
fn open_logs(app: AppHandle) -> Result<(), String> {
    let dir = app.path().app_log_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    open_with_system(&dir.to_string_lossy())
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

/// Whether Windows itself speaks Chinese, for the few words the tray menu needs.
fn chinese() -> bool {
    #[cfg(windows)]
    {
        // The primary language is the low ten bits; Chinese is 0x04.
        unsafe { windows::Win32::Globalization::GetUserDefaultUILanguage() & 0x3ff == 0x04 }
    }
    #[cfg(not(windows))]
    {
        ["LC_ALL", "LC_MESSAGES", "LANG"]
            .iter()
            .find_map(|k| std::env::var(k).ok().filter(|v| !v.is_empty()))
            .map(|v| v.starts_with("zh"))
            .unwrap_or(false)
    }
}

fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn stop_casting(app: &AppHandle) {
    if let Some(stopper) = app.state::<Casting>().stopper.lock().unwrap().as_ref() {
        stopper.stop();
    }
}

/// Ends a cast in progress (the receiver is told goodbye) and then the program.
fn quit(app: &AppHandle) {
    stop_casting(app);
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(800));
        app.exit(0);
    });
}

#[cfg(windows)]
mod autostart {
    const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    const RUN_VALUE: &str = "CastBayDesktop";

    pub fn reg(args: &[&str]) -> std::io::Result<std::process::ExitStatus> {
        use std::os::windows::process::CommandExt;
        let mut command = std::process::Command::new("reg");
        command.args(args);
        command.creation_flags(0x0800_0000); // no console window
        command.output().map(|o| o.status)
    }

    pub fn get() -> bool {
        reg(&["query", RUN_KEY, "/v", RUN_VALUE]).map(|s| s.success()).unwrap_or(false)
    }

    pub fn set(on: bool) -> Result<(), String> {
        if on {
            let exe = std::env::current_exe().map_err(|e| e.to_string())?;
            let value = format!("\"{}\" --minimized", exe.display());
            let ok = reg(&["add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", &value, "/f"]).map_err(|e| e.to_string())?.success();
            if !ok {
                return Err("could not write the startup entry".into());
            }
        } else {
            let _ = reg(&["delete", RUN_KEY, "/v", RUN_VALUE, "/f"]);
        }
        Ok(())
    }
}

#[cfg(not(windows))]
mod autostart {
    use std::path::PathBuf;

    fn entry() -> Result<PathBuf, String> {
        let base = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
            .ok_or("no home folder")?;
        Ok(base.join("autostart").join("castbay-desktop.desktop"))
    }

    pub fn get() -> bool {
        entry().map(|p| p.exists()).unwrap_or(false)
    }

    pub fn set(on: bool) -> Result<(), String> {
        let path = entry()?;
        if on {
            let exe = std::env::current_exe().map_err(|e| e.to_string())?;
            let text = format!(
                "[Desktop Entry]\nType=Application\nName=CastBay Desktop\nExec=\"{}\" --minimized\nX-GNOME-Autostart-enabled=true\n",
                exe.display()
            );
            std::fs::create_dir_all(path.parent().unwrap()).map_err(|e| e.to_string())?;
            std::fs::write(&path, text).map_err(|e| e.to_string())?;
        } else {
            let _ = std::fs::remove_file(&path);
        }
        Ok(())
    }
}

/// Whether the program starts with the computer (for the window's switch).
#[tauri::command]
fn autostart_get() -> bool {
    autostart::get()
}

/// Starts the program with the computer, minimised into the tray, or stops doing so.
#[tauri::command]
fn autostart_set(on: bool, app: AppHandle) -> Result<(), String> {
    autostart::set(on)?;
    log(&app, &format!("autostart {}", if on { "on" } else { "off" }));
    Ok(())
}

/// Whether the Microsoft Edge WebView2 runtime, which draws the window, is on this PC.
#[cfg(windows)]
fn webview2_installed() -> bool {
    [
        r"HKLM\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
        r"HKCU\Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
    ]
    .iter()
    .any(|key| autostart::reg(&["query", key, "/v", "pv"]).map(|s| s.success()).unwrap_or(false))
}

/// Says what is missing and offers the download, instead of failing without a word.
#[cfg(windows)]
fn webview2_missing() {
    use windows::core::HSTRING;
    use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, IDYES, MB_ICONWARNING, MB_YESNO};
    let (title, text) = if chinese() {
        ("CastBay Desktop", "需要先安装 Microsoft Edge WebView2 运行库才能显示窗口（Windows 11 和较新的 Windows 10 已自带）。\n\n现在打开下载页面吗？")
    } else {
        ("CastBay Desktop", "The Microsoft Edge WebView2 runtime is needed to show the window (it is part of Windows 11 and recent Windows 10).\n\nOpen the download page now?")
    };
    let answer = unsafe { MessageBoxW(None, &HSTRING::from(text), &HSTRING::from(title), MB_YESNO | MB_ICONWARNING) };
    if answer == IDYES {
        let _ = std::process::Command::new("rundll32")
            .args(["url.dll,FileProtocolHandler", "https://go.microsoft.com/fwlink/p/?LinkId=2124703"])
            .spawn();
    }
}

fn main() {
    #[cfg(windows)]
    if !webview2_installed() {
        webview2_missing();
        return;
    }
    tauri::Builder::default()
        // A second launch (the shortcut again, Windows startup) brings the first one forward.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .manage(Casting::default())
        .setup(|app| {
            let loaded = Settings::load(&app.path().app_data_dir().unwrap_or_default());
            let zh = loaded.chinese(chinese());
            app.manage(SettingsState(Mutex::new(loaded)));
            let [open_text, stop_text, quit_text] = tray_words(zh);
            let open = MenuItem::with_id(app, "open", open_text, true, None::<&str>)?;
            let stop = MenuItem::with_id(app, "stop", stop_text, true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", quit_text, true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &stop, &quit_item])?;
            let mut tray = TrayIconBuilder::new()
                .tooltip("CastBay Desktop")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => show_main(app),
                    "stop" => stop_casting(app),
                    "quit" => quit(app),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        show_main(tray.app_handle());
                    }
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;
            app.manage(TrayItems { open, stop, quit: quit_item });
            // Started with Windows: wait in the tray until the person opens it.
            if !std::env::args().any(|a| a == "--minimized") {
                show_main(app.handle());
            }
            Ok(())
        })
        // Closing the window during a cast leaves the cast running in the tray; otherwise it quits.
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let casting = window.state::<Casting>().stopper.lock().unwrap().is_some();
                if casting {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![get_settings, set_settings, list_paired, discover, sources, start_cast, submit_code, stop_cast, forget_receiver, open_logs, open_release, install_update, encoders, diagnostics_view, upload_report, autostart_get, autostart_set])
        .run(tauri::generate_context!())
        .expect("the CastBay Desktop window failed to start");
}

#[cfg(test)]
mod tests {
    use super::{project_page, utc};

    #[test]
    fn only_this_projects_pages_are_opened() {
        for ok in [
            "https://github.com/weenas/castbay/releases/tag/desktop-v0.1.3",
            "https://castbay.weenas.com/changelog#desktop-v0.1.3",
            "https://castbay.weenas.com/zh/changelog#desktop-v0.1.3",
        ] {
            assert!(project_page(ok), "{ok}");
        }
        for bad in [
            "https://evil.example/",
            "http://castbay.weenas.com/changelog",
            "https://castbay.weenas.com.evil.example/",
            "file:///etc/passwd",
            "https://castbay.weenas.com/a b",
            "https://castbay.weenas.com/\"x",
            "https://github.com/weenas/castbay/issues/1",
        ] {
            assert!(!project_page(bad), "{bad}");
        }
    }

    #[test]
    fn dates_come_out_right() {
        assert_eq!(utc(0), "1970-01-01 00:00:00Z");
        assert_eq!(utc(951_782_400), "2000-02-29 00:00:00Z");
        assert_eq!(utc(1_800_000_000), "2027-01-15 08:00:00Z");
    }
}
