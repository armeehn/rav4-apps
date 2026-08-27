#!/usr/bin/env bash
# Gradle-free Android build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Builds a signed debug APK from a template-style project (this dir or $1).
set -euo pipefail
PROJ="${1:-$(pwd)}"; PROJ="$(cd "$PROJ" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"; PLATFORM="$SDK/platforms/android-33/android.jar"
AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIP="$BT/zipalign"; SIGN="$BT/apksigner"
KS="$HOME/rav4-apps/debug.keystore"
[ -f "$PLATFORM" ] || { echo "missing $PLATFORM"; exit 1; }
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias rav4 -storepass android \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=rav4apps" >/dev/null 2>&1

OUT="$PROJ/build"; rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes"
# 1. compile + link resources, generate R.java
find "$PROJ/res" -type f | while read -r f; do "$AAPT2" compile "$f" -o "$OUT/compiled" 2>/dev/null || true; done
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" --java "$OUT/gen" \
  $(find "$OUT/compiled" -name '*.flat' -printf '%p ') >/dev/null
# 2. compile java (app sources + generated R.java)
javac -source 17 -target 17 -d "$OUT/classes" -classpath "$PLATFORM" \
  $(find "$PROJ/src" "$OUT/gen" -name '*.java') 2>/dev/null
# 3. dex
"$D8" --lib "$PLATFORM" --output "$OUT" $(find "$OUT/classes" -name '*.class') >/dev/null 2>&1
# 4. assemble: add classes.dex into the resource apk
cd "$OUT" && cp base.apk unsigned.apk && zip -qj unsigned.apk classes.dex
# 5. align + sign
"$ZIP" -f 4 unsigned.apk aligned.apk >/dev/null
"$SIGN" sign --ks "$KS" --ks-pass pass:android --out "$PROJ/app-debug.apk" aligned.apk
echo ">> built: $PROJ/app-debug.apk"
