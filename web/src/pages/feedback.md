---
layout: ../layouts/Doc.astro
lang: en
page: feedback
title: 'Feedback – CastBay'
description: 'How to report a problem with CastBay, suggest a feature, or tell us it works on your TV.'
heading: 'Feedback and contributing'
intro: 'CastBay is open source, and reports from people using it are how it gets better. Everything happens on <a href="https://github.com/weenas/castbay/issues">GitHub</a> (a free account is needed to post).'
---

## Report a problem

**From CastBay Desktop**: Settings → About → **Diagnostics** → **Upload log** sends a report you can point to with its ID (such as CB-7K3F9Q); then open an issue on [GitHub](https://github.com/weenas/castbay/issues) with what you did, the version (shown under Settings → About) and that ID.

<p class="report-id" id="report-id" hidden>Your report ID: <strong></strong>. It is filled in when you open the problem report below.</p>

Open a [problem report](https://github.com/weenas/castbay/issues/new?template=bug_report.yml). It asks for what we need to find the cause:

- **CastBay's version**: on the About screen.
- **The TV**: brand and model, and its Android version (the TV's settings, usually under About).
- **The phone or computer**: model and system version, e.g. iPhone 17, iOS 26.
- **How you cast, and from which app**: screen mirroring, the AirPlay icon in an app, or an app's cast button (DLNA); e.g. Apple Music, YouTube.
- **What happened**, what you expected, and the steps that lead to it. A photo or a short video of the TV helps a lot.
- **Diagnostics** (especially useful on devices you can't debug from a computer, like a car's): after the problem, open CastBay's About → Diagnostics and press **Upload log**, then give the report ID it shows (scanning its QR code brings you here with the ID filled in). Without a network, attach a photo of that screen instead. What a report contains is in the [privacy policy](/privacy#problem-reports-only-when-you-send-one).
- **The network**: Wi-Fi or wired, and anything unusual, like a mesh network, a VPN or a proxy.

Before posting, check the [FAQ](/faq) and whether someone has [already reported it](https://github.com/weenas/castbay/issues). Leave out anything private: passwords, your address, phone numbers.

## Tell us it works on your device

Used CastBay on a TV, phone or app not on the [compatibility list](/compatibility)? [Report it](https://github.com/weenas/castbay/issues/new?template=compatibility.yml), working or not, and it will be added.

## Suggest a feature

Ideas are welcome as a [feature request](https://github.com/weenas/castbay/issues/new?template=feature_request.yml): what you'd like to do, and why.

## Contribute

CastBay is under GPL-3.0. Code, translations and fixes to this website are welcome as pull requests; the [README](https://github.com/weenas/castbay#readme) explains how to build the app and the website.
