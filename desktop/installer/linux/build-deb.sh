#!/bin/sh
# Builds castbay-desktop_<version>_<arch>.deb from an already built binary.
# Usage: build-deb.sh BINARY VERSION OUTPUT_DIR
set -eu
bin=$1; version=$2; out=$3
here=$(cd "$(dirname "$0")" && pwd)
icon="$here/../../app/icons/128x128.png"
arch=$(dpkg --print-architecture)
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
chmod 755 "$root"

install -Dm755 "$bin" "$root/usr/bin/castbay-desktop"
install -Dm644 "$here/castbay-desktop.desktop" "$root/usr/share/applications/castbay-desktop.desktop"
install -Dm644 "$icon" "$root/usr/share/icons/hicolor/128x128/apps/castbay-desktop.png"

mkdir -p "$root/DEBIAN"
cat > "$root/DEBIAN/control" <<CONTROL
Package: castbay-desktop
Version: $version
Section: video
Priority: optional
Architecture: $arch
Depends: libwebkit2gtk-4.1-0, libgtk-3-0, libpipewire-0.3-0, xdg-desktop-portal, xdg-utils
Recommends: gnome-shell-extension-appindicator | xfce4-statusnotifier-plugin
Maintainer: CastBay <noreply@weenas.com>
Homepage: https://castbay.weenas.com
Description: Cast your screen to CastBay receivers
 CastBay Desktop sends your screen and its sound to a CastBay receiver on the
 same network. The system asks which screen or window to share.
CONTROL

mkdir -p "$out"
dpkg-deb --root-owner-group --build "$root" "$out/castbay-desktop_${version}_${arch}.deb" >/dev/null
echo "$out/castbay-desktop_${version}_${arch}.deb"
