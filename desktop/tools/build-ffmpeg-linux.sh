#!/usr/bin/env bash
# Builds FFmpeg's libavcodec, libavutil and libswscale as static libraries for Linux, from the
# pinned submodules under desktop/third_party, into desktop/build/ffmpeg/{include,lib}: the Linux
# counterpart of build-ffmpeg.sh. Enabled: x264 (CPU), NVIDIA's NVENC (the driver's library is
# loaded when the encoder opens, so nothing NVIDIA ships is linked) and the AAC encoder.
# The result is GPL-licensed (x264), like the rest of CastBay.
#
#   tools/build-ffmpeg-linux.sh        (needs gcc, make, nasm, pkg-config)
#
# An existing build from the same submodule commits is kept.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="$PWD/build/ffmpeg"
THIRD="$PWD/third_party"
WORK="$PWD/build/ffmpeg-work"
STAMP="$OUT/.built-from"

commit() { git -C "$THIRD/$1" rev-parse HEAD 2>/dev/null || cat "$THIRD/$1/.commit"; }
WANT="linux $(commit FFmpeg) $(commit x264) $(commit nv-codec-headers)"
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$WANT" && -f "$OUT/lib/libavcodec.a" ]]; then
  echo "FFmpeg is already built from these commits: $OUT"
  exit 0
fi
for dir in FFmpeg x264 nv-codec-headers; do
  [[ -e "$THIRD/$dir/.git" || -e "$THIRD/$dir/.commit" ]] || { echo "third_party/$dir is missing: run git submodule update --init" >&2; exit 1; }
done
for tool in gcc make nasm pkg-config; do
  command -v "$tool" >/dev/null || { echo "$tool is needed" >&2; exit 1; }
done

rm -rf "$OUT" "$WORK"
mkdir -p "$OUT" "$WORK"
export PKG_CONFIG_PATH="$OUT/lib/pkgconfig"
JOBS="$(nproc)"

echo "== nv-codec-headers"
make -C "$THIRD/nv-codec-headers" install PREFIX="$OUT"

echo "== x264"
mkdir -p "$WORK/x264" && cd "$WORK/x264"
"$THIRD/x264/configure" --prefix="$OUT" --enable-static --enable-pic --disable-cli --disable-opencl \
  --disable-avs --disable-swscale --disable-lavf --disable-ffms --disable-gpac --disable-lsmash
make -j"$JOBS" install

echo "== FFmpeg"
mkdir -p "$WORK/ffmpeg" && cd "$WORK/ffmpeg"
"$THIRD/FFmpeg/configure" --prefix="$OUT" --pkg-config-flags=--static \
  --enable-gpl --enable-version3 --enable-static --disable-shared --enable-pic \
  --disable-programs --disable-doc --disable-debug --disable-autodetect \
  --disable-everything --disable-avformat --disable-avdevice --disable-avfilter --disable-swresample \
  --enable-avcodec --enable-avutil --enable-swscale \
  --enable-libx264 --enable-ffnvcodec --enable-nvenc \
  --enable-encoder=libx264,h264_nvenc,aac --enable-parser=h264 \
  --extra-cflags="-I$OUT/include" --extra-ldflags="-L$OUT/lib"
make -j"$JOBS"
make install

echo "$WANT" > "$STAMP"
echo "FFmpeg built into $OUT"
ls "$OUT/lib" | head -20
