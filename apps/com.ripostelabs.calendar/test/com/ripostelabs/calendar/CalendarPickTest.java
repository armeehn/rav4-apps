package com.ripostelabs.calendar;

import java.util.ArrayList;
import java.util.List;

/**
 * Which calendar a new event goes into. A wrong answer is either a crash-free event that never
 * shows up (hidden or read-only calendar) or a second local calendar beside a synced one.
 */
public final class CalendarPickTest {

    private static final int READ = 200;         // CAL_ACCESS_READ
    private static final int CONTRIBUTOR = 500;  // CAL_ACCESS_CONTRIBUTOR
    private static final int OWNER = 700;        // CAL_ACCESS_OWNER

    public static void main(String[] args) {
        // No calendar at all (a fresh head unit): the caller must create a local one.
        expect(CalendarPick.NONE, CalendarPick.choose(new ArrayList<>()));

        // Read-only or hidden calendars are not a place to write.
        expect(CalendarPick.NONE, CalendarPick.choose(list(
                new CalendarPick.Candidate(1, READ, true, false, false),
                new CalendarPick.Candidate(2, OWNER, false, false, false))));

        // A synced calendar (Migadu via DAVx5) beats the local fallback.
        expect(3, CalendarPick.choose(list(
                new CalendarPick.Candidate(4, OWNER, true, true, false),
                new CalendarPick.Candidate(3, CONTRIBUTOR, true, false, false))));

        // Among synced calendars the account's primary one wins.
        expect(6, CalendarPick.choose(list(
                new CalendarPick.Candidate(5, OWNER, true, false, false),
                new CalendarPick.Candidate(6, OWNER, true, false, true))));

        // Only the local one exists: reuse it rather than create another.
        expect(7, CalendarPick.choose(list(
                new CalendarPick.Candidate(7, OWNER, true, true, false))));

        System.out.println("CalendarPickTest: ok");
    }

    private static List<CalendarPick.Candidate> list(CalendarPick.Candidate... c) {
        List<CalendarPick.Candidate> out = new ArrayList<>();
        for (CalendarPick.Candidate x : c) {
            out.add(x);
        }
        return out;
    }

    private static void expect(long want, long got) {
        if (want != got) {
            throw new AssertionError("expected calendar " + want + ", got " + got);
        }
    }
}
