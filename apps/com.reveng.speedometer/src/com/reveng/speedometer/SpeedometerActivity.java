package com.reveng.speedometer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import com.reveng.design.Palette;

/**
 * Clean-room standalone GPS speedometer / HUD.
 *
 * Live speed comes straight off the on-device {@link LocationManager} GPS
 * provider ({@link Location#getSpeed()} m/s). It is shown two ways: a custom
 * {@link GaugeView} analog dial with an eased needle, and a big digital readout.
 * A unit toggle switches km/h <-> mph. Trip stats (distance via
 * {@link Location#distanceTo}, max / moving-average speed, heading and elapsed
 * time) accumulate across fixes and can be zeroed with Reset. GPS-off and
 * permission-denied are handled with an on-dial notice + a settings shortcut.
 *
 * No networking, no AndroidX, no sharedUserId.
 */
public class SpeedometerActivity extends Activity {

    private static final int REQ_LOC = 1;
    private static final float MS_TO_KMH = 3.6f;
    private static final float KMH_TO_MPH = 0.621371f;
    private static final float MOVING_THRESHOLD_KMH = 3f; // ignore GPS jitter at rest

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LocationManager lm;
    private boolean listening;
    private boolean metric = true; // true = km/h, false = mph

    private GaugeView gauge;
    private TextView bigSpeed, bigUnit, statusView;
    private TextView statDistance, statMax, statAvg, statHeading, statElapsed;
    private Button unitBtn, resetBtn;
    private View noticeBox;
    private TextView noticeTitle, noticeHint;
    private Button noticeBtn;

    // --- Trip accumulators (SI units internally) ---
    private Location lastFix;
    private double tripMeters;       // total distance
    private long movingMillis;       // wall time spent above the moving threshold
    private float maxSpeedKmh;       // peak instantaneous speed
    private long tripStartElapsed;   // SystemClock.elapsedRealtime() at trip start
    private boolean tripStarted;
    private float lastSpeedKmh;
    private float lastBearing = Float.NaN;
    private boolean hasBearing;

    private static final String[] CARD8 = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        gauge        = findViewById(R.id.gauge);
        bigSpeed     = findViewById(R.id.bigSpeed);
        bigUnit      = findViewById(R.id.bigUnit);
        statusView   = findViewById(R.id.status);
        statDistance = findViewById(R.id.statDistance);
        statMax      = findViewById(R.id.statMax);
        statAvg      = findViewById(R.id.statAvg);
        statHeading  = findViewById(R.id.statHeading);
        statElapsed  = findViewById(R.id.statElapsed);
        unitBtn      = findViewById(R.id.unitBtn);
        resetBtn     = findViewById(R.id.resetBtn);
        noticeBox    = findViewById(R.id.noticeBox);
        noticeTitle  = findViewById(R.id.noticeTitle);
        noticeHint   = findViewById(R.id.noticeHint);
        noticeBtn    = findViewById(R.id.noticeBtn);

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        applyUnitScale();

