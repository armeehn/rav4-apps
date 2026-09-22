package com.ripostelabs.design;

/**
 * The third text tier ({@code text3}): the launcher's muted text pushed toward the
 * background, but never past the WCAG AA floor on any surface it is drawn on.
 *
 * A fixed blend cannot hold the floor across themes. At 0.45 the default palette gave
 * #626974, 2.8:1 on surface2 (a11y audit, 2026-09-22). So the blend starts at 0.45 and
 * steps back toward text2 until the result clears 4.5:1 on background, surface and surface
 * variant alike:
 *
 * <pre>
 *   text2 ─────────────●──────────── background
 *   0.0   ◄── steps ── t ── 0.45
 *                      └ first t that clears all three surfaces
 * </pre>
 *
 * Pure arithmetic with no Android types, so run-tests.sh can exercise it on the JVM.
 */
final class TextTier {

    private TextTier() {}

    /** WCAG 2.1 SC 1.4.3, normal-size text: hints and captions are body-sized. */
    static final double MIN_CONTRAST = 4.5;

    /** How far text3 sits from text2 toward the background when the palette allows it. */
    static final float TOWARD_BACKGROUND = 0.45f;

    /** Blend steps between TOWARD_BACKGROUND and text2 itself. */
    private static final int STEPS = 18;

    /**
     * The dimmest blend of {@code muted} toward {@code background}, up to
     * TOWARD_BACKGROUND, that clears MIN_CONTRAST on every surface. When even
     * {@code muted} fails, it is returned as is: the launcher's own colour, never dimmer.
     */
    static int third(int muted, int background, int surface, int surfaceVariant) {
        for (int i = 0; i <= STEPS; i++) {
            float t = TOWARD_BACKGROUND * (STEPS - i) / STEPS;
            int c = blend(muted, background, t);
            if (contrast(c, background) >= MIN_CONTRAST
                    && contrast(c, surface) >= MIN_CONTRAST
                    && contrast(c, surfaceVariant) >= MIN_CONTRAST) {
                return c;
            }
        }
        return muted;
    }

    /** Label inks for an accent fill. */
    static final int LIGHT_INK = 0xFFFFFFFF;
    static final int DARK_INK = 0xFF000000;

    /**
     * The label colour for text or a glyph on an {@code accent} fill: white or black,
     * whichever contrasts more. One of the two always clears 4.5:1 (the worst case, a
     * mid-grey, still gives 4.6:1). White wins ties, so a dark accent keeps its white label.
     * Example: #5B9DFF gives black, 7.7:1 (white was 2.7:1).
     */
    static int onAccent(int accent) {
        return contrast(LIGHT_INK, accent) >= contrast(DARK_INK, accent) ? LIGHT_INK : DARK_INK;
    }

    /** WCAG contrast ratio of two opaque colours, 1.0 to 21.0. */
    static double contrast(int a, int b) {
        double la = luminance(a) + 0.05;
        double lb = luminance(b) + 0.05;
        return Math.max(la, lb) / Math.min(la, lb);
    }

    /** Opaque linear blend, {@code amount} of the way from a to b. */
    static int blend(int a, int b, float amount) {
        int r = (int) (((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * amount));
        int g = (int) (((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * amount));
        int bl = (int) ((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * amount));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** WCAG relative luminance (sRGB). android.graphics.Color's is a stub off-device. */
    private static double luminance(int c) {
        return 0.2126 * channel((c >> 16) & 0xFF)
                + 0.7152 * channel((c >> 8) & 0xFF)
                + 0.0722 * channel(c & 0xFF);
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
