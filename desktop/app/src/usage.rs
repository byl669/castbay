//! Anonymous usage statistics, only while the person has turned them on (Settings, off by default):
//! counts for the day (casts, how long they lasted in bands, the encoder, frame rate and bitrate
//! bands, failures), sent once a day as one summary with the system, the encoders that open and
//! the settings in use, under a random ID that is made when the switch is turned on and forgotten
//! (and deleted on the website) when it is turned off. Nothing is counted while it is off. The
//! privacy policy lists every field.
//!
//! The counts live in `usage.json` in the program's data folder: `{"id": "...", "days": {"2026-10-29":
//! {"cast.screen": 2}}}`. Days are UTC days. A day is sent once it is over, and kept until the
//! website has it (a week at most).

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde_json::{json, Map, Value};

/// One thread at a time reads and writes the file.
static FILE: Mutex<()> = Mutex::new(());
/// Days older than this are given up on.
const KEEP_DAYS: u64 = 7;

fn path(dir: &Path) -> PathBuf {
    dir.join("usage.json")
}

fn read(dir: &Path) -> Value {
    std::fs::read(path(dir)).ok().and_then(|b| serde_json::from_slice(&b).ok()).filter(Value::is_object).unwrap_or_else(|| json!({}))
}

fn write(dir: &Path, value: &Value) {
    let _ = std::fs::create_dir_all(dir);
    let temporary = dir.join("usage.json.tmp");
    if std::fs::write(&temporary, serde_json::to_vec(value).unwrap_or_default()).is_ok() {
        let _ = std::fs::rename(&temporary, path(dir));
    }
}

/// A random version 4 UUID, in the form the website checks.
pub fn new_id() -> String {
    let mut b = [0u8; 16];
    // Without randomness from the system there is no ID (the caller then counts nothing).
    if getrandom::fill(&mut b).is_err() {
        return String::new();
    }
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let hex: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &hex[0..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..32])
}

/// Statistics were turned on: an ID is made if there is none. Returns whether there is one.
pub fn turn_on(dir: &Path) -> bool {
    let _guard = FILE.lock().unwrap();
    let mut value = read(dir);
    if value["id"].as_str().is_some_and(|s| !s.is_empty()) {
        return true;
    }
    let id = new_id();
    if id.is_empty() {
        return false;
    }
    value["id"] = json!(id);
    value["days"] = json!({});
    write(dir, &value);
    true
}

/// Statistics were turned off: the ID and the counts are forgotten. Returns the ID, for the
/// website to be asked to delete what it holds.
pub fn turn_off(dir: &Path) -> Option<String> {
    let _guard = FILE.lock().unwrap();
    let id = read(dir)["id"].as_str().filter(|s| !s.is_empty()).map(str::to_string);
    let _ = std::fs::remove_file(path(dir));
    id
}

/// Counts `key` for `day`, if statistics are on (there is an ID).
pub fn count(dir: &Path, day: &str, key: &str) {
    let _guard = FILE.lock().unwrap();
    let mut value = read(dir);
    if value["id"].as_str().is_none_or(str::is_empty) {
        return;
    }
    let days = value["days"].as_object().cloned().unwrap_or_default();
    let mut days = days;
    let counts = days.entry(day.to_string()).or_insert_with(|| json!({}));
    let n = counts[key].as_u64().unwrap_or(0) + 1;
    counts[key] = json!(n);
    value["days"] = Value::Object(days);
    write(dir, &value);
}

