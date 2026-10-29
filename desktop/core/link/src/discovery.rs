//! Finding receivers with mDNS (`_castbay._tcp`).

use std::net::IpAddr;
use std::time::{Duration, Instant};

use mdns_sd::{ServiceDaemon, ServiceEvent};

use crate::protocol::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairMode {
    /// Anyone may cast; the first connection is trusted.
    Open,
    /// A new sender is allowed on the TV first.
    Ask,
    /// A code the TV shows is typed here, once.
    Pin,
}

#[derive(Debug, Clone)]
pub struct Receiver {
    pub id: String,
    pub name: String,
    pub addresses: Vec<IpAddr>,
    pub port: u16,
    pub pair: PairMode,
    /// The receiver wants a password in `hello`.
    pub password: bool,
    /// The device's model and the receiving app's version, when it says (older ones do not).
    pub model: Option<String>,
    pub app_version: Option<String>,
}

/// A receiver from the text records of its service (`text` looks a key up) and where it was seen.
fn receiver_from(text: impl Fn(&str) -> Option<String>, fullname: &str, addresses: Vec<IpAddr>, port: u16) -> Receiver {
    // Hints from the network: shown to the person, so only plain, short text is taken.
    let plain = |key: &str| text(key).map(|v| v.trim().chars().filter(|c| !c.is_control()).take(40).collect::<String>()).filter(|v| !v.is_empty());
    Receiver {
        id: text("id").unwrap_or_else(|| fullname.to_string()),
        name: text("name").unwrap_or_else(|| fullname.split('.').next().unwrap_or("").to_string()),
        addresses,
        port,
        pair: match text("pair").as_deref() {
            Some("ask") => PairMode::Ask,
            Some("pin") => PairMode::Pin,
            _ => PairMode::Open,
        },
        password: text("pw").as_deref() == Some("1"),
        model: plain("model"),
        app_version: plain("app"),
    }
}

/// Browses for `listen` and returns the receivers seen by then.
pub fn browse(listen: Duration) -> Result<Vec<Receiver>, Error> {
    let daemon = ServiceDaemon::new().map_err(|e| Error::Io(e.to_string()))?;
    let events = daemon.browse("_castbay._tcp.local.").map_err(|e| Error::Io(e.to_string()))?;
    let until = Instant::now() + listen;
    let mut found: Vec<Receiver> = Vec::new();
    while let Some(left) = until.checked_duration_since(Instant::now()) {
        match events.recv_timeout(left) {
            Ok(ServiceEvent::ServiceResolved(info)) => {
                let text = |key: &str| info.get_property_val_str(key).map(str::to_string);
                let receiver = receiver_from(text, info.get_fullname(), info.get_addresses().iter().copied().collect(), info.get_port());
                found.retain(|r| r.id != receiver.id);
                found.push(receiver);
            }
            Ok(_) => {}
            Err(_) => break,
        }
    }
    let _ = daemon.shutdown();
    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn from(pairs: &[(&str, &str)]) -> Receiver {
        let map: HashMap<String, String> = pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        receiver_from(|key| map.get(key).cloned(), "CastBay._castbay._tcp.local.", vec![], 7000)
    }

    #[test]
    fn the_model_and_version_are_read_when_there_are_any() {
        let r = from(&[("id", "x"), ("name", "TV"), ("model", "BRAVIA 4K"), ("app", "1.8.9"), ("pair", "pin"), ("pw", "1")]);
        assert_eq!((r.model.as_deref(), r.app_version.as_deref(), r.pair, r.password), (Some("BRAVIA 4K"), Some("1.8.9"), PairMode::Pin, true));
    }

    #[test]
    fn an_older_receiver_has_neither() {
        let r = from(&[("id", "x"), ("name", "TV")]);
        assert_eq!((r.model, r.app_version), (None, None));
    }

    #[test]
    fn what_the_network_says_is_kept_short_and_plain() {
        let r = from(&[("model", "  A\u{7}B\nC  "), ("app", &"9".repeat(100))]);
        assert_eq!(r.model.as_deref(), Some("ABC"));
        assert_eq!(r.app_version.unwrap().len(), 40);
        assert_eq!(from(&[("model", "   ")]).model, None);
    }

    #[test]
    fn without_an_id_or_name_the_service_name_stands_in() {
        let r = from(&[]);
        assert_eq!((r.id.as_str(), r.name.as_str()), ("CastBay._castbay._tcp.local.", "CastBay"));
    }
}
