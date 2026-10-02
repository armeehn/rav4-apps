package com.ripostelabs.projection.ns;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The wiring around the echo canceller, with a fake canceller so it runs on the JVM: which
 * formats it takes, that bypass leaves the PCM byte for byte, that the downlink reaches the
 * canceller before the mic it echoes in, and that the audio loop allocates nothing. The real
 * canceller's behaviour is measured offline (share/carlauncher/mic-ns/aec).
 */
public final class EchoPipelineTest {

    private static final int RATE = 16000;
    /** One MIC_DATA frame: 20 ms of 16 kHz mono s16. */
    private static final int FRAME_BYTES = RATE / 50 * 2;
    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
        formatsItTakes();
        missingLibraryBypasses();
        offIsBypass();
        unsupportedFormatBypassesAndCloses();
        renderBeforeTheCaptureItEchoesIn();
        noDownlinkStillCaptures();
        captureWritesBack();
        raggedTailGoesOutAsRecorded();
        statsComeFromTheCanceller();
        closeClosesTheCanceller();
        audioLoopAllocatesNothing();
        System.out.println(Check.count + " assertions passed");
    }

    private static void formatsItTakes() {
        Check.that(EchoPipeline.supports(16000, 1), "16 kHz mono");
        Check.that(EchoPipeline.supports(8000, 1), "8 kHz mono");
        Check.that(EchoPipeline.supports(48000, 1), "48 kHz mono");
        Check.that(!EchoPipeline.supports(44100, 1), "AEC3 runs at 8/16/32/48 kHz");
        Check.that(!EchoPipeline.supports(16000, 2), "stereo mic");
    }

    private static void missingLibraryBypasses() {
        Check.that(!Aec3.available(), "library is absent off the device");
        Check.that(Aec3.open(RATE) == null, "open without the library is null");
        EchoPipeline p = EchoPipeline.create(RATE, 1, Aec3.open(RATE), new EchoReference());
        Check.that(p.mode() == EchoPipeline.Mode.NO_ENGINE, "no engine mode");
        byte[] pcm = EchoReferenceTest.pcm(320, 7);
        byte[] copy = pcm.clone();
        p.process(pcm, pcm.length, 0);
        Check.that(Arrays.equals(copy, pcm), "untouched");
    }

    private static void offIsBypass() {
        EchoPipeline p = EchoPipeline.off();
        Check.that(p.mode() == EchoPipeline.Mode.OFF, "off");
        byte[] pcm = EchoReferenceTest.pcm(320, 7);
        byte[] copy = pcm.clone();
        p.process(pcm, pcm.length, 0);
        Check.that(Arrays.equals(copy, pcm), "untouched");
        p.close();
    }

    private static void unsupportedFormatBypassesAndCloses() {
        Fake c = new Fake();
        EchoPipeline p = EchoPipeline.create(RATE, 2, c, new EchoReference());
        Check.that(p.mode() == EchoPipeline.Mode.UNSUPPORTED_FORMAT, "stereo refused");
        Check.that(c.closed, "a refused canceller is closed");
        byte[] pcm = EchoReferenceTest.pcm(640, 7);
        byte[] copy = pcm.clone();
        p.process(pcm, pcm.length, 0);
        Check.that(Arrays.equals(copy, pcm), "untouched");
    }

    /**
     * The downlink frame that arrived before a mic read goes in first; one that arrives after
     * waits for the next read. Rendering late would put the echo ahead of its reference, which
     * no canceller can undo.
     */
    private static void renderBeforeTheCaptureItEchoesIn() {
        Fake c = new Fake();
        EchoReference ref = new EchoReference();
        ref.format(RATE, 1);
        EchoPipeline p = EchoPipeline.create(RATE, 1, c, ref);
        byte[] down = EchoReferenceTest.pcm(160, 1);
        ref.push(down, 0, down.length, 10 * MS);
        ref.push(down, 0, down.length, 30 * MS);

        p.process(new byte[FRAME_BYTES], FRAME_BYTES, 20 * MS);
        Check.that(c.calls.equals(Arrays.asList("R", "C", "C")), "render, then two 10 ms captures: " + c.calls);
        c.calls.clear();
        p.process(new byte[FRAME_BYTES], FRAME_BYTES, 40 * MS);
        Check.that(c.calls.equals(Arrays.asList("R", "C", "C")), "the later frame on the later read: " + c.calls);
        Check.eq(RATE, c.renderRate, "render rate");
        Check.eq(1, c.renderChannels, "render channels");
    }

    /** No downlink (the far end silent, or Siri): AEC3 still runs and passes the mic. */
    private static void noDownlinkStillCaptures() {
        Fake c = new Fake();
        EchoPipeline p = EchoPipeline.create(RATE, 1, c, new EchoReference());
        p.process(new byte[FRAME_BYTES], FRAME_BYTES, 0);
        Check.that(c.calls.equals(Arrays.asList("C", "C")), "captures only: " + c.calls);
    }

    private static void captureWritesBack() {
        Fake c = new Fake();
        EchoPipeline p = EchoPipeline.create(RATE, 1, c, new EchoReference());
        byte[] pcm = EchoReferenceTest.pcm(320, 100);
        p.process(pcm, pcm.length, 0);
        // The fake negates: sample 100 comes back as -100.
        Check.eq(-100, (short) ((pcm[0] & 0xff) | (pcm[1] << 8)), "first sample through the canceller");
        Check.eq(-419, (short) ((pcm[638] & 0xff) | (pcm[639] << 8)), "last sample through the canceller");
    }

    private static void raggedTailGoesOutAsRecorded() {
        Fake c = new Fake();
        EchoPipeline p = EchoPipeline.create(RATE, 1, c, new EchoReference());
        byte[] pcm = EchoReferenceTest.pcm(250, 100);
        p.process(pcm, pcm.length, 0);
        Check.eq(1, c.calls.size(), "one whole 10 ms chunk");
        Check.eq(260, (short) ((pcm[320] & 0xff) | (pcm[321] << 8)), "the tail is as recorded");
    }

    private static void statsComeFromTheCanceller() {
        Fake c = new Fake();
        EchoPipeline p = EchoPipeline.create(RATE, 1, c, new EchoReference());
        Check.that(p.stats().contains("erle 12.5 dB"), "erle: " + p.stats());
        Check.that(p.stats().contains("delay 140 ms"), "delay: " + p.stats());
        Check.that(EchoPipeline.off().stats().isEmpty(), "nothing when off");
    }

    private static void closeClosesTheCanceller() {
        Fake c = new Fake();
        EchoPipeline.create(RATE, 1, c, new EchoReference()).close();
        Check.that(c.closed, "closed");
    }

    private static void audioLoopAllocatesNothing() {
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Fake c = new Fake();
        c.record = false;
        EchoReference ref = new EchoReference();
        ref.format(48000, 2);
        EchoPipeline p = EchoPipeline.create(RATE, 1, c, ref);
        byte[] down = new byte[1920 * 2];
        byte[] pcm = new byte[FRAME_BYTES];
        long t = 0;
        for (int i = 0; i < 3000; i++) {
            ref.push(down, 0, down.length, t);
            t += 20 * MS;
            p.process(pcm, pcm.length, t);
        }
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1000; i++) {
            ref.push(down, 0, down.length, t);
            t += 20 * MS;
            p.process(pcm, pcm.length, t);
        }
        long allocated = mx.getCurrentThreadAllocatedBytes() - before;
        Check.eq(0, allocated, "bytes allocated by 1000 frames");
    }

    /** Logs the call order and negates the mic, so the write-back is visible. */
    private static final class Fake implements Canceller {
        final List<String> calls = new ArrayList<>();
        boolean record = true;
        boolean closed;
        int renderRate;
        int renderChannels;

        @Override
        public void render(short[] far, int rate, int channels) {
            renderRate = rate;
            renderChannels = channels;
            if (record) {
                calls.add("R");
            }
        }

        @Override
        public void capture(short[] near) {
            if (record) {
                calls.add("C");
            }
            for (int i = 0; i < near.length; i++) {
                near[i] = (short) -near[i];
            }
        }

        @Override
        public void stats(float[] out) {
            out[STAT_ERLE_DB] = 12.5f;
            out[STAT_ERL_DB] = 20f;
            out[STAT_DELAY_MS] = 140f;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
