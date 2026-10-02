package com.ripostelabs.projection.zlink;

/**
 * RAV4-278: the capture can read exact zeros for a whole call. The platform silences it during
 * a Telecom call (audio mode IN_CALL), or the HAL route under it goes dead. A real mic never
 * reads exact zeros for a second, so a run of them is reported once with its cause; a dead route
 * (not the platform's doing) is reopened, at most once per gap; sound coming back is reported.
 */
public final class MicWatchTest {

    private static final int FRAME_MS = 20;

    public static void main(String[] args) {
        soundIsQuiet();
        aQuietCabinIsNotSilence();
        zerosForASecondAreReportedOnce();
        thePlatformFlagReportsAtOnce();
        soundComingBackIsReported();
        deadRouteIsReopened();
        platformSilencingIsNeverReopened();
        reopensAreSpacedOut();
        System.out.println(Check.count + " assertions passed");
    }

    private static void soundIsQuiet() {
        MicWatch w = new MicWatch();
        Check.that(w.frame(4000, 0, false) == MicWatch.Verdict.OK, "sound is OK");
    }

    /** The car's quiet cabin reads a peak of 56-72, never 0 (logs 2026-10-01 15:29). */
    private static void aQuietCabinIsNotSilence() {
        MicWatch w = new MicWatch();
        Check.that(run(w, 56, 0, 5000, false) == 0, "no verdict for a quiet cabin");
    }

    private static void zerosForASecondAreReportedOnce() {
        MicWatch w = new MicWatch();
        Check.that(w.frame(0, 0, false) == MicWatch.Verdict.OK, "one zero frame is nothing");
        Check.that(w.frame(0, MicWatch.SILENT_MS - FRAME_MS, false) == MicWatch.Verdict.OK, "not yet");
        Check.that(w.frame(0, MicWatch.SILENT_MS, false) == MicWatch.Verdict.SILENCED, "a second of zeros");
        Check.that(w.frame(0, MicWatch.SILENT_MS + FRAME_MS, false) == MicWatch.Verdict.OK, "reported once");
    }

    /** AudioRecordingConfiguration.isClientSilenced needs no waiting. */
    private static void thePlatformFlagReportsAtOnce() {
        MicWatch w = new MicWatch();
        Check.that(w.frame(0, 0, true) == MicWatch.Verdict.SILENCED, "flag reports at once");
    }

    private static void soundComingBackIsReported() {
        MicWatch w = new MicWatch();
        w.frame(0, 0, true);
        Check.that(w.frame(3000, 500, false) == MicWatch.Verdict.RECOVERED, "sound back");
        Check.that(w.frame(3000, 520, false) == MicWatch.Verdict.OK, "recovered once");
    }

    private static void deadRouteIsReopened() {
        MicWatch w = new MicWatch();
        Check.eq(1, run(w, 0, 0, MicWatch.REOPEN_MS, false), "one reopen after the wait");
    }

    /** Reopening under a Telecom call gets another silenced capture; wait for the call to end. */
    private static void platformSilencingIsNeverReopened() {
        MicWatch w = new MicWatch();
        Check.eq(0, run(w, 0, 0, 60_000, true), "no reopen while the platform silences");
    }

    private static void reopensAreSpacedOut() {
        MicWatch w = new MicWatch();
        Check.eq(1, run(w, 0, 0, MicWatch.REOPEN_GAP_MS - FRAME_MS, false), "one reopen inside the gap");
        Check.eq(1, run(w, 0, MicWatch.REOPEN_GAP_MS, MicWatch.REOPEN_GAP_MS + MicWatch.REOPEN_MS, false),
                "one more after it");
    }

    /** Feed frames of {@code peak} from {@code from} through {@code to} ms; count REOPEN verdicts. */
    private static int run(MicWatch w, int peak, long from, long to, boolean silenced) {
        int reopens = 0;
        for (long t = from; t <= to; t += FRAME_MS) {
            MicWatch.Verdict v = w.frame(peak, t, silenced);
            if (v == MicWatch.Verdict.REOPEN) {
                reopens++;
            }
            if (peak > 0 && v != MicWatch.Verdict.OK) {
                return -1;
            }
        }
        return reopens;
    }
}
