package com.ripostelabs.projection.ns;

/**
 * Integer-ratio resampling between the mic's rate and the denoiser's 48 kHz.
 *
 * <pre>
 *   16 kHz ──up(3)──▶ 48 kHz ──(denoise)──▶ 48 kHz ──down(3)──▶ 16 kHz
 * </pre>
 *
 * One Kaiser-windowed sinc low-pass serves both directions: it removes the images that
 * zero-stuffing makes going up, and the band above the low rate's Nyquist going down. Its
 * cutoff sits at that Nyquist (8 kHz for 16 kHz), the whole band CarPlay's wideband voice
 * carries. Each direction delays by {@code (taps - 1) / 2} samples at 48 kHz, 1 ms at 16 kHz.
 *
 * Streaming: the history of each direction carries over between calls, so chunks of any size
 * join without a seam. Every buffer is allocated here; {@link #up} and {@link #down} allocate
 * nothing.
 */
public final class Resampler {

    /** Taps per polyphase branch: 32 gives about 70 dB of image rejection. */
    static final int TAPS_PER_PHASE = 32;
    private static final double KAISER_BETA = 6.8;

    private final int factor;
    private final int taps;
    private final float[] filter;
    /** Low-rate input with its last {@code TAPS_PER_PHASE - 1} samples in front. */
    private final float[] upLine;
    /** High-rate input with its last {@code taps - 1} samples in front. */
    private final float[] downLine;
    private final int maxLow;

    /**
     * @param factor the high rate over the low rate, 1 or more
     * @param maxLow the most low-rate samples one call will carry
     */
    public Resampler(int factor, int maxLow) {
        if (factor < 1 || maxLow < 1) {
            throw new IllegalArgumentException("factor " + factor + ", maxLow " + maxLow);
        }
        this.factor = factor;
        this.maxLow = maxLow;
        this.taps = TAPS_PER_PHASE * factor;
        this.filter = design(factor, taps);
        this.upLine = new float[TAPS_PER_PHASE - 1 + maxLow];
        this.downLine = new float[taps - 1 + maxLow * factor];
    }

    public int factor() {
        return factor;
    }

    /** Samples at the high rate that a round trip (up then down) delays the signal by. */
    public int roundTripDelay() {
        return factor == 1 ? 0 : taps - 1;
    }

    /** {@code n} low-rate samples in, {@code n * factor} high-rate samples out. */
    public void up(float[] in, int n, float[] out) {
        check(n);
        if (factor == 1) {
            System.arraycopy(in, 0, out, 0, n);
            return;
        }

        int hist = TAPS_PER_PHASE - 1;
        System.arraycopy(in, 0, upLine, hist, n);

        // Output phase p of input sample i is sum_k h[k*L + p] * x[i - k], scaled by L to
        // make up for the zeros the rate change stuffs in between.
        for (int i = 0; i < n; i++) {
            int newest = hist + i;
            for (int p = 0; p < factor; p++) {
                float acc = 0f;
                for (int k = 0; k < TAPS_PER_PHASE; k++) {
                    acc += filter[k * factor + p] * upLine[newest - k];
                }
                out[i * factor + p] = acc * factor;
            }
        }

        System.arraycopy(upLine, n, upLine, 0, hist);
    }

    /** {@code n * factor} high-rate samples in, {@code n} low-rate samples out. */
    public void down(float[] in, int n, float[] out) {
        check(n);
        if (factor == 1) {
            System.arraycopy(in, 0, out, 0, n);
            return;
        }

        int hist = taps - 1;
        int high = n * factor;
        System.arraycopy(in, 0, downLine, hist, high);

        // Only every factor-th output of the low-pass is kept, so only those are computed.
        for (int m = 0; m < n; m++) {
            int newest = hist + m * factor + factor - 1;
            float acc = 0f;
            for (int j = 0; j < taps; j++) {
                acc += filter[j] * downLine[newest - j];
            }
            out[m] = acc;
        }

        System.arraycopy(downLine, high, downLine, 0, hist);
    }

    private void check(int n) {
        if (n < 0 || n > maxLow) {
            throw new IllegalArgumentException(n + " samples, at most " + maxLow);
        }
    }

    /** Windowed sinc with cutoff at the low rate's Nyquist, unity gain at DC. */
    private static float[] design(int factor, int taps) {
        float[] h = new float[taps];
        if (factor == 1) {
            return h;
        }

        double cutoff = 0.5 / factor;
        double mid = (taps - 1) / 2.0;
        double norm = bessel0(KAISER_BETA);
        double sum = 0;
        double[] raw = new double[taps];
        for (int i = 0; i < taps; i++) {
            double t = i - mid;
            double sinc = t == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * t) / (Math.PI * t);
            double r = 2 * i / (double) (taps - 1) - 1;
            double window = bessel0(KAISER_BETA * Math.sqrt(1 - r * r)) / norm;
            raw[i] = sinc * window;
            sum += raw[i];
        }
        for (int i = 0; i < taps; i++) {
            h[i] = (float) (raw[i] / sum);
        }
        return h;
    }

    /** Modified Bessel function of the first kind, order 0, by its power series. */
    private static double bessel0(double x) {
        double term = 1;
        double sum = 1;
        for (int k = 1; k < 32; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
        }
        return sum;
    }
}
