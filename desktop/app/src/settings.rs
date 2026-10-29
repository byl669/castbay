//! The person's choices, kept in `settings.json` in the app's data folder (not in the web page, so
//! the tray, the start-up path and the engine all see the same ones).

use std::path::Path;

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(default)]
pub struct Settings {
    /// The most the picture may use, in Mbit/s ([`BITRATE_MBPS`]); a poor connection lowers it.
    pub bitrate_mbps: u32,
    /// Pictures per second: 30 or 60.
    pub fps: u32,
    pub send_audio: bool,
    /// Send a problem report by itself when the program ended badly or a cast failed (off until the
    /// person turns it on; otherwise they are asked after a crash).
    pub error_reports: bool,
    /// `auto`, `amf`, `nvenc` or `x264`.
    pub encoder: String,
    /// `auto` (follow the system), `zh` or `en`.
    pub language: String,
    /// The receiver picked last time (its id).
    pub last_receiver: String,
    /// `monitor` or `window`, and what was picked in each.
    pub source_kind: String,
    pub source_monitor: String,
    pub source_window: String,
    /// The first-run card was dismissed.
    pub welcome_done: bool,
    /// Values kept by older versions in the page's own storage were copied here.
    pub migrated: bool,
}

impl Default for Settings {
    fn default() -> Settings {
        Settings {
            bitrate_mbps: 8,
            fps: 30,
            send_audio: true,
            error_reports: false,
            encoder: "auto".into(),
            language: "auto".into(),
            last_receiver: String::new(),
            source_kind: "monitor".into(),
            source_monitor: String::new(),
            source_window: String::new(),
            welcome_done: false,
            migrated: false,
        }
    }
}

/// What the slider offers (a version before 0.1.4 had three fixed qualities: 4, 8 and 10 Mbit/s).
pub const BITRATE_MBPS: std::ops::RangeInclusive<u32> = 2..=20;
const FPS: [u32; 2] = [30, 60];
const ENCODERS: [&str; 4] = ["auto", "amf", "nvenc", "x264"];
const LANGUAGES: [&str; 3] = ["auto", "zh", "en"];
const KINDS: [&str; 2] = ["monitor", "window"];

impl Settings {
    /// What is saved; defaults for a missing, unreadable or damaged file (and for fields it lacks).
    pub fn load(dir: &Path) -> Settings {
        let Some(value) = std::fs::read(dir.join("settings.json")).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()) else {
            return Settings::default();
        };
        let mut settings: Settings = serde_json::from_value(value.clone()).unwrap_or_default();
        // A file from before the two settings: its quality becomes what that quality meant.
        if value.get("bitrate_mbps").is_none() && value.get("fps").is_none() {
            if let Some(quality) = value["quality"].as_str() {
                settings.use_quality(quality);
            }
        }
        // A value out of range (a hand-edited file) is as good as none.
        if !BITRATE_MBPS.contains(&settings.bitrate_mbps) {
            settings.bitrate_mbps = Settings::default().bitrate_mbps;
        }
        if !FPS.contains(&settings.fps) {
            settings.fps = Settings::default().fps;
        }
        settings
    }

    /// Writes the file whole or not at all (a crash in the middle leaves the old one).
    pub fn save(&self, dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        let temporary = dir.join("settings.json.tmp");
        std::fs::write(&temporary, serde_json::to_vec_pretty(self).expect("settings serialise")).map_err(|e| e.to_string())?;
        std::fs::rename(&temporary, dir.join("settings.json")).map_err(|e| e.to_string())
    }

    /// Applies the fields present in `patch` (an object); a value that is not allowed changes nothing
    /// at all. Unknown fields are ignored.
    pub fn apply(&mut self, patch: &Value) -> Result<(), String> {
        let Some(object) = patch.as_object() else {
            return Err("settings must be an object".into());
        };
        let mut next = self.clone();
        for (key, value) in object {
            match key.as_str() {
                "bitrate_mbps" => next.bitrate_mbps = number(value, BITRATE_MBPS, key)?,
                "fps" => next.fps = number(value, 30..=60, key).and_then(|n| if FPS.contains(&n) { Ok(n) } else { Err(format!("{key}: 30 or 60")) })?,
                // What the page kept in its own storage before settings.json (three fixed qualities).
                "quality" => match value.as_str() {
                    Some(q @ ("saver" | "balanced" | "smooth")) => next.use_quality(q),
                    _ => return Err(format!("{key}: not one of saver, balanced, smooth")),
                },
                "encoder" => next.encoder = choice(value, &ENCODERS, key)?,
                "language" => next.language = choice(value, &LANGUAGES, key)?,
                "source_kind" => next.source_kind = choice(value, &KINDS, key)?,
                "send_audio" => next.send_audio = boolean(value, key)?,
                "error_reports" => next.error_reports = boolean(value, key)?,
                "welcome_done" => next.welcome_done = boolean(value, key)?,
                "migrated" => next.migrated = boolean(value, key)?,
                "last_receiver" => next.last_receiver = text(value, key)?,
                "source_monitor" => next.source_monitor = text(value, key)?,
                "source_window" => next.source_window = text(value, key)?,
                _ => {}
            }
        }
        *self = next;
        Ok(())
    }

    /// What the three fixed qualities of earlier versions meant.
    fn use_quality(&mut self, quality: &str) {
        (self.fps, self.bitrate_mbps) = match quality {
            "smooth" => (60, 10),
            "saver" => (30, 4),
            _ => (30, 8),
        };
    }

    /// Pictures per second and bits per second to cast with.
    pub fn quality_params(&self) -> (u32, u32) {
        (self.fps, self.bitrate_mbps * 1_000_000)
    }

    /// Whether the interface (and the tray) should speak Chinese given what the system speaks.
    pub fn chinese(&self, system_is_chinese: bool) -> bool {
        match self.language.as_str() {
            "zh" => true,
            "en" => false,
            _ => system_is_chinese,
        }
    }
}

