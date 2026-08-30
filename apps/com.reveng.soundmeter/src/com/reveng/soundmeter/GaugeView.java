package com.reveng.soundmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import com.reveng.design.Palette;

/**
 * A self-contained analog dB gauge drawn entirely with {@link Canvas}. A 240-degree
 * arc spans {@link #MIN_DB}..{@link #MAX_DB}; a coloured band runs quiet -> loud
 * (accent -> amber -> red), tick marks and numeric labels ring the outside, and an
 * accent needle points at the current smoothed level. Pure {@code android.*}: no
 * AndroidX, no external libraries.
 */
public class GaugeView extends View {

    public static final float MIN_DB = 30f;
    public static final float MAX_DB = 120f;

    // Sweep geometry: arc opens downward, centred on the bottom gap.
    private static final float START_ANGLE = 150f; // degrees (Canvas convention)
    private static final float SWEEP = 240f;        // total arc extent

    // Zone colours along the arc.
    private static final int C_QUIET = 0xFF5B9DFF; // accent (calm)
    private static final int C_MODER = 0xFF3DD68C; // green-ish moderate
    private static final int C_LOUD  = 0xFFFFC24B; // amber
    private static final int C_HARM  = 0xFFFF4D5E; // red (harmful)

    private int cAccent, cSurface, cSurface2, cText, cText2, cText3, cStroke;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakArc = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path needlePath = new Path();
    private final RectF arcRect = new RectF();

    /** Current smoothed dB value shown by the needle. */
    private float db = MIN_DB;
    /** Peak (max) dB marker along the arc, -1 = none. */
    private float peakDb = -1f;
    private boolean active = true;

    public GaugeView(Context c) { super(c); init(c); }
    public GaugeView(Context c, AttributeSet a) { super(c, a); init(c); }
    public GaugeView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        cAccent   = Palette.color(c, R.color.accent);
        cSurface  = Palette.color(c, R.color.surface);
        cSurface2 = Palette.color(c, R.color.surface2);
        cText     = Palette.color(c, R.color.text);
        cText2    = Palette.color(c, R.color.text2);
        cText3    = Palette.color(c, R.color.text3);
        cStroke   = Palette.color(c, R.color.stroke);

        track.setStyle(Paint.Style.STROKE);
        track.setColor(cSurface2);
        track.setStrokeCap(Paint.Cap.ROUND);

        band.setStyle(Paint.Style.STROKE);
        band.setStrokeCap(Paint.Cap.ROUND);

        tickMinor.setStyle(Paint.Style.STROKE);
        tickMinor.setColor(cText3);
        tickMinor.setStrokeCap(Paint.Cap.ROUND);

        tickMajor.setStyle(Paint.Style.STROKE);
        tickMajor.setColor(cText2);
        tickMajor.setStrokeCap(Paint.Cap.ROUND);

        label.setColor(cText2);
        label.setTextAlign(Paint.Align.CENTER);

        needle.setStyle(Paint.Style.FILL);
        needle.setColor(cAccent);

        needleGlow.setStyle(Paint.Style.STROKE);
        needleGlow.setStrokeCap(Paint.Cap.ROUND);

        peakArc.setStyle(Paint.Style.STROKE);
        peakArc.setStrokeCap(Paint.Cap.ROUND);
        peakArc.setColor(cText);

        hub.setStyle(Paint.Style.FILL);
        hub.setColor(cSurface2);

