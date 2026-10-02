package com.ripostelabs.projection.ns;

/**
 * An acoustic echo canceller on 10 ms frames: {@link Aec3} on the car, a fake in the tests.
 * The downlink goes in through {@link #render} before the mic frames it can echo in, the mic
 * through {@link #capture}, cleaned in place.
 */
public interface Canceller {

    int FRAMES_PER_SECOND = 100;

    /** Indexes into the array {@link #stats} fills. */
    int STAT_ERLE_DB = 0;
    int STAT_ERL_DB = 1;
    int STAT_DELAY_MS = 2;
    int STATS = 3;
    /** A statistic the canceller does not know yet. */
    float UNKNOWN = -1000f;

    /** One 10 ms downlink frame, interleaved s16 in its own format. */
    void render(short[] far, int rate, int channels);

    /** One 10 ms mono mic frame at the capture rate, echo removed in place. */
    void capture(short[] near);

    /** Echo return loss enhancement, echo return loss (dB) and the delay it found (ms). */
    void stats(float[] out);

    void close();
}
