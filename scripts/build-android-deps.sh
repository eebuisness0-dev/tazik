#!/usr/bin/env bash
#
# Build minimal native deps for Inferno QEMU Android cross build:
#   zlib, pixman, libfdt (all optional at runtime but usually required by softmmu)
#
# Output: android/deps/{include,lib}
#
# Uses the same NDK detection as build-inferno-android.sh.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(dirname "$SELF_DIR")"
OUT="$PROJ_DIR/android/deps"
BUILD="$PROJ_DIR/external/deps-build"
mkdir -p "$OUT" "$BUILD"

NDK="${ANDROID_NDK_ROOT:-${NDK:-/opt/android-ndk}}"
SDK_VER="${SDK_VER:-26}"
HOST_TAG="$(case "$(uname)" in Darwin) echo darwin-x86_64 ;; *) echo linux-x86_64 ;; esac)"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/aarch64-linux-android${SDK_VER}-clang"
export CC
export PKG_CONFIG=disabled

fetch() {
    local url="$1" dir="$2"
    if [[ -d "$BUILD/$dir" ]]; then return; fi
    local tarball="$BUILD/$(basename "$url")"
    mkdir -p "$BUILD"
    curl -L -o "$tarball" "$url"
    tar -xf "$tarball" -C "$BUILD"
}

# zlib
ZLIB_VER=1.3.1
if [[ ! -f "$OUT/lib/libz.so" ]]; then
    fetch "https://zlib.net/zlib-$ZLIB_VER.tar.gz" "zlib-$ZLIB_VER"
    ( cd "$BUILD/zlib-$ZLIB_VER" && \
      CHOST=aarch64-linux-android ./configure --prefix="$OUT" &&
      make -j"$(nproc 2>/dev/null || echo 4)" shared &&
      make install )
fi

# pixman (optional, QEMU uses it for qemu-pixman)
PIXMAN_VER=0.46.4
if [[ ! -f "$OUT/lib/libpixman-1.so" ]]; then
    fetch "https://www.cairographics.org/releases/pixman-$PIXMAN_VER.tar.gz" "pixman-$PIXMAN_VER"
    ( cd "$BUILD/pixman-$PIXMAN_VER" && \
      ./configure --host=aarch64-linux-android \
          --prefix="$OUT" --disable-static --enable-shared &&
      make -j"$(nproc 2>/dev/null || echo 4)" &&
      make install )
fi

# libfdt (dtc)
DTC_VER=1.7.2
if [[ ! -f "$OUT/lib/libfdt.so" ]]; then
    fetch "https://ftp.debian.org/debian/pool/main/d/device-tree-compiler/device-tree-compiler_$DTC_VER.orig.tar.xz" "dtc-$DTC_VER" || \
    fetch "https://git.kernel.org/pub/scm/utils/dtc/dtc.git/snapshot/dtc-$DTC_VER.tar.gz" "dtc-$DTC_VER"
    ( cd "$BUILD/dtc-$DTC_VER" && \
      make -j"$(nproc 2>/dev/null || echo 4)" -C libfdt \
          CC="$CC" CFLAGS="-fPIC -O2" SHAREDLIB_EXT=so && \
      cp libfdt/libfdt.so* "$OUT/lib/" && cp libfdt/*.h "$OUT/include/" 2>/dev/null || true )
fi

echo "deps ready at $OUT"
