#!/usr/bin/env python3
"""Accessibility rules applied to a uiautomator dump of a suite app.

WHY HERE AND NOT AS AN INSTRUMENTATION TEST. Google's Accessibility Test Framework runs
inside the app process, which needs an androidTest APK per app. template/build.sh is a
hand-rolled aapt2/javac/d8 pipeline with no dependency resolution at all, so ATF and its
transitive tree (androidx.test, guava, protobuf) would have to be vendored and a second
APK wired up 28 times. These are XML-layout apps: the View tree IS the accessibility tree,
so a uiautomator dump carries the same nodes ATF would walk. The rules below are ATF's,
reimplemented against that dump.

THE RULES (ATF names in brackets):
  label     [SpeakableTextPresent]  a clickable node with no text and no content-desc.
  target    [TouchTargetSize]       a clickable node under 48 dp on either axis.
  contrast  [TextContrast]          text under 4.5:1 against the pixels behind it.
  dupe      [DuplicateSpeakableText] two clickable siblings that say the same thing.

MEASUREMENT CAVEAT. A uiautomator dump reports the node's bounds, which for a Compose
toolkit is the expanded hit box rather than the laid-out view. These apps are Views, so
the bounds are the real layout -- but every run still re-checks one known-good and one
known-bad control (see calibrate()) before any number here is trusted.
"""
import functools
import re
import xml.etree.ElementTree as ET

MIN_TAP_DP = 48          # Android touch-target floor
WCAG_AA_RATIO = 4.5      # WCAG 2.1 1.4.3 for normal-size text
WCAG_AA_LARGE_RATIO = 3.0
LARGE_INK_PX = 26        # ink height of 18pt text on this 240 dpi panel (see check_contrast)
INSET_FRACTION = 0.15    # of the node, trimmed off each side before sampling
MIN_INK_PX = 8           # a colour owning fewer pixels is noise, not a glyph

# Containers whose click is the row, not the icon: their children carry the label.
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class Node:
    """One uiautomator node, with the bits the rules care about."""

    def __init__(self, el, parent=None):
        a = el.attrib
        self.cls = a.get("class", "")
        self.rid = a.get("resource-id", "").split("/")[-1]
        self.text = (a.get("text") or "").strip()
        self.desc = (a.get("content-desc") or "").strip()
        self.clickable = a.get("clickable") == "true"
        self.scrollable = a.get("scrollable") == "true"
        self.enabled = a.get("enabled") == "true"
        self.visible = a.get("visible-to-user", "true") == "true"
        m = BOUNDS.match(a.get("bounds", ""))
        self.l, self.t, self.r, self.b = (int(g) for g in m.groups()) if m else (0, 0, 0, 0)
        self.parent = parent
        self.kids = [Node(k, self) for k in el]

    def walk(self):
        yield self
        for k in self.kids:
            yield from k.walk()

    @property
    def w(self):
        return self.r - self.l

    @property
    def h(self):
        return self.b - self.t

    def label(self):
        """What a screen reader would announce: own text/desc, else a descendant's."""
        if self.desc:
            return self.desc
        if self.text:
            return self.text
        parts = [n.desc or n.text for n in self.walk() if n is not self and (n.desc or n.text)]
        return " ".join(parts).strip()

    def name(self):
        return self.rid or self.cls.rsplit(".", 1)[-1]


def parse(xml_text):
    root = ET.fromstring(xml_text)
    return Node(root[0]) if len(root) else None


# --------------------------------------------------------------------------- rules

def check_labels(root):
    """A control the driver can press must announce something."""
    out = []
    for n in root.walk():
        if not (n.clickable and n.enabled and n.visible):
            continue
        if n.w <= 0 or n.h <= 0:
            continue
        if not n.label():
            out.append({"rule": "label", "control": n.name(), "detail": "clickable with no text and no content-desc"})
    return out


