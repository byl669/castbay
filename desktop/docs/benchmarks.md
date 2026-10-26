# Benchmarks

Measured, not estimated. Every row says what machine and setup it came from. Phase 1 numbers
use the `castbay-cast` command line tool and the spike receiver (unauthenticated, raw H.264
over TCP); they say nothing about the final protocol's overhead.

## Setup

| | |
| --- | --- |
| Sender | HP laptop, Ryzen 7 PRO 6850HS (16 threads), Radeon 680M, 15 GB, Windows 11 24H2 (26100), on battery, 1920x1200 at 60 Hz, one monitor |
| Receiver | Sony BRAVIA XR-55X90L, Android 12, wired Ethernet, MediaTek AVC decoder (`OMX.MTK.VIDEO.DECODER.AVC`); sender on Wi-Fi |
| Encoders | FFmpeg master build (BtbN, GPL), run as a child process: `h264_amf` (ultralowlatency, CBR, no B-frames) and `libx264` (ultrafast, zerolatency); 8 Mbit/s target, 2 s GOP |
| Content | a moving 1280x720 60 fps test pattern window (capture and encode runs), a phone-camera stopwatch page (latency runs) |
| Date | 2026-10-24 |

## Capture (Windows Graphics Capture, `windows-capture` crate, CPU copy)

| Target | Pictures per second | Longest gap | Note |
| --- | --- | --- | --- |
| Whole monitor, 1920x1200, moving content | 52 | 34 ms | |
| One window, 1284x767, moving content | 53 | 34 ms | |
| Whole monitor, static desktop | about 2 | 549 ms | Windows delivers a picture only when the screen changes; the sender repeats the last one |

## Encoding, 1920x1200, 10 s runs (`bench-encode`)

CPU is this process plus the FFmpeg child, as a share of one core; the second figure is the
share of the 16-thread machine. The conversion from BGRA to NV12 runs on the CPU (about
3 ms per picture, split over threads) and is included.

| Encoder | Target | Pictures in | Units out | Bitrate | CPU |
| --- | --- | --- | --- | --- | --- |
| AMD `h264_amf` | 30 fps | 29.1 | 28.7 | 7.2 Mbit/s | 59 % (4 %) |
| AMD `h264_amf` | 60 fps | 57.6 | 57.3 | 7.7 Mbit/s | 108 % (7 %) |
| x264 ultrafast | 30 fps | 29.3 | 29.0 | 7.9 Mbit/s | 53 % (3 %) |
| x264 ultrafast | 60 fps | 58.2 | 57.9 | 7.9 Mbit/s | 178 % (11 %) |

Findings:

- **Media Foundation's AMD encoder does not work on this machine.** Our code crashed inside
  `amdh264enc64.dll` (access violation at the same offset) on `MFT_MESSAGE_NOTIFY_START_OF_STREAM`,
  with and without a Direct3D device manager and with different message orders. Skipping
  that message avoids the crash, but the transform then never asks for input. FFmpeg's own
  `h264_mf` also fails on it ("format negotiation failed") at 1920x1080 and 1920x1200. The
  Media Foundation code stays in the tree (`encode/src/windows.rs`) for other GPUs and the
  software transform, untested there.
- `h264_amf` ignores `-g` (one key frame per stream, also with `-forced_idr 1`,
  `-header_spacing` and another usage). Timed key frames work: `-force_key_frames
  expr:gte(t,n_forced*2)` gives 5 in ten seconds, and the sender uses it. Key frames on
  demand (after loss) need the encoder in process, or a restart; not solved yet.
- `-async_depth 1` (the AMF default is 16, "higher values increase output latency") made no
  difference to the delay below at 30 fps.
- At these settings the software encoder is cheap on this 16-thread CPU; it is not a
  stand-in for weaker machines.

## Delay from capture to a unit ready to send (`send`, 12 to 20 s runs)

| Encoder | 30 fps: median | 95th percentile | Worst |
| --- | --- | --- | --- |
| AMD `h264_amf` | 134 ms | 180 to 208 ms | 244 to 284 ms |
| x264 ultrafast | 101 ms | 118 ms | 168 to 175 ms |

At 60 fps (a second run, moving content kept on top): `h264_amf` median 83 ms (95th 109,
worst 245) and x264 median 52 ms (95th 83, worst 113); the encoders delivered 48 and 49
pictures per second of 60, with 110 and 102 repeated pictures, so read these as indicative.
Doubling the rate takes about 50 ms off the median because every interval-sized wait halves.

This includes about one picture interval of waiting for the next access unit delimiter to
find the end of a unit, which a sender that gets units straight from an encoder does not
have. Sent pictures per second: 27.7 to 28.6 of 30 (the pacing is a little slow).

## End to end (phone video of the laptop and the TV side by side, 60 fps, stopwatch)

Read by comparing the stopwatch on the TV with the one on the laptop in the same frame.
Resolution: about 10 ms for the readings, plus up to a frame of camera exposure, so treat
every value as about 0.25 s, plus or minus 0.02 s. They include the laptop panel's latency.

| Sender | Samples | TV behind the laptop by |
| --- | --- | --- |
| FFmpeg `gdigrab` + `h264_amf`, one process (spike) | 3 | 0.26 s |
| `castbay-cast` + FFmpeg `h264_amf` | 6 | 0.25 to 0.26 s (one reading 0.29) |
| `castbay-cast` + FFmpeg `libx264` | 7 | 0.20 to 0.23 s |

