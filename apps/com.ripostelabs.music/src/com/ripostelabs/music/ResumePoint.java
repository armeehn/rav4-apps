package com.ripostelabs.music;

/**
 * Where playback picks up after the app restarts, the car goes through ACC off and on, or the
 * library is rescanned (a USB stick in or out). Pure logic so the harness can test it; the
 * Activity owns the prefs and the player.
 *
 * Stock parity: the szchoiceway player saved the path and time the same way
 * (musicplayer/MusicPlayerService.java:1085, zxwmediaplaylib/utils/MediaListUtil.java:340-352).
 */
final class ResumePoint {

    /** No track saved yet. MediaStore ids start at 1, so -1 is never a real row. */
    static final long NONE = -1;

    /** The saved or current track is not in the library any more. */
    static final int GONE = -1;

    /**
     * Closer than this to the end, resume from the start instead. Seeking to 4:58 of a 5:00 song
     * would play two seconds and skip, which reads as "it forgot where I was".
     */
    static final long END_GUARD_MS = 5_000;

    private ResumePoint() {
    }

    /** Row of {@code id} in the freshly loaded library, or {@link #GONE}. */
    static int indexOf(long[] ids, long id) {
        if (id == NONE) {
            return GONE;
        }

        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return GONE;
    }

    /**
     * Whether a file sits on the volume that is going away. Checked on the eject broadcast,
     * which arrives before the unmount: stopping then beats the player erroring on a vanished
     * file and skipping on to the next track. Stock does the same prefix test
     * (musicplayer/MusicPlayerService.java:790-808).
     */
    static boolean onVolume(String file, String volume) {
        if (file == null || volume == null) {
            return false;
        }
        return file.startsWith(volume + "/");
    }

    /** Position to seek to: the saved one, or 0 when it is invalid or inside the end guard. */
    static long seekTo(long savedMs, long durationMs) {
        if (savedMs <= 0) {
            return 0;
        }

        // MediaStore reports 0 for a file it could not measure: nothing to check against.
        if (durationMs <= 0) {
            return savedMs;
        }

        if (savedMs > durationMs - END_GUARD_MS) {
            return 0;
        }
        return savedMs;
    }
}
