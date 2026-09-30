package com.ripostelabs.video;

import java.util.Random;

/**
 * What plays when a video ends. Pure logic so the harness can test it.
 *
 * Stock parity: videoplayer/VideoPlayerService.java:132, :224, :399-402 (all, one, random, saved
 * as SAVE_LAST_VIDEO_LOOP_MODE) and :456 (the wheel's shuffle key).
 */
enum LoopMode {
    ALL,
    ONE,
    SHUFFLE;

    /** The on-screen button: every mode in turn. */
    LoopMode onButton() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Wheel repeat key: all and one in turn. From shuffle it starts at one. */
    LoopMode onRepeatKey() {
        return this == ONE ? ALL : ONE;
    }

    /** Wheel shuffle key: a toggle, so the same key gets back out. */
    LoopMode onShuffleKey() {
        return this == SHUFFLE ? ALL : SHUFFLE;
    }

    /** The mode stored in prefs, or ALL for a missing or retired name. */
    static LoopMode parse(String name) {
        for (LoopMode m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return ALL;
    }

    /** The row after {@code current} (0 ≤ current < count) once it has ended. */
    int next(int current, int count, Random random) {
        if (this == ONE || count <= 1) {
            return current;
        }

        // Never the same clip twice in a row while there is a choice.
        if (this == SHUFFLE) {
            int pick = random.nextInt(count - 1);
            return pick >= current ? pick + 1 : pick;
        }
        return (current + 1) % count;
    }
}
