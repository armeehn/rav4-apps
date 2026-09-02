#!/usr/bin/env python3
"""Generate adaptive launcher icons for every rewritten app under apps/.

Style: one hard-edged, single-colour glyph per app (drawn below, in GLYPHS) on
a per-app coloured gradient plate. Every icon ships THREE layers:

    <background>  gradient plate             -> launchers that don't tint
    <foreground>  white glyph                -> launchers that don't tint
    <monochrome>  the same glyph, one colour -> API 33 themed icons; the
                  CarLauncher tints it with the active theme's palette

The glyph set is drawn on a 24-unit grid with stroke weight 3, butt caps and
mitre joins: straight lines, right angles and 45 degree diagonals only, so a
tile still reads at 48 dp on the 1920x720 panel and matches the Riposte
brand (radius 0, 2 dp borders, mono). Also writes a plain-mipmap layer-list
fallback for API 24/25 and patches android:icon into the manifest.
Idempotent: re-running just rewrites the same files.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent if (Path(__file__).resolve().parent.name == "scope") else Path.home() / "rav4-apps"
APPS = ROOT / "apps"

# Glyph grammar. Each app is a list of primitives on the 24x24 grid:
#   ("s", path)  stroked polyline, weight STROKE, butt caps, mitre joins
#   ("t", path)  thin stroked detail, weight THIN (hairlines, pins)
#   ("f", path)  filled polygon
STROKE = 3
THIN = 2

GLYPHS = {
    # the rune, straightened: one polyline, no curves
    "bluetooth": [("s", "M6,7 L18,17 L12,22 L12,2 L18,7 L6,17")],
    # a window: outline + solid title bar
    "browser": [("s", "M3,4 h18 v16 h-18 z"), ("f", "M3,4 h18 v5 h-18 z")],
    # body outline, solid display, 2x3 key grid
    "calculator": [
        ("s", "M4,2 h16 v20 h-16 z"),
        ("f", "M7,5 h10 v4 h-10 z"),
        ("f", "M7,12 h2 v2 h-2 z M11,12 h2 v2 h-2 z M15,12 h2 v2 h-2 z"),
        ("f", "M7,16 h2 v2 h-2 z M11,16 h2 v2 h-2 z M15,16 h2 v2 h-2 z"),
    ],
    # page outline, solid header, two binding pegs, one marked day
    "calendar": [
        ("s", "M3,5 h18 v16 h-18 z"),
        ("f", "M3,5 h18 v5 h-18 z"),
        ("s", "M7,2 v5 M17,2 v5"),
        ("f", "M7,13 h4 v4 h-4 z"),
    ],
    # octagon dial (a square clock reads as a window) + hands at ten past
    "clock": [
        ("s", "M8,2 L16,2 L22,8 L22,16 L16,22 L8,22 L2,16 L2,8 z"),
        ("s", "M12,7 L12,12 L16,12"),
    ],
    # diamond rose + solid needle
    "compass": [("s", "M12,2 L22,12 L12,22 L2,12 z"), ("f", "M12,7 L15,12 L12,17 L9,12 z")],
    # square head, trapezoid shoulders
    "contacts": [("f", "M9,3 h6 v6 h-6 z"), ("f", "M3,21 L6,14 L18,14 L21,21 z")],
    # two opposed arrows
    "converter": [
        ("s", "M3,8 h15"), ("f", "M17,4 L21,8 L17,12 z"),
        ("s", "M21,16 h-15"), ("f", "M7,12 L3,16 L7,20 z"),
    ],
    # banknote: outline + centre seal
    "currency": [("s", "M2,6 h20 v12 h-20 z"), ("f", "M10,9 h4 v6 h-4 z")],
    # chip: hollow die + pins on all four sides
    "deviceinfo": [
        ("s", "M7,7 h10 v10 h-10 z"),
        ("t", "M9,2 v5 M12,2 v5 M15,2 v5 M9,17 v5 M12,17 v5 M15,17 v5"),
        ("t", "M2,9 h5 M2,12 h5 M2,15 h5 M17,9 h5 M17,12 h5 M17,15 h5"),
    ],
    # solid folder with a tab
    "files": [("f", "M2,4 h8 l2,3 h10 v13 h-20 z")],
    # crosshair: square reticle, four ticks, solid centre
    "gps": [
        ("s", "M6,6 h12 v12 h-12 z"),
        ("s", "M12,2 v4 M12,18 v4 M2,12 h4 M18,12 h4"),
        ("f", "M10.5,10.5 h3 v3 h-3 z"),
    ],
    # arrow into a tray
    "installer": [("s", "M12,2 v11"), ("f", "M6,12 L18,12 L12,18 z"), ("s", "M3,17 v4 h18 v-4")],
    # spirit level: vial outline, solid bubble, two index marks
    "level": [("s", "M2,8 h20 v8 h-20 z"), ("f", "M10,10 h4 v4 h-4 z"), ("t", "M8,4 v4 M16,4 v4")],
    # note: solid head, stem, flag
    "music": [("f", "M10,15 h7 v6 h-7 z"), ("s", "M15.5,3 v18"), ("f", "M14,3 L21,6 L21,10 L14,7 z")],
    # front page: outline, lead photo, headline lines
    "news": [
        ("s", "M3,3 h18 v18 h-18 z"),
        ("f", "M6,6 h6 v6 h-6 z"),
        ("t", "M14,7 h4 M14,11 h4 M6,15 h12 M6,18 h12"),
    ],
    # pencil at 45 degrees: body, tip, eraser
    "notes": [
        ("f", "M4,17 L15,6 L18,9 L7,20 z"),
        ("f", "M3,21 L4,17 L7,20 z"),
        ("f", "M16,5 L18,3 L21,6 L19,8 z"),
    ],
    # frame, mountains, square sun
    "photos": [
        ("s", "M3,3 h18 v18 h-18 z"),
        ("f", "M6,18 L10,11 L13,15 L15,13 L18,18 z"),
        ("f", "M15,6 h3 v3 h-3 z"),
    ],
    # set outline, antenna, solid dial, two band marks
    "radio": [
        ("s", "M3,8 h18 v13 h-18 z"), ("s", "M6,8 L18,2"),
        ("f", "M7,12 h5 v5 h-5 z"), ("t", "M15,13 h3 M15,16 h3"),
    ],
    # microphone: solid capsule, square cradle, stand
    "recorder": [
        ("f", "M9,2 h6 v11 h-6 z"), ("s", "M6,10 v6 h12 v-6"),
        ("s", "M12,16 v4 M8,20 h8"),
    ],
    # brush: diagonal handle, solid head
    "sketch": [("s", "M12,11 L21,2"), ("f", "M11,10 L14,13 L8,19 L3,21 L5,15 z")],
    # four rising bars
    "soundmeter": [("f", "M3,14 h3 v7 h-3 z M8,10 h3 v11 h-3 z M13,6 h3 v15 h-3 z M18,2 h3 v19 h-3 z")],
    # half-octagon gauge, needle, solid hub
    "speedometer": [
        ("s", "M2,17 L2,12 L6,6 L12,4 L18,6 L22,12 L22,17"),
        ("s", "M12,16 L18,8"),
        ("f", "M10,14 h4 v4 h-4 z"),
    ],
    # one bold tick
    "tasks": [("s", "M4,12 L9,17 L20,6")],
    # screen outline + play triangle
    "video": [("s", "M2,4 h20 v16 h-20 z"), ("f", "M10,8 L16,12 L10,16 z")],
    # square sun, eight rays
    "weather": [
        ("f", "M8,8 h8 v8 h-8 z"),
        ("s", "M12,2 v4 M12,18 v4 M2,12 h4 M18,12 h4"),
        ("s", "M5,5 l3,3 M16,16 l3,3 M19,5 l-3,3 M8,16 l-3,3"),
    ],
}

# app suffix -> plate colour (the untinted foreground/background pair)
PLATES = {
    "bluetooth": "#1B6FD8", "browser": "#0C9DA8", "calculator": "#6A5AE0",
    "calendar": "#E8590C", "clock": "#3B5BDB", "compass": "#C0392B",
    "contacts": "#E64980", "converter": "#0CA678", "currency": "#2F9E44",
    "deviceinfo": "#5F3DC4", "files": "#E5A50A", "gps": "#1C7ED6",
    "installer": "#37B24D", "level": "#82C91E", "music": "#D6336C",
    "news": "#4C6EF5", "notes": "#F08C00", "photos": "#9C36B5",
    "radio": "#7048E8", "recorder": "#E03131", "sketch": "#F76707",
    "soundmeter": "#12B886", "speedometer": "#FA5252", "tasks": "#0B7285",
    "video": "#862E9C", "weather": "#228BE6",
}

WHITE = "#FFFFFFFF"

def darken(hex6, f=0.55):
    r, g, b = (int(hex6[i:i+2], 16) for i in (1, 3, 5))
    return "#%02X%02X%02X" % (int(r*f), int(g*f), int(b*f))

BG = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="270" android:startColor="{top}" android:endColor="{bot}" />
</shape>
"""

