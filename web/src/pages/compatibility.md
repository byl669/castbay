---
layout: ../layouts/Doc.astro
lang: en
page: compatibility
title: 'Compatibility – CastBay'
description: 'Which TVs, phones and apps CastBay has been tested with, and how well each works.'
heading: 'Compatibility'
intro: 'What CastBay has been tested with so far. Tried it on something not listed, or found something that doesn''t work? <a href="/feedback">Tell us</a>.'
---

## Supported casting features

CastBay supports **AirPlay screen mirroring, music and video casting from compatible apps, and DLNA/UPnP media playback**. Support is described by the features tested below, not a blanket “AirPlay 2” claim. Full AirPlay 2 support, including multi-room synchronized audio, is not claimed.

| Feature | Scope |
| --- | --- |
| AirPlay screen mirroring | Tested with iPhone and Mac; iPad is not yet confirmed |
| AirPlay music | Tested with iPhone apps listed below; the Mac Music app is not supported |
| AirPlay video casting | Tested with compatible apps listed below; support varies by app and content |
| DLNA/UPnP media playback | Receives media from compatible apps; not Android screen mirroring |
| AirPlay 2 multi-room synchronized audio | Not supported |

## TVs and car displays

| Device | Android | Status |
| --- | --- | --- |
| Sony BRAVIA XR-55X90L (4K) | 12 | ✅ Tested: H.265 mirroring in 4K, everything else |
| TCL TV | 9 | ✅ Tested |
| Xiaomi TV 4 (MiTV4, Amlogic, 32-bit) | 6.0.1 | ✅ Tested: mirroring from an iPhone, music; about 0.3 s of decoding latency, more than newer TVs |
| Google TV Streamer | 14 | ✅ Installed and running; hardware HEVC (4K mirroring on a 4K screen) |
| BYD car display, DiLink 5.0 (touch screen) | 12 | ✅ Tested: mirroring, music, video, steering-wheel buttons (pause, change tracks); force-stopped when switched off, so open CastBay after starting the car |

CastBay needs an Android device on Android 6.0 or later. Other brands of TVs, TV boxes, car displays and tablets should work, with a remote or a touch screen; 4K H.265 mirroring needs a TV with a hardware HEVC decoder (the home screen says what it offers).

## Phones and computers

| Device | System | Status |
| --- | --- | --- |
| iPhone 17 Pro Max | iOS 26 | ✅ Tested: mirroring, music, video, PIN pairing |
| Samsung Galaxy (Android phone) | Android 16 | ✅ Tested: DLNA from NetEase Cloud Music |
| iPad | iPadOS | ❔ Not yet confirmed; uses the same AirPlay as an iPhone |
| MacBook: screen mirroring | macOS | ✅ Tested: mirroring in 1080p and 4K; for latency see the [FAQ](/faq) |
| Mac: the Music app | macOS | ❌ Not supported: it uses an encryption the open-source AirPlay library can't handle |

## Apps

| App | How | Status |
| --- | --- | --- |
| Apple Music | AirPlay | ✅ Lyrics in sync; pausing and resuming pick up where they left off |
| NetEase Cloud Music (iPhone) | AirPlay | ✅ Works; after a pause from the TV, what the TV has plays out for a couple of seconds |
| NetEase Cloud Music (Android) | DLNA | ⚠️ Plays, with titles and cover; the phone's play state isn't always in step |
| YouTube (iPhone) | AirPlay | ✅ Up to 4K on 4K TVs; the TV must be able to reach YouTube itself |
| YouTube (Android) | — | ❌ Only casts with Google Cast, which CastBay can't receive |
| iQiyi | AirPlay, screen mirroring | ✅ Works |
| Bilibili | DLNA | ⚠️ Works, up to 720p (Bilibili's limit for other devices) |
| QQ Music | DLNA | ❔ Not yet confirmed |
| Any app | Screen mirroring | ✅ Whatever is on the phone's screen |

## Media formats (DLNA)

Apps' own cast button (DLNA) sends the TV a link, and the TV plays it. CastBay tells the app which formats it takes, so an app usually offers it only for those:

| Type | Formats | Status |
| --- | --- | --- |
| Video files | MP4, MOV, MKV, WebM, FLV, MPEG-TS, MPEG-PS | ✅ Plays; the codec inside must be one the TV decodes (H.264 everywhere; H.265, VP9 or AV1 only with the TV's hardware decoder) |
| Streaming video | HLS (.m3u8), DASH (.mpd) | ✅ Plays, adapting to the network; a link without .m3u8 or .mpd plays if the app says which it is |
| Music | MP3, AAC (M4A), WAV, Ogg (Vorbis, Opus) | ✅ Plays, with the music screen |
| Music | FLAC | ✅ On Android 8.1 and later; earlier versions have no FLAC decoder, so CastBay doesn't offer it there |
| Subtitle files | SRT, WebVTT, ASS/SSA, TTML | ✅ Shown when the app sends them with the video (e.g. NAS and media-server apps); Chinese files in GBK are read correctly; they can be turned off in the quick menu |
| Pictures | JPEG, PNG, … | ❌ Not shown: photo apps don't offer CastBay, and a picture sent anyway is refused |

A link the TV can't play shows "This TV can't play the video's format" and ends the cast. AirPlay video from iPhone apps always arrives as HLS. Copy-protected (DRM) videos and Microsoft Smooth Streaming aren't supported.

## Not supported

- **Google Cast (Chromecast)**: CastBay currently does not support Google Cast media casting or screen mirroring. Use AirPlay or DLNA instead.
- **Miracast and Android's own screen mirroring**: they need system access an app doesn't get.
- **Photos over DLNA**: CastBay plays video and music, not pictures; to show photos, mirror the phone's screen.
