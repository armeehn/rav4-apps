package com.ripostelabs.projection.ns;

/**
 * Peak and RMS of 16-bit little-endian PCM in dBFS, for the mic source test. Full scale is 32768,
 * so a sample of 32767 reads about 0 dBFS and silence reads {@link #FLOOR_DBFS}.
 *
 * <p>Speech close to a working cabin mic peaks around -20 to -6 dBFS. The CarPlay calls on the
 * plain mic peaked near -35 dBFS (car, 2026-10-02 19:06), which is what this is for.
 */
public final class MicLevel {

    /** What an all-zero buffer reads: below anything a 16-bit capture can show. */
    public static final double FLOOR_DBFS = -96.0;

    private static final double FULL_SCALE = 32768.0;
    private static final int BYTES_PER_SAMPLE = 2;

    public final double peakDbfs;
    public final double rmsDbfs;

    private MicLevel(double peakDbfs, double rmsDbfs) {
        this.peakDbfs = peakDbfs;
        this.rmsDbfs = rmsDbfs;
    }

    /** The level of {@code len} bytes of {@code pcm} from {@code off}; a trailing odd byte is ignored. */
    public static MicLevel of(byte[] pcm, int off, int len) {
        int samples = len / BYTES_PER_SAMPLE;
        if (samples == 0) {
            return new MicLevel(FLOOR_DBFS, FLOOR_DBFS);
        }

        int peak = 0;
        double energy = 0;
        for (int i = 0; i < samples; i++) {
            int at = off + i * BYTES_PER_SAMPLE;
            int s = (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8));
            peak = Math.max(peak, Math.abs(s));
            energy += (double) s * s;
        }

        return new MicLevel(dbfs(peak), dbfs(Math.sqrt(energy / samples)));
    }

    private static double dbfs(double amplitude) {
        if (amplitude <= 0) {
            return FLOOR_DBFS;
        }
        return Math.max(FLOOR_DBFS, 20 * Math.log10(amplitude / FULL_SCALE));
    }
}
