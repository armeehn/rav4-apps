package com.ripostelabs.design;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Which views {@link Palette} has already painted.
 *
 * The palette walk is not idempotent: it scales a shape's corner radius in place and
 * re-draws strokes, so a view walked twice would be styled twice. The deferred pass runs
 * on every layout and revisits the whole tree, so it needs to know what it has seen.
 *
 * Keys are compared by identity and held weakly, so a view that leaves the screen leaves
 * the ledger with it. Not a WeakHashMap: that compares by equals(), and an identity ledger
 * must not merge two distinct objects that happen to be equal.
 */
final class PaintLedger {

    /** Dead references are swept once this many views have been added since the last sweep. */
    private static final int SWEEP_EVERY = 256;

    private final Map<Integer, List<WeakReference<Object>>> seen = new HashMap<>();

    private int sinceSweep;

    /** True exactly once per object; false for null and for anything seen before. */
    boolean firstVisit(Object view) {
        if (view == null) {
            return false;
        }

        List<WeakReference<Object>> bucket = seen.get(System.identityHashCode(view));
        if (bucket == null) {
            bucket = new ArrayList<>(1);
            seen.put(System.identityHashCode(view), bucket);
        }
        for (WeakReference<Object> ref : bucket) {
            if (ref.get() == view) {
                return false;
            }
        }

        bucket.add(new WeakReference<>(view));
        if (++sinceSweep >= SWEEP_EVERY) {
            sweep();
        }
        return true;
    }

    /** Live entries. Dead references still count until the next sweep. */
    int size() {
        int n = 0;
        for (List<WeakReference<Object>> bucket : seen.values()) {
            n += bucket.size();
        }
        return n;
    }

    /** Drop references whose view was collected, and the buckets that emptied. */
    void sweep() {
        sinceSweep = 0;
        Iterator<List<WeakReference<Object>>> buckets = seen.values().iterator();
        while (buckets.hasNext()) {
            List<WeakReference<Object>> bucket = buckets.next();
            bucket.removeIf(ref -> ref.get() == null);
            if (bucket.isEmpty()) {
                buckets.remove();
            }
        }
    }
}
