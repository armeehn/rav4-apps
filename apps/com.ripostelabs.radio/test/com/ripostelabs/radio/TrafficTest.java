package com.ripostelabs.radio;

/**
 * Traffic announcements. While the MCU plays one (radio event 0, getRadioTrafficState), the
 * vendor radio drops every radio key it would send (MainActivity.java:541-559,
 * isNeedCancleSend), so a stray tap cannot tune away from the bulletin.
 */
public final class TrafficTest {

    public static void main(String[] args) {
        check(Traffic.holds(Traffic.State.ANNOUNCING, Tuner.KEY_SEEK_UP), "seek is held during a bulletin");
        check(Traffic.holds(Traffic.State.ANNOUNCING, Tuner.KEY_BAND_CYCLE), "bank key is held");
        check(!Traffic.holds(Traffic.State.QUIET, Tuner.KEY_SEEK_UP), "seek passes otherwise");
        check(Traffic.State.of(true) == Traffic.State.ANNOUNCING, "flag set is announcing");
        check(Traffic.State.of(false) == Traffic.State.QUIET, "flag clear is quiet");
        System.out.println("TrafficTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
