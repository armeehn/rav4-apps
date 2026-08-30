#!/usr/bin/env bash
# Every app must be able to READ the launcher palette, and nothing about failing to
# is loud: package visibility hides the provider, the query returns null, and the app
# quietly keeps its own colours. That is indistinguishable from "no launcher installed",
# which is a state the client is *designed* to tolerate — so only a check like this one
# can tell the two apart. It is the regression that shipped once already.
set -euo pipefail
cd "$(dirname "$0")/.."

AUTHORITY="com.reveng.carlauncher.theme"
PALETTE="com/reveng/design/Palette"
fail=0

for d in apps/com.reveng.*; do
    pkg="$(basename "$d")"

    if ! grep -q "$AUTHORITY" "$d/AndroidManifest.xml"; then
        echo "FAIL $pkg: no <queries> entry for $AUTHORITY"
        fail=1
    fi

    # v0.5.2: every Activity must hand its view tree to Palette.apply, or everything the
    # design-pack *resources* coloured (card backgrounds, hairlines, styled text) stays on
    # the built-in palette while the Java-set colours follow the launcher — a half-themed
    # screen, which is worse than an unthemed one.
    for a in $(grep -rl "extends Activity" "$d/src" 2>/dev/null); do
        grep -q "Palette.apply(this)" "$a" || {
            echo "FAIL $pkg: $(basename "$a") never calls Palette.apply(this)"
            fail=1
        }
    done

    apk="$d/app-debug.apk"
    if [ ! -f "$apk" ]; then
        echo "FAIL $pkg: not built"
        fail=1
        continue
    fi

    # Read the dex out of the zip in python: `unzip` is absent on server x, and a
    # grep through a missing binary reports zero matches rather than an error.
    if ! python3 - "$apk" "$PALETTE" <<'PY'
import sys, zipfile
apk, needle = sys.argv[1], sys.argv[2].encode()
with zipfile.ZipFile(apk) as z:
    sys.exit(0 if z.read("classes.dex").count(needle) else 1)
PY
    then
        echo "FAIL $pkg: $PALETTE missing from classes.dex"
        fail=1
    fi
done

# v0.6.1: an app that plays or captures audio must go through MediaCitizen. Without it it
# plays over the radio, does not duck for a navigation prompt, is invisible to the launcher's
# now-playing card, and the steering-wheel media keys do nothing. None of that fails a build,
# and none of it is visible on a desk.
for d in apps/com.reveng.*; do
    pkg="$(basename "$d")"
    grep -rqE "MediaPlayer|MediaRecorder|AudioRecord|VideoView" "$d/src" 2>/dev/null || continue
    grep -rq "MediaCitizen" "$d/src" 2>/dev/null || {
        echo "FAIL $pkg: uses audio but never takes audio focus (MediaCitizen)"
        fail=1
    }
done

[ "$fail" -eq 0 ] && echo "OK: theme wiring and audio citizenship both intact"
exit "$fail"
