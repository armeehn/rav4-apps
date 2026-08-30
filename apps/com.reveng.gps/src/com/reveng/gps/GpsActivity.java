package com.reveng.gps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import com.reveng.design.Palette;

/**
 * Clean-room standalone GPS status dashboard. Everything is read straight off the
 * on-device {@link LocationManager}: live position/speed/heading via a
 * {@link LocationListener}, and per-satellite SNR via a {@link GnssStatus.Callback}
 * (API 24+). No networking, no AndroidX, no sharedUserId.
 */
public class GpsActivity extends Activity {

    private static final int REQ_LOC = 1;
    private static final float SNR_MAX = 50f;   // dB-Hz full-scale for the bars

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LocationManager lm;

    // Hero card
    private TextView statusView, speedView, conditionView, coordsView;
    private TextView chipAlt, chipAcc, chipSats;
    // Right panel
    private TextView satCountView;
    private LinearLayout snrChart, detailGrid;

    private Location lastFix;

    // Palette (mirrors res/values/colors.xml) for code-built views.
    private static final int C_TEXT   = 0xFFF2F5FA;
    private static final int C_TEXT2  = 0xFFAAB3C2;
    private static final int C_TEXT3  = 0xFF6B7484;
    private static final int C_ACCENT = 0xFF5B9DFF;
    private static final int C_SURF2  = 0xFF1E2431;
    private static final int C_STROKE = 0x22FFFFFF;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        statusView    = findViewById(R.id.status);
        speedView     = findViewById(R.id.speed);
        conditionView = findViewById(R.id.condition);
        coordsView    = findViewById(R.id.coords);
        chipAlt       = findViewById(R.id.chipAlt);
        chipAcc       = findViewById(R.id.chipAcc);
        chipSats      = findViewById(R.id.chipSats);
        satCountView  = findViewById(R.id.satCount);
        snrChart      = findViewById(R.id.snrChart);
        detailGrid    = findViewById(R.id.detailGrid);

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        ImageButton refreshBtn  = findViewById(R.id.refreshBtn);
        ImageButton settingsBtn = findViewById(R.id.settingsBtn);
        refreshBtn.setOnClickListener(v -> { restartUpdates(); Toast.makeText(this, "Restarting GPS…", Toast.LENGTH_SHORT).show(); });
        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));

        buildDetailGrid();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasPermission()) {
            requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, REQ_LOC);
            return;
        }
        startUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUpdates();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_LOC && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            startUpdates();
        } else {
            conditionView.setText("Permission denied");
            statusView.setText("Location permission required");
            coordsView.setText("Grant ACCESS_FINE_LOCATION to see position");
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- updates

    private boolean listening;

    private void startUpdates() {
        if (lm == null || listening) return;
        if (!hasPermission()) return;
        try {
            // Prompt if the GPS radio itself is off.
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                statusView.setText("GPS is off — tap settings");
                conditionView.setText("GPS disabled");
            }
            // Seed the UI with the freshest last-known fix so the screen isn't empty.
            Location seed = bestLastKnown();
            if (seed != null) renderLocation(seed);

            // Subscribe to every provider we can: GPS first, then NETWORK and FUSED.
            requestFrom(LocationManager.GPS_PROVIDER);
            requestFrom(LocationManager.NETWORK_PROVIDER);
            if (containsProvider("fused")) requestFrom("fused");

            lm.registerGnssStatusCallback(gnss, ui);
            listening = true;
        } catch (SecurityException e) {
            statusView.setText("Location permission required");
        }
    }

    private void requestFrom(String provider) {
        try {
            if (lm.getProvider(provider) != null) {
                lm.requestLocationUpdates(provider, 1000L, 0f, locListener, Looper.getMainLooper());
            }
        } catch (Exception ignore) { /* provider not present on this device */ }
    }

    private boolean containsProvider(String p) {
        List<String> all = lm.getAllProviders();
        return all != null && all.contains(p);
    }

    private void stopUpdates() {
        if (lm == null) return;
        try {
            lm.removeUpdates(locListener);
            lm.unregisterGnssStatusCallback(gnss);
        } catch (Exception ignore) { }
        listening = false;
    }

    private void restartUpdates() {
        stopUpdates();
        if (hasPermission()) startUpdates();
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
        @Override public void onLocationChanged(Location location) { renderLocation(location); }
        @Override public void onProviderEnabled(String provider) {
            statusView.setText(provider.toUpperCase(Locale.US) + " enabled");
        }
        @Override public void onProviderDisabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                statusView.setText("GPS is off — tap settings");
            }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    // ---------------------------------------------------------------- rendering

    private void renderLocation(Location loc) {
        if (loc == null) return;
        // Prefer the newest fix if two providers report almost together.
        if (lastFix != null && loc.getTime() < lastFix.getTime() - 2000) return;
        lastFix = loc;

        float speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
        speedView.setText(fmtSpeed(speedKmh));

        String cond;
        if (loc.hasBearing() && speedKmh > 1f) {
            cond = compass(loc.getBearing()) + "  " + Math.round(loc.getBearing()) + "°";
        } else {
            cond = loc.getProvider() != null
                    ? loc.getProvider().toUpperCase(Locale.US) + " fix"
                    : "Fix acquired";
        }
        conditionView.setText(cond);

        coordsView.setText(fmtLat(loc.getLatitude()) + "   " + fmtLon(loc.getLongitude()));

        chipAlt.setText(loc.hasAltitude() ? Math.round(loc.getAltitude()) + " m" : "-- m");
        chipAcc.setText(loc.hasAccuracy() ? "±" + Math.round(loc.getAccuracy()) + " m" : "±-- m");

        statusView.setText("Fix " + time(loc.getTime())
                + (loc.getProvider() != null ? " • " + loc.getProvider() : ""));

        updateDetail(loc);
    }

    // ---------------------------------------------------------------- satellites

    private final GnssStatus.Callback gnss = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus s) { renderSats(s); }
    };

    private static class Sat {
        String label; float snr; boolean used; int type;
    }

    private void renderSats(GnssStatus s) {
        int total = s.getSatelliteCount();
        int used = 0;
        List<Sat> sats = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            Sat sat = new Sat();
            sat.type  = s.getConstellationType(i);
            sat.label = constellationLetter(sat.type) + s.getSvid(i);
            sat.snr   = s.getCn0DbHz(i);
            sat.used  = s.usedInFix(i);
            if (sat.used) used++;
            sats.add(sat);
        }
        // Strongest signals on top.
        Collections.sort(sats, new Comparator<Sat>() {
            @Override public int compare(Sat a, Sat b) { return Float.compare(b.snr, a.snr); }
        });

        satCountView.setText(used + " used / " + total + " seen");
        chipSats.setText(used + "/" + total);

        snrChart.removeAllViews();
        if (sats.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Searching for satellites…");
            empty.setTextColor(C_TEXT3);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            snrChart.addView(empty);
            return;
        }
        for (Sat sat : sats) snrChart.addView(buildSnrRow(sat));
    }

    /** One satellite row: label | proportional SNR bar | dB-Hz value. */
    private View buildSnrRow(Sat sat) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(9);
        row.setLayoutParams(rlp);

        TextView label = new TextView(this);
        label.setText(sat.label);
        label.setTextColor(sat.used ? C_TEXT : C_TEXT3);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(android.graphics.Typeface.MONOSPACE);
        label.setWidth(dp(58));
        row.addView(label);

        // Bar track: fills weight; bar + spacer split it by SNR.
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, dp(12), 1f);
        track.setLayoutParams(tlp);

        float snr = Math.max(0f, Math.min(sat.snr, SNR_MAX));
        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(sat.used ? C_ACCENT : 0xFF3A4557);
        bar.setBackground(bg);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(12), Math.max(snr, 0.5f)));
        track.addView(bar);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, dp(12), Math.max(SNR_MAX - snr, 0.001f)));
        track.addView(spacer);
        row.addView(track);

        TextView val = new TextView(this);
        val.setText(sat.snr > 0 ? String.valueOf(Math.round(sat.snr)) : "--");
        val.setTextColor(sat.used ? C_TEXT : C_TEXT3);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        val.setGravity(Gravity.END);
        val.setWidth(dp(34));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.setMarginStart(dp(10));
        val.setLayoutParams(vlp);
        row.addView(val);
        return row;
    }

    // ---------------------------------------------------------------- detail cards

    private TextView dLat, dLon, dAlt, dBearing;

    private void buildDetailGrid() {
        detailGrid.removeAllViews();
        dLat     = addDetailCard("LATITUDE", "--");
        dLon     = addDetailCard("LONGITUDE", "--");
        dAlt     = addDetailCard("ALTITUDE", "--");
        dBearing = addDetailCard("BEARING", "--");
    }

    private TextView addDetailCard(String label, String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (detailGrid.getChildCount() > 0) lp.setMarginStart(dp(10));
        card.setLayoutParams(lp);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xFF161B24);
        bg.setStroke(dp(1), C_STROKE);
        card.setBackground(bg);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(C_TEXT3);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        lbl.setLetterSpacing(0.12f);
        lbl.setAllCaps(true);
        card.addView(lbl);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(C_TEXT);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(6);
        val.setLayoutParams(vlp);
        card.addView(val);

        detailGrid.addView(card);
        return val;
    }

    private void updateDetail(Location loc) {
        dLat.setText(String.format(Locale.US, "%.5f", loc.getLatitude()));
        dLon.setText(String.format(Locale.US, "%.5f", loc.getLongitude()));
        dAlt.setText(loc.hasAltitude() ? Math.round(loc.getAltitude()) + " m" : "--");
        dBearing.setText(loc.hasBearing()
                ? Math.round(loc.getBearing()) + "° " + compass(loc.getBearing()) : "--");
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String fmtSpeed(float kmh) {
        if (Float.isNaN(kmh) || kmh < 0) return "0";
        return String.valueOf(Math.round(kmh));
    }

    private static String fmtLat(double lat) {
        return String.format(Locale.US, "%.5f°%s", Math.abs(lat), lat >= 0 ? "N" : "S");
    }

    private static String fmtLon(double lon) {
        return String.format(Locale.US, "%.5f°%s", Math.abs(lon), lon >= 0 ? "E" : "W");
    }

    private static String time(long millis) {
        if (millis <= 0) return "--";
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private static String compass(double deg) {
        if (Double.isNaN(deg)) return "";
        String[] dirs = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
        int idx = (int) Math.round(deg / 45.0) % 8;
        if (idx < 0) idx += 8;
        return dirs[idx];
    }

    private static String constellationLetter(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS:     return "G";
            case GnssStatus.CONSTELLATION_GLONASS: return "R";
            case GnssStatus.CONSTELLATION_GALILEO: return "E";
            case GnssStatus.CONSTELLATION_BEIDOU:  return "C";
            case GnssStatus.CONSTELLATION_QZSS:    return "J";
            case GnssStatus.CONSTELLATION_SBAS:    return "S";
            case GnssStatus.CONSTELLATION_IRNSS:   return "I";
            default:                               return "U";
        }
    }
}
