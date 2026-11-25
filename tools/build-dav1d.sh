#!/usr/bin/env bash
# Cross-compiles dav1d (the fastest AV1 decoder) as static libraries for the app's JNI wrapper.
# Output: app/src/main/jni/dav1d/<abi>/libdav1d.a and app/src/main/jni/dav1d/include/dav1d/*.h
# Needs: an Android NDK ($ANDROID_NDK_HOME), meson, ninja, nasm (x86_64 assembly).
set -euo pipefail
VERSION=${DAV1D_VERSION:-1.5.5}
API=23
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/app/src/main/jni/dav1d
WORK=${WORK:-$(mktemp -d)}
NDK=${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin

cd "$WORK"
curl -fsSL "https://code.videolan.org/videolan/dav1d/-/archive/$VERSION/dav1d-$VERSION.tar.gz" | tar xz
SRC=$WORK/dav1d-$VERSION

build() {
    local abi=$1 triple=$2 family=$3
    cat > "cross-$abi.txt" <<CROSS
[binaries]
c = '$TC/${triple}${API}-clang'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
nasm = 'nasm'

[built-in options]
c_args = ['-O3', '-fPIC', '-fvisibility=hidden']

[host_machine]
system = 'android'
cpu_family = '$family'
cpu = '$family'
endian = 'little'
CROSS
    meson setup "build-$abi" "$SRC" --cross-file "cross-$abi.txt" \
        --buildtype=release --default-library=static \
        -Denable_tools=false -Denable_tests=false -Denable_examples=false \
        -Dbitdepths=8,16 -Denable_asm=true -Dlogging=false
    ninja -C "build-$abi"
    mkdir -p "$OUT/$abi"
    cp "build-$abi/src/libdav1d.a" "$OUT/$abi/libdav1d.a"
    "$TC/llvm-strip" --strip-debug "$OUT/$abi/libdav1d.a"
}

build arm64-v8a aarch64-linux-android aarch64
build x86_64 x86_64-linux-android x86_64

mkdir -p "$OUT/include/dav1d"
cp "$SRC"/include/dav1d/*.h "$OUT/include/dav1d/"
# version.h is generated at configure time; its place in the build tree changed between releases.
gen=$(find "$WORK/build-arm64-v8a" -name version.h -path "*dav1d*" | head -n1)
[ -n "$gen" ] || gen=$(find "$WORK/build-arm64-v8a" -name version.h | head -n1)
[ -n "$gen" ] && cp "$gen" "$OUT/include/dav1d/version.h"
ls "$OUT/include/dav1d/"
cp "$SRC/COPYING" "$OUT/COPYING"
echo "dav1d $VERSION" > "$OUT/VERSION"
ls -la "$OUT" "$OUT"/*/
