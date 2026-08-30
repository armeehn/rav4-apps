#!/usr/bin/env bash
# Gradle-free Android build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Builds a signed debug APK from a template-style project (this dir or $1).
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"   # java/keytool live here on some hosts
PROJ="${1:-$(pwd)}"; PROJ="$(cd "$PROJ" && pwd)"

# d8 (build-tools 34) crashes on class files from JDK 26 even with --release 17,
# so compile with a real JDK 17 when available. Resolve one, else fall back.
# $JAVA_HOME is checked first so CI (setup-java) works, but only if it really is 17 --
# on many machines it points at a much newer JDK, which is the crash this loop avoids.
JAVAC=javac; JAVAC_ARGS="--release 17"
for j in "${JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk "$HOME/.local/opt/jdk17" /usr/lib/jvm/*17*; do
    [ -n "$j" ] && [ -x "$j/bin/javac" ] || continue
    "$j/bin/javac" -version 2>&1 | grep -q " 17\." || continue
    JAVAC="$j/bin/javac"; JAVAC_ARGS=""; break
done
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"; PLATFORM="$SDK/platforms/android-33/android.jar"
AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIP="$BT/zipalign"; SIGN="$BT/apksigner"
# Keystore lives beside the repo, not under $HOME: on a CI runner $HOME is not the
# developer's home, the hardcoded directory does not exist, keytool fails, and `set -e`
# ends the script with no message at all because the error went to /dev/null.
KS="${RAV4_KEYSTORE:-$(cd "$PROJ/../.." 2>/dev/null && pwd || echo "$HOME")/debug.keystore}"
[ -f "$PLATFORM" ] || { echo "missing $PLATFORM"; exit 1; }
# Debug signing only — this key is deliberately throwaway and git-ignored, so CI mints
# its own on first build. Errors are NOT swallowed: a failure here used to surface as a
# silent exit 1 from the whole script.
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -alias rav4 -storepass android \
      -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=rav4apps" \
      || { echo "could not create debug keystore at $KS" >&2; exit 1; }
fi

OUT="$PROJ/build"; rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes"
# 1. compile + link resources, generate R.java.
#    No error-swallowing: a failed compile/link must abort the build (set -e),
#    not slip through and produce a broken/mis-targeted APK.
find "$PROJ/res" -type f | while read -r f; do "$AAPT2" compile "$f" -o "$OUT/compiled"; done
# --min/--target-sdk-version pin the APK to the car's API level (28..33);
# without them aapt2 stamps targetSdk 1, which changes runtime behaviour.
"$AAPT2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --min-sdk-version 28 --target-sdk-version 33 \
  --manifest "$PROJ/AndroidManifest.xml" --java "$OUT/gen" \
  $(find "$OUT/compiled" -name '*.flat' -printf '%p ') >/dev/null
# 2. compile java (app sources + the shared design sources + generated R.java).
# apps/_design/src holds code every app needs and no app should own a copy of (the launcher
# palette client). It is compiled in rather than pre-built into a jar: there is no dependency
# resolution here by design, and one more source root costs nothing.
SHARED_SRC="$PROJ/../_design/src"
[ -d "$SHARED_SRC" ] || SHARED_SRC=""
# -encoding is explicit: several sources carry × ÷ √ π as literals, and javac otherwise
# decodes them with the ambient locale's charset — green on a UTF-8 shell, 200 syntax
# errors on a runner that happens to start in the C locale.
"$JAVAC" $JAVAC_ARGS -encoding UTF-8 -d "$OUT/classes" -classpath "$PLATFORM" \
  $(find "$PROJ/src" $SHARED_SRC "$OUT/gen" -name '*.java')
# 3. dex
"$D8" --lib "$PLATFORM" --output "$OUT" $(find "$OUT/classes" -name '*.class') >/dev/null 2>&1
# 4. assemble: add classes.dex into the resource apk (python fallback: hosts without zip(1))
cd "$OUT" && cp base.apk unsigned.apk
if command -v zip >/dev/null; then zip -qj unsigned.apk classes.dex
else python3 -c 'import zipfile; z=zipfile.ZipFile("unsigned.apk","a",zipfile.ZIP_DEFLATED); z.write("classes.dex","classes.dex"); z.close()'
fi
# 5. align + sign
"$ZIP" -f 4 unsigned.apk aligned.apk >/dev/null
"$SIGN" sign --ks "$KS" --ks-pass pass:android --out "$PROJ/app-debug.apk" aligned.apk
echo ">> built: $PROJ/app-debug.apk"
