#!/usr/bin/env python3
"""Generate adaptive launcher icons for every rewritten app under apps/.

Style: per-app colored vertical gradient background + white glyph (from the
shared apps/_design set, or an inline custom path), 50% safe-zone scale.
Writes bg/fg drawables, mipmap-anydpi-v26 adaptive icon, a plain-mipmap
layer-list fallback for API 24/25, and patches android:icon into the manifest.
Idempotent: re-running just rewrites the same files.
"""
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent if (Path(__file__).resolve().parent.name == "scope") else Path.home() / "rav4-apps"
APPS = ROOT / "apps"
DESIGN = APPS / "_design" / "res" / "drawable"

# Custom glyph paths (material symbols, 24dp viewport) for apps with no _design match
CALC = ("M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3z"
        "M13.03,8.06l1.06,-1.06 1.41,1.41 1.41,-1.41 1.06,1.06 -1.41,1.41 1.41,1.41 -1.06,1.06 "
        "-1.41,-1.41 -1.41,1.41 -1.06,-1.06 1.41,-1.41 -1.41,-1.41zM6.25,7.72h5v1.5h-5v-1.5z"
        "M11.5,16h-2v2H8v-2H6v-1.5h2v-2h1.5v2h2V16zM18,17.25h-5v-1.5h5v1.5zM18,14.75h-5v-1.5h5v1.5z")
LEVEL = ("M4,9h16c1.1,0 2,0.9 2,2v2c0,1.1 -0.9,2 -2,2H4c-1.1,0 -2,-0.9 -2,-2v-2c0,-1.1 0.9,-2 2,-2z"
         "M12,10a2,2 0 1,0 0.001,0z")
SUNNY = ("M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5H1v2h3v-2zM13,0.55h-2V3.5h2V0.55z"
         "M20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79zM17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4z"
         "M20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6z"
         "M11,22.45h2V19.5h-2v2.95zM3.55,18.54l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z")

# app suffix -> (base color, glyph file in _design | (inline_paths, fill_type))
ICONS = {
    "bluetooth":   ("#1B6FD8", "ic_bluetooth"),
    "browser":     ("#0C9DA8", "ic_globe"),
    "calculator":  ("#6A5AE0", ([CALC], None)),
    "calendar":    ("#E8590C", "ic_calendar"),
    "clock":       ("#3B5BDB", "ic_clock"),
    "compass":     ("#C0392B", "ic_compass"),
    "contacts":    ("#E64980", "ic_person"),
    "converter":   ("#0CA678", "ic_swap"),
    "currency":    ("#2F9E44", "ic_dollar"),
    "deviceinfo":  ("#5F3DC4", "ic_info"),
    "files":       ("#E5A50A", "ic_folder"),
    "gps":         ("#1C7ED6", "ic_navigation"),
    "installer":   ("#37B24D", "ic_download"),
    "level":       ("#82C91E", ([LEVEL], "evenOdd")),
    "music":       ("#D6336C", "ic_music"),
    "news":        ("#4C6EF5", "ic_news"),
    "notes":       ("#F08C00", "ic_edit"),
    "photos":      ("#9C36B5", "ic_image"),
    "radio":       ("#7048E8", "ic_radio"),
    "recorder":    ("#E03131", "ic_mic"),
    "sketch":      ("#F76707", "ic_brush"),
    "soundmeter":  ("#12B886", "ic_volume"),
    "speedometer": ("#FA5252", "ic_speed"),
    "tasks":       ("#0B7285", "ic_check"),
    "video":       ("#862E9C", "ic_movie"),
    "weather":     ("#228BE6", ([SUNNY], None)),
}

def darken(hex6, f=0.55):
    r, g, b = (int(hex6[i:i+2], 16) for i in (1, 3, 5))
    return "#%02X%02X%02X" % (int(r*f), int(g*f), int(b*f))

def glyph_paths(spec):
    if isinstance(spec, tuple):
        return spec
    src = (DESIGN / f"{spec}.xml").read_text()
    paths = re.findall(r'android:pathData="([^"]+)"', src)
    if not paths:
        sys.exit(f"no pathData in _design/{spec}.xml")
    return paths, None

BG = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="270" android:startColor="{top}" android:endColor="{bot}" />
</shape>
"""

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
</adaptive-icon>
"""

FALLBACK = """<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_launcher_bg" />
    <item android:drawable="@drawable/ic_launcher_fg" />
</layer-list>
"""

def fg_vector(paths, fill_type):
    # 24dp glyph scaled 2.25x and centered in the 108dp adaptive canvas
    # -> glyph spans 54/108 = 50%, inside the 66/108 safe zone
    ft = f' android:fillType="{fill_type}"' if fill_type else ""
    body = "\n".join(
        f'        <path android:fillColor="#FFFFFFFF"{ft} android:pathData="{p}" />' for p in paths)
    return f"""<?xml version="1.0" encoding="utf-8"?>
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
        if suffix not in ICONS:
            missing.append(app.name)
            continue
        color, spec = ICONS[suffix]
        paths, ft = glyph_paths(spec)
        (app / "res" / "drawable").mkdir(parents=True, exist_ok=True)
        (app / "res" / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
        (app / "res" / "mipmap").mkdir(parents=True, exist_ok=True)
        (app / "res" / "drawable" / "ic_launcher_bg.xml").write_text(BG.format(top=color, bot=darken(color)))
        (app / "res" / "drawable" / "ic_launcher_fg.xml").write_text(fg_vector(paths, ft))
        (app / "res" / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(ADAPTIVE)
        (app / "res" / "mipmap" / "ic_launcher.xml").write_text(FALLBACK)
        status = patch_manifest(app / "AndroidManifest.xml")
        done.append(f"{app.name}: {color} {'inline' if isinstance(spec, tuple) else spec} manifest={status}")
    print("\n".join(done))
    if missing:
        print("NO ICON MAPPING (add to ICONS):", *missing, sep="\n  ")

if __name__ == "__main__":
    main()
