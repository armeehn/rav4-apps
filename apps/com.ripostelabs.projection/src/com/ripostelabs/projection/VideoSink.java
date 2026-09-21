package com.ripostelabs.projection;

import android.media.MediaCodec;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * H.264 from the phone into a {@link MediaCodec} decoder drawing straight onto the panel's
 * surface. The stream is Annex-B with SPS/PPS in-band, which the platform decoders accept
 * without a separate codec-config buffer.
 *
 * <p>Access units that arrive before the surface exists are held (bounded) rather than dropped,
 * because the first one carries the parameter sets and the IDR frame; losing it means a grey
 * screen until the phone's next key frame.
 */
final class VideoSink {

    private static final String TAG = "Projection";
    private static final String MIME = "video/avc";
    private static final int PENDING_LIMIT = 64;
    private static final int OFFSCREEN_TEXTURE_NAME = 0;
    private static final int START_CODE_LEN = 4;
    private static final int NAL_TYPE_MASK = 0x1f;
    private static final int NAL_IDR = 5;
    private static final int NAL_SPS = 7;
    private static final int NAL_PPS = 8;
    private static final long INPUT_WAIT_US = 20_000;
    /** How long a unit may wait for an input buffer before it is given up as lost. */
    private static final long INPUT_GIVE_UP_US = 1_000_000;
    /** MediaFormat.KEY_PRIORITY: 0 is realtime, 1 is best effort. */
    private static final int PRIORITY_REALTIME = 0;
    private static final int DEFAULT_FRAME_RATE = 30;
    private int frameRate = DEFAULT_FRAME_RATE;
    private static final long OUTPUT_WAIT_US = 10_000;

    private final Deque<byte[]> pending = new ArrayDeque<>();
    private MediaCodec codec;
    private Thread drain;
    private int width;
    private int height;
    private Surface surface;
    private int dropped;
    private int fed;
    /** Units fed with no output since the last render; past the limit the sink asks for a picture. */
    private int starved;
    private int renderedSeen;
    private static final int STARVE_LIMIT = 30;
    private Runnable onStarved;
    /** Bench aid: with the property set, every access unit is appended to this file as well. */
    private static final String DUMP_PROP = "riposte.video.dump";
    private static final String DUMP_PATH = "/data/data/com.ripostelabs.projection/files/carplay.h264";
    private java.io.FileOutputStream dump;
    private static volatile int rendered;
    private static final int REPORT_EVERY = 10;

    /** Called (on the feeding thread) when the decoder keeps eating and shows nothing. */
    void setOnStarved(Runnable r) {
        onStarved = r;
    }

