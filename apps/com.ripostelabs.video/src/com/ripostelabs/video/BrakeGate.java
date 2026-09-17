package com.ripostelabs.video;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Handbrake gate, as the OEM player does it (decompiled/com.szchoiceway.videoplayer):
 *
 * <pre>
 *   MCU sys frame 0x71, bit 0x04 = brake connected
 *     EventService.java:2348-2352 -> handler 230
 *     EventService.java:536-541   -> SysVar Sys_CurBreakSate = "1" (brake OFF and
 *                                    Set_BreakDetected on) or "0" (brake ON, or
 *                                    detection disabled)
 *     EventService.java:547       -> broadcast MCU_MSG_BRAKE_EVT, no extras
 *   videoplayer VideoPlayerService.java:727 (receiver) and :1062-1070 (ContentObserver)
 *     -> MainActivity.java:233 setBreakState()
 *     -> VideoFragmentUILandscape.java:1136-1140: cover shown while the value is "1"
 * </pre>
 *
 * So "1" means <em>gate</em>. The MCU decides, and the app only mirrors it: the
 * SysVar is re-read on every change signal rather than parsed out of the notify
 * URI or the broadcast. When no provider can be read at all (the emulator) the
 * gate stays open, because a player that blanks forever is useless.
 *
 * On Riposte OS 0.2 there is no eventcenter: the launcher owns the MCU port,
 * replays the broadcast and serves the same row on its own authority
 * (carlauncher SysVarMirrorProvider, RAV4-98). The authorities are tried in
 * order and the first one that answers wins, so stock keeps reading the gateway.
 */
final class BrakeGate {

    interface Listener {
        /** {@code gated} is true while the picture must be covered. */
        void onGateChanged(boolean gated);
    }

    /** Declared in the manifest's {@code <queries>}; invisible otherwise on API 30+. */
    static final String AUTHORITY = "com.szchoiceway.eventcenter.SysVarProvider";
    /** The launcher's mirror of the same table, for Riposte OS 0.2 (RAV4-98). */
    static final String LAUNCHER_AUTHORITY = "com.ripostelabs.carlauncher.sysvar";
    /** Gateway first: on stock both may exist and the gateway's row is the MCU's own. */
    private static final Uri[] SYSVAR_URIS = {
            Uri.parse("content://" + AUTHORITY + "/SysVar"),
            Uri.parse("content://" + LAUNCHER_AUTHORITY + "/SysVar"),
    };
    private static final String COL_KEYNAME = "keyname";
    private static final String COL_KEYVALUE = "keyvalue";
    private static final String SELECT_BY_KEY = COL_KEYNAME + "=?";

    private static final String KEY_CUR_BRAKE_STATE = "Sys_CurBreakSate";
    private static final String VALUE_GATED = "1";

    private static final String ACTION_BRAKE_EVT =
            "com.choiceway.eventcenter.EventUtils.MCU_MSG_BRAKE_EVT";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean gated = false;
    private boolean started = false;

    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange, Uri uri) { evaluate(); }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { evaluate(); }
    };

    BrakeGate(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean isGated() { return gated; }

    /** Subscribe to both signals and publish the current state. */
    void start() {
        if (started) {
            return;
        }
        started = true;

        // registerContentObserver throws SecurityException for an authority this
        // app cannot see (no gateway installed). One observer is enough; none means
        // the broadcast alone drives the re-read.
        for (Uri uri : SYSVAR_URIS) {
            try {
                context.getContentResolver().registerContentObserver(uri, true, observer);
            } catch (Exception ignored) {
            }
        }
        try {
            context.registerReceiver(receiver, new IntentFilter(ACTION_BRAKE_EVT));
        } catch (Exception ignored) {
        }

        evaluate();
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;

        try { context.getContentResolver().unregisterContentObserver(observer); } catch (Exception ignored) {}
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    /** Re-read the SysVar and tell the listener only when the answer moved. */
    private void evaluate() {
        boolean now = VALUE_GATED.equals(readSysVar(KEY_CUR_BRAKE_STATE));
        if (now == gated) {
            return;
        }
        gated = now;
        listener.onGateChanged(gated);
    }

    /** The row's keyvalue from the first authority that has it, or null when none does. */
    private String readSysVar(String key) {
        for (Uri uri : SYSVAR_URIS) {
            String value = readSysVar(uri, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** One provider's answer, or null when it is absent or the key unset there. */
    private String readSysVar(Uri uri, String key) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, new String[] {COL_KEYVALUE},
                    SELECT_BY_KEY, new String[] {key}, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            return c.getString(c.getColumnIndexOrThrow(COL_KEYVALUE));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }
}
