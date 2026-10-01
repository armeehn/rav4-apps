package com.ripostelabs.radio;

/**
 * RAV4-275: tries a lost ITuner binding again until the launcher answers.
 *
 * When the launcher dies (a crash, a force stop, an update), Android drops the binding and
 * never restores a dead one by itself. Radio then sat on "tuner gateway unavailable" until
 * Radio restarted. Each loss schedules one attempt; a failed attempt waits twice as long,
 * up to {@link #MAX_MS}:
 *
 *   lost ─ 0.5 s ─ bind? no ─ 1 s ─ bind? no ─ 2 s ─ … ─ 30 s ─ 30 s …
 *                    └─ yes: wait for connected(), which resets the wait to 0.5 s
 *
 * No Android here, so the schedule is testable; {@link LauncherTuner} supplies the clock
 * (the main Handler) and the attempt (bindService).
 */
final class Rebind {

    static final long FIRST_MS = 500;
    static final long MAX_MS = 30_000;

    interface Scheduler { void after(long ms, Runnable task); }

    /** One bind attempt; false when the system refused it outright. */
    interface Attempt { boolean bind(); }

    private final Scheduler scheduler;
    private final Attempt attempt;
    private long delayMs = FIRST_MS;
    private boolean pending;
    private boolean stopped;
    /** Bumped by connected(): a try queued before it is stale and does nothing. */
    private int generation;

    Rebind(Scheduler scheduler, Attempt attempt) {
        this.scheduler = scheduler;
        this.attempt = attempt;
    }

    /** The binding died or was refused: try again later, unless one try is already queued. */
    void lost() {
        if (stopped || pending) {
            return;
        }

        schedule();
    }

    /** The launcher answered: the next loss starts with a quick retry again. */
    void connected() {
        delayMs = FIRST_MS;
        pending = false;
        generation++;
    }

    /** The screen bound on purpose: losses count again. */
    void start() {
        stopped = false;
    }

    /** The screen unbound on purpose: a queued try does nothing, and losses are ignored. */
    void stop() {
        stopped = true;
    }

    private void schedule() {
        pending = true;
        long wait = delayMs;
        delayMs = Math.min(delayMs * 2, MAX_MS);
        int queuedIn = generation;
        scheduler.after(wait, () -> fire(queuedIn));
    }

    private void fire(int queuedIn) {
        if (queuedIn != generation) {
            return;
        }

        pending = false;
        if (stopped) {
            return;
        }

        if (!attempt.bind()) {
            schedule();
        }
    }
}
