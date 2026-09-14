package com.ripostelabs.projection.aa;

/** Varint edges, the four wire types, nesting, and skipping what we do not model. */
public final class ProtoTest {

    public static void main(String[] args) {
        varints();
        negativeInt32();
        fixed();
        lengthDelimited();
        nested();
        skipUnknown();
        truncated();
        System.out.println(Check.count + " assertions passed");
    }

    private static void varints() {
        Check.bytes(Check.hex("08 00"), new Proto.Writer().varint(1, 0).toBytes(), "zero");
        Check.bytes(Check.hex("08 7f"), new Proto.Writer().varint(1, 127).toBytes(), "one byte max");
        Check.bytes(Check.hex("08 80 01"), new Proto.Writer().varint(1, 128).toBytes(), "two bytes");
        Check.bytes(Check.hex("08 ac 02"), new Proto.Writer().varint(1, 300).toBytes(), "spec example 300");
        Check.bytes(Check.hex("10 ff ff 03"), new Proto.Writer().varint(2, 0xFFFF).toBytes(), "mismatch status");
        Check.bytes(Check.hex("f8 01 01"), new Proto.Writer().varint(31, 1).toBytes(), "two-byte key");

        Proto.Reader r = new Proto.Reader(Check.hex("08 ac 02 10 ff ff 03"));
        Check.that(r.next(), "first field");
        Check.eq(1, r.field(), "field 1");
        Check.eq(300, r.varint(), "300");
        Check.that(r.next(), "second field");
        Check.eq(2, r.field(), "field 2");
        Check.eq(0xFFFF, r.varint(), "65535");
        Check.that(!r.next(), "end");
    }

    private static void negativeInt32() {
        byte[] b = new Proto.Writer().int32(1, -1).toBytes();
        Check.eq(11, b.length, "ten-byte varint plus key");
        Proto.Reader r = new Proto.Reader(b);
        r.next();
        Check.eq(-1, (int) r.varint(), "round trip");
    }

    private static void fixed() {
        byte[] b = new Proto.Writer().fixed32(1, 0x01020304).fixed64(2, 0x0102030405060708L).toBytes();
        Check.bytes(Check.hex("0d 04 03 02 01 11 08 07 06 05 04 03 02 01"), b, "little-endian fixed");
        Proto.Reader r = new Proto.Reader(b);
        r.next();
        Check.eq(Proto.WIRE_FIXED32, r.wire(), "wire 5");
        Check.eq(0x01020304, r.fixed32(), "fixed32");
        r.next();
        Check.eq(Proto.WIRE_FIXED64, r.wire(), "wire 1");
        Check.eq(0x0102030405060708L, r.fixed64(), "fixed64");
    }

    private static void lengthDelimited() {
        byte[] b = new Proto.Writer().string(2, "testing").toBytes();
        Check.bytes(Check.hex("12 07 74 65 73 74 69 6e 67"), b, "spec example string");
        Proto.Reader r = new Proto.Reader(b);
        r.next();
        Check.eq("testing", r.string(), "string back");

        byte[] empty = new Proto.Writer().bytes(1, new byte[0]).toBytes();
        Check.bytes(Check.hex("0a 00"), empty, "empty bytes");
    }

    private static void nested() {
        // c { a: 150 } as in the encoding spec: 1a 03 08 96 01
        byte[] b = new Proto.Writer().message(3, new Proto.Writer().varint(1, 150)).toBytes();
        Check.bytes(Check.hex("1a 03 08 96 01"), b, "nested spec example");
        Proto.Reader r = new Proto.Reader(b);
        r.next();
        Proto.Reader inner = r.message();
        inner.next();
        Check.eq(150, inner.varint(), "inner value");
        Check.that(!inner.next(), "inner end");
        Check.that(!r.next(), "outer end");
    }

    private static void skipUnknown() {
        byte[] b = new Proto.Writer().varint(1, 5).fixed64(2, 9).string(3, "x").fixed32(4, 7).varint(5, 6).toBytes();
        Proto.Reader r = new Proto.Reader(b);
        int seen = 0;
        while (r.next()) {
            if (r.field() == 5) {
                Check.eq(6, r.varint(), "reached field 5 past four skipped types");
                seen++;
            } else {
                r.skip();
            }
        }
        Check.eq(1, seen, "field 5 seen once");
    }

    private static void truncated() {
        boolean threw = false;
        try {
            Proto.Reader r = new Proto.Reader(Check.hex("0a 05 01"));
            r.next();
            r.bytes();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        Check.that(threw, "length past the end is refused");

        threw = false;
        try {
            Proto.Reader r = new Proto.Reader(Check.hex("08 80"));
            r.next();
            r.varint();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        Check.that(threw, "truncated varint is refused");
    }
}
