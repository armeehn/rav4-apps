package com.ripostelabs.projection.zlink;

import java.util.ArrayList;
import java.util.List;

/**
 * The cabin mic for Siri and calls. The daemon asks with {@code MicStart} (0x402) and stops with
 * {@code MicStop} (0x403); the app answers with {@code MIC_DATA} (0x404) frames. The rule under
 * test: the mic is open only between a start and the next stop, session drop or service end,
 * and no frame reaches the daemon outside that window.
 */
public final class MicLinkTest {

    public static void main(String[] args) {
        micStartDecodes();
        micDataEncodes();
        micDataTakesOnlyTheFilledPart();
        startOpensInTheAskedFormat();
        zeroFieldsFallBackToVoiceFormat();
        framesBecomeMicData();
        stopCloses();
        stopWhenClosedIsQuiet();
        secondStartReopens();
        sessionDropWhileOpenCloses();
        framesAfterStopAreDropped();
        oldCaptureFramesAreDroppedAfterRestart();
        failedOpenStaysClosed();
        System.out.println(Check.count + " assertions passed");
    }

    /** MicStart {id 0x402, 16000 Hz, 1 channel, 16 bit, bt aec 0}. */
    private static void micStartDecodes() {
        Messages.MicStart m = Messages.micStart(Check.hex("08 82 08 10 80 7d 18 01 20 10 28 00"));
        Check.eq(16000, m.sampleRate, "rate");
        Check.eq(1, m.channels, "channels");
        Check.eq(16, m.bits, "bits");
    }

    /** MIC_DATA {id 0x404, 16000, 1, 16, aec off, delay 0, data}; field 6 and 9 stay unset. */
    private static void micDataEncodes() {
        byte[] pcm = Check.hex("01 02 03 04");
        Check.bytes(Check.hex("08 84 08 10 80 7d 18 01 20 10 28 00 38 00 42 04 01 02 03 04"),
                Messages.micData(16000, 1, 16, pcm, pcm.length), "mic data frame");
    }

    /** The recorder reuses one buffer; only the part it filled goes on the wire. */
    private static void micDataTakesOnlyTheFilledPart() {
        byte[] pcm = Check.hex("0a 0b 0c 0d");
        byte[] frame = Messages.micData(8000, 1, 16, pcm, 2);
        Check.bytes(Check.hex("42 02 0a 0b"), tail(frame, 4), "two bytes of data");
    }

    private static void startOpensInTheAskedFormat() {
        Rig r = new Rig();
        r.link.start(ask(24000, 2));
        Check.that(r.link.state() == MicLink.State.OPEN, "open after start");
        Check.eq(1, r.recorder.opens, "one open");
        Check.eq(24000, r.recorder.rate, "asked rate");
        Check.eq(2, r.recorder.channels, "asked channels");
    }

    /** A MicStart with fields left at zero gets CarPlay's voice format, not a recorder error. */
    private static void zeroFieldsFallBackToVoiceFormat() {
        Rig r = new Rig();
        r.link.start(new Messages.MicStart());
        Check.eq(MicLink.DEFAULT_RATE, r.recorder.rate, "default rate");
        Check.eq(MicLink.DEFAULT_CHANNELS, r.recorder.channels, "default channels");
    }

    private static void framesBecomeMicData() {
        Rig r = new Rig();
        r.link.start(ask(16000, 1));
        byte[] pcm = Check.hex("01 02 03 04");
        r.recorder.sink.onPcm(pcm, pcm.length);
        Check.eq(1, r.sent.size(), "one frame sent");
        Check.bytes(Messages.micData(16000, 1, MicLink.PCM_BITS, pcm, pcm.length), r.sent.get(0), "as MIC_DATA");
    }

    private static void stopCloses() {
        Rig r = new Rig();
        r.link.start(ask(16000, 1));
        r.link.stop();
        Check.that(r.link.state() == MicLink.State.CLOSED, "closed after stop");
        Check.eq(1, r.recorder.closes, "recorder closed once");
    }

