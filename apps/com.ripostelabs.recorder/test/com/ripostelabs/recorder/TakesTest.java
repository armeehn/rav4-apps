package com.ripostelabs.recorder;

import com.ripostelabs.projection.ns.Engine;
import com.ripostelabs.projection.ns.NsPipeline;
import com.ripostelabs.projection.ns.Strength;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The noise reduction test's file half, on the JVM: how a take's two files are named and
 * paired in the list, what a WAV header says, and that one capture feeds both files from the
 * same samples, with the pipeline either cleaning the second or passing it through.
 */
public final class TakesTest {

    private static final int RATE = 16000;
    /** 20 ms of 16 kHz mono s16, the size the capture thread reads. */
    private static final int READ_BYTES = RATE / 50 * 2;

    private static int count;

    public static void main(String[] args) throws IOException {
        namesCarryTheHalf();
        pairBaseReadsEitherHalf();
        groupPairsTwinsAndKeepsOrder();
        loneHalfStaysASingleTake();
        renameMovesBothHalves();
        wavHeaderDescribesThePcm();
        wavSecondsFromHeader();
        bypassWritesTheSameSamplesTwice();
        engineCleansOnlyTheSecondFile();
        System.out.println(count + " assertions passed");
    }

    private static void namesCarryTheHalf() {
        eq("Highway raw.wav", Takes.rawName("Highway"), "raw name");
        eq("Highway cleaned.wav", Takes.cleanedName("Highway"), "cleaned name");
    }

    private static void pairBaseReadsEitherHalf() {
        eq("NR_1", Takes.pairBase("NR_1 raw.wav"), "raw half");
        eq("NR_1", Takes.pairBase("NR_1 cleaned.wav"), "cleaned half");
        eq(null, Takes.pairBase("REC_1.m4a"), "a memo is not a half");
        eq(null, Takes.pairBase("raw.wav"), "no base, no pair");
    }

    private static void groupPairsTwinsAndKeepsOrder() {
        // Newest first, as the list sorts them; the cleaned half is written last, so it is first.
        List<Takes.Take> takes = Takes.group(Arrays.asList(
                new File("b cleaned.wav"), new File("b raw.wav"),
                new File("REC_2.m4a"),
                new File("a raw.wav"), new File("a cleaned.wav")));

        that(takes.size() == 3, "three takes, got " + takes.size());
        eq("b", takes.get(0).name, "pair b first");
        eq("b raw.wav", takes.get(0).first.getName(), "raw plays first");
        eq("b cleaned.wav", takes.get(0).second.getName(), "cleaned plays second");
        eq("REC_2", takes.get(1).name, "memo keeps its place");
        that(takes.get(1).second == null, "a memo has no twin");
        eq("a raw.wav", takes.get(2).first.getName(), "pair a raw first");
    }

    private static void loneHalfStaysASingleTake() {
        List<Takes.Take> takes = Takes.group(Arrays.asList(new File("c cleaned.wav")));
        that(takes.size() == 1, "one take");
        eq("c cleaned", takes.get(0).name, "a lone half shows its whole name");
        that(takes.get(0).second == null, "no twin to play");
    }

    private static void renameMovesBothHalves() throws IOException {
        File dir = Files.createTempDirectory("takes").toFile();
        File raw = touch(new File(dir, Takes.rawName("NR_1")));
        File cleaned = touch(new File(dir, Takes.cleanedName("NR_1")));
        touch(new File(dir, Takes.rawName("Cabin")));

        String base = Takes.renamePair(raw, cleaned, "Cabin");

        eq("Cabin (2)", base, "a taken name gets a number");
        that(new File(dir, "Cabin (2) raw.wav").exists(), "raw moved");
        that(new File(dir, "Cabin (2) cleaned.wav").exists(), "cleaned moved");
        that(!raw.exists() && !cleaned.exists(), "old names gone");
        eq("a_b", Takes.renamePair(new File(dir, "Cabin (2) raw.wav"),
                new File(dir, "Cabin (2) cleaned.wav"), "a/b"), "path characters are replaced");
    }

