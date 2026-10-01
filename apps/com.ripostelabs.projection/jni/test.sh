#!/usr/bin/env bash
# Build the denoiser for the host and run its native test. CI runs this; it needs only cc.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

# shellcheck source=sources.sh
. "$here/sources.sh"
cc -O2 $NS_CFLAGS -Wa,-I"$here/rnnoise/weights" -I"$here" \
  $(ns_sources "$here") "$here/test/ns_test.c" -o "$out/ns_test" -lm -lpthread
"$out/ns_test"
