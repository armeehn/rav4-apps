package com.ripostelabs.radio;

/**
 * The band plan behind the MCU's zone setting (`05 01 z`), as the vendor radio draws it:
 * bounds and dial step per zone from UIControllerBase.getCurrRadioFreq
 * (UIControllerBase.java:117-209, the landscape branch this unit runs). Units are the
 * tuner's: FM in 10 kHz (9630 = 96.30 MHz), AM in kHz. Pure, so it has a test.
 *
 *   zone  FM                    AM
 *   0     87.5-108   50 kHz     522-1620  9 kHz   (Europe, the vendor default)
 *   1     87.5-107.9 200 kHz    530-1710 10 kHz   (North America)
 *   2     87.5-108   100 kHz    520-1620 10 kHz
 *   3     65.0-74.0   30 kHz    522-1620  9 kHz   (OIRT)
 *   4     76.0-90.0  100 kHz    522-1629  9 kHz   (Japan)
 *   5     87.5-108   100 kHz    530-1710  9 kHz
 */
final class RadioZone {

    /** One band: bounds and dial step. */
    static final class Plan {
        final int min, max, step;

        Plan(int min, int max, int step) {
            this.min = min; this.max = max; this.step = step;
        }

        /** reformatFreqInt (UIControllerBase.java:211-227): clamp, then snap down to the grid. */
        int snap(int freq) {
            if (freq < min) return min;
            if (freq > max) return max;
            return min + ((freq - min) / step) * step;
        }

        /** Slider range: the number of steps from min to max. */
        int steps() { return (max - min) / step; }

        int atStep(int progress) { return min + progress * step; }
    }

    /** First slot of the AM banks in the MCU's 42-entry list (`i += 18`, MainActivity.java:170-175). */
    private static final int AM_SLOT_OFFSET = 18;

    /** What the launcher sends the MCU when nothing is configured; also the vendor's SysVar default. */
    static final int DEFAULT_ZONE = 0;

    private static final RadioZone[] ZONES = {
            new RadioZone(new Plan(8750, 10800, 5), new Plan(522, 1620, 9)),
            new RadioZone(new Plan(8750, 10790, 20), new Plan(530, 1710, 10)),
            new RadioZone(new Plan(8750, 10800, 10), new Plan(520, 1620, 10)),
            new RadioZone(new Plan(6500, 7400, 3), new Plan(522, 1620, 9)),
            new RadioZone(new Plan(7600, 9000, 10), new Plan(522, 1629, 9)),
            new RadioZone(new Plan(8750, 10800, 10), new Plan(530, 1710, 9)),
    };

    final Plan fm, am;

    private RadioZone(Plan fm, Plan am) {
        this.fm = fm; this.am = am;
    }

    /** The zone for a setting value; off the table reads as zone 0, like the gateway's clamp. */
    static RadioZone of(int zone) {
        if (zone < 0 || zone >= ZONES.length) return ZONES[DEFAULT_ZONE];
        return ZONES[zone];
    }

    /** The plan for a band as getRadioBand() counts them: 0..2 FM, 3+ AM. */
    static Plan plan(int zone, int band) {
        RadioZone z = of(zone);
        return band >= 3 ? z.am : z.fm;
    }

    /** Slot in the MCU's list for a preset position within a band: FM 0.., AM 18.. */
    static int stationSlot(int band, int position) {
        return band >= 3 ? AM_SLOT_OFFSET + position : position;
    }
}
