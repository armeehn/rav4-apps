package com.ripostelabs.soundmeter;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import com.ripostelabs.design.Palette;
import com.ripostelabs.design.MediaCitizen;

/**
 * Clean-room standalone Sound Meter. Samples the microphone's peak amplitude via
 * {@link MediaRecorder#getMaxAmplitude()} on a ~100ms {@link Handler} tick, converts
 * it to an approximate dB SPL value and renders it on a custom {@link GaugeView} with
 * a big digital readout plus running min / avg / max statistics.
 *
 * The amplitude -> dB mapping is intentionally simple and uncalibrated:
 *   dB = 20 * log10(amplitude / FULL_SCALE) + CALIBRATION
 * where FULL_SCALE is the 16-bit PCM peak (32767) and CALIBRATION assumes a
 * full-scale signal corresponds to ~120 dB SPL. The result is clamped to the
 * gauge's 30..120 dB window. Pure framework only, no AndroidX.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;

    // Amplitude -> dB SPL mapping constants.
    private static final double FULL_SCALE = 32767.0;   // 16-bit PCM peak
    private static final float CALIBRATION = 120f;      // dB SPL at full scale
    private static final float SMOOTH = 0.28f;          // low-pass factor
    private static final long TICK_MS = 100;            // sample cadence

    private final Handler ui = new Handler(Looper.getMainLooper());

    private GaugeView gauge;
    private TextView dbView, minView, avgView, maxView, statusView;
    private Button grantBtn, resetBtn;
    private View readoutBox, noMicBox, statusRow;

    private MediaRecorder recorder;
    private boolean sampling = false;

    /** v0.6.1 — exclusive audio focus while sampling, so the meter measures the cabin and not the radio. */
    private MediaCitizen citizen;
    private File sink; // throwaway output; amplitude is read, bytes are discarded

    // Smoothed level + statistics (dB) since start / last reset.
    private float smoothed = Float.NaN;
    private float minDb = Float.NaN, maxDb = Float.NaN;
    private double sum = 0.0;
    private long samples = 0;

    private int cText, cText2, cText3;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!sampling || recorder == null) return;
            int amp = 0;
            try { amp = recorder.getMaxAmplitude(); } catch (Exception ignored) {}
            float db = toDb(amp);
            smoothed = Float.isNaN(smoothed) ? db : smoothed + SMOOTH * (db - smoothed);
            onLevel(smoothed);
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);
        cText3 = Palette.color(this, R.color.text3);

        gauge = findViewById(R.id.gauge);
        dbView = findViewById(R.id.db);
        minView = findViewById(R.id.min);
        avgView = findViewById(R.id.avg);
        maxView = findViewById(R.id.max);
        statusView = findViewById(R.id.status);
        statusRow = findViewById(R.id.statusRow);
        readoutBox = findViewById(R.id.readoutBox);
        noMicBox = findViewById(R.id.noMicBox);
        grantBtn = findViewById(R.id.grant);
        resetBtn = findViewById(R.id.reset);

        resetBtn.setOnClickListener(v -> resetStats());
        grantBtn.setOnClickListener(v -> requestPermissions(
                new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM));

        // Device with no microphone at all: show the graceful fallback.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            showNoMic();
            return;
        }

        if (!hasPerm()) {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM);
        }
    }

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) {
            boolean granted = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                showDenied(false);
                startSampling();
            } else {
                showDenied(true);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (noMicBox.getVisibility() == View.VISIBLE) return;
        if (hasPerm()) {
            showDenied(false);
            startSampling();
        } else {
            showDenied(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSampling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        stopSampling();
        if (citizen != null) {
            citizen.release();
            citizen = null;
        }
    }

    // ---------------- sampling ----------------

    private void startSampling() {
        // Guard first: returning *after* taking exclusive focus would leave the cabin silent
        // with nothing listening, for as long as this screen stays open.
        if (sampling || !hasPerm()) return;

        // Exclusive focus: a ducked radio is still audible, and still ends up in the
        // capture. A refusal means something else already holds the microphone.
        if (citizen == null) {
            citizen = MediaCitizen.attach(this, "soundmeter", new SilentTransport());
        }
        if (!citizen.takeFocus(MediaCitizen.Focus.RECORDING)) {
            return;
        }
        try {
            sink = new File(getCacheDir(), "sm_sink.tmp");
            recorder = (Build.VERSION.SDK_INT >= 31)
                    ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(sink.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException e) {
            releaseRecorder();
            // Nothing is listening, so hand the cabin back rather than hold it silent.
            citizen.releaseFocus();
            // Not fatal: surface a paused state rather than crashing.
            setListening(false);
            return;
        }
        sampling = true;
        // Prime getMaxAmplitude() reference and begin ticking.
        try { recorder.getMaxAmplitude(); } catch (Exception ignored) {}
        setListening(true);
        gauge.setActive(true);
        ui.postDelayed(tick, TICK_MS);
    }

    private void stopSampling() {
        if (citizen != null) {
            citizen.releaseFocus();
        }
        sampling = false;
        ui.removeCallbacks(tick);
        setListening(false);
        gauge.setActive(false);
        releaseRecorder();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (sink != null) { try { sink.delete(); } catch (Exception ignored) {} }
    }

    // ---------------- level + stats ----------------

    private static float toDb(int amplitude) {
        if (amplitude <= 0) return GaugeView.MIN_DB;
        float db = (float) (20.0 * Math.log10(amplitude / FULL_SCALE)) + CALIBRATION;
        if (db < GaugeView.MIN_DB) db = GaugeView.MIN_DB;
        if (db > GaugeView.MAX_DB) db = GaugeView.MAX_DB;
        return db;
    }

    private void onLevel(float db) {
        gauge.setDb(db);
        dbView.setText(String.valueOf(Math.round(db)));
        dbView.setTextColor(gauge.colorFor(db));

        if (Float.isNaN(minDb) || db < minDb) minDb = db;
        if (Float.isNaN(maxDb) || db > maxDb) maxDb = db;
        sum += db;
        samples++;
        float avg = (float) (sum / samples);

        minView.setText(fmt(minDb));
        maxView.setText(fmt(maxDb));
        avgView.setText(fmt(avg));
        gauge.setPeak(maxDb);
    }

    private void resetStats() {
        minDb = Float.NaN;
        maxDb = Float.NaN;
        sum = 0.0;
        samples = 0;
        minView.setText("-- dB");
        avgView.setText("-- dB");
        maxView.setText("-- dB");
        gauge.setPeak(-1f);
    }

    private static String fmt(float db) {
        return Math.round(db) + " dB";
    }

    // ---------------- ui state ----------------

    private void setListening(boolean on) {
        statusView.setText(on ? R.string.listening : R.string.paused);
        statusView.setTextColor(on ? cText : cText2);
    }

    private void showDenied(boolean denied) {
        grantBtn.setVisibility(denied ? View.VISIBLE : View.GONE);
        readoutBox.setVisibility(denied ? View.INVISIBLE : View.VISIBLE);
        if (denied) {
            stopSampling();
            gauge.setDb(GaugeView.MIN_DB);
            gauge.setActive(false);
            dbView.setText("--");
            dbView.setTextColor(cText3);
            statusView.setText(R.string.need_permission);
            statusView.setTextColor(cText2);
        }
    }

    private void showNoMic() {
        noMicBox.setVisibility(View.VISIBLE);
        gauge.setVisibility(View.INVISIBLE);
        readoutBox.setVisibility(View.GONE);
        statusRow.setVisibility(View.GONE);
        grantBtn.setVisibility(View.GONE);
        resetBtn.setEnabled(false);
    }

    /**
     * Capture has no transport to offer: there is nothing for the wheel or the launcher to
     * play, pause or skip. Only the focus half of MediaCitizen is used here.
     */
    private static final class SilentTransport implements MediaCitizen.Transport {
        @Override public void onPlay() { }

        @Override public void onPause() { }

        @Override public void onNext() { }

        @Override public void onPrevious() { }

        @Override public void onStop() { }

        @Override public void onDuck(boolean duck) { }
    }
}
