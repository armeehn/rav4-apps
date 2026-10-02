package com.ripostelabs.projection.ns;

/**
 * The call audio check records only when armed by a person, stops by itself after its length of
 * raw mic, and hands back WAV files a player opens.
 */
public final class CallCheckTest {

    private static final int RATE = 16000;

    public static void main(String[] args) {
        nothingRecordsUntilArmed();
        aTakeEndsOnTheRawMic();
        echoCancelledTapIsTheMicsTwin();
        secondsLeftCountsDown();
        aShortCallKeepsWhatItHas();
        wavHeaderIsCanonical();
        aRunningTakeCannotBeArmedAgain();
        resetFreesForTheNextTake();
        System.out.println("ok   CallCheckTest " + Check.count + " checks");
    }

    private static void nothingRecordsUntilArmed() {
        CallCheck c = new CallCheck(1);
        c.feed(CallCheck.Tap.RAW, new byte[640], 0, 640);
        Check.that(c.state() == CallCheck.State.IDLE, "idle until armed");
        Check.that(c.wav(CallCheck.Tap.RAW) == null, "no file without a take");
    }

    private static void aTakeEndsOnTheRawMic() {
        CallCheck c = new CallCheck(1);
        Check.that(c.arm(RATE, 0, 0), "armed");
        byte[] frame = new byte[640];
        for (int i = 0; i < 49; i++) {
            c.feed(CallCheck.Tap.RAW, frame, 0, frame.length);
            c.feed(CallCheck.Tap.PROCESSED, frame, 0, frame.length);
        }
        Check.that(c.state() == CallCheck.State.RECORDING, "49 frames of 50 still recording");
        c.feed(CallCheck.Tap.RAW, frame, 0, frame.length);
        c.feed(CallCheck.Tap.PROCESSED, frame, 0, frame.length);
        Check.that(c.state() == CallCheck.State.DONE, "one second of raw mic ends it");
        Check.eq(44 + RATE * 2, c.wav(CallCheck.Tap.RAW).length, "raw file is one second");
        Check.eq(44 + RATE * 2, c.wav(CallCheck.Tap.PROCESSED).length, "processed file is one second");
        Check.that(c.wav(CallCheck.Tap.DOWNLINK) == null, "no downlink, no file");
        c.feed(CallCheck.Tap.RAW, frame, 0, frame.length);
        Check.eq(44 + RATE * 2, c.wav(CallCheck.Tap.RAW).length, "nothing appended after the end");
    }

    /** Between RAW and PROCESSED: what the echo canceller left, at the mic's rate. */
    private static void echoCancelledTapIsTheMicsTwin() {
        CallCheck c = new CallCheck(1);
        Check.that(c.arm(RATE, 48000, 2), "armed");
        byte[] frame = new byte[640];
        for (int i = 0; i < 50; i++) {
            c.feed(CallCheck.Tap.RAW, frame, 0, frame.length);
            c.feed(CallCheck.Tap.ECHO_CANCELLED, frame, 0, frame.length);
            c.feed(CallCheck.Tap.PROCESSED, frame, 0, frame.length);
        }
        Check.that(c.state() == CallCheck.State.DONE, "done");
        Check.eq(44 + RATE * 2, c.wav(CallCheck.Tap.ECHO_CANCELLED).length, "echo-cancelled file is one second");
    }

    private static void secondsLeftCountsDown() {
        CallCheck c = new CallCheck(20);
        Check.eq(0, c.secondsLeft(), "idle has nothing left");
        c.arm(RATE, 0, 0);
        Check.eq(20, c.secondsLeft(), "full take ahead");
        c.feed(CallCheck.Tap.RAW, new byte[RATE * 2], 0, RATE * 2);
        Check.eq(19, c.secondsLeft(), "one second in");
    }

    private static void aShortCallKeepsWhatItHas() {
        CallCheck c = new CallCheck(20);
        c.arm(RATE, 48000, 2);
        c.feed(CallCheck.Tap.RAW, new byte[640], 0, 640);
        c.feed(CallCheck.Tap.DOWNLINK, new byte[3840], 0, 3840);
        c.finish();
        Check.that(c.state() == CallCheck.State.DONE, "call over ends the take");
        Check.eq(44 + 640, c.wav(CallCheck.Tap.RAW).length, "partial raw kept");
        Check.eq(44 + 3840, c.wav(CallCheck.Tap.DOWNLINK).length, "partial downlink kept");
    }

    private static void wavHeaderIsCanonical() {
        byte[] w = CallCheck.wav(new byte[] {1, 2, 3, 4}, 4, 48000, 2);
        Check.that("RIFF".equals(new String(w, 0, 4)), "riff");
        Check.eq(40, le32(w, 4), "riff size");
        Check.that("WAVEfmt ".equals(new String(w, 8, 8)), "wave fmt");
        Check.eq(1, le16(w, 20), "pcm");
        Check.eq(2, le16(w, 22), "channels");
        Check.eq(48000, le32(w, 24), "rate");
        Check.eq(192000, le32(w, 28), "byte rate");
        Check.eq(4, le16(w, 32), "block align");
        Check.eq(16, le16(w, 34), "bits");
        Check.that("data".equals(new String(w, 36, 4)), "data");
        Check.eq(4, le32(w, 40), "data size");
        Check.eq(4, w[47], "payload");
    }

    private static void aRunningTakeCannotBeArmedAgain() {
        CallCheck c = new CallCheck(20);
        Check.that(c.arm(RATE, 0, 0), "first arm");
        Check.that(!c.arm(RATE, 0, 0), "second arm refused while recording");
        Check.that(!new CallCheck(20).arm(0, 0, 0), "no mic rate, no take");
    }

    private static void resetFreesForTheNextTake() {
        CallCheck c = new CallCheck(1);
        c.arm(RATE, 0, 0);
        c.finish();
        c.reset();
        Check.that(c.state() == CallCheck.State.IDLE, "idle after reset");
        Check.that(c.arm(RATE, 0, 0), "armed again");
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static int le32(byte[] b, int at) {
        return le16(b, at) | le16(b, at + 2) << 16;
    }
}
