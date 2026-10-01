package com.ripostelabs.radio;

import java.util.ArrayList;
import java.util.List;

/**
 * RAV4-275: after the launcher restarted, Radio stayed on "tuner gateway unavailable" until
 * Radio itself restarted. A lost ITuner binding must be tried again, sooner first and then
 * further apart, until the launcher answers.
 */
public final class RebindTest {

    /** Holds scheduled attempts so the test fires them by hand, like a main-thread queue. */
    private static final class Queue implements Rebind.Scheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        @Override public void after(long ms, Runnable task) {
            delays.add(ms);
            tasks.add(task);
        }

        void fireNext() { tasks.remove(0).run(); }
    }

    public static void main(String[] args) {
        lostBindingIsTriedAgain();
        failedAttemptsBackOffToTheCap();
        connectResetsTheBackoff();
        stoppedRebindDoesNothing();
        System.out.println("RebindTest OK");
    }

    private static void lostBindingIsTriedAgain() {
        Queue q = new Queue();
        int[] attempts = {0};
        Rebind r = new Rebind(q, () -> { attempts[0]++; return true; });

        r.lost();
        check(q.tasks.size() == 1, "a lost binding schedules one attempt");
        q.fireNext();
        check(attempts[0] == 1, "the attempt binds again");
    }

    private static void failedAttemptsBackOffToTheCap() {
        Queue q = new Queue();
        Rebind r = new Rebind(q, () -> false);

        r.lost();
        for (int i = 0; i < 10; i++) q.fireNext();
        check(q.delays.get(0) == Rebind.FIRST_MS, "first retry is quick");
        check(q.delays.get(1) == Rebind.FIRST_MS * 2, "each failure doubles the wait");
        check(q.delays.get(q.delays.size() - 1) == Rebind.MAX_MS, "the wait stops at the cap");
    }

    private static void connectResetsTheBackoff() {
        Queue q = new Queue();
        Rebind r = new Rebind(q, () -> false);

        r.lost();
        q.fireNext();
        q.fireNext();
        r.connected();
        q.tasks.clear();
        q.delays.clear();
        r.lost();
        check(q.delays.get(0) == Rebind.FIRST_MS, "a fresh loss starts quick again");
    }

    private static void stoppedRebindDoesNothing() {
        Queue q = new Queue();
        int[] attempts = {0};
        Rebind r = new Rebind(q, () -> { attempts[0]++; return true; });

        r.lost();
        r.stop();
        q.fireNext();
        r.lost();
        check(attempts[0] == 0, "no attempt after the screen unbound");
        check(q.tasks.isEmpty(), "and nothing more is scheduled");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
