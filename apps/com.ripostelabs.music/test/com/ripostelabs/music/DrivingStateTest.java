package com.ripostelabs.music;

/**
 * The launcher's parked-only rule, copied for the search keyboard: MOVING at or above 8 km/h,
 * PARKED at or below 3, the last verdict inside the band, and UNKNOWN never locks.
 */
public final class DrivingStateTest {

    public static void main(String[] args) {
        DrivingState.Motion u = DrivingState.Motion.UNKNOWN;
        DrivingState.Motion p = DrivingState.Motion.PARKED;
        DrivingState.Motion m = DrivingState.Motion.MOVING;

        expect(m, DrivingState.next(u, 8f));
        expect(p, DrivingState.next(u, 0f));
        expect(m, DrivingState.next(p, 30f));
        expect(p, DrivingState.next(m, 3f));

        // Inside the band the verdict stands, so walking pace does not flap the keyboard.
        expect(p, DrivingState.next(p, 5f));
        expect(m, DrivingState.next(m, 5f));
        // A first fix inside the band errs towards moving, as the launcher does.
        expect(m, DrivingState.next(u, 5f));

        // No fix is no reading: the verdict stands.
        expect(m, DrivingState.next(m, Float.NaN));
        expect(u, DrivingState.next(u, Float.NaN));

        // Only MOVING locks the keyboard; no GPS fails open.
        expect(true, DrivingState.keyboardAllowed(u));
        expect(true, DrivingState.keyboardAllowed(p));
        expect(false, DrivingState.keyboardAllowed(m));

        System.out.println("DrivingStateTest: ok");
    }

    private static void expect(Object want, Object got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
