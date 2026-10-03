package com.ripostelabs.recorder;

/**
 * A take the platform silences must not be saved as a silent file. On the car (2026-10-01)
 * a phone call put the unit in call mode, the mic gave zeros, and the Recorder saved them
 * with no word: the busy toast only fires when focus is refused, and a silenced capture is
 * granted focus and starts "fine".
 */
public final class MicSilenceTest {

    private static int count;

    public static void main(String[] args) {
        callModeRefusesBeforeTheTake();
        idleModeLetsTheTakeStart();
        platformSilenceStopsAtOnce();
        silenceInCallSaysCall();
        silenceWithAnotherCaptureSaysBusy();
        shortZeroRunIsTolerated();
        longZeroRunStops();
        soundResetsTheZeroRun();
        System.out.println(count + " assertions passed");
    }

    private static void callModeRefusesBeforeTheTake() {
        eq(MicSilence.Why.CALL, MicSilence.before(MicSilence.Mode.CALL), "call mode");
    }

    private static void idleModeLetsTheTakeStart() {
        eq(MicSilence.Why.NONE, MicSilence.before(MicSilence.Mode.NORMAL), "normal mode");
    }

    private static void platformSilenceStopsAtOnce() {
        MicSilence s = new MicSilence(0);
        eq(MicSilence.Why.SILENCED, s.check(reading(0, true, MicSilence.Mode.NORMAL, false), 90), "silenced flag");
    }

    private static void silenceInCallSaysCall() {
        MicSilence s = new MicSilence(0);
        eq(MicSilence.Why.CALL, s.check(reading(0, true, MicSilence.Mode.CALL, true), 90), "call wins");
    }

    private static void silenceWithAnotherCaptureSaysBusy() {
        MicSilence s = new MicSilence(0);
        eq(MicSilence.Why.BUSY, s.check(reading(0, true, MicSilence.Mode.NORMAL, true), 90), "CarPlay mic");
    }

    private static void shortZeroRunIsTolerated() {
        // The first amplitude read is always 0, and the encoder takes a moment to start.
        MicSilence s = new MicSilence(0);
        eq(MicSilence.Why.NONE, s.check(reading(0, false, MicSilence.Mode.NORMAL, false), MicSilence.ZERO_LIMIT_MS - 1), "under the limit");
    }

    private static void longZeroRunStops() {
        MicSilence s = new MicSilence(0);
        eq(MicSilence.Why.SILENT, s.check(reading(0, false, MicSilence.Mode.NORMAL, false), MicSilence.ZERO_LIMIT_MS), "at the limit");
    }

    private static void soundResetsTheZeroRun() {
        MicSilence s = new MicSilence(0);
        long half = MicSilence.ZERO_LIMIT_MS / 2;
        s.check(reading(0, false, MicSilence.Mode.NORMAL, false), half);
        s.check(reading(12, false, MicSilence.Mode.NORMAL, false), half + 1);
        eq(MicSilence.Why.NONE, s.check(reading(0, false, MicSilence.Mode.NORMAL, false), MicSilence.ZERO_LIMIT_MS + 1), "run restarted");
    }

    private static MicSilence.Reading reading(int peak, boolean silenced, MicSilence.Mode mode, boolean others) {
        return new MicSilence.Reading(peak, silenced, mode, others);
    }

    private static void eq(Object want, Object got, String what) {
        count++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want " + want + ", got " + got);
        }
    }
}
