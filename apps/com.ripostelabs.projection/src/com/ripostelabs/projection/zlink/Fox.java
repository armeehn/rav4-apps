package com.ripostelabs.projection.zlink;

import java.nio.ByteBuffer;

/**
 * The local TCP framing between the OEM projection daemon ({@code zlink5}, an init service) and
 * the app that gives it a screen. Every message on every channel is
 *
 * <pre>
 *   ff ff ff 10 | u32 length (big-endian) | u32 message id (big-endian) | payload
 * </pre>
 *
 * where the payload is a protobuf-lite message whose field 1 repeats the id, or raw media on the
 * audio and video channels. Established by black-box capture on the bench (2026-09-19): the daemon
 * connected to a listener and sent {@code 0x101 SessionState} followed by {@code 0xa02} carrying
 * its build hash; ids were then swept with empty payloads and read back from its own log lines.
 *
 * <p>The daemon is the client. It dials four servers on localhost, all of which this app runs:
 *
 * <pre>
 *   1777  control   session state, init, MFi, touch, keys, night, focus  (protobuf)
 *   1666  audio     PCM from the phone, format announced first            (raw)
 *   1888  video     H.264 from the phone, size announced first            (raw)
 *   1999  bluetooth RFCOMM relay for the wireless bootstrap (unused wired)
 * </pre>
 */
public final class Fox {

    private Fox() {
    }

    public static final int PORT_CONTROL = 1777;
    public static final int PORT_AUDIO = 1666;
    public static final int PORT_VIDEO = 1888;
    public static final int PORT_BLUETOOTH = 1999;

    public static final int HEADER_LEN = 12;
    /** Bigger than any capture; a header claiming more is a lost sync, not a frame. */
    public static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    private static final byte[] MAGIC = {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x10};

    /** One complete frame off the wire. */
    public static final class Frame {
        public final int id;
        public final byte[] payload;

        Frame(int id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }
    }

    public static byte[] encode(int id, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_LEN + payload.length);
        b.put(MAGIC).putInt(payload.length).putInt(id).put(payload);
        return b.array();
    }

    /**
     * Incremental parser: feed bytes as they arrive, take frames as they complete. A bad magic
     * skips one byte and resyncs, which is what the daemon's own {@code *_message_head_recv}
     * does on its side.
     */
    public static final class Parser {
        private byte[] buf = new byte[64 * 1024];
        private int len;

        public void feed(byte[] data, int off, int n) {
            if (len + n > buf.length) {
                byte[] grown = new byte[Math.max(buf.length * 2, len + n)];
                System.arraycopy(buf, 0, grown, 0, len);
                buf = grown;
            }
            System.arraycopy(data, off, buf, len, n);
            len += n;
        }

        /** The next complete frame, or null when more bytes are needed. */
        public Frame next() {
            while (len >= HEADER_LEN) {
                if (!magicAt(0)) {
                    drop(1);
                    continue;
                }
                ByteBuffer b = ByteBuffer.wrap(buf, 4, 8);
                int plen = b.getInt();
                int id = b.getInt();
                if (plen < 0 || plen > MAX_PAYLOAD) {
                    drop(1);
                    continue;
                }
                if (len < HEADER_LEN + plen) {
                    return null;
                }
                byte[] payload = new byte[plen];
                System.arraycopy(buf, HEADER_LEN, payload, 0, plen);
                drop(HEADER_LEN + plen);
                return new Frame(id, payload);
            }
            return null;
        }

        private boolean magicAt(int at) {
            for (int i = 0; i < MAGIC.length; i++) {
                if (buf[at + i] != MAGIC[i]) {
                    return false;
                }
            }
            return true;
        }

        private void drop(int n) {
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }
    }
}
