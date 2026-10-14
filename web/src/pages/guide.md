---
layout: ../layouts/Doc.astro
lang: en
toc: true
page: guide
title: 'User guide – CastBay'
description: 'How to use CastBay: installing and updating it, casting from iPhone, iPad, Mac and Android, the remote, touch screens and the quick menu, and every setting.'
heading: 'User guide'
intro: 'Everything about using CastBay, from installing it to each setting. For problems, see the <a href="/faq">FAQ</a>; for how it works, see <a href="/tech">How it works</a>.'
---

## What you need

- An Android device on Android 6.0 or later: a TV, a TV box, a car display or a tablet, worked with a remote or a touch screen.
- The TV and your phone on the same network, not a guest network. The TV can be wired and the phone on Wi-Fi, as long as both go through the same router.

## Install and update

### 1. Get the APK

The latest version is always at **castbay.weenas.com/apk** (the website's Download APK button); older ones are in the [changelog](/changelog). It is one APK for every device, 32-bit and 64-bit alike. CastBay needs Android 6.0 or later. It is also coming to F-Droid; see [where to get CastBay](/faq#where-can-i-get-castbay-and-can-i-switch-between-sources) before switching between them.

### 2. Put it on the device

Use whichever way your device allows; most TVs offer more than one.

- **The TV's browser**: type `castbay.weenas.com/apk` in its address bar. When the download finishes, open it and install.
- **A USB drive**: copy the APK onto a USB drive, plug it into the TV, and open the APK in the TV's file manager or media center. If the TV's own file manager doesn't show APK files, install a file manager from its app store first (ES File Explorer, File Commander and the like).
- **From your phone**: apps such as Dangbei Assistant or Shafa Butler (当贝助手, 沙发管家), or the TV maker's own phone app (Xiaomi's TV Assistant, for example), send an APK from the phone to the TV over the home network and install it. Phone and TV must be on the same network.
- **An app on the TV**: on Android TV and Google TV, install **Downloader** (or a file manager) from the Play Store, then enter `castbay.weenas.com/apk` in it. On Chinese TVs, an app store such as Dangbei Market (当贝市场) can install APKs too.
- **From a computer (adb)**: turn on the TV's developer options and network debugging (see below), then, with Android's platform tools on the computer:

  ```
  adb connect <the TV's IP address>
  adb install CastBay.apk
  ```

  The TV asks once to allow debugging from this computer: allow it. The TV's network settings show its IP address.

### 3. Allow installing apps

The first time you install an APK, Android asks you to allow installing apps from that source (the browser, the file manager, the app that sent it). Allow it, then go back and install. Where the switch is, if the TV doesn't ask:

| Device | Where to allow installing apps |
| --- | --- |
| Xiaomi TV and box | Settings → Account & Security → Allow installing apps from unknown sources (ADB debugging is there too) |
| TCL TV | Settings → System (or Security) → Unknown sources |
| Sony, Philips and other Android TVs | Settings → Device Preferences → Security & restrictions → Unknown sources, then turn on the app you install from |
| Google TV (Chromecast with Google TV, Google TV Streamer, Sony and TCL Google TVs) | Settings → Apps → Security & restrictions → Unknown sources. Developer options: Settings → System → About, press Android TV OS build seven times |
| Car displays | Depends on the maker: some install from a USB drive or their own app store; others don't allow other apps at all |

Menus differ between models and system versions; if yours isn't there, search the settings for "unknown sources". Developer options usually appear after pressing the build number (or the model) seven times in Settings → About.

CastBay can't be installed on TVs whose system isn't Android: Samsung (Tizen), LG (webOS), Hisense's VIDAA models and Huawei's HarmonyOS screens. Use an Android TV box with them instead.

### 4. Open CastBay

Open it from the TV's apps. It starts receiving right away, and is ready whenever the TV turns on from then on. (On a car display, open it after starting the car: see the [FAQ](/faq).)

### If it won't install

- **"There was a problem parsing the package"**: the device's Android is older than 6.0, or the download is incomplete (download it again).
- **"App not installed"**: a CastBay signed differently is already there, for example one you built yourself: uninstall it first. Also check the device has free storage.
- **The installer says installing is blocked**: allow installing apps from that source, as above.
- **The file manager doesn't list the APK**: install another file manager, or use a different way above.

### Updates

CastBay looks for a newer version once a day; to look now, press **Check for updates** on About. When a newer version is out, a red dot appears on the home screen's About button. Open About and press **Download and install**: CastBay downloads the new version, checks it, and opens Android's installer, where you confirm. The first time, the TV asks you to allow CastBay to install apps; allow it. Your settings are kept. (Or scan the QR code on About with your phone to download it yourself.)

