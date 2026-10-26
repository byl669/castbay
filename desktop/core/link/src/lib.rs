//! CastBay Link, the sender side (desktop/docs/protocol.md): finding receivers, pairing with
//! one, and sending H.264 to it over TLS. Nothing here knows about capture or encoding.

mod discovery;
mod identity;
mod protocol;
mod session;

pub use discovery::{browse, PairMode, Receiver};
pub use identity::{Identity, PinnedReceiver, Pins};
pub use protocol::{verification_code, Error};
pub use session::{connect, AudioOffer, Config, Event, PairingCode, SendResult, Session};
