# Bilibili casting (哔哩必连 / NVA) — probe build

Bilibili's app caps a standard DLNA receiver at 720p and gives it no speed control. That is not
a decoding limit on this side: a plain DLNA renderer can only be handed *one URL*, with no
headers and no separate audio track, so Bilibili sends its muxed `durl` stream, which tops out
at 720p.

Bilibili has its own casting protocol for its own boxes, known as 哔哩必连 (internally **NVA**).
A receiver that advertises the `NirvanaControl` service is listed by the phone as
「我的小电视」, and the phone then:

* sends the account's `accessKey` in the `Play` command, so the **receiver** fetches the stream
  itself — DASH, up to 1080p, or higher with an eligible account;
* accepts playback-speed changes;
* sends and expects danmaku;
* expects the receiver to report progress, quality and state once a second.

So the ceiling is set by *which protocol the receiver speaks*, not by the TV.

## What this build does

`app/src/main/java/com/weenas/castbay/bili/`:

| File | Role |
|---|---|
| `Nva.kt` | Advertised identity, device description and SCPD, binary framing |
| `NvaServer.kt` | One port (9958): device/SOAP descriptions over HTTP, plus the NVA control channel |
| `NvaSsdp.kt` | Answers the phone's SSDP search for `NirvanaControl:3` and announces periodically |
| `NvaReceiver.kt` | Lifecycle and a stable device UUID |

**This is a probe.** It answers every command and writes what arrived, but fetches and plays
nothing, so the phone will sit on its loading state. The point is to confirm, against a real
phone, what the wire format actually is before the playback path is built on it:

* whether the TV appears under the phone's cast button at all;
* what `Play` really carries (we expect `aid`/`cid`/`epid`, `accessKey`, `userDesireQn`);
* whether the phone uses the SOAP `GetAppInfo`/`GetPlayInfo` path at all, and with what.

The framing is hand-ported from the MIT-licensed reference implementations
([Macast nirvana plugin](https://github.com/xfangfang/Macast-plugins/tree/main/nirvana) and the
[Kodi receiver](https://github.com/autulin/kodi-bilibili-cast) built on it), so `NvaFramingTest`
encodes frames the way the phone does and reads them back; a byte-offset mistake there would
look exactly like "the phone ignores us".

## How to test

1. Install the probe APK. The debug build shares no package with the official app, so it can
   sit beside it.
2. **Stop the official 云视听小电视 if it is installed** — it holds `tcp/9958`, and whichever
   starts first keeps it:
   ```
   adb shell am force-stop com.xiaodianshi.tv.yst
   ```
3. Open a video in the Bilibili phone app → TV icon. Look for a device named after this TV.
4. Cast to it. Then read the log.

Log locations (debug build):

```
adb pull /sdcard/Android/data/com.weenas.castbay.debug/files/logs/castbay.log
```

Every Bilibili line is tagged `CastBayBili`, and command lines read:

```
Bilibili → Play {"aid":80433022,"cid":137649199,"qn":80,"userDesireQn":80,"accessKey":"abcdef…(共 32 位)"}
```

The `accessKey` is always masked: it is the account's credential and never needs to be in a log.

## What plays now

The `Play` command is answered in full:

1. `/x/tv/playurl` is called with the phone's `accessKey` and `fnval=4048`, so the reply is DASH
   rather than the 720p muxed `durl` a plain DLNA renderer gets.
2. A track is picked: `avc1` first (the client's decoder handles HEVC poorly), highest height at
   or below 1080p.
3. The two tracks are wrapped in a small static MPD (`profiles="isoff-on-demand"`, one
   `SegmentBase` with `indexRange` plus `Initialization` per track) and served from this port, so
   `http://127.0.0.1:9958/bili/manifest.mpd` needs no network interface to be up and ExoPlayer
   can seek inside a ten-minute video without downloading it first.
4. The phone is told what happened through the control channel — `OnPlayState`, `OnProgress`
   (once a second), `OnQnSwitch`, `OnEpisodeSwitch`, `SpeedChanged`, `PLAY_SUCCESS`. Its progress
   bar, quality menu and speed slider are drawn from these alone.

Measured on a Nubia tablet (Android 13) with a phone on the same Wi-Fi:

```
Bilibili cast: aid 116985195143005 cid 40310997685 from 3456 s, asking qn 64
Bilibili stream: 1920x1080 avc1.640033 qn 80 · audio 30280 · 5041 s · account quality 64
Bilibili → SwitchSpeed {"speed":1.5}  → speed 1.5x
Bilibili → SwitchQn {"qn":112}        → quality switch at 3485 s
```

## Known limits of this build

* **Quality stops where the account stops.** Asking for qn 112 (1080P+) on a non-VIP account
  returns qn 80; the reply reports the qn that was actually granted, so the phone's menu snaps
  back to what is playing instead of lying.
* **Danmaku is not drawn.** The switch is acknowledged and reported as off, so the phone's own
  toggle matches the screen. Rendering it needs the ASS/subtitle path.
* **No live rooms yet.** `Play` with a live `roomId` is answered with a failure state.
* Port 9958 is shared with the official app; only one of them can run.
* It starts and stops with the DLNA setting, next to the Plex receiver.

## Next step

Danmaku (fetch `/x/v1/dm/list.so?oid=<cid>`, convert to ASS, feed the existing subtitle
pipeline) and live rooms (`/xlive/web-room/v1/playUrl`, an HLS variant instead of DASH).
