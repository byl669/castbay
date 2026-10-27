#!/bin/sh
# Start-up smoke test on a running emulator or device: install the debug APK, then start the app
# several times, each time from a stopped app, and expect its local network discovery to come up
# (and no crash) within 60 s. Catches a receiver that stays on "Starting..." (#239) or fails to
# start (#214). Usage: tools/ci/smoke.sh path/to/app-debug.apk
set -eu
APK=${1:?usage: smoke.sh app-debug.apk}
PKG=com.weenas.castbay.debug
ROUNDS=${ROUNDS:-5}

adb install -r "$APK"
round=1
while [ "$round" -le "$ROUNDS" ]; do
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start -n "$PKG/com.weenas.castbay.MainActivity" > /dev/null
  ok=0
  i=0
  while [ "$i" -lt 60 ]; do
    if adb logcat -d | grep -q "AirPlay discovery records are visible"; then ok=1; break; fi
    sleep 1
    i=$((i + 1))
  done
  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "round $round: the app crashed"
    adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -60
    exit 1
  fi
  if [ "$ok" != 1 ]; then
    echo "round $round: local network discovery did not come up in 60 s"
    adb logcat -d | grep -E "CastBay|AirPlay|Nsd|Mdns" | tail -60
    exit 1
  fi
  echo "round $round: discovery up after ${i} s"
  round=$((round + 1))
done
echo "smoke test passed ($ROUNDS starts)"
