#!/usr/bin/env bash
# The committed .so files must come from the committed sources (see build.sh).
#   check-libs.sh          exit 1 when jniLibs/SOURCES.sha256 does not match jni/ (CI)
#   check-libs.sh --stamp  rewrite the stamp (build.sh, after a rebuild)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
stamp="$here/../jniLibs/SOURCES.sha256"

# Every input of the libraries: C sources, headers, the model blob, the flag and source lists.
# Tests and the scripts that only check are not inputs.
digest() {
    (cd "$here" && find . -type f \( -name '*.c' -o -name '*.h' -o -name '*.bin' -o -name 'sources.sh' -o -name 'build.sh' \) \
        ! -path './test/*' -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
}

if [ "${1:-}" = "--stamp" ]; then
    digest > "$stamp"
    echo ">> stamped $(cat "$stamp")"
    exit 0
fi

want="$(digest)"
have="$(cat "$stamp" 2>/dev/null || echo none)"
if [ "$want" != "$have" ]; then
    echo "FAIL jniLibs are stale: jni/ hashes to $want, the libraries were built from $have"
    echo "     rebuild with jni/build.sh (needs NDK r27) and commit jniLibs/"
    exit 1
fi
for so in "$here"/../jniLibs/*/librnnoise_jni.so; do
    [ -s "$so" ] || { echo "FAIL missing $so"; exit 1; }
done
echo "ok   jniLibs match jni/ ($want)"
