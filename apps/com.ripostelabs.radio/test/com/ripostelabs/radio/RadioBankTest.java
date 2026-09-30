package com.ripostelabs.radio;

import java.util.Arrays;

/**
 * The preset banks as the vendor radio lays them out. Its grid shows all 18 FM slots
 * (FM1 = 0-5, FM2 = 6-11, FM3 = 12-17) and 12 AM slots (AM1 = 18-23, AM2 = 24-29), and a
 * cell is `band * 6 + tune` (RadioUIControllerLandscape.java:1048-1062). Tap sends
 * `02 64 slot`, hold `02 65 slot` (FreqView.java:161-192). A wrong slot stores FM2 over FM1.
 */
public final class RadioBankTest {

    public static void main(String[] args) {
        // Slot for (band, position): FM banks first, AM from 18.
        check(RadioZone.stationSlot(0, 0) == 0, "FM1 01 is slot 0");
        check(RadioZone.stationSlot(0, 5) == 5, "FM1 06 is slot 5");
        check(RadioZone.stationSlot(1, 0) == 6, "FM2 01 is slot 6");
        check(RadioZone.stationSlot(2, 5) == 17, "FM3 06 is slot 17");
        check(RadioZone.stationSlot(3, 0) == 18, "AM1 01 is slot 18");
        check(RadioZone.stationSlot(3, 2) == 20, "AM1 03 is slot 20");
        check(RadioZone.stationSlot(4, 5) == 29, "AM2 06 is slot 29");

        // 3 FM banks and 2 AM banks of 6 give 18 + 12 distinct slots.
        boolean[] seen = new boolean[42];
        for (int band = 0; band <= 4; band++) {
            for (int pos = 0; pos < RadioZone.BANK_SLOTS; pos++) {
                int slot = RadioZone.stationSlot(band, pos);
                check(!seen[slot], "slot " + slot + " is used once");
                seen[slot] = true;
            }
        }

        // Bank labels, the vendor's "FM%d" / "AM%d" (MainActivity.java:645-650).
        check(RadioZone.bankLabel(0).equals("FM1"), "band 0 is FM1");
        check(RadioZone.bankLabel(2).equals("FM3"), "band 2 is FM3");
        check(RadioZone.bankLabel(3).equals("AM1"), "band 3 is AM1");
        check(RadioZone.bankLabel(4).equals("AM2"), "band 4 is AM2");

        // Frame bytes against the vendor's sendRadioCmd / sendRadioKey.
        bytes(RadioFrames.recall(RadioZone.stationSlot(1, 2)), new byte[]{0x02, 0x64, 0x08}, "tap FM2 03");
        bytes(RadioFrames.store(RadioZone.stationSlot(4, 5)), new byte[]{0x02, 0x65, 0x1D}, "hold AM2 06");
        bytes(RadioFrames.key(Tuner.KEY_BAND_CYCLE), new byte[]{0x02, 0x18}, "bank key 24");
        System.out.println("RadioBankTest OK");
    }

    private static void bytes(byte[] got, byte[] want, String what) {
        check(Arrays.equals(got, want), what + ": " + Arrays.toString(got));
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
