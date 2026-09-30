package com.ripostelabs.video;

import java.util.Random;

/**
 * Which video plays when one ends, under each loop mode. A wrong answer stops a playlist the
 * passenger wanted looped, or replays the same clip under shuffle.
 */
public final class LoopModeTest {

    public static void main(String[] args) {
        Random r = new Random(3);

        // The button walks all three modes; a stored name that no longer exists is ALL.
        expect(LoopMode.ONE, LoopMode.ALL.onButton());
        expect(LoopMode.SHUFFLE, LoopMode.ONE.onButton());
        expect(LoopMode.ALL, LoopMode.SHUFFLE.onButton());
        expect(LoopMode.ALL, LoopMode.parse("FOLDER"));
        expect(LoopMode.ALL, LoopMode.parse(null));
        expect(LoopMode.SHUFFLE, LoopMode.parse("SHUFFLE"));

        // The wheel keys, as in the music app: repeat walks all/one, shuffle toggles.
        expect(LoopMode.ONE, LoopMode.ALL.onRepeatKey());
        expect(LoopMode.ALL, LoopMode.ONE.onRepeatKey());
        expect(LoopMode.ONE, LoopMode.SHUFFLE.onRepeatKey());
        expect(LoopMode.SHUFFLE, LoopMode.ONE.onShuffleKey());
        expect(LoopMode.ALL, LoopMode.SHUFFLE.onShuffleKey());

        // ALL wraps at the end of the list, as stock did.
        expect(1, LoopMode.ALL.next(0, 3, r));
        expect(0, LoopMode.ALL.next(2, 3, r));

        // ONE replays the clip that ended.
        expect(2, LoopMode.ONE.next(2, 3, r));

        // SHUFFLE never picks the clip that just ended while there is a choice.
        for (int i = 0; i < 200; i++) {
            int n = LoopMode.SHUFFLE.next(1, 3, r);
            if (n == 1 || n < 0 || n > 2) {
                throw new AssertionError("shuffle picked " + n);
            }
        }
        expect(0, LoopMode.SHUFFLE.next(0, 1, r));

        System.out.println("LoopModeTest: ok");
    }

    private static void expect(Object want, Object got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
