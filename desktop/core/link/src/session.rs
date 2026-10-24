//! A connection to a receiver: TLS, pairing, the offer, and then video.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};
use serde_json::{json, Value};
use tokio::io::{AsyncReadExt, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::timeout;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

use crate::identity::{fingerprint, hex, Identity, Pins};
use crate::protocol::{control_frame, verification_code, video_frame, Error, MAX_CONTROL, TYPE_CONTROL, VERSION};

#[derive(Debug, Clone)]
pub struct Config {
    /// What the receiver shows and remembers: the computer's name.
    pub name: String,
    /// Sent in `hello` when the receiver announced it wants one.
    pub password: Option<String>,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_bps: u32,
    /// `screen` or `window`.
    pub source: String,
}

/// Where the pairing code comes from: the person at the keyboard, who reads it off the TV.
pub trait PairingCode: Send {
    /// The code the TV shows, or None if the person gave up. `expected` is what the TV should
    /// be showing, for tests that stand in for the person; **a real app must not use it**, it
    /// asks the person (that is the whole point of the code).
    fn code(&mut self, expected: &str) -> Option<String>;
}

impl<F: FnMut(&str) -> Option<String> + Send> PairingCode for F {
    fn code(&mut self, expected: &str) -> Option<String> {
        self(expected)
    }
}

/// What the receiver tells us while casting.
#[derive(Debug, Clone)]
pub enum Event {
    /// The decoder lost its place (or frames were dropped here): send a key frame soon.
    KeyFrameRequested,
    Stats { decode_ms: i64, dropped: u64, fps: f64 },
    /// The session is over: the receiver said goodbye (`stopped_by_receiver`, `replaced`, ...) or the connection broke.
    Closed(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SendResult {
    Sent,
    /// The connection could not keep up: this picture was dropped, and so is every
    /// delta frame until the next key frame.
    Dropped,
    Closed,
}

/// Accepts any certificate: who the receiver is gets decided by its pinned key, after the handshake.
#[derive(Debug)]
struct AcceptAny(Arc<CryptoProvider>);

impl ServerCertVerifier for AcceptAny {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

fn tls_config(identity: &Identity) -> Result<ClientConfig, Error> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let (certs, key) = identity.rustls();
    ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|e| Error::Protocol(e.to_string()))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAny(provider)))
        .with_client_auth_cert(certs, key)
        .map_err(|e| Error::Protocol(e.to_string()))
}

type Reader = ReadHalf<TlsStream<TcpStream>>;
type Writer = WriteHalf<TlsStream<TcpStream>>;

/// Reads one message: a control message as JSON, anything else skipped (video is ours to send).
async fn read_control(reader: &mut Reader) -> Result<Option<Value>, Error> {
    loop {
        let mut head = [0u8; 5];
        match reader.read_exact(&mut head).await {
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) => return Err(e.into()),
        }
        let length = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
        let limit = if head[0] == TYPE_CONTROL { MAX_CONTROL } else { crate::protocol::MAX_MESSAGE };
        if length > limit {
            return Err(Error::Protocol(format!("a message of {length} bytes")));
        }
        let mut payload = vec![0u8; length];
        reader.read_exact(&mut payload).await?;
        if head[0] == TYPE_CONTROL {
            return serde_json::from_slice(&payload).map(Some).map_err(|_| Error::Protocol("a control message that is not JSON".into()));
        }
    }
}

async fn expect(reader: &mut Reader, what: &str) -> Result<Value, Error> {
    match timeout(Duration::from_secs(130), read_control(reader)).await {
        Err(_) => Err(Error::Protocol(format!("no answer waiting for {what}"))),
        Ok(Err(e)) => Err(e),
        Ok(Ok(None)) => Err(Error::Io("the receiver closed the connection".into())),
        Ok(Ok(Some(message))) => Ok(message),
    }
}

fn kind(message: &Value) -> &str {
    message.get("t").and_then(Value::as_str).unwrap_or("")
}

fn reason(message: &Value) -> String {
    message.get("reason").and_then(Value::as_str).unwrap_or("").to_string()
}

