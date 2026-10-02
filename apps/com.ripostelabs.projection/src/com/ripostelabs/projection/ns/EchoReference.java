package com.ripostelabs.projection.ns;

/**
 * The phone's downlink, kept for the echo canceller: 10 ms frames stamped with the time they
 * reached the app, handed to the mic thread in arrival order.
 *
 * <pre>
 *   daemon audio ──▶ onAudio ──▶ AudioTrack ──▶ speakers ──▶ cabin ──▶ mic
 *                       └──▶ push(t) ──▶ [ring] ──▶ poll(mic read time) ──▶ AEC3 render
 * </pre>
 *
 * A frame is released to a mic read only once it has arrived: a downlink frame reaches the app
 * before the speakers play it, so its echo is always in that read or a later one, never an
 * earlier one. That keeps the reference ahead of the echo; the rest of the delay (the track's
 * buffer, the HAL, the cabin) is AEC3's delay estimator's to find. Frames older than
 * {@link #MAX_AGE_NANOS} at a read are dropped: their echo is gone, or out of the estimator's
 * reach. The ring holds {@link #CAPACITY} frames and overwrites the oldest when no mic reads.
 */
public final class EchoReference {

    /** One second of 10 ms frames. */
    public static final int CAPACITY = 100;
    /** Past AEC3's delay estimator range (about 500 ms): no use as a reference. */
    static final long MAX_AGE_NANOS = 500_000_000L;
    /** The largest frame taken: 10 ms of 48 kHz stereo. */
    static final int MAX_RATE = 48000;
    static final int MAX_CHANNELS = 2;
    static final int MAX_FRAME = MAX_RATE / Canceller.FRAMES_PER_SECOND * MAX_CHANNELS;
    private static final int BYTES_PER_SAMPLE = 2;

    /** One 10 ms downlink frame, interleaved: the first {@code samples} of {@code pcm}. */
    public static final class Frame {
        public final short[] pcm = new short[MAX_FRAME];
        public int samples;
        public int rate;
        public int channels;
    }

    private final short[][] ring = new short[CAPACITY][MAX_FRAME];
    private final long[] arrived = new long[CAPACITY];
    private int head;
    private int count;
    private int dropped;

    private int rate;
    private int channels;
    /** Samples per frame, all channels; 0 when the format is unknown or not taken. */
    private int frame;
    /** The frame being filled across pushes, and how many samples it has. */
    private final short[] partial = new short[MAX_FRAME];
    private int partialFill;

    /** A downlink format the canceller can take a reference in. */
    public static boolean supports(int rate, int channels) {
        return rate > 0 && rate <= MAX_RATE && rate % Canceller.FRAMES_PER_SECOND == 0
                && channels >= 1 && channels <= MAX_CHANNELS;
    }

    /** The daemon's downlink format changed: what is queued is in the old one, so it goes. */
    public synchronized void format(int rate, int channels) {
        this.rate = rate;
        this.channels = channels;
        this.frame = supports(rate, channels) ? rate / Canceller.FRAMES_PER_SECOND * channels : 0;
        count = 0;
        partialFill = 0;
    }

    /** Downlink s16 LE PCM as it reaches the app, at {@code nowNanos} (System.nanoTime). */
    public synchronized void push(byte[] pcm, int off, int len, long nowNanos) {
        if (frame == 0) {
            return;
        }

        for (int b = off; b + 1 < off + len; b += BYTES_PER_SAMPLE) {
            partial[partialFill++] = (short) ((pcm[b] & 0xff) | (pcm[b + 1] << 8));
            if (partialFill == frame) {
                enqueue(nowNanos);
            }
        }
    }

    private void enqueue(long nowNanos) {
        if (count == CAPACITY) {
            head = (head + 1) % CAPACITY;
            count--;
            dropped++;
        }
        int slot = (head + count) % CAPACITY;
        System.arraycopy(partial, 0, ring[slot], 0, frame);
        arrived[slot] = nowNanos;
        count++;
        partialFill = 0;
    }

    /**
     * The oldest frame that arrived by {@code readNanos}, the time the mic read returned; false
     * when there is none. Frames too old to matter are dropped on the way.
     */
    public synchronized boolean poll(Frame out, long readNanos) {
        while (count > 0 && arrived[head] < readNanos - MAX_AGE_NANOS) {
            head = (head + 1) % CAPACITY;
            count--;
            dropped++;
        }
        if (count == 0 || arrived[head] > readNanos) {
            return false;
        }

        System.arraycopy(ring[head], 0, out.pcm, 0, frame);
        out.samples = frame;
        out.rate = rate;
        out.channels = channels;
        head = (head + 1) % CAPACITY;
        count--;
        return true;
    }

    /** Frames dropped (stale or overwritten) since the last call; resets. */
    public synchronized int takeDropped() {
        int d = dropped;
        dropped = 0;
        return d;
    }
}