/// The ID and each finished day's counts (days before `today`, not older than a week), oldest
/// first; older days are dropped here.
pub fn finished_days(dir: &Path, today: &str) -> Option<(String, Vec<(String, Map<String, Value>)>)> {
    let _guard = FILE.lock().unwrap();
    let mut value = read(dir);
    let id = value["id"].as_str().filter(|s| !s.is_empty())?.to_string();
    let oldest = days_before(today, KEEP_DAYS);
    let mut days = value["days"].as_object().cloned().unwrap_or_default();
    let before = days.len();
    days.retain(|day, _| day.as_str() >= oldest.as_str());
    if days.len() != before {
        value["days"] = Value::Object(days.clone());
        write(dir, &value);
    }
    let mut finished: Vec<(String, Map<String, Value>)> = days
        .into_iter()
        .filter(|(day, _)| day.as_str() < today)
        .filter_map(|(day, counts)| counts.as_object().cloned().filter(|c| !c.is_empty()).map(|c| (day, c)))
        .collect();
    finished.sort_by(|a, b| a.0.cmp(&b.0));
    Some((id, finished))
}

/// A day was sent (or has nothing to send): it is forgotten.
pub fn forget(dir: &Path, day: &str) {
    let _guard = FILE.lock().unwrap();
    let mut value = read(dir);
    if let Some(days) = value["days"].as_object_mut() {
        days.remove(day);
        write(dir, &value);
    }
}

/// `YYYY-MM-DD` of the UTC day `secs` Unix seconds fall in.
pub fn day_of(secs: u64) -> String {
    let (y, m, d) = civil(secs / 86400);
    format!("{y:04}-{m:02}-{d:02}")
}

fn days_before(day: &str, n: u64) -> String {
    let mut parts = day.split('-').filter_map(|p| p.parse::<i64>().ok());
    let (y, m, d) = (parts.next().unwrap_or(1970), parts.next().unwrap_or(1), parts.next().unwrap_or(1));
    let days = days_from_civil(y, m, d) - n as i64;
    day_of((days.max(0) as u64) * 86400)
}

/// Year, month, day from days since 1970-01-01 (Howard Hinnant's algorithm).
fn civil(days: u64) -> (i64, i64, i64) {
    let z = days as i64 + 719468;
    let era = z.div_euclid(146097);
    let doe = z.rem_euclid(146097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + (month <= 2) as i64, month, day)
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y.rem_euclid(400);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// The band a cast's length falls in: only the band is counted, never the time.
pub fn length_key(seconds: f64) -> &'static str {
    match seconds / 60.0 {
        m if m < 1.0 => "length.under1m",
        m if m < 10.0 => "length.1to10m",
        _ => "length.over10m",
    }
}

/// The band the bitrate falls in, in Mbit/s.
pub fn bitrate_key(mbps: u32) -> &'static str {
    match mbps {
        0..=5 => "bitrate.under6",
        6..=11 => "bitrate.6to11",
        _ => "bitrate.12plus",
    }
}

/// The height in pixels in a monitor's name ("0: 1920x1080 @ 60 Hz").
pub fn monitor_height(name: &str) -> Option<u32> {
    let size = name.split_whitespace().find(|word| word.contains('x') && word.split('x').all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit())))?;
    size.split('x').nth(1)?.parse().ok()
}

/// The class of a screen from its height in pixels.
pub fn screen_class(height: u32) -> &'static str {
    match height {
        2000.. => "2160p",
        1300.. => "1440p",
        1000.. => "1080p",
        700.. => "720p",
        _ => "smaller",
    }
}