        hubRing.setStyle(Paint.Style.STROKE);
        hubRing.setColor(cAccent);
    }

    /** Set the current level (dB) and repaint. Clamped to the gauge range. */
    public void setDb(float value) {
        this.db = clamp(value);
        invalidate();
    }

    /** Set the peak/max marker along the arc; pass a value < MIN_DB to hide. */
    public void setPeak(float value) {
        this.peakDb = value < MIN_DB ? -1f : clamp(value);
        invalidate();
    }

    /** Dim the gauge when not actively listening. */
    public void setActive(boolean a) {
        this.active = a;
        invalidate();
    }

    /** Colour for a given dB level (used by the needle + digital readout). */
    public int colorFor(float value) {
        float t = (clamp(value) - MIN_DB) / (MAX_DB - MIN_DB);
        if (t < 0.40f) return lerp(C_QUIET, C_MODER, t / 0.40f);
        if (t < 0.68f) return lerp(C_MODER, C_LOUD, (t - 0.40f) / 0.28f);
        return lerp(C_LOUD, C_HARM, (t - 0.68f) / 0.32f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        final float cx = w / 2f;
        // Bias the centre downward a touch so the 240-degree fan sits nicely.
        final float cy = h * 0.56f;
        final float radius = Math.min(w, h * 1.15f) / 2f - dp(30);
        if (radius <= 0) return;

        final float stroke = radius * 0.11f;
        track.setStrokeWidth(stroke);
        band.setStrokeWidth(stroke);
        peakArc.setStrokeWidth(stroke + dp(4));
        tickMinor.setStrokeWidth(dp(1.5f));
        tickMajor.setStrokeWidth(dp(3f));
        label.setTextSize(radius * 0.085f);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // Base track.
        canvas.drawArc(arcRect, START_ANGLE, SWEEP, false, track);

        // Coloured quiet -> loud band via a sweep gradient masked to the arc.
        SweepGradient sg = new SweepGradient(cx, cy,
                new int[]{ C_QUIET, C_MODER, C_LOUD, C_HARM, C_HARM },
                new float[]{ 0f, SWEEP / 360f * 0.45f, SWEEP / 360f * 0.72f,
                             SWEEP / 360f, 1f });
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setRotate(START_ANGLE, cx, cy);
        sg.setLocalMatrix(m);
        band.setShader(sg);
        band.setAlpha(active ? 255 : 90);
        canvas.drawArc(arcRect, START_ANGLE, SWEEP, false, band);
        band.setShader(null);
        band.setAlpha(255);

        // Ticks + labels every 10 dB (major) and 5 dB (minor).
        final float tickOuter = radius - stroke / 2f - dp(6);
        final float labelR = tickOuter - dp(26);
        for (int v = (int) MIN_DB; v <= (int) MAX_DB; v += 5) {
            float frac = (v - MIN_DB) / (MAX_DB - MIN_DB);
            float ang = START_ANGLE + frac * SWEEP;
            double rad = Math.toRadians(ang);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
            boolean major = (v % 10) == 0;
            float len = major ? dp(16) : dp(9);
            Paint p = major ? tickMajor : tickMinor;
            float ox = cx + cos * tickOuter;
            float oy = cy + sin * tickOuter;
            float ix = cx + cos * (tickOuter - len);
            float iy = cy + sin * (tickOuter - len);
            canvas.drawLine(ix, iy, ox, oy, p);
            if (major) {
                float lx = cx + cos * labelR;
                float ly = cy + sin * labelR;
                Paint.FontMetrics fm = label.getFontMetrics();
                canvas.drawText(String.valueOf(v), lx,
                        ly - (fm.ascent + fm.descent) / 2f, label);
            }
        }

        // Peak (max) marker: a short bright arc segment at the peak angle.
        if (peakDb >= MIN_DB) {
            float frac = (peakDb - MIN_DB) / (MAX_DB - MIN_DB);
            float ang = START_ANGLE + frac * SWEEP;
            peakArc.setColor(colorFor(peakDb));
            canvas.drawArc(arcRect, ang - 1.4f, 2.8f, false, peakArc);
        }

        // Needle.
        float frac = (db - MIN_DB) / (MAX_DB - MIN_DB);
        float ang = START_ANGLE + frac * SWEEP;
        double rad = Math.toRadians(ang);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        int nc = active ? colorFor(db) : cText3;

        float tipR = radius - stroke - dp(4);
        float tailR = radius * 0.16f;
        float baseHalf = radius * 0.045f;
        // Perpendicular direction for the needle base width.
        float px = -sin, py = cos;
        float tipX = cx + cos * tipR, tipY = cy + sin * tipR;
        float tailX = cx - cos * tailR, tailY = cy - sin * tailR;

        needleGlow.setStrokeWidth(baseHalf * 2.4f);
        needleGlow.setColor((nc & 0x00FFFFFF) | 0x33000000);
        canvas.drawLine(tailX, tailY, tipX, tipY, needleGlow);

        needle.setColor(nc);
        needlePath.reset();
        needlePath.moveTo(tipX, tipY);
        needlePath.lineTo(cx + px * baseHalf, cy + py * baseHalf);
        needlePath.lineTo(tailX, tailY);
        needlePath.lineTo(cx - px * baseHalf, cy - py * baseHalf);
        needlePath.close();
        canvas.drawPath(needlePath, needle);

        // Hub.
        hubRing.setStrokeWidth(dp(3f));
        hubRing.setColor(nc);
        canvas.drawCircle(cx, cy, baseHalf * 1.7f, hub);
        canvas.drawCircle(cx, cy, baseHalf * 1.7f, hubRing);
    }

    private static float clamp(float v) {
        if (v < MIN_DB) return MIN_DB;
        if (v > MAX_DB) return MAX_DB;
        return v;
    }

    private static int lerp(int a, int b, float t) {
        if (t < 0) t = 0; if (t > 1) t = 1;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
