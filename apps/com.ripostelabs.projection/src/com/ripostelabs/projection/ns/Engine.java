package com.ripostelabs.projection.ns;

/**
 * A denoiser at 48 kHz: {@link #FRAME} samples in int16 scale (±32768), processed in place,
 * the output {@link #delayFrames} frames behind the input. {@link RnNoise} on the car; a fake
 * in the tests.
 */
public interface Engine {

    int RATE = 48000;
    /** 10 ms at 48 kHz. */
    int FRAME = 480;

    void frame(float[] pcm);

    /** Whole frames between a sample going in and coming out; the dry mix waits as long. */
    int delayFrames();

    void close();
}
