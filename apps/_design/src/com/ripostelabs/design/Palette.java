package com.ripostelabs.design;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Looper;
import android.util.TypedValue;
import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * v0.5 — paints this app in the launcher's colours.
 *
 * The launcher (com.ripostelabs.carlauncher) publishes the palette it is drawing right now on a
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
 *       keeps three distinct text tiers on a palette of any lightness, but never under 4.5:1
 *       on any published surface ({@link TextTier}).</li>
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
    private static final String AUTHORITY = "com.ripostelabs.carlauncher.theme";

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

    // v0.9 style columns — appended by launcher v0.8+, absent on older launchers. Read
    // tolerantly: missing columns mean "the original style", exactly like no launcher at all.
    private static final String COL_CORNER_SCALE = "corner_scale";
    private static final String COL_MONO_TYPE = "mono_type";
    private static final String COL_HARD_EDGE = "hard_edge";

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
                return TextTier.third(opaque(s.onSurfaceMuted), opaque(s.background),
                        opaque(s.surface), opaque(s.surfaceVariant));
            default:
                return fallback;
        }
    }

    /** Drops the cached palette; the next {@link #color} re-reads it. */
    public static void invalidate() {
        loaded = false;
        cached = null;
    }

    // ---------------------------------------------------------------- v0.9 style

    /** Multiplier for every corner radius the design pack draws (Riposte publishes 0). */
    public static float cornerScale(Context context) {
        Snapshot s = snapshot(context);
        return s == null ? 1f : s.cornerScale;
    }

    /** Whether the active theme asks for the JetBrains Mono brand type. */
    public static boolean monoType(Context context) {
        Snapshot s = snapshot(context);
        return s != null && s.monoType;
    }

    /** Whether the active theme asks for structural 2dp borders instead of hairlines. */
    public static boolean hardEdge(Context context) {
        Snapshot s = snapshot(context);
        return s != null && s.hardEdge;
    }

    /**
     * The face for text built in code: the bundled JetBrains Mono when the active theme asks
     * for it, the original system sans otherwise. Layout XML never calls this — the
     * {@link #apply} walk re-faces what the resources typed.
     */
    public static Typeface typeface(Context context, boolean bold) {
        if (monoType(context)) {
            Typeface mono = fontByName(context, "jetbrains_mono");
            if (mono != null) {
                return bold ? Typeface.create(mono, Typeface.BOLD) : mono;
            }
        }
        return Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    private static Typeface fontByName(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "font", ctx.getPackageName());
        if (id == 0) {
            return null;
        }
        try {
            return ctx.getResources().getFont(id);
        } catch (RuntimeException e) {
            return null;
        }
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

            // Style columns are optional: an older launcher publishes colours only, and the
            // absence of a column must read as the shipped style, not as an error.
            int corner = c.getColumnIndex(COL_CORNER_SCALE);
            if (corner >= 0) {
                s.cornerScale = c.getFloat(corner);
            }
            int mono = c.getColumnIndex(COL_MONO_TYPE);
            if (mono >= 0) {
                s.monoType = c.getInt(mono) == 1;
            }
            int hard = c.getColumnIndex(COL_HARD_EDGE);
            if (hard >= 0) {
                s.hardEdge = c.getInt(hard) == 1;
            }
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

        // v0.9 — the active theme's style. Defaults are the shipped design: full radii,
        // system sans, hairline strokes. Only a theme that asks for more (Riposte) moves them.
        float cornerScale = 1f;
        boolean monoType;
        boolean hardEdge;
    }

    // ---------------------------------------------------------------- v0.5.2

    /**
     * Every role in the design pack, by resource name. Looked up with
     * {@code getIdentifier} rather than an {@code R} reference: this class is compiled into
     * twenty-six apps, each with its own {@code R}, and it must not know any of them.
     */
    private static final String[] ROLES = {
        "bg", "bg2", "surface", "surface2", "stroke", "accent", "accent2", "accent_dim",
        "text", "text2", "text3", "ripple", "scrim", "error",
    };

    /** Hairline width for our own card/field shapes; the XML pack draws them at 1dp. */
    private static final float STROKE_DP = 1f;

    /** Border width when the active theme asks for hard edges (Riposte). */
    private static final float HARD_STROKE_DP = 2f;

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
    /**
     * Hide the status and navigation bars for the whole activity, and keep them hidden when a
     * swipe reveals them (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE). API 30+; on anything older
     * the theme's windowFullscreen still applies.
     */
    private static void hideSystemBars(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        final Window window = activity.getWindow();
        window.setDecorFitsSystemWindows(false);
        hideNow(window);
        // apply() runs from onCreate, before the decor view is attached, and the controller a
        // detached window hands out forgets the request. Ask again once the view is up.
        window.getDecorView().post(() -> hideNow(window));
    }

    private static void hideNow(Window window) {
        final WindowInsetsController controller = window.getInsetsController();
        if (controller == null) {
            return;
        }
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

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

        // The launcher runs fullscreen; a suite app that does not flashes the Android clock,
        // Wi-Fi and battery in and out over the car UI on every launch (UI audit, 2026-09-22).
        // android:windowFullscreen no longer hides them on API 30+, so the controller does.
        hideSystemBars(activity);

        // Not walked here: these screens build their view tree during onCreate, so a walk
        // at the call site would only see whatever existed by that line. The layout hook
        // sees the finished tree, and everything built after it (v0.10).
        applyDeferred(activity);
        watch(activity);
    }

    /**
     * v0.10 — paint the views built after {@code onCreate} too.
     *
     * <p>A walk at the end of onCreate sees only what exists then. Forecast cards built after
     * a fetch, adapter rows, a panel swapped in on a tab change all arrive later with the
     * design-pack defaults still on them, and on a themed launcher that is a dark card inside a
     * light screen. This hooks the window's layout pass instead: each time the tree is laid
     * out, whatever the ledger has not seen is painted <em>before that layout is drawn</em>,
     * so a late view never shows its fallback colours, not even for one frame.
     *
     * <p>Each view is painted once ({@link PaintLedger}): the walk scales corner radii in
     * place, so a second pass over the same view would compound it. The pass over an
     * already-painted tree is a ledger lookup per view and nothing else.
     *
     * <p>A dialog draws in its own window and is not reached from here. Do not walk one: an
     * AlertDialog is system widgets whose text colours coincide with the pack's, and the walk
     * re-faces and re-tints them into a half-themed picker (tried on the clock's alarm
     * dialog). A dialog theme is the fix for dialogs, not this.
     *
     * <p>Called for you by {@link #apply(Activity)}.
     */
    public static void applyDeferred(Activity activity) {
        if (activity == null) {
            return;
        }
        final View root = activity.getWindow().getDecorView();
        // The decor view's observer exists before the window is attached; the framework
        // merges it into the live one on attach, so hooking here is enough.
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                apply(root);
            }
        });
    }

    /** Re-colour one view subtree. See {@link #apply(Activity)} for the rule. */
    public static void apply(View root) {
        if (root == null) {
            return;
        }
        Context ctx = root.getContext();
        int[][] map = roleMap(ctx);
        // v0.9: an unchanged palette no longer means an unchanged look — a theme may keep the
        // default colours and still ask for the brand style, so the walk runs for either.
        boolean styled = cornerScale(ctx) != 1f || monoType(ctx) || hardEdge(ctx);
        if (map.length == 0 && !styled) {
            return;
        }
        walk(root, map);
    }

    /** Views painted so far. The walk is not idempotent, so each view is painted once. */
    private static final PaintLedger PAINTED_VIEWS = new PaintLedger();

    private static void walk(View v, int[][] map) {
        if (PAINTED_VIEWS.firstVisit(v)) {
            paint(v, map);
        }

        // Always descend: a painted parent can have gained new children since.
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), map);
            }
        }
    }

    private static void paint(View v, int[][] map) {
        retintBackground(v, map);

        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int themed = themedFor(t.getCurrentTextColor(), map);
            if (themed != 0) {
                t.setTextColor(themed);
            }
            if (monoType(t.getContext())) {
                applyMono(t);
            }
        }

        // An icon's android:tint is a colour written in XML like any other (v0.10).
        if (v instanceof ImageView) {
            ImageView i = (ImageView) v;
            ColorStateList tint = i.getImageTintList();
            if (tint == null) {
                inkGlyph(i, map);
                return;
            }
            int themed = themedFor(tint.getDefaultColor(), map);
            if (themed != 0) {
                i.setImageTintList(ColorStateList.valueOf(themed));
            }
        }
    }

    /**
     * v0.11 — an untinted vector glyph takes the ink of the surface it sits on.
     *
     * <p>Every vector in the suite is drawn white, which a dark pack never notices and a
     * light one turns invisible: white toolbar icons on a bone header. The fix is by role,
     * not per file: the nearest opaque ancestor fill decides ({@link IconRole}). A pack
     * surface gets the palette's {@code text} ink; an accent button, a photo or a gauge
     * keeps its white glyph. Bitmaps are never touched.
     *
     * <p>Only when the palette moved the ink: with the default {@code text} colour the
     * white glyphs are already right, and skipping keeps the no-launcher and default-theme
     * screens pixel-identical.
     */
    private static void inkGlyph(ImageView i, int[][] map) {
        if (!(i.getDrawable() instanceof VectorDrawable)) {
            return;
        }
        Context ctx = i.getContext();
        int textId = roleId(ctx, "text");
        int ink = textId == 0 ? 0 : themedFor(ctx.getColor(textId), map);
        if (ink == 0) {
            return;
        }
        if (IconRole.decide(groundsOf(i), surfacesNow(ctx)) != IconRole.Paint.INK) {
            return;
        }
        i.setImageTintList(ColorStateList.valueOf(ink));
    }

    /** Background fills from {@code v} up to the window, nearest first; 0 where none. */
    private static int[] groundsOf(View v) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (View p = v; p != null; p = parentOf(p)) {
            out.add(fillOf(p.getBackground()));
        }
        int[] grounds = new int[out.size()];
        for (int k = 0; k < grounds.length; k++) {
            grounds[k] = out.get(k);
        }
        return grounds;
    }

    private static View parentOf(View v) {
        return v.getParent() instanceof View ? (View) v.getParent() : null;
    }

    /**
     * The solid colour a background paints, or 0 when it paints none (a stroke-only field, a
     * mask-only ripple). A ripple's mask layer is skipped: it clips the highlight and is
     * never seen.
     */
    private static int fillOf(Drawable d) {
        if (d instanceof ColorDrawable) {
            return ((ColorDrawable) d).getColor();
        }
        if (d instanceof GradientDrawable) {
            ColorStateList solid = ((GradientDrawable) d).getColor();
            return solid == null ? 0 : solid.getDefaultColor();
        }
        if (d instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) d;
            for (int k = 0; k < layers.getNumberOfLayers(); k++) {
                if (layers.getId(k) == android.R.id.mask) {
                    continue;
                }
                int fill = fillOf(layers.getDrawable(k));
                if (!IconRole.isClear(fill)) {
                    return fill;
                }
            }
        }
        return 0;
    }

    /** The pack's ground fills as the walk has painted them. */
    private static int[] surfacesNow(Context ctx) {
        String[] roles = {"bg", "bg2", "surface", "surface2"};
        int[] out = new int[roles.length];
        for (int k = 0; k < roles.length; k++) {
            out[k] = colorByName(ctx, roles[k]);
        }
        return out;
    }

    /**
     * Re-face one text onto the brand family, keeping what the original face expressed:
     * emphasis (a bold or medium face) stays the 700 weight, and display-sized numerals —
     * the pack draws those in a thin face the mono family does not have — take the 800.
     */
    private static void applyMono(TextView t) {
        Context ctx = t.getContext();
        boolean display = t.getTextSize() >= TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 40, t.getResources().getDisplayMetrics());
        Typeface mono = fontByName(ctx, display ? "jetbrains_mono_extrabold" : "jetbrains_mono");
        if (mono == null) {
            return;
        }
        Typeface was = t.getTypeface();
        boolean bold = was != null && was.isBold();
        t.setTypeface(!display && bold ? Typeface.create(mono, Typeface.BOLD) : mono);
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

        // Layer-lists get each shape child re-styled in place.
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
     * Re-style one shape: scale its corner radius by the theme's {@code cornerScale},
     * re-colour its solid, then re-draw the stroke on the shapes that carry one. Stroke has
     * no getter, so which shapes are bordered is decided by what the fill <em>was</em>:
     * every pack shape filled with a ground colour (bg/bg2/surface/surface2) is drawn with
     * the hairline — a 2dp opaque structural border instead when the theme asks for hard
     * edges — and accent fills and dim highlights are not. The pack's one gradient (the
     * hero card, whose fill cannot be read back) still gets the radius and the hard border.
     */
    private static void retintShape(View v, GradientDrawable g, int[][] map) {
        Context ctx = v.getContext();
        float scale = cornerScale(ctx);
        if (scale != 1f) {
            g.mutate();
            g.setCornerRadius(g.getCornerRadius() * scale);
        }

        ColorStateList solid = g.getColor();
        if (solid == null) {
            if (hardEdge(ctx)) {
                strokeShape(v, g);
            }
            return;
        }
        int was = solid.getDefaultColor();
        int themed = themedFor(was, map);
        if (themed != 0) {
            g.mutate();
            g.setColor(themed);
        }
        if (!isGround(ctx, was)) {
            return;
        }
        strokeShape(v, g);
    }

    private static void strokeShape(View v, GradientDrawable g) {
        Context ctx = v.getContext();
        int stroke = colorByName(ctx, "stroke");
        if (stroke == 0) {
            return;
        }
        boolean hard = hardEdge(ctx);
        if (hard) {
            // Structural, not a hairline: full-strength on-surface ink.
            stroke |= 0xFF000000;
        }
        int px = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, hard ? HARD_STROKE_DP : STROKE_DP,
                v.getResources().getDisplayMetrics());
        g.mutate();
        g.setStroke(px, stroke);
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

    /** A cheap fingerprint of the palette and style this app would paint right now. */
    private static int revision(Context ctx) {
        int h = 17;
        for (String role : ROLES) {
            h = h * 31 + colorByName(ctx, role);
        }
        h = h * 31 + Float.floatToIntBits(cornerScale(ctx));
        h = h * 31 + (monoType(ctx) ? 1 : 0);
        h = h * 31 + (hardEdge(ctx) ? 1 : 0);
        return h;
    }
}
