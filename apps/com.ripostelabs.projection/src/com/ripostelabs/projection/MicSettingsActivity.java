package com.ripostelabs.projection;

import android.app.Activity;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.ripostelabs.design.Palette;
import com.ripostelabs.projection.ns.RnNoise;
import com.ripostelabs.projection.ns.Strength;

/**
 * Noise suppression for the CarPlay mic: on or off, and how deep. The capture reads these at
 * every MicStart, so nothing here talks to a running capture.
 */
public final class MicSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mic);
        Palette.apply(this);

        Switch carplay = findViewById(R.id.ns_carplay);
        RadioGroup strength = findViewById(R.id.strength);
        TextView engine = findViewById(R.id.engine_state);

        carplay.setChecked(MicPrefs.suppress(this, MicPrefs.Path.CARPLAY));
        carplay.setOnCheckedChangeListener((button, on) -> {
            MicPrefs.setSuppress(this, MicPrefs.Path.CARPLAY, on);
            strength.setEnabled(on);
            enableChildren(strength, on);
        });

        strength.check(idFor(MicPrefs.strength(this)));
        strength.setOnCheckedChangeListener((group, id) -> MicPrefs.setStrength(this, strengthFor(id)));
        enableChildren(strength, carplay.isChecked());

        // A missing library is not an error the driver can fix, but it explains a mic that
        // sounds unchanged with the switch on.
        engine.setText(RnNoise.available() ? R.string.mic_engine_ready : R.string.mic_engine_missing);
    }

    private static void enableChildren(RadioGroup group, boolean on) {
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(on);
        }
    }

    private static int idFor(Strength s) {
        switch (s) {
            case LIGHT:
                return R.id.strength_light;
            case FULL:
                return R.id.strength_full;
            default:
                return R.id.strength_medium;
        }
    }

    private static Strength strengthFor(int id) {
        if (id == R.id.strength_light) {
            return Strength.LIGHT;
        }
        if (id == R.id.strength_full) {
            return Strength.FULL;
        }
        return Strength.MEDIUM;
    }
}
