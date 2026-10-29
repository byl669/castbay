//! Installing a newer release from inside the app: the release page of the new version is asked for
//! the installer that fits this system, the file is downloaded and checked against the SHA-256
//! GitHub lists for it, and then Windows installs it over this version (and starts it again) while
//! on Linux the system's own installer is opened. The transfers are done by `curl` (part of Windows
//! 10 1803 and later, a dependency of the .deb), so the app carries no web client of its own.

use std::path::{Path, PathBuf};
use std::process::Command;

use serde_json::Value;
use sha2::{Digest, Sha256};

const RELEASES: &str = "https://api.github.com/repos/weenas/castbay/releases/tags/desktop-v";
const DOWNLOADS: &str = "https://github.com/weenas/castbay/releases/download/desktop-v";
/// Far more than any installer of this program; stops a wrong file from filling the disk.
const MAX_BYTES: u64 = 300_000_000;

/// A version as it appears in a tag: digits and dots, optionally a `-suffix`. Anything else is not
/// put into an address or a file name.
pub fn valid_version(v: &str) -> bool {
    let (core, suffix) = v.split_once('-').map_or((v, ""), |(a, b)| (a, b));
    !core.is_empty()
        && core.split('.').all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
        && suffix.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.')
}

/// The release file this system installs: the .msi in the person's language on Windows, the .deb
/// of this architecture on Linux.
pub fn asset_name(version: &str, windows: bool, zh: bool, arch: &str) -> String {
    if windows {
        format!("CastBay-Desktop-{version}-windows-x64-{}.msi", if zh { "zh-CN" } else { "en-US" })
    } else {
        format!("castbay-desktop_{version}_{arch}.deb")
    }
}

/// The download address and SHA-256 (lower case hex) of the asset called `name` in a release's JSON.
pub fn find_asset(release: &Value, name: &str, version: &str) -> Result<(String, String), String> {
    let asset = release["assets"]
        .as_array()
        .and_then(|list| list.iter().find(|a| a["name"].as_str() == Some(name)))
        .ok_or_else(|| format!("the release has no {name}"))?;
    let url = asset["browser_download_url"].as_str().unwrap_or_default();
    // Only this project's own release files, whatever the answer says.
    if url != format!("{DOWNLOADS}{version}/{name}") {
        return Err("the download address is not this project's".into());
    }
    let digest = asset["digest"].as_str().and_then(|d| d.strip_prefix("sha256:")).unwrap_or_default().to_ascii_lowercase();
    if digest.len() != 64 || !digest.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("the release lists no SHA-256 for the file".into());
    }
    Ok((url.to_string(), digest))
}

fn sha256_file(path: &Path) -> Result<String, String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path).map_err(|e| e.to_string())?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = file.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

