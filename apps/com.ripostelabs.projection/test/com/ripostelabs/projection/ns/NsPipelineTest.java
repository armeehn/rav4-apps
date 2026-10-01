package com.ripostelabs.projection.ns;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * The frame pipeline around the denoiser, with fake engines so it runs on the JVM: which
 * formats it takes, that bypass leaves the PCM byte for byte, that the strength limit holds,
 * and that the audio loop allocates nothing. The real engine's behaviour is jni/test.
 */
public final class NsPipelineTest {

    private static final int RATE = 16000;
    /** One MIC_DATA frame: 20 ms of 16 kHz mono s16. */
    private static final int FRAME_BYTES = RATE / 50 * 2;
    private static final int FRAMES = 50;

    public static void main(String[] args) {
        formatsItTakes();
        tenMillisecondChunks();
        missingLibraryBypasses();
        bypassLeavesPcmUntouched();
        refusedEngineIsClosed();
        offIsBypass();
        engineSeesTwoFramesPerMicFrame();
        identityEngineKeepsTheTone();
        fullStrengthFollowsTheEngine();
        limitCapsTheAttenuation();
        dryMixIsTimeAligned();
        raggedTailGoesOutAsRecorded();
        closeClosesTheEngine();
        audioLoopAllocatesNothing();
        System.out.println(Check.count + " assertions passed");
    }

    private static void formatsItTakes() {
        Check.that(NsPipeline.supports(16000, 1), "16 kHz mono");
        Check.that(NsPipeline.supports(8000, 1), "8 kHz mono");
        Check.that(NsPipeline.supports(24000, 1), "24 kHz mono");
        Check.that(NsPipeline.supports(48000, 1), "48 kHz mono");
        Check.that(!NsPipeline.supports(44100, 1), "44.1 kHz is not a divisor of 48 kHz");
        Check.that(!NsPipeline.supports(16000, 2), "stereo");
        Check.that(!NsPipeline.supports(0, 1), "no rate");
    }

    private static void tenMillisecondChunks() {
        Check.eq(160, NsPipeline.create(16000, 1, new Fake(Fake.Kind.IDENTITY), Strength.FULL).chunkSamples(), "16 kHz");
        Check.eq(80, NsPipeline.create(8000, 1, new Fake(Fake.Kind.IDENTITY), Strength.FULL).chunkSamples(), "8 kHz");
        Check.eq(480, NsPipeline.create(48000, 1, new Fake(Fake.Kind.IDENTITY), Strength.FULL).chunkSamples(), "48 kHz");
    }

    /** No .so on the JVM: the real engine does not open, and the mic still flows. */
    private static void missingLibraryBypasses() {
        Check.that(!RnNoise.available(), "library is absent off the device");
        Check.that(RnNoise.open() == null, "open without the library is null");
        NsPipeline p = NsPipeline.create(RATE, 1, RnNoise.open(), Strength.MEDIUM);
        Check.that(p.mode() == NsPipeline.Mode.NO_ENGINE, "no engine mode");
    }

    private static void bypassLeavesPcmUntouched() {
        byte[] pcm = speechLikePcm(FRAME_BYTES);
        byte[] before = pcm.clone();
        NsPipeline.create(RATE, 1, null, Strength.FULL).process(pcm, pcm.length);
        Check.that(Arrays.equals(before, pcm), "no-engine bypass is byte-exact");

        Fake engine = new Fake(Fake.Kind.SILENCE);
        NsPipeline stereo = NsPipeline.create(RATE, 2, engine, Strength.FULL);
        stereo.process(pcm, pcm.length);
        Check.that(stereo.mode() == NsPipeline.Mode.UNSUPPORTED_FORMAT, "stereo mode");
        Check.that(Arrays.equals(before, pcm), "stereo bypass is byte-exact");
        Check.eq(0, engine.frames, "engine never called in bypass");
    }

    private static void refusedEngineIsClosed() {
        Fake engine = new Fake(Fake.Kind.IDENTITY);
        NsPipeline.create(44100, 1, engine, Strength.FULL);
        Check.that(engine.closed, "an engine the pipeline will not use is closed at once");
    }

