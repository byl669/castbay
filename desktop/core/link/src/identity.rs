//! The sender's long-term identity (a self-signed certificate) and the receivers it has met.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P256_SHA256};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::protocol::Error;

/// SHA-256 of a certificate's SubjectPublicKeyInfo: how both sides name a key.
pub fn fingerprint(cert_der: &[u8]) -> Result<[u8; 32], Error> {
    let (_, cert) = x509_parser::parse_x509_certificate(cert_der).map_err(|e| Error::Protocol(format!("certificate: {e}")))?;
    Ok(Sha256::digest(cert.tbs_certificate.subject_pki.raw).into())
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[derive(Clone)]
pub struct Identity {
    pub(crate) cert: Vec<u8>,
    pub(crate) key: Vec<u8>,
    pub fingerprint: [u8; 32],
}

impl Identity {
    /// Loads the identity in `dir`, creating it the first time.
    pub fn load_or_create(dir: &Path) -> Result<Identity, Error> {
        fs::create_dir_all(dir)?;
        let cert_path = dir.join("cert.der");
        let key_path = dir.join("key.der");
        if let (Ok(cert), Ok(key)) = (fs::read(&cert_path), fs::read(&key_path)) {
            let fingerprint = fingerprint(&cert)?;
            return Ok(Identity { cert, key, fingerprint });
        }
        let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).map_err(|e| Error::Protocol(e.to_string()))?;
        let mut params = CertificateParams::new(Vec::<String>::new()).map_err(|e| Error::Protocol(e.to_string()))?;
        params.distinguished_name = DistinguishedName::new();
        params.distinguished_name.push(DnType::CommonName, "CastBay Desktop");
        // Valid for a long time: nothing checks the date, the key is the identity.
        params.not_before = rcgen::date_time_ymd(2024, 1, 1);
        params.not_after = rcgen::date_time_ymd(2054, 1, 1);
        let cert = params.self_signed(&key_pair).map_err(|e| Error::Protocol(e.to_string()))?;
        let (cert, key) = (cert.der().to_vec(), key_pair.serialize_der());
        write_private(&key_path, &key)?;
        fs::write(&cert_path, &cert)?;
        let fingerprint = fingerprint(&cert)?;
        Ok(Identity { cert, key, fingerprint })
    }

    pub(crate) fn rustls(&self) -> (Vec<CertificateDer<'static>>, PrivateKeyDer<'static>) {
        (
            vec![CertificateDer::from(self.cert.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key.clone())),
        )
    }
}

/// The key only its owner may read.
fn write_private(path: &Path, bytes: &[u8]) -> Result<(), Error> {
    fs::write(path, bytes)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}

/// One receiver as the file keeps it. Files written before names were kept hold just the key.
#[derive(Serialize, Deserialize, Clone)]
#[serde(untagged)]
enum Entry {
    Key(String),
    Known(Known),
}

#[derive(Serialize, Deserialize, Clone, Default)]
struct Known {
    fp: String,
    #[serde(default)]
    name: String,
    /// When the receiver was last connected to, in Unix seconds.
    #[serde(default)]
    seen: u64,
}

impl Entry {
    fn fp(&self) -> &str {
        match self {
            Entry::Key(fp) => fp,
            Entry::Known(k) => &k.fp,
        }
    }

    fn known(&self) -> Known {
        match self {
            Entry::Key(fp) => Known { fp: fp.clone(), ..Known::default() },
            Entry::Known(k) => k.clone(),
        }
    }
}

/// A receiver that was paired with, as listed to the person.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PinnedReceiver {
    pub id: String,
    /// What the receiver called itself last time (empty for pairings from before names were kept).
    pub name: String,
    /// When it was last connected to, in Unix seconds (0 when not known).
    pub seen: u64,
}

/// Receivers this sender has paired with, by their id: the key each presented, to be
/// presented again. A receiver whose key changes is not the same receiver.
pub struct Pins {
    path: PathBuf,
    map: HashMap<String, Entry>,
}

impl Pins {
    pub fn load(dir: &Path) -> Pins {
        let path = dir.join("receivers.json");
        let map = fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        Pins { path, map }
    }

    pub fn get(&self, receiver_id: &str) -> Option<[u8; 32]> {
        let text = self.map.get(receiver_id)?.fp();
        let mut out = [0u8; 32];
        if text.len() != 64 {
            return None;
        }
        for i in 0..32 {
            out[i] = u8::from_str_radix(&text[i * 2..i * 2 + 2], 16).ok()?;
        }
        Some(out)
    }

