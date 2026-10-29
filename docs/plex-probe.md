# Plex Companion receiver (probe build)

CastBay answers AirPlay, DLNA and CastBay Link. This adds a fourth: **Plex Companion**, so the
official Plex app on a phone lists the television under "play to device" and drives it from there.
The reason it is worth having: Plex for Android and Android TV ship 64-bit only and require
Android 7+, so no 32-bit Android 6 television can install a Plex client at all. A receiver that
speaks Companion gives those televisions a Plex player without one.

## What this build does — and deliberately does not do

It answers the whole command surface and **records every command instead of guessing the wire
format from the wiki**. Nothing is decoded yet. The wiki is incomplete on exactly the details that
matter (`containerKey`'s real shape, whether the token really arrives with the command, how a
`deviceClass` of `stb` affects what the app offers), so the first step is to watch a real Plex app
talk to a real television.

## Where it lives

| File | Role |
|---|---|
| `plex/PlexGdm.java` | GDM discovery: answers `M-SEARCH * HTTP/1.0` on UDP 32412, announces with `HELLO` to `239.0.0.250` and broadcast |
| `plex/PlexCompanion.java` | HTTP player port (32500+): `/resources`, `/player/playback/*`, `/player/timeline/*`, and `/:/timeline` reports back to a subscribed controller |
| `plex/PlexReceiver.kt` | Lifecycle, the stable 40-hex machine identifier, and the header-safe name |
| `service/AirPlayManager.kt` | Starts and stops it beside DLNA; a failure only logs |

A Plex failure cannot take AirPlay or DLNA down: `PlexReceiver.start` catches everything and logs
it, exactly as `DlnaReceiver` does.

## Two details worth knowing

**The name announced over GDM is ASCII-only.** GDM headers carry no charset, so
`settings.advertisedName` ("客厅的电视") is reduced to its printable ASCII part, falling back to
`CastBay`. The full name is still what `/resources` returns in XML.

**Written for Android 6.0 (API 23).** No `java.util.function`, no `Map.getOrDefault`/`merge`, no
records — the app's lint fails the build on anything newer than `minSdk`, and those are API 24.

## Verified before it reached a television

The two protocol classes depend on nothing but `java.net`, so they were compiled and run against a
real Plex Media Server (1.43.5) on the same network:

| Check | Result |
|---|---|
| GDM: a real PMS discovers the player | The server at `192.168.2.10` sent `M-SEARCH` and was answered within seconds |
| `/resources` | Valid `MediaContainer` XML |
| `subscribe` / `playMedia` / `pause` | `200` |
| A command addressed to another player | `404`, and logged |
| An unknown path | Recorded rather than dropped — those are the commands this build missed |
| Token in the log | Masked: `abcdef…(共 32 位)` |
| `/:/timeline` to a subscribed controller | Valid XML with `state`, `time`, `duration`, `controllable`, `volume` |

## Still to confirm on the television

1. Does the Plex app list the television at all, and under what name?
2. What does `containerKey` actually contain?
3. Does the token arrive with `playMedia`, or must the player ask for it?
4. Does `deviceClass=stb` change what the app offers?

The log answers all four: every line is prefixed `[Plex]` in `logs/castbay.log`, on external
storage in a debug build (`adb pull /sdcard/Android/data/com.weenas.castbay.debug/files/logs/`).

## Not in this build

- No decoding: `playMedia` is recorded, not played.
- No `X-Plex-Token` storage: the token is used when it arrives, never written down.
