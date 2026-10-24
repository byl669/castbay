//! The sender's long-term identity (a self-signed certificate) and the receivers it has met.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P256_SHA256};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
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

/// Receivers this sender has paired with, by their id: the key each presented, to be
/// presented again. A receiver whose key changes is not the same receiver.
pub struct Pins {
    path: PathBuf,
    map: HashMap<String, String>,
}

impl Pins {
    pub fn load(dir: &Path) -> Pins {
        let path = dir.join("receivers.json");
        let map = fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        Pins { path, map }
    }

    pub fn get(&self, receiver_id: &str) -> Option<[u8; 32]> {
        let text = self.map.get(receiver_id)?;
        let mut out = [0u8; 32];
        if text.len() != 64 {
            return None;
        }
        for i in 0..32 {
            out[i] = u8::from_str_radix(&text[i * 2..i * 2 + 2], 16).ok()?;
        }
        Some(out)
    }

    pub fn pin(&mut self, receiver_id: &str, fingerprint: &[u8; 32]) -> Result<(), Error> {
        self.map.insert(receiver_id.to_string(), hex(fingerprint));
        fs::write(&self.path, serde_json::to_vec_pretty(&self.map).expect("a map serialises"))?;
        Ok(())
    }

    pub fn forget(&mut self, receiver_id: &str) -> Result<(), Error> {
        self.map.remove(receiver_id);
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
        pins.pin("abc", &[7u8; 32]).unwrap();
        assert_eq!(Pins::load(&dir).get("abc"), Some([7u8; 32]));
        pins.forget("abc").unwrap();
        assert!(Pins::load(&dir).get("abc").is_none());
        let _ = fs::remove_dir_all(dir);
    }
}
