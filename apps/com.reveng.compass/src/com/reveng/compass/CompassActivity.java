package com.reveng.compass;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * Clean-room standalone Compass. Reads device orientation from motion sensors via
 * {@link SensorManager} and renders a live heading on a custom {@link CompassView}.
 *
 * Sensor strategy:
 *  - Prefer {@link Sensor#TYPE_ROTATION_VECTOR}: fused, drift-corrected, needs no
 *    manual gravity/geomagnetic pairing. Azimuth comes from
 *    {@code getRotationMatrixFromVector} + {@code getOrientation}.
 *  - Fall back to raw {@link Sensor#TYPE_ACCELEROMETER} +
 *    {@link Sensor#TYPE_MAGNETIC_FIELD}, combined with
 *    {@code getRotationMatrix} + {@code getOrientation}.
 *  - If neither path is available (no magnetometer), show a graceful message.
 *
 * No permissions and no networking are required.
 */
public class CompassActivity extends Activity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor rotationVector, accelerometer, magnetometer;
    private boolean useRotationVector;
    private boolean hasCompass;

    private CompassView compass;
    private TextView degreesView, cardinalView, pitchView, rollView, sourceView, accuracyView;
    private View calibrateBox, noSensorBox;

    // Raw sensor buffers for the fallback (accelerometer + magnetometer) path.
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private boolean haveGravity, haveGeomagnetic;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    // Low-pass smoothing state (degrees). Smoothed across the 0/360 wrap.
    private float smoothedAzimuth = Float.NaN;
    private float smoothedPitch = 0f, smoothedRoll = 0f;
    private static final float ALPHA = 0.15f;

    private static final String[] CARD8 =
            { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        compass = findViewById(R.id.compass);
        degreesView = findViewById(R.id.degrees);
        cardinalView = findViewById(R.id.cardinal);
        pitchView = findViewById(R.id.pitch);
        rollView = findViewById(R.id.roll);
        sourceView = findViewById(R.id.source);
        accuracyView = findViewById(R.id.accuracy);
        calibrateBox = findViewById(R.id.calibrateBox);
        noSensorBox = findViewById(R.id.noSensorBox);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        // Rotation vector is only trustworthy as a compass when a magnetometer
        // backs it; without a mag sensor, fall back or declare "no compass".
        useRotationVector = rotationVector != null && magnetometer != null;
        hasCompass = useRotationVector || (accelerometer != null && magnetometer != null);

        if (!hasCompass) {
            showNoSensor();
        } else {
            sourceView.setText(useRotationVector ? "Rotation vector" : "Accel + Mag");
        }
    }

    private void showNoSensor() {
        noSensorBox.setVisibility(View.VISIBLE);
        compass.setVisibility(View.INVISIBLE);
        degreesView.setText("--°");
        cardinalView.setText("—");
        sourceView.setText("None");
        accuracyView.setText("Unavailable");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasCompass || sensorManager == null) return;
        if (useRotationVector) {
            sensorManager.registerListener(this, rotationVector,
                    SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, magnetometer,
                    SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        boolean ready = false;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                ready = true;
                break;
            case Sensor.TYPE_ACCELEROMETER:
                lowPassCopy(event.values, gravity);
                haveGravity = true;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                lowPassCopy(event.values, geomagnetic);
                haveGeomagnetic = true;
                break;
            default:
                return;
        }

        if (!useRotationVector) {
            if (!haveGravity || !haveGeomagnetic) return;
            if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                return;
            }
            ready = true;
        }
        if (!ready) return;

        SensorManager.getOrientation(rotationMatrix, orientation);
        float azimuth = (float) Math.toDegrees(orientation[0]); // -180..180, 0 = N
        if (azimuth < 0) azimuth += 360f;                        // 0..360
        float pitch = (float) Math.toDegrees(orientation[1]);
        float roll = (float) Math.toDegrees(orientation[2]);

        smoothedAzimuth = smoothAngle(smoothedAzimuth, azimuth);
        smoothedPitch = smoothedPitch + ALPHA * (pitch - smoothedPitch);
        smoothedRoll = smoothedRoll + ALPHA * (roll - smoothedRoll);

        render(smoothedAzimuth, smoothedPitch, smoothedRoll);
    }

    private void render(float azimuth, float pitch, float roll) {
        compass.setAzimuth(azimuth);
        int deg = Math.round(azimuth) % 360;
        if (deg < 0) deg += 360;
        degreesView.setText(deg + "°");
        cardinalView.setText(cardinal8(azimuth));
        pitchView.setText(Math.round(pitch) + "°");
        rollView.setText(Math.round(roll) + "°");
    }

    /** 8-point cardinal/intercardinal name for an azimuth in degrees. */
    private static String cardinal8(float deg) {
        int idx = Math.round(deg / 45f) % 8;
        if (idx < 0) idx += 8;
        return CARD8[idx];
    }

    /** Exponential low-pass over the shortest arc, so the 0/360 seam is smooth. */
    private static float smoothAngle(float prev, float next) {
        if (Float.isNaN(prev)) return next;
        float diff = next - prev;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        float out = prev + ALPHA * diff;
        out %= 360f;
        if (out < 0) out += 360f;
        return out;
    }

    /** Light smoothing of raw accel/mag vectors to steady the fallback path. */
    private static void lowPassCopy(float[] src, float[] dst) {
        final float a = 0.2f;
        for (int i = 0; i < 3; i++) dst[i] = dst[i] + a * (src[i] - dst[i]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Only the magnetic field / fused heading accuracy is meaningful here.
        if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) return;

        String label;
        boolean low;
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                label = "High"; low = false; break;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                label = "Medium"; low = false; break;
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                label = "Low"; low = true; break;
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
            default:
                label = "Unreliable"; low = true; break;
        }
        accuracyView.setText(label);
        calibrateBox.setVisibility(low ? View.VISIBLE : View.GONE);
    }
}
