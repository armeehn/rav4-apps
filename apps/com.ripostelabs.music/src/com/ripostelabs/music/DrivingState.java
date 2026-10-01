package com.ripostelabs.music;

/**
 * The launcher's parked-only rule (CarEvents.motion, LAUNCHER_DESIGN §1.4), for the search
 * keyboard (RAV4-179). The music app reads speed from its own GPS fix, so the thresholds are
 * copied here rather than shared.
 *
 * <pre>
 *   speed ≥ 8 km/h ─▶ MOVING     speed ≤ 3 km/h ─▶ PARKED     between ─▶ unchanged
 *   no fix         ─▶ unchanged  UNKNOWN never locks (fails open, as the launcher does)
 * </pre>
 */
final class DrivingState {

    enum Motion { UNKNOWN, PARKED, MOVING }

    /** 8 km/h ≈ 5 mph, the conventional automotive lockout threshold. */
    static final float MOVING_ABOVE_KMH = 8f;
    static final float PARKED_BELOW_KMH = 3f;

    private DrivingState() {
    }

    /** The verdict after a speed reading; NaN means no fix. */
    static Motion next(Motion current, float kmh) {
        if (Float.isNaN(kmh)) {
            return current;
        }
        if (kmh >= MOVING_ABOVE_KMH) {
            return Motion.MOVING;
        }
        if (kmh <= PARKED_BELOW_KMH) {
            return Motion.PARKED;
        }
        // Inside the band: keep the verdict. A first fix here errs towards moving.
        return current == Motion.UNKNOWN ? Motion.MOVING : current;
    }

    /** Typing is withheld only while moving. */
    static boolean keyboardAllowed(Motion m) {
        return m != Motion.MOVING;
    }
}
