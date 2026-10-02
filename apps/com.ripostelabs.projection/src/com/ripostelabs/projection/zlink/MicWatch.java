package com.ripostelabs.projection.zlink;

/**
 * RAV4-278: the capture's health, frame by frame. A capture can read exact zeros for a whole
 * call: the platform silences it while a Telecom call holds audio mode IN_CALL, or the HAL route
 * under it dies. A real mic never reads exact zeros for {@link #SILENT_MS} (a quiet cabin peaks
 * at 56-72), so such a run is one SILENCED report, with its cause logged by the caller.
 *
 * <pre>
 *  zeros or platform flag ──▶ SILENCED (once) ──▶ zeros go on, platform not to blame
 *                                  │                  └▶ REOPEN after REOPEN_MS, then every REOPEN_GAP_MS
 *                                  └─ sound again ──▶ RECOVERED (once)
 * </pre>
 *
 * Reopening under the platform's silencing would get another silenced capture, so that case only
 * waits for the call to end. Pure Java, so the policy is unit-tested.
 */
public final class MicWatch {

    /** Exact zeros this long mean silence, not a quiet cabin. */
    public static final int SILENT_MS = 1000;
    /** Zeros this long, the platform not to blame: the route is dead, open a fresh capture. */
    public static final int REOPEN_MS = 3000;
    /** At most one reopen per gap, so a route that stays dead does not churn the HAL. */
    public static final int REOPEN_GAP_MS = 10_000;

    public enum Verdict { OK, SILENCED, RECOVERED, REOPEN }

    private static final long NONE = -1;

    private long zeroSince = NONE;
    private long lastReopen = NONE;
    private boolean reported;

    /**
     * One frame: its loudest sample, the time, and whether the platform says it silences this
     * capture ({@code AudioRecordingConfiguration.isClientSilenced}).
     */
    public Verdict frame(int peak, long nowMs, boolean platformSilenced) {
        if (peak > 0 && !platformSilenced) {
            zeroSince = NONE;
            if (!reported) {
                return Verdict.OK;
            }
            reported = false;
            return Verdict.RECOVERED;
        }

        if (zeroSince == NONE) {
            zeroSince = nowMs;
        }
        long silentFor = nowMs - zeroSince;
        if (!reported && (platformSilenced || silentFor >= SILENT_MS)) {
            reported = true;
            return Verdict.SILENCED;
        }

        if (platformSilenced || silentFor < REOPEN_MS) {
            return Verdict.OK;
        }
        if (lastReopen != NONE && nowMs - lastReopen < REOPEN_GAP_MS) {
            return Verdict.OK;
        }

        lastReopen = nowMs;
        return Verdict.REOPEN;
    }
}
