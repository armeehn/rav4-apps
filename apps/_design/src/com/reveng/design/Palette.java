package com.reveng.design;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * v0.5 — paints this app in the launcher's colours.
 *
 * The launcher (com.reveng.carlauncher) publishes the palette it is drawing right now on a
 * read-only ContentProvider. This reads it and maps it onto the design system's role names, so a
 * call site changes from
 *
 * <pre>getColor(R.color.accent)</pre>
 *
 * to
 *
 * <pre>Palette.color(this, R.color.accent)</pre>
 *
 * and nothing else moves.
 *
 * <h3>The contract that makes adoption safe</h3>
 *
 * With no launcher installed, an older launcher, or any failure at all, {@link #color} returns
 * exactly {@code context.getColor(colorRes)} — the value the call site used before. So switching
 * a call site over cannot change how the app looks on a unit without the launcher, and a bad
 * read degrades to the shipped design rather than to a black screen.
 *
 * <h3>Roles that have no launcher counterpart</h3>
 *
 * The launcher publishes eleven colours; the design system names thirteen. The rest are
 * *derived* from the published ones rather than left at their fixed values, because a fixed
 * hairline or third-tier label that was drawn for a dark palette disappears on a light one:
 *
 * <ul>
 *   <li>{@code stroke}, {@code ripple} — the launcher's on-surface colour at the resource's own
 *       alpha, so a hairline keeps its weight and only changes hue.</li>
 *   <li>{@code accent_dim} — the accent at the resource's alpha, same reasoning.</li>
 *   <li>{@code scrim} — the background at the resource's alpha.</li>
 *   <li>{@code text3} — the muted text pushed a little further toward the background, which
 *       keeps three distinct text tiers on a palette of any lightness.</li>
 * </ul>
 *
 * <h3>Reading cost</h3>
 *
 * One cursor query per process, cached. A {@link ContentObserver} drops the cache when the
 * driver switches theme or the cabin crosses into night, so the next screen built picks the new
 * colours up. Views already on screen are not re-tinted: that would mean tracking every view
 * this app ever coloured, and the launcher's own day/night switch is itself a recreate.
 */
public final class Palette {

    private Palette() {}

    /** The release launcher's authority. A debug launcher serves its own and is ignored. */
    private static final String AUTHORITY = "com.reveng.carlauncher.theme";

    private static final Uri ACTIVE_URI = Uri.parse("content://" + AUTHORITY + "/active");

    // Published column names — see ThemeContract in the launcher.
    private static final String COL_BACKGROUND = "background";
    private static final String COL_SURFACE = "surface";
    private static final String COL_SURFACE_VARIANT = "surface_variant";
    private static final String COL_PRIMARY = "primary";
    private static final String COL_ON_BACKGROUND = "on_background";
    private static final String COL_ON_SURFACE = "on_surface";
    private static final String COL_ON_SURFACE_MUTED = "on_surface_muted";
    private static final String COL_ERROR = "error";
    private static final String COL_ACCENT2 = "accent2";

    /** How far {@code text3} sits from {@code text2} toward the background. */
    private static final float TEXT3_TOWARD_BACKGROUND = 0.45f;

    /** Null until the first read; stays null when there is no launcher to read. */
    private static volatile Snapshot cached;
    private static volatile boolean loaded;
    private static volatile boolean observing;

    /**
     * The launcher's colour for {@code colorRes}'s design role, or the resource's own value when
     * the launcher is absent or the role has no counterpart.
     */
    public static int color(Context context, int colorRes) {
        final int fallback = context.getColor(colorRes);
        final Snapshot s = snapshot(context);
        if (s == null) {
            return fallback;
        }

        final String role = roleName(context, colorRes);
        if (role == null) {
            return fallback;
        }

        switch (role) {
            case "bg":
            case "bg2":
                return opaque(s.background);
            case "surface":
                return opaque(s.surface);
            case "surface2":
                return opaque(s.surfaceVariant);
            case "accent":
                return opaque(s.primary);
            case "accent2":
                return opaque(s.accent2);
            case "text":
                return opaque(s.onSurface);
            case "text2":
                return opaque(s.onSurfaceMuted);
            case "error":
                return opaque(s.error);

            // Derived: keep the resource's alpha, take the launcher's hue.
            case "accent_dim":
                return withAlphaOf(fallback, s.primary);
            case "stroke":
            case "ripple":
                return withAlphaOf(fallback, s.onSurface);
            case "scrim":
                return withAlphaOf(fallback, s.background);

            case "text3":
                return blend(opaque(s.onSurfaceMuted), opaque(s.background),
                        TEXT3_TOWARD_BACKGROUND);
            default:
                return fallback;
        }
    }

    /** Drops the cached palette; the next {@link #color} re-reads it. */
    public static void invalidate() {
        loaded = false;
        cached = null;
    }

    private static String roleName(Context context, int colorRes) {
        try {
            return context.getResources().getResourceEntryName(colorRes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Snapshot snapshot(Context context) {
        if (loaded) {
            return cached;
        }
        synchronized (Palette.class) {
            if (!loaded) {
                cached = read(context.getApplicationContext());
                loaded = true;
                observe(context.getApplicationContext());
            }
        }
        return cached;
    }

    /**
     * One query, defensively. Anything at all going wrong here — no launcher, an older launcher
     * without a column, a provider that throws — must leave the app on its own palette rather
     * than take it down: this runs while a screen is being built.
     */
    private static Snapshot read(Context appContext) {
        Cursor c = null;
        try {
            c = appContext.getContentResolver().query(ACTIVE_URI, null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            Snapshot s = new Snapshot();
            s.background = c.getLong(c.getColumnIndexOrThrow(COL_BACKGROUND));
            s.surface = c.getLong(c.getColumnIndexOrThrow(COL_SURFACE));
            s.surfaceVariant = c.getLong(c.getColumnIndexOrThrow(COL_SURFACE_VARIANT));
            s.primary = c.getLong(c.getColumnIndexOrThrow(COL_PRIMARY));
            s.onBackground = c.getLong(c.getColumnIndexOrThrow(COL_ON_BACKGROUND));
            s.onSurface = c.getLong(c.getColumnIndexOrThrow(COL_ON_SURFACE));
            s.onSurfaceMuted = c.getLong(c.getColumnIndexOrThrow(COL_ON_SURFACE_MUTED));
            s.error = c.getLong(c.getColumnIndexOrThrow(COL_ERROR));
            s.accent2 = c.getLong(c.getColumnIndexOrThrow(COL_ACCENT2));
            return s;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private static void observe(final Context appContext) {
        if (observing) {
            return;
        }
        try {
            appContext.getContentResolver().registerContentObserver(
                    ACTIVE_URI, false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            invalidate();
                        }
                    });
            observing = true;
        } catch (Exception e) {
            // An app that cannot observe simply keeps the palette it started with.
        }
    }

    /** ARGB with the alpha forced opaque — the published roles are solid colours. */
    private static int opaque(long argb) {
        return (int) (argb | 0xFF000000L);
    }

    /** The alpha of {@code from} with the RGB of {@code rgbSource}. */
    private static int withAlphaOf(int from, long rgbSource) {
        return (from & 0xFF000000) | ((int) rgbSource & 0x00FFFFFF);
    }

    /** {@code amount} of the way from {@code a} to {@code b}, per channel. */
    private static int blend(int a, int b, float amount) {
        int r = (int) (((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * amount));
        int g = (int) (((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * amount));
        int bl = (int) ((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * amount));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static final class Snapshot {
        long background;
        long surface;
        long surfaceVariant;
        long primary;
        long onBackground;
        long onSurface;
        long onSurfaceMuted;
        long error;
        long accent2;
    }
}
