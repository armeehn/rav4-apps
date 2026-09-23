package com.ripostelabs.radio;

/**
 * The band plan per tuner zone, the vendor radio's getCurrRadioFreq table
 * (UIControllerBase.java:117-209, landscape branch). Wrong bounds draw a dial the MCU
 * cannot follow, so the table has a test.
 */
public final class RadioZoneTest {

    public static void main(String[] args) {
        RadioZone.Plan fm = RadioZone.of(1).fm;
        check(fm.min == 8750 && fm.max == 10790 && fm.step == 20, "zone 1 FM is 87.5-107.9 in 200 kHz");
        RadioZone.Plan am = RadioZone.of(1).am;
        check(am.min == 530 && am.max == 1710 && am.step == 10, "zone 1 AM is 530-1710 in 10 kHz");

        RadioZone.Plan eu = RadioZone.of(0).fm;
        check(eu.min == 8750 && eu.max == 10800 && eu.step == 5, "zone 0 FM is 87.5-108 in 50 kHz");
        check(RadioZone.of(0).am.step == 9, "zone 0 AM steps 9 kHz");
        check(RadioZone.of(3).fm.min == 6500, "zone 3 is OIRT");
        check(RadioZone.of(4).fm.max == 9000, "zone 4 is Japan");
        check(RadioZone.of(9) == RadioZone.of(0), "off the table reads as zone 0");
        check(RadioZone.of(-1) == RadioZone.of(0), "negative reads as zone 0");

        check(fm.snap(8000) == 8750, "snap clamps low");
        check(fm.snap(11000) == 10790, "snap clamps high");
        check(fm.snap(9639) == 9630, "snap steps down");
        check(fm.steps() == 102, "102 steps across the NA FM band");
        check(fm.atStep(1) == 8770, "step 1 is 87.7");

        check(RadioZone.plan(1, 0) == fm, "band 0..2 is FM");
        check(RadioZone.plan(1, 3) == am, "band 3+ is AM");
        check(RadioZone.stationSlot(0, 2) == 2, "FM slots start at 0");
        check(RadioZone.stationSlot(3, 2) == 20, "AM slots start at 18");
        System.out.println("RadioZoneTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
