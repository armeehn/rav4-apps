package com.ripostelabs.projection.ns;

/** The assertion vocabulary the tests share. A failure throws; that is the whole harness. */
final class Check {

    static int count;

    private Check() {
    }

    static void that(boolean ok, String what) {
        count++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void eq(long expected, long actual, String what) {
        that(expected == actual, what + ": expected " + expected + ", got " + actual);
    }

    static void near(double expected, double actual, double tolerance, String what) {
        that(Math.abs(expected - actual) <= tolerance,
                what + ": expected " + expected + " ± " + tolerance + ", got " + actual);
    }

    /** RMS of a float signal over [from, to). */
    static double rms(float[] x, int from, int to) {
        double e = 0;
        for (int i = from; i < to; i++) {
            e += (double) x[i] * x[i];
        }
        return Math.sqrt(e / Math.max(1, to - from));
    }

    static double db(double ratio) {
        return 20 * Math.log10(ratio);
    }
}
