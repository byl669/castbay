<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="branding/castbay-signal-final/inline-dark.png">
    <img src="branding/castbay-signal-final/inline-light.png" alt="CastBay 映湾" width="480">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/weenas/castbay/actions/workflows/android.yml"><img src="https://github.com/weenas/castbay/actions/workflows/android.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/weenas/castbay/releases/latest"><img src="https://img.shields.io/github/v/release/weenas/castbay" alt="Latest release"></a>
  <a href="https://github.com/weenas/castbay/releases"><img src="https://img.shields.io/github/downloads/weenas/castbay/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/weenas/castbay" alt="License: GPL-3.0"></a>
  <a href="https://castbay.weenas.com/compatibility"><img src="https://img.shields.io/badge/Android-6.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 6.0+"></a>
  <a href="https://github.com/weenas/castbay/commits/main"><img src="https://img.shields.io/github/last-commit/weenas/castbay" alt="Last commit"></a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/weenas/castbay"><img src="https://api.scorecard.dev/projects/github.com/weenas/castbay/badge" alt="OpenSSF Scorecard"></a>
</p>

# 映湾 CastBay: AirPlay and DLNA Receiver for Android TVs, Car Displays and Tablets

**Cast it, it's there. Kick back with CastBay.** · 一投即达，自在映湾

