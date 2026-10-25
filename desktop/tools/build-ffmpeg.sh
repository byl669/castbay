#!/usr/bin/env bash
# Builds FFmpeg's libavcodec, libavutil and libswscale as static libraries for Windows (MSVC),
# from the pinned submodules under desktop/third_party, into desktop/build/ffmpeg/{include,lib}.
# core/encode links them. Only what CastBay Desktop needs is enabled: the AMD (AMF) and NVIDIA
# (NVENC) hardware H.264 encoders and x264 as the software one. The result is GPL-licensed
# (x264), like the rest of CastBay; the sources that built it are these submodules.
#
#   tools/build-ffmpeg.cmd            (runs this in MSYS2 with the Visual Studio tools on the PATH)
#
# An existing build from the same submodule commits is kept.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="$PWD/build/ffmpeg"
THIRD=third_party
STAMP="$OUT/.built-from"

# A source tarball without git metadata carries the commit in third_party/<name>/.commit.
commit() { git -C "$THIRD/$1" rev-parse HEAD 2>/dev/null || cat "$THIRD/$1/.commit"; }
WANT="$(commit FFmpeg) $(commit x264) $(commit nv-codec-headers) $(commit AMF)"
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$WANT" && -f "$OUT/lib/avcodec.lib" ]]; then
  echo "FFmpeg is already built from these commits: $OUT"
  exit 0
fi
for dir in FFmpeg x264 nv-codec-headers AMF; do
  [[ -e "$THIRD/$dir/.git" || -e "$THIRD/$dir/.commit" ]] || { echo "third_party/$dir is missing: run git submodule update --init" >&2; exit 1; }
done
command -v cl >/dev/null || { echo "cl.exe is not on the PATH: run through tools/build-ffmpeg.cmd" >&2; exit 1; }
# MSYS2's link.exe (a coreutils tool) hides the compiler's linker.
[[ -e /usr/bin/link.exe ]] && mv /usr/bin/link.exe /usr/bin/link.exe.msys2

rm -rf "$OUT" build/ffmpeg-work
mkdir -p "$OUT" build/ffmpeg-work
WORK="$PWD/build/ffmpeg-work"
export PKG_CONFIG_PATH="$OUT/lib/pkgconfig"
JOBS="$(nproc)"

echo "== nv-codec-headers"
make -C "$THIRD/nv-codec-headers" install PREFIX="$OUT"

echo "== AMF headers"
mkdir -p "$OUT/include/AMF"
cp -r "$THIRD/AMF/amf/public/include/." "$OUT/include/AMF/"

echo "== x264"
mkdir -p "$WORK/x264" && cd "$WORK/x264"
CC=cl "$OLDPWD/$THIRD/x264/configure" --prefix="$OUT" --enable-static --disable-cli --disable-opencl \
  --disable-avs --disable-swscale --disable-lavf --disable-ffms --disable-gpac --disable-lsmash
make -j"$JOBS" install
# FFmpeg looks for x264.lib; x264 installs libx264.lib.
[[ -f "$OUT/lib/libx264.lib" ]] && cp "$OUT/lib/libx264.lib" "$OUT/lib/x264.lib"
cd "$OLDPWD"

echo "== FFmpeg"
mkdir -p "$WORK/ffmpeg" && cd "$WORK/ffmpeg"
"$OLDPWD/$THIRD/FFmpeg/configure" --toolchain=msvc --arch=x86_64 --prefix="$OUT" \
  --enable-gpl --enable-version3 --enable-static --disable-shared \
  --disable-programs --disable-doc --disable-debug --disable-autodetect \
  --disable-everything --disable-avformat --disable-avdevice --disable-avfilter --disable-swresample \
  --enable-avcodec --enable-avutil --enable-swscale \
  --enable-libx264 --enable-ffnvcodec --enable-nvenc --enable-amf --enable-d3d11va \
  --enable-encoder=libx264,h264_amf,h264_nvenc --enable-parser=h264 \
  --extra-cflags="-I$OUT/include" --extra-ldflags="-LIBPATH:$OUT/lib"
make -j"$JOBS"
make install
# The MSVC toolchain installs libavcodec.a etc.; the linker looks for avcodec.lib.
for f in "$OUT"/lib/lib{avcodec,avutil,swscale}.a; do cp "$f" "$(echo "$f" | sed 's#/lib/lib\(.*\)\.a$#/lib/\1.lib#')"; done
cd "$OLDPWD"

echo "$WANT" > "$STAMP"
echo "FFmpeg built into $OUT"
ls "$OUT/lib" | head -20