# <monochrome> is read by API 33+ (and by the CarLauncher, which tints it itself);
# older AdaptiveIconDrawable inflaters skip the tag.
ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
"""

FALLBACK = """<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_launcher_bg" />
    <item android:drawable="@drawable/ic_launcher_fg" />
</layer-list>
"""

def path_element(kind, data):
    if kind == "f":
        return f'        <path android:fillColor="{WHITE}" android:pathData="{data}" />'
    width = STROKE if kind == "s" else THIN
    return (f'        <path android:strokeColor="{WHITE}" android:strokeWidth="{width}"'
            f' android:strokeLineCap="butt" android:strokeLineJoin="miter" android:pathData="{data}" />')

def glyph_vector(glyph, note):
    # 24-unit glyph scaled 2.25x and centred in the 108dp adaptive canvas
    # -> glyph spans 54/108 = 50%, inside the 66/108 safe zone
    body = "\n".join(path_element(kind, data) for kind, data in glyph)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {note} Generated by scope/gen-launcher-icons.py; edit GLYPHS there. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="2.25" android:scaleY="2.25"
        android:translateX="27" android:translateY="27">
{body}
    </group>
</vector>
"""

def patch_manifest(mf):
    text = mf.read_text()
    if "android:icon=" in text:
        return "already"
    new = text.replace("<application\n", '<application\n        android:icon="@mipmap/ic_launcher"\n', 1)
    if new == text:  # <application ...> on one line
        new = re.sub(r"<application\b", '<application android:icon="@mipmap/ic_launcher"', text, count=1)
    if "android:icon=" not in new:
        return "FAILED"
    mf.write_text(new)
    return "patched"