## The home screen

- **Left**: the CastBay mark and the status. While it says "Waiting for a connection…", phones can find the TV. Below are Settings, Help and About.
- **The panel on the right**: the name phones see, the network and IP address, what mirroring it offers (e.g. H.265 · 2160p), whether DLNA is on, the casting verification, and whether a second device is refused or takes over.
- **Quitting**: press Back twice on the home screen. CastBay then stops receiving until you open it again.

## Casting from an iPhone or iPad

### Screen mirroring

1. Swipe down from the top-right corner to open Control Center and tap Screen Mirroring.
2. Choose CastBay's name (by default "CastBay" plus the TV's name; you can change it in Settings).
3. Your screen and sound appear on the TV. To stop, tap Stop Mirroring in Control Center, or press Back twice on the TV.

### Video from an app

In YouTube, iQiyi and other apps, tap the AirPlay icon (a rectangle with a triangle) on the player and choose CastBay. The TV plays the video straight from the internet, so you can lock the phone or use other apps.

### Music

In Apple Music, NetEase Cloud Music and other apps, tap the AirPlay icon there or on the music card in Control Center, and choose CastBay. Music starts about two seconds later: that keeps the TV in step with the phone, which is also what keeps lyrics in sync (see [How it works](/tech)).

## Casting from a Mac

Click Control Center in the menu bar → Screen Mirroring, and choose CastBay. Sending music from the Mac's Music app isn't supported.

## Casting from an Android phone

Use an app's own cast button (DLNA): in Bilibili, iQiyi, NetEase Cloud Music, QQ Music and similar apps, tap the cast button on the player (usually a TV icon) and choose CastBay. DLNA casting must be on in Settings (it is by default). These apps' buttons work from iPhones too.

## The first time a device connects

If casting verification is on in Settings, a new device casting for the first time:

- **Confirm**: is turned away while the TV asks "… wants to cast to this TV". Choose Allow, then cast again from the device; Block keeps it from casting.
- **PIN**: enters the four-digit PIN the TV shows in the box that appears on the phone, once; the device is remembered after that. OK or Back puts the PIN away, and the device gets a new one when it tries again.
- **Password**: enters the password from Settings. The phone remembers it until the password changes.

Apps' own cast button (DLNA) has no way to enter a PIN or password, so with any of the three, a phone casting from an app for the first time is confirmed on the TV as above. DLNA doesn't say which phone is casting, so the TV knows it by its address on the network, listed as the app and that address (e.g. "Bilibili · 192.168.1.23").

## While playing: remote and touch screen

