package com.ripostelabs.projection.ns;

/**
 * The owner's call audio check: a fixed-length take of one call's mic path, before and after
 * the suppressor, with the phone's downlink beside it, for tuning on real data.
 *
 * <pre>
 *   recorder PCM ──▶ RAW ──▶ NsPipeline ──▶ PROCESSED ──▶ MIC_DATA
 *   phone's audio ──▶ DOWNLINK ──▶ speakers         (the echo's source, for AEC work)
 * </pre>
 *
 * Nothing records until a person arms it, and a take stops by itself once the mic, raw and
 * processed, has {@code seconds} of audio. Each tap is a WAV in memory; the caller writes the files. Pure
 * Java, so the take's rules are unit-tested.
 */
public final class CallCheck {

    /** Where in the path a buffer was taken. */
    public enum Tap { RAW, PROCESSED, DOWNLINK }

    public enum State { IDLE, RECORDING, DONE }

    private static final int BYTES_PER_SAMPLE = 2;
    private static final int WAV_HEADER = 44;
    private static final int BITS = 16;

    private final int seconds;
    private State state = State.IDLE;
    private final byte[][] pcm = new byte[Tap.values().length][];
    private final int[] filled = new int[Tap.values().length];
    private final int[] rate = new int[Tap.values().length];
    private final int[] channels = new int[Tap.values().length];

    public CallCheck(int seconds) {
        this.seconds = seconds;
    }

    /**
     * Start a take: the mic at {@code micRate} mono, the downlink in its own format (rate 0 when
     * the phone sends none, and that tap stays empty). False when a take is already running.
     */
    public synchronized boolean arm(int micRate, int downRate, int downChannels) {
        if (state == State.RECORDING || micRate <= 0) {
            return false;
        }

        size(Tap.RAW, micRate, 1);
        size(Tap.PROCESSED, micRate, 1);
        size(Tap.DOWNLINK, Math.max(downRate, 0), Math.max(downChannels, 1));
        state = State.RECORDING;
        return true;
    }

    private void size(Tap tap, int r, int ch) {
        int i = tap.ordinal();
        rate[i] = r;
        channels[i] = ch;
        filled[i] = 0;
        pcm[i] = new byte[seconds * r * ch * BYTES_PER_SAMPLE];
    }

    /** Append little-endian s16 PCM to a tap; what does not fit is dropped. */
    public synchronized void feed(Tap tap, byte[] data, int off, int len) {
        if (state != State.RECORDING) {
            return;
        }

        int i = tap.ordinal();
        int n = Math.min(len, pcm[i].length - filled[i]);
        if (n > 0) {
            System.arraycopy(data, off, pcm[i], filled[i], n);
            filled[i] += n;
        }
        // The mic is the clock: the take ends when the raw mic and its processed twin are both
        // full, whatever the downlink holds. RAW is fed first, so RAW alone would cut PROCESSED
        // one frame short.
        if (full(Tap.RAW) && full(Tap.PROCESSED)) {
            state = State.DONE;
        }
    }

    private boolean full(Tap tap) {
        return filled[tap.ordinal()] == pcm[tap.ordinal()].length;
    }

    /** The mic stopped (call over) before the take filled: keep what there is. */
    public synchronized void finish() {
        if (state == State.RECORDING) {
            state = State.DONE;
        }
    }

    public synchronized State state() {
        return state;
    }

    /** Whole seconds of raw mic still to record; 0 unless recording. */
    public synchronized int secondsLeft() {
        if (state != State.RECORDING) {
            return 0;
        }

        int i = Tap.RAW.ordinal();
        int perSecond = rate[i] * BYTES_PER_SAMPLE;
        return (pcm[i].length - filled[i] + perSecond - 1) / perSecond;
    }

    /** One tap as a WAV file once the take is done; null before, or when the tap holds nothing. */
    public synchronized byte[] wav(Tap tap) {
        int i = tap.ordinal();
        if (state != State.DONE || filled[i] == 0) {
            return null;
        }

        return wav(pcm[i], filled[i], rate[i], channels[i]);
    }

    /** Free the buffers; the next take can be armed. */
    public synchronized void reset() {
        state = State.IDLE;
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = null;
            filled[i] = 0;
        }
    }

    /** A canonical 44-byte RIFF/WAVE header, PCM 16-bit, then the data. */
    static byte[] wav(byte[] data, int len, int sampleRate, int ch) {
        byte[] out = new byte[WAV_HEADER + len];
        int blockAlign = ch * BYTES_PER_SAMPLE;
        ascii(out, 0, "RIFF");
        le32(out, 4, 36 + len);
        ascii(out, 8, "WAVE");
        ascii(out, 12, "fmt ");
        le32(out, 16, 16);
        le16(out, 20, 1);
        le16(out, 22, ch);
        le32(out, 24, sampleRate);
        le32(out, 28, sampleRate * blockAlign);
        le16(out, 32, blockAlign);
        le16(out, 34, BITS);
        ascii(out, 36, "data");
        le32(out, 40, len);
        System.arraycopy(data, 0, out, WAV_HEADER, len);
        return out;
    }

    private static void ascii(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[at + i] = (byte) s.charAt(i);
        }
    }

    private static void le16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] b, int at, int v) {
        le16(b, at, v);
        le16(b, at + 2, v >> 16);
    }
}
