# CastBay Link protocol (draft v1)

A desktop sender casts to a CastBay receiver over one TCP connection, protected by TLS.
This is a draft: the transport security choice depends on experiment E4 (design.md).

## 1. Roles and goals

- Receiver: the CastBay Android app. Sender: CastBay Desktop (or `castbay-cast`).
- One sender at a time per receiver; the receiver's access rules decide who may cast
  (design.md, section 6).
- Latency first: no buffering beyond what decoding needs, and dropping frames instead of
  queueing them.
- Screen content is sensitive: no unauthenticated or unencrypted mode.

## 2. Discovery

The receiver publishes an mDNS service `_castbay._tcp` on its link port.

| TXT key | Meaning |
| --- | --- |
| `v` | protocol version, `1` |
| `id` | receiver ID, a stable random UUID |
| `name` | display name |
| `fp` | first 16 hex characters of the SHA-256 of the receiver's certificate public key |
| `pair` | `open`, `ask` or `pin`: what the receiver's casting verification needs |
| `model` | optional: the device's model, to tell receivers apart in a list |
| `app` | optional: the receiving app's version |

The sender lists receivers by browsing; a receiver can also be added by IP address and port.
The TXT records are hints; the authoritative identity is the certificate the receiver
presents in the handshake.

## 3. Transport and identity

TCP, then TLS 1.2 or newer with mutual authentication. Each side has a long-term key pair
and a self-signed certificate that it generates once (the receiver in the Android Keystore).
Neither side validates the other's certificate against a CA: trust is a pinned public-key
fingerprint, established at pairing (section 4). Cipher suites: ECDHE with an AEAD cipher
(AES-GCM or ChaCha20-Poly1305), ECDSA P-256 keys. Nothing is sent before the handshake has
finished and the peer is pinned or being paired.

Fingerprint: SHA-256 of the certificate's SubjectPublicKeyInfo. The device ID the receiver's
access rules use is `desktop:` plus the first 16 hex characters of the sender's fingerprint.

## 4. Pairing

Pairing happens on the first connection from a sender the receiver does not know, and when
the stored fingerprint of a receiver changes (which the sender treats as a new receiver and
asks the user).

Short authentication string (SAS): after the handshake both sides compute

    SAS = decimal( first 4 bytes of SHA-256( "castbay-link-sas-v1" ||
                   fingerprint_receiver || fingerprint_sender ) ) mod 1,000,000, six digits

The receiver shows the SAS on the TV (the existing pairing dialog); the sender's user types
it in. If the numbers match, no one sits in between: a man in the middle has a different
certificate towards each side, so the two SAS values differ. The sender sends its computed
SAS in `pair`; the receiver compares it with its own. A wrong code is refused, and the
receiver locks pairing for 60 seconds after three failures. The code is single use and
expires after two minutes.

On success both sides store the peer's fingerprint (the receiver in `PairedDevices`, with
the sender's name) and later connections skip pairing. Receiver verification mode `open`
skips the code for first connections (trust on first use, still encrypted); `ask` shows an
allow or deny prompt instead of a code; `pin` uses the code. The user can forget a paired
sender on the TV.

## 5. Framing

After the handshake the TLS stream carries messages:

    u8   type
    u32  length   (big endian, at most 8 MiB; larger is a protocol error)
    ...  payload

| Type | Name | Direction | Payload |
| --- | --- | --- | --- |
| 0x01 | `control` | both | UTF-8 JSON object |
| 0x02 | `video` | sender to receiver | see below |
| 0x03 | `audio` | sender to receiver | see below |

`video` payload: `u64` presentation time in microseconds on the sender's monotonic clock
(only differences are meaningful), `u8` flags (bit 0: key frame, with SPS and PPS before the
IDR slice in the same unit), then one H.264 access unit in Annex B format. The receiver
hands it straight to the decoder and does not reorder.

`audio` payload: `u64` presentation time in microseconds on the same clock as `video`, then
one raw AAC-LC frame (1024 samples per channel, no ADTS header; at most 64 KiB). Audio is
optional: the sender adds an `audio` object to `offer` (`codec` `aac`, `sample_rate` 44100 or
48000, `channels` 1 or 2, `config` the AudioSpecificConfig as hex, `1190` for 48 kHz stereo) and
sends `audio` messages only if `accept` answers `"audio": true`. The receiver plays frames as they
arrive and drops the oldest when the sender's clock runs ahead, so audio adds no delay of its own;
there is no lip-sync correction in v1 (the picture and the sound travel the same path and take
about as long).

