package com.ripostelabs.video;

/**
 * Where a film picks up after the app or the car restarts. A wrong answer offers to continue a
 * file that has gone, or "continues" in the closing credits so the film is over at once.
 */
public final class ResumeSpotTest {

    private static final long MINUTE = 60_000;

    public static void main(String[] args) {
        String[] uris = {"content://media/external/video/media/5", "content://media/external/video/media/9"};

        expect(1, ResumeSpot.indexOf(uris, "content://media/external/video/media/9"));
        expect(ResumeSpot.GONE, ResumeSpot.indexOf(uris, "content://media/external/video/media/7"));
        expect(ResumeSpot.GONE, ResumeSpot.indexOf(uris, null));
        expect(ResumeSpot.GONE, ResumeSpot.indexOf(new String[0], uris[0]));

        // Mid-film: offer to continue from there.
        expect(20 * MINUTE, ResumeSpot.seekTo(20 * MINUTE, 90 * MINUTE));

        // The last 30 s are credits: the film counts as watched, nothing to continue.
        expect(ResumeSpot.WATCHED, ResumeSpot.seekTo(90 * MINUTE - 10_000, 90 * MINUTE));

        // A few seconds in is not worth a Continue button.
        expect(ResumeSpot.WATCHED, ResumeSpot.seekTo(3_000, 90 * MINUTE));

        // Unknown duration (MediaStore said 0): trust the saved position.
        expect(20 * MINUTE, ResumeSpot.seekTo(20 * MINUTE, 0));

        System.out.println("ResumeSpotTest: ok");
    }

    private static void expect(long want, long got) {
        if (want != got) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
