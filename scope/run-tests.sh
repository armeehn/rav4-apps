#!/usr/bin/env bash
# Run the suite's unit tests.
#
# WHY THIS SHAPE. The suite has no Gradle and no dependencies: every app is javac, d8 and
# apksigner driven by template/build.sh. Introducing a build system or a JUnit jar to get one
# test would be a larger change than the thing being tested. So a test is an ordinary class with
# a `main` that throws on a wrong answer, compiled and run the same way everything else here is.
#
# WHAT IT COVERS, deliberately. Only logic that needs no Android. Anything touching an Activity,
# a View or a system service is not testable this way and is not pretended to be — the checks in
# check-theme-wiring.sh are what guard that half. An app with no test/ directory is skipped
# silently, because 26 apps of mostly-UI code should not each carry an empty ceremony.
#
# Layout: apps/<pkg>/test/<same package path>/<Name>Test.java, compiled against the app's own src.
set -uo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
PLATFORM="$SDK/platforms/android-33/android.jar"
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

fail=0
ran=0

for d in apps/com.ripostelabs.*; do
    [ -d "$d/test" ] || continue
    pkg="$(basename "$d")"

    out="$(mktemp -d)"
    # Only the test files are named. The app's sources are on the -sourcepath, so javac pulls in
    # exactly the classes a test actually reaches and nothing else.
    #
    # That is the important part. Compiling the whole app would need the generated R class and the
    # shared design pack, i.e. the aapt2 half of the build, which a test has no business needing.
    # It also enforces the boundary by construction: a test that reaches for an Activity fails to
    # resolve, so this harness can only ever cover logic that does not depend on Android.
    if ! "$JAVAC" -nowarn -encoding UTF-8 -d "$out" \
        -classpath "$PLATFORM" -sourcepath "$d/src:$d/test" \
        $(find "$d/test" -name '*.java') >"$out/compile.log" 2>&1
    then
        echo "FAIL $pkg: tests do not compile"
        sed -n '1,20p' "$out/compile.log"
        fail=1
        rm -rf "$out"
        continue
    fi

    for t in $(cd "$d/test" && find . -name '*Test.java'); do
        cls="$(echo "${t#./}" | sed 's/\.java$//; s#/#.#g')"
        ran=$((ran + 1))
        if "$JAVA" -cp "$out:$PLATFORM" "$cls" >"$out/run.log" 2>&1; then
            echo "ok   $pkg $cls"
        else
            echo "FAIL $pkg $cls"
            sed -n '1,25p' "$out/run.log"
            fail=1
        fi
    done

    rm -rf "$out"
done

if [ "$ran" -eq 0 ]; then
    echo "no tests found"
fi

[ "$fail" -eq 0 ] && echo "OK: $ran test class(es) passed"
exit "$fail"
