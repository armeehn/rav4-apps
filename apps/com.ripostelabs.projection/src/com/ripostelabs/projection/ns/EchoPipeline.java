package com.ripostelabs.projection.ns;

import java.util.Locale;

/**
 * Echo cancellation on the CarPlay mic's PCM, in place, before the noise suppressor.
 *
 * <pre>
 *   mic read at t ──▶ every downlink frame that arrived by t ──▶ canceller.render
 *                 ──▶ each 10 ms chunk ──▶ canceller.capture ──▶ NsPipeline (RNNoise)
 * </pre>
 *
 * The canceller is AEC3: it finds the downlink-to-mic delay itself and backs its suppressor
 * off while the near end talks (double talk), so the driver is not cut off under the far end.
 * It runs before RNNoise because a suppressor in front of it would change the echo path it
 * models. Nothing is buffered here, so it adds no delay of its own.
 *
 * Bypass passes the PCM through untouched: stereo, a rate AEC3 does not run at, no library, or
 * switched off. Nothing here allocates after construction.
 */
public final class EchoPipeline {

    private static final int BYTES_PER_SAMPLE = 2;

    /** Why a pipeline does nothing; ACTIVE when it does. */
    public enum Mode {
        ACTIVE,
        OFF,
        NO_ENGINE,
        UNSUPPORTED_FORMAT
    }

    private final Mode mode;
    private final Canceller canceller;
    private final EchoReference reference;
    private final short[] near;
    private final EchoReference.Frame far = new EchoReference.Frame();
    private final float[] stats = new float[Canceller.STATS];

    private long busyNanos;
    private int chunks;

    private EchoPipeline(Mode mode, Canceller canceller, EchoReference reference, int rate) {
        this.mode = mode;
        this.canceller = canceller;
        this.reference = reference;
        this.near = new short[mode == Mode.ACTIVE ? rate / Canceller.FRAMES_PER_SECOND : 0];
    }

    /**
     * A pipeline for this capture. {@code canceller} may be null (library not loaded); the
     * pipeline then bypasses, and it closes the canceller itself when it refuses one.
     */
    public static EchoPipeline create(int rate, int channels, Canceller canceller, EchoReference reference) {
        Mode mode = Mode.ACTIVE;
        if (canceller == null) {
            mode = Mode.NO_ENGINE;
        } else if (!supports(rate, channels)) {
            mode = Mode.UNSUPPORTED_FORMAT;
        }
        if (mode != Mode.ACTIVE && canceller != null) {
            canceller.close();
        }
        return new EchoPipeline(mode, mode == Mode.ACTIVE ? canceller : null, reference, rate);
    }

    /** Switched off in Settings: the PCM goes on as recorded. */
    public static EchoPipeline off() {
        return new EchoPipeline(Mode.OFF, null, null, 0);
    }

    /** A mono mic at a rate AEC3 processes natively. */
    public static boolean supports(int rate, int channels) {
        return channels == 1 && (rate == 8000 || rate == 16000 || rate == 32000 || rate == 48000);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Cancel the echo in the first {@code len} bytes of little-endian s16 PCM, in place. The
     * mic read returned at {@code readNanos} (System.nanoTime); every downlink frame that
     * arrived by then is rendered first. A ragged tail (a short read) goes on as recorded.
     */
    public void process(byte[] pcm, int len, long readNanos) {
        if (mode != Mode.ACTIVE) {
            return;
        }

        long start = System.nanoTime();
        while (reference.poll(far, readNanos)) {
            canceller.render(far.pcm, far.rate, far.channels);
        }

        int bytesPerChunk = near.length * BYTES_PER_SAMPLE;
        for (int off = 0; off + bytesPerChunk <= len; off += bytesPerChunk) {
            processChunk(pcm, off);
            chunks++;
        }
        busyNanos += System.nanoTime() - start;
    }

    private void processChunk(byte[] pcm, int off) {
        for (int i = 0; i < near.length; i++) {
            int b = off + i * BYTES_PER_SAMPLE;
            near[i] = (short) ((pcm[b] & 0xff) | (pcm[b + 1] << 8));
        }

        canceller.capture(near);

        for (int i = 0; i < near.length; i++) {
            int b = off + i * BYTES_PER_SAMPLE;
            pcm[b] = (byte) near[i];
            pcm[b + 1] = (byte) (near[i] >> 8);
        }
    }

    /** Mean processing time per 10 ms chunk since the last call, in microseconds; resets. */
    public long takeMicrosPerChunk() {
        long us = chunks == 0 ? 0 : busyNanos / 1000 / chunks;
        busyNanos = 0;
        chunks = 0;
        return us;
    }

    /**
     * What the canceller reports, for the log and the call audio check, e.g.
     * "erle 18.2 dB, erl 9.5 dB, delay 140 ms"; empty when bypassing. Allocates: once a second.
     */
    public String stats() {
        if (mode != Mode.ACTIVE) {
            return "";
        }

        canceller.stats(stats);
        return "erle " + db(stats[Canceller.STAT_ERLE_DB]) + ", erl " + db(stats[Canceller.STAT_ERL_DB])
                + ", delay " + (stats[Canceller.STAT_DELAY_MS] <= Canceller.UNKNOWN ? "?"
                : Math.round(stats[Canceller.STAT_DELAY_MS])) + " ms"
                + ", downlink dropped " + reference.takeDropped();
    }

    private static String db(float v) {
        return v <= Canceller.UNKNOWN ? "? dB" : String.format(Locale.ROOT, "%.1f dB", v);
    }

    public void close() {
        if (canceller != null) {
            canceller.close();
        }
    }
}
