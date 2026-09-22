#!/usr/bin/env bash
# Every count string must have a singular twin.
#
# WHY. The suite writes counts as "%1$d tracks" and formats them straight into a header, so a
# lone track reads "1 tracks" (UI audit, 2026-09-22). The house fix is a pair of strings picked
# by the caller -- <name>_one for the singular, <name> for the rest -- because there is no
# resource compiler step here that would give us <plurals> for free at this size.
#
# WHAT IT CHECKS. Only strings whose whole value is "%1$d <word>s": that is the shape that
# breaks. "%1$d package(s)" and "%1$d of %2$d done" are already safe and are left alone.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
for f in apps/com.ripostelabs.*/res/values/strings.xml; do
    while read -r name; do
        grep -q "name=\"${name}_one\"" "$f" && continue
        echo "FAIL $(basename "$(dirname "$(dirname "$(dirname "$f")")")"): $name has no ${name}_one"
        fail=1
    done < <(sed -n 's#.*<string name="\([a-z_]*\)">%1\$d [a-z]*s</string>.*#\1#p' "$f")
done

[ "$fail" -eq 0 ] && echo "OK: every count string has a singular twin"
exit "$fail"
