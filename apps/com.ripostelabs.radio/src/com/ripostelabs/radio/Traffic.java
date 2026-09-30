package com.ripostelabs.radio;

/**
 * RDS traffic announcements. While one plays (radio event 0, getRadioTrafficState) the vendor
 * radio drops every radio key it would send (isNeedCancleSend, MainActivity.java:541-559), so a
 * stray tap cannot tune away from the bulletin. The MCU ends the announcement itself.
 */
final class Traffic {

    enum State {
        QUIET, ANNOUNCING;

        static State of(boolean flag) {
            return flag ? ANNOUNCING : QUIET;
        }
    }

    private Traffic() {}

    /** True when [key] must not reach the tuner: every key, while a bulletin plays. */
    static boolean holds(State state, int key) {
        return state == State.ANNOUNCING;
    }
}