    private static void offIsBypass() {
        byte[] pcm = speechLikePcm(FRAME_BYTES);
        byte[] before = pcm.clone();
        NsPipeline off = NsPipeline.off();
        off.process(pcm, pcm.length);
        Check.that(off.mode() == NsPipeline.Mode.OFF, "off mode");
        Check.that(Arrays.equals(before, pcm), "off is byte-exact");
    }

    private static void engineSeesTwoFramesPerMicFrame() {
        Fake engine = new Fake(Fake.Kind.IDENTITY);
        NsPipeline p = NsPipeline.create(RATE, 1, engine, Strength.FULL);
        p.process(new byte[FRAME_BYTES], FRAME_BYTES);
        Check.eq(2, engine.frames, "a 20 ms frame is two engine frames");
        Check.eq(Engine.FRAME, engine.lastLength, "engine frames are 480 samples");
    }

    /** A pass-through engine: the tone comes back at its level, a little over 10 ms late. */
    private static void identityEngineKeepsTheTone() {
        float[] in = toFloat(tonePcm(FRAMES));
        float[] out = toFloat(run(new Fake(Fake.Kind.IDENTITY), Strength.FULL, tonePcm(FRAMES)));
        int skip = RATE / 10;
        double gain = Check.db(Check.rms(out, skip, out.length) / Check.rms(in, skip, in.length));
        Check.near(0, gain, 0.3, "identity gain (dB)");
        int lag = bestLag(in, out, RATE / 25);
        // Two 10 ms engine frames plus the resampler's 95 samples at 48 kHz, about 32 at 16 kHz.
        Check.near(320 + 32, lag, 2, "delay in 16 kHz samples");
    }

    private static void fullStrengthFollowsTheEngine() {
        float[] out = toFloat(run(new Fake(Fake.Kind.SILENCE), Strength.FULL, tonePcm(FRAMES)));
        Check.that(Check.rms(out, RATE / 10, out.length) < 1, "a silencing engine at FULL silences");
    }

    /**
     * An engine that keeps everything, mixed with the dry signal at any strength, is still the
     * signal: the dry path is delayed exactly as much as the engine. A misaligned dry frame
     * would comb-filter the voice (this is how a one-frame dry delay showed on the eval set).
     */
    private static void dryMixIsTimeAligned() {
        float[] full = toFloat(run(new Fake(Fake.Kind.IDENTITY), Strength.FULL, tonePcm(FRAMES)));
        float[] light = toFloat(run(new Fake(Fake.Kind.IDENTITY), Strength.LIGHT, tonePcm(FRAMES)));
        double diff = 0;
        for (int i = RATE / 10; i < full.length; i++) {
            diff = Math.max(diff, Math.abs(full[i] - light[i]));
        }
        Check.that(diff <= 2, "LIGHT and FULL differ by " + diff + " on a kept signal");
    }

    /** The engine removes everything; the limit leaves the original at -limit dB. */
    private static void limitCapsTheAttenuation() {
        for (Strength s : new Strength[] {Strength.LIGHT, Strength.MEDIUM}) {
            float[] in = toFloat(tonePcm(FRAMES));
            float[] out = toFloat(run(new Fake(Fake.Kind.SILENCE), s, tonePcm(FRAMES)));
            int skip = RATE / 10;
            double gain = Check.db(Check.rms(out, skip, out.length) / Check.rms(in, skip, in.length));
            Check.near(-s.limitDb, gain, 0.5, s + " attenuation (dB)");
        }
    }

    /** A short read that is not a whole 10 ms chunk: its tail is passed on as recorded. */
    private static void raggedTailGoesOutAsRecorded() {
        byte[] pcm = speechLikePcm(FRAME_BYTES + 6);
        byte[] before = pcm.clone();
        NsPipeline p = NsPipeline.create(RATE, 1, new Fake(Fake.Kind.SILENCE), Strength.FULL);
        p.process(pcm, pcm.length);
        Check.that(Arrays.equals(Arrays.copyOfRange(before, FRAME_BYTES, pcm.length),
                Arrays.copyOfRange(pcm, FRAME_BYTES, pcm.length)), "ragged tail untouched");
    }

