package com.ripostelabs.projection.zlink;

import android.content.res.Configuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Call state and night between the daemon and the launcher. The daemon's {@code 0x710}
 * {@code phone_call_state} is decoded here and turned into the OEM app's status broadcasts,
 * which the launcher already folds; night goes the other way as {@code 0x705}/{@code 0x706}.
 */
public final class LauncherStatusTest {

    public static void main(String[] args) {
        idleCallStateDecodes();
        callOnDecodes();
        callEdgesOnly();
        mainAudioEdges();
        sessionDropEndsCall();
        sessionDropWhenIdleSaysNothing();
        nightMessages();
        System.out.println(Check.count + " assertions passed");
    }

    /** Seen idle on the bench as {@code 2=0 3=0 4=-1 5=0 6=0}; the -1 is a ten-byte varint. */
    private static void idleCallStateDecodes() {
        byte[] idle = Check.hex("08 90 0e 10 00 18 00 20 ff ff ff ff ff ff ff ff ff 01 28 00 30 00");
        Messages.CallState c = Messages.callState(idle);
        Check.that(!c.callOn, "no call");
        Check.that(!c.turnByTurn, "no turn-by-turn");
        Check.that(!c.mainAudio, "no main audio");
    }

    private static void callOnDecodes() {
        Messages.CallState c = Messages.callState(Check.hex("08 90 0e 10 01 18 00 28 01"));
        Check.that(c.callOn, "call on");
        Check.that(c.mainAudio, "main audio on");
    }

    /** The daemon repeats its state; the launcher hears each edge once. */
    private static void callEdgesOnly() {
        LauncherStatus s = new LauncherStatus();
        Check.that(s.onCallState(call(true, false)).equals(list(LauncherStatus.CALL_ON)), "call on edge");
        Check.that(s.onCallState(call(true, false)).isEmpty(), "repeat is quiet");
        Check.that(s.onCallState(call(false, false)).equals(list(LauncherStatus.CALL_OFF)), "call off edge");
    }

    private static void mainAudioEdges() {
        LauncherStatus s = new LauncherStatus();
        Check.that(s.onCallState(call(false, true)).equals(list(LauncherStatus.MAIN_AUDIO_START)), "audio start");
        Check.that(s.onCallState(call(true, false))
                .equals(list(LauncherStatus.CALL_ON, LauncherStatus.MAIN_AUDIO_STOP)), "call takes over");
    }

    /**
     * A phone that walks away mid-call never sends call off. Without this the launcher keeps
     * its in-call state until the next CarPlay session.
     */
    private static void sessionDropEndsCall() {
        LauncherStatus s = new LauncherStatus();
        s.onCallState(call(true, true));
        Check.that(s.onSessionDown().equals(list(LauncherStatus.CALL_OFF, LauncherStatus.MAIN_AUDIO_STOP)),
                "session drop closes call and audio");
        Check.that(s.onCallState(call(true, false)).equals(list(LauncherStatus.CALL_ON)),
                "next session starts clean");
    }

    private static void sessionDropWhenIdleSaysNothing() {
        LauncherStatus s = new LauncherStatus();
        Check.that(s.onSessionDown().isEmpty(), "nothing to close");
    }

    /**
     * The launcher mirrors its day/night into the system uiMode ({@code cmd uimode night}), so
     * the service reads it from its configuration. {@code {1: id}}: 0x705 is {@code 08 85 0e},
     * 0x706 {@code 08 86 0e}.
     */
    private static void nightMessages() {
        int night = Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_YES;
        int day = Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO;
        Check.that(LauncherStatus.dayNight(night) == Messages.DayNight.NIGHT, "night uiMode");
        Check.that(LauncherStatus.dayNight(day) == Messages.DayNight.DAY, "day uiMode");
        Check.that(LauncherStatus.dayNight(Configuration.UI_MODE_TYPE_NORMAL) == Messages.DayNight.DAY,
                "undefined is day");
        Check.eq(Messages.NIGHT_START, Messages.nightId(Messages.DayNight.NIGHT), "night id");
        Check.eq(Messages.NIGHT_STOP, Messages.nightId(Messages.DayNight.DAY), "day id");
        Check.bytes(Check.hex("08 85 0e"), Messages.idOnly(Messages.NIGHT_START), "night bytes");
        Check.bytes(Check.hex("08 86 0e"), Messages.idOnly(Messages.NIGHT_STOP), "day bytes");
    }

    private static Messages.CallState call(boolean on, boolean mainAudio) {
        Messages.CallState c = new Messages.CallState();
        c.callOn = on;
        c.mainAudio = mainAudio;
        return c;
    }

    private static List<String> list(String... s) {
        return s.length == 0 ? Collections.emptyList() : Arrays.asList(s);
    }
}
