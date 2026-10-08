package com.ripostelabs.projection.ns;

/** The treble shelf: speech's upper band up by GAIN_DB, the low band left alone, no wild output. */
public final class VoiceClarityTest {

    private static final int RATE = 16000;

    public static void main(String[] args) {
        lowFrequenciesPassUnchanged();
        trebleRisesByTheGain();
        loudInputClampsInsteadOfWrapping();
        System.out.println("ok   VoiceClarityTest " + Check.count + " checks");
    }

    private static byte[] tone(int hz, double amp, int samples) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int s = (int) Math.round(amp * 32767 * Math.sin(2 * Math.PI * hz * i / RATE));
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    /** Gain in dB of the shelf on a tone, measured past the filter's start. */
    private static double gainDb(int hz, double amp) {
        int n = RATE;
        byte[] in = tone(hz, amp, n);
        byte[] out = in.clone();
        new VoiceClarity(RATE).process(out, out.length);
        return 20 * Math.log10(rms(out, n / 2, n) / rms(in, n / 2, n));
    }

    private static double rms(byte[] b, int from, int to) {
        double e = 0;
        for (int i = from; i < to; i++) {
            int s = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
            e += (double) s * s;
        }
        return Math.sqrt(e / (to - from));
    }

    private static void lowFrequenciesPassUnchanged() {
        Check.near(0.0, gainDb(200, 0.1), 0.5, "200 Hz");
    }

    // 3.5 kHz, in the band the mic loses, sits on the shelf's top.
    private static void trebleRisesByTheGain() {
        Check.near(VoiceClarity.GAIN_DB, gainDb(3500, 0.01), 1.0, "3.5 kHz");
    }

    private static void loudInputClampsInsteadOfWrapping() {
        byte[] b = tone(4000, 0.9, RATE / 10);
        new VoiceClarity(RATE).process(b, b.length);
        int min = 0;
        for (int i = 0; i < b.length / 2; i++) {
            min = Math.min(min, (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8)));
        }
        Check.that(min < -30000, "a clamped wave still swings negative, it did not wrap");
    }
}
