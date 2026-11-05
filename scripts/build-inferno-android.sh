#!/usr/bin/env bash
#
# Build Inferno QEMU (MakrSas fork, branch ios) as an Android shared library
# and stage artifacts for the Tazik app:
#
#   android/libqemu-aarch64-softmmu.so      -> drop into app/src/main/jniLibs/arm64-v8a/
#   android/jniLibs/*.so                    -> shared dep libs (glib/pixman/zlib/fdt), if used
#
# Prereqs: NDK (ANDROID_NDK_ROOT), meson>=1.5, ninja, pkg-config, cmake,
# and native deps already built by scripts/build-android-deps-full.sh
# (android/deps/{lib,include} + android/deps/qemu-cross.txt).
#
# Usage: scripts/build-inferno-android.sh [--src DIR] [--build-only]
#
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(dirname "$SELF_DIR")"
OUT_DIR="$PROJ_DIR/android"
DEPS_DIR="$OUT_DIR/deps"

SRC_DIR="/home/kotu/opencode/inferno-makrsas"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --src) SRC_DIR="$2"; shift 2 ;;
        --build-only) BUILD_ONLY=1; shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

NDK="${ANDROID_NDK_ROOT:-$(ls -d /home/kotu/.tazik-build/android-sdk/ndk/* 2>/dev/null | head -1)}"
[[ -d "$NDK" ]] || { echo "NDK not found" >&2; exit 1; }
[[ -f "$DEPS_DIR/qemu-cross.txt" ]] || { echo "deps not built: run scripts/build-android-deps-full.sh first" >&2; exit 1; }

cd "$SRC_DIR"

# ---------- configure ----------
if [[ ! -f build-android/build.ninja ]]; then
    PKG_CONFIG_LIBDIR="$DEPS_DIR/lib/pkgconfig" \
    meson setup build-android \
        --cross-file "$DEPS_DIR/qemu-cross.txt" \
        -Dshared_lib=true \
        -Dsdl=disabled -Dgtk=disabled -Dvnc=enabled \
        -Db_staticpic=true
fi

# ---------- build ----------
ninja -C build-android -j"$(nproc)"

LIB="$(find build-android -maxdepth 2 -name 'libqemu-aarch64-softmmu.so' | head -1)"
[[ -n "$LIB" ]] || { echo "libqemu-aarch64-softmmu.so not found after build" >&2; exit 1; }
cp -v "$LIB" "$OUT_DIR/libqemu-aarch64-softmmu.so"

# ---------- stage shared dep libs (if the lib links against them) ----------
STAGE="$OUT_DIR/jniLibs/arm64-v8a"
mkdir -p "$STAGE"
for soname in $(objdump -p "$OUT_DIR/libqemu-aarch64-softmmu.so" 2>/dev/null | awk '/NEEDED/ {print $2}'); do
    case "$soname" in
        libc.so|libm.so|libdl.so|libc++_shared.so|liblog.so|libstdc++.so) continue ;;
    esac
    if [[ -f "$DEPS_DIR/lib/$soname" ]]; then
        cp -P "$DEPS_DIR/lib/$soname" "$STAGE/"
    else
        echo "WARNING: DT_NEEDED $soname not present in deps/lib (must be bundled or static)"
    fi
done

"${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$OUT_DIR/libqemu-aarch64-softmmu.so"

cat <<'DONE'

Staged:
  android/libqemu-aarch64-softmmu.so
  android/jniLibs/arm64-v8a/*.so   (shared deps, bundle via jniLibs)

Install into the app:
  cp android/libqemu-aarch64-softmmu.so app/src/main/jniLibs/arm64-v8a/
  cp android/jniLibs/arm64-v8a/*.so   app/src/main/jniLibs/arm64-v8a/   # if any
DONE
