package com.ripostelabs.projection.ns;

/** Peak and RMS in dBFS, the numbers the mic source test shows per capture. */
public final class MicLevelTest {

    public static void main(String[] args) {
        silenceIsTheFloor();
        fullScaleIsZero();
        aSquareWaveHasEqualPeakAndRms();
        theCallLevelReadsMinus35();
        System.out.println("ok   MicLevelTest " + Check.count + " checks");
    }

    private static byte[] pcm(short... samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[2 * i] = (byte) samples[i];
            b[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return b;
    }

    private static void silenceIsTheFloor() {
        MicLevel l = MicLevel.of(pcm((short) 0, (short) 0), 0, 4);
        Check.near(MicLevel.FLOOR_DBFS, l.peakDbfs, 0, "silent peak");
        Check.near(MicLevel.FLOOR_DBFS, l.rmsDbfs, 0, "silent rms");
        Check.near(MicLevel.FLOOR_DBFS, MicLevel.of(new byte[0], 0, 0).peakDbfs, 0, "empty");
    }

    private static void fullScaleIsZero() {
        Check.near(0.0, MicLevel.of(pcm((short) -32768), 0, 2).peakDbfs, 0.001, "negative full scale");
    }

    private static void aSquareWaveHasEqualPeakAndRms() {
        MicLevel l = MicLevel.of(pcm((short) 16384, (short) -16384, (short) 16384, (short) -16384), 0, 8);
        Check.near(-6.02, l.peakDbfs, 0.01, "half scale peak");
        Check.near(-6.02, l.rmsDbfs, 0.01, "half scale rms");
    }

    // The 19:06 call's loud second: peak 584 of 32767.
    private static void theCallLevelReadsMinus35() {
        Check.near(-34.98, MicLevel.of(pcm((short) 584), 0, 2).peakDbfs, 0.01, "call peak");
    }
}