    /// Remembers a receiver's key, its name (kept when `name` is empty) and that it was just seen.
    pub fn pin(&mut self, receiver_id: &str, fingerprint: &[u8; 32], name: &str) -> Result<(), Error> {
        let mut known = self.map.get(receiver_id).map(Entry::known).unwrap_or_default();
        known.fp = hex(fingerprint);
        if !name.is_empty() {
            known.name = name.to_string();
        }
        known.seen = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        self.map.insert(receiver_id.to_string(), Entry::Known(known));
        self.save()
    }

    pub fn forget(&mut self, receiver_id: &str) -> Result<(), Error> {
        self.map.remove(receiver_id);
        self.save()
    }

    /// The receivers paired with, the most recently connected first.
    pub fn list(&self) -> Vec<PinnedReceiver> {
        let mut all: Vec<PinnedReceiver> = self
            .map
            .iter()
            .map(|(id, e)| {
                let k = e.known();
                PinnedReceiver { id: id.clone(), name: k.name, seen: k.seen }
            })
            .collect();
        all.sort_by(|a, b| b.seen.cmp(&a.seen).then_with(|| a.name.cmp(&b.name)).then_with(|| a.id.cmp(&b.id)));
        all
    }

    fn save(&self) -> Result<(), Error> {
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        fs::write(&self.path, serde_json::to_vec_pretty(&self.map).expect("a map serialises"))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("castbay-link-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn an_identity_is_created_once_and_loaded_after() {
        let dir = temp("identity");
        let first = Identity::load_or_create(&dir).unwrap();
        let second = Identity::load_or_create(&dir).unwrap();
        assert_eq!(first.fingerprint, second.fingerprint);
        assert_ne!(first.fingerprint, [0u8; 32]);
        let other = Identity::load_or_create(&temp("identity-other")).unwrap();
        assert_ne!(first.fingerprint, other.fingerprint);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn pins_are_kept_between_runs() {
        let dir = temp("pins");
        fs::create_dir_all(&dir).unwrap();
        let mut pins = Pins::load(&dir);
        assert!(pins.get("abc").is_none());
        pins.pin("abc", &[7u8; 32], "Living room TV").unwrap();
        assert_eq!(Pins::load(&dir).get("abc"), Some([7u8; 32]));
        pins.forget("abc").unwrap();
        assert!(Pins::load(&dir).get("abc").is_none());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn names_are_kept_and_listed_newest_first() {
        let dir = temp("pins-list");
        fs::create_dir_all(&dir).unwrap();
        let mut pins = Pins::load(&dir);
        pins.pin("a", &[1u8; 32], "Bedroom").unwrap();
        pins.pin("b", &[2u8; 32], "Living room").unwrap();
        // Connecting again refreshes the time and keeps the name when none is given.
        std::thread::sleep(std::time::Duration::from_millis(1100));
        pins.pin("a", &[1u8; 32], "").unwrap();
        let list = Pins::load(&dir).list();
        assert_eq!(list.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), ["a", "b"]);
        assert_eq!(list[0].name, "Bedroom");
        assert!(list[0].seen > list[1].seen);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_file_from_before_names_were_kept_still_loads() {
        let dir = temp("pins-old");
        fs::create_dir_all(&dir).unwrap();
        let key = "07".repeat(32);
        fs::write(dir.join("receivers.json"), format!(r#"{{"abc": "{key}"}}"#)).unwrap();
        let mut pins = Pins::load(&dir);
        assert_eq!(pins.get("abc"), Some([7u8; 32]));
        assert_eq!(pins.list(), vec![PinnedReceiver { id: "abc".into(), name: String::new(), seen: 0 }]);
        // Meeting it again fills in the name; the file is then in the new format.
        pins.pin("abc", &[7u8; 32], "Old TV").unwrap();
        let reread = Pins::load(&dir);
        assert_eq!(reread.get("abc"), Some([7u8; 32]));
        assert_eq!(reread.list()[0].name, "Old TV");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_broken_file_is_an_empty_list_not_a_crash() {
        let dir = temp("pins-bad");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("receivers.json"), b"not json").unwrap();
        assert!(Pins::load(&dir).list().is_empty());
        let _ = fs::remove_dir_all(dir);
    }
}
