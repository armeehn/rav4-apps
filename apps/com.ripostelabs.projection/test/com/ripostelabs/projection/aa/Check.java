package com.ripostelabs.projection.aa;

import java.util.Arrays;

/** The assertion vocabulary the tests share. A failure throws; that is the whole harness. */
final class Check {

    static int count;

    private Check() {
    }

    static void that(boolean ok, String what) {
        count++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void eq(long expected, long actual, String what) {
        that(expected == actual, what + ": expected " + expected + ", got " + actual);
    }

    static void eq(String expected, String actual, String what) {
        that(expected.equals(actual), what + ": expected " + expected + ", got " + actual);
    }

    static void bytes(byte[] expected, byte[] actual, String what) {
        that(Arrays.equals(expected, actual), what + ": expected " + hex(expected) + ", got " + hex(actual));
    }

    /** "00 03 00 06" style literals, so a vector reads like a packet dump. */
    static byte[] hex(String dump) {
        String[] parts = dump.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x ", x));
        }
        return sb.toString().trim();
    }
}
