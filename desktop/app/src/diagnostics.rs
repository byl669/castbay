//! The Diagnostics page and the problem report the person may upload from it: this computer, the
//! program's settings (no names), and its recent log, with personal details taken out on this
//! computer ([`scrub`]) before anything leaves it. The website keeps a report for 90 days under a
//! short ID ("CB-7K3F9Q") that the person passes on with their problem report. Nothing is sent
//! unless they press Upload, on a page that says what will be sent.

use std::path::Path;
use std::process::Command;

/// The website takes up to 512 KB.
const MAX_CHARS: usize = 400_000;
const SITE: &str = "https://castbay.weenas.com/api/reports";
/// For networks that cannot reach Cloudflare (see update.rs): the same request, passed on unchanged.
const RELAY: &str = "https://cast.weenas.com/api/reports";

/// What went wrong with an upload, for the page.
#[derive(Debug, PartialEq)]
pub enum Refusal {
    /// The website answered with this status (429: too many reports in a row).
    Status(u16),
    /// No answer from either address, with the last reason.
    Network(String),
}

impl Refusal {
    pub fn text(&self) -> String {
        match self {
            Refusal::Status(429) => "too many".into(),
            Refusal::Status(code) => format!("HTTP {code}"),
            Refusal::Network(reason) => reason.clone(),
        }
    }
}

/// `text` with what identifies a person or their network taken out: addresses (a computer's on its
/// network, a receiver's), the names of receivers, and the home folder in file paths. `home` is
/// that folder ("" for none).
pub fn scrub(text: &str, home: &str) -> String {
    let mut out = text.to_string();
    if home.len() > 3 {
        out = replace_ignoring_case(&out, home, "~");
        // A path may also be written with the other kind of slash.
        let other = if home.contains('\\') { home.replace('\\', "/") } else { home.replace('/', "\\") };
        out = replace_ignoring_case(&out, &other, "~");
    }
    out = hide_addresses(&out);
    hide_receiver_names(&out)
}

fn replace_ignoring_case(text: &str, find: &str, with: &str) -> String {
    let (lower, needle) = (text.to_lowercase(), find.to_lowercase());
    // Lower-casing can change a text's length (rare letters); then it is left as it is.
    if lower.len() != text.len() {
        return text.replace(find, with);
    }
    let mut out = String::with_capacity(text.len());
    let mut from = 0;
    while let Some(at) = lower[from..].find(&needle) {
        out.push_str(&text[from..from + at]);
        out.push_str(with);
        from += at + needle.len();
    }
    out.push_str(&text[from..]);
    out
}

/// Every a.b.c.d (each part 0 to 255) becomes `<ip>`; a longer run of numbers and dots, such as a
/// Windows version (10.0.19045.5555), stays.
fn hide_addresses(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut run = String::new();
    let flush = |run: &mut String, out: &mut String| {
        if !run.is_empty() {
            // A full stop that ends a sentence is not part of the address.
            let body = run.trim_end_matches('.');
            let parts: Vec<&str> = body.split('.').collect();
            let address = parts.len() == 4 && parts.iter().all(|p| !p.is_empty() && p.len() <= 3 && p.parse::<u32>().is_ok_and(|n| n <= 255));
            if address {
                out.push_str("<ip>");
                out.push_str(&run[body.len()..]);
            } else {
                out.push_str(run);
            }
            run.clear();
        }
    };
    for c in text.chars() {
        if c.is_ascii_digit() || c == '.' {
            run.push(c);
        } else {
            flush(&mut run, &mut out);
            out.push(c);
        }
    }
    flush(&mut run, &mut out);
    out
}

/// `receiver: "Living room TV"` (how the log names the receiver of a cast) becomes `receiver: "<name>"`.
fn hide_receiver_names(text: &str) -> String {
    const KEY: &str = "receiver: \"";
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(at) = rest.find(KEY) {
        let start = at + KEY.len();
        out.push_str(&rest[..start]);
        match rest[start..].find('"') {
            Some(end) => {
                out.push_str("<name>");
                rest = &rest[start + end..];
            }
            None => {
                rest = &rest[start..];
                break;
            }
        }
    }
    out.push_str(rest);
    out
}

/// The newest of `lines` that fit in `budget` characters, oldest first.
pub fn newest(lines: &[String], budget: usize) -> String {
    let mut kept: Vec<&str> = Vec::new();
    let mut size = 0;
    for line in lines.iter().rev() {
        if size + line.len() + 1 > budget {
            break;
        }
        kept.push(line);
        size += line.len() + 1;
    }
    kept.reverse();
    kept.join("\n")
}

