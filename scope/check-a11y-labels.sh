#!/usr/bin/env bash
# Every image the driver can press must announce what it does.
#
# WHY. A control with no label is silent under TalkBack and nameless to every UI driver,
# including this repo's own emulator sweep -- it can only be reached by pixel. The suite's
# ImageButtons were already labelled; the gap was ImageView used as a button, which looks
# identical on screen and carries no implicit role (Lamp's effect arrows, UI audit
# 2026-09-22). This is the source half of scope/a11y-audit.py, which needs a device.
#
# WHAT IT CHECKS, per app:
#   1. every <ImageButton>, every text-less <Switch>/<CheckBox>/<RadioButton>/<ToggleButton>,
#      and every <ImageView> that is clicked -- declared in the layout with android:onClick /
#      clickable="true", or wired in Java by findViewById(R.id.X).setOnClickListener --
#      carries android:contentDescription;
#   2. that contentDescription is a @string reference, not a literal, because the suite
#      ships in 16 locales and a literal is untranslatable.
#
# WHAT IT DOES NOT CHECK. A decorative ImageView with no click: ATF does not flag it and
# neither does this. Touch-target size is scope/check-tap-targets.sh; contrast and the
# code-built screens (Clock, Calendar) need the device and live in a11y-audit.py.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
for d in apps/com.ripostelabs.*; do
    [ -f "$d/AndroidManifest.xml" ] || continue
    [ -d "$d/res/layout" ] || continue
    while read -r msg; do
        echo "FAIL $(basename "$d"): $msg"
        fail=1
    done < <(python3 - "$d" <<'PY'
import glob, os, re, sys

app = sys.argv[1]

# Ids the Java wires a click to. The layout alone cannot tell: an ImageView becomes a
# button at run time and nothing in the XML says so.
java = "".join(open(f).read() for f in glob.glob(f"{app}/src/**/*.java", recursive=True))
clicked = set(re.findall(r"R\.id\.(\w+)\s*\)\s*\.setOnClickListener", java))
clicked |= set(re.findall(r"(\w+)\s*\.setOnClickListener", java))   # held in a local

TAG = re.compile(r'<(ImageButton|ImageView|Switch|CheckBox|RadioButton|ToggleButton)'
                 r'((?:[^<>"]|"[^"]*")*?)/?>', re.S)
# A compound button announces its android:text (a ToggleButton its textOn/textOff); with
# neither it is "switch, on" and nothing else.
COMPOUND = ("Switch", "CheckBox", "RadioButton", "ToggleButton")
CD = re.compile(r'android:contentDescription="([^"]*)"')

for path in sorted(glob.glob(f"{app}/res/layout/*.xml")):
    src = open(path).read()
    for m in TAG.finditer(src):
        tag, attrs = m.group(1), m.group(2)
        rid = re.search(r'android:id="@\+?id/(\w+)"', attrs)
        rid = rid.group(1) if rid else None
        if tag in COMPOUND and ("android:text=" in attrs or "android:textOn=" in attrs):
            continue
        acts = (tag == "ImageButton" or tag in COMPOUND
                or "android:onClick" in attrs
                or 'android:clickable="true"' in attrs
                or (rid and rid in clicked))
        where = f"{os.path.basename(path)}:{src[:m.start()].count(chr(10)) + 1}"
        cd = CD.search(attrs)
        if acts and not cd:
            print(f"{tag} {rid or '(no id)'} at {where} acts but has no contentDescription")
        elif cd and not cd.group(1).startswith("@"):
            print(f'{tag} {rid or "(no id)"} at {where} has a literal contentDescription '
                  f'"{cd.group(1)}"; use a @string')
PY
)
done

[ "$fail" -eq 0 ] && echo "OK: every image and switch that acts carries a @string contentDescription"
exit "$fail"
