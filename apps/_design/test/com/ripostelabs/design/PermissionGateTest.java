package com.ripostelabs.design;

import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The two readings behind every permission gate in the suite: what is still missing, and
 * what a result array means. Both were re-typed per app; the empty-array case is where they
 * disagreed (a cancelled dialog answers with nothing, which is not a grant).
 */
public final class PermissionGateTest {

    private PermissionGateTest() {}

    private static final int OK = PackageManager.PERMISSION_GRANTED;
    private static final int NO = PackageManager.PERMISSION_DENIED;

    public static void main(String[] args) {
        Set<String> have = new HashSet<>(Arrays.asList("a", "c"));
        PermissionGate.Checker checker = have::contains;

        check(Arrays.equals(PermissionGate.missing(new String[]{"a", "b", "c", "d"}, checker), new String[]{"b", "d"}),
                "missing keeps the asked order and drops what is granted");
        check(PermissionGate.missing(new String[]{"a", "c"}, checker).length == 0, "nothing missing when all granted");
        check(PermissionGate.missing(new String[]{}, checker).length == 0, "nothing asked, nothing missing");

        check(PermissionGate.allGranted(new int[]{OK}), "one grant is a grant");
        check(PermissionGate.allGranted(new int[]{OK, OK}), "all grants is a grant");
        check(!PermissionGate.allGranted(new int[]{OK, NO}), "one denial is a denial");
        check(!PermissionGate.allGranted(new int[]{}), "an empty result (cancelled dialog) is not a grant");
        check(!PermissionGate.allGranted(null), "no result is not a grant");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
