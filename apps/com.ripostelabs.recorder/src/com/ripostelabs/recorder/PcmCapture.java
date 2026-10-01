package com.ripostelabs.recorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.io.IOException;

/**
 * Raw 16-bit mono PCM from the cabin mic, on its own thread, for the takes that keep samples
 * rather than an encoded file. The source is VOICE_COMMUNICATION, the one the CarPlay mic
 * uses (Projection's MicSource), so a test hears the mic path a call does: the platform's echo
 * canceller included, and the vendor's own processing whatever it is.
 */
final class PcmCapture {

    /** Where the samples go. Runs on the capture thread. */
    interface Sink {
        void accept(byte[] pcm, int len) throws IOException;
    }

    static final int SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    static final String SOURCE_NAME = "VOICE_COMMUNICATION";

    private static final String TAG = "Recorder";
    private static final int READ_MS = 20;
    private static final int BUFFER_READS = 8;

    private final AudioRecord record;
    private final Thread pump;
    private volatile boolean running = true;
    private volatile int peak;
    private volatile String error;

    private PcmCapture(AudioRecord record, int readBytes, Sink sink) {
        this.record = record;
        this.pump = new Thread(() -> loop(readBytes, sink), "recorder-pcm");
    }

    /** A running capture, or null when the mic will not open (busy, or no such format). */
    static PcmCapture start(int rate, Sink sink) {
        int mask = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int readBytes = rate * READ_MS / 1000 * Wav.BYTES_PER_SAMPLE;
        int min = AudioRecord.getMinBufferSize(rate, mask, encoding);
        if (min <= 0) {
            Log.w(TAG, "pcm: unsupported rate " + rate);
            return null;
        }

        AudioRecord record;
        try {
            record = new AudioRecord(SOURCE, rate, mask, encoding, Math.max(min, readBytes * BUFFER_READS));
            record.startRecording();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.w(TAG, "pcm: record failed: " + e.getMessage());
            return null;
        }

        // A recorder another capture holds starts "fine" and then never records.
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            return null;
        }

        PcmCapture capture = new PcmCapture(record, readBytes, sink);
        capture.pump.start();
        return capture;
    }

    private void loop(int readBytes, Sink sink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[readBytes];
        try {
            while (running) {
                int n = record.read(buf, 0, readBytes);
                if (n < 0) {
                    error = "mic read failed (" + n + ")";
                    break;
                }
                if (n == 0) {
                    continue;
                }
                peak = Math.max(peak, peakOf(buf, n));
                sink.accept(buf, n);
            }
        } catch (IOException e) {
            error = "write failed: " + e.getMessage();
        }
        if (error != null) {
            Log.w(TAG, "pcm: " + error);
        }
    }

    /** The loudest sample since the last call, 0..32767; resets. */
    int takePeak() {
        int p = peak;
        peak = 0;
        return p;
    }

    /** Why the capture ended early, or null. */
    String error() {
        return error;
    }

    /** Stop and wait for the last read to reach the sink. */
    void stop() {
        running = false;
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // Already stopped: the loop is on its way out either way.
        }
        try {
            pump.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        record.release();
    }

    private static int peakOf(byte[] pcm, int len) {
        int p = 0;
        for (int i = 0; i + 1 < len; i += Wav.BYTES_PER_SAMPLE) {
            p = Math.max(p, Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8))));
        }
        return p;
    }
}