/// How a failed cast is counted (the website knows only these).
pub fn failure_key(code: &str) -> &'static str {
    match code {
        "encoder" => "fail.encoder",
        "capture" => "fail.capture",
        "no_picture" => "fail.no_picture",
        "protocol" => "fail.protocol",
        "network" => "fail.network",
        _ => "fail.other",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("castbay-usage-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn nothing_is_counted_until_it_is_turned_on() {
        let dir = temp("off");
        count(&dir, "2026-10-28", "cast.screen");
        assert!(!path(&dir).exists());
        assert!(finished_days(&dir, "2026-10-29").is_none());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn counts_are_kept_per_day_and_only_finished_days_come_out() {
        let dir = temp("days");
        assert!(turn_on(&dir));
        count(&dir, "2026-10-27", "cast.screen");
        count(&dir, "2026-10-27", "cast.screen");
        count(&dir, "2026-10-28", "fail.encoder");
        count(&dir, "2026-10-29", "cast.window");
        let (id, days) = finished_days(&dir, "2026-10-29").unwrap();
        assert_eq!(id.len(), 36);
        assert_eq!(days.iter().map(|(d, _)| d.as_str()).collect::<Vec<_>>(), ["2026-10-27", "2026-10-28"]);
        assert_eq!(days[0].1["cast.screen"], 2);
        forget(&dir, "2026-10-27");
        assert_eq!(finished_days(&dir, "2026-10-29").unwrap().1.len(), 1);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn days_older_than_a_week_are_given_up_on() {
        let dir = temp("old");
        turn_on(&dir);
        count(&dir, "2026-10-01", "cast.screen");
        count(&dir, "2026-10-25", "cast.screen");
        let (_, days) = finished_days(&dir, "2026-10-29").unwrap();
        assert_eq!(days.len(), 1);
        assert_eq!(days[0].0, "2026-10-25");
        // And it is gone from the file, not only from the answer.
        assert!(!std::fs::read_to_string(path(&dir)).unwrap().contains("2026-10-01"));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn turning_off_forgets_everything_and_gives_the_id_back() {
        let dir = temp("forget");
        turn_on(&dir);
        let first = read(&dir)["id"].as_str().unwrap().to_string();
        assert!(turn_on(&dir), "turning on again keeps the ID");
        assert_eq!(read(&dir)["id"], json!(first));
        count(&dir, "2026-10-28", "cast.screen");
        assert_eq!(turn_off(&dir).as_deref(), Some(first.as_str()));
        assert!(!path(&dir).exists());
        assert_eq!(turn_off(&dir), None);
        turn_on(&dir);
        assert_ne!(read(&dir)["id"], json!(first), "a new ID after being off");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn an_id_looks_like_a_version_4_uuid() {
        let id = new_id();
        let parts: Vec<&str> = id.split('-').collect();
        assert_eq!(parts.iter().map(|p| p.len()).collect::<Vec<_>>(), [8, 4, 4, 4, 12]);
        assert!(parts[2].starts_with('4') && "89ab".contains(&parts[3][..1]));
        assert!(id.bytes().all(|b| b == b'-' || b.is_ascii_digit() || (b'a'..=b'f').contains(&b)));
        assert_ne!(new_id(), id);
    }

    #[test]
    fn a_monitors_height_comes_from_its_name() {
        assert_eq!(monitor_height("0: 1920x1080 @ 60 Hz"), Some(1080));
        assert_eq!(monitor_height("1: 3840x2160 @ 144 Hz"), Some(2160));
        assert_eq!(monitor_height("Display"), None);
        assert_eq!(monitor_height("0: axb"), None);
    }

    #[test]
    fn days_and_bands() {
        assert_eq!(day_of(0), "1970-01-01");
        assert_eq!(day_of(1_793_000_000), "2026-10-26");
        assert_eq!(days_before("2026-10-29", 7), "2026-10-22");
        assert_eq!(days_before("2026-03-03", 7), "2026-02-24");
        assert_eq!([length_key(30.0), length_key(60.0), length_key(599.0), length_key(600.0)], ["length.under1m", "length.1to10m", "length.1to10m", "length.over10m"]);
        assert_eq!([bitrate_key(2), bitrate_key(5), bitrate_key(6), bitrate_key(11), bitrate_key(12), bitrate_key(20)],
            ["bitrate.under6", "bitrate.under6", "bitrate.6to11", "bitrate.6to11", "bitrate.12plus", "bitrate.12plus"]);
        assert_eq!([screen_class(2160), screen_class(1440), screen_class(1080), screen_class(900), screen_class(480)], ["2160p", "1440p", "1080p", "720p", "smaller"]);
        assert_eq!([failure_key("encoder"), failure_key("busy"), failure_key("network")], ["fail.encoder", "fail.other", "fail.network"]);
    }
}
