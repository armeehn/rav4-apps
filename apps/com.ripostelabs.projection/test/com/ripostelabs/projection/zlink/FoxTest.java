package com.ripostelabs.projection.zlink;

import java.util.Arrays;

/**
 * Vectors are the first two frames the daemon sent on the bench (2026-09-19): a SessionState
 * {@code WAIT_INIT} and its build hash, captured with a raw listener before any code existed.
 */
public final class FoxTest {

    /** ff ff ff 10 | len 7 | id 0x101 | protobuf {1: 0x101, 2: 1, 3: 0} */
    private static final String SESSION_STATE = "ff ff ff 10 00 00 00 07 00 00 01 01 08 81 02 10 01 18 00";

    public static void main(String[] args) {
        capturedFrameDecodes();
        encodeMatchesCapture();
        splitAcrossReads();
        resyncsAfterJunk();
        sessionStateFields();
        initInfoCarriesTheLinkBit();
        touchAndKey();
        describeIsReadable();
        System.out.println("zlink: all checks passed");
    }

    private static void capturedFrameDecodes() {
        Fox.Parser p = new Fox.Parser();
        byte[] wire = hex(SESSION_STATE);
        p.feed(wire, 0, wire.length);
        Fox.Frame f = p.next();
        that(f != null, "frame parsed");
        eq(0x101, f.id, "id");
        bytes(hex("08 81 02 10 01 18 00"), f.payload, "payload");
        that(p.next() == null, "nothing left");
    }

    private static void encodeMatchesCapture() {
        byte[] wire = Fox.encode(0x101, hex("08 81 02 10 01 18 00"));
        bytes(hex(SESSION_STATE), wire, "encode");
    }

    private static void splitAcrossReads() {
        Fox.Parser p = new Fox.Parser();
        byte[] wire = hex(SESSION_STATE);
        p.feed(wire, 0, 5);
        that(p.next() == null, "header incomplete");
        p.feed(wire, 5, 9);
        that(p.next() == null, "payload incomplete");
        p.feed(wire, 14, wire.length - 14);
        Fox.Frame f = p.next();
        that(f != null && f.id == 0x101, "frame completes across three reads");
    }

    private static void resyncsAfterJunk() {
        Fox.Parser p = new Fox.Parser();
        byte[] junk = hex("de ad be ef");
        byte[] wire = hex(SESSION_STATE);
        p.feed(junk, 0, junk.length);
        p.feed(wire, 0, wire.length);
        Fox.Frame f = p.next();
        that(f != null && f.id == 0x101, "junk before the magic is skipped");
    }

    private static void sessionStateFields() {
        Messages.SessionState s = Messages.sessionState(hex("08 81 02 10 02 18 00"));
        eq(Messages.STATE_WAITING_LINK, s.state, "state");
        eq(0, s.linkType, "link type");
    }

    private static void initInfoCarriesTheLinkBit() {
        Messages.InitInfo i = new Messages.InitInfo();
        byte[] payload = Messages.initInfo(i);
        // field 1 = 0x102, field 2 = 1920, field 7 = 1 (wired CarPlay) at the daemon's positions
        that(startsWith(payload, hex("08 82 02 10 80 0f")), "id and width lead the message");
        that(contains(payload, hex("38 01")), "field 7 is the wired CarPlay bit");
    }

    private static void touchAndKey() {
        bytes(hex("08 92 02 10 64 18 c8 01 20 01"), Messages.touch(100, 200, true), "touch down");
        bytes(hex("08 93 02 10 03 18 00"), Messages.key(3, false), "key up");
    }

    private static void describeIsReadable() {
        String d = Messages.describe(hex("08 95 02 10 00 1a 03 41 42 43"));
        eq("1=0x115 2=0x0 3=\"ABC\"", d, "describe");
    }

    // ---- assertions (the aa tests' Check is package-private) ----------------------------------

    private static void that(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(long expected, long actual, String what) {
        that(expected == actual, what + ": expected " + expected + ", got " + actual);
    }

    private static void eq(String expected, String actual, String what) {
        that(expected.equals(actual), what + ": expected " + expected + ", got " + actual);
    }

    private static void bytes(byte[] expected, byte[] actual, String what) {
        that(Arrays.equals(expected, actual), what + ": expected " + Arrays.toString(expected)
                + ", got " + Arrays.toString(actual));
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        return b.length >= prefix.length && Arrays.equals(Arrays.copyOf(b, prefix.length), prefix);
    }

    private static boolean contains(byte[] b, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= b.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (b[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] hex(String dump) {
        String[] parts = dump.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }
}
