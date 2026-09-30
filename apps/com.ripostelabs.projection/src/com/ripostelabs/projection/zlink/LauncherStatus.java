package com.ripostelabs.projection.zlink;

import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * What the launcher hears about a CarPlay call and the phone's main stream. The daemon repeats
 * {@link Messages#CALL_STATE} while nothing changes; the launcher (device-reveng
 * {@code carlib/Zlink.kt}) folds the OEM app's status strings, so each edge is sent once.
 *
 * <pre>
 *   0x710 callOn 0→1   ──▶ PHONE_CALL_ON
 *   0x710 callOn 1→0   ──▶ PHONE_CALL_OFF
 *   session down, call still on ──▶ PHONE_CALL_OFF   (the phone left mid-call and never said so)
 * </pre>
 *
 * Pure Java apart from two compile-time constants, so the edges are unit-tested.
 */
public final class LauncherStatus {

    public static final String CALL_ON = "PHONE_CALL_ON";
    public static final String CALL_OFF = "PHONE_CALL_OFF";
    public static final String MAIN_AUDIO_START = "MAIN_AUDIO_START";
    public static final String MAIN_AUDIO_STOP = "MAIN_AUDIO_STOP";

    private boolean callOn;
    private boolean mainAudio;

    /** The status strings this update changes, in order; empty when it repeats the last one. */
    public List<String> onCallState(Messages.CallState c) {
        List<String> out = new ArrayList<>();
        if (c.callOn != callOn) {
            callOn = c.callOn;
            out.add(callOn ? CALL_ON : CALL_OFF);
        }
        if (c.mainAudio != mainAudio) {
            mainAudio = c.mainAudio;
            out.add(mainAudio ? MAIN_AUDIO_START : MAIN_AUDIO_STOP);
        }
        return out;
    }

    /** Close whatever the phone left open, so the next session starts from idle. */
    public List<String> onSessionDown() {
        return onCallState(new Messages.CallState());
    }

    /** The unit's day or night from a configuration's uiMode; undefined counts as day. */
    public static Messages.DayNight dayNight(int uiMode) {
        boolean night = (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? Messages.DayNight.NIGHT : Messages.DayNight.DAY;
    }
}
