package com.ripostelabs.music;

import java.util.Random;

/**
 * The loop modes and the order they play in. Pure logic so the harness can test it; the
 * Activity owns the player and the prefs.
 *
 * Stock parity: 0 all, 1 one, 2 random, 3 folder (zxwmediaplaylib/utils/FileUtil.java:262-272,
 * musicplayer/MusicPlayerService.java:492), with the wheel's repeat key (MCU key 29) cycling
 * the repeat modes and the shuffle key (30) selecting random (MusicPlayerService.java:544-550).
 */
final class PlayOrder {

    /** No track to play (an empty library). */
    static final int NONE = -1;

    /** Why the player is moving on. Repeat-one only holds a track that ended by itself. */
    enum Cause {
        ENDED,
        SKIP,
    }

    enum Mode {
        ALL,
        ONE,
        FOLDER,
        SHUFFLE;

        /** The on-screen button: every mode in turn. */
        Mode onButton() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** Wheel repeat key: all, one, folder, all. From shuffle it starts at one. */
        Mode onRepeatKey() {
            switch (this) {
                case ALL:
                    return ONE;
                case ONE:
                    return FOLDER;
                case FOLDER:
                    return ALL;
                default:
                    return ONE;
            }
        }

        /** Wheel shuffle key: a toggle, so the same key gets the driver back out. */
        Mode onShuffleKey() {
            return this == SHUFFLE ? ALL : SHUFFLE;
        }

        /** The mode stored in prefs, or ALL for a missing or retired name. */
        static Mode parse(String name) {
            for (Mode m : values()) {
                if (m.name().equals(name)) {
                    return m;
                }
            }
            return ALL;
        }
    }

    private PlayOrder() {
    }

    /**
     * The row after {@code current}. {@code folders[i]} is row i's folder (null if unknown);
     * rows are title-sorted, so a folder's tracks need not be adjacent.
     */
    static int next(Mode mode, int current, String[] folders, Cause cause, Random random) {
        int n = folders.length;
        if (n == 0) {
            return NONE;
        }
        if (current < 0 || current >= n) {
            return 0;
        }

        if (mode == Mode.ONE && cause == Cause.ENDED) {
            return current;
        }

        // Never the same track twice in a row while there is a choice.
        if (mode == Mode.SHUFFLE) {
            if (n == 1) {
                return current;
            }
            int pick = random.nextInt(n - 1);
            return pick >= current ? pick + 1 : pick;
        }

        if (mode == Mode.FOLDER && folders[current] != null) {
            return step(current, folders, 1);
        }
        return (current + 1) % n;
    }

    /** The row before {@code current}: in the same folder under FOLDER, otherwise the list. */
    static int previous(Mode mode, int current, String[] folders) {
        int n = folders.length;
        if (n == 0) {
            return NONE;
        }
        if (current < 0 || current >= n) {
            return n - 1;
        }

        if (mode == Mode.FOLDER && folders[current] != null) {
            return step(current, folders, -1);
        }
        return (current - 1 + n) % n;
    }

    /** Walk the list by {@code dir}, wrapping, to the next row in the same folder. */
    private static int step(int current, String[] folders, int dir) {
        int n = folders.length;
        for (int k = 1; k <= n; k++) {
            int i = ((current + dir * k) % n + n) % n;
            if (folders[current].equals(folders[i])) {
                return i;
            }
        }
        return current;
    }
}
