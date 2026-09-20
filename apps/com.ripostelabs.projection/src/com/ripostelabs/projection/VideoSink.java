package com.ripostelabs.projection;

import android.media.MediaCodec;
import android.media.MediaFormat;
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
    private static final long INPUT_WAIT_US = 20_000;
    private static final long OUTPUT_WAIT_US = 10_000;

    private final Deque<byte[]> pending = new ArrayDeque<>();
    private MediaCodec codec;
    private Thread drain;
    private int width;
    private int height;
    private Surface surface;
    private int dropped;
    private int fed;
    /** Bench aid: with the property set, every access unit is appended to this file as well. */
    private static final String DUMP_PROP = "riposte.video.dump";
    private static final String DUMP_PATH = "/data/local/tmp/carplay.h264";
    private java.io.FileOutputStream dump;
    private static volatile int rendered;
    private static final int REPORT_EVERY = 10;

    synchronized void setSurface(Surface s) {
        surface = s;
        if (surface == null) {
            stop();
            return;
        }
        if (width > 0) {
            start();
        }
    }

    /** The phone told us the stream size; the decoder starts once a surface exists too. */
    synchronized void configure(int w, int h) {
        stop();
        width = w;
        height = h;
        if (surface != null) {
            start();
        }
    }

    synchronized void feed(byte[] data, int off, int len, long timestampUs) {
        if (codec == null) {
            hold(data, off, len);
            return;
        }
        push(data, off, len, timestampUs);
    }

    synchronized void stop() {
        if (drain != null) {
            drain.interrupt();
            drain = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
                // Already dead; releasing is all that matters.
            }
            codec.release();
            codec = null;
        }
    }

    private void start() {
        try {
            codec = MediaCodec.createDecoderByType(MIME);
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            codec.configure(format, surface, null, 0);
            codec.start();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "video: decoder failed to start: " + e);
            codec = null;
            return;
        }
        Log.i(TAG, "video: decoder " + codec.getName() + " up at " + width + "x" + height + ", " + pending.size() + " held units");
        if ("1".equals(SystemProps.get(DUMP_PROP))) {
            try {
                dump = new java.io.FileOutputStream(DUMP_PATH);
            } catch (IOException e) {
                Log.w(TAG, "video: no dump: " + e.getMessage());
            }
        }

        while (!pending.isEmpty()) {
            byte[] unit = pending.pollFirst();
            push(unit, 0, unit.length, 0);
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

    private void hold(byte[] data, int off, int len) {
        if (pending.size() >= PENDING_LIMIT) {
            pending.pollFirst();
            dropped++;
        }
        byte[] copy = new byte[len];
        System.arraycopy(data, off, copy, 0, len);
        pending.addLast(copy);
    }

    private void push(byte[] data, int off, int len, long timestampUs) {
        if (dump != null) {
            try {
                dump.write(data, off, len);
            } catch (IOException ignored) {
                // bench aid only
            }
        }
        try {
            int index = codec.dequeueInputBuffer(INPUT_WAIT_US);
            if (index < 0) {
                dropped++;
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
        } catch (IllegalStateException e) {
            Log.w(TAG, "video: decoder rejected input: " + e);
        }
    }

    /** Renders every decoded frame as soon as it exists; the phone paces the stream. */
    private static void drainLoop(MediaCodec running) {
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
