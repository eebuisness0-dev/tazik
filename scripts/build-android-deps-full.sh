#!/usr/bin/env bash
#
# Build ALL native deps for Inferno QEMU (MakrSas fork) Android cross build:
#   zlib, pixman, libfdt, lzfse, lz4, gmp, nettle(+hogweed), libtasn1, glib
#
# Output: android/deps/{include,lib} + android/deps/lib/pkgconfig
#
# Cross-file for the final QEMU meson build is emitted to android/deps/qemu-cross.txt
# and glib cross file to android/deps/glib-cross.txt.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(dirname "$SELF_DIR")"
OUT="$PROJ_DIR/android/deps"
BUILD="$PROJ_DIR/external/deps-build"
mkdir -p "$OUT" "$BUILD"

NDK="${ANDROID_NDK_ROOT:-$(ls -d /home/kotu/.tazik-build/android-sdk/ndk/* 2>/dev/null | head -1)}"
[[ -d "$NDK" ]] || { echo "NDK not found"; exit 1; }
SDK_VER="${SDK_VER:-26}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/aarch64-linux-android${SDK_VER}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${SDK_VER}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"
export CC CXX AR RANLIB
export PKG_CONFIG=disabled
JOBS="$(nproc 2>/dev/null || echo 4)"

fetch() {
    local url="$1" dir="$2"
    if [[ -d "$BUILD/$dir" ]]; then return; fi
    local tarball="$BUILD/$(basename "$url")"
    mkdir -p "$BUILD"
    echo "fetch $url"
    curl -L --max-time 300 -o "$tarball" "$url"
    tar -xf "$tarball" -C "$BUILD"
}

# ---------- zlib ----------
ZLIB_VER=1.3.1
if [[ ! -f "$OUT/lib/libz.so" ]]; then
    fetch "https://github.com/madler/zlib/releases/download/v$ZLIB_VER/zlib-$ZLIB_VER.tar.gz" "zlib-$ZLIB_VER" || \
    fetch "https://zlib.net/zlib-$ZLIB_VER.tar.gz" "zlib-$ZLIB_VER"
    ( cd "$BUILD/zlib-$ZLIB_VER" && \
      SRCS="adler32.c compress.c crc32.c deflate.c gzclose.c gzlib.c gzread.c gzwrite.c infback.c inffast.c inflate.c inftrees.c trees.c uncompr.c zutil.c" && \
      $CC -O2 -fPIC -DHAVE_SYS_TYPES_H -DHAVE_STDINT_H -DHAVE_UNISTD_H -c $SRCS -I. && \
      $CC -fPIC -shared -Wl,-soname,libz.so.1 -o "$OUT/lib/libz.so.1.$ZLIB_VER" *.o && \
      ln -sf "libz.so.1.$ZLIB_VER" "$OUT/lib/libz.so.1" && \
      ln -sf libz.so.1 "$OUT/lib/libz.so" && \
      cp zconf.h zlib.h "$OUT/include/" )
fi

# ---------- generic cross file ----------
mkdir -p "$OUT"
cat > "$BUILD/ndk-cross.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
cmake = '/usr/bin/cmake'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true
c_args = ['-I$OUT/include']
cpp_args = ['-I$OUT/include']
c_link_args = ['-L$OUT/lib']
cpp_link_args = ['-L$OUT/lib']
EOF

# ---------- pixman ----------
PIXMAN_VER=0.46.4
if [[ ! -f "$OUT/lib/libpixman-1.so" ]]; then
    fetch "https://www.cairographics.org/releases/pixman-$PIXMAN_VER.tar.gz" "pixman-$PIXMAN_VER"
    ( cd "$BUILD/pixman-$PIXMAN_VER" && \
      PKG_CONFIG_LIBDIR="$OUT/lib/pkgconfig" \
      meson setup build --cross-file "$BUILD/ndk-cross.txt" \
          -Dprefix="$OUT" -Ddefault_library=shared \
          -Dgtk=disabled -Dlibpng=disabled -Dtests=disabled \
          -Ddemos=disabled -Dloongson-mmi=disabled \
          -Dopenmp=disabled 2>&1 | tail -3 &&
      ninja -C build -j"$JOBS" 2>&1 | tail -2 &&
      meson install -C build --destdir "$BUILD/pixman-stage" 2>&1 | tail -2 &&
      cp -a "$BUILD/pixman-stage$OUT/." "$OUT/" ) || \
      cp -a "$BUILD/pixman-stage/." "$OUT/" 2>/dev/null || true
