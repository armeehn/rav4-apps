package com.ripostelabs.radio;

/**
 * Direct frequency entry, the vendor's KeyboardView (KeyboardView.java:170-240) without the
 * view. The keypad works in the current band class: FM takes one dot and reads MHz, AM reads
 * whole kHz. The typed value is snapped to the zone's grid, so it is always tunable.
 *
 *   FM  "96.3" -> 9630    "1017" -> 10170 (no dot, too big for MHz: read as tenths)
 *   AM  "1010" -> 1010
 */
final class FreqKeypad {

    enum Band { FM, AM }

    /** Not a frequency: empty or unparsable input. */
    static final int NONE = -1;
    /** The key that removes the last character. */
    static final char DELETE = '<';

    /** The vendor stops at 6 keys (`getListSize() >= 6`). */
    private static final int MAX_KEYS = 6;
    private static final char DOT = '.';
    /** No FM band reaches 200 MHz, so a dot-less value this big was typed in tenths. */
    private static final int MAX_WHOLE_MHZ = 200;
    /** Tuner FM units per MHz (10 kHz steps). */
    private static final int UNITS_PER_MHZ = 100;
    private static final int UNITS_PER_TENTH = 10;

    private FreqKeypad() {}

    /** The entry after one key press; a key the rules refuse leaves it as it was. */
    static String type(String text, char key, Band band) {
        if (key == DELETE) {
            return text.isEmpty() ? text : text.substring(0, text.length() - 1);
        }
        if (text.length() >= MAX_KEYS) {
            return text;
        }
        if (key == DOT && (band == Band.AM || text.indexOf(DOT) >= 0)) {
            return text;
        }
        if (key != DOT && !Character.isDigit(key)) {
            return text;
        }
        return text + key;
    }

    /** The entry as a tuner frequency on the plan's grid, or {@link #NONE}. */
    static int freq(String text, RadioZone.Plan plan, Band band) {
        int raw = band == Band.FM ? fmUnits(text) : digits(text);
        if (raw == NONE) {
            return NONE;
        }
        return plan.snap(raw);
    }

    /** "96.3" -> 9630: whole MHz times 100 plus up to two decimals. */
    private static int fmUnits(String text) {
        int dot = text.indexOf(DOT);
        if (dot < 0) {
            int whole = digits(text);
            if (whole == NONE) {
                return NONE;
            }
            return whole >= MAX_WHOLE_MHZ ? whole * UNITS_PER_TENTH : whole * UNITS_PER_MHZ;
        }

        String wholePart = text.substring(0, dot);
        String fracPart = (text.substring(dot + 1) + "00").substring(0, 2);
        int whole = wholePart.isEmpty() ? 0 : digits(wholePart);
        int frac = digits(fracPart);
        if (whole == NONE || frac == NONE || (wholePart.isEmpty() && text.length() == 1)) {
            return NONE;
        }
        return whole * UNITS_PER_MHZ + frac;
    }

    private static int digits(String text) {
        if (text.isEmpty() || text.length() > MAX_KEYS) {
            return NONE;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return NONE;
            }
        }
        return Integer.parseInt(text);
    }
}
