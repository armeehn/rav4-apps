package com.ripostelabs.design;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * One runtime-permission gate for the suite (Track D, RAV4-66).
 *
 * <pre>
 *   activity.onCreate  ──▶ gate.request()          asks only for what is still missing
 *   system dialog      ──▶ gate.onResult(...)      granted → listener.onGranted()
 *                                                   denied  → grant view shown, listener.onDenied()
 *   grant view tap     ──▶ gate.request()          the same ask again
 * </pre>
 *
 * Sixteen apps typed this by hand, each with its own request code and its own reading of
 * the result array. The reading is the part that drifts, so it lives in two static methods
 * with no Android in them ({@link #missing}, {@link #allGranted}) and the harness tests those.
 * The rest is the Activity plumbing, kept thin on purpose: the gate owns the request code and
 * the grant view's visibility, the app keeps deciding what "granted" unlocks.
 */
public final class PermissionGate {

    /** How a permission is checked; the Activity's own {@code checkSelfPermission} in production. */
    public interface Checker {
        boolean granted(String permission);
    }

    public interface Listener {
        void onGranted();

        void onDenied();
    }

    /** One code for every gate in an app: an app with two gates wants two instances, not two codes. */
    public static final int REQUEST_CODE = 0x9A7E;

    private final Activity activity;
    private final String[] permissions;
    private final View grantView;
    private final Listener listener;
    private final Checker checker;

    private PermissionGate(Activity activity, String[] permissions, View grantView, Listener listener, Checker checker) {
        this.activity = activity;
        this.permissions = permissions;
        this.grantView = grantView;
        this.listener = listener;
        this.checker = checker;
    }

    /**
     * @param grantView shown while something is denied, hidden once everything is granted;
     *                  tapping it asks again. May be null for an app with no such control.
     */
    public static PermissionGate of(Activity activity, String[] permissions, View grantView, Listener listener) {
        Checker checker = p -> activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        PermissionGate gate = new PermissionGate(activity, permissions, grantView, listener, checker);
        if (grantView != null) {
            grantView.setOnClickListener(v -> gate.request());
        }
        return gate;
    }

    /** True when nothing is missing right now. */
    public boolean granted() {
        return missing(permissions, checker).length == 0;
    }

    /** Ask for what is missing; calls {@link Listener#onGranted()} at once when nothing is. */
    public void request() {
        String[] ask = missing(permissions, checker);
        if (ask.length == 0) {
            show(false);
            listener.onGranted();
            return;
        }
        activity.requestPermissions(ask, REQUEST_CODE);
    }

    /**
     * Route the Activity's {@code onRequestPermissionsResult} here.
     *
     * @return true when the result was this gate's; false leaves it to the caller.
     */
    public boolean onResult(int requestCode, String[] asked, int[] results) {
        if (requestCode != REQUEST_CODE) {
            return false;
        }
        // The result array answers only what was asked; anything asked earlier is re-checked.
        boolean ok = allGranted(results) && granted();
        show(!ok);
        if (ok) {
            listener.onGranted();
        } else {
            listener.onDenied();
        }
        return true;
    }

    private void show(boolean denied) {
        if (grantView != null) {
            grantView.setVisibility(denied ? View.VISIBLE : View.GONE);
        }
    }

    // ---- The two readings every app got slightly differently. No Android below this line. ----

    /** The subset of {@code permissions} the checker does not grant, in the given order. */
    public static String[] missing(String[] permissions, Checker checker) {
        List<String> out = new ArrayList<>();
        for (String p : permissions) {
            if (!checker.granted(p)) {
                out.add(p);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * A result array means "granted" only when it is non-empty and every entry is GRANTED. An
     * empty array is the system cancelling the dialog, which several apps read as granted.
     */
    public static boolean allGranted(int[] results) {
        if (results == null || results.length == 0) {
            return false;
        }
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
