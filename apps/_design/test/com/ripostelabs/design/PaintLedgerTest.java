package com.ripostelabs.design;

/**
 * The ledger behind Palette's deferred walk: a view is painted the first time it is seen and
 * never again, because the walk is not idempotent (corner radii are scaled in place).
 */
public final class PaintLedgerTest {

    private PaintLedgerTest() {}

    public static void main(String[] args) {
        PaintLedger ledger = new PaintLedger();
        Object a = new Object();
        Object b = new Object();

        check(ledger.firstVisit(a), "first visit paints");
        check(!ledger.firstVisit(a), "second visit skips");
        check(ledger.firstVisit(b), "another view is independent");
        check(!ledger.firstVisit(b), "and is remembered too");
        check(ledger.size() == 2, "two views remembered");

        // Identity, not equality: two equal-but-distinct objects are two views.
        Object c = "row";
        Object d = new String("row");
        check(ledger.firstVisit(c), "c is new");
        check(ledger.firstVisit(d), "d is a different object, so new too");

        check(!ledger.firstVisit(null), "null is never painted");

        // A collected view leaves the ledger at the next sweep, so a long-lived screen that
        // churns rows does not grow without bound.
        PaintLedger churn = new PaintLedger();
        for (int i = 0; i < 1000; i++) {
            churn.firstVisit(new Object());
        }
        System.gc();
        churn.sweep();
        check(churn.size() < 1000, "dead views were swept, size=" + churn.size());
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