/// The program's log lines, the previous file's first, newest last.
pub fn read_log(dir: &Path) -> Vec<String> {
    ["castbay-desktop.old.log", "castbay-desktop.log"]
        .iter()
        .filter_map(|name| std::fs::read(dir.join(name)).ok())
        .flat_map(|bytes| String::from_utf8_lossy(&bytes).lines().map(str::to_string).collect::<Vec<_>>())
        .collect()
}

/// The report: `head` (this computer, the settings), then the log, scrubbed, at most about 400 KB.
pub fn report_text(head: &str, log: &[String], home: &str) -> String {
    let head = scrub(head, home);
    let budget = MAX_CHARS.saturating_sub(head.len() + 64);
    format!("{head}\n== Log (oldest first) ==\n{}\n", scrub(&newest(log, budget), home))
}

/// This computer's operating system, in a line.
pub fn system_line() -> String {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let out = Command::new("cmd").args(["/c", "ver"]).creation_flags(0x0800_0000).output();
        out.ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).filter(|s| !s.is_empty()).unwrap_or_else(|| "Windows".into())
    }
    #[cfg(not(windows))]
    {
        let release = std::fs::read_to_string("/etc/os-release").unwrap_or_default();
        let name = release.lines().find_map(|l| l.strip_prefix("PRETTY_NAME=")).map(|v| v.trim_matches('"').to_string()).unwrap_or_else(|| "Linux".into());
        let kernel = std::fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default();
        let session = std::env::var("XDG_SESSION_TYPE").unwrap_or_default();
        let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
        format!("{name}, kernel {}, session {session}, desktop {desktop}", kernel.trim())
    }
}

/// POSTs `text` to `path` of the website, and if it cannot be reached, of its relay: the status
/// and the answer. Whatever answer comes (an error status too) is final; only a failure to reach
/// an address tries the next one. `headers` are whole lines (`Name: value`).
pub fn post(path: &str, content_type: &str, headers: &[String], text: &str, dir: &Path) -> Result<(u16, String), Refusal> {
    std::fs::create_dir_all(dir).map_err(|e| Refusal::Network(e.to_string()))?;
    let file = dir.join("post.txt");
    std::fs::write(&file, text).map_err(|e| Refusal::Network(e.to_string()))?;
    let mut last = Refusal::Network("no address".into());
    for base in [SITE, RELAY] {
        let mut command = Command::new("curl");
        command
            .args(["--silent", "--show-error", "--proto", "=https", "--connect-timeout", "15", "--max-time", "90", "--request", "POST"])
            .args(["--header", &format!("Content-Type: {content_type}")]);
        for header in headers {
            command.args(["--header", header]);
        }
        command.arg("--data-binary").arg(format!("@{}", file.display())).args(["--write-out", "\n%{http_code}"]).arg(format!("{base}{path}"));
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x0800_0000);
        }
        let output = match command.output() {
            Ok(o) => o,
            Err(e) => {
                last = Refusal::Network(format!("curl: {e}"));
                continue;
            }
        };
        if !output.status.success() {
            let line = String::from_utf8_lossy(&output.stderr).lines().last().unwrap_or("").trim().to_string();
            last = Refusal::Network(if line.is_empty() { output.status.to_string() } else { line });
            continue;
        }
        let _ = std::fs::remove_file(&file);
        let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
        let (body, code) = stdout.rsplit_once('\n').unwrap_or(("", &stdout));
        return match code.trim().parse::<u16>() {
            Ok(code) => Ok((code, body.to_string())),
            Err(_) => Err(Refusal::Network("unreadable answer".into())),
        };
    }
    let _ = std::fs::remove_file(&file);
    Err(last)
}

/// Sends a problem report and returns its ID. `kind` is `manual`, `crash` or `failure` (what the
/// website files it under).
pub fn upload(text: &str, version: &str, kind: &str, dir: &Path) -> Result<String, Refusal> {
    let headers = [format!("X-CastBay-Version: {version}"), format!("X-CastBay-Report: {kind}"), "X-CastBay-App: desktop".to_string()];
    match post("/api/reports", "text/plain; charset=utf-8", &headers, text, dir)? {
        (201, body) => parse_id(&body).ok_or_else(|| Refusal::Network("unreadable answer".into())),
        (code, _) => Err(Refusal::Status(code)),
    }
}

/// Windows' version from `ver`'s line ("Microsoft Windows [Version 10.0.19045.5555]"): 10.0.19045.
pub fn windows_version(line: &str) -> Option<String> {
    let v = line.split("Version ").nth(1)?.trim_end_matches(']').trim();
    let parts: Vec<&str> = v.split('.').take(3).collect();
    (parts.len() == 3 && parts.iter().all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))).then(|| parts.join("."))
}

