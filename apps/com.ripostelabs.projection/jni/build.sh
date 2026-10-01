#!/usr/bin/env bash
# Build librnnoise_jni.so for the car (arm64-v8a) and the emulator farm (x86_64) with the NDK,
# into ../jniLibs, which template/build.sh packs into the APK.
#
# The .so files are committed: the CI runners and the gradle-free build have no NDK. What keeps
# them honest is jniLibs/SOURCES.sha256, the hash of every input below; check-libs.sh (in CI)
# fails when a source changed and the libraries were not rebuilt. Built with NDK r27c:
#   NDK=/path/to/ndk/27.2.12479018 jni/build.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
libs="$here/../jniLibs"
NDK="${NDK:-${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.2.12479018}}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly API=24
readonly LIB=librnnoise_jni.so

# shellcheck source=sources.sh
. "$here/sources.sh"

# 16 KiB pages: Android 15+ devices refuse a 4 KiB-aligned .so; it costs nothing on the car.
LDFLAGS="-shared -Wl,--gc-sections -Wl,--build-id=none -Wl,-z,max-page-size=16384 -lm"

for abi in arm64-v8a x86_64; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android$API ;;
        x86_64) target=x86_64-linux-android$API ;;
    esac
    mkdir -p "$libs/$abi"
    "$BIN/clang" --target="$target" -O2 -fPIC -ffunction-sections -fdata-sections \
        -ffile-prefix-map="$here"=. $NS_CFLAGS -Wa,-I"$here/rnnoise/weights" -I"$here" \
        $(ns_sources "$here") "$here/rnnoise_jni.c" $LDFLAGS -o "$libs/$abi/$LIB"
    "$BIN/llvm-strip" --strip-unneeded "$libs/$abi/$LIB"
    echo ">> $abi: $(stat -c %s "$libs/$abi/$LIB") bytes"
done

"$here/check-libs.sh" --stamp
