package com.ripostelabs.projection.aa;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Android Auto transport framing: what goes on the USB bulk pipe.
 *
 * <pre>
 *   +---------+---------+-----------------+---------------------------+-----------------+
 *   | channel | flags   | length (u16 BE) | total length (u32 BE)     | payload         |
 *   | 1 byte  | 1 byte  | of this payload | only when flags == FIRST  | length bytes    |
 *   +---------+---------+-----------------+---------------------------+-----------------+
 *
 *   flags:  bit0 FIRST   bit1 LAST   (both = BULK, a whole message in one frame)
 *           bit2 CONTROL             (control-type message on a non-control channel)
 *           bit3 ENCRYPTED           (payload is TLS application data)
 * </pre>
 *
 * <p>A message longer than {@link #MAX_PAYLOAD} is split FIRST / MIDDLE.. / LAST. Encryption is
 * per frame: each frame's payload is one TLS record of its plaintext chunk, the length field is
 * the ciphertext length and the total length is the plaintext message length. The message
 * payload starts with a u16 BE message id (see {@link Ids}).
 *
 * <p>Facts from aasdk {@code Messenger/FrameHeader.cpp}, {@code FrameSize.cpp},
 * {@code MessageOutStream.cpp} and {@code MessageInStream.cpp}.
 */
public final class Frame {

    public static final int FLAG_FIRST = 0x01;
    public static final int FLAG_LAST = 0x02;
    public static final int FLAG_BULK = FLAG_FIRST | FLAG_LAST;
    public static final int FLAG_CONTROL = 0x04;
    public static final int FLAG_ENCRYPTED = 0x08;

    /** Largest plaintext chunk aasdk puts in one frame. */
    public static final int MAX_PAYLOAD = 0x4000;

    private static final int SHORT_HEADER = 4;
    private static final int LONG_HEADER = 8;
    private static final int ID_LENGTH = 2;

    public final int channel;
    public final int flags;
    /** Plaintext message length, present only on a FIRST frame; 0 otherwise. */
    public final int totalLength;
    public final byte[] payload;

    public Frame(int channel, int flags, int totalLength, byte[] payload) {
        this.channel = channel;
        this.flags = flags;
        this.totalLength = totalLength;
        this.payload = payload;
    }

    public int frameType() {
        return flags & FLAG_BULK;
    }

    public boolean encrypted() {
        return (flags & FLAG_ENCRYPTED) != 0;
    }

    public boolean control() {
        return (flags & FLAG_CONTROL) != 0;
    }

    /** True when the header carries the 4-byte total: a FIRST frame that is not also LAST. */
    public static boolean hasTotal(int flags) {
        return (flags & FLAG_BULK) == FLAG_FIRST;
    }

    public static int headerLength(int flags) {
        return hasTotal(flags) ? LONG_HEADER : SHORT_HEADER;
    }

    /** Header bytes only; the caller appends the (possibly encrypted) payload. */
    public static byte[] header(int channel, int flags, int payloadLength, int totalLength) {
        byte[] h = new byte[headerLength(flags)];
        h[0] = (byte) channel;
        h[1] = (byte) flags;
        h[2] = (byte) (payloadLength >>> 8);
        h[3] = (byte) payloadLength;

        if (hasTotal(flags)) {
            h[4] = (byte) (totalLength >>> 24);
            h[5] = (byte) (totalLength >>> 16);
            h[6] = (byte) (totalLength >>> 8);
            h[7] = (byte) totalLength;
        }
        return h;
    }

    public byte[] encode() {
        byte[] h = header(channel, flags, payload.length, totalLength);
        byte[] out = new byte[h.length + payload.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(payload, 0, out, h.length, payload.length);
        return out;
    }

    /** Decodes one frame at {@code off}; null when the buffer does not yet hold all of it. */
    public static Frame decode(byte[] buf, int off, int len) {
        if (len < SHORT_HEADER) {
            return null;
        }
        int flags = buf[off + 1] & 0xFF;
        int header = headerLength(flags);
        if (len < header) {
            return null;
        }

        int payloadLength = ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
        if (len < header + payloadLength) {
            return null;
        }

        int total = 0;
        if (hasTotal(flags)) {
            total = ((buf[off + 4] & 0xFF) << 24) | ((buf[off + 5] & 0xFF) << 16)
                    | ((buf[off + 6] & 0xFF) << 8) | (buf[off + 7] & 0xFF);
        }

        byte[] payload = new byte[payloadLength];
        System.arraycopy(buf, off + header, payload, 0, payloadLength);
        return new Frame(buf[off] & 0xFF, flags, total, payload);
    }

    /** Wire size of the frame at {@code off}, or -1 when the header is incomplete. */
    public static int encodedLength(byte[] buf, int off, int len) {
        if (len < SHORT_HEADER) {
            return -1;
        }
        int flags = buf[off + 1] & 0xFF;
        int payloadLength = ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
        return headerLength(flags) + payloadLength;
    }

    // ---- message id prefix -----------------------------------------------------------------

    public static byte[] withId(int messageId, byte[] body) {
        byte[] out = new byte[ID_LENGTH + body.length];
        out[0] = (byte) (messageId >>> 8);
        out[1] = (byte) messageId;
        System.arraycopy(body, 0, out, ID_LENGTH, body.length);
        return out;
    }

    public static int messageId(byte[] message) {
        return ((message[0] & 0xFF) << 8) | (message[1] & 0xFF);
    }

    public static byte[] body(byte[] message) {
        byte[] out = new byte[message.length - ID_LENGTH];
        System.arraycopy(message, ID_LENGTH, out, 0, out.length);
        return out;
    }

    // ---- fragmentation ---------------------------------------------------------------------

    /** A plaintext slice of a message with the frame-type bits it travels under. */
    public static final class Chunk {
        public final int frameType;
        public final byte[] data;

        Chunk(int frameType, byte[] data) {
            this.frameType = frameType;
            this.data = data;
        }
    }

    /**
     * Cuts a message into chunks the way aasdk does: one BULK chunk below {@link #MAX_PAYLOAD},
     * otherwise FIRST, MIDDLE.., LAST of at most {@link #MAX_PAYLOAD} each.
     */
    public static List<Chunk> split(byte[] message) {
        List<Chunk> chunks = new ArrayList<>();
        if (message.length < MAX_PAYLOAD) {
            chunks.add(new Chunk(FLAG_BULK, message));
            return chunks;
        }

        int off = 0;
        while (off < message.length) {
            int size = Math.min(MAX_PAYLOAD, message.length - off);
            int type = off == 0 ? FLAG_FIRST : (off + size < message.length ? 0 : FLAG_LAST);
            byte[] data = new byte[size];
            System.arraycopy(message, off, data, 0, size);
            chunks.add(new Chunk(type, data));
            off += size;
        }

        // Exactly MAX_PAYLOAD bytes: aasdk still splits, sending FIRST and then an empty LAST.
        if (chunks.size() == 1) {
            chunks.add(new Chunk(FLAG_LAST, new byte[0]));
        }
        return chunks;
    }

    // ---- reassembly ------------------------------------------------------------------------

    /**
     * Puts split messages back together, one stream per channel. Feed each frame's plaintext
     * (decrypt first when the frame is ENCRYPTED); a complete message comes back, else null.
     */
    public static final class Assembler {
        private final ByteArrayOutputStream[] pending = new ByteArrayOutputStream[256];

        public byte[] feed(int channel, int flags, byte[] plaintext) {
            int type = flags & FLAG_BULK;
            if (type == FLAG_BULK) {
                pending[channel] = null;
                return plaintext;
            }

            if (type == FLAG_FIRST || pending[channel] == null) {
                pending[channel] = new ByteArrayOutputStream();
            }
            pending[channel].write(plaintext, 0, plaintext.length);

            if (type != FLAG_LAST) {
                return null;
            }
            byte[] whole = pending[channel].toByteArray();
            pending[channel] = null;
            return whole;
        }
    }

    // ---- stream reader ---------------------------------------------------------------------

    /** Turns arbitrary bulk-read slices into whole frames. */
    public static final class Reader {
        private byte[] buf = new byte[MAX_PAYLOAD * 2];
        private int used;

        public void push(byte[] data, int off, int len) {
            if (used + len > buf.length) {
                byte[] bigger = new byte[Math.max(buf.length * 2, used + len)];
                System.arraycopy(buf, 0, bigger, 0, used);
                buf = bigger;
            }
            System.arraycopy(data, off, buf, used, len);
            used += len;
        }

        /** The next complete frame, or null until more bytes arrive. */
        public Frame next() {
            int size = encodedLength(buf, 0, used);
            if (size < 0 || size > used) {
                return null;
            }
            Frame f = decode(buf, 0, used);
            System.arraycopy(buf, size, buf, 0, used - size);
            used -= size;
            return f;
        }
    }
}
