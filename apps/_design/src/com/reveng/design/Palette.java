package com.reveng.design;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

            // v0.8: the drawn offset shadow under raised cards. Ink on a light field,
            // plain black on a dark one, so the block reads as shadow rather than glow.
            case "shadow":
                return isLight(s.background)
                        ? opaque(s.onBackground)
                        : 0xFF000000;
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
                            repaintWatchers();
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

    /** Whether {@code argb} is a light colour (perceptual luma over the midpoint). */
    private static boolean isLight(long argb) {
        int r = (int) ((argb >> 16) & 0xFF);
        int g = (int) ((argb >> 8) & 0xFF);
        int b = (int) (argb & 0xFF);
        return (299 * r + 587 * g + 114 * b) / 1000 > 140;
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

    // ---------------------------------------------------------------- v0.5.2

    /**
     * Every role in the design pack, by resource name. Looked up with
     * {@code getIdentifier} rather than an {@code R} reference: this class is compiled into
     * twenty-six apps, each with its own {@code R}, and it must not know any of them.
     */
    private static final String[] ROLES = {
        "bg", "bg2", "surface", "surface2", "stroke", "accent", "accent2", "accent_dim",
        "text", "text2", "text3", "ripple", "scrim", "error", "shadow",
    };

    /** Border width for our own card/field shapes; the v0.8 pack draws them at 2dp. */
    private static final float STROKE_DP = 2f;

    /**
     * Re-colour everything the *resources* coloured, then keep it current.
     *
     * <h4>The problem this solves</h4>
     *
     * A colour written in XML — a shape's solid, a ripple, a style's {@code textColor} — is
     * resolved when the view is inflated, from the app's own {@code colors.xml}. It cannot
     * follow a palette published at runtime. Roughly 860 references across the suite are like
     * that, and {@link #color} does nothing for any of them, because no Java call site is
     * involved.
     *
     * <h4>The rule</h4>
     *
     * A colour is replaced only if it is <em>exactly</em> a design-pack default. That is what
     * makes this safe to run over a whole screen: it repaints what the design system painted
     * and leaves everything else — a gauge's red band, a chart series, a photo — untouched.
     * With no launcher the themed value equals the default, so every replacement is a no-op.
     *
     * <p>Call once at the end of {@code onCreate}, after the view tree exists. It also starts
     * watching the palette, so the screen re-paints if the driver changes theme (v0.5.3).
     */
    public static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        final View root = activity.getWindow().getDecorView();

        // The window background comes from the activity theme (@color/bg) and is not part of
        // the content tree, so walking alone would leave the ground colour behind.
        int bg = colorByName(activity, "bg");
        if (bg != 0) {
            activity.getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        }

        // Posted, not immediate: these screens build their view tree during onCreate, so a
        // walk that ran at the call site would only see whatever existed by that line. This
        // way the call can sit anywhere in onCreate and still see the finished tree.
        root.post(new Runnable() {
            @Override
            public void run() {
                apply(root);
            }
        });
        watch(activity);
    }

    /** Re-colour one view subtree. See {@link #apply(Activity)} for the rule. */
    public static void apply(View root) {
        if (root == null) {
            return;
        }
        Context ctx = root.getContext();
        int[][] map = roleMap(ctx);
        if (map.length == 0) {
            return;
        }
        walk(root, map);
    }

    private static void walk(View v, int[][] map) {
        retintBackground(v, map);

        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int themed = themedFor(t.getCurrentTextColor(), map);
            if (themed != 0) {
                t.setTextColor(themed);
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), map);
            }
        }
    }

    private static void retintBackground(View v, int[][] map) {
        Drawable d = v.getBackground();
        if (d == null) {
            return;
        }

        if (d instanceof ColorDrawable) {
            int themed = themedFor(((ColorDrawable) d).getColor(), map);
            if (themed != 0) {
                v.setBackground(new ColorDrawable(themed));
            }
            return;
        }

        if (d instanceof RippleDrawable) {
            // RippleDrawable exposes no getter for its colour, but every ripple in the pack is
            // @color/ripple, so the themed value is unambiguous.
            int themed = colorByName(v.getContext(), "ripple");
            if (themed != 0) {
                d.mutate();
                ((RippleDrawable) d).setColor(ColorStateList.valueOf(themed));
            }
            // A ripple is a LayerDrawable: keep walking so its content — an accent button's
            // fill, a bordered ghost body — follows the palette too. (v0.8)
            retintLayers(v, (RippleDrawable) d, map);
            return;
        }

        if (d instanceof GradientDrawable) {
            d.mutate();
            retintShape(v, (GradientDrawable) v.getBackground(), map);
            return;
        }

        // v0.8: the hard-edge cards are layer-lists (offset shadow block + bordered body).
        if (d instanceof LayerDrawable) {
            d.mutate();
            retintLayers(v, (LayerDrawable) v.getBackground(), map);
        }
    }

    private static void retintLayers(View v, LayerDrawable layers, int[][] map) {
        for (int i = 0; i < layers.getNumberOfLayers(); i++) {
            Drawable child = layers.getDrawable(i);
            if (child instanceof GradientDrawable) {
                retintShape(v, (GradientDrawable) child, map);
            } else if (child instanceof LayerDrawable) {
                retintLayers(v, (LayerDrawable) child, map);
            }
        }
    }

    /**
     * Re-colour one shape's solid, then re-draw the structural border on the shapes that
     * carry one. Stroke has no getter, so which shapes are bordered is decided by what the
     * fill <em>was</em>: every pack shape filled with a ground colour (bg/bg2/surface/
     * surface2) is drawn with the 2dp border, and shadow blocks, accent fills and dim
     * highlights are not — a rule the pack's own drawables are written to keep.
     */
    private static void retintShape(View v, GradientDrawable g, int[][] map) {
        ColorStateList solid = g.getColor();
        if (solid == null) {
            return;
        }
        int was = solid.getDefaultColor();
        int themed = themedFor(was, map);
        if (themed != 0) {
            g.mutate();
            g.setColor(themed);
        }
        if (!isGround(v.getContext(), was)) {
            return;
        }
        int stroke = colorByName(v.getContext(), "stroke");
        if (stroke != 0) {
            int px = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, STROKE_DP,
                    v.getResources().getDisplayMetrics());
            g.mutate();
            g.setStroke(px, stroke);
        }
    }

    /** Whether {@code color} is one of the pack's ground fills — the bordered ones. */
    private static boolean isGround(Context ctx, int color) {
        for (String role : new String[]{"bg", "bg2", "surface", "surface2"}) {
            int id = roleId(ctx, role);
            if (id != 0 && ctx.getColor(id) == color) {
                return true;
            }
        }
        return false;
    }

    /** The themed replacement for {@code current}, or 0 if it is not a design-pack default. */
    private static int themedFor(int current, int[][] map) {
        for (int[] pair : map) {
            if (pair[0] == current) {
                return pair[1] == current ? 0 : pair[1];
            }
        }
        return 0;
    }

    /** {@code {default, themed}} for every role this app actually defines. */
    private static int[][] roleMap(Context ctx) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (String role : ROLES) {
            int id = roleId(ctx, role);
            if (id == 0) {
                continue;
            }
            int def = ctx.getColor(id);
            int themed = color(ctx, id);
            if (def != themed) {
                out.add(new int[]{def, themed});
            }
        }
        return out.toArray(new int[0][]);
    }

    private static int colorByName(Context ctx, String role) {
        int id = roleId(ctx, role);
        return id == 0 ? 0 : color(ctx, id);
    }

    private static int roleId(Context ctx, String role) {
        return ctx.getResources().getIdentifier(role, "color", ctx.getPackageName());
    }
    // ---------------------------------------------------------------- v0.5.3

    /**
     * Activities that should re-paint when the palette changes. Weak, because this list
     * outlives any screen: a strong reference here would keep every activity the app has ever
     * opened alive for the life of the process.
     */
    private static final java.util.List<java.lang.ref.WeakReference<Activity>> WATCHERS =
            new java.util.ArrayList<>();

    /** The palette each watching activity was last painted with, to avoid pointless recreates. */
    private static final java.util.Map<Activity, Integer> PAINTED = new java.util.WeakHashMap<>();

    /**
     * Re-paint {@code activity} when the driver switches theme, or the cabin crosses into night.
     *
     * <p>The screen is rebuilt with {@link Activity#recreate()} rather than re-walked: colours
     * set from Java at build time (an icon tint, a paint in a custom view's constructor) are not
     * reachable from the view tree afterwards, so a second {@link #apply} pass would leave half
     * the screen on the old palette. A recreate is what the framework itself does for a
     * configuration change, and these screens are cheap to rebuild.
     *
     * <p>Called for you by {@link #apply(Activity)}.
     */
    public static void watch(Activity activity) {
        if (activity == null) {
            return;
        }
        synchronized (WATCHERS) {
            prune();
            for (java.lang.ref.WeakReference<Activity> ref : WATCHERS) {
                if (ref.get() == activity) {
                    PAINTED.put(activity, revision(activity));
                    return;
                }
            }
            WATCHERS.add(new java.lang.ref.WeakReference<>(activity));
            PAINTED.put(activity, revision(activity));
        }
    }

    private static void repaintWatchers() {
        java.util.List<Activity> due = new java.util.ArrayList<>();
        synchronized (WATCHERS) {
            prune();
            for (java.lang.ref.WeakReference<Activity> ref : WATCHERS) {
                Activity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) {
                    continue;
                }
                Integer was = PAINTED.get(a);
                int now = revision(a);
                // A notify does not mean the colours moved: the launcher republishes on every
                // theme *and* day/night change, and re-publishing an identical palette is
                // explicitly allowed. Recreating on those would restart the screen for nothing.
                if (was != null && was == now) {
                    continue;
                }
                PAINTED.put(a, now);
                due.add(a);
            }
        }
        for (Activity a : due) {
            a.runOnUiThread(a::recreate);
        }
    }

    private static void prune() {
        java.util.Iterator<java.lang.ref.WeakReference<Activity>> it = WATCHERS.iterator();
        while (it.hasNext()) {
            Activity a = it.next().get();
            if (a == null || a.isDestroyed()) {
                it.remove();
            }
        }
    }

    /** A cheap fingerprint of the palette this app would paint right now. */
    private static int revision(Context ctx) {
        int h = 17;
        for (String role : ROLES) {
            h = h * 31 + colorByName(ctx, role);
        }
        return h;
    }
}
