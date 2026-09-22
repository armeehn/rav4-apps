#!/usr/bin/env python3
"""Audit the suite apps for accessibility defects on a running head unit.

    scope/a11y-audit.py --instance 1 --out /tmp/a11y.json [app ...]

Launches each app on the emulator, dumps the accessibility tree with uiautomator, grabs a
screenshot, and applies the rules in a11y_rules.py. Needs no per-app test wiring, which is
what makes it usable across 28 apps that each build as a separate APK.

CALIBRATION. The dump's bounds are trusted only after two controls of known size agree
with it on every run: see --calibrate and the note in a11y_rules.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import a11y_rules as rules
from a11y_png import Image

SETTLE_S = 3.0           # launch animation plus first layout pass
DEFAULT_DENSITY = 1.5    # 240 dpi panel; overridden by what the device reports
APPS_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "apps")


def hu(instance, *args, text=True):
    cmd = ["headunit", "-i", str(instance)] + list(args)
    return subprocess.run(cmd, capture_output=True, text=text, check=True).stdout


def suite_apps():
    names = sorted(d for d in os.listdir(APPS_ROOT) if d.startswith("com.ripostelabs."))
    return [n for n in names if os.path.isfile(os.path.join(APPS_ROOT, n, "AndroidManifest.xml"))]


def density(instance):
    out = hu(instance, "shell", "wm", "density")
    for tok in out.replace("\r", "").split():
        if tok.isdigit():
            return int(tok) / 160.0
    return DEFAULT_DENSITY


def capture(instance, workdir, pkg):
    """Launch, settle, dump and screenshot in two device round trips.

    Each `headunit` call is a full hop (ssh to the farm host, pct exec, adb), about 15 s on
    the ml350p farm. Doing launch, settle and dump in one shell and streaming the PNG over
    stdout keeps an app to two hops instead of six. The dump and the screenshot must show
    the same frame, so nothing runs between them.
    """
    dump = f"/data/local/tmp/a11y-{pkg}.xml"
    xml_text = hu(instance, "shell",
                  f"am force-stop {pkg}; "
                  f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; "
                  f"sleep {SETTLE_S}; uiautomator dump {dump} >/dev/null && cat {dump}; rm -f {dump}")
    png_bytes = hu(instance, "shell", "screencap -p", text=False)

    # uiautomator gives up on a screen that never idles (a live gauge, a spinner) and
    # prints an error instead of a dump; the report says so rather than guessing.
    if "<?xml" not in xml_text:
        raise RuntimeError(f"no dump: {xml_text.strip()[:160]}")

    xml = os.path.join(workdir, f"{pkg}.xml")
    png = os.path.join(workdir, f"{pkg}.png")
    open(xml, "w").write(xml_text[xml_text.index("<?xml"):])
    open(png, "wb").write(png_bytes)
    return xml, png


def audit_screen(xml_path, png_path, dens):
    root = rules.parse(open(xml_path).read())
    if root is None:
        return []
    found = rules.check_labels(root) + rules.check_targets(root, dens) + rules.check_dupes(root)
    found += rules.check_contrast(root, Image(png_path))
    return found


def calibrate(instance, workdir, dens):
    """Prove the dump's numbers before believing any of them.

    The launcher's own grid is the reference: its app tiles are laid out well above the
    floor and the system status row sits below it. If the dump disagrees with the pixels,
    every measurement in the report is worthless, so this refuses to continue.
    """
    xml, png = capture(instance, workdir, "com.ripostelabs.carlauncher")
    root = rules.parse(open(xml).read())
    img = Image(png)
    sizes = [(n.name(), n.w, n.h) for n in root.walk() if n.clickable and n.w > 0]
    if not sizes:
        raise SystemExit("calibrate: no clickable node in the dump; is the launcher up?")
    biggest = max(sizes, key=lambda s: s[1] * s[2])
    smallest = min(sizes, key=lambda s: s[1] * s[2])
    floor = round(rules.MIN_TAP_DP * dens)
    return {"screen_px": [img.w, img.h], "density": dens, "floor_px": floor,
            "largest_clickable": biggest, "smallest_clickable": smallest,
            "clickables": len(sizes)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apps", nargs="*", help="package names; default is every suite app")
    ap.add_argument("--instance", type=int, default=1)
    ap.add_argument("--out", default="/tmp/a11y-report.json")
    ap.add_argument("--keep", help="directory to keep dumps and screenshots in")
    ap.add_argument("--calibrate", action="store_true", help="print the bounds check and stop")
    ap.add_argument("--replay", help="re-score the captures a --keep run left, no device needed")
    args = ap.parse_args()

    workdir = args.keep or args.replay or tempfile.mkdtemp(prefix="a11y-")
    os.makedirs(workdir, exist_ok=True)
    dens = DEFAULT_DENSITY if args.replay else density(args.instance)

    if args.calibrate:
        print(json.dumps(calibrate(args.instance, workdir, dens), indent=2))
        return 0

    report, total = {}, 0
    for pkg in (args.apps or suite_apps()):
        try:
            if args.replay:
                xml, png = (os.path.join(workdir, f"{pkg}.{ext}") for ext in ("xml", "png"))
            else:
                xml, png = capture(args.instance, workdir, pkg)
            found = audit_screen(xml, png, dens)
        except (subprocess.CalledProcessError, RuntimeError) as e:
            detail = e.stderr.strip()[:200] if hasattr(e, "stderr") and e.stderr else str(e)
            report[pkg] = [{"rule": "error", "control": "-", "detail": detail}]
            print(f"{pkg:34} ERROR {detail}")
            continue
        report[pkg] = found
        total += len(found)
        print(f"{pkg:34} {len(found):3} finding(s)")
        for f in found:
            print(f"    {f['rule']:9} {f['control']:22} {f['detail']}")

    json.dump({"density": dens, "apps": report}, open(args.out, "w"), indent=2)
    print(f"\n{total} finding(s) across {len(report)} apps -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
