# CastBay Desktop: known limitations

What does not work, was not tested, or cannot be done, as of 0.1.x. Kept honest on purpose:
update it when something changes.

## Not possible, or not planned

- **Protected video.** Windows Graphics Capture returns black for windows showing DRM content
  (some streaming sites and players). The cast shows black there. Nothing to do on our side.
- **The lock screen.** Windows gives nothing to capture while the PC is locked; the picture stays
  as it was and the window says so. (While casting, the PC is kept from sleeping or switching its
  screen off.)
- **Other receivers.** Only CastBay receivers (the Android app). No AirPlay sender (it needs the
  sender-side FairPlay) and no DLNA (its delay is too high); a Mac already mirrors to CastBay
  with AirPlay.
- **Several receivers at once.** One cast to one receiver at a time.
- **Windows 10 older than 1903 / 32-bit Windows.** Not built for.

## Built but not tested

- **NVIDIA on Windows.** The NVENC settings were checked with the encoder alone on Linux (RTX
  4070): every picture came out at once and key frames can be forced. A Windows PC with an
  NVIDIA GPU has not been tried; automatic choice falls back to x264 if NVENC does not open.
- **Intel Quick Sync.** Not built into the in-process encoder; x264 (CPU) is used instead.
- **4K screens.** Scaling to 1080p is covered by unit tests and tried with a 1920x1200 screen
  (sent as 1728x1080), but no 4K screen was available.
- **Real congestion.** The bitrate controller follows drops and is unit tested; its response was
  tried with a simulated burst of drops, not on a bad network, and its thresholds are reasoned,
  not tuned.
- **The Android 6 receiver on real hardware.** The receiver side was checked on an Android 6
  emulator (software decoding is slow there, around 84 ms), not on the Xiaomi TV.
- **Sound by ear on other receivers, and lip-sync.** Sound was heard on the Sony and judged
  fine by the developer; the delay between picture and sound was not measured, and there is no
  correction (see docs/protocol.md).
- **The locked-screen note.** Written against the documented behaviour of the Windows API; not
  exercised because it needs the test PC locked.
- **Downloading and installing from a browser.** CI installs, upgrades and uninstalls the MSIs and
  the Chinese wizard was walked through on a real PC, but the SmartScreen warning of a browser
  download of an unsigned file was never seen (files were copied over the network).

## Linux

- **Tested on one setup only**: Ubuntu 26.04, GNOME on Wayland, an NVIDIA GPU (NVENC and x264), the
  receiver on a Sony TV. Other distributions, KDE, Sway and X11 sessions were not tried.
- **No screen or window list**: Wayland gives applications no list of windows, so the system's own
  dialog chooses what to share (the choice can be remembered).
- **No AMD or Intel hardware encoder** (VAAPI is not built in); the CPU encodes with x264.
- **Tray**: GNOME needs the AppIndicator extension to show it; without one the window of a running
  cast is brought back by starting CastBay Desktop again from the applications menu.
- **A stale "remember" token** makes the portal wait without showing a dialog; deleting
  `~/.local/share/castbay-desktop/screencast-token` fixes it (see the user guide).
- **A static screen** gets no pictures from the desktop; the capture hands on the last one twice a
  second so the stream stays alive.
- **No package signing or repository**: the `.deb` is a plain download, not an apt source.

## Done differently from what one might expect

- **MSI, not NSIS.** Windows Defender quarantined the NSIS uninstaller of the unsigned build
  (`Trojan:Win32/Bearfoos.A!ml`); an MSI has no program of its own to flag.
- **Two installers, not one.** The language of an MSI's setup screens is fixed when it is built.
  One `setup.exe` that picks the language and also installs WebView2 is for after code signing.
- **No automatic update.** The window tells you a newer release exists and opens its page.
- **Unsigned.** SmartScreen warns until the signature (SignPath Foundation, free for open source)
  is in place and the files build a reputation.
