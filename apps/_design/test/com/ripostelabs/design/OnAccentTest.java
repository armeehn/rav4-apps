package com.ripostelabs.design;

/**
 * The label drawn on an accent fill (buttons, chips, selected states). The suite drew it
 * white, which fails on any mid-light accent: the default #5B9DFF gives 2.7:1, and the
 * launcher's dark-theme accents, tuned to clear 4.5:1 on dark surfaces, cannot also clear
 * it under white.
 */
public final class OnAccentTest {

    private OnAccentTest() {}

    /** Accents from near-black to near-white, plus the pack default and saturated hues. */
    private static final int[] ACCENTS = {
        0xFF000000, 0xFF1A237E, 0xFF3A4557, 0xFF5B9DFF, 0xFF7C5CFF, 0xFF808080,
        0xFFFF4D5E, 0xFF22C55E, 0xFFFFC107, 0xFF00E5FF, 0xFFE0E0E0, 0xFFFFFFFF,
    };

    public static void main(String[] args) {
        // Every accent gets a label that clears the AA floor.
        for (int accent : ACCENTS) {
            int ink = TextTier.onAccent(accent);
            double ratio = TextTier.contrast(ink, accent);
            check(ratio >= TextTier.MIN_CONTRAST,
                    "on " + hex(accent) + ": " + hex(ink) + " is " + String.format("%.2f", ratio));
        }

        // A dark accent keeps its white label: the fix only moves what failed.
        check(TextTier.onAccent(0xFF1A237E) == 0xFFFFFFFF, "dark accent keeps white");
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
