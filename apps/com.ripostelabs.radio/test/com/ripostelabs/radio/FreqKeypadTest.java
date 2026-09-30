package com.ripostelabs.radio;

/**
 * The direct-entry keypad, the vendor's KeyboardView (KeyboardView.java:170-240): at most 6
 * keys, one dot and only on FM, and the value snapped to the zone's grid before it is tuned.
 */
public final class FreqKeypadTest {

    public static void main(String[] args) {
        FreqKeypad.Band fm = FreqKeypad.Band.FM;
        FreqKeypad.Band am = FreqKeypad.Band.AM;

        // Typing rules.
        check(FreqKeypad.type("96", '.', fm).equals("96."), "FM takes a dot");
        check(FreqKeypad.type("96.3", '.', fm).equals("96.3"), "FM takes one dot only");
        check(FreqKeypad.type("10", '.', am).equals("10"), "AM takes no dot");
        check(FreqKeypad.type("107.90", '1', fm).equals("107.90"), "6 keys at most");
        check(FreqKeypad.type("96.3", FreqKeypad.DELETE, fm).equals("96."), "delete drops the last key");
        check(FreqKeypad.type("", FreqKeypad.DELETE, fm).isEmpty(), "delete on empty stays empty");

        // Parsing and snapping, North America (zone 1): FM 87.5-107.9 in 200 kHz.
        RadioZone.Plan naFm = RadioZone.of(1).fm;
        check(FreqKeypad.freq("96.3", naFm, fm) == 9630, "96.3 is 9630");
        check(FreqKeypad.freq("96.30", naFm, fm) == 9630, "96.30 is 9630");
        check(FreqKeypad.freq("96.4", naFm, fm) == 9630, "96.4 snaps down to the 200 kHz grid");
        check(FreqKeypad.freq("101.7", naFm, fm) == 10170, "101.7 is 10170");
        check(FreqKeypad.freq("96", naFm, fm) == 9590, "96 is 96.00, off the odd-tenth grid, so 95.9");
        check(FreqKeypad.freq("1017", naFm, fm) == 10170, "no dot and too big for MHz reads as tenths");
        check(FreqKeypad.freq("120", naFm, fm) == 10790, "over the band clamps high");
        check(FreqKeypad.freq("50", naFm, fm) == 8750, "under the band clamps low");
        check(FreqKeypad.freq("", naFm, fm) == FreqKeypad.NONE, "empty is no frequency");
        check(FreqKeypad.freq(".", naFm, fm) == FreqKeypad.NONE, "a dot alone is no frequency");

        RadioZone.Plan naAm = RadioZone.of(1).am;
        check(FreqKeypad.freq("1010", naAm, am) == 1010, "AM 1010 kHz");
        check(FreqKeypad.freq("1015", naAm, am) == 1010, "AM snaps to 10 kHz");
        System.out.println("FreqKeypadTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
