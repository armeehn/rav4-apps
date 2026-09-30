package com.ripostelabs.projection.zlink;

/**
 * When the cabin mic is open for Siri and calls, and what reaches the daemon from it.
 *
 * <pre>
 *   0x402 MicStart ──▶ close any capture, open one in the asked format
 *                      recorder PCM ──▶ 0x404 MIC_DATA ──▶ daemon (audio channel)
 *   0x403 MicStop, session down, service end ──▶ close; frames still in flight are dropped
 * </pre>
 *
 * Each capture carries a generation number. The recorder thread can hand over one more buffer
 * after {@link #stop} returns; that buffer belongs to a closed generation and is not sent, so
 * no MIC_DATA ever follows a stop. Pure Java, so the lifecycle is unit-tested.
 */
public final class MicLink {

    /** CarPlay's voice format, used when MicStart leaves a field at zero. */
    public static final int DEFAULT_RATE = 16000;
    public static final int DEFAULT_CHANNELS = 1;
    /** The recorder always captures 16-bit PCM, so MIC_DATA says 16 whatever MicStart asked. */
    public static final int PCM_BITS = 16;

    public enum State { CLOSED, OPEN }

    /** One buffer of little-endian PCM from the recorder; only the first {@code len} bytes count. */
    public interface Pcm {
        void onPcm(byte[] pcm, int len);
    }

    /** The platform recorder. {@code open} is false when the mic cannot be had. */
    public interface Recorder {
        boolean open(int sampleRate, int channels, Pcm sink);

        void close();
    }

    /** Where an encoded MIC_DATA payload goes: the daemon's audio channel. */
    public interface Uplink {
        void send(byte[] micData);
    }

    private final Recorder recorder;
    private final Uplink uplink;
    private State state = State.CLOSED;
    private int generation;

    public MicLink(Recorder recorder, Uplink uplink) {
        this.recorder = recorder;
        this.uplink = uplink;
    }

    /** MicStart: a second one (Siri, then a call) replaces the first capture, never adds one. */
    public synchronized void start(Messages.MicStart asked) {
        close();

        int rate = asked.sampleRate > 0 ? asked.sampleRate : DEFAULT_RATE;
        int channels = asked.channels > 0 ? asked.channels : DEFAULT_CHANNELS;
        final int gen = generation;
        if (!recorder.open(rate, channels, (pcm, len) -> deliver(gen, rate, channels, pcm, len))) {
            return;
        }
        state = State.OPEN;
    }

    /** MicStop, the session going down, or the service ending: all close the same way. */
    public synchronized void stop() {
        close();
    }

    public synchronized State state() {
        return state;
    }

    private void close() {
        // Frames from the capture being closed are stale from here on.
        generation++;
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        recorder.close();
    }

    /** Under the lock, so a stop on another thread cannot slip between the check and the send. */
    private synchronized void deliver(int gen, int rate, int channels, byte[] pcm, int len) {
        if (gen != generation || state != State.OPEN) {
            return;
        }
        uplink.send(Messages.micData(rate, channels, PCM_BITS, pcm, len));
    }
}
