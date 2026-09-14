package com.ripostelabs.lamp;

import java.util.UUID;

/**
 * Frame builder for the "Magic Lantern" / ELK-BLEDOM family of BLE LED controllers.
 *
 * Every command is one 9-byte GATT write to characteristic {@link #WRITE}:
 *
 *   7E | LEN | CMD | P1 | P2 | P3 | P4 | P5 | EF
 *
 * LEN is checked by the firmware and differs per command, so each builder spells its own
 * frame out in full rather than deriving LEN. Unused parameters are 0xFF. Values were
 * sniffed from the vendor app (Home Assistant issue #145934, LotusLantern PROTOCOL.md).
 *
 * Pure bytes, no Android: this class is what the unit test covers.
 */
final class Elk {

    static final UUID SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    static final UUID WRITE = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    /** Advertised name prefixes the vendor app accepts. */
    private static final String[] NAME_PREFIXES = {
        "ELK-", "ELK~", "MELK-", "LED LIGHT STRIP", "XSL-", "LEDBLE",
    };

    static final int MAX_PERCENT = 100;
    /** Highest effect index the vendor app sends (0xCF, "yellow marquee"). */
    static final int MAX_EFFECT = 0xCF;
    static final int AUTO_EFFECT = 0;

    private static final int HEAD = 0x7E;
    private static final int TAIL = 0xEF;
    private static final int PAD = 0xFF;

    private static final int CMD_BRIGHTNESS = 0x01;
    private static final int CMD_SPEED = 0x02;
    private static final int CMD_EFFECT = 0x03;
    private static final int CMD_POWER = 0x04;
    private static final int CMD_COLOUR = 0x05;
    private static final int CMD_MIC_GAIN = 0x06;
    private static final int CMD_MIC = 0x07;

    // Sub-selectors the vendor app sends with the command byte.
    private static final int COLOUR_RGB = 0x03;
    private static final int COLOUR_TAIL = 0x10;
    private static final int EFFECT_RGBIC = 0x06;
    private static final int POWER_ON_MASK = 0xF0;

    enum Switch { ON, OFF }

    private Elk() {}

    static boolean isLamp(String name) {
        if (name == null) {
            return false;
        }
        for (String p : NAME_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 7E 04 04 F0 00 01 FF 00 EF (on) / 7E 04 04 00 00 00 FF 00 EF (off). */
    static byte[] power(Switch s) {
        int on = s == Switch.ON ? 1 : 0;
        return frame(0x04, CMD_POWER, on == 1 ? POWER_ON_MASK : 0x00, 0x00, on, PAD, 0x00);
    }

    /** 7E 07 05 03 RR GG BB 10 EF. Takes a packed 0xRRGGBB int. */
    static byte[] colour(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return frame(0x07, CMD_COLOUR, COLOUR_RGB, r, g, b, COLOUR_TAIL);
    }

    /** 7E 04 01 pp 01 FF FF 00 EF, pp = 0..100. */
    static byte[] brightness(int percent) {
        return frame(0x04, CMD_BRIGHTNESS, clampPercent(percent), 0x01, PAD, PAD, 0x00);
    }

    /** 7E 04 02 pp FF FF FF 00 EF, pp = 0..100. */
    static byte[] speed(int percent) {
        return frame(0x04, CMD_SPEED, clampPercent(percent), PAD, PAD, PAD, 0x00);
    }

    /** 7E 05 03 mm 06 FF FF 00 EF, mm = 0 (auto) .. 0xCF. */
    static byte[] effect(int mode) {
        int m = Math.max(AUTO_EFFECT, Math.min(MAX_EFFECT, mode));
        return frame(0x05, CMD_EFFECT, m, EFFECT_RGBIC, PAD, PAD, 0x00);
    }

    /** 7E 04 07 oo FF FF FF 00 EF. Music-reactive mode on the controller's own mic. */
    static byte[] mic(Switch s) {
        return frame(0x04, CMD_MIC, s == Switch.ON ? 1 : 0, PAD, PAD, PAD, 0x00);
    }

    /** 7E 04 06 ll FF FF FF 00 EF, ll = 0..100. */
    static byte[] micGain(int percent) {
        return frame(0x04, CMD_MIC_GAIN, clampPercent(percent), PAD, PAD, PAD, 0x00);
    }

    /** Command byte of a built frame; the write queue coalesces on it. */
    static int command(byte[] frame) {
        return frame[2] & 0xFF;
    }

    static String hex(byte[] frame) {
        StringBuilder sb = new StringBuilder(frame.length * 2);
        for (byte b : frame) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static int clampPercent(int p) {
        return Math.max(0, Math.min(MAX_PERCENT, p));
    }

    private static byte[] frame(int len, int cmd, int p1, int p2, int p3, int p4, int p5) {
        return new byte[] {
            (byte) HEAD, (byte) len, (byte) cmd,
            (byte) p1, (byte) p2, (byte) p3, (byte) p4, (byte) p5,
            (byte) TAIL,
        };
    }
}
