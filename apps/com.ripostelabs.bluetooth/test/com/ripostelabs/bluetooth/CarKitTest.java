package com.ripostelabs.bluetooth;

import java.nio.charset.StandardCharsets;

/**
 * RAV4-178: the launcher contract for connect / disconnect / forget, and the car name the
 * adapter will take. A wrong verb string is a broadcast the launcher silently drops.
 */
public final class CarKitTest {

    public static void main(String[] args) {
        // Must match the launcher's BtDeviceCommand (device-reveng launcher/carlib).
        expect("com.ripostelabs.carlauncher.action.BT_DEVICE", CarKit.ACTION);
        expect("op", CarKit.EXTRA_OP);
        expect("address", CarKit.EXTRA_ADDRESS);
        expect("CONNECT", CarKit.Op.CONNECT.name());
        expect("DISCONNECT", CarKit.Op.DISCONNECT.name());
        expect("FORGET", CarKit.Op.FORGET.name());

        // A name is trimmed; blank is no name at all.
        expect("RAV4 Sasha", CarKit.cleanName("  RAV4 Sasha "));
        expect(null, CarKit.cleanName("   "));
        expect(null, CarKit.cleanName(null));

        // Bluetooth names stop at 248 UTF-8 bytes; a cut never splits a character.
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) longName.append("é");   // 2 bytes each
        String cut = CarKit.cleanName(longName.toString());
        expect(124, cut.length());
        expect(true, cut.getBytes(StandardCharsets.UTF_8).length <= CarKit.MAX_NAME_BYTES);

        System.out.println("CarKitTest: ok");
    }

    private static void expect(Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError("expected " + want + " got " + got);
        }
    }
}
