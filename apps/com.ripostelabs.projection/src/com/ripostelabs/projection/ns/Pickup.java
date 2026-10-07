package com.ripostelabs.projection.ns;

/**
 * Which capture feeds the CarPlay mic.
 *
 * <pre>
 *   DIRECT       MIC source               ──▶ RNNoise ──▶ phone   (HAL device handset-mic)
 *   RECOGNITION  VOICE_RECOGNITION source ──▶ RNNoise ──▶ phone   (HAL voice-rec-mic route)
 *   UNPROCESSED  UNPROCESSED source       ──▶ RNNoise ──▶ phone   (no HAL effects at all)
 *   PLATFORM     VOICE_COMMUNICATION      ──▶ HAL AEC + NS ──▶ RNNoise ──▶ phone
 * </pre>
 *
 * PLATFORM is the vendor HAL's ECNS in the audio DSP. On this unit its echo reference is a
 * capture port the speaker-mic route never feeds, and calls through it sounded "underwater" on
 * stock firmware too. DIRECT replaced it, but its HAL device is handset-mic, not the speaker-mic
 * route the vendor path used, and calls on it peaked 20 to 30 dB lower and still sounded
 * underwater (car, 2026-10-02 19:06). RECOGNITION and UNPROCESSED are the other two routes to
 * the cabin mic. The mic source test ({@code MicProbe}) on the bench (2026-10-07, room noise)
 * read DIRECT -63, RECOGNITION -49, UNPROCESSED -47 and PLATFORM -30 dBFS RMS, so UNPROCESSED,
 * the loudest route with no HAL effects, is the default.
 */
public enum Pickup {
    DIRECT,
    RECOGNITION,
    UNPROCESSED,
    PLATFORM;

    /** True when the HAL's own echo canceller and suppressor run on the capture. */
    public boolean vendorProcessing() {
        return this == PLATFORM;
    }

    /** A stored name back to a pickup; anything unknown is the default. */
    public static Pickup parse(String name) {
        if (name == null) {
            return UNPROCESSED;
        }
        for (Pickup p : values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return UNPROCESSED;
    }
}
