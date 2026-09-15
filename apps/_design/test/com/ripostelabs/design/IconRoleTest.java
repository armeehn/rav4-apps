package com.ripostelabs.design;

import static com.ripostelabs.design.IconRole.Paint.INK;
import static com.ripostelabs.design.IconRole.Paint.KEEP;

/**
 * The role rule behind Palette's icon tint: a white glyph turns to ink only when the nearest
 * opaque ancestor is a pack surface. Accent buttons and unknown grounds keep white.
 */
public final class IconRoleTest {

    private IconRoleTest() {}

    // Riposte day: bone ground, dark ink, red accent.
    private static final int BG = 0xFFF6F1E7;
    private static final int SURFACE2 = 0xFFEDE6D6;
    private static final int ACCENT = 0xFFD81050;
    private static final int CANVAS_DARK = 0xFF12151C;
    private static final int CLEAR = 0x00000000;
    private static final int CLEAR_WHITE = 0x00FFFFFF;

    private static final int[] SURFACES = {BG, SURFACE2};

    public static void main(String[] args) {
        check(IconRole.decide(new int[]{BG}, SURFACES) == INK, "icon straight on bg is ink");
        check(IconRole.decide(new int[]{SURFACE2}, SURFACES) == INK, "any pack surface is ink");
        check(IconRole.decide(new int[]{ACCENT}, SURFACES) == KEEP, "icon on accent stays white");
        check(IconRole.decide(new int[]{CANVAS_DARK}, SURFACES) == KEEP, "unknown ground is left alone");

        // The toolbar case: a mask-only ripple paints nothing, so the surface behind decides.
        check(IconRole.decide(new int[]{CLEAR, BG}, SURFACES) == INK, "clear ripple over bg is ink");
        check(IconRole.decide(new int[]{CLEAR_WHITE, CLEAR, BG}, SURFACES) == INK,
                "alpha, not rgb, decides what is clear");

        // The fab case: the accent fill is nearer than the surface it sits on.
        check(IconRole.decide(new int[]{CLEAR, ACCENT, BG}, SURFACES) == KEEP, "nearest opaque wins");

        // Nothing opaque at all, or no surfaces known: do nothing.
        check(IconRole.decide(new int[]{CLEAR, CLEAR}, SURFACES) == KEEP, "no ground means keep");
        check(IconRole.decide(new int[]{}, SURFACES) == KEEP, "no ancestors means keep");
        check(IconRole.decide(new int[]{BG}, new int[]{}) == KEEP, "no surfaces means keep");

        System.out.println("IconRoleTest: ok");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
