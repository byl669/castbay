# Spike: end-to-end latency (2026-10-24)

Question: is a plain H.264 stream over TCP into the receiver's existing decoder fast enough
to justify a dedicated desktop mode (DLNA/HLS is not: seconds)?

## Setup

- Throwaway receiver on branch `spike/desktop-latency` (not merged): a TCP server on port 7600
  that cuts an Annex B stream at access unit delimiters and hands each unit to the existing
  `VideoRenderer`. No pairing, no encryption.
- Sender: stock FFmpeg, `gdigrab` 30 fps, scaled to 1920x1080, `h264_amf` (ultralowlatency,
  no B-frames, CBR 8 Mbps), over TCP.
- Sender PC: HP laptop, Ryzen 7 PRO 6850HS / Radeon 680M, Windows 11 24H2, on battery.
- Receiver: Sony BRAVIA XR-55X90L (Android 12, MediaTek hardware AVC decoder), wired.

## Results

| Measurement | Result |
| --- | --- |
| Sender | 30 fps, 0.999x speed, 1 dropped frame in 25 s, ~1.4 Mbps on a static page, ~6 Mbps with motion |
| Receiver, arrival to display | 13 to 30 ms (smoothed) |
| End to end | about 260 ms |

End to end was read from a 60 fps phone video of the laptop and the TV side by side showing a
millisecond stopwatch. Three frames were legible and agreed (TV 03:19.26 against laptop
03:19.52, and two more at the same 0.26 s). Read it as roughly 0.26 s, plus or minus 20 ms,
from one clip. It includes the laptop panel's own latency and the spike receiver waiting for
the next frame's delimiter (about one frame, not in the real receiver).

## Conclusions

- The plain-stream approach works with the existing decoder path, with no Android decoding
  changes. Mirroring's low-latency decoder setup already applies.
- 0.26 s suits video, slides and demos; it is noticeable for typing and the pointer.
- Not measured: AirPlay mirroring on the same TV (needed for a fair comparison), software
  encoding, 4K, high-motion content, Wi-Fi receivers, the Xiaomi TV (Android 6).
- Gotchas: a TV screensaver removes the receiver's surface (frames pile up until a wake-up);
  the sender must run in the interactive session, and a scheduled task must be allowed to
  start on battery.
