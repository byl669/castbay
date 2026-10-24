# CastBay Desktop: design

Status: draft for review. Decisions marked **open** need an experiment before they are final.

## 1. Goals and non-goals

Goals, in order: stability, latency, compatibility with CastBay receivers, ease of use.

- Cast the whole desktop or one window from Windows to a CastBay receiver (Android TVs, car
  displays, tablets).
- Linux next; macOS only on demand.
- Leave the Android app's behaviour and build untouched, except for one new, self-contained
  receive mode (section 6).

Non-goals for the first release: AirPlay or DLNA sending (see section 2), remote control of
the PC, casting to receivers that are not CastBay, audio-only casting, multiple receivers at
once.

## 2. Transport decision

| Option | Result |
| --- | --- |
| DLNA push of a live HLS or TS URL | Rejected. HLS adds seconds; the receiver already plays it, but not at interactive latency |
| AirPlay sender | Shelved. Mirroring needs the sender side of FairPlay (the receiver implements only the other half), which has no open implementation and legal risk |
| CastBay Link: authenticated TLS over TCP, H.264 access units | **Chosen.** Spike: about 260 ms end to end on a TV, with the existing decoder (docs/spike-latency.md) |

CastBay Link only talks to CastBay receivers. That is a deliberate scope limit.

## 3. Repository layout

```
desktop/
  README.md
  docs/                 design, protocol, decision log, benchmark results
  core/                 Rust workspace, no UI dependency
    link/               protocol: discovery, TLS, framing, pairing, control messages
    capture/            trait CaptureSource; windows/ (now), linux/ and macos/ (later)
    encode/             trait Encoder; Media Foundation H.264 (now)
    session/            glues capture, encode and link; owns the lifecycle and stats
    cli/                `castbay-cast`: a command-line sender used for tests and benchmarks
  app/                  Tauri 2 + React + TypeScript shell (UI only)
.github/workflows/desktop.yml     path-filtered to desktop/**
```

Rules: `core` never depends on Tauri; the UI never calls a platform API; `link` knows
nothing about capture or encoding (it takes encoded access units), and `capture` knows
nothing about the network. The Android app and `web/` stay where they are. Their workflows
get `paths-ignore: desktop/**` so desktop changes do not run the Android build.

Releases: tag `desktop-vX.Y.Z`, its own workflow and version number. The Android `v*` tag,
`release.yml`, `latest.json` and the F-Droid recipe are not touched. The desktop required
check is added to the main ruleset only after it has run reliably.

Shared code with Android: none at first. The protocol is specified in docs/protocol.md and
tested on both sides against the same fixtures; share code only if a real duplication
appears.

## 4. Desktop stack

**Tauri 2 shell over an independent Rust core.** Capture, encoding and networking are native
in either framework, and the UI is a device list, a source picker and a status page, so the
framework matters least. Keeping everything below the UI in `core` means the shell can be
swapped (Electron through napi-rs) without touching it, which is what keeps this decision
reversible.

Why not rely on the web view's `getDisplayMedia` and WebCodecs: it exists on Windows
(WebView2) but not on the other systems' web views, and it gives little control over frame
rate, bitrate, window selection and latency.

Costs accepted: the project has no Rust yet; Linux web views are the weakest part of Tauri
(only the UI is affected). **open**: if the Tauri build or WebView2 deployment turns out
painful in Phase 1, revisit.

## 5. Pipeline (sender)

```
capture --frames--> encode --access units--> link --TLS--> receiver
   ^                    ^                       |
   +---- session: lifecycle, backpressure, stats, keyframe requests
```

**Capture (Windows).** Windows Graphics Capture (Windows 10 1903+; the yellow capture border
can be removed on recent Windows, to be verified). Whole monitor, or one window through
`IGraphicsCaptureItemInterop`. Known behaviour to handle: a minimised window stops producing
frames (send the last frame, keep the stream alive), a closed window raises an event (end the
session), size changes arrive with the frame (re-announce the format), DRM-protected content
captures black (document it). DXGI Desktop Duplication as a fallback for whole-screen capture
on older systems **(open)**. Linux later: PipeWire through the XDG portal on Wayland, and the
X11 route separately. No shared capture API is assumed.

**Encode.** H.264 only (every CastBay receiver has a hardware decoder; HEVC does not). No B
frames, constant bitrate, an IDR on request and every few seconds, and `num_reorder_frames=0`
in the stream so decoders do not hold frames back. Planned primary: Media Foundation H.264
encoder transforms, which reach the vendors' hardware encoders (AMD, NVIDIA, Intel) through
one API and have a low-latency mode. **E2 result (docs/benchmarks.md): on the AMD test machine
the Media Foundation encoder crashes in the driver and fails in FFmpeg too, so it cannot be
the only route.** Phase 1 uses an FFmpeg child process (AMF, NVENC, Quick Sync and x264 behind
one set of options) as a stopgap; the final choice (FFmpeg libraries in process, or one layer
per vendor, with Media Foundation as one of them) waits for measurements on NVIDIA and Intel. Fallback: a software encoder, **open** (OpenH264 or x264; this
project is GPL-3.0, so either licence fits, but patent coverage differs and must be settled
in docs before shipping). Not building an encoder. FFmpeg stays a benchmark tool, not a
dependency, unless Media Foundation proves unusable **(open: experiment E2)**.

