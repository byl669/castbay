---
layout: ../layouts/Doc.astro
lang: en
toc: true
page: tech
title: 'How CastBay works – CastBay'
description: 'AirPlay 2 and DLNA/UPnP explained: CastBay support, device discovery, music synchronization and mirroring, with links to official resources.'
heading: 'How CastBay works'
intro: 'What happens between your phone and the TV, and why CastBay behaves the way it does.'
---

## What are AirPlay and AirPlay 2?

AirPlay is Apple’s wireless media-sharing technology for sending music, video or a screen image to compatible receivers. AirPlay 2 extends the audio experience with multi-room playback, enhanced buffering and multi-device control. It is not simply higher-resolution mirroring, nor does it mean every stream uses lossless encoding.

### How it works, in brief

1. **Discover**: in CastBay’s local-network setup, the receiver advertises services through Bonjour (mDNS/DNS-SD). The sender finds it and reads its capabilities.
2. **Connect**: the devices negotiate the playback mode, media format and any required verification, then establish control and media connections.
3. **Play**: mirroring sends an encoded screen stream; music sends an audio stream; some video casting modes let the receiver fetch and play a media URL.
4. **Buffer and synchronize**: timing information schedules playback, while buffering absorbs brief network interruptions. AirPlay 2 multi-room audio additionally coordinates compatible receivers so they play together, rather than each playing audio immediately on arrival.

**Protocol capabilities are not a CastBay feature checklist.** Built on UxPlay, CastBay provides AirPlay screen mirroring, music reception and compatible video casting. It does not currently provide AirPlay 2 multi-room grouping and synchronized playback, and should not be treated as a complete AirPlay 2 implementation. Compatibility depends on the sender, app, content and CastBay version.

