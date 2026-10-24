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
                let receiver = Receiver {
                    id: text("id").unwrap_or_else(|| info.get_fullname().to_string()),
                    name: text("name").unwrap_or_else(|| info.get_fullname().split('.').next().unwrap_or("").to_string()),
                    addresses: info.get_addresses().iter().copied().collect(),
                    port: info.get_port(),
                    pair: match text("pair").as_deref() {
                        Some("ask") => PairMode::Ask,
                        Some("pin") => PairMode::Pin,
                        _ => PairMode::Open,
                    },
                    password: text("pw").as_deref() == Some("1"),
                };
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