**Link.** See docs/protocol.md. The session sheds load instead of queueing: if the socket
backs up, drop frames until the next IDR and ask the encoder for one; lower the bitrate when
the receiver reports trouble.

**Audio.** Not in the first milestone. Plan: WASAPI loopback, AAC-LC (decoded by MediaCodec
on all receivers), timestamps from one clock; the receiver plays audio as it arrives, as it
does for AirPlay mirroring, so there is no extra buffering to keep in sync.

## 6. Receiver changes (Android)

One new package, `service/desktop/`, no changes to the AirPlay or DLNA code paths other than
the hooks listed here.

- `DesktopLinkServer`: TLS listener on a fixed port (persisted like DLNA's), accepts one
  session, runs the handshake and control channel.
- Frames go to the existing `VideoRenderer.configure` and `render`, through a small method on
  `AirPlayManager` like `onNativeVideoData`, so the home screen's casting state, the stats
  overlay, the casting badge and "stop from the TV" work unchanged. This method also needs
  the session to show as mirroring with a `desktop` sender kind.
- Discovery: publish `_castbay._tcp` with the existing NSD code.
- Access: `AccessPolicy` gets `desktopNeedsApproval` and `desktopDeviceId` (the sender's key
  fingerprint); the same blocked, busy and ask rules apply. `PairedDevices` already stores a
  public key, a device ID and a name; it is reused.
- UI: a sender kind in `CastingBadge` and `DlnaSender`'s naming equivalent; strings in both
  languages; the pairing dialog shows the verification code (protocol, section 4).
- Settings: one switch, "Desktop casting", default on, consistent with DLNA.
- Tests: unit tests for framing, the handshake against the shared fixtures, and the policy;
  a protocol test with `castbay-cast`; and the existing mirroring regression (AirPlay
  mirroring, DLNA video) before every PR that touches shared code
  ([tools/sim](../../tools/sim)).

Compatibility risk to settle first (**open, experiment E4**): TLS on API 23 (the Xiaomi TV)
with an Android Keystore key. If the platform's TLS is not good enough there, fall back to a
Noise-based handshake over a small library, documented as a decision change.

## 7. Phases

| Phase | Content | Exit criterion |
| --- | --- | --- |
| 0 | Investigation, spike, this design | Reviewed and accepted |
| 1 | `core/capture` and `core/encode` on Windows, `castbay-cast` CLI that casts a monitor or window; E1, E2, E4 | CLI casts a monitor or a window to the spike receiver (branch build, no security) with measured latency, CPU and bitrate in a benchmark table |
| 2 | Receiver `service/desktop` (own PR, regression run), pairing, reconnect, the Tauri UI | A Windows PC pairs once, then reconnects, casts and stops cleanly |
| 3 | First-run experience, errors and recovery, logs, installer, CI, signing decision, update mechanism | External testers can install it |
| 4 | Audio, Linux, macOS on demand | By need |

Phase 1 changes nothing on main in the Android app: `castbay-cast` talks to the spike
receiver, which stays on its branch and is never released. The authenticated receiver mode
arrives with Phase 2, as its own pull request.

## 8. Experiments

| ID | Question | Method | Done when |
| --- | --- | --- | --- |
| E1 | Do whole-monitor and window capture stay correct across minimise, close, resize, DPI, several monitors, 60 Hz and above? | `core/capture` test app on the Windows laptop, plus a second monitor | Behaviours listed in docs/benchmarks.md |
| E2 | Media Foundation low-latency H.264 at 1080p30/60 and 4K30: latency, CPU, GPU, bitrate. Software fallback | `core/encode` bench; compare with FFmpeg `h264_mf` and `h264_amf` | Table of measured results, not estimates. Partly done on AMD: Media Foundation failed, see benchmarks.md |
| E3 | End-to-end latency with the final pipeline, and AirPlay mirroring on the same TV | 60 fps or faster phone video of a millisecond clock, 30+ samples | Median and spread per setup |
| E4 | TLS with a Keystore key on API 23 to 36 | Small test app on the emulator and the Xiaomi TV | Handshake works, or the fallback is chosen |
| E5 | One-hour stability: memory, handles, drift, Wi-Fi loss and recovery | `castbay-cast` run | No growth, clean reconnect |

Targets are set after E2 and E3, not before.

## 9. Risks

| Risk | Mitigation |
| --- | --- |
| Latency stays around 250 ms | Native WGC, 60 fps, no one-frame wait, backpressure policy; measure each (E3) |
| Hardware encoder behaves differently per GPU vendor | Media Foundation as the common API; test on AMD now, NVIDIA and Intel before release; software fallback |
| TLS or Keystore quirks on old Android | E4 before committing; Noise fallback |
| Rust and Windows API learning curve | Small crates, the `windows` bindings, CLI first, no UI until the pipeline works |
| Receiver change breaks AirPlay or DLNA | Separate package, minimal hooks, separate PR, full regression |
| Installer trust (SmartScreen) and signing cost | Decide in Phase 3; unsigned builds are for testers only |
| Screen content on the LAN | Authenticated, encrypted from the first release; no plain mode outside spike branches |
| Only one Windows machine for testing | Mac and Linux CI covers the protocol and the CLI sender; Windows-only code is kept small |

## 10. Licences and third-party code

Desktop is GPL-3.0 like the rest. Every dependency, its licence and any distribution limit
(codecs, Windows redistributables, WebView2) is listed in `desktop/docs/third-party.md`,
created in Phase 1 with the first dependency.
