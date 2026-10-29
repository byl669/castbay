---
layout: ../layouts/Doc.astro
lang: en
page: privacy
title: 'Privacy Policy – CastBay'
description: 'CastBay''s privacy policy: no account, no ads, no third-party analytics; nothing is sent unless you choose to. Lyrics lookups, the update check, problem reports and anonymous usage statistics are optional.'
heading: 'Privacy Policy'
intro: 'Effective: October 29, 2026'
---

CastBay is an open-source casting receiver app for Android TVs, car displays and tablets. It needs no account and contains no ads or third-party analytics or tracking code.

## What we collect

Nothing, unless you choose to. CastBay does not collect personal information, and does not send your usage to us or anyone else on its own. Only two things can reach us from the app, both off until you choose them: a [problem report](#problem-reports-only-when-you-send-one), with personal details taken out first, and [anonymous usage statistics](#usage-statistics-only-if-you-turn-them-on).

## What you cast

- AirPlay mirroring and music travel only between your phone and TV, on your local network.
- When an app casts a video or song (AirPlay video or DLNA), the TV fetches it directly from the address that app provides, just as if you watched it on the TV. The service providing the content is responsible for its data handling.
- CastBay announces its device name on your local network so your phone can find it.

## Lyrics (optional)

"Show lyrics" is off by default. When you turn it on, CastBay sends the song's title, artist, album and length to [lrclib.net](https://lrclib.net) to look up its lyrics. Nothing that identifies you is included, and that site's own policy applies to it. You can turn this off at any time in Settings or the quick menu while playing.

## Update check

Once a day, CastBay asks this website (castbay.weenas.com/latest.json, or through [the relay](#the-relay-castweenascom) if the website can't be reached), else GitHub (api.github.com), for the number of the latest CastBay version, so it can tell you when a newer one is out. The request contains nothing about you or your TV; the server sees your network's address, as for any web page (the website is hosted by Cloudflare; GitHub's own policy applies to GitHub). You can turn this off in Settings, under General.

If you turn on **Receive Test Versions** in Settings, under General (off by default), CastBay also asks GitHub (api.github.com) for the list of CastBay's releases, test versions included, so it can offer a test version in About. That request, too, contains nothing about you or your TV; GitHub sees your network's address and GitHub's own policy applies.

Only when you choose **Download and install** on the About screen does CastBay download the new version, from this website or GitHub, check that it is exactly the released file (its SHA-256), and open Android's installer, where you confirm the update. Nothing is downloaded or installed on its own.

## Problem reports (only when you send one)

A problem report is sent in two cases, both your choice:

- **You upload one**: About → Diagnostics → **Upload log**, useful on a device you can't connect to a computer (a car display, for example). Nothing is sent until you press Upload on the screen that says what will be sent.
- **After CastBay stopped unexpectedly** (a crash, or Android closing it as not responding): when it next starts, the home screen asks whether to send a report about it, and sends one only if you press Send. If you turn on **Send Error Reports** (Settings → General, off by default), such reports are sent without asking; turn it off to stop that.

**What a report contains**

- The device: maker, model, Android version and build, screen size and density, whether it has a touch screen, CastBay's version, and how long the device has been on.
- The Diagnostics events shown on that screen: when CastBay started and stopped, remote and steering-wheel keys, taps, audio focus, and why the app last ended.
- CastBay's own recent log (at most about 400 KB): what the receiver did, such as connections, the video format and decoder, and errors. Android lets an app read only its own log, not other apps'.
- For a report after a crash: the error and where in CastBay's code it happened (the stack trace), and CastBay's log lines just before it.
- If CastBay's previous run ended while it was receiving, without CastBay stopping it (a crash, or Android closing it): that run's last log lines, of the same kind.

**What is taken out on the device, before sending**

- Song and video titles, artists and albums, and the names of phones and computers that cast (for example "Alex's iPhone").
- Links (the addresses of what was played), PINs and passwords, email addresses and hardware (MAC) addresses.
- Internet addresses. Local network addresses (such as 192.168.1.5) are kept, as they help find network problems; they say nothing about where you are.

**Where it goes and for how long**

The report is sent over HTTPS to this website (castbay.weenas.com, hosted by Cloudflare) and kept in its storage for 90 days, then deleted automatically. Only the developer can read it: there is no public link to it. You get a short report ID (such as CB-7K3F9Q) to give in your problem report; nothing else ties the report to you. Your network's address is used only to limit how many reports can be sent in a row and is not stored with the report. To have a report deleted sooner, open an issue on [GitHub](https://github.com/weenas/castbay/issues) with its ID.

## CastBay Desktop (the computer program)

CastBay Desktop, the Windows and Linux program that casts a computer's screen to a CastBay receiver, has no account and no tracking code either. Beyond what the TV app does for casting, it asks the network for two things on its own, both to find a newer version: GitHub's list of releases (and, where GitHub can't be reached, this website's note on the newest one) when it starts, and, when you press **Update**, the installer, from this website or GitHub. Nothing about you or your computer is sent with them (the servers see a network address, as for any web page).

The one thing it can send of its own kind is a **problem report**, in these cases only:

- **You upload one**: Settings → About → **Diagnostics** shows what would be sent, and nothing leaves the computer until you press **Upload log** and confirm.
- **After it ended badly** (a crash, or it was closed from outside during a cast): the next time it starts, the home screen asks whether to send a report about it, and sends one only if you press Send.
- **If you turn on Send error reports** (Settings, off by default): such a report is sent without asking, and so is one when a cast fails because of the encoder or the screen capture (at most one an hour). Turn it off to stop that.

The report holds:

- This computer's operating system and architecture, the web view's version, which video encoders (graphics card or CPU) open on it, and CastBay Desktop's settings (frame rate, bitrate, encoder, sound on or off, language) and the number of receivers paired. No names.
- The program's own recent log (at most about 400 KB): casts started and ended, the encoder used, errors.
- For a report after a crash: the error and where in CastBay's code it happened (the call stack). For a failed cast: the kind of failure and its message.

Taken out on the computer before sending: every network address, the names of receivers, and your user folder in file paths. Window titles are not written to the log at all.

It is kept and read as the TV app's reports are (see above): over HTTPS to this website, or its relay where Cloudflare can't be reached, kept 90 days, readable only by the developer, identified by a short report ID that you give in your problem report.

## Usage statistics (only if you turn them on)

**Send Anonymous Usage Statistics** (Settings → General) is off by default; nothing is counted while it is off. When you turn it on, CastBay makes a random installation ID (it says nothing about the device or you) and counts, on the device, what happens each day. Once a day it sends the previous day's summary to this website:

| Sent | Example |
| --- | --- |
| The random installation ID and the day | 3f2a…, 2026-10-02 |
| CastBay's version | 1.2.0 |
| Android version, and the device's maker and model | 12, Xiaomi MiTV4 |
| Screen class, kind of device, touch screen or not | 1080p, TV, no |
| The system's language | zh |
| Casts that day by protocol and kind | AirPlay mirroring 3, DLNA video 1 |
| How long casts lasted, in three bands | under 1 minute 2, 1–10 minutes 1, over 10 minutes 1 |
| Mirroring resolution class and codec | 1080p H.264: 3 |
| Failures, by kind | receiver 0, video decoder 1, video playback 0 |
| Settings in use | casting verification: PIN; DLNA on; lyrics off; resolution: Auto |

Never sent: device names (the TV's or a phone's), song or video titles, links, network or hardware addresses, the Wi-Fi name, your location, exact times of casts, passwords or PINs. The website keeps one row per installation and day in its database (Cloudflare D1) and does not store your network's address; rows are deleted after a year. Only the developer can read them, to see which devices and Android versions to support and where CastBay fails. **Turning the switch off** stops counting, forgets the ID and asks the website to delete everything sent under it.

**CastBay Desktop** has its own switch, **Send anonymous usage statistics** (Settings, off by default, nothing is counted while it is off), with its own random installation ID, made when you turn it on and forgotten, with its counts, when you turn it off (the website is then asked to delete its rows). Once a day it sends:

| Sent | Example |
| --- | --- |
| The random installation ID and the day (UTC) | 3f2a…, 2026-10-29 |
| CastBay Desktop's version | 0.1.4 |
| The operating system (Windows build number, or Linux distribution and version), processor type, and on Linux whether the session is Wayland or X11 | 10.0.19045; ubuntu 26.04, x86_64, wayland |
| The class of the biggest screen, and the program's language | 1080p, zh |
| Which video encoders open on the computer | NVIDIA, CPU |
| Casts that day: whole screen or one window, how long in three bands, the encoder used, frame rate, bitrate in three bands, sound or not | screen 2, 1–10 minutes 1, NVIDIA 2, 30 fps 2, 6–11 Mbit/s 2, with sound 2 |
| Failures, by kind, and updates installed from the program | encoder 1, update installed 1 |
| Settings in use | encoder: automatic; language: automatic; update check on; error reports off |

Never sent: the computer's or receivers' names, window titles or screen contents, network or hardware addresses, your location, exact times or lengths of casts. It goes to this website, or to its relay where Cloudflare can't be reached, and is kept as the TV app's statistics are.

## The relay (cast.weenas.com)

In some networks (often in mainland China) this website, on Cloudflare, can't be reached. CastBay then sends the same three requests (the update check, problem reports and usage statistics) to cast.weenas.com instead: a relay on the developer's server in the United States that passes them on, over HTTPS and unchanged, to this website, and passes the answer back. It stores nothing and keeps no access log; it answers nothing else. It tells this website your network's address only for the upload limits described above, which don't store it either. What is sent and kept is exactly as described above.

## Settings and data on the TV

Your settings (such as the device name and casting password) stay on the TV and are never uploaded, as does the list of devices that have cast to it (their names and AirPlay device IDs, whether each is allowed, and the pairing keys of devices paired with a PIN). You can remove the devices in Settings; uninstalling CastBay deletes all of it.

The record on About → Diagnostics (recent starts, keys, taps and audio-focus events, and the device's model, Android version and screen size) also stays on the device, at most the latest 120 events: it is only shown when you open that screen, can be cleared there, and leaves the device only in a problem report you upload.

CastBay also keeps its recent log in its own storage on the device, the current run's and the previous one's, a few hundred KB at most, so that a problem report can show what happened before a crash. No other app can read it; it leaves the device only in a problem report you send, with personal details taken out as above, and uninstalling CastBay deletes it.

## Permissions

- Network: to receive casts and play what apps cast.
- Location, requested only when you choose "Show Wi-Fi name": Android only lets apps with this permission read the Wi-Fi name. CastBay uses it to show the network's name on the home screen and never reads or uses your location.
- Start at boot and foreground service: so CastBay is ready when the TV turns on and keeps playing in the background.

## Children's privacy

CastBay collects no personal information from anyone, including children. Problem reports have personal details taken out before they are sent.

## Changes and contact

If this policy changes, we will update this page and its effective date. For questions, please open an issue on [GitHub](https://github.com/weenas/castbay/issues).