fn curl() -> Command {
    let mut c = Command::new("curl");
    c.args(["--fail", "--silent", "--show-error", "--location", "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "15", "--max-time", "900"]);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // no console window
    }
    c
}

fn describe(output: &std::process::Output) -> String {
    let text = String::from_utf8_lossy(&output.stderr);
    let line = text.lines().last().unwrap_or("").trim();
    format!("curl: {}", if line.is_empty() { output.status.to_string() } else { line.to_string() })
}

/// Downloads the installer for `version` into `dir` and checks it. Returns the file.
pub fn fetch(version: &str, windows: bool, zh: bool, dir: &Path) -> Result<PathBuf, String> {
    if !valid_version(version) {
        return Err("not a version".into());
    }
    let arch = if cfg!(target_arch = "aarch64") { "arm64" } else { "amd64" };
    let name = asset_name(version, windows, zh, arch);

    let out = curl().args(["--header", "Accept: application/vnd.github+json", &format!("{RELEASES}{version}")]).output().map_err(|e| format!("curl: {e}"))?;
    if !out.status.success() {
        return Err(describe(&out));
    }
    let release: Value = serde_json::from_slice(&out.stdout).map_err(|e| e.to_string())?;
    let (url, digest) = find_asset(&release, &name, version)?;

    let _ = std::fs::remove_dir_all(dir);
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let file = dir.join(&name);
    let out = curl().args(["--max-filesize", &MAX_BYTES.to_string(), "--output"]).arg(&file).arg(&url).output().map_err(|e| format!("curl: {e}"))?;
    if !out.status.success() {
        let _ = std::fs::remove_file(&file);
        return Err(describe(&out));
    }
    if sha256_file(&file)? != digest {
        let _ = std::fs::remove_file(&file);
        return Err("the downloaded file does not match its SHA-256".into());
    }
    Ok(file)
}

/// Windows: installs the .msi over this version with only a progress bar, then starts the program
/// again (the installer closes this one). The caller ends this process afterwards.
#[cfg(windows)]
pub fn install(msi: &Path, exe: &Path) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    let (msi, exe) = (msi.to_string_lossy(), exe.to_string_lossy());
    // The two paths go into one command line of cmd.exe, where these would mean something else.
    if msi.chars().chain(exe.chars()).any(|c| matches!(c, '"' | '%' | '&' | '^' | '!' | '\r' | '\n')) {
        return Err("a path holds a character the installer line cannot carry".into());
    }
    // 0x08 DETACHED_PROCESS | 0x200 CREATE_NEW_PROCESS_GROUP | 0x0800_0000 CREATE_NO_WINDOW: it outlives this program.
    Command::new("cmd")
        .raw_arg(format!("/c msiexec /i \"{msi}\" /passive /norestart & start \"\" \"{exe}\""))
        .creation_flags(0x08 | 0x200 | 0x0800_0000)
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn versions_are_plain() {
        assert!(valid_version("0.1.3"));
        assert!(valid_version("1.0.0-beta.1"));
        for bad in ["", ".", "1..2", "1.2/../3", "1.2.3 ", "1.2.3?x=1", "a.b", "1.2.3-é", "-1"] {
            assert!(!valid_version(bad), "{bad}");
        }
    }

    #[test]
    fn asset_names_follow_the_release_workflow() {
        assert_eq!(asset_name("0.1.3", true, true, "amd64"), "CastBay-Desktop-0.1.3-windows-x64-zh-CN.msi");
        assert_eq!(asset_name("0.1.3", true, false, "amd64"), "CastBay-Desktop-0.1.3-windows-x64-en-US.msi");
        assert_eq!(asset_name("0.1.3", false, true, "amd64"), "castbay-desktop_0.1.3_amd64.deb");
    }

    fn release(url: &str, digest: Value) -> Value {
        json!({"assets": [
            {"name": "other.zip", "browser_download_url": "https://github.com/weenas/castbay/releases/download/desktop-v0.1.3/other.zip", "digest": null},
            {"name": "castbay-desktop_0.1.3_amd64.deb", "browser_download_url": url, "digest": digest},
        ]})
    }
    const DEB: &str = "https://github.com/weenas/castbay/releases/download/desktop-v0.1.3/castbay-desktop_0.1.3_amd64.deb";

    #[test]
    fn finds_the_asset_and_its_digest() {
        let hex = "AB".repeat(32);
        let (url, digest) = find_asset(&release(DEB, json!(format!("sha256:{hex}"))), "castbay-desktop_0.1.3_amd64.deb", "0.1.3").unwrap();
        assert_eq!(url, DEB);
        assert_eq!(digest, "ab".repeat(32));
    }

    #[test]
    fn refuses_what_it_cannot_check() {
        let name = "castbay-desktop_0.1.3_amd64.deb";
        assert!(find_asset(&release(DEB, Value::Null), name, "0.1.3").is_err());
        assert!(find_asset(&release(DEB, json!("sha256:abc")), name, "0.1.3").is_err());
        assert!(find_asset(&release(DEB, json!(format!("md5:{}", "a".repeat(64)))), name, "0.1.3").is_err());
        assert!(find_asset(&release(DEB, json!(format!("sha256:{}", "g".repeat(64)))), name, "0.1.3").is_err());
        assert!(find_asset(&release(DEB, json!(format!("sha256:{}", "a".repeat(64)))), "missing.deb", "0.1.3").is_err());
    }

    #[test]
    fn refuses_a_foreign_address() {
        let digest = json!(format!("sha256:{}", "a".repeat(64)));
        for url in ["https://evil.example/castbay-desktop_0.1.3_amd64.deb", "http://github.com/weenas/castbay/releases/download/desktop-v0.1.3/castbay-desktop_0.1.3_amd64.deb", "https://github.com/weenas/castbay/releases/download/desktop-v0.1.4/castbay-desktop_0.1.3_amd64.deb"] {
            assert!(find_asset(&release(url, digest.clone()), "castbay-desktop_0.1.3_amd64.deb", "0.1.3").is_err(), "{url}");
        }
    }

    #[test]
    fn hashes_a_file() {
        let path = std::env::temp_dir().join("castbay-update-test.bin");
        std::fs::write(&path, b"abc").unwrap();
        assert_eq!(sha256_file(&path).unwrap(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        let _ = std::fs::remove_file(path);
    }

    /// Needs the network: downloads and checks the installer of a published release.
    /// `cargo test -p castbay-desktop -- --ignored`
    #[test]
    #[ignore]
    fn fetches_a_published_release() {
        let dir = std::env::temp_dir().join("castbay-update-fetch");
        let file = fetch("0.1.2", false, false, &dir).unwrap();
        assert!(std::fs::metadata(&file).unwrap().len() > 1_000_000);
        let wrong = fetch("0.1.2", true, true, &dir).unwrap();
        assert!(wrong.to_string_lossy().ends_with("zh-CN.msi"));
        let _ = std::fs::remove_dir_all(dir);
    }
}
