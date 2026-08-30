package com.ripostelabs.weather;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import com.ripostelabs.design.Palette;

/**
 * Clean-room standalone Weather app. Pulls current conditions + daily/hourly
 * forecast from the free, no-API-key Open-Meteo service. All networking runs on a
 * background {@link Thread}; UI updates are posted back through a {@link Handler}.
 */
public class WeatherActivity extends Activity {

    private static final int REQ_LOC = 1;
    // Fallback location if device location is denied/unavailable: Toyota HQ, Plano TX.
    private static final double DEF_LAT = 33.0198, DEF_LON = -96.6989;
    private static final String DEF_NAME = "Plano, TX";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView cityView, statusView, iconView, tempView, conditionView;
    private TextView chipFeels, chipHum, chipWind;
    private LinearLayout dailyRow, hourlyRow;
    private EditText cityInput;

    // Design-system palette (mirrors res/values/colors.xml) for code-built cards.
    private static final String C_TEXT = "#FFF2F5FA";   // @color/text
    private static final String C_TEXT2 = "#FFAAB3C2";  // @color/text2
    private static final String C_TEXT3 = "#FF6B7484";  // @color/text3
    private static final String C_WHITE = "#FFFFFFFF";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        cityView = findViewById(R.id.city);
        statusView = findViewById(R.id.status);
        iconView = findViewById(R.id.icon);
        tempView = findViewById(R.id.temp);
        conditionView = findViewById(R.id.condition);
        chipFeels = findViewById(R.id.chipFeels);
        chipHum = findViewById(R.id.chipHum);
        chipWind = findViewById(R.id.chipWind);
        dailyRow = findViewById(R.id.daily);
        hourlyRow = findViewById(R.id.hourly);
        cityInput = findViewById(R.id.cityInput);

        ImageButton goBtn = findViewById(R.id.goBtn);
        ImageButton locBtn = findViewById(R.id.locBtn);

