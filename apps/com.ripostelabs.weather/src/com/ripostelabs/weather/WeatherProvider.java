package com.ripostelabs.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * RAV4-197 — the last reading, one row, read-only, for the launcher's home card
 * (content://com.ripostelabs.weather.current/current, columns {@link WeatherLogic#COLUMNS}).
 * Empty before the first fetch. The launcher judges staleness from the "updated" column.
 *
 * Its onCreate also schedules the hourly {@link RefreshJob}: the provider starts with the process
 * whenever the launcher asks, so the refresh runs even if nobody opens the app after a reinstall.
 */
public final class WeatherProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        RefreshJob.schedule(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(WeatherLogic.COLUMNS);
        WeatherLogic.Reading r = new WeatherStore(getContext()).reading();
        if (r != null) {
            c.addRow(r.row());
        }
        c.setNotificationUri(getContext().getContentResolver(), WeatherStore.URI);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.ripostelabs.weather.current";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
