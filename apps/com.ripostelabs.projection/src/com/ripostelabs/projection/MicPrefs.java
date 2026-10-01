package com.ripostelabs.projection;

import android.content.Context;
import android.content.SharedPreferences;

import com.ripostelabs.projection.ns.Strength;

/**
 * The microphone settings this app owns, one switch per mic path. Read at every MicStart, so a
 * change applies from the next Siri request or call.
 */
final class MicPrefs {

    /** The mic paths this app captures for. Bluetooth hands-free calls are the launcher's. */
    enum Path {
        CARPLAY
    }

    /** Best PESQ-WB of the three on speech in car-interior noise at 0, 5 and 10 dB SNR. */
    static final Strength DEFAULT_STRENGTH = Strength.MEDIUM;

    private static final String FILE = "mic";
    private static final String KEY_STRENGTH = "ns_strength";

    private MicPrefs() {
    }

    static boolean suppress(Context context, Path path) {
        return prefs(context).getBoolean(enabledKey(path), true);
    }

    static void setSuppress(Context context, Path path, boolean on) {
        prefs(context).edit().putBoolean(enabledKey(path), on).apply();
    }

    static Strength strength(Context context) {
        return Strength.parse(prefs(context).getString(KEY_STRENGTH, null), DEFAULT_STRENGTH);
    }

    static void setStrength(Context context, Strength strength) {
        prefs(context).edit().putString(KEY_STRENGTH, strength.name()).apply();
    }

    private static String enabledKey(Path path) {
        return "ns_" + path.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
