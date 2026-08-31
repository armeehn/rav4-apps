package com.reveng.themestub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Serves the Riposte day palette + style, mimicking launcher ThemeContract v0.8. */
public class StubProvider extends ContentProvider {
    private static final String[] COLUMNS = {
        "theme_id", "theme_name", "night",
        "background", "surface", "surface_variant", "primary",
        "on_background", "on_surface", "on_surface_muted",
        "error", "accent2", "accent3",
        "corner_scale", "mono_type", "hard_edge",
    };

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[]{
            "builtin.riposte", "Riposte", 0,
            0xFFF6F1E7L, 0xFFF6F1E7L, 0xFFEAE4D6L, 0xFFD81150L,
            0xFF1D1A17L, 0xFF1D1A17L, 0xFF5C554CL,
            0xFFB3261EL, 0xFF12B795L, 0xFFFE9A0DL,
            0f, 1, 1,
        });
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/stub"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
