package com.ripostelabs.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * RAV4-197 — what survives between fetches: the last reading, the place it was for and the
 * unit. The activity writes it on every load; {@link RefreshJob} re-fetches for the same place;
 * {@link WeatherProvider} serves the reading to the launcher.
 */
public final class WeatherStore {

    public static final String AUTHORITY = "com.ripostelabs.weather.current";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/current");

    private static final String PREFS = "weather";
    private static final String K_READING = "reading";
    private static final String K_LAT = "lat";
    private static final String K_LON = "lon";
    private static final String K_PLACE = "place";
    private static final String K_UNIT = "unit";

    private final Context context;
    private final SharedPreferences prefs;

    public WeatherStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public WeatherLogic.Unit unit() {
        return WeatherLogic.Unit.parse(prefs.getString(K_UNIT, null));
    }

    public void setUnit(WeatherLogic.Unit unit) {
        prefs.edit().putString(K_UNIT, unit.name()).apply();
    }

    /** The last reading, or null before the first fetch. */
    public WeatherLogic.Reading reading() {
        return WeatherLogic.Reading.decode(prefs.getString(K_READING, null));
    }

    /** Keep the reading and the place it is for, and tell the launcher's card. */
    public void save(WeatherLogic.Reading r, double lat, double lon) {
        prefs.edit()
                .putString(K_READING, r.encode())
                .putString(K_LAT, Double.toString(lat))
                .putString(K_LON, Double.toString(lon))
                .putString(K_PLACE, r.place)
                .commit();
        context.getContentResolver().notifyChange(URI, null);
    }

    /** {lat, lon} of the last load, or null when there has been none. */
    public double[] place() {
        String lat = prefs.getString(K_LAT, null);
        String lon = prefs.getString(K_LON, null);
        if (lat == null || lon == null) {
            return null;
        }
        return new double[] {Double.parseDouble(lat), Double.parseDouble(lon)};
    }

    public String placeName() {
        return prefs.getString(K_PLACE, "");
    }
}
