package com.ripostelabs.projection;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.ripostelabs.design.Palette;
import com.ripostelabs.projection.ns.Aec3;
import com.ripostelabs.projection.ns.Manifest;
import com.ripostelabs.projection.ns.Model;
import com.ripostelabs.projection.ns.ModelChoice;
import com.ripostelabs.projection.ns.ModelStore;
import com.ripostelabs.projection.ns.Pickup;
import com.ripostelabs.projection.ns.RnNoise;
import com.ripostelabs.projection.ns.Strength;

/**
 * The CarPlay mic: which pickup, echo cancellation and noise suppression on or off, how deep,
 * and which model. A running capture reopens on any change here (ZlinkService watches
 * MicPrefs), so a mid-call switch is heard at once.
 */
public final class MicSettingsActivity extends Activity {

    /** How often the call audio check's line refreshes while the screen is open. */
    private static final long CHECK_TICK_MS = 500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView checkState;
    private final Runnable checkTick = new Runnable() {
        @Override
        public void run() {
            showCheck();
            ui.postDelayed(this, CHECK_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mic);
        Palette.apply(this);

        RadioGroup pickup = findViewById(R.id.pickup);
        Switch echo = findViewById(R.id.aec_carplay);
        Switch carplay = findViewById(R.id.ns_carplay);
        RadioGroup strength = findViewById(R.id.strength);
        RadioGroup model = findViewById(R.id.model);
        TextView modelState = findViewById(R.id.model_state);
        TextView engine = findViewById(R.id.engine_state);

        pickup.check(MicPrefs.pickup(this) == Pickup.PLATFORM ? R.id.pickup_platform : R.id.pickup_direct);
        pickup.setOnCheckedChangeListener((group, id) -> MicPrefs.setPickup(this,
                id == R.id.pickup_platform ? Pickup.PLATFORM : Pickup.DIRECT));

        echo.setChecked(MicPrefs.echoCancel(this));
        echo.setOnCheckedChangeListener((button, on) -> MicPrefs.setEchoCancel(this, on));

        carplay.setChecked(MicPrefs.suppress(this, MicPrefs.Path.CARPLAY));
        carplay.setOnCheckedChangeListener((button, on) -> {
            MicPrefs.setSuppress(this, MicPrefs.Path.CARPLAY, on);
            strength.setEnabled(on);
            enableChildren(strength, on);
            enableChildren(model, on);
        });

        strength.check(idFor(MicPrefs.strength(this)));
        strength.setOnCheckedChangeListener((group, id) -> MicPrefs.setStrength(this, strengthFor(id)));
        enableChildren(strength, carplay.isChecked());

        model.check(idFor(MicPrefs.manual(this)));
        showModel(modelState);
        model.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.model_auto) {
                MicPrefs.setAutomatic(this);
            } else {
                MicPrefs.setModel(this, id == R.id.model_car ? Model.CAR_TUNED : Model.STANDARD);
            }
            // Automatic and Car-tuned fetch the newest manifest now rather than at the next daily run.
            if (ModelChoice.checksForUpdates(MicPrefs.manual(this))) {
                ModelUpdater.schedule(this);
            }
            showModel(modelState);
        });
        enableChildren(model, carplay.isChecked());

        // A missing library is not an error the driver can fix, but it explains a mic that
        // sounds unchanged with the switch on.
        engine.setText(getString(RnNoise.available() ? R.string.mic_engine_ready : R.string.mic_engine_missing)
                + "\n" + getString(Aec3.available() ? R.string.mic_aec_ready : R.string.mic_aec_missing));

        // The call audio check: owner-initiated only, and loud about it while it records.
        checkState = findViewById(R.id.callcheck_state);
        Button check = findViewById(R.id.callcheck);
        check.setOnClickListener(v -> startCheck());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(checkTick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(checkTick);
        super.onPause();
    }

    private void startCheck() {
        switch (CallCheckRecorder.get().start(this)) {
            case NO_CALL:
                checkState.setText(R.string.callcheck_no_call);
                return;
            case BUSY:
                checkState.setText(R.string.callcheck_busy);
                return;
            default:
                showCheck();
        }
    }

    /** Red while recording, the folder once saved; a refusal's text stays until the next tick changes state. */
    private void showCheck() {
        CallCheckRecorder r = CallCheckRecorder.get();
        if (r.recording()) {
            checkState.setText(getString(R.string.callcheck_left, r.secondsLeft()));
            checkState.setTextColor(Palette.color(this, R.color.error));
            return;
        }

        checkState.setTextColor(Palette.color(this, R.color.text2));
        String folder = r.lastFolder();
        if (folder != null) {
            checkState.setText(getString(R.string.callcheck_saved, folder));
        }
    }

    /** The radio for a person's pick; no pick is Automatic. */
    private static int idFor(Model manual) {
        if (manual == null) {
            return R.id.model_auto;
        }
        return manual == Model.CAR_TUNED ? R.id.model_car : R.id.model_standard;
    }

    /** Which model the next capture runs, with the car-tuned version when there is one. */
    private void showModel(TextView state) {
        if (MicPrefs.model(this) != Model.CAR_TUNED) {
            state.setText(MicPrefs.manual(this) == null ? R.string.mic_model_auto_standard_state
                    : R.string.mic_model_standard_state);
            return;
        }
        ModelStore store = ModelUpdater.store(this);
        int active = store.activeVersion();
        int pending = store.pendingVersion();
        if (active == 0 && pending == 0) {
            state.setText(R.string.mic_model_car_none);
            return;
        }
        if (active == 0) {
            state.setText(getString(R.string.mic_model_car_next, Manifest.label(pending)));
            return;
        }
        if (pending != 0) {
            state.setText(getString(R.string.mic_model_car_pending, Manifest.label(active), Manifest.label(pending)));
            return;
        }
        state.setText(getString(R.string.mic_model_car_active, Manifest.label(active)));
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
