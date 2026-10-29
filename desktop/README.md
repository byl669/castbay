# CastBay Desktop

Casts a computer's screen or a single window to CastBay receivers (the Android app in this
repository). Windows and Linux; no macOS version (a Mac already mirrors to CastBay through
AirPlay, and a signed, notarised build would cost an Apple developer account).

Status: usable on Windows and Linux, released as pre-releases (`desktop-v0.1.x`: portable zip and
MSI installers in English and Chinese for Windows, unsigned until the SignPath application is
approved; a `.deb` for Ubuntu/Debian).
`core/` builds on Windows: `castbay-sender` is the casting engine (capture, scaling, in-process
H.264 encoding with AMD, NVIDIA or x264, sound over AAC, CastBay Link, reconnect, bitrate that
follows the connection), `castbay-cast` is its command line, and `app/` is the CastBay Desktop
window (Tauri 2 over the engine, a plain HTML page, no Node needed): pick a receiver, a screen or
window and a quality, type the pairing code once, and cast; tray, start with Windows and in-app
update included. The receiver side is in the Android app (`service/desktop/`).

The Linux version (docs/plan-linux.md) shares the engine, the encoders (x264, NVENC, AAC) and the
Link code; it captures through the desktop portal and PipeWire (Wayland), plays no part in the
picker (the system asks what to share), takes the sound from PipeWire, keeps the PC awake through
logind and starts with the session through an autostart entry. Tested on Ubuntu 26.04 (GNOME,
Wayland, an NVIDIA GPU); see docs/limitations.md.

Building on Linux: `tools/build-ffmpeg-linux.sh` (gcc, make, nasm, pkg-config), then `cargo build --release -p castbay-desktop --features ffmpeg-lib`; `installer/linux/build-deb.sh BINARY VERSION OUTDIR` makes the package.

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
