#!/usr/bin/env python3
"""The smallest PNG reader that can answer "what colour is this pixel".

`screencap -p` writes 8-bit RGB or RGBA, no interlace. Pillow is not installed on the
host that drives the emulator and this repo has no dependency file to add it to, so the
five PNG filter types are undone here instead. Nothing else is supported on purpose: an
unexpected header raises rather than guessing.
"""
import struct
import zlib
from collections import Counter

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


class Image:
    def __init__(self, path):
        raw = open(path, "rb").read()
        if not raw.startswith(PNG_MAGIC):
            raise ValueError(f"{path} is not a PNG")

        idat, pos = bytearray(), len(PNG_MAGIC)
        while pos < len(raw):
            (length,) = struct.unpack(">I", raw[pos:pos + 4])
            kind = raw[pos + 4:pos + 8]
            body = raw[pos + 8:pos + 8 + length]
            if kind == b"IHDR":
                self.w, self.h, depth, colour = struct.unpack(">IIBB", body[:10])
                if depth != 8 or colour not in (2, 6):
                    raise ValueError(f"{path}: only 8-bit RGB/RGBA is handled")
                self.stride = 3 if colour == 2 else 4
            elif kind == b"IDAT":
                idat += body
            elif kind == b"IEND":
                break
            pos += length + 12

        self.rows = self._unfilter(zlib.decompress(bytes(idat)))

    def _unfilter(self, data):
        """Undo the per-scanline filter byte (PNG spec 9.2): None/Sub/Up/Average/Paeth."""
        bpp, rowlen = self.stride, self.w * self.stride
        rows, prev, pos = [], bytearray(rowlen), 0
        for _ in range(self.h):
            ftype = data[pos]
            line = bytearray(data[pos + 1:pos + 1 + rowlen])
            pos += 1 + rowlen
            for i in range(rowlen):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                if ftype == 1:
                    line[i] = (line[i] + a) & 255
                elif ftype == 2:
                    line[i] = (line[i] + b) & 255
                elif ftype == 3:
                    line[i] = (line[i] + (a + b) // 2) & 255
                elif ftype == 4:
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
            rows.append(bytes(line))
            prev = line
        return rows

    def pixel(self, x, y):
        i = x * self.stride
        row = self.rows[y]
        return (row[i] << 16) | (row[i + 1] << 8) | row[i + 2]

    def histogram(self, l, t, r, b):
        """Colours inside a rectangle, clamped to the screen. Steps to cap the cost."""
        l, t = max(0, l), max(0, t)
        r, b = min(self.w, r), min(self.h, b)
        step = max(1, ((r - l) * (b - t)) // 40000)
        hist = Counter()
        for y in range(t, b):
            row = self.rows[y]
            for x in range(l, r, step):
                i = x * self.stride
                hist[(row[i] << 16) | (row[i + 1] << 8) | row[i + 2]] += 1
        return hist
