package com.ripostelabs.projection;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.ripostelabs.projection.ns.MicLevel;
import com.ripostelabs.projection.ns.Pickup;
import com.ripostelabs.projection.ns.Wav;

/**
 * The mic source test: {@link #SECONDS_EACH} s from each {@link Pickup} in turn while the owner
 * talks, then the peak and RMS of each. The source that carries the cabin mic reads loudest.
 *
 * <pre>
 *   DIRECT ─3 s─▶ RECOGNITION ─3 s─▶ UNPROCESSED ─3 s─▶ PLATFORM ─3 s─▶ report (screen + logcat)
 *      └── each slot also saved as Riposte/MicProbe/<stamp>-<pickup>.wav
 * </pre>
 *
 * The WAVs carry what a level cannot: the frequency balance. On the bench (2026-10-07) every
 * source recorded speech 10 to 20 dB dark above 1 kHz, which only the saved audio showed.
 *
 * Why: the plain mic's calls peaked near -35 dBFS and sounded underwater (car, 2026-10-02
 * 19:06), the vendor path's near -6 dBFS with its filter on. Which HAL route reaches the mic
 * cleanly cannot be read off the car; this measures it. Each line also goes to logcat under
 * {@code Projection: mic probe}, which the next plug-in's log pull carries home.
 */
final class MicProbe {

    interface Listener {
        /** Now recording {@code pickup}; {@code left} sources after it. */
        void onSource(Pickup pickup, int left);

        void onDone(String report);
    }

    static final int SECONDS_EACH = 3;

    private static final String TAG = "Projection";
    private static final int RATE = 16000;
    private static final String DIR = "Riposte/MicProbe";
    private static final String APP_DIR = "MicProbe";
    /** Skipped at each start: the route switch and the ADC's own settling. */
    private static final int SETTLE_MS = 300;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int CHUNK_BYTES = RATE / 50 * BYTES_PER_SAMPLE;

    private MicProbe() {
    }

    /** True while any capture runs (a call, Siri): the test would fight it, so it waits. */
    static boolean busy(Context context) {
        return !context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty();
    }

    static void run(Context context, Listener listener) {
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            File dir = folder(context);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            Pickup[] all = Pickup.values();
            for (int i = 0; i < all.length; i++) {
                Pickup p = all[i];
                int left = all.length - 1 - i;
                ui.post(() -> listener.onSource(p, left));

                File wav = new File(dir, stamp + "-" + p.name().toLowerCase(Locale.ROOT) + ".wav");
                String line = p.name() + ": " + measure(p, wav);
                Log.i(TAG, "mic probe: " + line);
                report.append(line).append('\n');
            }
            Log.i(TAG, "mic probe: saved in " + dir);
            String text = report.toString().trim();
            ui.post(() -> listener.onDone(text));
        }, "mic-probe").start();
    }

    /** Riposte/MicProbe on shared storage with All files access, else the app's own folder. */
    private static File folder(Context context) {
        File shared = new File(Environment.getExternalStorageDirectory(), DIR);
        if (shared.isDirectory() || shared.mkdirs()) {
            return shared;
        }
        File own = context.getExternalFilesDir(APP_DIR);
        return own != null ? own : new File(context.getFilesDir(), APP_DIR);
    }

    private static String measure(Pickup pickup, File wav) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            return "unsupported";
        }

        AudioRecord record;
        try {
            record = new AudioRecord(MicSources.audioSource(pickup), RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK_BYTES * 4));
            record.startRecording();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return "failed: " + e.getMessage();
        }

        try {
            byte[] settle = new byte[RATE * SETTLE_MS / 1000 * BYTES_PER_SAMPLE];
            readFully(record, settle);

            byte[] pcm = new byte[RATE * SECONDS_EACH * BYTES_PER_SAMPLE];
            int got = readFully(record, pcm);
            if (got <= 0) {
                return "no audio";
            }
            save(wav, pcm, got);

            MicLevel level = MicLevel.of(pcm, 0, got);
            return String.format(Locale.ROOT, "peak %.0f dBFS, rms %.0f dBFS", level.peakDbfs, level.rmsDbfs);
        } finally {
            record.stop();
            record.release();
        }
    }

    /** Fills {@code buf} unless the recorder stops answering; the byte count read. */
    private static int readFully(AudioRecord record, byte[] buf) {
        int at = 0;
        while (at < buf.length) {
            int n = record.read(buf, at, Math.min(CHUNK_BYTES, buf.length - at));
            if (n <= 0) {
                break;
            }
            at += n;
        }
        return at;
    }

    /** A slot's WAV; a write failure only costs the file, never the level line. */
    private static void save(File wav, byte[] pcm, int len) {
        try (FileOutputStream out = new FileOutputStream(wav)) {
            out.write(Wav.of(pcm, len, RATE, 1));
        } catch (IOException e) {
            Log.w(TAG, "mic probe: cannot save " + wav + ": " + e.getMessage());
        }
    }
}
