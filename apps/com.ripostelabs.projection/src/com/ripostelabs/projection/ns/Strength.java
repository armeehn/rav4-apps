package com.ripostelabs.projection.ns;

/**
 * How far the suppressor may push noise down. A limit keeps a little of the cabin in the
 * signal, which sounds more natural to the far end than a gate snapping to silence between
 * words; FULL lets the network go as deep as it wants.
 *
 * The limit is a dry/wet mix: {@code out = (1 - m) * denoised + m * original}, with
 * {@code m = 10^(-limit/20)}. Where the network removes everything, the original at gain m
 * remains, i.e. at most {@code limit} dB of attenuation; where it keeps the signal, the mix is
 * the signal.
 */
public enum Strength {
    LIGHT(12),
    MEDIUM(24),
    FULL(0);

    /** Attenuation limit in dB; 0 means none. */
    public final int limitDb;

    Strength(int limitDb) {
        this.limitDb = limitDb;
    }

    /** The share of the original signal mixed back in. */
    public float dryMix() {
        return limitDb == 0 ? 0f : (float) Math.pow(10, -limitDb / 20.0);
    }

    /** A stored name back to a strength; anything unknown is the default. */
    public static Strength parse(String name, Strength fallback) {
        if (name == null) {
            return fallback;
        }
        for (Strength s : values()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return fallback;
    }
}
