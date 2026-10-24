# CastBay Desktop

Casts a computer's screen or a single window to CastBay receivers (the Android app in this
repository). Windows first; Linux and macOS later (a Mac already mirrors to CastBay through
AirPlay, so it has the lowest priority).

Status: Phase 2. `core/` builds on Windows: `castbay-cast` finds receivers (`discover`), pairs,
captures, encodes and casts over CastBay Link (`cast`). The receiver side is in the Android app
(`service/desktop/`). There is no desktop app (UI) yet.

- [docs/design.md](docs/design.md): decisions, architecture, phases, risks
- [docs/protocol.md](docs/protocol.md): the CastBay Link protocol (draft)
- [docs/spike-latency.md](docs/spike-latency.md): the first end-to-end measurement
- [docs/benchmarks.md](docs/benchmarks.md): measured capture, encoding and latency

The Android app and the desktop app are built, tested, versioned and released separately.
Desktop release tags use the `desktop-v*` prefix; `v*` is the Android release.
