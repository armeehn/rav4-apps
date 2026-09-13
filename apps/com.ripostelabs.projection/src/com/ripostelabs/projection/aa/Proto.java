package com.ripostelabs.projection.aa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The slice of protocol buffers Android Auto messages need: varint, fixed32/64,
 * length-delimited (bytes, strings, nested messages). No descriptors, no reflection, no jar.
 *
 * <p>Wire format (Google's encoding spec): every field is a varint key {@code (field << 3) | wire}
 * followed by the value. Wire types: 0 varint, 1 fixed64, 2 length-delimited, 5 fixed32.
 * Negative {@code int32} values are sign-extended to ten bytes, as protobuf does.
 */
public final class Proto {

    private Proto() {
    }

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_FIXED64 = 1;
    public static final int WIRE_LENGTH = 2;
    public static final int WIRE_FIXED32 = 5;

    /** Appends fields to a byte string. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(int field, long value) {
            key(field, WIRE_VARINT);
            rawVarint(value);
            return this;
        }

        /** proto2 {@code int32}: a negative value is written as its 64-bit two's complement. */
        public Writer int32(int field, int value) {
            return varint(field, value);
        }

        public Writer bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        public Writer fixed32(int field, int value) {
            key(field, WIRE_FIXED32);
            for (int i = 0; i < 4; i++) {
                out.write(value >>> (8 * i));
            }
            return this;
        }

        public Writer fixed64(int field, long value) {
            key(field, WIRE_FIXED64);
            for (int i = 0; i < 8; i++) {
                out.write((int) (value >>> (8 * i)));
            }
            return this;
        }

        public Writer bytes(int field, byte[] value) {
            key(field, WIRE_LENGTH);
            rawVarint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public Writer message(int field, Writer nested) {
            return bytes(field, nested.toBytes());
        }

        public byte[] toBytes() {
            return out.toByteArray();
        }

        private void key(int field, int wire) {
            rawVarint(((long) field << 3) | wire);
        }

        private void rawVarint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }
    }

    /**
     * Walks the fields of a byte string.
     *
     * <pre>
     *   Reader r = new Reader(body);
     *   while (r.next()) {
     *       switch (r.field()) {
     *           case 1: x = r.varint(); break;
     *           default: r.skip();
     *       }
     *   }
     * </pre>
     */
    public static final class Reader {
        private final byte[] buf;
        private final int end;
        private int pos;
        private int field;
        private int wire;

        public Reader(byte[] buf) {
            this(buf, 0, buf.length);
        }

        public Reader(byte[] buf, int off, int len) {
            this.buf = buf;
            this.pos = off;
            this.end = off + len;
        }

        /** Advances to the next field; false at the end of the message. */
        public boolean next() {
            if (pos >= end) {
                return false;
            }
            long key = rawVarint();
            field = (int) (key >>> 3);
            wire = (int) (key & 7);
            return true;
        }

        public int field() {
            return field;
        }

        public int wire() {
            return wire;
        }

        public long varint() {
            expect(WIRE_VARINT);
            return rawVarint();
        }

        public boolean bool() {
            return varint() != 0;
        }

        public int fixed32() {
            expect(WIRE_FIXED32);
            int v = 0;
            for (int i = 0; i < 4; i++) {
                v |= (buf[pos++] & 0xFF) << (8 * i);
            }
            return v;
        }

        public long fixed64() {
            expect(WIRE_FIXED64);
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v |= (long) (buf[pos++] & 0xFF) << (8 * i);
            }
            return v;
        }

        public byte[] bytes() {
            expect(WIRE_LENGTH);
            int len = (int) rawVarint();
            if (len < 0 || pos + len > end) {
                throw new IllegalArgumentException("length-delimited field runs past the message");
            }
            byte[] v = new byte[len];
            System.arraycopy(buf, pos, v, 0, len);
            pos += len;
            return v;
        }

        public String string() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        /** A nested message as its own reader. */
        public Reader message() {
            return new Reader(bytes());
        }

        public void skip() {
            switch (wire) {
                case WIRE_VARINT:
                    rawVarint();
                    return;
                case WIRE_FIXED64:
                    pos += 8;
                    return;
                case WIRE_LENGTH:
                    bytes();
                    return;
                case WIRE_FIXED32:
                    pos += 4;
                    return;
                default:
                    throw new IllegalArgumentException("unsupported wire type " + wire);
            }
        }

        private void expect(int w) {
            if (wire != w) {
                throw new IllegalArgumentException("field " + field + ": wire type " + wire + ", wanted " + w);
            }
        }

        private long rawVarint() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) {
                    throw new IllegalArgumentException("truncated varint");
                }
                int b = buf[pos++] & 0xFF;
                v |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return v;
                }
            }
            throw new IllegalArgumentException("varint longer than 10 bytes");
        }
    }
}
