#!/usr/bin/env bash
# Every control the driver taps must be at least 48 dp on both axes.
#
# WHY. 48 dp is the Android touch-target floor, and a head unit is used one-handed, at arm's
# length, on a moving vehicle. The GPS refresh and settings icons shipped at 44 dp and the
# Speedometer unit and trip-reset buttons at 46 dp (UI audit, 2026-09-22): uiautomator reports
# them at 66 px and 69 px on the 1920x720 panel, under the 72 px floor.
#
# WHAT IT CHECKS. Only a clickable view given a fixed dp size in a layout: that is the shape a
# reader cannot judge at a glance. wrap_content, match_parent and 0dp weights are sized at run
# time and are left to the emulator sweep.
set -uo pipefail
cd "$(dirname "$0")/.."

MIN_DP=48

fail=0
for f in apps/com.ripostelabs.*/res/layout/*.xml; do
    while read -r line tag size; do
        echo "FAIL $(basename "$(dirname "$(dirname "$(dirname "$f")")")"): $tag at $(basename "$f"):$line is ${size}dp, under ${MIN_DP}dp"
        fail=1
    done < <(python3 - "$f" "$MIN_DP" <<'PY'
import re, sys

path, min_dp = sys.argv[1], int(sys.argv[2])
src = open(path).read()

# A view is a tap target when it is a Button/ImageButton or asks to be clicked.
CLICKABLE = re.compile(r'android:(onClick|clickable="true")')
DP = re.compile(r'layout_(width|height)="(\d+)dp"')

for m in re.finditer(r'<(\w+)([^>]*?)/?>', src, re.S):
    tag, attrs = m.group(1), m.group(2)
    if not (tag.endswith("Button") or CLICKABLE.search(attrs)):
        continue
    sizes = [int(size) for _, size in DP.findall(attrs) if int(size) < min_dp]
    if sizes:
        print(src[:m.start()].count("\n") + 1, tag, min(sizes))
PY
)
done

[ "$fail" -eq 0 ] && echo "OK: every fixed-size tap target is at least ${MIN_DP}dp"
exit "$fail"
