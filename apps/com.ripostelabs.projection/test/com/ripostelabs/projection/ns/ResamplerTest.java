package com.ripostelabs.projection.ns;

/**
 * The resampler between the mic rate and 48 kHz: sizes per rate, a voice-band tone surviving
 * the round trip, and chunked streaming matching one long pass.
 */
public final class ResamplerTest {

    private static final int RATE = 16000;
    private static final int CHUNK = 160;

    public static void main(String[] args) {
        sizesPerRate();
        passThroughAt48k();
        dcKeepsItsLevel();
        toneSurvivesRoundTrip();
        imageAboveNyquistIsRemoved();
        chunksJoinWithoutSeam();
        oversizedCallIsRefused();
        System.out.println(Check.count + " assertions passed");
    }

    /** 8, 16, 24 kHz go up by 6, 3, 2: a 10 ms chunk is always one 480-sample engine frame. */
    private static void sizesPerRate() {
        for (int rate : new int[] {8000, 16000, 24000, 48000}) {
            int n = rate / 100;
            Resampler r = new Resampler(Engine.RATE / rate, n);
            float[] high = new float[n * r.factor()];
            r.up(new float[n], n, high);
            Check.eq(Engine.FRAME, high.length, rate + " Hz chunk at 48 kHz");
            float[] low = new float[n];
            r.down(high, n, low);
            Check.eq(n, low.length, rate + " Hz chunk back");
        }
    }

    private static void passThroughAt48k() {
        Resampler r = new Resampler(1, 4);
        float[] out = new float[4];
        r.up(new float[] {1, -2, 3, -4}, 4, out);
        Check.that(out[0] == 1 && out[1] == -2 && out[2] == 3 && out[3] == -4, "48 kHz is a copy");
        Check.eq(0, r.roundTripDelay(), "no delay at 48 kHz");
    }

    private static void dcKeepsItsLevel() {
        Resampler r = new Resampler(3, CHUNK);
        float[] in = new float[CHUNK];
        java.util.Arrays.fill(in, 1000f);
        float[] high = new float[CHUNK * 3];
        float[] low = new float[CHUNK];
        for (int i = 0; i < 4; i++) {
            r.up(in, CHUNK, high);
            r.down(high, CHUNK, low);
        }
        Check.near(1000, high[CHUNK * 3 - 1], 1, "DC up");
        Check.near(1000, low[CHUNK - 1], 1, "DC round trip");
    }

    /** 1 kHz in, 1 kHz out at the same level, once the filter delay has passed. */
    private static void toneSurvivesRoundTrip() {
        float[] out = roundTrip(sine(1000, RATE, RATE), CHUNK);
        int skip = RATE / 10;
        double gainDb = Check.db(Check.rms(out, skip, RATE) / Check.rms(sine(1000, RATE, RATE), skip, RATE));
        Check.near(0, gainDb, 0.2, "1 kHz round-trip gain (dB)");
    }

    /** Going up, the 16 kHz signal's image at 15 kHz must be gone from the 48 kHz stream. */
    private static void imageAboveNyquistIsRemoved() {
        Resampler r = new Resampler(3, RATE);
        float[] in = sine(1000, RATE, RATE);
        float[] high = new float[RATE * 3];
        r.up(in, RATE, high);
        // Project onto 15 kHz (the first image of 1 kHz at 16 kHz) and onto 1 kHz itself.
        double image = magnitudeAt(high, 15000, Engine.RATE);
        double tone = magnitudeAt(high, 1000, Engine.RATE);
        Check.that(Check.db(image / tone) < -60, "image at 15 kHz is " + Check.db(image / tone) + " dB");
    }

    /** Two 10 ms calls and one 20 ms call give the same samples: no seam between chunks. */
    private static void chunksJoinWithoutSeam() {
        float[] in = sine(440, RATE, CHUNK * 4);
        float[] a = roundTrip(in, CHUNK);
        float[] b = roundTrip(in, CHUNK * 2);
        for (int i = 0; i < a.length; i++) {
            Check.near(a[i], b[i], 1e-3, "sample " + i);
        }
    }

    private static void oversizedCallIsRefused() {
        Resampler r = new Resampler(3, CHUNK);
        try {
            r.up(new float[CHUNK + 1], CHUNK + 1, new float[(CHUNK + 1) * 3]);
            Check.that(false, "more than maxLow samples accepted");
        } catch (IllegalArgumentException expected) {
            Check.that(true, "more than maxLow samples refused");
        }
    }

    private static float[] roundTrip(float[] in, int step) {
        Resampler r = new Resampler(3, step);
        float[] high = new float[step * 3];
        float[] low = new float[step];
        float[] out = new float[in.length];
        float[] part = new float[step];
        for (int off = 0; off + step <= in.length; off += step) {
            System.arraycopy(in, off, part, 0, step);
            r.up(part, step, high);
            r.down(high, step, low);
            System.arraycopy(low, 0, out, off, step);
        }
        return out;
    }

    static float[] sine(double hz, int rate, int n) {
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) (10000 * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return x;
    }

    private static double magnitudeAt(float[] x, double hz, int rate) {
        double re = 0;
        double im = 0;
        for (int i = 0; i < x.length; i++) {
            re += x[i] * Math.cos(2 * Math.PI * hz * i / rate);
            im += x[i] * Math.sin(2 * Math.PI * hz * i / rate);
        }
        return Math.hypot(re, im);
    }
}
