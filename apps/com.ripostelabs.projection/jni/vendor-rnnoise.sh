#!/usr/bin/env bash
# Re-vendor RNNoise into jni/rnnoise from a pinned upstream commit and model.
#
# What goes into the repo, and why it is not the upstream tree as-is:
#
#   upstream src/*.c, *.h      the subset librnnoise is built from (Makefile.am RNNOISE_SOURCES)
#   rnnoise_data_little.c      the generated model source with every weight array stripped: only
#                              init_rnnoise() is left, the code that maps a blob onto the layers
#   weights/rnnoise_little.bin the "little" model's weights as a binary blob (1.5 MB), embedded
#                              into the .so with .incbin. The C source of the same weights is
#                              30 MB of text and takes a minute to compile; the blob is neither.
#
# Needs curl, python3 and a C compiler. Run from anywhere; it writes beside itself.
set -euo pipefail

# Upstream main after the 0.2 release: the Jan 2025 retrain and the VAD fading fix.
readonly COMMIT=70f1d256acd4b34a572f999a05c87bf00b67730d
# GitHub archive of that commit.
readonly SRC_SHA256=f61ee0b3f4c4cd337303e003d333357c5eaf25ef5d75a742109ee59e9a0a3932
# model_version at that commit; the tarball's sha256 is its name.
readonly MODEL=0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37
readonly KEEP_SRC="arch.h celt_lpc.c celt_lpc.h common.h cpu_support.h denoise.c denoise.h
  _kiss_fft_guts.h kiss_fft.c kiss_fft.h nnet.c nnet.h nnet_arch.h nnet_default.c opus_types.h
  parse_lpcnet_weights.c pitch.c pitch.h rnn.c rnn.h rnnoise_tables.c vec.h vec_avx.h vec_neon.h"
# vec.h includes these on every target; vec_avx.h is the SSE path the x86_64 (emulator) build takes.
readonly KEEP_X86="x86_arch_macros.h x86cpu.h dnn_x86.h"

here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/rnnoise"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 1. Fetch and check.
curl -sSfL -o "$work/src.tar.gz" "https://github.com/xiph/rnnoise/archive/$COMMIT.tar.gz"
curl -sSfL -o "$work/model.tar.gz" "https://media.xiph.org/rnnoise/models/rnnoise_data-$MODEL.tar.gz"
echo "$SRC_SHA256  $work/src.tar.gz" | sha256sum -c --quiet
echo "$MODEL  $work/model.tar.gz" | sha256sum -c --quiet
mkdir "$work/up"
tar xzf "$work/src.tar.gz" -C "$work/up" --strip-components=1
tar xzf "$work/model.tar.gz" -C "$work/up"
[ "$(cat "$work/up/model_version")" = "$MODEL" ] || { echo "model_version mismatch" >&2; exit 1; }

# 2. Copy the subset, licence and authors.
rm -rf "$dest"
mkdir -p "$dest/src/x86" "$dest/include" "$dest/weights"
cp "$work/up/COPYING" "$work/up/AUTHORS" "$dest/"
cp "$work/up/include/rnnoise.h" "$dest/include/"
for f in $KEEP_SRC; do cp "$work/up/src/$f" "$dest/src/"; done
for f in $KEEP_X86; do cp "$work/up/src/x86/$f" "$dest/src/x86/"; done
cp "$work/up/src/rnnoise_data_little.h" "$dest/src/rnnoise_data.h"

# 3. Strip the weight arrays out of the generated source, keeping init_rnnoise().
python3 - "$work/up/src/rnnoise_data_little.c" "$dest/src/rnnoise_data_little.c" <<'EOF'
import sys
out, skip = [], 0
for line in open(sys.argv[1]):
    if line.startswith("#ifndef USE_WEIGHTS_FILE"):
        skip += 1
        continue
    if skip and line.startswith("#endif /* USE_WEIGHTS_FILE */"):
        skip -= 1
        continue
    if not skip:
        out.append(line)
open(sys.argv[2], "w").write("".join(out))
EOF

# 4. Dump the blob: upstream's write_weights.c, pointed at the little model, without the
#    float debug copies the int8 layers never read.
sed 's/#include "rnnoise_data.c"/#include "rnnoise_data_little.c"/' \
  "$work/up/src/write_weights.c" > "$work/up/src/write_little.c"
cc -O1 -DDISABLE_DEBUG_FLOAT -I"$work/up/include" -I"$work/up/src" \
  "$work/up/src/write_little.c" "$work/up/src/parse_lpcnet_weights.c" -o "$work/write_little" -lm
(cd "$work" && ./write_little)
cp "$work/weights_blob.bin" "$dest/weights/rnnoise_little.bin"

echo "vendored rnnoise $COMMIT, model $MODEL"
ls -la "$dest/weights"
