package com.ripostelabs.projection;

import android.content.Context;
import android.content.SharedPreferences;

import com.ripostelabs.projection.ns.Model;
import com.ripostelabs.projection.ns.ModelChoice;
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
    /** A person's pick; absent means automatic. */
    private static final String KEY_MODEL = "ns_model";
    /** The estate's default from the last manifest (ModelUpdater). */
    private static final String KEY_ESTATE = "ns_model_estate";

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

    /** The model the mic runs: a person's pick, else the estate's default, else standard. */
    static Model model(Context context) {
        return ModelChoice.wanted(manual(context), Model.parse(prefs(context).getString(KEY_ESTATE, null), null));
    }

    /** A person's pick, or null for automatic. */
    static Model manual(Context context) {
        return Model.parse(prefs(context).getString(KEY_MODEL, null), null);
    }

    static void setModel(Context context, Model model) {
        prefs(context).edit().putString(KEY_MODEL, model.name()).apply();
    }

    static void setAutomatic(Context context) {
        prefs(context).edit().remove(KEY_MODEL).apply();
    }

    static void setEstateDefault(Context context, Model model) {
        prefs(context).edit().putString(KEY_ESTATE, model.name()).apply();
    }

    private static String enabledKey(Path path) {
        return "ns_" + path.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
