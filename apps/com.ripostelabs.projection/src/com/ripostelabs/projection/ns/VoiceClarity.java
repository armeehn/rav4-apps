package com.ripostelabs.projection.ns;

/**
 * A treble shelf on the cabin mic, in place on 16-bit little-endian mono PCM, before RNNoise.
 *
 * <pre>
 *   RAW ──▶ EchoPipeline ──▶ VoiceClarity (+{@link #GAIN_DB} dB above {@link #CORNER_HZ} Hz) ──▶ NsPipeline
 * </pre>
 *
 * The head unit's mic records speech dark: on a CarPlay call at full codec gain (bench,
 * 2026-10-07 17:44) 1-2 kHz sat 20 dB and 3-4 kHz 32 dB below the strongest band, where clear
 * speech sits about 8 and 18 dB below. That is the "underwater" sound; level alone did not fix
 * it. A shelf at 1.5 kHz, +15 dB, put 1-4 kHz within 2 dB of clear speech on that recording,
 * with peaks still at -10 dBFS. RNNoise runs after it, so the noise it lifts is cleaned too.
 *
 * One RBJ high-shelf biquad (S = 1), direct form I; the state carries between calls.
 */
public final class VoiceClarity {

    public static final double CORNER_HZ = 1500.0;
    public static final double GAIN_DB = 15.0;

    private static final int BYTES_PER_SAMPLE = 2;
    private static final double FULL_SCALE = 32768.0;

    private final double b0;
    private final double b1;
    private final double b2;
    private final double a1;
    private final double a2;
    private double x1;
    private double x2;
    private double y1;
    private double y2;

    public VoiceClarity(int sampleRate) {
        double a = Math.pow(10, GAIN_DB / 40);
        double w0 = 2 * Math.PI * CORNER_HZ / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / 2 * Math.sqrt(2);
        double sqA = 2 * Math.sqrt(a) * alpha;

        double a0 = (a + 1) - (a - 1) * cos + sqA;
        b0 = a * ((a + 1) + (a - 1) * cos + sqA) / a0;
        b1 = -2 * a * ((a - 1) + (a + 1) * cos) / a0;
        b2 = a * ((a + 1) + (a - 1) * cos - sqA) / a0;
        a1 = 2 * ((a - 1) - (a + 1) * cos) / a0;
        a2 = ((a + 1) - (a - 1) * cos - sqA) / a0;
    }

    /** Filters {@code len} bytes of {@code pcm} in place; samples clamp at full scale. */
    public void process(byte[] pcm, int len) {
        int samples = len / BYTES_PER_SAMPLE;
        for (int i = 0; i < samples; i++) {
            int at = i * BYTES_PER_SAMPLE;
            double x = (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8)) / FULL_SCALE;
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;

            long s = Math.round(y * FULL_SCALE);
            s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
            pcm[at] = (byte) s;
            pcm[at + 1] = (byte) (s >> 8);
        }
    }
}
