package com.ripostelabs.design;

/**
 * The third text tier the palette derives from the launcher's muted text. At a fixed 0.45
 * blend toward the background the default palette gave #626974, 2.8:1 on surface2 (a11y
 * audit, 2026-09-22): every hint and caption in the suite under the WCAG AA floor.
 */
public final class TextTierTest {

    private TextTierTest() {}

    private static final int BG = 0xFF0B0E14;
    private static final int SURFACE = 0xFF161B24;
    private static final int SURFACE2 = 0xFF1E2431;
    private static final int TEXT2 = 0xFFAAB3C2;

    public static void main(String[] args) {
        // The default palette: the old blend fails, the derived tier must clear every surface.
        int t3 = TextTier.third(TEXT2, BG, SURFACE, SURFACE2);
        check(TextTier.contrast(t3, BG) >= TextTier.MIN_CONTRAST, "clears bg: " + hex(t3));
        check(TextTier.contrast(t3, SURFACE) >= TextTier.MIN_CONTRAST, "clears surface: " + hex(t3));
        check(TextTier.contrast(t3, SURFACE2) >= TextTier.MIN_CONTRAST, "clears surface2: " + hex(t3));

        // Still a distinct, dimmer tier than text2, or there is no third tier left.
        check(TextTier.contrast(t3, BG) < TextTier.contrast(TEXT2, BG), "dimmer than text2: " + hex(t3));

        // A palette with headroom keeps the full blend: the fix changes nothing that passed.
        int white = 0xFFFFFFFF, black = 0xFF000000;
        int roomy = TextTier.third(white, black, black, black);
        check(roomy == TextTier.blend(white, black, TextTier.TOWARD_BACKGROUND), "roomy palette unchanged: " + hex(roomy));

        // A light theme walks the same way: toward the background, stopping at the floor.
        int light = TextTier.third(0xFF4A5261, 0xFFFFFFFF, 0xFFF2F4F7, 0xFFE6E9EE);
        check(TextTier.contrast(light, 0xFFE6E9EE) >= TextTier.MIN_CONTRAST, "light theme clears: " + hex(light));

        // A launcher whose own muted text fails gets that colour back, never something dimmer.
        int weak = 0xFF404040;
        check(TextTier.third(weak, black, 0xFF303030, 0xFF383838) == weak, "weak text2 returned as is");

        // Reference values: WCAG's own extremes.
        check(Math.abs(TextTier.contrast(white, black) - 21.0) < 0.01, "white on black is 21:1");
        check(Math.abs(TextTier.contrast(black, black) - 1.0) < 0.01, "black on black is 1:1");
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
