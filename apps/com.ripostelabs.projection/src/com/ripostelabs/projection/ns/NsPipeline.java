package com.ripostelabs.projection.ns;

/**
 * Noise suppression on the CarPlay mic's PCM, in place, between the recorder and MIC_DATA.
 *
 * <pre>
 *   s16 LE @ rate ─▶ float ─▶ up ×(48k/rate) ─┬─▶ engine (10 ms) ─▶ mix ─▶ down ─▶ s16 LE
 *                                             └──▶ dry, as late as the engine ──┘
 * </pre>
 *
 * The recorder hands over 20 ms buffers; each is cut into 10 ms chunks, one engine frame each.
 * The engine runs after the platform's echo canceller when one runs ({@link Pickup#PLATFORM})
 * and the daemon's own AEC is off, so suppression never sits in front of a head-unit echo
 * canceller. Added delay: RNNoise's own 20 ms (two engine frames, its
 * look-ahead) plus the resampler's 2 ms. Nothing else is buffered: each MIC_DATA frame goes
 * out in the same call that brought it in.
 *
 * Bypass passes the PCM through untouched: stereo, a rate 48 kHz is not a multiple of, no
 * engine (library missing), or switched off. Nothing here allocates after construction.
 */
public final class NsPipeline {

    /** Engine frames per second: one per 10 ms chunk. */
    static final int CHUNKS_PER_SECOND = 100;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final float PCM_MAX = 32767f;
    private static final float PCM_MIN = -32768f;

    /** Why a pipeline does nothing; ACTIVE when it does. */
    public enum Mode {
        ACTIVE,
        OFF,
        NO_ENGINE,
        UNSUPPORTED_FORMAT
    }

    private final Mode mode;
    private final Engine engine;
    private final Resampler resampler;
    private final int chunk;
    private final float dryMix;

    private final float[] low;
    private final float[] high;
    /** The last delayFrames + 1 dry frames, a ring; the oldest is mixed with the engine's output. */
    private final float[][] dry;
    private int dryHead;

    private long busyNanos;
    private int chunks;

    private NsPipeline(Mode mode, Engine engine, int rate, Strength strength) {
        this.mode = mode;
        this.engine = engine;
        if (mode != Mode.ACTIVE) {
            this.resampler = null;
            this.chunk = 0;
            this.dryMix = 0f;
            this.low = null;
            this.high = null;
            this.dry = null;
            return;
        }

        this.chunk = rate / CHUNKS_PER_SECOND;
        this.resampler = new Resampler(Engine.RATE / rate, chunk);
        this.dryMix = strength.dryMix();
        this.low = new float[chunk];
        this.high = new float[Engine.FRAME];
        this.dry = new float[engine.delayFrames() + 1][Engine.FRAME];
    }

    /**
     * A pipeline for this capture. {@code engine} may be null (library not loaded); the
     * pipeline then bypasses, and it closes the engine itself when it refuses one.
     */
    public static NsPipeline create(int rate, int channels, Engine engine, Strength strength) {
        Mode mode = Mode.ACTIVE;
        if (engine == null) {
            mode = Mode.NO_ENGINE;
        } else if (!supports(rate, channels)) {
            mode = Mode.UNSUPPORTED_FORMAT;
        }
        if (mode != Mode.ACTIVE && engine != null) {
            engine.close();
        }
        return new NsPipeline(mode, mode == Mode.ACTIVE ? engine : null, rate, strength);
    }

    /** Switched off in Settings: the PCM goes out as recorded. */
    public static NsPipeline off() {
        return new NsPipeline(Mode.OFF, null, 0, null);
    }

    /** Mono, 10 ms a whole number of samples, and 48 kHz an integer multiple (8/16/24/48k). */
    public static boolean supports(int rate, int channels) {
        return channels == 1 && rate > 0 && rate % CHUNKS_PER_SECOND == 0 && Engine.RATE % rate == 0;
    }

    public Mode mode() {
        return mode;
    }

    /** Samples per 10 ms chunk at the mic rate; 0 when bypassing. */
    public int chunkSamples() {
        return chunk;
    }

    /**
     * Denoise the first {@code len} bytes of little-endian s16 PCM in place. Whole 10 ms
     * chunks are processed; a ragged tail (a short read) goes out as recorded.
     */
    public void process(byte[] pcm, int len) {
        if (mode != Mode.ACTIVE) {
            return;
        }

        long start = System.nanoTime();
        int bytesPerChunk = chunk * BYTES_PER_SAMPLE;
        for (int off = 0; off + bytesPerChunk <= len; off += bytesPerChunk) {
            processChunk(pcm, off);
            chunks++;
        }
        busyNanos += System.nanoTime() - start;
    }

    private void processChunk(byte[] pcm, int off) {
        for (int i = 0; i < chunk; i++) {
            int b = off + i * BYTES_PER_SAMPLE;
            low[i] = (short) ((pcm[b] & 0xff) | (pcm[b + 1] << 8));
        }

        resampler.up(low, chunk, high);
        System.arraycopy(high, 0, dry[dryHead], 0, Engine.FRAME);
        engine.frame(high);

        // The engine's output is delayFrames late, so the dry frame it mixes with is too: the
        // slot after the newest in the ring. Mixing an unaligned dry frame would comb-filter.
        dryHead = (dryHead + 1) % dry.length;
        if (dryMix > 0f) {
            float wet = 1f - dryMix;
            float[] late = dry[dryHead];
            for (int i = 0; i < Engine.FRAME; i++) {
                high[i] = wet * high[i] + dryMix * late[i];
            }
        }

        resampler.down(high, chunk, low);
        for (int i = 0; i < chunk; i++) {
            int s = (int) Math.max(PCM_MIN, Math.min(PCM_MAX, low[i]));
            int b = off + i * BYTES_PER_SAMPLE;
            pcm[b] = (byte) s;
            pcm[b + 1] = (byte) (s >> 8);
        }
    }

    /** Mean processing time per 10 ms chunk since the last call, in microseconds; resets. */
    public long takeMicrosPerChunk() {
        long us = chunks == 0 ? 0 : busyNanos / 1000 / chunks;
        busyNanos = 0;
        chunks = 0;
        return us;
    }

    public void close() {
        if (engine != null) {
            engine.close();
        }
    }
}
