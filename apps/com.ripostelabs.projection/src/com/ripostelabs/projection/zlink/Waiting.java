package com.ripostelabs.projection.zlink;

/**
 * What the CarPlay screen shows until the phone's picture arrives. Pure, so it is unit-tested.
 *
 * <pre>
 *   state + first frame ──▶ picture()  true only for a session with a frame: immersive, no panel
 *   radios + state + time ─▶ reason()  airplane / Bluetooth off / Wi-Fi off / no phone / none
 *   reason ────────────────▶ fix()     the one tap that removes it
 *   state + time ──────────▶ title()   starting / waiting / connecting / disconnected
 * </pre>
 *
 * The screen used to go immersive at once and hide its only line outside WAITING_LINK, and the
 * launcher hides its nav bar over this app. With the radios off the owner saw a black panel
 * with no way out (car, 2026-10-01). Now the panel, with Home, stays until a picture exists.
 */
public final class Waiting {

    /** A connect (or a session with no frame) longer than this is treated as not connected. */
    public static final long CONNECT_TIMEOUT_MS = 30_000L;

    /** Waiting this long with the radios on means the phone was not found. */
    public static final long NO_PHONE_MS = 20_000L;

    public enum Reason { NONE, AIRPLANE, BLUETOOTH_OFF, WIFI_OFF, NO_PHONE }

    public enum Fix { NONE, AIRPLANE_OFF, BLUETOOTH_ON, WIFI_ON }

    public enum Title { STARTING, WAITING, CONNECTING, DISCONNECTED }

    /** The unit's radios as Android reports them. */
    public static final class Radios {
        final boolean bluetooth;
        final boolean wifi;
        final boolean airplane;

        public Radios(boolean bluetooth, boolean wifi, boolean airplane) {
            this.bluetooth = bluetooth;
            this.wifi = wifi;
            this.airplane = airplane;
        }
    }

    private Waiting() {
    }

    /** The phone's picture owns the panel only in a session that has drawn a frame. */
    public static boolean picture(int state, boolean frame) {
        return state == Messages.STATE_SESSION && frame;
    }

    /**
     * The title for a screen with no picture. {@code since} is when the state last changed.
     * A start or connect that runs past {@link #CONNECT_TIMEOUT_MS} reads as waiting again.
     */
    public static Title title(int state, long since, long now) {
        switch (state) {
            case Messages.STATE_WAITING_LINK:
                return Title.WAITING;
            case Messages.STATE_STOPPED:
                return Title.DISCONNECTED;
            case Messages.STATE_WIRELESS_CARPLAY:
            case Messages.STATE_SESSION:
                return timedOut(since, now) ? Title.WAITING : Title.CONNECTING;
            default:
                // A daemon that never reports a state is no reason to promise a start forever.
                return timedOut(since, now) ? Title.WAITING : Title.STARTING;
        }
    }

    /** Why no phone is connected, when the unit can tell. Radios first: they are fixable here. */
    public static Reason reason(Radios radios, int state, long since, long now) {
        if (radios.airplane) {
            return Reason.AIRPLANE;
        }

        if (!radios.bluetooth) {
            return Reason.BLUETOOTH_OFF;
        }

        if (!radios.wifi) {
            return Reason.WIFI_OFF;
        }

        boolean waiting = state == Messages.STATE_WAITING_LINK && now - since >= NO_PHONE_MS;
        boolean connecting = (state == Messages.STATE_WIRELESS_CARPLAY || state == Messages.STATE_SESSION)
                && timedOut(since, now);
        if (waiting || connecting) {
            return Reason.NO_PHONE;
        }

        return Reason.NONE;
    }

    /** The one-tap fix for a reason; a missing phone has none the unit can apply. */
    public static Fix fix(Reason reason) {
        switch (reason) {
            case AIRPLANE:
                return Fix.AIRPLANE_OFF;
            case BLUETOOTH_OFF:
                return Fix.BLUETOOTH_ON;
            case WIFI_OFF:
                return Fix.WIFI_ON;
            default:
                return Fix.NONE;
        }
    }

    private static boolean timedOut(long since, long now) {
        return now - since >= CONNECT_TIMEOUT_MS;
    }
}
