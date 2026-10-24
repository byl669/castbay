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
- `h264_amf` produced one key frame in ten seconds with `-g 60`: it ignores the option as
  given. Recovery after loss needs key frames on demand; to be solved before Phase 2.
- At these settings the software encoder is cheap on this 16-thread CPU; it is not a
  stand-in for weaker machines.

## Delay from capture to a unit ready to send (`send`, 12 to 20 s runs)

| Encoder | 30 fps: median | 95th percentile | Worst |
| --- | --- | --- | --- |
| AMD `h264_amf` | 134 ms | 180 to 208 ms | 244 to 284 ms |
| x264 ultrafast | 101 ms | 118 ms | 168 to 175 ms |

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

## Not measured yet

AirPlay mirroring on the same TV (a fair comparison); NVIDIA and Intel encoders; 4K; a
Wi-Fi receiver; the Xiaomi TV (Android 6); long runs (experiment E5); 60 fps end to end;
window capture end to end.