/// A running cast. Frames go in with [`Session::send_video`]; the receiver's messages come
/// out of [`Session::next_event`].
pub struct Session {
    frames: mpsc::Sender<Vec<u8>>,
    events: mpsc::UnboundedReceiver<Event>,
    dropping: Arc<AtomicBool>,
    closed: Arc<AtomicBool>,
    writer: JoinHandle<()>,
    reader: JoinHandle<()>,
    /// The receiver's id and key, for the caller's display.
    pub receiver_id: String,
    pub receiver_name: String,
}

/// Connects to the receiver at `addr`, pairs if it asks, and offers the stream.
///
/// `receiver_id` is the id discovery announced, when known: a pinned receiver must show the
/// key pinned for it. After the receiver admits this sender, its key is pinned.
pub async fn connect(
    addr: SocketAddr,
    identity: &Identity,
    pins: &mut Pins,
    receiver_id: Option<&str>,
    config: &Config,
    code: &mut dyn PairingCode,
) -> Result<Session, Error> {
    let tcp = timeout(Duration::from_secs(6), TcpStream::connect(addr))
        .await
        .map_err(|_| Error::Io("connecting timed out".into()))??;
    tcp.set_nodelay(true)?;
    let connector = TlsConnector::from(Arc::new(tls_config(identity)?));
    let tls = timeout(Duration::from_secs(10), connector.connect(ServerName::try_from("castbay").expect("a valid name"), tcp))
        .await
        .map_err(|_| Error::Io("the TLS handshake timed out".into()))??;
    let peer = tls.get_ref().1.peer_certificates().and_then(|c| c.first()).ok_or_else(|| Error::Protocol("the receiver sent no certificate".into()))?;
    let receiver_fp = fingerprint(peer.as_ref())?;
    if let Some(id) = receiver_id {
        if let Some(pinned) = pins.get(id) {
            if pinned != receiver_fp {
                return Err(Error::ReceiverChanged);
            }
        }
    }
    let (mut reader, mut writer) = tokio::io::split(tls);

    let mut hello = json!({"t": "hello", "v": VERSION, "name": config.name, "id": hex(&identity.fingerprint[..8]),
        "app": concat!("CastBay Desktop ", env!("CARGO_PKG_VERSION")), "os": std::env::consts::OS});
    if let Some(password) = &config.password {
        hello["password"] = json!(password);
    }
    writer.write_all(&control_frame(&hello)).await?;
    let first = expect(&mut reader, "hello").await?;
    if kind(&first) == "reject" {
        return Err(Error::Rejected(reason(&first)));
    }
    if kind(&first) != "hello" {
        return Err(Error::Protocol(format!("expected hello, got {}", kind(&first))));
    }
    let receiver_name = first.get("name").and_then(Value::as_str).unwrap_or("").to_string();
    let id = receiver_id.map(str::to_string).or_else(|| first.get("id").and_then(Value::as_str).map(str::to_string)).unwrap_or_default();

    // Admission: a reject, a pairing code to type, or ready.
    loop {
        let message = expect(&mut reader, "admission").await?;
        match kind(&message) {
            "reject" => return Err(Error::Rejected(reason(&message))),
            "pair_required" => {
                let expected = verification_code(&receiver_fp, &identity.fingerprint);
                let typed = code.code(&expected).ok_or(Error::CodeRequired)?;
                writer.write_all(&control_frame(&json!({"t": "pair", "sas": typed}))).await?;
            }
            "pair_result" => {
                if message.get("ok").and_then(Value::as_bool) != Some(true) {
                    return Err(Error::PairingFailed(reason(&message)));
                }
            }
            "ready" => break,
            _ => {}
        }
    }
    if !id.is_empty() {
        pins.pin(&id, &receiver_fp)?;
    }

    let offer = json!({"t": "offer", "codec": "h264", "width": config.width, "height": config.height,
        "fps": config.fps, "bitrate": config.bitrate_bps, "source": config.source});
    writer.write_all(&control_frame(&offer)).await?;
    loop {
        let message = expect(&mut reader, "accept").await?;
        match kind(&message) {
            "accept" => break,
            "reject" => return Err(Error::Rejected(reason(&message))),
            _ => {}
        }
    }

    let (frames, queue) = mpsc::channel::<Vec<u8>>(QUEUE_FRAMES);
    let (events_tx, events) = mpsc::unbounded_channel();
    let dropping = Arc::new(AtomicBool::new(false));
    let closed = Arc::new(AtomicBool::new(false));
    let writer_task = tokio::spawn(write_loop(writer, queue, closed.clone()));
    let reader_task = tokio::spawn(read_loop(reader, events_tx, closed.clone()));
    Ok(Session { frames, events, dropping, closed, writer: writer_task, reader: reader_task, receiver_id: id, receiver_name })
}

