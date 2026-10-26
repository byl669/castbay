//! Framing and the pairing code. Pure functions, tested against the receiver's fixtures.

use std::fmt;

use sha2::{Digest, Sha256};

pub const VERSION: u32 = 1;
pub const TYPE_CONTROL: u8 = 1;
pub const TYPE_VIDEO: u8 = 2;
pub const TYPE_AUDIO: u8 = 3;
pub const MAX_MESSAGE: usize = 8 * 1024 * 1024;
pub const MAX_CONTROL: usize = 64 * 1024;

#[derive(Debug)]
pub enum Error {
    /// The receiver said no: `busy`, `blocked`, `approval_needed`, `locked`, `wrong_password`, `unsupported`.
    Rejected(String),
    /// The pairing code was wrong (`wrong_code`) or pairing is paused (`locked`).
    PairingFailed(String),
    /// The receiver needs a code and none was given.
    CodeRequired,
    /// A known receiver presented another key than the one pinned: not the same receiver.
    ReceiverChanged,
    Protocol(String),
    Io(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Rejected(reason) => write!(f, "the receiver refused: {reason}"),
            Error::PairingFailed(reason) => write!(f, "pairing failed: {reason}"),
            Error::CodeRequired => write!(f, "the receiver needs a pairing code"),
            Error::ReceiverChanged => write!(f, "this is not the receiver paired before (its key changed)"),
            Error::Protocol(what) => write!(f, "protocol error: {what}"),
            Error::Io(what) => write!(f, "network error: {what}"),
        }
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e.to_string())
    }
}

/// The six-digit code both sides compute from the two certificate fingerprints.
pub fn verification_code(receiver: &[u8; 32], sender: &[u8; 32]) -> String {
    let mut hash = Sha256::new();
    hash.update(b"castbay-link-sas-v1");
    hash.update(receiver);
    hash.update(sender);
    let digest = hash.finalize();
    let value = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) as u64;
    format!("{:06}", value % 1_000_000)
}

/// One control message as a frame.
pub fn control_frame(json: &serde_json::Value) -> Vec<u8> {
    let payload = serde_json::to_vec(json).expect("a JSON value serialises");
    let mut out = Vec::with_capacity(5 + payload.len());
    out.push(TYPE_CONTROL);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(&payload);
    out
}

/// One raw AAC-LC frame as a frame.
pub fn audio_frame(pts_us: u64, frame: &[u8]) -> Vec<u8> {
    let length = 8 + frame.len();
    let mut out = Vec::with_capacity(5 + length);
    out.push(TYPE_AUDIO);
    out.extend_from_slice(&(length as u32).to_be_bytes());
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.extend_from_slice(frame);
    out
}

/// One access unit as a frame.
pub fn video_frame(pts_us: u64, key: bool, unit: &[u8]) -> Vec<u8> {
    let length = 9 + unit.len();
    let mut out = Vec::with_capacity(5 + length);
    out.push(TYPE_VIDEO);
    out.extend_from_slice(&(length as u32).to_be_bytes());
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.push(key as u8);
    out.extend_from_slice(unit);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_code_matches_the_receivers_fixture() {
        // The same value as LinkProtocolTest on Android.
        assert_eq!(verification_code(&[0x11; 32], &[0x22; 32]), "130806");
    }

    #[test]
    fn the_code_depends_on_both_keys_and_their_order() {
        let a = [1u8; 32];
        let b = [2u8; 32];
        assert_ne!(verification_code(&a, &b), verification_code(&b, &a));
        assert_ne!(verification_code(&a, &b), verification_code(&a, &[3u8; 32]));
        assert_eq!(verification_code(&a, &b).len(), 6);
    }

    #[test]
    fn frames_have_the_receivers_layout() {
        let video = video_frame(0x0102030405060708, true, &[9, 9]);
        assert_eq!(video[0], TYPE_VIDEO);
        let audio = audio_frame(7, &[1, 2, 3]);
        assert_eq!(audio[0], TYPE_AUDIO);
        assert_eq!(&audio[1..5], &11u32.to_be_bytes());
        assert_eq!(&audio[5..13], &7u64.to_be_bytes());
        assert_eq!(&audio[13..], &[1, 2, 3]);
        assert_eq!(&video[1..5], &11u32.to_be_bytes());
        assert_eq!(&video[5..13], &0x0102030405060708u64.to_be_bytes());
        assert_eq!(video[13], 1);
        assert_eq!(&video[14..], &[9, 9]);

        let control = control_frame(&serde_json::json!({"t": "ping"}));
        assert_eq!(control[0], TYPE_CONTROL);
        assert_eq!(&control[5..], br#"{"t":"ping"}"#);
    }
}