- **Video cast from an app**: OK or Play pauses and resumes; Left and Right skip back and ahead 10 seconds. On a touch screen, tap it and pause or skip in the menu.
- **Music**: Left and Right move between the buttons, OK presses one; on a touch screen, tap them. For AirPlay music they are previous, play/pause and next, carried out by the phone; for DLNA music, back 10 seconds, play/pause and ahead 10 seconds, and its progress bar can be dragged (or reached with Up and moved with Left and Right). AirPlay music's bar only shows where the song is: seek on the phone (see the [FAQ](/faq)). The remote's own play/pause key works too.
- **Screen mirroring**: the phone controls the picture; on the TV or car you only open the menu and stop the cast.
- **Down or Menu**: opens the quick menu along the bottom of the screen; Left and Right choose an option, OK changes it, Back or Down closes the menu. On a touch screen (a car's, for example), tap the screen to open or close it.
- **Media keys** (a remote's, or a car's steering-wheel buttons): play/pause, next and previous control the music, carried out by the phone.
- **Back twice**: stops the cast and goes back to the home screen.
- **Home**: goes to the TV's home screen; the cast keeps playing in the background.
- **Volume**: set it on the phone; the TV shows a volume bar in the corner for a moment.

## The quick menu

Press Down or Menu while playing, or tap a touch screen. It closes by itself after five seconds untouched. What it offers depends on what's playing:

- **A seek bar**, **Pause** and **Back** / **Ahead** 10 seconds (videos cast from apps, AirPlay or DLNA): drag the bar, or press Up to reach it and Left or Right to move it.

- **Playback stats**: codec, resolution, frame rate, bitrate and more; while mirroring, also the phone's frame rate and network (see [How it works](/tech)).
- **Picture** (video and mirroring): Fit (all of it, perhaps with black bars), Fill (the whole screen, perhaps cropping the edges) or Stretch (the whole screen, perhaps distorted).
- **Lyrics** (music): on or off.
- **Audio** and **Subtitles** (videos cast from apps, when there is a choice): another language, or subtitles off.

## The music screen

The blurred album cover fills the background; in front are the cover, title, artist, album and progress, with the playback buttons below. With lyrics on, they scroll along with the song on the right, or it says there are none. The top left shows which device is casting.

When music plays and nobody touches anything, the music screen does what Settings → "Music Screen When Idle" says (against burn-in by default):

- **Against burn-in**: after 10 minutes without a key or touch the picture dims, and moves a few pixels every minute, so an OLED TV doesn't keep the same picture in one place for hours.
- **Screen off**: after 2 minutes the screen goes black while the music plays on; tablets and car displays also turn their brightness down as far as it goes.
- **Off**: the screen stays as it is.

While lyrics are showing it neither dims nor goes black: they keep scrolling, so nothing burns in. When a song has no lyrics, it works as above.

Any key on the remote, a touch, or a new cast brings the picture back; that first key or touch only wakes it, without pressing a button. Media keys (play/pause, next, previous, on the remote or the steering wheel) work as usual and don't wake it. On the TV's home screen, with nothing casting, the TV's own screensaver takes over.

## Settings

Choose Settings on the home screen, and move between the four tabs at the top with Left and Right. Text fields, such as the device name, open the keyboard only when you press OK.

### Connection

Changing these restarts the receiver; a device that was casting connects again.

- **Device name**: the name phones see; "CastBay" by default.
- **Add the TV's name after it**: adds the TV's model or name after it, so several TVs are told apart. The full name phones see is shown below.
- **DLNA casting (apps' cast button)**: whether apps' own cast buttons can cast here.
- **Casting verification**: Not required, Confirm, PIN or Password; see "The first time a device connects" above. Password needs at least 4 digits.
- **When another device casts**: while one device is casting, another is refused, or takes over (the current cast ends). This holds across AirPlay and DLNA alike.
- **Devices that have cast**: each can be allowed or blocked; blocked devices can't cast. Remove all (press twice) makes every device new again.

### Screen mirroring

Only for mirroring a phone's screen, not apps' video and music. Changing these restarts the receiver.

- **Maximum Resolution**: Auto (2160p on a 4K TV with H.265, otherwise 1080p), 720p or 1080p.
- **Maximum Frame Rate**: Auto (60 fps) or 30 fps. On a weak network, 30 can be smoother.
- **Codec**: Auto (H.265 when the TV decodes it in hardware) or H.264 only. If H.265 mirroring looks wrong, try H.264 only.

### Playback

These apply at once, and can also be changed from the quick menu while casting.

- **Show playback stats**.
- **Show lyrics for music**: looks the song's title and artist up on lrclib.net; off by default.
- **Picture**: Fit, Fill or Stretch.
- **Music Screen When Idle**: Against burn-in (the default), Screen off or Off; see [The music screen](#the-music-screen). Not in the quick menu.

### General

- **Language**: System (the TV's), 中文 or English.
- **Check for Updates**: looks for a newer version once a day (on this website, or GitHub).
- **Send Error Reports** (off by default): after CastBay stops unexpectedly, sends a problem report when it next starts, without asking. Off, the home screen asks you each time. What a report contains is in the [privacy policy](/privacy#problem-reports-only-when-you-send-one).
- **Send Anonymous Usage Statistics** (off by default): once a day, counts of casts, failures and the settings in use, with this device's model and Android version; never names, titles or links. Turning it off deletes what was sent. Every field is listed in the [privacy policy](/privacy#usage-statistics-only-if-you-turn-them-on).
- **Restore Defaults**: press twice to put every setting back as it was when CastBay was installed.

## Help and About

Help lists how to cast in each way and what to do when something doesn't work, right on the TV. About shows the version, links to the website, privacy policy and source code (they open in the device's browser; a TV without one shows the link's QR code instead), and a QR code that downloads CastBay, to pass it on. When a newer version is out, About shows it with **Download and install**, and **What's New** lists every change since the version you have, in the app's language.

### Diagnostics and problem reports

About → **Diagnostics** shows this device (model, Android version, screen) and the app's recent events: starts and stops, keys, taps, audio focus, and why it last ended. It stays on the device. To report a problem:

1. Press **Upload log**. CastBay collects the device's details, these events and its own recent log, takes out song and video titles, device names, links, PINs and internet addresses, and shows what it will send and how big it is.
2. Press **Upload**. Nothing is sent before this.
3. CastBay shows a report ID, such as **CB-7K3F9Q**, and a QR code. Scan it with your phone: it opens the [feedback page](/feedback) with the ID filled in. Give the ID in your problem report.

Reports are kept for 90 days and only the developer can read them; see the [privacy policy](/privacy#problem-reports-only-when-you-send-one). Without a network, photograph the Diagnostics screen instead.
