package com.ripostelabs.radio;

/**
 * RDS programme type (PTY), radio event 5. Names are the vendor's 32-entry table
 * (UIControllerBase.java:16), spelled out: "pop m" is "Pop music". Pure, so it has a test.
 *
 * PTY seek (PTYView.java:104-116): `05 03 n` selects the type, 20 ms later key 22 (`02 16`)
 * seeks to the next station sending it; leaving the picker clears the type with `05 03 00`.
 */
final class Pty {

    /** sendSetup index for the PTY seek target. */
    static final int SETUP_INDEX = 3;
    /** Code 0: no programme type. */
    static final int NONE = 0;
    /** The vendor sleeps this long between the setup and the seek key. */
    static final long SEEK_DELAY_MS = 20;

    private static final String[] NAMES = {
            "", "News", "Current affairs", "Information", "Sport", "Education", "Drama",
            "Culture", "Science", "Varied", "Pop music", "Rock music", "Easy listening",
            "Light classical", "Classical", "Other music", "Weather", "Finance",
            "Children's", "Social affairs", "Religion", "Phone-in", "Travel", "Leisure",
            "Jazz", "Country", "National music", "Oldies", "Folk music", "Documentary",
            "Test", "Alarm",
    };

    static final int COUNT = NAMES.length;

    private Pty() {}

    /** The name for a PTY code, empty for 0 or anything off the table. */
    static String name(int code) {
        if (code <= NONE || code >= COUNT) {
            return "";
        }
        return NAMES[code];
    }
}
