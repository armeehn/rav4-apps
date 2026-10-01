package com.ripostelabs.radio;

/**
 * The band judged against the frequency. On 2026-09-30 the car was on AM while the band
 * still read FM: 530 kHz showed as "5.30 MHz", the FM grid drew the dial and the presets
 * read FM slots. The units cannot lie: below 65.00 MHz (the OIRT floor) a value is AM kHz.
 */
public final class AmBandTest {

    public static void main(String[] args) {
        // A missed band report: AM frequencies under an FM band are AM1.
        check(RadioZone.bandFor(1, 530) == 3, "530 under FM2 is AM1");
        check(RadioZone.bandFor(0, 1150) == 3, "CKFR 1150 under FM1 is AM1");

        // And the other way: an FM frequency under an AM band is FM1.
        check(RadioZone.bandFor(4, 9630) == 0, "96.30 under AM2 is FM1");

        // An agreeing band is kept as reported, so AM2 and FM3 keep their own presets.
        check(RadioZone.bandFor(4, 1150) == 4, "AM2 stays AM2");
        check(RadioZone.bandFor(2, 6590) == 2, "OIRT 65.90 on FM3 stays FM3");

        // No frequency yet: nothing to judge by.
        check(RadioZone.bandFor(1, 0) == 1, "no frequency keeps the band");
        check(RadioZone.bandFor(1, -1) == 1, "unknown frequency keeps the band");

        // FM and AM presets never share a slot once the band is right.
        check(RadioZone.stationSlot(RadioZone.bandFor(1, 530), 0) == 18, "AM preset 1 is slot 18, not FM2's 6");
        check(RadioZone.bankLabel(RadioZone.bandFor(1, 530)).equals("AM1"), "label reads AM1");
        check(RadioZone.isAm(RadioZone.bandFor(1, 530)), "AM band draws kHz");
        check(!RadioZone.isAm(RadioZone.bandFor(4, 9630)), "FM band draws MHz");
        System.out.println("AmBandTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