def check_targets(root, density):
    """48 dp on both axes, because the panel is used one-handed in a moving car."""
    floor = round(MIN_TAP_DP * density)
    out = []
    for n in root.walk():
        if not (n.clickable and n.enabled and n.visible) or n.w <= 0 or n.h <= 0:
            continue
        if clipped(n):
            continue
        if n.w < floor or n.h < floor:
            out.append({"rule": "target", "control": n.name(),
                        "detail": f"{n.w}x{n.h}px, floor {floor}px ({MIN_TAP_DP}dp)",
                        "bounds": [n.l, n.t, n.r, n.b]})
    return out


def clipped(n):
    """True when a scrolling ancestor cuts the node off at its edge.

    uiautomator reports the visible part of a node, so the last row of a list that runs
    past the fold reads short (Converter's "Area" row: 51 px of a 78 px row).
    """
    p = n.parent
    while p is not None:
        if p.scrollable and (n.t <= p.t or n.b >= p.b or n.l <= p.l or n.r >= p.r):
            return True
        p = p.parent
    return False


def check_dupes(root):
    """Two controls with the same announcement in one parent are indistinguishable."""
    out = []
    for n in root.walk():
        seen = {}
        for k in n.kids:
            if not (k.clickable and k.enabled and k.visible):
                continue
            lab = k.label()
            if not lab:
                continue
            seen.setdefault(lab, []).append(k)
        for lab, group in seen.items():
            if len(group) > 1:
                out.append({"rule": "dupe", "control": ", ".join(g.name() for g in group),
                            "detail": f'{len(group)} siblings all announce "{lab}"'})
    return out


def check_contrast(root, image):
    """Text against the pixels actually behind it, sampled from the screenshot.

    The commonest colour in the crop is the background; the foreground is the colour with
    the most contrast against it that owns at least MIN_INK_PX pixels. The crop is inset
    first: a Button's bounds include its rounded corners, which show the card behind and
    otherwise win as "foreground" (calculator RAD pill read 1.14:1 before the inset).
    """
    out = []
    for n in root.walk():
        if not n.text or n.w <= 0 or n.h <= 0 or not n.visible:
            continue
        if "EditText" in n.cls:          # a caret and a hint move under us; not a fair sample
            continue
        found = sample_text(image, n.l, n.t, n.r, n.b)
        if not found:
            continue
        fg, bg, ratio, ink_px = found
        # The dump has no text size. Ink height stands in for it: WCAG "large" is 18pt,
        # 24 CSS px, which is 36 px here, and capitals and ascenders fill ~0.7 of that.
        need = WCAG_AA_LARGE_RATIO if ink_px >= LARGE_INK_PX else WCAG_AA_RATIO
        if ratio < need:
            out.append({"rule": "contrast", "control": n.name(),
                        "detail": f'"{n.text[:24]}" {ratio:.2f}:1 (need {need}:1) '
                                  f"fg #{fg:06X} on bg #{bg:06X}"})
    return out


def sample_text(image, l, t, r, b):
    """(fg, bg, ratio, ink height px) for the text inside a node, or None if unreadable."""
    inset_y = (b - t) * INSET_FRACTION
    inset_x = min((b - t) / 2, (r - l) * INSET_FRACTION)
    l, r = int(l + inset_x), int(r - inset_x)
    t, b = int(t + inset_y), int(b - inset_y)
    if r - l < 4 or b - t < 4:
        return None

    hist = image.histogram(l, t, r, b)
    bg = hist.most_common(1)[0][0]
    cand = [c for c, k in hist.items() if c != bg and k >= MIN_INK_PX]
    if not cand:
        return None
    fg = max(cand, key=lambda c: contrast_ratio(c, bg))
    ratio = contrast_ratio(fg, bg)

    # Ink height: rows holding a pixel at least halfway (in contrast) from bg to fg.
    half = 1 + (ratio - 1) / 2
    rows = [y for y in range(t, b)
            if any(contrast_ratio(image.pixel(x, y), bg) >= half for x in range(l, r, 2))]
    ink = (rows[-1] - rows[0] + 1) if rows else 0
    return fg, bg, ratio, ink


@functools.lru_cache(maxsize=None)
def luminance(rgb):
    def chan(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b)


def contrast_ratio(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)
