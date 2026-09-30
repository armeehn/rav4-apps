package com.ripostelabs.music;

import java.util.Random;

/**
 * Which track plays next under each loop mode, and how the mode button and the wheel's repeat
 * (MCU key 29) and shuffle (MCU key 30) keys step through the modes. A wrong answer loops the
 * wrong set, or leaves a driver unable to get back out of shuffle from the wheel.
 */
public final class PlayOrderTest {

    public static void main(String[] args) {
        cycles();
        allAndOne();
        folder();
        shuffle();
        System.out.println("PlayOrderTest: ok");
    }

    private static void cycles() {
        // The on-screen button walks every mode.
        expect(PlayOrder.Mode.ONE, PlayOrder.Mode.ALL.onButton());
        expect(PlayOrder.Mode.FOLDER, PlayOrder.Mode.ONE.onButton());
        expect(PlayOrder.Mode.SHUFFLE, PlayOrder.Mode.FOLDER.onButton());
        expect(PlayOrder.Mode.ALL, PlayOrder.Mode.SHUFFLE.onButton());

        // The repeat key walks the repeat modes only; from shuffle it goes to repeat-one.
        expect(PlayOrder.Mode.ONE, PlayOrder.Mode.ALL.onRepeatKey());
        expect(PlayOrder.Mode.FOLDER, PlayOrder.Mode.ONE.onRepeatKey());
        expect(PlayOrder.Mode.ALL, PlayOrder.Mode.FOLDER.onRepeatKey());
        expect(PlayOrder.Mode.ONE, PlayOrder.Mode.SHUFFLE.onRepeatKey());

        // The shuffle key toggles, so the same key gets the driver back out.
        expect(PlayOrder.Mode.SHUFFLE, PlayOrder.Mode.ALL.onShuffleKey());
        expect(PlayOrder.Mode.SHUFFLE, PlayOrder.Mode.FOLDER.onShuffleKey());
        expect(PlayOrder.Mode.ALL, PlayOrder.Mode.SHUFFLE.onShuffleKey());

        // A stored name that no longer exists falls back to ALL.
        expect(PlayOrder.Mode.ALL, PlayOrder.Mode.parse("bogus"));
        expect(PlayOrder.Mode.FOLDER, PlayOrder.Mode.parse("FOLDER"));
        expect(PlayOrder.Mode.ALL, PlayOrder.Mode.parse(null));
    }

    private static void allAndOne() {
        String[] dirs = {"/a", "/a", "/b"};
        Random r = new Random(1);

        expect(1, PlayOrder.next(PlayOrder.Mode.ALL, 0, dirs, PlayOrder.Cause.ENDED, r));
        expect(0, PlayOrder.next(PlayOrder.Mode.ALL, 2, dirs, PlayOrder.Cause.ENDED, r));
        expect(2, PlayOrder.previous(PlayOrder.Mode.ALL, 0, dirs));

        // Repeat-one replays when the track ends, but the next key still moves on.
        expect(1, PlayOrder.next(PlayOrder.Mode.ONE, 1, dirs, PlayOrder.Cause.ENDED, r));
        expect(2, PlayOrder.next(PlayOrder.Mode.ONE, 1, dirs, PlayOrder.Cause.SKIP, r));

        // Nothing loaded, or no current track yet.
        expect(PlayOrder.NONE, PlayOrder.next(PlayOrder.Mode.ALL, 0, new String[0], PlayOrder.Cause.SKIP, r));
        expect(0, PlayOrder.next(PlayOrder.Mode.FOLDER, -1, dirs, PlayOrder.Cause.SKIP, r));
    }

    private static void folder() {
        // Rows are sorted by title, so one folder's tracks are interleaved with another's.
        String[] dirs = {"/usb/rock", "/usb/jazz", "/usb/rock", "/usb/jazz", "/usb/pop"};
        Random r = new Random(1);

        expect(2, PlayOrder.next(PlayOrder.Mode.FOLDER, 0, dirs, PlayOrder.Cause.ENDED, r));
        expect(0, PlayOrder.next(PlayOrder.Mode.FOLDER, 2, dirs, PlayOrder.Cause.ENDED, r));
        expect(3, PlayOrder.next(PlayOrder.Mode.FOLDER, 1, dirs, PlayOrder.Cause.SKIP, r));
        expect(1, PlayOrder.previous(PlayOrder.Mode.FOLDER, 3, dirs));
        expect(3, PlayOrder.previous(PlayOrder.Mode.FOLDER, 1, dirs));

        // A folder of one loops that one track.
        expect(4, PlayOrder.next(PlayOrder.Mode.FOLDER, 4, dirs, PlayOrder.Cause.ENDED, r));

        // An unknown folder (null path) behaves like ALL.
        String[] odd = {null, "/x", null};
        expect(1, PlayOrder.next(PlayOrder.Mode.FOLDER, 0, odd, PlayOrder.Cause.ENDED, r));
    }

    private static void shuffle() {
        String[] dirs = {"/a", "/a", "/a", "/a"};
        Random r = new Random(7);

        // Never the same track twice in a row while there is a choice.
        for (int i = 0; i < 200; i++) {
            int n = PlayOrder.next(PlayOrder.Mode.SHUFFLE, 2, dirs, PlayOrder.Cause.ENDED, r);
            if (n == 2 || n < 0 || n >= dirs.length) {
                throw new AssertionError("shuffle picked " + n);
            }
        }

        // A library of one just replays it.
        expect(0, PlayOrder.next(PlayOrder.Mode.SHUFFLE, 0, new String[]{"/a"}, PlayOrder.Cause.ENDED, r));
    }

    private static void expect(Object want, Object got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
