#!/usr/bin/env bash
# Every app must be able to READ the launcher palette, and nothing about failing to
# is loud: package visibility hides the provider, the query returns null, and the app
# quietly keeps its own colours. That is indistinguishable from "no launcher installed",
# which is a state the client is *designed* to tolerate — so only a check like this one
# can tell the two apart. It is the regression that shipped once already.
set -euo pipefail
cd "$(dirname "$0")/.."

AUTHORITY="com.ripostelabs.carlauncher.theme"
PALETTE="com/ripostelabs/design/Palette"
fail=0

for d in apps/com.ripostelabs.*; do
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

    # Read the dex out of the zip in python: `unzip` is absent on some hosts, and a
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
for d in apps/com.ripostelabs.*; do
    pkg="$(basename "$d")"
    # WebView is in this list because a page's soundtrack is media too, and the browser is
    # exactly the app that shipped without focus by not looking like a media app.
    #
    # Ringtone was missing from this list until 2026-09-10, and the clock had been ringing its
    # alarm and its timer over the radio the whole time. The check was green because the class it
    # used was not named here. A pattern list is only as good as its last omission, so anything
    # that makes noise belongs in it whether or not it looks like a player.
    grep -rqE "MediaPlayer|MediaRecorder|AudioRecord|VideoView|WebView|Ringtone" "$d/src" 2>/dev/null || continue
    # WebAudio counts: it is the WebView-shaped front of MediaCitizen, in _design for the
    # two apps that put arbitrary pages on screen. AlarmAudio counts too, and is deliberately
    # NOT MediaCitizen: an alarm takes focus but must not publish a MediaSession, or it lands on
    # the launcher's now-playing card and the wheel's skip key addresses the alarm.
    grep -rqE "MediaCitizen|WebAudio|AlarmAudio" "$d/src" 2>/dev/null || {
        echo "FAIL $pkg: uses audio but never takes audio focus (MediaCitizen/AlarmAudio)"
        fail=1
    }
done

# Every app must declare the same API levels, and the scaffolder must agree with them.
#
# They did not. All 26 apps shipped minSdk 24, the template scaffolded 28, and build.sh passed
# --min-sdk-version 28 under a comment claiming it pinned the level. It does not: an explicit
# <uses-sdk> overrides the flag, so the build asserted one number and produced another, and any
# app made from the template disagreed with the suite from the moment it was created.
#
# Read from the template rather than hardcoded here, so raising the level is one edit and this
# check follows it instead of having to be found and updated too.
sdk_of() { sed -n 's/.*android:minSdkVersion="\([0-9]*\)".*/\1/p' "$1" | head -1; }
target_of() { sed -n 's/.*android:targetSdkVersion="\([0-9]*\)".*/\1/p' "$1" | head -1; }

want_min="$(sdk_of template/AndroidManifest.xml)"
want_target="$(target_of template/AndroidManifest.xml)"

for d in apps/com.ripostelabs.*; do
    pkg="$(basename "$d")"
    m="$d/AndroidManifest.xml"
    [ -f "$m" ] || continue

    got_min="$(sdk_of "$m")"
    got_target="$(target_of "$m")"

    if [ "$got_min" != "$want_min" ] || [ "$got_target" != "$want_target" ]; then
        echo "FAIL $pkg: API levels $got_min/$got_target, template says $want_min/$want_target"
        fail=1
    fi
done

# And the build must not claim a level the manifests contradict.
if ! grep -q -- "--min-sdk-version $want_min " template/build.sh; then
    echo "FAIL template/build.sh: --min-sdk-version disagrees with the manifests ($want_min)"
    fail=1
fi

[ "$fail" -eq 0 ] && echo "OK: theme wiring, audio citizenship and API levels all intact"
exit "$fail"