Official resources: [Apple AirPlay](https://www.apple.com/airplay/) · [Apple WWDC: Introducing AirPlay 2 (PDF)](https://devstreaming-cdn.apple.com/videos/wwdc/2017/509v96e4gd96rlqyq/509/509_introducing_airplay_2.pdf). These describe Apple’s technology and ecosystem, not Apple certification or endorsement of CastBay.

## What is DLNA, and how does it relate to UPnP?

DLNA (Digital Living Network Alliance) developed interoperability guidelines for sharing media between different manufacturers’ devices on a home network. It is not an audio or video codec: it builds on technologies such as UPnP and specifies how discovery, control, media formats and transport work together. **UPnP supplies the underlying mechanisms; DLNA adds media interoperability requirements. They are not synonyms.**

### How it works, in brief

A typical app-to-CastBay media session works like this:

1. **Discover**: the phone app searches for UPnP AV devices using SSDP and reads their device descriptions.
2. **Control**: the app acts as a Control Point, sending a media URL and playback commands to a MediaRenderer such as CastBay. The media may come from a phone, NAS or internet service.
3. **Fetch**: the renderer typically retrieves the media over HTTP and decodes it. Media data need not pass through the Control Point; the phone can also act as a MediaServer.
4. **Interact**: UPnP services carry playback, pause, seek or volume commands and status queries or subscriptions. Available operations depend on both implementations and the media.

DLNA media casting is therefore not screen mirroring. If the phone serves the content, leaving the network or stopping its server interrupts playback. Independent playback requires the media source to remain reachable. Not every app’s cast button supports DLNA, and Google Cast is a different technology.

CastBay acts as the media renderer in this setup. Protocol compatibility does not imply that CastBay is DLNA Certified.

Official resources: [DLNA](https://www.dlna.org/) · [DLNA FAQ and certification](https://www.dlna.org/faq) · [OCF: UPnP AV architecture and MediaServer / MediaRenderer specifications](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/mediaserver1-and-mediarenderer1/).

## Three ways to cast, and what travels where

- **Screen mirroring** (Control Center → Screen Mirroring): the phone records its own screen, compresses it as video (H.264, or H.265 for 4K) and streams it over your Wi-Fi; the TV decodes it in hardware. Everything you see comes from the phone, so the phone has to stay on and nearby.
- **Casting from an app with AirPlay**: in compatible URL-based video casting, the phone tells the TV *where* the video is, and the TV fetches it directly. The receiver must be able to reach the source; the phone’s VPN or login access is not automatically shared. Other apps or content may use a different playback path or restrict casting. Music reception uses an audio stream; CastBay supports ALAC decoding, but the actual format and quality depend on the session and output path.
- **An app's own cast button (DLNA)**, as in Bilibili, iQiyi, NetEase Cloud Music or QQ Music: also hands the TV an address to play from. It works from iPhones and Android phones alike. How good the picture gets is up to the app: Bilibili, for example, keeps 1080p for its own TV app and offers other devices 720p; screen mirroring has no such limit.

## How your phone finds the TV

AirPlay discovers CastBay through Bonjour (mDNS/DNS-SD); DLNA uses SSDP. These are separate discovery mechanisms. With CastBay, the phone and receiver need a shared local network that allows them to communicate. Guest networks, AP isolation or multicast restrictions can prevent discovery. Also check that the receiver service is running, along with app permissions and firewall settings.

## Why music starts about two seconds late, and why that's a good thing

An iPhone doesn't send music "as it plays". It sends each piece about two seconds ahead, stamped with the moment it should be heard, and keeps the TV's clock in step with its own. CastBay plays every piece at exactly that moment, the way an Apple TV or HomePod does. That short wait buys a lot:

- **Lyrics stay in sync**, because the phone's idea of "now" and what you hear on the TV are the same moment.
- **Pausing and resuming pick up where they left off**, with nothing skipped or repeated. (Playing music the instant it arrived would put the TV two seconds ahead of the phone, and every resume would replay those two seconds.)
- Wi-Fi hiccups are absorbed by the two seconds in hand, instead of being heard as stutters.

Apps pause differently, and CastBay follows each, as an Apple TV does. Apple Music stops the TV at once. NetEase Cloud Music just stops sending, so what the TV already has plays out for a couple of seconds; when you resume, it carries on without a gap.

CastBay includes Apple’s open-source ALAC decoder to receive and decode ALAC audio without depending on a device-provided decoder. ALAC is lossless encoding, but the source may already have been lossily encoded or resampled, and the transport format depends on the sender and playback mode. Android audio processing and the output path also affect the result. ALAC support therefore does not guarantee end-to-end lossless playback in every scenario.

## Mirroring quality, and reading the stats

On a 4K TV with a hardware H.265 decoder, CastBay offers the phone H.265 up to 4K at up to 60 frames per second; otherwise H.264 up to 1080p. The phone chooses what it actually sends within that.

Turn on **Show stats** (Settings → Playback, or the quick menu while playing) to see what's going on. While mirroring, the phone also reports on itself:

- **Video**: what the TV receives and decodes (codec, resolution, frame rate, bitrate), how long each frame takes from arriving to being shown (decode), and frames it dropped.
- **Sender**: frames the phone sends against its target (e.g. 60/60), how many its screen drew (fewer while nothing moves, which is normal), and frames it dropped.
- **Network**: round trip time, packet loss, and the bandwidth used against what the phone thinks the link can carry.

So a stutter can be traced: packet loss or a bandwidth close to its limit points to Wi-Fi; the phone sending fewer frames than its target points to the phone being busy; the TV dropping frames points to the TV.

## Who can cast to your TV

Settings → Connection → Casting verification offers four choices:

- **Not required**: anyone on your network can cast.
- **Confirm**: the first time a device casts, the TV asks whether to allow it. The device is turned away while the TV asks, so once you allow it, cast again. (The TV can't hold the phone waiting: the AirPlay core serves every connection in turn, and waiting would freeze whatever is already playing.)
- **PIN**: as on an Apple TV, a new device enters a four-digit PIN shown on the TV, once. The two then trust each other's keys, so it connects straight away after that, and a device that never entered the PIN can't pretend it did.
- **Password**: every device enters the same password.

Whichever you choose, every device that has cast is listed there, and any of them can be blocked. Removing them all makes every device new again; if any had paired with a PIN, the TV also takes a new identity, so phones pair again instead of failing to connect.

## The remote and the connection

- **Controlling the phone's music from a remote or steering wheel.** CastBay holds Android's audio focus while casting, so a TV remote's or a car's steering-wheel media keys come to it. When a music app plays over AirPlay, play/pause, previous and next go back to the phone, over AirPlay's remote-control channel (DACP), and the phone carries them out, just as with an Apple TV remote. That channel has play, pause and track commands but no "jump to a time", which is why AirPlay music has no skip-10-seconds buttons: seek on the phone instead. (DLNA music plays on the TV itself, so it can skip exactly, and its progress bar can be dragged.)
- **When a second device casts.** Settings → Connection → "When another device casts" decides: *Refuse it* (the default) keeps the current cast and turns the newcomer away; *Let it take over* ends the current cast and shows the new device's, name and all.
- **Why a cast ends by itself.** While mirroring or playing music, the phone sends a heartbeat every two seconds, even when paused. If none arrives for five seconds (the phone left the Wi-Fi, ran out of battery, or its app was closed), the TV takes the cast as over and goes back to its home screen rather than freezing on the last picture. Videos cast from apps play on the TV by themselves, so they don't need one.

## What isn't supported, and why

- **Google Cast (Chromecast)**: CastBay currently does not support Google Cast media casting or screen mirroring. Use AirPlay or DLNA instead.
- **Miracast and Android's built-in screen mirroring**: they need access to the TV's system that an app doesn't get. From Android phones, use an app's cast button (DLNA).
- **The Mac's Music app**: it protects its stream with a kind of FairPlay encryption that the open-source AirPlay implementation can't handle. Screen mirroring from a Mac works.

## Built on open source

CastBay is open source under GPL-3.0. It builds on [UxPlay](https://github.com/FDH2/UxPlay) for the AirPlay protocol, [libplist](https://github.com/libimobiledevice/libplist), [Apple's ALAC decoder](https://github.com/macosforge/alac) and [Media3 ExoPlayer](https://developer.android.com/media/media3), with Android's hardware video decoding. The full source is on [GitHub](https://github.com/weenas/castbay).