def main():
    done, missing = [], []
    for app in sorted(APPS.glob("com.ripostelabs.*")):
        suffix = app.name.rsplit(".", 1)[-1]
        if suffix not in GLYPHS or suffix not in PLATES:
            missing.append(app.name)
            continue
        color, glyph = PLATES[suffix], GLYPHS[suffix]
        drawable = app / "res" / "drawable"
        drawable.mkdir(parents=True, exist_ok=True)
        (app / "res" / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
        (app / "res" / "mipmap").mkdir(parents=True, exist_ok=True)
        (drawable / "ic_launcher_bg.xml").write_text(BG.format(top=color, bot=darken(color)))
        (drawable / "ic_launcher_fg.xml").write_text(
            glyph_vector(glyph, "Foreground layer: white glyph over ic_launcher_bg."))
        # The monochrome layer is the SAME glyph, kept as its own resource: the adaptive-icon
        # contract says this layer is single-colour and tintable, and the foreground is free
        # to grow colour later without breaking themed launchers.
        (drawable / "ic_launcher_mono.xml").write_text(
            glyph_vector(glyph, "Monochrome layer: single colour, tinted by the launcher."))
        (app / "res" / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(ADAPTIVE)
        (app / "res" / "mipmap" / "ic_launcher.xml").write_text(FALLBACK)
        status = patch_manifest(app / "AndroidManifest.xml")
        done.append(f"{app.name}: {color} {len(glyph)} primitives manifest={status}")
    print("\n".join(done))
    if missing:
        print("NO GLYPH (add to GLYPHS/PLATES):", *missing, sep="\n  ")
        sys.exit(1)

if __name__ == "__main__":
    main()
