package com.ripostelabs.lamp;

/**
 * Frames must match the bytes sniffed from the vendor app byte for byte: the firmware
 * validates LEN and silently drops anything else, so an off-by-one here is an unlit lamp
 * with no error anywhere.
 */
public final class ElkTest {

    public static void main(String[] args) {
        // Sniffed reference frames (Home Assistant core issue #145934).
        expect("7e0404f00001ff00ef", Elk.power(Elk.Switch.ON));
        expect("7e0404000000ff00ef", Elk.power(Elk.Switch.OFF));
        expect("7e0705030000ff10ef", Elk.colour(0x0000FF));
        expect("7e07050312ab5610ef", Elk.colour(0x12AB56));
        expect("7e04016401ffff00ef", Elk.brightness(100));
        expect("7e04010001ffff00ef", Elk.brightness(0));
        expect("7e04014601ffff00ef", Elk.brightness(70));
        expect("7e040227ffffff00ef", Elk.speed(39));
        expect("7e05030006ffff00ef", Elk.effect(0));
        expect("7e0503cf06ffff00ef", Elk.effect(0xCF));
        expect("7e040701ffffff00ef", Elk.mic(Elk.Switch.ON));
        expect("7e040632ffffff00ef", Elk.micGain(50));

        // Out-of-range input clamps instead of corrupting the frame.
        expect("7e04016401ffff00ef", Elk.brightness(250));
        expect("7e04010001ffff00ef", Elk.brightness(-5));
        expect("7e0503cf06ffff00ef", Elk.effect(999));

        // Every frame is 9 bytes with the delimiters in place.
        for (byte[] f : new byte[][] {Elk.power(Elk.Switch.ON), Elk.colour(0), Elk.speed(1)}) {
            check(f.length == 9, "frame length " + f.length);
            check((f[0] & 0xFF) == 0x7E && (f[8] & 0xFF) == 0xEF, "delimiters " + Elk.hex(f));
        }

        check(Elk.command(Elk.colour(0)) == 0x05, "command byte");

        check(Elk.isLamp("ELK-BLEDOM"), "ELK-BLEDOM accepted");
        check(Elk.isLamp("MELK-OA12"), "MELK- accepted");
        check(!Elk.isLamp("Toyota RAV4"), "car rejected");
        check(!Elk.isLamp(null), "null rejected");

        System.out.println("ElkTest ok");
    }

    private static void expect(String hex, byte[] frame) {
        check(hex.equals(Elk.hex(frame)), "expected " + hex + " got " + Elk.hex(frame));
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
