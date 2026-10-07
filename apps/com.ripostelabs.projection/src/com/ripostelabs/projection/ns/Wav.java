package com.ripostelabs.projection.ns;

/** A WAV file's bytes for 16-bit PCM, for callers outside this package (the mic source test). */
public final class Wav {

    private Wav() {
    }

    public static byte[] of(byte[] pcm, int len, int sampleRate, int channels) {
        return CallCheck.wav(pcm, len, sampleRate, channels);
    }
}