**Website: [castbay.weenas.com](https://castbay.weenas.com)** · [User guide](https://castbay.weenas.com/guide) · [How it works](https://castbay.weenas.com/tech) · **Download: [CastBay.apk](https://github.com/weenas/castbay/releases/latest/download/CastBay.apk)** ([all releases](https://github.com/weenas/castbay/releases)) · [Privacy policy](https://castbay.weenas.com/privacy)

<p align="center">
  <a href="https://github.com/weenas/castbay/releases/latest/download/CastBay.apk"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="60"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/weenas/castbay"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60"></a>
</p>

CastBay (Chinese: 映湾) turns an Android TV, TV box, car display or tablet into a receiver for iPhone, iPad and Mac: AirPlay screen mirroring, music and video, plus the cast button in video and music apps (DLNA). Free, open source, no ads.

## Features

- **Screen mirroring** from iPhone, iPad and Mac, up to 60 fps; H.265 up to 4K on 4K TVs with hardware HEVC decoding.
- **Music**: a blurred-cover backdrop, optional synced lyrics (via lrclib.net) and round playback controls; AirPlay music is lossless ALAC, played when the phone means it heard (as on an Apple TV), so lyrics stay in sync and pausing and resuming pick up where they left off.
- **Video casting**: apps' AirPlay video (e.g. YouTube, iQiyi) plays straight from the source, with audio track and subtitle choices.
- **DLNA**: the cast button in apps such as Bilibili, iQiyi, NetEase Cloud Music and QQ Music, from iPhone and Android phones: video (MP4, MKV, WebM, FLV, HLS, DASH, …) and music (MP3, AAC, FLAC, …); not pictures. See [the formats](https://castbay.weenas.com/compatibility).
- **Remote or touch screen**: a quick menu while playing (picture fit, playback stats, audio/subtitles, pause and skip for videos), opened with Down on a remote or a tap on a touch screen; media keys (a steering wheel's too) pause and change tracks; Back twice to stop, Home keeps playing. For mirroring, the stats include the phone's own report: frames sent and dropped, round trip, packet loss and bandwidth.
- **Private**: choose who can cast (anyone, devices allowed on the TV, a PIN shown on the TV the first time as on an Apple TV, or a password), allow or block each device, refuse or allow a second device; no account, and nothing is collected unless you choose to: problem reports (personal details taken out first) and anonymous usage statistics are both opt-in.
- English and Chinese.

## Requirements

- An Android device on Android 6.0 (API 23) or later: a TV, TV box, car display or tablet
- The sender on the same local network

Tested on TCL (Android 9) and Sony BRAVIA (Android 12) TVs and a BYD car display with iPhones.

Mac screen mirroring works, but music from the Mac's Music app can't be sent to CastBay: it uses a FairPlay type (2) that UxPlay can't handle ([UxPlay#570](https://github.com/FDH2/UxPlay/issues/570)).

AirPlay and DLNA video are fetched by the TV itself (as on an Apple TV), so the TV must be able to reach the video source directly. For YouTube that means `googlevideo.com`; on networks where the phone only reaches it through a proxy or VPN, the TV needs one too.

## Technical Implementation

- **AirPlay Protocol**: UxPlay's `lib/` (RAOP/RTSP/RTP, pairing, FairPlay, AirPlay video with FCUP)
- **JNI Bridge**: Native C code interfaces with Android Java/Kotlin layer
- **Video Decoding**: Android MediaCodec (hardware accelerated)
- **Audio Output**: AudioTrack; AirPlay music is played at each frame's NTP time, mirroring audio as it arrives
- **Video casting**: Media3/ExoPlayer for AirPlay (HLS) and DLNA video and music
- **DLNA**: an in-app UPnP media renderer (SSDP, AVTransport/RenderingControl, GENA events)
- **mDNS Discovery**: Android NsdManager, publishing the TXT records UxPlay builds

## Building from Source

```bash
# Clone, then the pinned dependencies: UxPlay, libplist, ALAC and OpenSSL
git clone git@github.com:weenas/castbay.git
cd castbay
git submodule update --init   # top level only: OpenSSL's own submodules aren't needed

# Build (requires JDK 17-21, Android SDK platform 37, NDK 27.0.12077973, CMake 3.22.1, perl
# and make). The first build also compiles OpenSSL's libcrypto
# for each ABI (tools/build-openssl.sh, about a minute), then reuses it.
./gradlew assembleDebug
```

For an existing clone, initialize the pinned dependencies before building:

```bash
git submodule update --init
```

## Testing without a phone

Debug builds (`com.weenas.castbay.debug`, listed as "CastBay Dev") install beside the release app
and carry a simulated AirPlay sender. It feeds the app through the same callbacks the protocol
library uses, so admission, decoding, timed music playback, the screens, menus and stats all run
for real; only the network, pairing and encryption are skipped (those still need a real iPhone or
Mac). `tools/sim` drives it and the TV's remote over adb:

```bash
tools/sim install              # build, install and open the debug app
tools/sim mirror 30            # 30 s of a test picture: a clock, a frame counter, a moving block
tools/sim music 60 "My Song"   # a generated tune with title, artist, album and cover
tools/sim pause                # pause / resume, as the phone would
tools/sim video                # an HLS video (Apple's sample, with audio tracks and subtitles)
tools/sim stop                 # the sender disconnects
tools/sim crash                # the app crashes (to test error reports)
tools/sim key down right ok    # remote keys: up down left right ok back home menu playpause
tools/sim shot screen.png      # screenshot
tools/sim log                  # CastBay's recent log
```

Use `-s SERIAL` (or `ANDROID_SERIAL`) to pick the TV or an emulator. The debug app doesn't start
at boot, so it only runs while you test.

## Versions and CI

Versions follow [semantic versioning](https://semver.org): the middle number goes up for a release with new features (1.1.0), the last for a release that only fixes things (1.1.1), the first for big changes. `versionName` and `versionCode` (one more each release) in `app/build.gradle.kts` change only when releasing; a debug build's version names the commit it was built from (`1.1.0-dev+6228385`).

CI builds only what a change touches: the app (`Android` workflow) for `app/`, `airplay/`, `third_party/` and the Gradle files, the website (`Website` workflow) for `web/`. The other job shows as skipped.

## Project Structure

```
castbay/
├── app/                    # Android application
│   ├── src/main/
│   │   ├── java/com/weenas/castbay/
│   │   │   ├── MainActivity.kt
│   │   │   ├── service/       # Service lifecycle and bridge
│   │   │   ├── ui/            # Compose UI screens
│   │   │   ├── viewmodel/     # MVVM ViewModels
│   │   │   └── receiver/      # BroadcastReceivers
│   │   ├── cpp/              # C++ native code (CMake)
│   │   └── res/              # Android resources
│   └── build.gradle.kts
├── airplay/                # Android library, JNI bridge, and native protocol build
├── third_party/            # Pinned UxPlay, libplist, ALAC and OpenSSL submodules
├── web/                    # castbay.weenas.com (Astro), built and published by Cloudflare
├── branding/               # Logo and wordmark masters (SVG and PNG)
└── README.md
```

### Website

The website is built with [Astro](https://astro.build) from `web/` (Node 22.12 or later):

- `web/src/pages/`: the pages. The user guide, How it works and the privacy policy are Markdown (`guide.md`, `tech.md`, `privacy.md`, and the same under `zh/`); the home pages are `index.astro`.
- `web/src/layouts/`: the `<head>`, header, navigation and footer every page shares (`Page.astro`), and the layout of the Markdown pages (`Doc.astro`).
- `web/src/i18n.ts`: the words the shared parts use in each language.
- `web/src/content/releases/<en|zh>/<version>.md`: the release notes, shown on the changelog pages, used as the GitHub release notes, and, as plain text, in the app (About → What's New, from the latest ten versions in `latest.json`): keep each bullet one self-contained point. Add both before tagging a release (`vX.Y.Z`); the release workflow refuses a full release without them. Add the same points, shorter (500 characters at most), as `fastlane/metadata/android/<en-US|zh-CN>/changelogs/<versionCode>.txt`, which F-Droid shows. The changelog also lists each APK's SHA-256, fetched from GitHub when the site is built; with a Cloudflare deploy hook URL in the `CLOUDFLARE_DEPLOY_HOOK` secret, the release workflow rebuilds the site so a new release's appears at once. Each release also carries `CastBay-<version>-mapping.txt`, R8's name map: `retrace` (Android SDK command-line tools) turns a release build's obfuscated crash stack trace back into source names with it.
- `web/public/`: styles, images, scripts, `robots.txt` and `sitemap.xml`, published as they are.

```bash
cd web
npm install        # once
npm run dev        # preview at http://localhost:4321, updating as you edit
npm run build      # build into dist/, as Cloudflare does
```

Cloudflare builds and publishes the site from `web/` whenever `main` changes (`web/wrangler.jsonc`); CI checks that it builds. A new page also goes in `public/sitemap.xml`.

`web/worker/index.js` answers `/api/*`: problem reports (R2 bucket `castbay-reports`) and opt-in usage statistics (D1 database `castbay-stats`). For the maintainer, behind Cloudflare Access (an email login):

- **https://castbay.weenas.com/stats**: the usage statistics' totals (data: `/api/stats/summary`). Also listed in the Access app launcher, https://easonxiang.cloudflareaccess.com.
- A problem report by its ID: `cd web && npx wrangler r2 object get castbay-reports/reports/CB-XXXXXX.txt --remote --pipe` (after `npx wrangler login`).
- Any question the page doesn't answer: `npx wrangler d1 execute castbay-stats --remote --command "SELECT …"`; see `docs/design/usage-stats-and-reports.md`.

## App stores

`fastlane/metadata/android/` holds the store listing (title, descriptions, icon, TV screenshots, per-version changelogs) in English and Chinese, which F-Droid reads. Store builds pass `-Pcastbay.selfUpdate=false`: no in-app updater and no update check, since the store updates the app.

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Questions, ideas and device reports: start with the [community welcome discussion](https://github.com/weenas/castbay/discussions/205) or browse [all Discussions](https://github.com/weenas/castbay/discussions); security issues are reported privately ([SECURITY.md](SECURITY.md)). Everyone is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).

## License

This project is licensed under the GNU General Public License v3.0.
See the LICENSE file for details.

## Credits

- [UxPlay](https://github.com/FDH2/UxPlay) - AirPlay protocol library (mirroring, audio, HLS video)
- [RPiPlay](https://github.com/FD-/RPiPlay) - the original core UxPlay's library grew from
- [libplist](https://github.com/libimobiledevice/libplist) - binary plist support
- [Apple ALAC](https://github.com/macosforge/alac) - the ALAC decoder for AirPlay music
- [Media3 ExoPlayer](https://github.com/androidx/media) - video and DLNA playback
- [LRCLIB](https://lrclib.net) - synced lyrics
- Android Open Source Project - Base platform

## Disclaimer

This project is not affiliated with or endorsed by Apple Inc. or Google LLC.
AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc.; Android TV and Google TV are trademarks of Google LLC.
