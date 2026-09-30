package com.ripostelabs.music;

/**
 * Where the player picks up after a restart, an ACC cycle or a rescan. A wrong answer either
 * plays the wrong song, keeps playing a track whose USB stick has gone, or resumes one second
 * before the end so the car jumps straight to the next track.
 */
public final class ResumePointTest {

    private static final long MINUTE = 60_000;

    public static void main(String[] args) {
        long[] ids = {11, 42, 7};

        // The saved track is still in the library: its row, wherever the sort put it.
        expect(1, ResumePoint.indexOf(ids, 42));

        // The saved track has gone (stick pulled, file deleted): nothing to resume.
        expect(ResumePoint.GONE, ResumePoint.indexOf(ids, 99));

        // Nothing saved yet (first run).
        expect(ResumePoint.GONE, ResumePoint.indexOf(ids, ResumePoint.NONE));

        // An empty library (no stick at all).
        expect(ResumePoint.GONE, ResumePoint.indexOf(new long[0], 42));

        // Mid-track: seek back to where it was.
        expect(MINUTE, ResumePoint.seekTo(MINUTE, 4 * MINUTE));

        // Inside the end guard: restart the track rather than skip it at once.
        expect(0, ResumePoint.seekTo(4 * MINUTE - 1000, 4 * MINUTE));

        // Past the end (the file was re-encoded shorter) or negative: restart.
        expect(0, ResumePoint.seekTo(5 * MINUTE, 4 * MINUTE));
        expect(0, ResumePoint.seekTo(-5, 4 * MINUTE));

        // Unknown duration (MediaStore said 0): trust the saved position.
        expect(MINUTE, ResumePoint.seekTo(MINUTE, 0));

        // Eject: stop only when the playing file sits on the volume going away.
        String stick = "/storage/102D-17F0";
        expectTrue(ResumePoint.onVolume(stick + "/Music/a.mp3", stick));
        expectFalse(ResumePoint.onVolume("/storage/emulated/0/Music/a.mp3", stick));
        expectFalse(ResumePoint.onVolume("/storage/102D-17F01/Music/a.mp3", stick));
        expectFalse(ResumePoint.onVolume(null, stick));
        expectFalse(ResumePoint.onVolume(stick + "/a.mp3", null));

        System.out.println("ResumePointTest: ok");
    }

    private static void expectTrue(boolean got) {
        if (!got) {
            throw new AssertionError("want true");
        }
    }

    private static void expectFalse(boolean got) {
        if (got) {
            throw new AssertionError("want false");
        }
    }

    private static void expect(long want, long got) {
        if (want != got) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
