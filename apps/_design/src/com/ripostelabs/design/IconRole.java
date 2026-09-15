package com.ripostelabs.design;

/**
 * v0.11 — what colour a monochrome icon should be, from what it sits on.
 *
 * Every vector in the suite is a white glyph (957 files, all {@code #FFFFFFFF}). White is
 * right on an accent button in any palette, and right on a dark surface, and invisible on a
 * light one. The icon itself cannot know which: the decision belongs to the ground beneath
 * it, so it is made here from the ancestors' fills and nothing else.
 *
 * <pre>
 *   toolbar (surface)            fab (accent)          gauge (red)
 *     └ ripple (mask only)         └ ic_add               └ ic_warn
 *        └ ic_back
 *   nearest opaque = surface     nearest opaque = accent  nearest opaque = unknown
 *   → INK                        → KEEP                   → KEEP
 * </pre>
 *
 * Pure Java on purpose: the view walk that collects the fills lives in {@link Palette}, this
 * rule is what the unit test pins.
 */
final class IconRole {

    private IconRole() {}

    /** Tint the glyph with the palette's on-surface ink, or leave it as drawn. */
    enum Paint { INK, KEEP }

    /** A fill with no alpha does not count as ground; the walk looks further up. */
    private static final int ALPHA_MASK = 0xFF000000;

    /**
     * @param grounds  background fills from the icon outward, nearest first; 0 or a fully
     *                 transparent colour for a view that paints nothing
     * @param surfaces the pack's ground fills as painted right now (bg, bg2, surface, ...)
     */
    static Paint decide(int[] grounds, int[] surfaces) {
        for (int ground : grounds) {
            if (isClear(ground)) {
                continue;
            }
            return isSurface(ground, surfaces) ? Paint.INK : Paint.KEEP;
        }
        return Paint.KEEP;
    }

    static boolean isClear(int color) {
        return (color & ALPHA_MASK) == 0;
    }

    private static boolean isSurface(int ground, int[] surfaces) {
        for (int s : surfaces) {
            if (s == ground) {
                return true;
            }
        }
        return false;
    }
}
