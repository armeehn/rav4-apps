package com.ripostelabs.projection.ns;

/**
 * Which RNNoise weights the mic runs. STANDARD is the little model compiled into the library;
 * CAR_TUNED is the newest model fine-tuned on this car's road noise (ns-train/), downloaded by
 * the app and kept in {@link ModelStore}. Without a usable car-tuned model the mic runs
 * STANDARD.
 */
public enum Model {
    STANDARD,
    CAR_TUNED;

    /** A stored name back to a model; anything unknown is the fallback. */
    public static Model parse(String name, Model fallback) {
        if (name == null) {
            return fallback;
        }
        for (Model m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return fallback;
    }
}