    private static void closeClosesTheEngine() {
        Fake engine = new Fake(Fake.Kind.IDENTITY);
        NsPipeline.create(RATE, 1, engine, Strength.FULL).close();
        Check.that(engine.closed, "close reaches the engine");
    }

    /** Once warm, a thousand 20 ms frames through the pipeline allocate zero bytes. */
    private static void audioLoopAllocatesNothing() {
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        NsPipeline p = NsPipeline.create(RATE, 1, new Fake(Fake.Kind.IDENTITY), Strength.MEDIUM);
        byte[] pcm = speechLikePcm(FRAME_BYTES);
        for (int i = 0; i < 2000; i++) {
            p.process(pcm, pcm.length);
        }
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1000; i++) {
            p.process(pcm, pcm.length);
        }
        long allocated = mx.getCurrentThreadAllocatedBytes() - before;
        Check.eq(0, allocated, "bytes allocated by 1000 frames");
    }

    private static byte[] run(Engine engine, Strength strength, byte[] all) {
        NsPipeline p = NsPipeline.create(RATE, 1, engine, strength);
        byte[] frame = new byte[FRAME_BYTES];
        for (int off = 0; off + FRAME_BYTES <= all.length; off += FRAME_BYTES) {
            System.arraycopy(all, off, frame, 0, FRAME_BYTES);
            p.process(frame, FRAME_BYTES);
            System.arraycopy(frame, 0, all, off, FRAME_BYTES);
        }
        return all;
    }

    private static byte[] tonePcm(int frames) {
        return toPcm(ResamplerTest.sine(440, RATE, frames * FRAME_BYTES / 2));
    }

    private static byte[] speechLikePcm(int bytes) {
        byte[] pcm = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            pcm[i] = (byte) (i * 37 + 11);
        }
        return pcm;
    }

    private static byte[] toPcm(float[] x) {
        byte[] pcm = new byte[x.length * 2];
        for (int i = 0; i < x.length; i++) {
            int s = Math.round(x[i]);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    private static float[] toFloat(byte[] pcm) {
        float[] x = new float[pcm.length / 2];
        for (int i = 0; i < x.length; i++) {
            x[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        }
        return x;
    }

    private static int bestLag(float[] a, float[] b, int maxLag) {
        double best = -Double.MAX_VALUE;
        int lag = 0;
        int n = a.length - maxLag;
        for (int d = 0; d < maxLag; d++) {
            double c = 0;
            for (int i = RATE / 10; i < n; i++) {
                c += a[i] * b[i + d];
            }
            if (c > best) {
                best = c;
                lag = d;
            }
        }
        return lag;
    }

    /** IDENTITY delays by two frames like RNNoise and changes nothing; SILENCE removes all. */
    private static final class Fake implements Engine {
        enum Kind { IDENTITY, SILENCE }

        static final int DELAY = 2;

        private final Kind kind;
        private final float[][] queue = new float[DELAY][FRAME];
        int frames;
        int lastLength;
        boolean closed;

        Fake(Kind kind) {
            this.kind = kind;
        }

        @Override
        public void frame(float[] pcm) {
            frames++;
            lastLength = pcm.length;
            if (kind == Kind.SILENCE) {
                Arrays.fill(pcm, 0f);
                return;
            }
            // Out goes the frame from DELAY calls ago, in goes this one.
            float[] oldest = queue[0];
            for (int k = 0; k < DELAY - 1; k++) {
                queue[k] = queue[k + 1];
            }
            for (int i = 0; i < FRAME; i++) {
                float t = pcm[i];
                pcm[i] = oldest[i];
                oldest[i] = t;
            }
            queue[DELAY - 1] = oldest;
        }

        @Override
        public int delayFrames() {
            return DELAY;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
