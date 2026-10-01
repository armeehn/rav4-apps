package com.ripostelabs.recorder;

import com.ripostelabs.projection.ns.NsPipeline;

import java.io.File;
import java.io.IOException;

/**
 * One noise reduction take: every read of the mic goes to two files from the same samples.
 *
 * <pre>
 *   mic read ─┬──────────────────────────▶ "<name> raw.wav"
 *             └─▶ copy ─▶ NsPipeline ────▶ "<name> cleaned.wav"
 * </pre>
 *
 * The pipeline is the CarPlay mic's own (shared from the Projection app), so the cleaned file
 * is what the far end of a call would hear. When it bypasses, both files are identical.
 */
final class TwinTake {

    private final Wav.Sink raw;
    private final Wav.Sink cleaned;
    private final NsPipeline ns;
    private byte[] scratch = new byte[0];

    private TwinTake(Wav.Sink raw, Wav.Sink cleaned, NsPipeline ns) {
        this.raw = raw;
        this.cleaned = cleaned;
        this.ns = ns;
    }

    static TwinTake open(File rawFile, File cleanedFile, int rate, NsPipeline ns) throws IOException {
        return new TwinTake(Wav.Sink.open(rawFile, rate), Wav.Sink.open(cleanedFile, rate), ns);
    }

    NsPipeline.Mode mode() {
        return ns.mode();
    }

    /** The first {@code len} bytes of a read; the caller's buffer is left as it was. */
    void accept(byte[] pcm, int len) throws IOException {
        raw.write(pcm, len);

        // The pipeline works in place, so it gets a copy. Grown once, then reused.
        if (scratch.length < len) {
            scratch = new byte[len];
        }
        System.arraycopy(pcm, 0, scratch, 0, len);
        ns.process(scratch, len);
        cleaned.write(scratch, len);
    }

    void close() throws IOException {
        try {
            raw.close();
            cleaned.close();
        } finally {
            ns.close();
        }
    }
}