Unknown message types and unknown JSON fields are ignored, which is how the protocol
grows within a major version.

## 6. Control messages

Every control message has a `t` field. v1 messages:

| `t` | From | Fields | Meaning |
| --- | --- | --- | --- |
| `hello` | both | `v`, `name`, `id`, `app` (name and version), `os`; the sender adds `password` when the receiver announced `pw` | first message from each side |
| `pair_required` | receiver | none | a code is needed: the TV shows it, the sender asks its user and answers with `pair` |
| `pair` | sender | `sas` | the code the user entered |
| `pair_result` | receiver | `ok`, `reason` | `reason`: `wrong_code`, `locked`, `expired`, `denied` |
| `ready` | receiver | none | the sender may cast (admitted, paired if needed); the sender then sends `offer` |
| `offer` | sender | `codec` (`h264`), `width`, `height`, `fps`, `bitrate`, `source` (`screen` or `window`), optional `audio` (see above) | what will be sent; sent again when the size changes |
| `accept` | receiver | `audio` (bool, optional) | start sending; `audio` says the offered audio will be played |
| `reject` | receiver | `reason` | `busy`, `blocked`, `approval_needed` (asked on the TV: connect again once allowed), `locked` (pairing paused), `wrong_password`, `unsupported` |
| `keyframe` | receiver | none | send an IDR now |
| `stats` | receiver | `decode_ms`, `dropped`, `fps` | once a second; the sender may lower the bitrate |
| `pause` and `resume` | sender | none | the source is not changing, or is hidden, on purpose |
| `bye` | both | `reason` | orderly end; stopping from the TV sends `reason: "stopped_by_receiver"` |

Order: TLS, `hello` both ways, then from the receiver `reject`, or `pair_required` (answered by
`pair`, then `pair_result`), and finally `ready`; then the sender's `offer`, `accept`, and `video`. Until `accept` the receiver ignores `video`. Either side may close the TCP
connection at any time; the receiver treats a closed connection as the end of the session
and clears the screen.

## 7. Reliability and recovery

- Keepalive: either side sends `stats` or an empty `control` `{"t":"ping"}` at least every
  5 seconds; ten seconds of silence ends the session.
- The sender watches how much of its socket buffer is unsent. Above a threshold it drops
  frames until the next key frame and requests one; it never lets the backlog grow.
- A receiver that has lost its decoder or has not seen a key frame sends `keyframe`; the
  sender answers with an IDR within about 200 ms.
- After a network drop the sender reconnects to the same receiver automatically (bounded
  retries with a delay), without asking the user again, since the receiver is already
  paired. The receiver shows "waiting" for a few seconds before returning to the home
  screen.
- A new connection from the same paired sender replaces its old session; another sender is
  refused with `busy` unless the receiver's second-device rule allows a takeover.

## 8. Security notes

- Everything after the handshake is encrypted and authenticated by TLS.
- Bounded message sizes (8 MiB, 64 KiB for control messages), JSON nested at most 8 deep (the
  platform's parser recurses), a handshake timeout of 10 seconds, at most 8 connections open
  at once, and no allocation driven by a length field before it is checked. A stranger's
  connection, however malformed, can only end its own session.
- The TLS handshake runs on the session's thread: a peer that sends no certificate or speaks
  plain TCP must not be able to stop the receiver from accepting others.
- No secrets in logs: certificates' private keys never leave the device; fingerprints and
  names may be logged, frames and SAS values may not.
- The `fp` TXT value lets a sender notice a receiver whose identity changed.
- A receiver in `open` mode accepts any sender once; the user decides if that is acceptable
  for their network, as with AirPlay's "open" setting today.

## 9. Test plan

- Shared fixtures (`desktop/docs/fixtures/`, added with the implementation): known
  fingerprints and the expected SAS, framing round trips, malformed and oversized messages.
- Unit tests on both sides against those fixtures.
- Protocol tests: `castbay-cast` against a receiver build or a Kotlin test server: pair,
  reconnect, wrong code, lock-out, `busy`, dropped connection, key frame requests.
- Fuzzing the message parser on the receiver (it is the part reachable from the network).
