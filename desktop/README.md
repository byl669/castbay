# CastBay Desktop

Casts a computer's screen or a single window to CastBay receivers (the Android app in this
repository). Windows first; Linux and macOS later (a Mac already mirrors to CastBay through
AirPlay, so it has the lowest priority).

Status: Phase 3. `core/` builds on Windows: `castbay-sender` is the casting engine (capture,
encode, CastBay Link, reconnect), `castbay-cast` is its command line, and `app/` is the first
CastBay Desktop window (Tauri 2 over the engine, a plain HTML page, no Node needed): pick a
receiver, a screen or window and a quality, type the pairing code once, and cast. The receiver
side is in the Android app (`service/desktop/`).

Building on Windows: `tools/build-ffmpeg.cmd` builds FFmpeg from the submodules (needs MSYS2 and
Visual Studio's C++ tools); then `cargo build --release -p castbay-desktop --features ffmpeg-lib`
with `FFMPEG_DIR` pointing at its output (default `desktop/build/ffmpeg`). Without the feature the
encoders run as an `ffmpeg.exe` child process (`CASTBAY_FFMPEG`).

- [docs/design.md](docs/design.md): decisions, architecture, phases, risks
- [docs/protocol.md](docs/protocol.md): the CastBay Link protocol (draft)
- [docs/spike-latency.md](docs/spike-latency.md): the first end-to-end measurement
- [docs/benchmarks.md](docs/benchmarks.md): measured capture, encoding and latency

The Android app and the desktop app are built, tested, versioned and released separately.
Desktop release tags use the `desktop-v*` prefix; `v*` is the Android release.
