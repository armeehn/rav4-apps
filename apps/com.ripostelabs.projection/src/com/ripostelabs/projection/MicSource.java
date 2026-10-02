package com.ripostelabs.projection;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

import com.ripostelabs.projection.ns.CallCheck;
import com.ripostelabs.projection.ns.Engine;
import com.ripostelabs.projection.ns.Model;
import com.ripostelabs.projection.ns.ModelStore;
import com.ripostelabs.projection.ns.NsPipeline;
import com.ripostelabs.projection.ns.Pickup;
import com.ripostelabs.projection.ns.RnNoise;
import com.ripostelabs.projection.zlink.MicLink;
import com.ripostelabs.projection.zlink.MicWatch;

/**
 * The cabin microphone for Siri and calls: PCM from {@link AudioRecord} in the format the
 * daemon asked for, handed to a sink in frames of {@link #FRAME_MS}. The plain mic by default;
 * the voice-communication source (the HAL's echo canceller and suppressor) only when picked
 * ({@link Pickup}). Then RNNoise ({@link NsPipeline}), after any echo canceller as it must be,
 * unless switched off in {@link MicSettingsActivity}.
 */
final class MicSource implements MicLink.Recorder {

    private static final String TAG = "Projection";
    private static final int FRAME_MS = 20;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int BUFFER_FRAMES = 8;
    private static final int LEVEL_EVERY_MS = 1000;
    /** {@code AudioManager.getMode()} by value, for the silencing log line. */
    private static final String[] MODES = {"NORMAL", "RINGTONE", "IN_CALL", "IN_COMMUNICATION", "CALL_SCREENING"};

