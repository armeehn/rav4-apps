package com.ripostelabs.projection.ns;

/**
 * Which capture feeds the CarPlay mic.
 *
 * <pre>
 *   DIRECT    cabin mic ──▶ RNNoise ──▶ phone           (the phone runs its own call processing)
 *   PLATFORM  cabin mic ──▶ HAL AEC + NS ──▶ RNNoise ──▶ phone
 * </pre>
 *
 * PLATFORM is the voice-communication source: the vendor HAL's ECNS in the audio DSP. On this
 * unit its echo reference is a capture port the speaker-mic route never feeds, and calls
 * through it sounded "underwater" on stock firmware too, so DIRECT is the default and PLATFORM
 * stays for the owner's A/B.
 */
public enum Pickup {
    DIRECT,
    PLATFORM;

    /** True when the HAL's own echo canceller and suppressor run on the capture. */
    public boolean vendorProcessing() {
        return this == PLATFORM;
    }

    /** A stored name back to a pickup; anything unknown is the default. */
    public static Pickup parse(String name) {
        if (name == null) {
            return DIRECT;
        }
        for (Pickup p : values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return DIRECT;
    }
}
