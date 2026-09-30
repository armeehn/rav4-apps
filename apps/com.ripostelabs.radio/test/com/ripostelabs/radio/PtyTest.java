package com.ripostelabs.radio;

import java.util.Arrays;

/**
 * RDS programme type: the vendor's 32-name table (UIControllerBase.java:16) and its PTY seek,
 * `05 03 n`, 20 ms, then key 22 (`02 16`); leaving the picker sends `05 03 00`
 * (PTYView.java:104-116).
 */
public final class PtyTest {

    public static void main(String[] args) {
        check(Pty.name(0).isEmpty(), "0 is no programme type: show nothing");
        check(Pty.name(1).equals("News"), "1 is News");
        check(Pty.name(10).equals("Pop music"), "10 is Pop music");
        check(Pty.name(31).equals("Alarm"), "31 is Alarm");
        check(Pty.name(32).isEmpty(), "off the table shows nothing");
        check(Pty.name(-1).isEmpty(), "negative shows nothing");
        check(Pty.COUNT == 32, "32 codes");

        bytes(RadioFrames.setup(Pty.SETUP_INDEX, 10), new byte[]{0x05, 0x03, 0x0A}, "pick Pop music");
        bytes(RadioFrames.setup(Pty.SETUP_INDEX, Pty.NONE), new byte[]{0x05, 0x03, 0x00}, "leave the picker");
        bytes(RadioFrames.key(Tuner.KEY_PTY_SEEK), new byte[]{0x02, 0x16}, "PTY seek key 22");
        check(Pty.SEEK_DELAY_MS == 20, "the vendor waits 20 ms between the setup and the key");
        System.out.println("PtyTest OK");
    }

    private static void bytes(byte[] got, byte[] want, String what) {
        check(Arrays.equals(got, want), what + ": " + Arrays.toString(got));
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