    private static void wavHeaderDescribesThePcm() {
        byte[] h = Wav.header(48000, 1, 96000);
        eq("RIFF", new String(h, 0, 4), "RIFF");
        eq(36 + 96000, le32(h, 4), "RIFF size");
        eq("WAVE", new String(h, 8, 4), "WAVE");
        eq(1, le16(h, 20), "PCM format");
        eq(1, le16(h, 22), "mono");
        eq(48000, le32(h, 24), "rate");
        eq(96000, le32(h, 28), "byte rate");
        eq(16, le16(h, 34), "16-bit");
        eq("data", new String(h, 36, 4), "data tag");
        eq(96000, le32(h, 40), "data size");
    }

    private static void wavSecondsFromHeader() throws IOException {
        File f = File.createTempFile("sec", ".wav");
        Wav.Sink sink = Wav.Sink.open(f, RATE);
        sink.write(new byte[RATE * 2 * 3], RATE * 2 * 3);
        sink.close();
        eq(44 + RATE * 2 * 3, f.length(), "file size");
        that(Math.abs(Wav.seconds(f) - 3.0) < 1e-9, "three seconds, got " + Wav.seconds(f));
        that(Wav.seconds(new File("/nonexistent.wav")) == 0, "missing file is zero");
    }

    private static void bypassWritesTheSameSamplesTwice() throws IOException {
        File raw = File.createTempFile("raw", ".wav");
        File cleaned = File.createTempFile("cleaned", ".wav");
        NsPipeline ns = NsPipeline.create(RATE, 1, null, Strength.MEDIUM);
        that(ns.mode() == NsPipeline.Mode.NO_ENGINE, "no library, bypass");

        TwinTake take = TwinTake.open(raw, cleaned, RATE, ns);
        byte[] buf = tone();
        take.accept(buf, buf.length);
        take.close();

        that(Arrays.equals(Files.readAllBytes(raw.toPath()), Files.readAllBytes(cleaned.toPath())),
                "bypass: both files identical");
        that(Arrays.equals(buf, tone()), "the caller's buffer is left as read");
    }

    private static void engineCleansOnlyTheSecondFile() throws IOException {
        File raw = File.createTempFile("raw", ".wav");
        File cleaned = File.createTempFile("cleaned", ".wav");
        NsPipeline ns = NsPipeline.create(RATE, 1, new Silence(), Strength.FULL);

        TwinTake take = TwinTake.open(raw, cleaned, RATE, ns);
        for (int i = 0; i < 10; i++) {
            byte[] buf = tone();
            take.accept(buf, buf.length);
        }
        take.close();

        byte[] r = Files.readAllBytes(raw.toPath());
        byte[] c = Files.readAllBytes(cleaned.toPath());
        eq(r.length, c.length, "same length");
        byte[] expected = tone();
        that(Arrays.equals(Arrays.copyOfRange(r, 44, 44 + expected.length), expected),
                "raw holds the samples as read");
        that(peak(c, 44) == 0, "full strength on a silencing engine leaves nothing");
    }

    // ---- helpers ----

    /** One read of a loud 1 kHz tone. */
    private static byte[] tone() {
        byte[] b = new byte[READ_BYTES];
        for (int i = 0; i < READ_BYTES / 2; i++) {
            int s = (int) (12000 * Math.sin(2 * Math.PI * 1000 * i / RATE));
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    private static int peak(byte[] pcm, int from) {
        int p = 0;
        for (int i = from; i + 1 < pcm.length; i += 2) {
            p = Math.max(p, Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8))));
        }
        return p;
    }

    /** An engine that removes everything. */
    private static final class Silence implements Engine {
        @Override public void frame(float[] pcm) {
            Arrays.fill(pcm, 0f);
        }

        @Override public int delayFrames() {
            return 2;
        }

        @Override public void close() {
        }
    }

    private static File touch(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            r.write(1);
        }
        return f;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o + 1] & 0xffL) << 8 | (b[o + 2] & 0xffL) << 16 | (b[o + 3] & 0xffL) << 24;
    }

    private static void that(boolean ok, String what) {
        count++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        that(expected == null ? actual == null : expected.equals(actual),
                what + ": expected " + expected + ", got " + actual);
    }

    private static void eq(long expected, long actual, String what) {
        that(expected == actual, what + ": expected " + expected + ", got " + actual);
    }
}