fi

# ---------- libfdt ----------
DTC_VER=1.7.2
if [[ ! -f "$OUT/lib/libfdt.so" ]]; then
    fetch "https://git.kernel.org/pub/scm/utils/dtc/dtc.git/snapshot/dtc-$DTC_VER.tar.gz" "dtc-$DTC_VER" || \
    fetch "https://ftp.debian.org/debian/pool/main/d/device-tree-compiler/device-tree-compiler_$DTC_VER.orig.tar.xz" "dtc-$DTC_VER"
    ( cd "$BUILD/dtc-$DTC_VER" && \
      make -j"$JOBS" -C libfdt CC="$CC" AR="$AR" RANLIB="$RANLIB" DTC_SHARED=y \
          EXTRA_CFLAGS="-fPIC" NO_PYTHON=1 >/dev/null 2>&1 || \
      make -j"$JOBS" -C libfdt CC="$CC" AR="$AR" RANLIB="$RANLIB" ) || true
    ( cd "$BUILD/dtc-$DTC_VER/libfdt" && \
      $CC -fPIC -shared -Wl,-soname,libfdt.so.1 -o "$OUT/lib/libfdt.so" *.o -lz &&
      cp *.o "$BUILD/fdt-objs" 2>/dev/null || true &&
      $CC -c -I. *.c 2>/dev/null || true )
    if [[ ! -f "$OUT/lib/libfdt.so" ]]; then
        ( cd "$BUILD/dtc-$DTC_VER/libfdt" && \
          rm -f *.o; $CC -fPIC -I. -c *.c &&
          $CC -fPIC -shared -Wl,-soname,libfdt.so.1 -o "$OUT/lib/libfdt.so" *.o )
    fi
    cp -r "$BUILD/dtc-$DTC_VER/libfdt"/*.h "$OUT/include/" 2>/dev/null || \
      cp -r "$BUILD/dtc-$DTC_VER/libfdt"/*.h "$OUT/include/"
fi

# ---------- lzfse ----------
if [[ ! -f "$OUT/lib/liblzfse.a" ]]; then
    fetch "https://github.com/lzfse/lzfse/archive/refs/heads/master.tar.gz" "lzfse-src"
    ( cd "$BUILD/lzfse-master/src" && \
      $CC -O2 -fPIC -c *.c -I. &&
      ar rcs "$OUT/lib/liblzfse.a" *.o )
    cp "$BUILD/lzfse-master/src/lzfse.h" "$OUT/include/"
fi

# ---------- lz4 ----------
LZ4_VER=1.10.0
if [[ ! -f "$OUT/lib/liblz4.a" ]]; then
    fetch "https://github.com/lz4/lz4/archive/refs/tags/v$LZ4_VER.tar.gz" "lz4-$LZ4_VER"
    ( cd "$BUILD/lz4-$LZ4_VER/lib" && \
      $CC -O2 -fPIC -c lz4.c lz4hc.c lz4frame.c xxhash.c -I. &&
      ar rcs "$OUT/lib/liblz4.a" lz4.o lz4hc.o lz4frame.o xxhash.o )
    cp "$BUILD/lz4-$LZ4_VER"/lib/{lz4.h,lz4hc.h,lz4frame.h} "$OUT/include/"
fi

# ---------- gmp ----------
GMP_VER=6.3.0
if [[ ! -f "$OUT/lib/libgmp.a" ]]; then
    fetch "https://ftpmirror.gnu.org/gmp/gmp-$GMP_VER.tar.xz" "gmp-$GMP_VER" || \
    fetch "https://gmplib.org/download/gmp/gmp-$GMP_VER.tar.xz" "gmp-$GMP_VER"
    ( cd "$BUILD/gmp-$GMP_VER" && \
      ./configure --host=aarch64-linux-android --prefix="$OUT" \
          --enable-static --disable-shared --disable-assembly &&
      make -j"$JOBS" && make install )
fi

# ---------- nettle ----------
NETTLE_VER=3.10
if [[ ! -f "$OUT/lib/libnettle.a" ]]; then
    fetch "https://ftp.gnu.org/gnu/nettle/nettle-$NETTLE_VER.tar.gz" "nettle-$NETTLE_VER"
    ( cd "$BUILD/nettle-$NETTLE_VER" && \
      ./configure --host=aarch64-linux-android --prefix="$OUT" --libdir="$OUT/lib" \
          --enable-static --disable-shared --disable-documentation \
          --disable-openssl \
          CPPFLAGS="-I$OUT/include" LDFLAGS="-L$OUT/lib" &&
      make -j"$JOBS" && make install )
fi

# ---------- libtasn1 ----------
TASN1_VER=4.20.0
if [[ ! -f "$OUT/lib/libtasn1.a" ]]; then
    fetch "https://ftp.gnu.org/gnu/libtasn1/libtasn1-$TASN1_VER.tar.gz" "libtasn1-$TASN1_VER"
    ( cd "$BUILD/libtasn1-$TASN1_VER" && \
      ./configure --host=aarch64-linux-android --prefix="$OUT" --libdir="$OUT/lib" \
          --enable-static --disable-shared --disable-doc \
          CPPFLAGS="-I$OUT/include" LDFLAGS="-L$OUT/lib" &&
      make -j"$JOBS" && make install )
fi

# ---------- glib ----------
GLIB_VER=2.82.5
if [[ ! -f "$OUT/lib/libglib-2.0.so" ]]; then
    fetch "https://download.gnome.org/sources/glib/2.82/glib-$GLIB_VER.tar.xz" "glib-$GLIB_VER"
    cat > "$BUILD/glib-cross.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
cmake = '/usr/bin/cmake'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true
EOF
    ( cd "$BUILD/glib-$GLIB_VER" && \
      PKG_CONFIG_LIBDIR="$OUT/lib/pkgconfig" \
      meson setup build --cross-file "$BUILD/glib-cross.txt" \
          -Dprefix="$OUT" \
          -Dtests=false -Dintrospection=disabled -Ddtrace=disabled \
          -Dman=disabled -Ddocumentation=false -Dselinux=disabled \
          -Dxattr=false -Dlibmount=disabled -Dnls=disabled \
          -Dforce_posix_threads=true \
          -Dglib_debug=false -Dglib_assert=false -Dglib_checks=false \
          -Dsysprof=disabled 2>&1 | tail -3 &&
      ninja -C build -j"$JOBS" &&
      PKG_CONFIG_LIBDIR="$OUT/lib/pkgconfig" meson install -C build --destdir "$BUILD/glib-stage" 2>&1 | tail -2 ) || true
    # glib installs with destdir; relocate
    if [[ -d "$BUILD/glib-stage" ]]; then
        cp -a "$BUILD/glib-stage/." "$OUT/" 2>/dev/null || \
        cp -a "$BUILD/glib-stage$OUT/." "$OUT/" 2>/dev/null || true
    fi
    if [[ ! -f "$OUT/lib/libglib-2.0.so" && -d "$BUILD/glib-stage" ]]; then
        # destdir may embed prefix path
        STAGE=$(find "$BUILD/glib-stage" -name "libglib-2.0.so" | head -1 | xargs dirname 2>/dev/null | xargs dirname 2>/dev/null)
        if [[ -n "$STAGE" ]]; then
            cp -a "$STAGE/." "$OUT/"
            PC=$(find "$BUILD/glib-stage" -name "glib-2.0.pc" | head -1 | xargs dirname)
            mkdir -p "$OUT/lib/pkgconfig" && cp "$PC"/*.pc "$OUT/lib/pkgconfig/"
            INC=$(find "$BUILD/glib-stage" -type d -name "glib-2.0" | head -1)
            [[ -n "$INC" ]] && cp -a "$(dirname "$INC")/." "$OUT/include/" 2>/dev/null || true
        fi
    fi
fi

# fix pkgconfig prefixes
for pc in "$OUT/lib/pkgconfig"/*.pc; do
    [[ -f "$pc" ]] || continue
    sed -i "s|^prefix=.*|prefix=$OUT|; s|^libdir=.*|libdir=$OUT/lib|; s|^includedir=.*|includedir=$OUT/include|" "$pc"
done

# ---------- QEMU cross file ----------
cat > "$OUT/qemu-cross.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
cmake = '/usr/bin/cmake'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = ['-I$OUT/include', '-D_FILE_OFFSET_BITS=64', '-O2', '-fPIC']
cpp_args = ['-I$OUT/include', '-D_FILE_OFFSET_BITS=64', '-O2', '-fPIC']
c_link_args = ['-L$OUT/lib', '-Wl,-z,max-page-size=16384', '-liconv']
cpp_link_args = ['-L$OUT/lib', '-Wl,-z,max-page-size=16384', '-liconv']

[properties]
needs_exe_wrapper = true
EOF

echo "=== ALL DEPS DONE ==="
ls "$OUT/lib" | head -20