    /** The daemon sends MicStop at session end even when it never started the mic. */
    private static void stopWhenClosedIsQuiet() {
        Rig r = new Rig();
        r.link.stop();
        r.link.stop();
        Check.eq(0, r.recorder.closes, "nothing to close");
    }

    /** A second MicStart (Siri, then a call) never leaves two captures running. */
    private static void secondStartReopens() {
        Rig r = new Rig();
        r.link.start(ask(16000, 1));
        r.link.start(ask(8000, 1));
        Check.eq(1, r.recorder.closes, "first capture closed");
        Check.eq(2, r.recorder.opens, "second capture opened");
        Check.eq(0, r.recorder.maxOpen - 1, "never two open at once");
        Check.eq(8000, r.recorder.rate, "new rate");
    }

    /**
     * The phone walks away mid-call: no MicStop comes, only the session going down. Without
     * this the cabin mic would stay open until the next CarPlay session.
     */
    private static void sessionDropWhileOpenCloses() {
        Rig r = new Rig();
        r.link.start(ask(16000, 1));
        r.link.stop();
        Check.that(r.link.state() == MicLink.State.CLOSED, "closed on session drop");
        Check.eq(0, r.recorder.open, "recorder released");
        byte[] pcm = Check.hex("01 02");
        r.recorder.sink.onPcm(pcm, pcm.length);
        Check.eq(0, r.sent.size(), "nothing sent after the drop");
    }

    /** The recorder thread may hand over one last buffer after stop returns; it is not sent. */
    private static void framesAfterStopAreDropped() {
        Rig r = new Rig();
        r.link.start(ask(16000, 1));
        MicLink.Pcm late = r.recorder.sink;
        r.link.stop();
        late.onPcm(Check.hex("01 02"), 2);
        Check.eq(0, r.sent.size(), "late frame dropped");
    }

    private static void oldCaptureFramesAreDroppedAfterRestart() {
        Rig r = new Rig();
        r.link.start(ask(16000, 1));
        MicLink.Pcm old = r.recorder.sink;
        r.link.start(ask(8000, 1));
        old.onPcm(Check.hex("01 02"), 2);
        Check.eq(0, r.sent.size(), "old capture's frame dropped");
        r.recorder.sink.onPcm(Check.hex("03 04"), 2);
        Check.eq(1, r.sent.size(), "new capture's frame sent");
    }

    /** No RECORD_AUDIO or a format the HAL refuses: closed, and a later stop closes nothing. */
    private static void failedOpenStaysClosed() {
        Rig r = new Rig();
        r.recorder.refuse = true;
        r.link.start(ask(16000, 1));
        Check.that(r.link.state() == MicLink.State.CLOSED, "closed when open fails");
        r.link.stop();
        Check.eq(0, r.recorder.closes, "no close for a capture that never opened");
    }

    // ---- rig ---------------------------------------------------------------------------------

    private static Messages.MicStart ask(int rate, int channels) {
        Messages.MicStart m = new Messages.MicStart();
        m.sampleRate = rate;
        m.channels = channels;
        m.bits = MicLink.PCM_BITS;
        return m;
    }

    private static byte[] tail(byte[] b, int n) {
        byte[] out = new byte[n];
        System.arraycopy(b, b.length - n, out, 0, n);
        return out;
    }

    /** A recorder that counts what it was asked to do and hands its sink to the test. */
    private static final class FakeRecorder implements MicLink.Recorder {
        int opens;
        int closes;
        int open;
        int maxOpen;
        int rate;
        int channels;
        boolean refuse;
        MicLink.Pcm sink;

        @Override
        public boolean open(int sampleRate, int channels, MicLink.Pcm sink) {
            if (refuse) {
                return false;
            }
            opens++;
            open++;
            maxOpen = Math.max(maxOpen, open);
            this.rate = sampleRate;
            this.channels = channels;
            this.sink = sink;
            return true;
        }

        @Override
        public void close() {
            closes++;
            open--;
        }
    }

    private static final class Rig {
        final FakeRecorder recorder = new FakeRecorder();
        final List<byte[]> sent = new ArrayList<>();
        final MicLink link = new MicLink(recorder, sent::add);
    }
}