The 30 ms difference between the two encoders matches the difference measured at the
sender (134 against 101 ms). About 115 to 120 ms of the total is outside the sender's
capture to send path: waiting for the picture interval after a capture, the network, 14 ms
in the receiver's decoder, the TV panel, and the cameras.

## Casting over CastBay Link (`castbay-cast cast`, 12 s runs, moving content, 2026-10-25)

The Rust sender (`core/link`) discovers the receiver by mDNS, pairs, and sends over TLS to the
production receiver mode (Android PR #228). Same laptop and encoders as above; the
"captured to ready to send" delay is measured as before, the receiver's figures come from its
once-a-second `stats` message.

| Receiver | Encoder | Rate | Median / 95th (ms) | Receiver decode | Receiver fps | Dropped |
| --- | --- | --- | --- | --- | --- | --- |
| Sony TV (Android 12) | AMD `h264_amf` | 30 fps | 134 / 136 | 14 ms | 28.6 sent | 0 |
| Sony TV | AMD `h264_amf` | 60 fps | 70 / 79 | 14 ms | 55.2 | 0 |
| Sony TV | x264 ultrafast | 60 fps | 51 / 59 | 13 ms | 55.2 | 0 |
| Google TV Streamer (Android 14) | AMD `h264_amf` | 30 fps | 134 / 141 | 8 ms | 29.9 | 0 |
| Android 6 emulator (software decoder, over an SSH tunnel) | x264 | 30 fps | not recorded | 84 ms | 29.9 | 0 |

Pairing worked on the first connection (the test sender types the code the TV should show)
and every later one reconnected without it. The sender's frame queue never overflowed (0
dropped). Wall-clock end to end over Link has not been measured with the phone-video method
yet.

## In-process FFmpeg (`--features ffmpeg-lib`, 10 s runs to the Sony, 2026-10-25)

The encoders now run inside the sender (libavcodec built from the submodules, no child
process, no pipe, no wait for the next access-unit delimiter). Same laptop and receiver as above.

| Encoder | Rate | Median / 95th (ms) | Receiver decode | Receiver fps | Dropped |
| --- | --- | --- | --- | --- | --- |
| AMD `h264_amf`, in-process | 30 fps | 35 / 38 | 14 ms | 29.7 | 0 |
| AMD `h264_amf`, in-process | 60 fps | 18 / 22 | 14 ms | 59.0 | 0 |
| x264 ultrafast, in-process | 60 fps | 2 / 3 | 12 ms | 49.7 | 0 |
| AMD `h264_amf`, FFmpeg process (same build) | 30 fps | 134 / 141 | 12 ms | 30.0 | 0 |

The delay before sending drops from 134 ms to 35 ms for AMF at 30 fps. (The 50 fps with x264
is the test window's picture rate; the rest were repeated pictures.)

## NVENC (Linux, RTX 4070 Laptop, driver 580, 2026-10-25)

Encoder only: the same C shim and the same FFmpeg n7.1.5 source, built on Linux with
`h264_nvenc` only, fed 300 synthetic 1080p NV12 pictures at 60 fps, 8 Mbit/s. Windows capture
and the Windows driver were not involved, so this checks the encoder settings, not the whole path.

- Every picture came out as one access unit straight away (300 of 300; no delay inside the
  encoder), the first starting with SPS (`00 00 00 01 67`) and key frames carrying SPS and PPS.
- Asking for a key frame (pictures 100 and 200) gave a key frame for exactly that picture.
- One encode call took 1.6 ms on average, 3.3 ms at worst.

Still to do: NVENC on Windows with capture, once there is a Windows machine with an NVIDIA GPU.

## Audio, scaling and bitrate control (Sony, 2026-10-26)

- **Sound:** a 440 Hz tone played on the PC for 14 s: 652 AAC frames sent (48 kHz, one frame
  every 21 ms), a started media AudioTrack on the TV, video unchanged (29.9 fps, 0 dropped).
  Sound and picture have separate queues: with one shared queue 88 video units were dropped while
  the TV app was restarting mid-cast; with separate queues two such runs dropped 0.
- **Scaling:** the whole 1920x1200 screen goes out as 1728x1080; the sender's picture delay is
  46 ms median (35 ms without scaling), decode 14 ms, 0 dropped. A cast window resized mid-cast
  keeps updating, centred with black bars (1433 units in 50 s, 0 dropped).
- **Bitrate control:** normal casts at 8, 30 and 80 Mbit/s all drop 0 pictures on this network
  (x264 on the test picture does not even reach 11 Mbit/s), so real congestion could not be
  provoked. With a simulated burst of drops five seconds in, a 40 s cast went 8 to 5.6 Mbit/s at
  5 s and back up to 6.4 at 25 s; the TV kept 30 fps, 0 dropped, across the encoder reopen.

## Not measured yet

AirPlay mirroring on the same TV (a fair comparison); NVIDIA on Windows and Intel encoders; 4K; a
Wi-Fi receiver; the Xiaomi TV (Android 6); long runs (experiment E5); 60 fps end to end;
window capture end to end.