/// A Linux distribution from /etc/os-release, as "ubuntu 26.04" (just the ID when it has no version).
pub fn linux_distribution(os_release: &str) -> String {
    let value = |key: &str| {
        os_release
            .lines()
            .find_map(|l| l.strip_prefix(key))
            .map(|v| v.trim().trim_matches('"').to_ascii_lowercase())
            .filter(|v| !v.is_empty() && v.len() <= 20 && v.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-'))
    };
    match (value("ID="), value("VERSION_ID=")) {
        (Some(id), Some(version)) => format!("{id} {version}"),
        (Some(id), None) => id,
        _ => "linux".into(),
    }
}

/// The ID in the website's answer (`{"id":"CB-7K3F9Q"}`), if it has the shape of one.
fn parse_id(body: &str) -> Option<String> {
    let id = serde_json::from_str::<serde_json::Value>(body).ok()?["id"].as_str()?.to_string();
    let valid = id.len() == 9 && id.starts_with("CB-") && id[3..].bytes().all(|b| b.is_ascii_digit() || b.is_ascii_uppercase());
    valid.then_some(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_go_but_versions_stay() {
        assert_eq!(scrub("start: 10.1.1.168:33339 monitor", ""), "start: <ip>:33339 monitor");
        assert_eq!(scrub("a 192.168.0.1, b 8.8.8.8.", ""), "a <ip>, b <ip>.");
        assert_eq!(scrub("Version 10.0.19045.5555 and 0.1.3", ""), "Version 10.0.19045.5555 and 0.1.3");
        assert_eq!(scrub("300.1.1.1 is not an address", ""), "300.1.1.1 is not an address");
    }

    #[test]
    fn receiver_names_go() {
        let line = r#"Casting { receiver: "Anna's TV", width: 1728 } and receiver: "X" again"#;
        assert_eq!(scrub(line, ""), r#"Casting { receiver: "<name>", width: 1728 } and receiver: "<name>" again"#);
        // Unfinished: what follows the key goes on as it is, no panic.
        assert_eq!(scrub("receiver: \"half", ""), "receiver: \"half");
    }

    #[test]
    fn the_home_folder_goes_in_either_spelling_and_case() {
        let home = r"C:\Users\Anna";
        assert_eq!(scrub(r"C:\Users\Anna\AppData\x and c:\users\anna\y", home), r"~\AppData\x and ~\y");
        assert_eq!(scrub("C:/Users/Anna/AppData", home), "~/AppData");
        assert_eq!(scrub("/home/anna/.cache/x", "/home/anna"), "~/.cache/x");
        // A home folder too short to be one is not looked for (it would match everywhere).
        assert_eq!(scrub("a/b", "/"), "a/b");
    }

    #[test]
    fn the_newest_lines_that_fit() {
        let lines: Vec<String> = (1..=5).map(|n| format!("line {n}")).collect();
        assert_eq!(newest(&lines, 100), "line 1\nline 2\nline 3\nline 4\nline 5");
        assert_eq!(newest(&lines, 21), "line 3\nline 4\nline 5");
        assert_eq!(newest(&lines, 3), "");
    }

    #[test]
    fn a_report_stays_within_the_limit_and_is_scrubbed() {
        let log: Vec<String> = (0..20_000).map(|n| format!("2026-10-29 start: 10.1.1.{}:3 receiver: \"TV {n}\"", n % 250)).collect();
        let text = report_text("CastBay Desktop 0.1.4\nSystem: Test", &log, "");
        assert!(text.len() <= MAX_CHARS, "{}", text.len());
        assert!(text.contains("<ip>") && text.contains("<name>") && !text.contains("10.1.1."));
        assert!(text.starts_with("CastBay Desktop 0.1.4"));
    }

    #[test]
    fn the_system_is_named_coarsely() {
        assert_eq!(windows_version("Microsoft Windows [Version 10.0.19045.5555]").as_deref(), Some("10.0.19045"));
        assert_eq!(windows_version("Windows"), None);
        assert_eq!(linux_distribution("NAME=\"Ubuntu\"\nID=ubuntu\nVERSION_ID=\"26.04\"\n"), "ubuntu 26.04");
        assert_eq!(linux_distribution("ID=arch\n"), "arch");
        assert_eq!(linux_distribution("PRETTY_NAME=x"), "linux");
        // Anything odd is not passed on.
        assert_eq!(linux_distribution("ID=\"a b c d\"\nVERSION_ID=1"), "linux");
    }

    #[test]
    fn the_answer_must_have_the_shape_of_an_id() {
        assert_eq!(parse_id(r#"{"id":"CB-7K3F9Q"}"#).as_deref(), Some("CB-7K3F9Q"));
        for bad in [r#"{"id":"CB-7k3f9q"}"#, r#"{"id":"XX-7K3F9Q"}"#, r#"{"id":"CB-7K3F9Q1"}"#, r#"{"error":"busy"}"#, "not json"] {
            assert_eq!(parse_id(bad), None, "{bad}");
        }
    }
}
