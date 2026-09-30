package com.ripostelabs.weather;

import java.util.Locale;

/**
 * RAV4-197 — the Android-free half of the weather app: units, URLs, AQI bands and the cached
 * reading the launcher's home card reads through {@link WeatherProvider}.
 *
 * <pre>
 *   WeatherActivity ──fetch──┐
 *   RefreshJob (hourly) ─────┴─▶ Reading ─encode─▶ prefs ─▶ WeatherProvider ─▶ launcher card
 * </pre>
 */
public final class WeatherLogic {

    private WeatherLogic() {
    }

    /** The provider's columns, in row order. The launcher's WeatherFeed reads them by name. */
    public static final String[] COLUMNS = {
        "temp", "high", "low", "unit", "code", "aqi", "place", "updated",
    };

    /** Temperature unit. Open-Meteo converts server-side, so a reading is stored as fetched. */
    public enum Unit {
        C, F;

        /** A stored name, or Celsius when there is none (the car is in Canada). */
        public static Unit parse(String s) {
            return "F".equals(s) ? F : C;
        }

        public Unit toggle() {
            return this == C ? F : C;
        }

        public String symbol() {
            return this == C ? "°C" : "°F";
        }
    }

    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    private static final String AIR = "https://air-quality-api.open-meteo.com/v1/air-quality";

    public static String forecastUrl(double lat, double lon, Unit unit) {
        String url = FORECAST + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                + "wind_speed_10m,wind_direction_10m,weather_code"
                + "&hourly=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&forecast_days=7&timezone=auto";
        if (unit == Unit.F) {
            url += "&temperature_unit=fahrenheit";
        }
        return url;
    }

    public static String airUrl(double lat, double lon) {
        return AIR + "?latitude=" + lat + "&longitude=" + lon + "&current=us_aqi&timezone=auto";
    }

    // EPA US AQI band tops.
    private static final int AQI_GOOD = 50;
    private static final int AQI_MODERATE = 100;
    private static final int AQI_SENSITIVE = 150;
    private static final int AQI_UNHEALTHY = 200;
    private static final int AQI_VERY_UNHEALTHY = 300;

    /** The EPA band name for a US AQI value. */
    public static String aqiBand(int aqi) {
        if (aqi <= AQI_GOOD) {
            return "Good";
        }
        if (aqi <= AQI_MODERATE) {
            return "Moderate";
        }
        if (aqi <= AQI_SENSITIVE) {
            return "Unhealthy for some";
        }
        if (aqi <= AQI_UNHEALTHY) {
            return "Unhealthy";
        }
        if (aqi <= AQI_VERY_UNHEALTHY) {
            return "Very unhealthy";
        }
        return "Hazardous";
    }

    /** One fetch, as cached and served. Temperatures are in {@link #unit}. */
    public static final class Reading {
        public final double temp;
        public final Double high;
        public final Double low;
        public final Unit unit;
        public final int code;
        public final Integer aqi;
        public final String place;
        public final long updatedMs;

        public Reading(double temp, Double high, Double low, Unit unit, int code, Integer aqi,
                String place, long updatedMs) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.unit = unit;
            this.code = code;
            this.aqi = aqi;
            this.place = place == null ? "" : place;
            this.updatedMs = updatedMs;
        }

        /** The provider row, in {@link #COLUMNS} order. */
        public Object[] row() {
            return new Object[] {temp, high, low, unit.name(), code, aqi, place, updatedMs};
        }

        private static final String SEP = "\t";
        private static final int FIELDS = 8;

        /** One line for SharedPreferences. The place goes last, tabs in it flattened. */
        public String encode() {
            return String.join(SEP,
                    Double.toString(temp), str(high), str(low), unit.name(), Integer.toString(code),
                    str(aqi), Long.toString(updatedMs), place.replace(SEP, " "));
        }

        /** The inverse of {@link #encode}; null for a missing or damaged line. */
        public static Reading decode(String line) {
            if (line == null) {
                return null;
            }
            String[] f = line.split(SEP, -1);
            if (f.length != FIELDS) {
                return null;
            }
            try {
                return new Reading(Double.parseDouble(f[0]), dbl(f[1]), dbl(f[2]), Unit.parse(f[3]),
                        Integer.parseInt(f[4]), num(f[5]), f[7], Long.parseLong(f[6]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String str(Object o) {
            return o == null ? "" : o.toString();
        }

        private static Double dbl(String s) {
            return s.isEmpty() ? null : Double.parseDouble(s);
        }

        private static Integer num(String s) {
            return s.isEmpty() ? null : Integer.parseInt(s);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.1f%s code %d aqi %s", temp, unit.symbol(), code, aqi);
        }
    }
}