fn choice(value: &Value, allowed: &[&str], key: &str) -> Result<String, String> {
    match value.as_str() {
        Some(s) if allowed.contains(&s) => Ok(s.to_string()),
        _ => Err(format!("{key}: not one of {}", allowed.join(", "))),
    }
}

fn number(value: &Value, allowed: std::ops::RangeInclusive<u32>, key: &str) -> Result<u32, String> {
    match value.as_u64() {
        Some(n) if allowed.contains(&(n as u32)) && n <= u32::MAX as u64 => Ok(n as u32),
        _ => Err(format!("{key}: a whole number from {} to {}", allowed.start(), allowed.end())),
    }
}

fn boolean(value: &Value, key: &str) -> Result<bool, String> {
    value.as_bool().ok_or_else(|| format!("{key}: must be true or false"))
}

fn text(value: &Value, key: &str) -> Result<String, String> {
    match value.as_str() {
        Some(s) if s.len() <= 512 => Ok(s.to_string()),
        _ => Err(format!("{key}: must be text of at most 512 bytes")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn temp(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("castbay-settings-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn a_new_install_gets_the_defaults() {
        let s = Settings::load(&temp("none"));
        assert_eq!(s, Settings::default());
        assert_eq!((s.send_audio, s.encoder.as_str(), s.error_reports), (true, "auto", false));
        assert_eq!(s.quality_params(), (30, 8_000_000));
    }

    #[test]
    fn settings_survive_a_restart() {
        let dir = temp("roundtrip");
        let mut s = Settings::default();
        s.apply(&json!({"fps": 60, "bitrate_mbps": 12, "send_audio": false, "last_receiver": "tv-1", "source_window": "Notes"})).unwrap();
        s.save(&dir).unwrap();
        let again = Settings::load(&dir);
        assert_eq!(again, s);
        assert_eq!(again.quality_params(), (60, 12_000_000));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_bad_value_changes_nothing() {
        let mut s = Settings::default();
        assert!(s.apply(&json!({"fps": 60, "encoder": "magic"})).is_err());
        for bad in [json!({"fps": 45}), json!({"fps": "60"}), json!({"bitrate_mbps": 1}), json!({"bitrate_mbps": 21}), json!({"bitrate_mbps": 7.5}), json!({"bitrate_mbps": -3}), json!({"quality": "best"})] {
            assert!(s.apply(&bad).is_err(), "{bad}");
        }
        assert_eq!(s, Settings::default());
        assert!(s.apply(&json!({"send_audio": "yes"})).is_err());
        assert!(s.apply(&json!({"last_receiver": "x".repeat(600)})).is_err());
        assert!(s.apply(&json!("fps")).is_err());
        assert_eq!(s, Settings::default());
    }

    #[test]
    fn unknown_fields_are_ignored_and_a_patch_changes_only_what_it_names() {
        let mut s = Settings::default();
        s.apply(&json!({"language": "zh", "from_the_future": 1})).unwrap();
        assert_eq!(s.language, "zh");
        assert_eq!((s.fps, s.bitrate_mbps), (30, 8));
    }

    #[test]
    fn a_damaged_file_or_missing_fields_fall_back_to_defaults() {
        let dir = temp("damaged");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("settings.json"), b"{ broken").unwrap();
        assert_eq!(Settings::load(&dir), Settings::default());
        // A file from a version with fewer fields keeps what it has.
        std::fs::write(dir.join("settings.json"), br#"{"language": "en"}"#).unwrap();
        let s = Settings::load(&dir);
        assert_eq!((s.language.as_str(), s.send_audio, s.quality_params()), ("en", true, (30, 8_000_000)));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn the_three_qualities_of_earlier_versions_become_a_rate_and_a_bitrate() {
        let dir = temp("qualities");
        std::fs::create_dir_all(&dir).unwrap();
        for (quality, expected) in [("saver", (30, 4_000_000)), ("balanced", (30, 8_000_000)), ("smooth", (60, 10_000_000)), ("unheard-of", (30, 8_000_000))] {
            std::fs::write(dir.join("settings.json"), format!(r#"{{"quality": "{quality}"}}"#)).unwrap();
            assert_eq!(Settings::load(&dir).quality_params(), expected, "{quality}");
        }
        // Once the two settings exist the old one is not looked at again.
        std::fs::write(dir.join("settings.json"), br#"{"quality": "smooth", "bitrate_mbps": 6, "fps": 30}"#).unwrap();
        assert_eq!(Settings::load(&dir).quality_params(), (30, 6_000_000));
        // A hand-edited value out of range falls back to the default.
        std::fs::write(dir.join("settings.json"), br#"{"bitrate_mbps": 500, "fps": 144}"#).unwrap();
        assert_eq!(Settings::load(&dir).quality_params(), (30, 8_000_000));
        // The page's old storage can still hand over a quality.
        let mut s = Settings::default();
        s.apply(&json!({"quality": "smooth"})).unwrap();
        assert_eq!(s.quality_params(), (60, 10_000_000));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn the_language_setting_overrides_the_system() {
        let mut s = Settings::default();
        assert!(s.chinese(true) && !s.chinese(false));
        s.apply(&json!({"language": "en"})).unwrap();
        assert!(!s.chinese(true));
        s.apply(&json!({"language": "zh"})).unwrap();
        assert!(s.chinese(false));
    }
}
