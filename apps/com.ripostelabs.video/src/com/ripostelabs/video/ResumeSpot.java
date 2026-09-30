package com.ripostelabs.video;

/**
 * Where a video picks up after the app restarts or the car goes through ACC off and on. Pure
 * logic so the harness can test it; the activities own the prefs and the player.
 *
 * Stock parity: videoplayer/VideoPlayerService.java:993-1001 and
 * zxwmediaplaylib/utils/MediaListUtil.java:350-352 saved the file and time and resumed them.
 */
final class ResumeSpot {

    /** Prefs file and keys the player writes and the list reads. */
    static final String PREFS = "resume";
    static final String KEY_URI = "uri";
    static final String KEY_POS = "position_ms";

    /** Intent extra: where the player starts the first video, in ms. */
    static final String EXTRA_START = "start_ms";

    /** The saved file is not in the library any more. */
    static final int GONE = -1;

    /** Nothing worth continuing: the video barely started, or only the credits were left. */
    static final long WATCHED = -1;

    /** Closer than this to the end counts as finished: the tail of a film is its credits. */
    static final long END_GUARD_MS = 30_000;

    /** Earlier than this is a false start, not a place to come back to. */
    static final long START_GUARD_MS = 10_000;

    private ResumeSpot() {
    }

    /** Row of {@code uri} in the loaded list, or {@link #GONE}. */
    static int indexOf(String[] uris, String uri) {
        if (uri == null) {
            return GONE;
        }

        for (int i = 0; i < uris.length; i++) {
            if (uri.equals(uris[i])) {
                return i;
            }
        }
        return GONE;
    }

    /** Position to continue from, or {@link #WATCHED} when there is nothing to continue. */
    static long seekTo(long savedMs, long durationMs) {
        if (savedMs < START_GUARD_MS) {
            return WATCHED;
        }

        // MediaStore reports 0 for a file it could not measure: nothing to check against.
        if (durationMs > 0 && savedMs > durationMs - END_GUARD_MS) {
            return WATCHED;
        }
        return savedMs;
    }
}
