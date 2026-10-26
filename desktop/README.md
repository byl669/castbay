# CastBay Desktop

Casts a computer's screen or a single window to CastBay receivers (the Android app in this
repository). Windows first; Linux and macOS later (a Mac already mirrors to CastBay through
AirPlay, so it has the lowest priority).

Status: usable on Windows, released as pre-releases (`desktop-v0.1.x`: portable zip and MSI
installers in English and Chinese, unsigned until the SignPath application is approved).
`core/` builds on Windows: `castbay-sender` is the casting engine (capture, scaling, in-process
H.264 encoding with AMD, NVIDIA or x264, sound over AAC, CastBay Link, reconnect, bitrate that
follows the connection), `castbay-cast` is its command line, and `app/` is the CastBay Desktop
window (Tauri 2 over the engine, a plain HTML page, no Node needed): pick a receiver, a screen or
window and a quality, type the pairing code once, and cast; tray, start with Windows and update
notice included. The receiver side is in the Android app (`service/desktop/`).

A Linux version is in progress (docs/plan-linux.md): the engine, the encoders (x264, NVENC, AAC)
and the Link code build and pass their tests on Ubuntu; capture (Wayland: desktop portal and
PipeWire), sound, the system integration and packages are next.

Building on Windows: `tools/build-ffmpeg.cmd` builds FFmpeg from the submodules (needs MSYS2 and
Visual Studio's C++ tools); then `cargo build --release -p castbay-desktop --features ffmpeg-lib`
with `FFMPEG_DIR` pointing at its output (default `desktop/build/ffmpeg`). Without the feature the
encoders run as an `ffmpeg.exe` child process (`CASTBAY_FFMPEG`).

- [docs/limitations.md](docs/limitations.md): what is not done, not tested or not possible
- [docs/user-guide.md](docs/user-guide.md): installing and using it (English and 中文)
- [docs/design.md](docs/design.md): decisions, architecture, phases, risks
- [docs/protocol.md](docs/protocol.md): the CastBay Link protocol (draft)
- [docs/spike-latency.md](docs/spike-latency.md): the first end-to-end measurement
- [docs/benchmarks.md](docs/benchmarks.md): measured capture, encoding and latency

The Android app and the desktop app are built, tested, versioned and released separately.
Desktop release tags use the `desktop-v*` prefix; `v*` is the Android release.
