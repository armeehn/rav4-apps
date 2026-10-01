package com.ripostelabs.recorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 16-bit mono PCM WAV, the format the noise tests keep: no encoder between the microphone and
 * the file, so what RNNoise saw is what the file holds.
 *
 * <pre>
 *   0 RIFF  4 size  8 WAVE  12 "fmt " 16 16  20 1(PCM) 22 ch  24 rate  28 byte rate
 *   32 block  34 bits  36 data  40 data size  44 samples...
 * </pre>
 */
final class Wav {

    static final int HEADER_BYTES = 44;
    static final int BYTES_PER_SAMPLE = 2;

    private static final int BITS = 16;
    private static final int FMT_CHUNK_BYTES = 16;
    private static final int FORMAT_PCM = 1;
    /** RIFF size counts everything after its own 8 bytes: the header's other 36 and the data. */
    private static final int RIFF_OVERHEAD = HEADER_BYTES - 8;
    private static final int DATA_SIZE_OFFSET = 40;
    private static final int RATE_OFFSET = 24;

    private Wav() {
    }

    static byte[] header(int rate, int channels, long dataBytes) {
        byte[] h = new byte[HEADER_BYTES];
        int block = channels * BYTES_PER_SAMPLE;
        ascii(h, 0, "RIFF");
        le32(h, 4, RIFF_OVERHEAD + dataBytes);
        ascii(h, 8, "WAVE");
        ascii(h, 12, "fmt ");
        le32(h, 16, FMT_CHUNK_BYTES);
        le16(h, 20, FORMAT_PCM);
        le16(h, 22, channels);
        le32(h, RATE_OFFSET, rate);
        le32(h, 28, (long) rate * block);
        le16(h, 32, block);
        le16(h, 34, BITS);
        ascii(h, 36, "data");
        le32(h, DATA_SIZE_OFFSET, dataBytes);
        return h;
    }

    /** Length in seconds from a mono WAV's header; 0 for anything unreadable. */
    static double seconds(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[HEADER_BYTES];
            r.readFully(h);
            long rate = u32(h, RATE_OFFSET);
            long data = u32(h, DATA_SIZE_OFFSET);
            return rate == 0 ? 0 : (double) data / (rate * BYTES_PER_SAMPLE);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * A WAV being written: the header goes first with a zero length and is patched on close,
     * so a capture that dies midway leaves a file that still opens, just shorter.
     */
    static final class Sink {
        private final RandomAccessFile out;
        private final int rate;
        private long dataBytes;

        private Sink(RandomAccessFile out, int rate) {
            this.out = out;
            this.rate = rate;
        }

        static Sink open(File f, int rate) throws IOException {
            RandomAccessFile out = new RandomAccessFile(f, "rw");
            out.setLength(0);
            out.write(header(rate, 1, 0));
            return new Sink(out, rate);
        }

        void write(byte[] pcm, int len) throws IOException {
            out.write(pcm, 0, len);
            dataBytes += len;
        }

        void close() throws IOException {
            out.seek(0);
            out.write(header(rate, 1, dataBytes));
            out.close();
        }
    }

    private static void ascii(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[o + i] = (byte) s.charAt(i);
        }
    }

    private static void le16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] b, int o, long v) {
        for (int i = 0; i < 4; i++) {
            b[o + i] = (byte) (v >> (8 * i));
        }
    }

    private static long u32(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            v |= (b[o + i] & 0xffL) << (8 * i);
        }
        return v;
    }
}
