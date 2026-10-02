package com.ripostelabs.projection.ns;

/**
 * The downlink queue the echo canceller reads its reference from: 10 ms frames in arrival
 * order, released only once the mic has read past their arrival, stale ones dropped.
 */
public final class EchoReferenceTest {

    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
        nothingBeforeAFormat();
        cutIntoTenMsFrames();
        partialFrameWaitsForTheRest();
        releasedOnlyAfterArrival();
        staleFramesAreDropped();
        overflowDropsTheOldest();
        stereoMediaFrames();
        unsupportedFormatQueuesNothing();
        formatChangeClears();
        System.out.println(Check.count + " assertions passed");
    }

    private static void nothingBeforeAFormat() {
        EchoReference ref = new EchoReference();
        ref.push(pcm(160, 1), 0, 320, 0);
        Check.that(!ref.poll(new EchoReference.Frame(), Long.MAX_VALUE), "no format, no frame");
    }

    private static void cutIntoTenMsFrames() {
        EchoReference ref = new EchoReference();
        ref.format(16000, 1);
        byte[] twenty = pcm(320, 1);
        ref.push(twenty, 0, twenty.length, 10 * MS);

        EchoReference.Frame f = new EchoReference.Frame();
        Check.that(ref.poll(f, 10 * MS), "first frame");
        Check.eq(160, f.samples, "10 ms at 16 kHz mono");
        Check.eq(16000, f.rate, "rate rides with the frame");
        Check.eq(1, f.pcm[0], "first sample");
        Check.eq(160, f.pcm[159], "last sample of the first frame");
        Check.that(ref.poll(f, 10 * MS), "second frame");
        Check.eq(161, f.pcm[0], "second frame continues the stream");
        Check.that(!ref.poll(f, 10 * MS), "drained");
    }

    private static void partialFrameWaitsForTheRest() {
        EchoReference ref = new EchoReference();
        ref.format(16000, 1);
        byte[] b = pcm(160, 1);
        ref.push(b, 0, 200, 10 * MS);
        EchoReference.Frame f = new EchoReference.Frame();
        Check.that(!ref.poll(f, 100 * MS), "100 samples are not a frame");

        ref.push(b, 200, 120, 30 * MS);
        Check.that(!ref.poll(f, 29 * MS), "the frame arrived with its last sample");
        Check.that(ref.poll(f, 30 * MS), "whole now");
        Check.eq(101, f.pcm[100], "carried samples joined in order");
    }

    /** A mic read at time t takes every frame that arrived by t, and none after. */
    private static void releasedOnlyAfterArrival() {
        EchoReference ref = new EchoReference();
        ref.format(16000, 1);
        byte[] b = pcm(160, 1);
        ref.push(b, 0, b.length, 50 * MS);
        EchoReference.Frame f = new EchoReference.Frame();
        Check.that(!ref.poll(f, 49 * MS), "not yet arrived for a mic read before it");
        Check.that(ref.poll(f, 50 * MS), "arrived");
    }

    private static void staleFramesAreDropped() {
        EchoReference ref = new EchoReference();
        ref.format(16000, 1);
        byte[] b = pcm(160, 1);
        for (int i = 0; i < 10; i++) {
            ref.push(b, 0, b.length, i * 100 * MS);
        }
        // Read at 1 s: frames older than MAX_AGE (500 ms) were played long ago.
        EchoReference.Frame f = new EchoReference.Frame();
        int got = 0;
        while (ref.poll(f, 1000 * MS)) {
            got++;
        }
        Check.eq(5, got, "frames from 500 ms to 900 ms");
        Check.eq(5, ref.takeDropped(), "the five older ones counted");
        Check.eq(0, ref.takeDropped(), "the count resets");
    }

    private static void overflowDropsTheOldest() {
        EchoReference ref = new EchoReference();
        ref.format(16000, 1);
        for (int i = 0; i < EchoReference.CAPACITY + 5; i++) {
            byte[] b = pcm(160, 1);
            b[0] = (byte) i;
            ref.push(b, 0, b.length, 0);
        }
        EchoReference.Frame f = new EchoReference.Frame();
        Check.that(ref.poll(f, 0), "a frame");
        Check.eq(5, f.pcm[0] & 0xff, "the five oldest were overwritten");
        Check.eq(5, ref.takeDropped(), "and counted");
    }

    private static void stereoMediaFrames() {
        EchoReference ref = new EchoReference();
        ref.format(48000, 2);
        byte[] b = pcm(960, 1);
        ref.push(b, 0, b.length, 0);
        EchoReference.Frame f = new EchoReference.Frame();
        Check.that(ref.poll(f, 0), "a stereo frame");
        Check.eq(960, f.samples, "10 ms of 48 kHz stereo, interleaved");
        Check.eq(2, f.channels, "channels ride with the frame");
    }

    private static void unsupportedFormatQueuesNothing() {
        EchoReference ref = new EchoReference();
        ref.format(44100, 6);
        byte[] b = pcm(441 * 6, 1);
        ref.push(b, 0, b.length, 0);
        Check.that(!ref.poll(new EchoReference.Frame(), 0), "six channels are not taken");
        Check.that(!EchoReference.supports(96000, 1), "above 48 kHz");
        Check.that(EchoReference.supports(44100, 2), "44.1 kHz stereo");
    }

    private static void formatChangeClears() {
        EchoReference ref = new EchoReference();
        ref.format(16000, 1);
        byte[] b = pcm(240, 1);
        ref.push(b, 0, b.length, 0);
        ref.format(48000, 2);
        Check.that(!ref.poll(new EchoReference.Frame(), 0), "frames and the carried half are gone");
    }

    /** s16 LE ramp 1, 2, 3, ... from {@code first}. */
    static byte[] pcm(int samples, int first) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int s = first + i;
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }
}
