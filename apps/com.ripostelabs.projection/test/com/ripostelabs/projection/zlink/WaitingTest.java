package com.ripostelabs.projection.zlink;

/**
 * The CarPlay screen before the phone's picture: what it says, why, the fix it offers, and
 * that it never takes the whole panel until a picture exists (car, 2026-10-01: the owner was
 * stuck on a black CarPlay screen with the radios off).
 */
public final class WaitingTest {

    private static final Waiting.Radios ALL_ON = new Waiting.Radios(true, true, false);
    private static final long T0 = 1_000_000L;

    public static void main(String[] args) {
        onlyAPictureHidesThePanel();
        everyStateWithoutAPictureShowsThePanel();
        bluetoothOffIsTheReasonWithItsFix();
        wifiOffIsTheReasonWithItsFix();
        airplaneModeComesFirst();
        radiosOnWhileWaitingHasNoReasonYet();
        aConnectThatNeverFinishesFallsBackToWaiting();
        aSessionWithoutAFrameTimesOutToo();
        theTitleFollowsTheState();
        System.out.println(Check.count + " assertions passed");
    }

    private static void onlyAPictureHidesThePanel() {
        Check.that(Waiting.picture(Messages.STATE_SESSION, true), "session with a frame shows the picture");
        Check.that(!Waiting.picture(Messages.STATE_SESSION, false), "session without a frame keeps the panel");
        Check.that(!Waiting.picture(Messages.STATE_STOPPED, true), "a dropped session keeps no stale picture");
    }

    private static void everyStateWithoutAPictureShowsThePanel() {
        int[] states = {
            Messages.STATE_WAIT_INIT, Messages.STATE_WAITING_LINK,
            Messages.STATE_WIRELESS_CARPLAY, Messages.STATE_STOPPED, 0, 99,
        };
        for (int s : states) {
            Check.that(!Waiting.picture(s, true), "state " + s + " is never immersive");
        }
    }

    private static void bluetoothOffIsTheReasonWithItsFix() {
        Waiting.Radios r = new Waiting.Radios(false, true, false);
        Waiting.Reason why = Waiting.reason(r, Messages.STATE_WAITING_LINK, T0, T0);
        Check.that(why == Waiting.Reason.BLUETOOTH_OFF, "bluetooth off: " + why);
        Check.that(Waiting.fix(why) == Waiting.Fix.BLUETOOTH_ON, "fix turns bluetooth on");
    }

    private static void wifiOffIsTheReasonWithItsFix() {
        Waiting.Radios r = new Waiting.Radios(true, false, false);
        Waiting.Reason why = Waiting.reason(r, Messages.STATE_WAITING_LINK, T0, T0);
        Check.that(why == Waiting.Reason.WIFI_OFF, "wifi off: " + why);
        Check.that(Waiting.fix(why) == Waiting.Fix.WIFI_ON, "fix turns wifi on");
    }

    /** Airplane mode turns both off; turning one on alone would not bring the other back. */
    private static void airplaneModeComesFirst() {
        Waiting.Radios r = new Waiting.Radios(false, false, true);
        Waiting.Reason why = Waiting.reason(r, Messages.STATE_WAITING_LINK, T0, T0);
        Check.that(why == Waiting.Reason.AIRPLANE, "airplane: " + why);
        Check.that(Waiting.fix(why) == Waiting.Fix.AIRPLANE_OFF, "fix leaves airplane mode");
    }

    private static void radiosOnWhileWaitingHasNoReasonYet() {
        Waiting.Reason why = Waiting.reason(ALL_ON, Messages.STATE_WAITING_LINK, T0, T0 + 1_000);
        Check.that(why == Waiting.Reason.NONE, "no reason in the first seconds: " + why);
        Check.that(Waiting.fix(why) == Waiting.Fix.NONE, "no fix without a reason");

        long late = T0 + Waiting.NO_PHONE_MS;
        Check.that(Waiting.reason(ALL_ON, Messages.STATE_WAITING_LINK, T0, late) == Waiting.Reason.NO_PHONE,
                "a long wait says no phone was found");
    }

    private static void aConnectThatNeverFinishesFallsBackToWaiting() {
        long early = T0 + Waiting.CONNECT_TIMEOUT_MS - 1;
        long late = T0 + Waiting.CONNECT_TIMEOUT_MS;
        Check.that(Waiting.title(Messages.STATE_WIRELESS_CARPLAY, T0, early) == Waiting.Title.CONNECTING,
                "connecting inside the timeout");
        Check.that(Waiting.title(Messages.STATE_WIRELESS_CARPLAY, T0, late) == Waiting.Title.WAITING,
                "back to waiting after the timeout");
        Check.that(Waiting.reason(ALL_ON, Messages.STATE_WIRELESS_CARPLAY, T0, late) == Waiting.Reason.NO_PHONE,
                "a timed-out connect says why");
    }

    private static void aSessionWithoutAFrameTimesOutToo() {
        long late = T0 + Waiting.CONNECT_TIMEOUT_MS;
        Check.that(Waiting.title(Messages.STATE_SESSION, T0, T0) == Waiting.Title.CONNECTING, "session, no frame yet");
        Check.that(Waiting.title(Messages.STATE_SESSION, T0, late) == Waiting.Title.WAITING, "session, no frame for long");
    }

    private static void theTitleFollowsTheState() {
        Check.that(Waiting.title(Messages.STATE_WAIT_INIT, T0, T0) == Waiting.Title.STARTING, "starting");
        Check.that(Waiting.title(Messages.STATE_WAIT_INIT, T0, T0 + Waiting.CONNECT_TIMEOUT_MS) == Waiting.Title.WAITING,
                "a start that never finishes reads as waiting");
        Check.that(Waiting.title(Messages.STATE_WAITING_LINK, T0, T0) == Waiting.Title.WAITING, "waiting");
        Check.that(Waiting.title(Messages.STATE_STOPPED, T0, T0) == Waiting.Title.DISCONNECTED, "disconnected");
    }
}