        unitBtn.setOnClickListener(v -> { metric = !metric; applyUnitScale(); redrawStats(); });
        resetBtn.setOnClickListener(v -> resetTrip());
        noticeBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));

        redrawStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasPermission()) {
            requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, REQ_LOC);
            showNotice(getString(R.string.perm_needed), getString(R.string.perm_hint), false);
            return;
        }
        startUpdates();
        ui.post(elapsedTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUpdates();
        ui.removeCallbacks(elapsedTick);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_LOC && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            startUpdates();
            ui.post(elapsedTick);
        } else {
            showNotice(getString(R.string.perm_needed), getString(R.string.perm_hint), false);
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- updates

    private void startUpdates() {
        if (lm == null || listening) return;
        if (!hasPermission()) return;
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                showNotice(getString(R.string.gps_off),
                        "Enable GPS location to measure your speed.", true);
                statusView.setText(R.string.gps_off);
            } else {
                hideNotice();
            }

            Location seed = bestLastKnown();
            if (seed != null) renderSpeed(seed);

            requestFrom(LocationManager.GPS_PROVIDER);
            requestFrom(LocationManager.NETWORK_PROVIDER);
            if (containsProvider("fused")) requestFrom("fused");
            listening = true;
        } catch (SecurityException e) {
            showNotice(getString(R.string.perm_needed), getString(R.string.perm_hint), false);
        }
    }

    private void requestFrom(String provider) {
        try {
            if (lm.getProvider(provider) != null) {
                lm.requestLocationUpdates(provider, 500L, 0f, locListener, Looper.getMainLooper());
            }
        } catch (Exception ignore) { /* provider absent on this device */ }
    }

    private boolean containsProvider(String p) {
        List<String> all = lm.getAllProviders();
        return all != null && all.contains(p);
    }

    private void stopUpdates() {
        if (lm == null) return;
        try { lm.removeUpdates(locListener); } catch (Exception ignore) { }
        listening = false;
    }

    private Location bestLastKnown() {
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best;
        } catch (SecurityException e) {
            return null;
        }
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { onFix(location); }
        @Override public void onProviderEnabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) hideNotice();
        }
        @Override public void onProviderDisabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                showNotice(getString(R.string.gps_off),
                        "Enable GPS location to measure your speed.", true);
            }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    // ---------------------------------------------------------------- fix handling

    private void onFix(Location loc) {
        if (loc == null) return;
        hideNotice();

        // Trip distance: sum straight-line hops between consecutive fixes.
        if (lastFix != null) {
            float d = loc.distanceTo(lastFix);
            long dt = loc.getTime() - lastFix.getTime();
            if (d >= 0 && d < 10000 && dt > 0 && dt < 60000) {
                float instKmh = loc.hasSpeed()
                        ? loc.getSpeed() * MS_TO_KMH
                        : (d / (dt / 1000f)) * MS_TO_KMH;
                tripMeters += d;
                if (instKmh > MOVING_THRESHOLD_KMH) movingMillis += dt;
            }
        }
        lastFix = loc;

        renderSpeed(loc);

        if (loc.hasBearing() && lastSpeedKmh > MOVING_THRESHOLD_KMH) {
            lastBearing = loc.getBearing();
            hasBearing = true;
        }

        if (!tripStarted) {
            tripStarted = true;
            tripStartElapsed = SystemClock.elapsedRealtime();
        }
        redrawStats();
    }

    private void renderSpeed(Location loc) {
        float kmh = loc.hasSpeed() ? loc.getSpeed() * MS_TO_KMH : 0f;
        if (Float.isNaN(kmh) || kmh < 0) kmh = 0f;
        lastSpeedKmh = kmh;
        if (kmh > maxSpeedKmh) maxSpeedKmh = kmh;

        float disp = metric ? kmh : kmh * KMH_TO_MPH;
        gauge.setSpeed(disp);
        bigSpeed.setText(String.valueOf(Math.round(disp)));

        statusView.setText(loc.getProvider() != null
                ? loc.getProvider().toUpperCase(Locale.US) + " fix" : "Fix acquired");
    }

    // ---------------------------------------------------------------- stats + units

    private void applyUnitScale() {
        if (metric) {
            gauge.setScale(180f, 20f);
            bigUnit.setText("km/h");
            unitBtn.setText("km/h");
        } else {
            gauge.setScale(120f, 20f);
            bigUnit.setText("mph");
            unitBtn.setText("mph");
        }
        // Re-render the needle/readout in the new unit immediately.
        float disp = metric ? lastSpeedKmh : lastSpeedKmh * KMH_TO_MPH;
        gauge.setSpeed(disp);
        bigSpeed.setText(String.valueOf(Math.round(disp)));
    }

    private void redrawStats() {
        String unit = metric ? "km/h" : "mph";

        // Distance
        if (metric) {
            statDistance.setText(String.format(Locale.US, "%.2f km", tripMeters / 1000.0));
        } else {
            statDistance.setText(String.format(Locale.US, "%.2f mi", tripMeters / 1609.344));
        }

        // Max speed
        float maxDisp = metric ? maxSpeedKmh : maxSpeedKmh * KMH_TO_MPH;
        statMax.setText(Math.round(maxDisp) + " " + unit);

        // Moving average = distance / moving time
        float avgKmh = movingMillis > 0
                ? (float) (tripMeters / (movingMillis / 1000.0)) * MS_TO_KMH : 0f;
        float avgDisp = metric ? avgKmh : avgKmh * KMH_TO_MPH;
        statAvg.setText(Math.round(avgDisp) + " " + unit);

        // Heading + cardinal
        if (hasBearing && !Float.isNaN(lastBearing)) {
            statHeading.setText(Math.round(lastBearing) + "° " + cardinal8(lastBearing));
        } else {
            statHeading.setText("—");
        }

        updateElapsed();
    }

    private void updateElapsed() {
        long ms = tripStarted ? SystemClock.elapsedRealtime() - tripStartElapsed : 0;
        long s = ms / 1000;
        statElapsed.setText(String.format(Locale.US, "%02d:%02d:%02d",
                s / 3600, (s % 3600) / 60, s % 60));
    }

    private final Runnable elapsedTick = new Runnable() {
        @Override public void run() {
            updateElapsed();
            ui.postDelayed(this, 1000L);
        }
    };

    private void resetTrip() {
        lastFix = null;
        tripMeters = 0;
        movingMillis = 0;
        maxSpeedKmh = 0;
        tripStarted = false;
        tripStartElapsed = 0;
        hasBearing = false;
        lastBearing = Float.NaN;
        redrawStats();
    }

    private static String cardinal8(float deg) {
        int idx = Math.round(deg / 45f) % 8;
        if (idx < 0) idx += 8;
        return CARD8[idx];
    }

    // ---------------------------------------------------------------- notice overlay

    private void showNotice(String title, String hint, boolean settings) {
        noticeTitle.setText(title);
        noticeHint.setText(hint);
        noticeBtn.setVisibility(View.VISIBLE); // settings shortcut helps in both cases
        noticeBox.setVisibility(View.VISIBLE);
    }

    private void hideNotice() {
        if (noticeBox.getVisibility() != View.GONE) noticeBox.setVisibility(View.GONE);
    }
}
