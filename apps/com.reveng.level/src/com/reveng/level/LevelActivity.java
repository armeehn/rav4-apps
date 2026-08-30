package com.reveng.level;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import com.reveng.design.Palette;

/**
 * Clean-room standalone bubble Level / inclinometer. Reads the device gravity
 * vector from {@link SensorManager}'s {@link Sensor#TYPE_ACCELEROMETER} and
 * derives pitch and roll tilt angles, which drive a custom {@link BubbleLevelView}.
 *
 * Sensor strategy:
 *  - The tilt of a surface is fully determined by the direction of gravity, so
 *    the accelerometer alone is sufficient and, unlike
 *    {@code getRotationMatrix}/{@code getOrientation}, needs no magnetometer
 *    (this head unit has none). When a magnetometer *is* present we still only
 *    need gravity for tilt, so a single accelerometer path is used throughout.
 *  - pitch = atan2(-gy, hypot(gx, gz)), roll = atan2(gx, gz); both are ~0 when
 *    the screen lies flat and face up.
 *  - Values are low-pass smoothed to steady the readout.
 *  - A user "Zero" calibration stores a pitch/roll offset in SharedPreferences.
 *  - If no accelerometer exists, a graceful message is shown.
 *
 * Pure {@code android.*}: no AndroidX, no external libraries. No permissions and
 * no networking are required.
 */
public class LevelActivity extends Activity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean hasSensor;

    private BubbleLevelView level;
    private TextView pitchView, rollView, statusView, sourceView, calibStateView;
    private View calibrateBtn, noSensorBox, statusChip;

    // Smoothed gravity vector (m/s^2) and derived, smoothed tilt (degrees).
    private final float[] gravity = new float[3];
    private boolean haveGravity;
    private float smoothedPitch = 0f, smoothedRoll = 0f;
    private static final float ALPHA = 0.12f;      // tilt low-pass
    private static final float GRAV_ALPHA = 0.20f; // raw gravity low-pass
    private static final float LEVEL_TOL = 1.0f;   // degrees to count as "level"

    // Calibration offset (degrees) persisted between runs.
    private float pitchOffset = 0f, rollOffset = 0f;
    private SharedPreferences prefs;
    private static final String PREFS = "level_prefs";
    private static final String KEY_PITCH = "pitch_offset";
    private static final String KEY_ROLL = "roll_offset";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        level = findViewById(R.id.level);
        pitchView = findViewById(R.id.pitch);
        rollView = findViewById(R.id.roll);
        statusView = findViewById(R.id.status);
        sourceView = findViewById(R.id.source);
        calibStateView = findViewById(R.id.calibState);
        calibrateBtn = findViewById(R.id.calibrateBtn);
        noSensorBox = findViewById(R.id.noSensorBox);
        statusChip = findViewById(R.id.statusChip);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        pitchOffset = prefs.getFloat(KEY_PITCH, 0f);
        rollOffset = prefs.getFloat(KEY_ROLL, 0f);
        updateCalibState();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        hasSensor = accelerometer != null;

        if (!hasSensor) {
            showNoSensor();
        } else {
            sourceView.setText("Accelerometer");
        }

        // Tap to zero the current tilt as the new reference.
        calibrateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { calibrate(); }
        });
        // Long-press clears calibration back to the raw sensor reference.
        calibrateBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { resetCalibration(); return true; }
        });
    }

    private void showNoSensor() {
        noSensorBox.setVisibility(View.VISIBLE);
        level.setVisibility(View.INVISIBLE);
        pitchView.setText("--°");
        rollView.setText("--°");
        statusView.setText("—");
        sourceView.setText("None");
        calibrateBtn.setEnabled(false);
    }

    private void calibrate() {
        if (!hasSensor) return;
        // Make the current pose the new zero by storing the raw smoothed tilt.
        pitchOffset = smoothedPitch;
        rollOffset = smoothedRoll;
        prefs.edit().putFloat(KEY_PITCH, pitchOffset).putFloat(KEY_ROLL, rollOffset).apply();
        updateCalibState();
        render();
    }

    private void resetCalibration() {
        pitchOffset = 0f;
        rollOffset = 0f;
        prefs.edit().remove(KEY_PITCH).remove(KEY_ROLL).apply();
        updateCalibState();
        render();
    }

    private void updateCalibState() {
        boolean calibrated = pitchOffset != 0f || rollOffset != 0f;
        calibStateView.setText(calibrated ? getString(R.string.calibrated) : "Raw");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasSensor || sensorManager == null) return;
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        // Low-pass the raw accelerometer to isolate the steady gravity vector.
        for (int i = 0; i < 3; i++) {
            gravity[i] = gravity[i] + GRAV_ALPHA * (event.values[i] - gravity[i]);
        }
        haveGravity = true;

        float gx = gravity[0], gy = gravity[1], gz = gravity[2];
        // Tilt of the screen plane relative to horizontal; ~0 when flat, face up.
        float pitch = (float) Math.toDegrees(Math.atan2(-gy, Math.hypot(gx, gz)));
        float roll = (float) Math.toDegrees(Math.atan2(gx, gz));

        smoothedPitch = smoothedPitch + ALPHA * (pitch - smoothedPitch);
        smoothedRoll = smoothedRoll + ALPHA * (roll - smoothedRoll);

        render();
    }

    private void render() {
        if (!haveGravity) return;
        float p = smoothedPitch - pitchOffset;
        float r = smoothedRoll - rollOffset;
        boolean isLevel = Math.abs(p) <= LEVEL_TOL && Math.abs(r) <= LEVEL_TOL;

        level.setTilt(p, r, isLevel, LEVEL_TOL);

        pitchView.setText(formatDeg(p));
        rollView.setText(formatDeg(r));

        if (isLevel) {
            statusView.setText(R.string.level_label);
            statusView.setTextColor(Palette.color(this, R.color.accent));
            statusChip.setBackgroundResource(R.drawable.bg_status_level);
        } else {
            statusView.setText("Tilted");
            statusView.setTextColor(Palette.color(this, R.color.text2));
            statusChip.setBackgroundResource(R.drawable.bg_status);
        }
    }

    /** One-decimal signed degrees, e.g. "+0.3°" / "-12.4°". */
    private static String formatDeg(float deg) {
        if (Math.abs(deg) < 0.05f) deg = 0f; // avoid "-0.0"
        return String.format("%+.1f°", deg);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Accelerometer accuracy does not affect a tilt reading here.
    }
}
