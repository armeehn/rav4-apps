package com.ripostelabs.projection;

/**
 * The one texture the CarPlay screen shows for its whole life. The decoder draws into it
 * while the screen is away, so it always holds the phone's latest picture.
 *
 * <p>A stopped window loses its texture layer: the view lets go of the texture (the screen
 * keeps it, see {@code onSurfaceTextureDestroyed}) and makes a fresh, empty one on the next
 * draw. Adopting that one showed nothing until the phone redrew, which on a still screen took
 * up to 10 s (car log 2026-10-01 15:29:45). So the kept texture goes back into the view.
 *
 * <pre>
 *   first texture        ─▶ ADOPT    keep it, point the decoder at it
 *   fresh one after stop ─▶ RESTORE  hand the kept one back to the view
 *   the kept one         ─▶ KEEP     nothing to do
 * </pre>
 *
 * Generic so the JVM tests need no Android texture.
 */
final class KeptTexture<T> {

    enum Use { ADOPT, RESTORE, KEEP }

    private T kept;

    /** The view made [fresh] available. */
    Use onAvailable(T fresh) {
        if (kept == null) {
            kept = fresh;
            return Use.ADOPT;
        }
        return fresh == kept ? Use.KEEP : Use.RESTORE;
    }

    /** The screen is starting; [current] is the view's texture, null when the stop took it. */
    Use onStart(T current) {
        if (kept == null || current == kept) {
            return Use.KEEP;
        }
        return Use.RESTORE;
    }

    T texture() {
        return kept;
    }
}
