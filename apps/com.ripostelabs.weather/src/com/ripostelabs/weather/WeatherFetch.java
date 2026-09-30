package com.ripostelabs.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * RAV4-197 — the two Open-Meteo calls, shared by the screen and the hourly job. Blocking: call
 * off the main thread.
 */
public final class WeatherFetch {

    private static final int TIMEOUT_MS = 10000;
    private static final int HTTP_OK = 200;

    private WeatherFetch() {
    }

    /** The forecast document, in [unit]. */
    public static JSONObject forecast(double lat, double lon, WeatherLogic.Unit unit) throws Exception {
        return new JSONObject(get(WeatherLogic.forecastUrl(lat, lon, unit)));
    }

    /** Current US AQI, or null when the air-quality API has nothing or cannot be reached. */
    public static Integer aqi(double lat, double lon) {
        try {
            JSONObject cur = new JSONObject(get(WeatherLogic.airUrl(lat, lon))).optJSONObject("current");
            if (cur == null || cur.isNull("us_aqi")) {
                return null;
            }
            return (int) Math.round(cur.getDouble("us_aqi"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Fold a forecast into the reading the launcher reads: now, and today's high and low. */
    public static WeatherLogic.Reading reading(JSONObject root, Integer aqi, String place,
            WeatherLogic.Unit unit, long nowMs) throws Exception {
        JSONObject cur = root.getJSONObject("current");
        JSONObject daily = root.optJSONObject("daily");
        Double high = first(daily, "temperature_2m_max");
        Double low = first(daily, "temperature_2m_min");
        return new WeatherLogic.Reading(cur.getDouble("temperature_2m"), high, low, unit,
                cur.optInt("weather_code", 0), aqi, place, nowMs);
    }

    private static Double first(JSONObject daily, String key) {
        JSONArray a = daily == null ? null : daily.optJSONArray(key);
        if (a == null || a.length() == 0 || a.isNull(0)) {
            return null;
        }
        return a.optDouble(0);
    }

    static String get(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "riposte-weather/1.0");
            int code = conn.getResponseCode();
            if (code != HTTP_OK) {
                throw new Exception("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