/// Pictures waiting to be written; more than this and the connection is not keeping up.
const QUEUE_FRAMES: usize = 6;

async fn write_loop(mut writer: Writer, mut queue: mpsc::Receiver<Vec<u8>>, closed: Arc<AtomicBool>) {
    let mut ping = tokio::time::interval(Duration::from_secs(2));
    loop {
        tokio::select! {
            frame = queue.recv() => match frame {
                Some(bytes) => {
                    if writer.write_all(&bytes).await.is_err() {
                        break;
                    }
                }
                None => break,
            },
            _ = ping.tick() => {
                if writer.write_all(&control_frame(&json!({"t": "ping"}))).await.is_err() {
                    break;
                }
            }
        }
    }
    closed.store(true, Ordering::SeqCst);
    let _ = writer.shutdown().await;
}

async fn read_loop(mut reader: Reader, events: mpsc::UnboundedSender<Event>, closed: Arc<AtomicBool>) {
    let end = loop {
        match timeout(Duration::from_secs(10), read_control(&mut reader)).await {
            Err(_) => break "the receiver went silent".to_string(),
            Ok(Err(e)) => break e.to_string(),
            Ok(Ok(None)) => break "connection closed".to_string(),
            Ok(Ok(Some(message))) => match kind(&message) {
                "keyframe" => {
                    let _ = events.send(Event::KeyFrameRequested);
                }
                "stats" => {
                    let _ = events.send(Event::Stats {
                        decode_ms: message.get("decode_ms").and_then(Value::as_i64).unwrap_or(-1),
                        dropped: message.get("dropped").and_then(Value::as_u64).unwrap_or(0),
                        fps: message.get("fps").and_then(Value::as_f64).unwrap_or(0.0),
                    });
                }
                "bye" => break reason(&message),
                _ => {}
            },
        }
    };
    closed.store(true, Ordering::SeqCst);
    let _ = events.send(Event::Closed(end));
}

impl Session {
    /// Queues one access unit. Never waits: if the connection is behind, the picture is
    /// dropped, and so are the delta frames after it, until a key frame comes.
    pub fn send_video(&self, pts_us: u64, key: bool, unit: &[u8]) -> SendResult {
        if self.closed.load(Ordering::SeqCst) {
            return SendResult::Closed;
        }
        if self.dropping.load(Ordering::SeqCst) && !key {
            return SendResult::Dropped;
        }
        match self.frames.try_send(video_frame(pts_us, key, unit)) {
            Ok(()) => {
                if key {
                    self.dropping.store(false, Ordering::SeqCst);
                }
                SendResult::Sent
            }
            Err(mpsc::error::TrySendError::Full(_)) => {
                self.dropping.store(true, Ordering::SeqCst);
                SendResult::Dropped
            }
            Err(mpsc::error::TrySendError::Closed(_)) => SendResult::Closed,
        }
    }

    /// Whether frames are being dropped until a key frame; the caller should ask its encoder for one.
    pub fn wants_key_frame(&self) -> bool {
        self.dropping.load(Ordering::SeqCst)
    }

    /// The next message from the receiver, if one has arrived.
    pub fn next_event(&mut self) -> Option<Event> {
        self.events.try_recv().ok()
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// Says goodbye and waits for what is queued to be written.
    pub async fn close(self) {
        let _ = self.frames.send(control_frame(&json!({"t": "bye", "reason": "done"}))).await;
        drop(self.frames);
        let _ = timeout(Duration::from_secs(3), self.writer).await;
        self.reader.abort();
    }
}
