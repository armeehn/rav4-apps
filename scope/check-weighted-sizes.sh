#!/usr/bin/env bash
# No view inside a LinearLayout may be 0dp across the layout's axis.
#
# WHY. A 0dp size only means "take your share of the weight" along the LinearLayout's own
# orientation. Across it, 0dp is a real zero. The Voice Recorder's record button sat in a
# FrameLayout that was 0dp wide inside a vertical column (2026-10-01): the button still drew,
# but no touch could reach it through a zero-width parent, so tapping the mic did nothing.
# It builds, it looks right on a screenshot, and only a finger finds it.
#
# WHAT IT CHECKS. A direct child of a LinearLayout whose orientation is written in the layout:
# vertical children may not be 0dp wide, horizontal children may not be 0dp tall. A LinearLayout
# with no orientation attribute but a style is skipped, because the style may set it.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
for f in apps/com.ripostelabs.*/res/layout/*.xml; do
    while read -r tag attr; do
        echo "FAIL $(basename "$(dirname "$(dirname "$(dirname "$f")")")"): $tag in $(basename "$f") has $attr=\"0dp\" across its LinearLayout"
        fail=1
    done < <(python3 - "$f" <<'PY'
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"

# Which size attribute is a real zero, by the parent's orientation.
CROSS = {"vertical": "layout_width", "horizontal": "layout_height"}

def orientation(node):
    """The LinearLayout's axis, or None when a style may decide it."""
    value = node.get(A + "orientation")
    if value is not None:
        return value
    if node.get("style") is not None:
        return None
    return "horizontal"  # the framework default

for parent in ET.parse(sys.argv[1]).iter():
    if parent.tag != "LinearLayout":
        continue
    axis = orientation(parent)
    if axis is None:
        continue
    attr = CROSS[axis]
    for child in parent:
        if child.get(A + attr) == "0dp":
            print(child.tag, "android:" + attr)
PY
)
done

[ "$fail" -eq 0 ] && echo "OK: no LinearLayout child is 0dp across its layout's axis"
exit "$fail"
