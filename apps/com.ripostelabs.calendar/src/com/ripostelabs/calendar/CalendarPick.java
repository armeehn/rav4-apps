package com.ripostelabs.calendar;

import java.util.List;

/**
 * Chooses the calendar a new event is written to. No Android here, so it runs under
 * scope/run-tests.sh.
 *
 * Order: writable and visible first, then synced before local, then the account's primary.
 * A synced calendar (e.g. Migadu through DAVx5) wins so the event reaches the server; the
 * local calendar is only the fallback for a unit with no account.
 */
final class CalendarPick {

    /** No usable calendar: the caller creates the local one. */
    static final long NONE = -1;

    /** CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR: the lowest level that may add events. */
    private static final int MIN_WRITE_ACCESS = 500;

    /** One row of CalendarContract.Calendars, reduced to what the choice needs. */
    static final class Candidate {
        final long id;
        final int access;
        final boolean visible;
        final boolean local;
        final boolean primary;

        Candidate(long id, int access, boolean visible, boolean local, boolean primary) {
            this.id = id;
            this.access = access;
            this.visible = visible;
            this.local = local;
            this.primary = primary;
        }
    }

    private CalendarPick() {
    }

    static long choose(List<Candidate> all) {
        Candidate best = null;
        for (Candidate c : all) {
            if (c.access < MIN_WRITE_ACCESS || !c.visible) {
                continue;
            }
            if (best == null || rank(c) > rank(best)) {
                best = c;
            }
        }
        return best == null ? NONE : best.id;
    }

    // Synced outranks primary: a primary local calendar must not beat a synced one.
    private static int rank(Candidate c) {
        return (c.local ? 0 : 2) + (c.primary ? 1 : 0);
    }
}