    private final Context context;
    private AudioRecord record;
    private Thread pump;
    private volatile boolean running;
    /** RAV4-278: the platform's word on this capture ({@code isClientSilenced}). */
    private volatile boolean silencedByPlatform;
    private AudioManager.AudioRecordingCallback silencing;

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
        return NsPipeline.create(sampleRate, channels, engine(), MicPrefs.strength(context));
    }

    /**
     * Car-tuned when chosen and usable, else the standard model. This is the session boundary:
     * a model downloaded since the last capture takes over here, never mid-call.
     */
    private Engine engine() {
        if (MicPrefs.model(context) != Model.CAR_TUNED) {
            return RnNoise.open();
        }
        ModelStore.Session s = ModelUpdater.store(context).open(RnNoise::open);
        if (!s.refused.isEmpty()) {
            Log.w(TAG, "mic: car-tuned model(s) " + s.refused + " would not load, rolled back");
        }
        if (s.engine == null) {
            Log.i(TAG, "mic: no car-tuned model yet, standard model");
            return RnNoise.open();
        }
        Log.i(TAG, "mic: car-tuned model " + com.ripostelabs.projection.ns.Manifest.label(s.version));
        return s.engine;
    }

    @Override
    public synchronized boolean open(int sampleRate, int channels, MicLink.Pcm sink) {
        return capture(sampleRate, channels, sink, new MicWatch());
    }

    /** A capture for {@code sink}; a reopen passes the old {@link MicWatch} so its gap holds. */
    private boolean capture(int sampleRate, int channels, MicLink.Pcm sink, MicWatch watch) {
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
        Pickup pickup = MicPrefs.pickup(context);
        try {
            record = new AudioRecord(source(pickup), sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frame * BUFFER_FRAMES));
            record.startRecording();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.w(TAG, "mic: record failed: " + e.getMessage());
            record = null;
            return false;
        }
        running = true;
        final AudioRecord r = record;
        follow(r.getAudioSessionId());
        final int frameLen = frame;
        // The pump thread owns the pipeline and frees it on its way out: close() only stops
        // the loop, so the native state is never freed under a frame in flight.
        final NsPipeline ns = pipeline(sampleRate, channels);
        final CallCheckRecorder check = CallCheckRecorder.get();
        check.micOpened(sampleRate);
        pump = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] buf = new byte[frameLen];
            int peak = 0;
            int frames = 0;
            try {
                while (running) {
                    int n = r.read(buf, 0, frameLen);
                    MicLink.Read got = MicLink.read(n);
                    // A dead recorder answers at once, so a retry spins this thread. The next
                    // MicStart opens a fresh one.
                    if (got == MicLink.Read.LOST) {
                        Log.w(TAG, "mic: recorder lost (" + n + "), capture ends");
                        break;
                    }

                    if (got == MicLink.Read.IDLE) {
                        if (!idle()) {
                            break;
                        }
                        continue;
                    }

                    // Peak before suppression: it is the recorder's health, not the output's.
                    int framePeak = peak(buf, n);
                    peak = Math.max(peak, framePeak);
                    // RAV4-278: exact zeros mean the platform or the route silenced us. Say why;
                    // a dead route gets a fresh capture, the platform's silencing a wait.
                    MicWatch.Verdict health = watch.frame(framePeak, SystemClock.elapsedRealtime(), silencedByPlatform);
                    if (health == MicWatch.Verdict.REOPEN) {
                        Log.w(TAG, "mic: zeros for " + MicWatch.REOPEN_MS + " ms, recorder state "
                                + r.getRecordingState() + ", reopening the capture");
                        reopenLater(r, sampleRate, channels, sink, watch);
                        break;
                    }
                    report(health);
                    // The owner's call audio check, when armed: the mic before and after RNNoise.
                    check.feed(CallCheck.Tap.RAW, buf, 0, n);
                    ns.process(buf, n);
                    check.feed(CallCheck.Tap.PROCESSED, buf, 0, n);
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
        Log.i(TAG, "mic: recording " + sampleRate + " Hz x" + channels + ", pickup " + pickup
                + ", noise suppression " + ns.mode());
        return true;
    }

    /** One log line per change of the capture's health, with the audio mode behind it. */
    private void report(MicWatch.Verdict health) {
        if (health == MicWatch.Verdict.SILENCED) {
            Log.w(TAG, "mic: silenced, by the platform " + silencedByPlatform + ", audio mode " + mode());
        }
        if (health == MicWatch.Verdict.RECOVERED) {
            Log.i(TAG, "mic: sound back, audio mode " + mode());
        }
    }

    private String mode() {
        int mode = context.getSystemService(AudioManager.class).getMode();
        return mode >= 0 && mode < MODES.length ? MODES[mode] : String.valueOf(mode);
    }

    /**
     * Follow the platform's silencing of this capture: during a Telecom call (audio mode IN_CALL)
     * the audio policy silences every capture but the call's own.
     */
    private void follow(int session) {
        AudioManager am = context.getSystemService(AudioManager.class);
        // Seeded now: the capture may have started silenced, before the callback was there.
        silencedByPlatform = silenced(am.getActiveRecordingConfigurations(), session);
        silencing = new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                silencedByPlatform = silenced(configs, session);
            }
        };
        am.registerAudioRecordingCallback(silencing, null);
    }

    private static boolean silenced(List<AudioRecordingConfiguration> configs, int session) {
        for (AudioRecordingConfiguration c : configs) {
            if (c.getClientAudioSessionId() == session) {
                return c.isClientSilenced();
            }
        }
        return false;
    }

    /**
     * Off the pump thread, which is on its way out. Only if {@code was} is still the capture: a
     * MicStop in between closed it, and no capture may outlive a stop.
     */
    private void reopenLater(AudioRecord was, int sampleRate, int channels, MicLink.Pcm sink, MicWatch watch) {
        new Thread(() -> {
            synchronized (MicSource.this) {
                if (record != was) {
                    return;
                }
                capture(sampleRate, channels, sink, watch);
            }
        }, "carplay-mic-reopen").start();
    }

    private static int source(Pickup pickup) {
        return pickup.vendorProcessing() ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.MIC;
    }

    /** Nothing read: wait one frame instead of asking again at once; false when interrupted. */
    private static boolean idle() {
        try {
            Thread.sleep(FRAME_MS);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
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
        if (silencing != null) {
            context.getSystemService(AudioManager.class).unregisterAudioRecordingCallback(silencing);
            silencing = null;
        }
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