    /**
     * The screen's surface, or null while it is away. The decoder never stops for that: it
     * keeps decoding onto an off-screen surface and is only re-pointed, so the picture is whole
     * the moment the screen comes back and no key frame has to be asked for.
     */
    synchronized void setSurface(Surface s) {
        surface = s;
        if (codec == null) {
            return;
        }
        try {
            codec.setOutputSurface(s != null ? s : offscreen());
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "video: output surface switch failed: " + e);
        }
    }

    /** The phone told us the stream size; the decoder starts right away, screen or not. */
    synchronized void configure(int w, int h) {
        stop();
        width = w;
        height = h;
        start();
    }

    /** The rate the phone was asked for: the decoder clocks itself for it. */
    synchronized void setFrameRate(int fps) {
        frameRate = fps;
    }

    private SurfaceTexture offscreenTexture;
    private Surface offscreenSurface;

    /** A surface nobody looks at, for the frames decoded while the screen is away. */
    private Surface offscreen() {
        if (offscreenSurface == null) {
            offscreenTexture = new SurfaceTexture(OFFSCREEN_TEXTURE_NAME);
            offscreenTexture.setDefaultBufferSize(width, height);
            offscreenSurface = new Surface(offscreenTexture);
        }
        return offscreenSurface;
    }

    void feed(byte[] data, int off, int len, long timestampUs) {
        MediaCodec c;
        synchronized (this) {
            if (codec == null) {
                hold(data, off, len);
                return;
            }
            c = codec;
        }
        // Outside the monitor: the wait for an input buffer can run to a second, and the
        // main thread takes this lock to switch surfaces. A codec stopped meanwhile throws
        // IllegalStateException, which push() treats as one lost unit.
        push(c, data, off, len, timestampUs);
    }

    private void closeDump() {
        if (dump == null) {
            return;
        }
        try {
            dump.close();
        } catch (IOException ignored) {
            // bench aid only
        }
        dump = null;
    }

    synchronized void stop() {
        if (drain != null) {
            drain.interrupt();
            drain = null;
        }
        closeDump();
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
                // Already dead; releasing is all that matters.
            }
            codec.release();
            codec = null;
        }
        if (offscreenSurface != null) {
            offscreenSurface.release();
            offscreenTexture.release();
            offscreenSurface = null;
            offscreenTexture = null;
        }
    }

    private void start() {
        try {
            codec = MediaCodec.createDecoderByType(MIME);
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            // A bare format clocks the video core for a default session; a live stream at
            // 45 fps and above lags behind it. Realtime priority and the true rate keep the
            // decoder ahead of the phone, low latency stops it holding frames for reordering.
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            codec.configure(format, surface != null ? surface : offscreen(), null, 0);
            codec.start();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "video: decoder failed to start: " + e);
            codec = null;
            return;
        }
        Log.i(TAG, "video: decoder " + codec.getName() + " up at " + width + "x" + height + ", " + pending.size() + " held units");
        if (SystemProps.bench() && "1".equals(SystemProps.get(DUMP_PROP))) {
            try {
                dump = new java.io.FileOutputStream(DUMP_PATH);
            } catch (IOException e) {
                Log.w(TAG, "video: no dump: " + e.getMessage());
            }
        }

        while (!pending.isEmpty()) {
            byte[] unit = pending.pollFirst();
            push(codec, unit, 0, unit.length, 0);
        }

        final MediaCodec running = codec;
        drain = new Thread(new Runnable() {
            @Override
            public void run() {
                drainLoop(running);
            }
        }, "projection-video");
        drain.start();
    }

    /**
     * A key frame makes everything before it dead weight: start the held run over from it,
     * so the decoder that comes up later opens on a picture instead of waiting for the next
     * one (the phone sends key frames only on request).
     */
    private void hold(byte[] data, int off, int len) {
        int type = nalType(data, off, len);
        boolean restart = type == NAL_SPS
                || (type == NAL_IDR && (pending.isEmpty() || !isParameterSet(pending.peekLast())));
        if (restart) {
            dropped += pending.size();
            pending.clear();
        } else if (pending.size() >= PENDING_LIMIT) {
            pending.pollFirst();
            dropped++;
        }
        byte[] copy = new byte[len];
        System.arraycopy(data, off, copy, 0, len);
        pending.addLast(copy);
    }

    /** The type of an Annex-B unit's first NAL, or 0 when it is too short to say. */
    private static int nalType(byte[] data, int off, int len) {
        if (len < START_CODE_LEN + 1) {
            return 0;
        }
        return data[off + START_CODE_LEN] & NAL_TYPE_MASK;
    }

    private static boolean isParameterSet(byte[] unit) {
        int type = nalType(unit, 0, unit.length);
        return type == NAL_SPS || type == NAL_PPS;
    }

    private void push(MediaCodec codec, byte[] data, int off, int len, long timestampUs) {
        if (dump != null) {
            try {
                dump.write(data, off, len);
            } catch (IOException ignored) {
                // bench aid only
            }
        }
        try {
            // The phone sends P-frames only, with a key frame on request: a dropped unit
            // corrupts the picture until the intra refresh has walked it. When the session's
            // opening burst (110 frames in 1.3 s) fills the input queue, the reader waits
            // instead; the socket holds the daemon. A unit given up asks for a key frame.
            int index = codec.dequeueInputBuffer(INPUT_WAIT_US);
            for (long waited = INPUT_WAIT_US; index < 0 && waited < INPUT_GIVE_UP_US; waited += INPUT_WAIT_US) {
                index = codec.dequeueInputBuffer(INPUT_WAIT_US);
            }
            if (index < 0) {
                dropped++;
                Log.w(TAG, "video: no input buffer for a unit, asking for a picture");
                if (onStarved != null) {
                    onStarved.run();
                }
                return;
            }
            ByteBuffer buf = codec.getInputBuffer(index);
            if (buf == null || buf.capacity() < len) {
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                dropped++;
                return;
            }
            buf.clear();
            buf.put(data, off, len);
            codec.queueInputBuffer(index, 0, len, timestampUs, 0);
            fed++;
            if (fed % REPORT_EVERY == 0) {
                Log.i(TAG, "video: fed " + fed + " rendered " + rendered + " dropped " + dropped);
            }
            if (rendered != renderedSeen) {
                renderedSeen = rendered;
                starved = 0;
            } else if (++starved >= STARVE_LIMIT) {
                starved = 0;
                Log.i(TAG, "video: no output for " + STARVE_LIMIT + " units, asking for a picture");
                if (onStarved != null) {
                    onStarved.run();
                }
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "video: decoder rejected input: " + e);
        }
    }

    /** Renders every decoded frame as soon as it exists; the phone paces the stream. */
    private static void drainLoop(MediaCodec running) {
        // Renders every frame to the panel: never behind the launcher or a background sync.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int index = running.dequeueOutputBuffer(info, OUTPUT_WAIT_US);
                if (index >= 0) {
                    running.releaseOutputBuffer(index, true);
                    rendered++;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "video: output format " + running.getOutputFormat());
                }
            } catch (IllegalStateException e) {
                return;
            }
        }
    }

    int dropped() {
        return dropped;
    }
}
