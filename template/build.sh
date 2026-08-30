#!/usr/bin/env bash
# Gradle-free Android build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Builds a signed debug APK from a template-style project (this dir or $1).
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"   # java/keytool live here on server x
PROJ="${1:-$(pwd)}"; PROJ="$(cd "$PROJ" && pwd)"

# d8 (build-tools 34) crashes on class files from JDK 26 even with --release 17,
# so compile with a real JDK 17 when available. Resolve one, else fall back.
# $JAVA_HOME is checked first so CI (setup-java) works, but only if it really is 17 --
# on server x it points at a much newer JDK, which is the crash this loop exists to avoid.
JAVAC=javac; JAVAC_ARGS="--release 17"
for j in "${JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk "$HOME/.local/opt/jdk17" /usr/lib/jvm/*17*; do
    [ -n "$j" ] && [ -x "$j/bin/javac" ] || continue
    "$j/bin/javac" -version 2>&1 | grep -q " 17\." || continue
    JAVAC="$j/bin/javac"; JAVAC_ARGS=""; break
done
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
# 2. compile java (app sources + the shared design sources + generated R.java).
# apps/_design/src holds code every app needs and no app should own a copy of (the launcher
# palette client). It is compiled in rather than pre-built into a jar: there is no dependency
# resolution here by design, and one more source root costs nothing.
SHARED_SRC="$PROJ/../_design/src"
[ -d "$SHARED_SRC" ] || SHARED_SRC=""
"$JAVAC" $JAVAC_ARGS -d "$OUT/classes" -classpath "$PLATFORM" \
  $(find "$PROJ/src" $SHARED_SRC "$OUT/gen" -name '*.java')
# 3. dex
"$D8" --lib "$PLATFORM" --output "$OUT" $(find "$OUT/classes" -name '*.class') >/dev/null 2>&1
# 4. assemble: add classes.dex into the resource apk (python fallback: no zip on server x)
cd "$OUT" && cp base.apk unsigned.apk
if command -v zip >/dev/null; then zip -qj unsigned.apk classes.dex
else python3 -c 'import zipfile; z=zipfile.ZipFile("unsigned.apk","a",zipfile.ZIP_DEFLATED); z.write("classes.dex","classes.dex"); z.close()'
fi
# 5. align + sign
"$ZIP" -f 4 unsigned.apk aligned.apk >/dev/null
"$SIGN" sign --ks "$KS" --ks-pass pass:android --out "$PROJ/app-debug.apk" aligned.apk
echo ">> built: $PROJ/app-debug.apk"
