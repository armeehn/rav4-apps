package com.reveng.deviceinfo;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import com.reveng.design.Palette;

/**
 * Clean-room standalone Device Info screen. Reads device state through public,
 * permission-free {@code android.*} APIs and renders it into a two-column grid of
 * design-system section cards. Battery/memory refresh live on a {@link Handler};
 * the header button forces a full rebuild.
 */
public class DeviceInfoActivity extends Activity {

    private static final long REFRESH_MS = 3000L;

    // Design-system palette (mirrors res/values/colors.xml) for code-built views.
    private static final int C_TEXT   = Color.parseColor("#FFF2F5FA");
    private static final int C_TEXT2  = Color.parseColor("#FFAAB3C2");
    private static final int C_TEXT3  = Color.parseColor("#FF6B7484");
    private static final int C_ACCENT = Color.parseColor("#FF5B9DFF");
    private static final int C_TRACK  = Color.parseColor("#FF1E2431"); // surface2

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout colLeft, colRight;
    private TextView subtitle;

    // Live-updated value fields kept between rebuilds so the Handler can poke them.
    private TextView vUptime;
    private TextView vRamUsage, vRamAvail;
    private View barRam;
    private TextView vBatLevel, vBatStatus, vBatHealth, vBatTemp, vBatVolt, vBatTech;
    private View barBat;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateLive();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);
        colLeft = findViewById(R.id.colLeft);
        colRight = findViewById(R.id.colRight);
        subtitle = findViewById(R.id.subtitle);
        ImageButton refresh = findViewById(R.id.refreshBtn);
        refresh.setOnClickListener(v -> buildAll());
        buildAll();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.postDelayed(ticker, REFRESH_MS);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------------ build

    private void buildAll() {
        colLeft.removeAllViews();
        colRight.removeAllViews();
        // Reset live refs; cards below repopulate them.
        vUptime = null; vRamUsage = vRamAvail = null; barRam = null;
        vBatLevel = vBatStatus = vBatHealth = vBatTemp = vBatVolt = vBatTech = null;
        barBat = null;

        buildSystem(colLeft);
        buildMemory(colLeft);
        buildBattery(colLeft);

        buildCpu(colRight);
        buildStorage(colRight);
        buildDisplay(colRight);

        subtitle.setText("Updated " + timeNow());
    }

    private void buildSystem(LinearLayout col) {
        LinearLayout card = addCard(col, R.drawable.ic_info, "System");
        addRow(card, "Manufacturer", str(Build.MANUFACTURER));
        addRow(card, "Model", str(Build.MODEL));
        addRow(card, "Device", str(Build.DEVICE));
        addRow(card, "Android", Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")");
        addRow(card, "Security patch", secPatch());
        addRow(card, "Build ID", str(Build.ID));
        addRow(card, "Kernel", str(System.getProperty("os.version")));
        addRowWrap(card, "Fingerprint", str(Build.FINGERPRINT));
        vUptime = addRow(card, "Uptime", uptime());
    }

    private void buildCpu(LinearLayout col) {
        LinearLayout card = addCard(col, R.drawable.ic_speed, "CPU");
        addRow(card, "SoC / hardware", str(Build.HARDWARE));
        addRow(card, "Board", str(Build.BOARD));
        addRow(card, "Cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        addRow(card, "ABIs", abis());
        long[] mm = cpuFreqRange();
        if (mm != null) {
            addRow(card, "Freq range", fmtMHz(mm[0]) + " – " + fmtMHz(mm[1]));
        } else {
            addRow(card, "Freq range", "n/a");
        }
    }

    private void buildMemory(LinearLayout col) {
        LinearLayout card = addCard(col, R.drawable.ic_grid, "Memory");
        ActivityManager.MemoryInfo mi = memInfo();
        long total = mi.totalMem, avail = mi.availMem, used = total - avail;
        vRamUsage = addRow(card, "Used", bytes(used) + " / " + bytes(total));
        vRamAvail = addRow(card, "Available", bytes(avail));
        addRow(card, "Low-memory", mi.lowMemory ? "yes" : "no");
        barRam = addBar(card, total > 0 ? (float) used / total : 0f);
    }

    private void buildStorage(LinearLayout col) {
        LinearLayout card = addCard(col, R.drawable.ic_folder, "Storage");
        addStorage(card, "Internal", Environment.getDataDirectory().getPath());
        File ext = Environment.getExternalStorageDirectory();
        String extPath = ext != null ? ext.getPath() : null;
        String dataPath = Environment.getDataDirectory().getPath();
        if (extPath != null && !extPath.equals(dataPath)) {
            addStorage(card, "External", extPath);
        }
    }

    private void addStorage(LinearLayout card, String label, String path) {
        try {
            StatFs s = new StatFs(path);
            long total = s.getTotalBytes();
            long free = s.getAvailableBytes();
            long used = total - free;
            addRow(card, label + " used", bytes(used) + " / " + bytes(total));
            addRow(card, label + " free", bytes(free));
            addBar(card, total > 0 ? (float) used / total : 0f);
        } catch (Exception e) {
            addRow(card, label, "n/a");
        }
    }

    private void buildBattery(LinearLayout col) {
        LinearLayout card = addCard(col, R.drawable.ic_battery, "Battery");
        vBatLevel = addRow(card, "Level", "—");
        vBatStatus = addRow(card, "Status", "—");
        vBatHealth = addRow(card, "Health", "—");
        vBatTemp = addRow(card, "Temperature", "—");
        vBatVolt = addRow(card, "Voltage", "—");
        vBatTech = addRow(card, "Technology", "—");
        barBat = addBar(card, 0f);
        updateBattery();
    }

    private void buildDisplay(LinearLayout col) {
        LinearLayout card = addCard(col, R.drawable.ic_settings, "Display");
        DisplayMetrics dm = new DisplayMetrics();
        Display d = getWindowManager().getDefaultDisplay();
        d.getRealMetrics(dm);
        addRow(card, "Resolution", dm.widthPixels + " x " + dm.heightPixels + " px");
        addRow(card, "Density", dm.densityDpi + " dpi  (x" + trim(dm.density) + ")");
        addRow(card, "Exact DPI", trim(dm.xdpi) + " x " + trim(dm.ydpi));
        float rr;
        try {
            rr = d.getMode().getRefreshRate();
        } catch (Throwable t) {
            rr = d.getRefreshRate();
        }
        addRow(card, "Refresh rate", trim(rr) + " Hz");
    }

    // --------------------------------------------------------------- live tick

    private void updateLive() {
        if (vUptime != null) vUptime.setText(uptime());
        updateMemory();
        updateBattery();
    }

    private void updateMemory() {
        if (vRamUsage == null) return;
        ActivityManager.MemoryInfo mi = memInfo();
        long total = mi.totalMem, avail = mi.availMem, used = total - avail;
        vRamUsage.setText(bytes(used) + " / " + bytes(total));
        vRamAvail.setText(bytes(avail));
        setBar(barRam, total > 0 ? (float) used / total : 0f);
    }

    private void updateBattery() {
        if (vBatLevel == null) return;
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        float pct = scale > 0 && level >= 0 ? (level * 100f / scale) : Float.NaN;
        vBatLevel.setText(Float.isNaN(pct) ? "n/a" : Math.round(pct) + "%");
        vBatStatus.setText(statusText(b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)));
        vBatHealth.setText(healthText(b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)));
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        vBatTemp.setText(temp == Integer.MIN_VALUE ? "n/a" : trim(temp / 10f) + " °C");
        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        vBatVolt.setText(mv < 0 ? "n/a" : trim(mv / 1000f) + " V");
        String tech = b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        vBatTech.setText(str(tech));
        setBar(barBat, Float.isNaN(pct) ? 0f : pct / 100f);
    }

    // ----------------------------------------------------------- card builders

    private LinearLayout addCard(LinearLayout col, int iconRes, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(C_ACCENT);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(18), dp(18));
        ip.setMarginEnd(dp(9));
        icon.setLayoutParams(ip);
        head.addView(icon);
        TextView t = new TextView(this);
        t.setText(title);
        t.setAllCaps(true);
        t.setTextColor(C_TEXT3);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setLetterSpacing(0.14f);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        head.addView(t);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = dp(10);
        card.addView(head, hp);

        col.addView(card);
        return card;
    }

    /** One key/value row; returns the value TextView so callers can live-update it. */
    private TextView addRow(LinearLayout card, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(5);
        rp.bottomMargin = dp(5);

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(C_TEXT2);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(C_TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setGravity(Gravity.END);
        v.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        vp.setMarginStart(dp(10));
        row.addView(v, vp);

        card.addView(row, rp);
        return v;
    }

    /** Full-width key over value; for long strings like the fingerprint. */
    private void addRowWrap(LinearLayout card, String key, String value) {
        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(C_TEXT2);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        kp.topMargin = dp(5);
        card.addView(k, kp);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(C_TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Accent progress bar (track + fill Views); returns the fill for live sizing. */
    private View addBar(LinearLayout card, float fraction) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(C_TRACK);
        tg.setCornerRadius(dp(5));
        track.setBackground(tg);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(9));
        tp.topMargin = dp(10);
        card.addView(track, tp);

        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(C_ACCENT);
        fg.setCornerRadius(dp(5));
        fill.setBackground(fg);
        float f = clamp(fraction);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, f));
        View rest = new View(this);
        track.addView(rest, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - f));
        fill.setTag(track);
        return fill;
    }

    private void setBar(View fill, float fraction) {
        if (fill == null) return;
        Object tag = fill.getTag();
        if (!(tag instanceof LinearLayout)) return;
        LinearLayout track = (LinearLayout) tag;
        if (track.getChildCount() < 2) return;
        float f = clamp(fraction);
        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = f;
        ((LinearLayout.LayoutParams) track.getChildAt(1).getLayoutParams()).weight = 1f - f;
        track.requestLayout();
    }

    // ---------------------------------------------------------------- data src

    private ActivityManager.MemoryInfo memInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi;
    }

    /** Best-effort min/max CPU frequency in kHz from sysfs; null if unreadable. */
    private long[] cpuFreqRange() {
        long min = Long.MAX_VALUE, max = 0;
        boolean any = false;
        File base = new File("/sys/devices/system/cpu");
        File[] cpus = base.listFiles();
        if (cpus == null) return null;
        for (File cpu : cpus) {
            String n = cpu.getName();
            if (!n.matches("cpu[0-9]+")) continue;
            long lo = readKHz(new File(cpu, "cpufreq/cpuinfo_min_freq"));
            long hi = readKHz(new File(cpu, "cpufreq/cpuinfo_max_freq"));
            if (lo > 0) { min = Math.min(min, lo); any = true; }
            if (hi > 0) { max = Math.max(max, hi); any = true; }
        }
        if (!any) return null;
        if (min == Long.MAX_VALUE) min = 0;
        return new long[]{ min, max };
    }

    private long readKHz(File f) {
        BufferedReader r = null;
        try {
            if (!f.exists()) return -1;
            r = new BufferedReader(new FileReader(f));
            String line = r.readLine();
            return line == null ? -1 : Long.parseLong(line.trim());
        } catch (Exception e) {
            return -1;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignore) { }
        }
    }

    // ------------------------------------------------------------- formatting

    private static String abis() {
        try {
            String[] a = Build.SUPPORTED_ABIS;
            return (a == null || a.length == 0) ? "n/a" : android.text.TextUtils.join(", ", a);
        } catch (Throwable t) {
            return "n/a";
        }
    }

    private static String secPatch() {
        String p = Build.VERSION.SECURITY_PATCH;
        return (p == null || p.isEmpty()) ? "n/a" : p;
    }

    private static String uptime() {
        long ms = SystemClock.elapsedRealtime();
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        sb.append(String.format(Locale.US, "%02d:%02d:%02d", h, m, s));
        return sb.toString();
    }

    private static String fmtMHz(long kHz) {
        if (kHz <= 0) return "n/a";
        return String.format(Locale.US, "%.2f GHz", kHz / 1000000.0);
    }

    private static String bytes(long b) {
        if (b < 0) return "n/a";
        double v = b;
        String[] u = { "B", "KB", "MB", "GB", "TB" };
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, (v >= 100 || i == 0) ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    private static String statusText(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING:     return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:  return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL:         return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            default:                                          return "Unknown";
        }
    }

    private static String healthText(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD:            return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:        return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD:            return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:    return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Failure";
            case BatteryManager.BATTERY_HEALTH_COLD:            return "Cold";
            default:                                            return "Unknown";
        }
    }

    private static String trim(float v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String str(String s) {
        return (s == null || s.isEmpty()) ? "n/a" : s;
    }

    private static float clamp(float f) {
        if (Float.isNaN(f)) return 0f;
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    private static String timeNow() {
        return new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
