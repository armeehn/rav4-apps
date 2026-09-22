#!/usr/bin/env python3
"""Self-test for the a11y harness, runnable in CI with no device.

The harness is only worth its report if its measurements are right, and nothing else
exercises it between sweeps. This builds a screen whose answers are known -- one unlabelled
button, one 32 dp target, one text under 4.5:1, one that clears it -- and checks each rule
fires exactly where it should. The PNG is encoded here with every one of the five scanline
filters, because screencap uses them all and the decoder's unfiltering is the riskiest code.
"""
import os
import struct
import sys
import tempfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import a11y_rules as rules
from a11y_png import Image

W, H = 400, 200
BG = (0x0B, 0x0E, 0x14)
DIM = (0x51, 0x59, 0x62)       # launcher-derived text3 before the fix: 2.7:1 on BG
BRIGHT = (0xF2, 0xF5, 0xFA)    # design text: 17:1 on BG
DENSITY = 1.5                  # 240 dpi

# Two text rectangles, each drawn as horizontal "glyph" bars so ink height is measurable.
DIM_BOX = (20, 20, 180, 60)
BRIGHT_BOX = (220, 20, 380, 60)


def draw():
    rows = []
    for y in range(H):
        row = []
        for x in range(W):
            c = BG
            for box, ink in ((DIM_BOX, DIM), (BRIGHT_BOX, BRIGHT)):
                l, t, r, b = box
                if l + 30 <= x < r - 30 and t + 10 <= y < b - 10 and (x // 4) % 2 == 0:
                    c = ink
            row.append(c)
        rows.append(row)
    return rows


def encode(rows, path):
    """RGB PNG, cycling the filter type per scanline (PNG spec 9.2) to test every one."""
    bpp, raw, prev = 3, bytearray(), bytearray(W * 3)
    for y, row in enumerate(rows):
        line = bytearray(v for px in row for v in px)
        ftype = y % 5
        out = bytearray(len(line))
        for i, v in enumerate(line):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ftype == 0:
                p = 0
            elif ftype == 1:
                p = a
            elif ftype == 2:
                p = b
            elif ftype == 3:
                p = (a + b) // 2
            else:
                q = a + b - c
                pa, pb, pc = abs(q - a), abs(q - b), abs(q - c)
                p = a if pa <= pb and pa <= pc else b if pb <= pc else c
            out[i] = (v - p) & 255
        raw += bytes([ftype]) + out
        prev = line

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")
    open(path, "wb").write(png)


def box(b):
    return f"[{b[0]},{b[1]}][{b[2]},{b[3]}]"


DUMP = f"""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0"><node class="android.widget.FrameLayout" bounds="[0,0][{W},{H}]" clickable="false" enabled="true">
  <node class="android.widget.TextView" resource-id="p:id/dim" text="Hint" bounds="{box(DIM_BOX)}" clickable="false" enabled="true" />
  <node class="android.widget.TextView" resource-id="p:id/bright" text="Title" bounds="{box(BRIGHT_BOX)}" clickable="false" enabled="true" />
  <node class="android.widget.ImageButton" resource-id="p:id/nolabel" text="" content-desc="" bounds="[20,100][110,190]" clickable="true" enabled="true" />
  <node class="android.widget.ImageButton" resource-id="p:id/tiny" text="" content-desc="Close" bounds="[200,100][248,148]" clickable="true" enabled="true" />
  <node class="android.widget.ImageButton" resource-id="p:id/good" text="" content-desc="Open" bounds="[300,100][372,172]" clickable="true" enabled="true" />
  <node class="android.widget.ScrollView" bounds="[0,150][100,200]" scrollable="true" clickable="false" enabled="true">
    <node class="android.widget.TextView" resource-id="p:id/cut" text="Row" bounds="[0,180][100,200]" clickable="true" enabled="true" />
  </node>
</node></hierarchy>"""


def main():
    path = os.path.join(tempfile.mkdtemp(), "screen.png")
    pixels = draw()
    encode(pixels, path)
    img = Image(path)

    # The decoder must give back every pixel it was handed, across all five filters.
    for y in range(H):
        for x in range(0, W, 7):
            r, g, b = pixels[y][x]
            assert img.pixel(x, y) == (r << 16) | (g << 8) | b, f"pixel {x},{y} (filter {y % 5})"

    root = rules.parse(DUMP)

    labels = rules.check_labels(root)
    assert [f["control"] for f in labels] == ["nolabel"], labels

    # 32 dp is 48 px here: flagged. 48 dp is 72 px: exactly the floor, not flagged. A row
    # cut off by its scroll view reads short but is not.
    targets = rules.check_targets(root, DENSITY)
    assert [f["control"] for f in targets] == ["tiny"], targets

    contrast = rules.check_contrast(root, img)
    assert [f["control"] for f in contrast] == ["dim"], contrast
    assert "2.72:1" in contrast[0]["detail"], contrast

    # WCAG's reference extremes.
    assert abs(rules.contrast_ratio(0xFFFFFF, 0x000000) - 21.0) < 0.01
    assert abs(rules.contrast_ratio(0x777777, 0x777777) - 1.0) < 0.01

    print("OK: a11y harness self-test passed (labels, targets, contrast, PNG filters 0-4)")


if __name__ == "__main__":
    main()