        goBtn.setOnClickListener(v -> submitCity());
        cityInput.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCity();
                return true;
            }
            return false;
        });
        locBtn.setOnClickListener(v -> useDeviceLocation());

        // First load: try device location, else default city.
        useDeviceLocation();
    }

    private void submitCity() {
        String name = cityInput.getText().toString().trim();
        if (name.isEmpty()) return;
        hideKeyboard();
        geocodeThenLoad(name);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        cityInput.clearFocus();
    }

    // ---------------------------------------------------------------- location

    private void useDeviceLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_LOC);
            return;
        }
        Location loc = lastKnownLocation();
        if (loc != null) {
            load(loc.getLatitude(), loc.getLongitude(), null);
        } else {
            // No fix yet -> fall back to a sensible default so the screen isn't empty.
            statusView.setText("Location unavailable — showing " + DEF_NAME);
            load(DEF_LAT, DEF_LON, DEF_NAME);
        }
    }

    private Location lastKnownLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
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

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_LOC && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            useDeviceLocation();
        } else {
            statusView.setText("Location denied — showing " + DEF_NAME);
            load(DEF_LAT, DEF_LON, DEF_NAME);
        }
    }

    // ---------------------------------------------------------------- geocoding

    /** Resolve a city name to coordinates via Open-Meteo geocoding, then load weather. */
    private void geocodeThenLoad(final String name) {
        statusView.setText("Searching \"" + name + "\"…");
        new Thread(() -> {
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                        + URLEncoder.encode(name, "UTF-8");
                JSONObject root = new JSONObject(httpGet(url));
                JSONArray results = root.optJSONArray("results");
                if (results == null || results.length() == 0) {
                    ui.post(() -> statusView.setText("No match for \"" + name + "\""));
                    return;
                }
                JSONObject c = results.getJSONObject(0);
                final double lat = c.getDouble("latitude");
                final double lon = c.getDouble("longitude");
                final String label = buildLabel(c);
                ui.post(() -> load(lat, lon, label));
            } catch (Exception e) {
                ui.post(() -> statusView.setText("Search failed: " + e.getMessage()));
            }
        }).start();
    }

    private String buildLabel(JSONObject c) {
        StringBuilder sb = new StringBuilder(c.optString("name", "?"));
        String admin = c.optString("admin1", "");
        String country = c.optString("country_code", c.optString("country", ""));
        if (!admin.isEmpty()) sb.append(", ").append(admin);
        if (!country.isEmpty()) sb.append(", ").append(country);
        return sb.toString();
    }

    // ---------------------------------------------------------------- weather load

    private void load(final double lat, final double lon, final String label) {
        if (label != null) cityView.setText(label);
        statusView.setText(R.string.loading);
        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                        + "wind_speed_10m,wind_direction_10m,weather_code"
                        + "&hourly=temperature_2m,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=7&timezone=auto";
                final JSONObject root = new JSONObject(httpGet(url));
                ui.post(() -> render(root, label));
            } catch (Exception e) {
                ui.post(() -> statusView.setText("Load failed: " + e.getMessage()));
            }
        }).start();
    }

    private void render(JSONObject root, String label) {
        try {
            JSONObject cur = root.getJSONObject("current");
            JSONObject curU = root.optJSONObject("current_units");
            String tUnit = curU != null ? curU.optString("temperature_2m", "°") : "°";
            String wUnit = curU != null ? curU.optString("wind_speed_10m", " km/h") : " km/h";

            int code = cur.optInt("weather_code", 0);
            double temp = cur.optDouble("temperature_2m", Double.NaN);
            double feels = cur.optDouble("apparent_temperature", Double.NaN);
            double hum = cur.optDouble("relative_humidity_2m", Double.NaN);
            double wind = cur.optDouble("wind_speed_10m", Double.NaN);
            double windDir = cur.optDouble("wind_direction_10m", Double.NaN);

            iconView.setText(wmoEmoji(code));
            tempView.setText(fmt(temp) + tUnit);
            conditionView.setText(wmoText(code));
            chipFeels.setText(fmt(feels) + tUnit);
            chipHum.setText(fmt(hum) + "%");
            chipWind.setText(fmt(wind) + wUnit.trim() + " " + compass(windDir));

            String tz = root.optString("timezone", "");
            statusView.setText("Updated " + nowLabel() + (tz.isEmpty() ? "" : "  •  " + tz));
            if (label == null && cityView.getText().toString().equals("—")) {
                cityView.setText("Current location");
            }

            renderDaily(root.optJSONObject("daily"), tUnit);
            renderHourly(root.optJSONObject("hourly"), tUnit);
        } catch (Exception e) {
            statusView.setText("Parse error: " + e.getMessage());
        }
    }

    private void renderDaily(JSONObject daily, String tUnit) {
        dailyRow.removeAllViews();
        if (daily == null) return;
        JSONArray times = daily.optJSONArray("time");
        JSONArray codes = daily.optJSONArray("weather_code");
        JSONArray maxs = daily.optJSONArray("temperature_2m_max");
        JSONArray mins = daily.optJSONArray("temperature_2m_min");
        if (times == null) return;
        int n = times.length();
        for (int i = 0; i < n; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(8));
            card.setLayoutParams(lp);
            card.setPadding(dp(8), dp(16), dp(8), dp(16));
            card.setBackgroundResource(R.drawable.bg_card);

            int code = codes != null ? codes.optInt(i, 0) : 0;
            TextView wd = addText(card, weekday(times.optString(i, "")), 12, C_TEXT3, true, 0);
            wd.setAllCaps(true);
            wd.setLetterSpacing(0.1f);
            addText(card, wmoEmoji(code), 34, C_WHITE, false, 10);
            String hi = maxs != null ? fmt(maxs.optDouble(i, Double.NaN)) : "--";
            String lo = mins != null ? fmt(mins.optDouble(i, Double.NaN)) : "--";
            addText(card, hi + tUnit, 22, C_TEXT, true, 12);
            addText(card, lo + tUnit, 16, C_TEXT2, false, 2);
            dailyRow.addView(card);
        }
    }

    private void renderHourly(JSONObject hourly, String tUnit) {
        hourlyRow.removeAllViews();
        if (hourly == null) return;
        JSONArray times = hourly.optJSONArray("time");
        JSONArray temps = hourly.optJSONArray("temperature_2m");
        JSONArray codes = hourly.optJSONArray("weather_code");
        if (times == null) return;
        int start = hourIndexFromNow(times);
        int shown = 0;
        for (int i = start; i < times.length() && shown < 24; i++, shown++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (shown > 0) lp.setMarginStart(dp(10));
            card.setLayoutParams(lp);
            card.setMinimumWidth(dp(72));
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackgroundResource(R.drawable.bg_card);
            int code = codes != null ? codes.optInt(i, 0) : 0;
            addText(card, hourLabel(times.optString(i, "")), 12, C_TEXT3, false, 0);
            addText(card, wmoEmoji(code), 26, C_WHITE, false, 8);
            String t = temps != null ? fmt(temps.optDouble(i, Double.NaN)) : "--";
            addText(card, t + tUnit, 17, C_TEXT, true, 6);
            hourlyRow.addView(card);
        }
    }

    private TextView addText(LinearLayout parent, String s, int sizeSp, String color,
                             boolean bold, int topMarginDp) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(Color.parseColor(color));
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        if (topMarginDp > 0) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(topMarginDp);
            tv.setLayoutParams(p);
        }
        parent.addView(tv);
        return tv;
    }

    // ---------------------------------------------------------------- helpers

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "riposte-weather/1.0");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "--";
        return String.valueOf(Math.round(v));
    }

    private static String compass(double deg) {
        if (Double.isNaN(deg)) return "";
        String[] dirs = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
        int idx = (int) Math.round(deg / 45.0) % 8;
        if (idx < 0) idx += 8;
        return dirs[idx];
    }

    private static String nowLabel() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    /** ISO date "2026-08-27" -> short weekday, "Today" for the current date. */
    private String weekday(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = in.parse(iso);
            String today = in.format(new Date());
            if (today.equals(iso)) return "Today";
            return new SimpleDateFormat("EEE", Locale.US).format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    /** ISO local time "2026-08-27T15:00" -> "15:00". */
    private String hourLabel(String iso) {
        int t = iso.indexOf('T');
        return t >= 0 && iso.length() >= t + 6 ? iso.substring(t + 1, t + 6) : iso;
    }

    /** Find the first hourly index at or after the current local hour. */
    private int hourIndexFromNow(JSONArray times) {
        try {
            Calendar c = Calendar.getInstance();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            String now = f.format(c.getTime());
            for (int i = 0; i < times.length(); i++) {
                if (times.optString(i, "").compareTo(now) >= 0) return i;
            }
        } catch (Exception ignore) { }
        return 0;
    }

    // WMO weather interpretation codes -> human text.
    private static String wmoText(int c) {
        switch (c) {
            case 0:  return "Clear sky";
            case 1:  return "Mainly clear";
            case 2:  return "Partly cloudy";
            case 3:  return "Overcast";
            case 45: return "Fog";
            case 48: return "Rime fog";
            case 51: return "Light drizzle";
            case 53: return "Drizzle";
            case 55: return "Dense drizzle";
            case 56: return "Freezing drizzle";
            case 57: return "Dense freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: return "Freezing rain";
            case 67: return "Heavy freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Light showers";
            case 81: return "Showers";
            case 82: return "Violent showers";
            case 85: return "Light snow showers";
            case 86: return "Heavy snow showers";
            case 95: return "Thunderstorm";
            case 96: return "Thunderstorm, hail";
            case 99: return "Thunderstorm, heavy hail";
            default: return "Code " + c;
        }
    }

    private static String wmoEmoji(int c) {
        switch (c) {
            case 0:  return "☀️";               // sun
            case 1:  return "🌤️";         // sun behind small cloud
            case 2:  return "⛅";                     // sun behind cloud
            case 3:  return "☁️";               // cloud
            case 45: case 48: return "🌫️"; // fog
            case 51: case 53: case 55:
            case 56: case 57: return "🌦️"; // sun behind rain cloud
            case 61: case 63: case 65:
            case 66: case 67: return "🌧️"; // rain cloud
            case 71: case 73: case 75:
            case 77: return "🌨️";         // snow cloud
            case 80: case 81: case 82: return "🌦️";
            case 85: case 86: return "🌨️";
            case 95: case 96: case 99: return "⛈️"; // thunder cloud
            default: return "🌡️";         // thermometer
        }
    }
}
