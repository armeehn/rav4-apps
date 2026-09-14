package com.ripostelabs.projection.aa;

import java.util.List;

/**
 * Vectors are the byte strings the open receivers put on the wire: the version request
 * {@code 00 03 00 06 00 01 00 01 00 01} and the auth-complete {@code 00 03 00 04 00 04 08 00}
 * from headunit {@code hu_aap.c} are the canonical ones.
 */
public final class FrameTest {

    public static void main(String[] args) {
        versionRequestVector();
        authCompleteVector();
        flagsRoundTrip();
        longHeader();
        incompleteInput();
        messageIdPrefix();
        splitAndReassemble();
        streamReader();
        System.out.println(Check.count + " assertions passed");
    }

    private static void versionRequestVector() {
        byte[] message = Frame.withId(Ids.VERSION_REQUEST, Messages.versionRequest(1, 1));
        Frame f = new Frame(Ids.CH_CONTROL, Frame.FLAG_BULK, 0, message);
        Check.bytes(Check.hex("00 03 00 06 00 01 00 01 00 01"), f.encode(), "version request frame");

        Frame back = Frame.decode(f.encode(), 0, 10);
        Check.eq(0, back.channel, "channel");
        Check.eq(Frame.FLAG_BULK, back.flags, "flags");
        Check.eq(6, back.payload.length, "payload length");
        Check.that(!back.encrypted() && !back.control(), "plain, not control");
    }

    private static void authCompleteVector() {
        byte[] message = Frame.withId(Ids.AUTH_COMPLETE, Messages.authComplete(Messages.STATUS_OK));
        Frame f = new Frame(Ids.CH_CONTROL, Frame.FLAG_BULK, 0, message);
        Check.bytes(Check.hex("00 03 00 04 00 04 08 00"), f.encode(), "auth complete frame");
    }

    private static void flagsRoundTrip() {
        // An encrypted control-type message on the video channel, as a channel-open response.
        int flags = Frame.FLAG_BULK | Frame.FLAG_CONTROL | Frame.FLAG_ENCRYPTED;
        Check.eq(0x0F, flags, "headunit's 0x0f");
        Frame f = new Frame(Ids.CH_VIDEO, flags, 0, new byte[] {1, 2, 3});
        byte[] wire = f.encode();
        Check.bytes(Check.hex("03 0f 00 03 01 02 03"), wire, "encrypted control frame");

        Frame back = Frame.decode(wire, 0, wire.length);
        Check.that(back.encrypted(), "encrypted flag");
        Check.that(back.control(), "control flag");
        Check.eq(Frame.FLAG_BULK, back.frameType(), "type bits only");
    }

    private static void longHeader() {
        Check.eq(4, Frame.headerLength(Frame.FLAG_BULK), "bulk header");
        Check.eq(8, Frame.headerLength(Frame.FLAG_FIRST), "first header");
        Check.eq(4, Frame.headerLength(0), "middle header");
        Check.eq(4, Frame.headerLength(Frame.FLAG_LAST), "last header");

        byte[] h = Frame.header(3, Frame.FLAG_FIRST | Frame.FLAG_ENCRYPTED, 0x4010, 0x00012345);
        Check.bytes(Check.hex("03 09 40 10 00 01 23 45"), h, "first header with total");

        byte[] wire = new byte[8 + 0x4010];
        System.arraycopy(h, 0, wire, 0, 8);
        Frame f = Frame.decode(wire, 0, wire.length);
        Check.eq(0x00012345, f.totalLength, "total length decoded");
        Check.eq(0x4010, f.payload.length, "payload length decoded");
    }

    private static void incompleteInput() {
        byte[] wire = Check.hex("00 03 00 06 00 01 00 01 00 01");
        Check.that(Frame.decode(wire, 0, 3) == null, "no header yet");
        Check.that(Frame.decode(wire, 0, 9) == null, "payload short by one");
        Check.that(Frame.decode(wire, 0, 10) != null, "complete");
        Check.eq(-1, Frame.encodedLength(wire, 0, 2), "length unknown");
        Check.eq(10, Frame.encodedLength(wire, 0, 4), "length known from the header");
    }

    private static void messageIdPrefix() {
        byte[] m = Frame.withId(0x8003, new byte[] {8, 2});
        Check.bytes(Check.hex("80 03 08 02"), m, "big-endian id");
        Check.eq(0x8003, Frame.messageId(m), "id read back");
        Check.bytes(new byte[] {8, 2}, Frame.body(m), "body");
    }

    private static void splitAndReassemble() {
        byte[] small = new byte[Frame.MAX_PAYLOAD - 1];
        List<Frame.Chunk> one = Frame.split(small);
        Check.eq(1, one.size(), "below the limit is one chunk");
        Check.eq(Frame.FLAG_BULK, one.get(0).frameType, "and it is BULK");

        byte[] exact = new byte[Frame.MAX_PAYLOAD];
        Check.eq(2, Frame.split(exact).size(), "aasdk splits at >= 0x4000");

        byte[] big = new byte[Frame.MAX_PAYLOAD * 2 + 5];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        List<Frame.Chunk> chunks = Frame.split(big);
        Check.eq(3, chunks.size(), "first, middle, last");
        Check.eq(Frame.FLAG_FIRST, chunks.get(0).frameType, "first");
        Check.eq(0, chunks.get(1).frameType, "middle");
        Check.eq(Frame.FLAG_LAST, chunks.get(2).frameType, "last");
        Check.eq(5, chunks.get(2).data.length, "tail size");

        Frame.Assembler a = new Frame.Assembler();
        Check.that(a.feed(3, Frame.FLAG_FIRST, chunks.get(0).data) == null, "first: incomplete");
        // Another channel's bulk message in between must not disturb the video stream.
        Check.bytes(new byte[] {9}, a.feed(0, Frame.FLAG_BULK, new byte[] {9}), "interleaved bulk");
        Check.that(a.feed(3, 0, chunks.get(1).data) == null, "middle: incomplete");
        Check.bytes(big, a.feed(3, Frame.FLAG_LAST, chunks.get(2).data), "reassembled");
    }

    private static void streamReader() {
        byte[] a = Check.hex("00 03 00 06 00 01 00 01 00 01");
        byte[] b = Check.hex("03 0f 00 03 01 02 03");
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);

        Frame.Reader r = new Frame.Reader();
        r.push(both, 0, 7);
        Check.that(r.next() == null, "first frame not yet complete");
        r.push(both, 7, both.length - 7);
        Frame f1 = r.next();
        Frame f2 = r.next();
        Check.that(f1 != null && f2 != null, "two frames out");
        Check.eq(0, f1.channel, "first is control");
        Check.eq(3, f2.channel, "second is video");
        Check.that(r.next() == null, "drained");
    }
}
