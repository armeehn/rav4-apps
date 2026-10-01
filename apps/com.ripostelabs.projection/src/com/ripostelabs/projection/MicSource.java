package com.ripostelabs.projection;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import com.ripostelabs.projection.ns.NsPipeline;
import com.ripostelabs.projection.ns.RnNoise;
import com.ripostelabs.projection.zlink.MicLink;

/**
 * The cabin microphone for Siri and calls: PCM from {@link AudioRecord} in the format the
 * daemon asked for, handed to a sink in frames of {@link #FRAME_MS}. Voice-communication
 * source, so the platform's own echo cancellation and noise suppression apply where the
 * HAL has them. Then RNNoise ({@link NsPipeline}), after the echo canceller as it must be,
 * unless switched off in {@link MicSettingsActivity}.
 */
final class MicSource implements MicLink.Recorder {

    private static final String TAG = "Projection";
    private static final int FRAME_MS = 20;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int BUFFER_FRAMES = 8;
    private static final int LEVEL_EVERY_MS = 1000;

    private final Context context;
    private AudioRecord record;
    private Thread pump;
    private volatile boolean running;

    MicSource(Context context) {
        this.context = context;
    }

    static boolean permitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** This capture's suppressor, per the settings; bypass when off or unavailable. */
    private NsPipeline pipeline(int sampleRate, int channels) {
        if (!MicPrefs.suppress(context, MicPrefs.Path.CARPLAY)) {
            return NsPipeline.off();
        }
        return NsPipeline.create(sampleRate, channels, RnNoise.open(), MicPrefs.strength(context));
    }

    @Override
    public synchronized boolean open(int sampleRate, int channels, MicLink.Pcm sink) {
        close();
        if (!permitted(context)) {
            Log.w(TAG, "mic: RECORD_AUDIO not granted");
            return false;
        }
        int channelMask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int frame = sampleRate * FRAME_MS / 1000 * channels * BYTES_PER_SAMPLE;
        int min = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.w(TAG, "mic: unsupported " + sampleRate + "/" + channels);
            return false;
        }
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frame * BUFFER_FRAMES));
            record.startRecording();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.w(TAG, "mic: record failed: " + e.getMessage());
            record = null;
            return false;
        }
        running = true;
        final AudioRecord r = record;
        final int frameLen = frame;
        // The pump thread owns the pipeline and frees it on its way out: close() only stops
        // the loop, so the native state is never freed under a frame in flight.
        final NsPipeline ns = pipeline(sampleRate, channels);
        pump = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] buf = new byte[frameLen];
            int peak = 0;
            int frames = 0;
            try {
                while (running) {
                    int n = r.read(buf, 0, frameLen);
                    if (n <= 0) {
                        continue;
                    }
                    // Peak before suppression: it is the recorder's health, not the output's.
                    peak = Math.max(peak, peak(buf, n));
                    ns.process(buf, n);
                    sink.onPcm(buf, n);
                    // One line a second with the loudest sample: a recorder the policy silences
                    // (a foreground service started from the background) reads all zeros. The
                    // suppressor's cost per 10 ms rides along, the CPU figure for the car.
                    if (++frames * FRAME_MS >= LEVEL_EVERY_MS) {
                        Log.i(TAG, "mic: peak " + peak + " / 32767, ns " + ns.mode()
                                + " " + ns.takeMicrosPerChunk() + " us/10ms");
                        peak = 0;
                        frames = 0;
                    }
                }
            } finally {
                ns.close();
            }
        }, "carplay-mic");
        pump.start();
        Log.i(TAG, "mic: recording " + sampleRate + " Hz x" + channels + ", noise suppression " + ns.mode());
        return true;
    }

    private static int peak(byte[] pcm, int len) {
        int peak = 0;
        for (int i = 0; i + 1 < len; i += BYTES_PER_SAMPLE) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs(s));
        }
        return peak;
    }

    @Override
    public synchronized void close() {
        running = false;
        if (pump != null) {
            pump.interrupt();
            pump = null;
        }
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // never started
            }
            record.release();
            record = null;
        }
    }
}
