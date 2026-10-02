package com.ripostelabs.recorder;

/**
 * Whether a take is really hearing the cabin, and if not, why. Pure, so it is JVM-tested.
 *
 * <p>A silenced capture is not an error: the platform grants focus, the recorder starts, and
 * the file fills with zeros. That is what the car saved during a phone call (2026-10-01): the
 * unit was in call mode, which mutes every capture but the call's own. So the take is watched
 * while it runs and stopped with the reason instead of saved.
 *
 * <pre>
 *   before the take: call mode ─────────────▶ CALL     (refused, nothing started)
 *   each tick:  platform silenced our client ─▶ CALL | BUSY | SILENCED
 *               peak 0 for ZERO_LIMIT_MS     ─▶ CALL | BUSY | SILENT
 *               otherwise                    ─▶ NONE
 * </pre>
 */
final class MicSilence {

    /** Why a take cannot hear the cabin. */
    enum Why { NONE, CALL, BUSY, SILENCED, SILENT }

    /** The unit's audio mode, folded: a call (cellular or VoIP) or anything else. */
    enum Mode { NORMAL, CALL }

    /**
     * A real cabin mic never reads exactly 0 for this long; the first amplitude read always
     * does, and the encoder needs a moment to start, so a shorter run is tolerated.
     */
    static final long ZERO_LIMIT_MS = 2_000L;

    /** One tick of a running take. */
    static final class Reading {
        final int peak;
        final boolean silenced;
        final Mode mode;
        final boolean othersCapturing;

        Reading(int peak, boolean silenced, Mode mode, boolean othersCapturing) {
            this.peak = peak;
            this.silenced = silenced;
            this.mode = mode;
            this.othersCapturing = othersCapturing;
        }
    }

    private long lastSoundMs;

    MicSilence(long startMs) {
        lastSoundMs = startMs;
    }

    /** Refuse a take the call mode would silence from its first sample. */
    static Why before(Mode mode) {
        return mode == Mode.CALL ? Why.CALL : Why.NONE;
    }

    /** NONE while the take hears something; otherwise why it does not. */
    Why check(Reading r, long nowMs) {
        if (r.silenced) {
            return reason(r, Why.SILENCED);
        }

        if (r.peak > 0) {
            lastSoundMs = nowMs;
            return Why.NONE;
        }

        if (nowMs - lastSoundMs < ZERO_LIMIT_MS) {
            return Why.NONE;
        }
        return reason(r, Why.SILENT);
    }

    /** A call explains it best, then another capture (CarPlay's mic), then [fallback]. */
    private static Why reason(Reading r, Why fallback) {
        if (r.mode == Mode.CALL) {
            return Why.CALL;
        }
        return r.othersCapturing ? Why.BUSY : fallback;
    }
}
