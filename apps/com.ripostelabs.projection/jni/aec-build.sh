#!/usr/bin/env bash
# Build libaec3_jni.so: WebRTC AEC3 from the pinned webrtc-audio-processing release, linked
# statically with aec_engine.cc and aec_jni.c, for the car (arm64-v8a) and the emulator farm
# (x86_64), into ../jniLibs.
#
#   NDK=/path/to/ndk/27.2.12479018 jni/aec-build.sh     the two Android libraries, then the stamp
#   jni/aec-build.sh --host OUTDIR                        a host libaec3_jni.so, for offline eval
#
# Needs curl, meson, ninja and network (the release tarball and its abseil wrap). Like
# librnnoise_jni.so the output is committed: no CI runner has the NDK, and check-libs.sh holds
# the libraries to this script's pins.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
libs="$here/../jniLibs"
NDK="${NDK:-${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.2.12479018}}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly API=24
readonly LIB=libaec3_jni.so

# webrtc-audio-processing 2.1 (Jan 2025, WebRTC M131 AEC3), the PulseAudio/PipeWire packaging.
readonly VERSION=2.1
readonly URL="https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/webrtc-audio-processing-$VERSION.tar.xz"
readonly SHA256=ae9302824b2038d394f10213cab05312c564a038434269f11dbf68f511f9f9fe

work="${AEC_WORK:-$(mktemp -d)}"
src="$work/webrtc-audio-processing-$VERSION"

# Fetch and verify the release once per work dir.
fetch() {
    [ -d "$src" ] && return 0
    curl -sSfL -o "$work/wap.tar.xz" "$URL"
    echo "$SHA256  $work/wap.tar.xz" | sha256sum -c --quiet
    tar -C "$work" -xf "$work/wap.tar.xz"
}

# Configure and build the static libraries in $1, with an optional cross file $2 and meson
# options after it.
meson_build() {
    local build="$1" cross="${2:-}"
    shift 2 || shift $#
    if [ ! -f "$build/build.ninja" ]; then
        meson setup "$build" "$src" --default-library=static --buildtype=release \
            -Dwrap_mode=forcefallback -Dgnustl=disabled ${cross:+--cross-file "$cross"} "$@" >"$build.log"
    fi
    ninja -C "$build" >>"$build.log"
}

# Link the shim and every static library of build $1 into $2 with compiler $3 (+ its flags).
# The shim builds AEC3 itself (the car tuning), so it sees AEC3's headers with the same
# architecture defines the library was compiled with (ARCH_DEFINES).
link() {
    local build="$1" out="$2"
    shift 2
    "$@" -O2 -fPIC -shared -DNDEBUG -DWEBRTC_POSIX -DWEBRTC_APM_DEBUG_DUMP=0 $ARCH_DEFINES \
        -ffile-prefix-map="$here"=. -I"$src/webrtc" -I"$src/subprojects/abseil-cpp-20240722.0" \
        -I"$JNI_INCLUDE" ${JNI_INCLUDE_OS:+-I"$JNI_INCLUDE_OS"} \
        -x c++ "$here/aec_engine.cc" -x c "$here/aec_jni.c" -x none \
        -Wl,--start-group $(find "$build" -name '*.a' | LC_ALL=C sort) -Wl,--end-group \
        -static-libstdc++ -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--build-id=none \
        -Wl,-z,max-page-size=16384 $EXTRA_LIBS -o "$out"
}

cross_file() {
    local target="$1" family="$2" file="$work/cross-$2.ini"
    cat >"$file" <<EOF
[binaries]
c = '$BIN/clang'
cpp = '$BIN/clang++'
ar = '$BIN/llvm-ar'
strip = '$BIN/llvm-strip'

[built-in options]
c_args = ['--target=$target', '-fPIC', '-ffunction-sections', '-fdata-sections']
cpp_args = ['--target=$target', '-fPIC', '-ffunction-sections', '-fdata-sections']
c_link_args = ['--target=$target']
cpp_link_args = ['--target=$target']

[host_machine]
system = 'android'
cpu_family = '$family'
cpu = '$family'
endian = 'little'
EOF
    echo "$file"
}

mkdir -p "$work"
fetch

if [ "${1:-}" = "--host" ]; then
    out="${2:?--host OUTDIR}"
    mkdir -p "$out"
    meson_build "$work/b-host" ""
    java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    JNI_INCLUDE="$java_home/include" JNI_INCLUDE_OS="$java_home/include/linux" EXTRA_LIBS="-lpthread -lm" \
        ARCH_DEFINES="-DWEBRTC_LINUX -DWEBRTC_ENABLE_AVX2" \
        link "$work/b-host" "$out/$LIB" c++
    echo ">> host: $out/$LIB"
    exit 0
fi

# The NDK's clang finds jni.h in its sysroot.
for abi in arm64-v8a x86_64; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android$API family=aarch64 simd=-Dneon=enabled
            defines="-DWEBRTC_ANDROID -DWEBRTC_LINUX -DWEBRTC_ARCH_ARM64 -DWEBRTC_HAS_NEON" ;;
        x86_64) target=x86_64-linux-android$API family=x86_64 simd=-Dinline-sse=true
            defines="-DWEBRTC_ANDROID -DWEBRTC_LINUX -DWEBRTC_ENABLE_AVX2" ;;
    esac
    # "auto" leaves NEON off even on arm64 (meson's feature is never enabled by itself), and
    # AEC3's filters then take the plain C path.
    meson_build "$work/b-$abi" "$(cross_file "$target" "$family")" "$simd"
    mkdir -p "$libs/$abi"
    JNI_INCLUDE="$here" JNI_INCLUDE_OS="" EXTRA_LIBS="-llog -lm" ARCH_DEFINES="$defines" \
        link "$work/b-$abi" "$libs/$abi/$LIB" "$BIN/clang++" --target="$target" -ffunction-sections -fdata-sections
    "$BIN/llvm-strip" --strip-unneeded "$libs/$abi/$LIB"
    echo ">> $abi: $(stat -c %s "$libs/$abi/$LIB") bytes"
done

"$here/check-libs.sh" --stamp
