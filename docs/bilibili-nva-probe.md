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

## Known limits of this build

* Nothing plays. `Play` is acknowledged, then silence, so the phone shows its loading state.
* Port 9958 is shared with the official app; only one of them can run.
* It starts and stops with the DLNA setting, next to the Plex receiver.

## Next step (not in this build)

Once the frames are confirmed: call `/x/tv/playurl` with the phone's `accessKey` and
`fnval=4048` for DASH, build a minimal MPD (`SegmentBase` + byte ranges — Bilibili serves one
fragmented file per track, and it carries an `sidx`), serve it on this port, and play it with the
existing ExoPlayer path. Danmaku becomes an ASS subtitle through the existing subtitle pipeline;
speed needs one call (`setPlaybackSpeed`) that the current player lacks.
