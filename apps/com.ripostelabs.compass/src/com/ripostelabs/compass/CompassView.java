package com.ripostelabs.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import com.ripostelabs.design.Palette;

/**
 * A self-contained compass dial drawn entirely with {@link Canvas}. The lettered
 * ring (N/E/S/W, tick marks and degree labels) rotates opposite the measured
 * azimuth so that its "N" always points at real-world magnetic/true north, while
 * a fixed accent needle at the top of the view marks the direction of travel
 * (the heading). Pure {@code android.*}: no AndroidX, no external libraries.
 */
public class CompassView extends View {

    // Design-system palette, pulled from the copied res/values/colors.xml.
    private int cAccent, cSurface, cSurface2, cText, cText2, cText3, cStroke;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardinal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardinalN = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleTail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path needlePath = new Path();

    /** Current smoothed azimuth, degrees clockwise from north (0 = N). */
    private float azimuth = 0f;

    public CompassView(Context c) { super(c); init(c); }
    public CompassView(Context c, AttributeSet a) { super(c, a); init(c); }
    public CompassView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        cAccent   = Palette.color(c, R.color.accent);
        cSurface  = Palette.color(c, R.color.surface);
        cSurface2 = Palette.color(c, R.color.surface2);
        cText     = Palette.color(c, R.color.text);
        cText2    = Palette.color(c, R.color.text2);
        cText3    = Palette.color(c, R.color.text3);
        cStroke   = Palette.color(c, R.color.stroke);

        face.setStyle(Paint.Style.FILL);
        face.setColor(cSurface);

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(cSurface2);

        tickMinor.setStyle(Paint.Style.STROKE);
        tickMinor.setColor(cText3);
        tickMinor.setStrokeCap(Paint.Cap.ROUND);

        tickMajor.setStyle(Paint.Style.STROKE);
        tickMajor.setColor(cText2);
        tickMajor.setStrokeCap(Paint.Cap.ROUND);

        label.setColor(cText2);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(false);

        cardinal.setColor(cText);
        cardinal.setTextAlign(Paint.Align.CENTER);
        cardinal.setFakeBoldText(true);

        cardinalN.setColor(cAccent);
        cardinalN.setTextAlign(Paint.Align.CENTER);
        cardinalN.setFakeBoldText(true);

        needle.setStyle(Paint.Style.FILL);
        needle.setColor(cAccent);

        needleTail.setStyle(Paint.Style.FILL);
        needleTail.setColor(cText3);

        hub.setStyle(Paint.Style.FILL);
        hub.setColor(cSurface2);

        hubRing.setStyle(Paint.Style.STROKE);
        hubRing.setColor(cAccent);
    }

    /** Set the heading (degrees, 0..360) and repaint. */
    public void setAzimuth(float deg) {
        this.azimuth = deg;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        final float cx = w / 2f, cy = h / 2f;
        final float radius = Math.min(w, h) / 2f - dp(6);
        if (radius <= 0) return;

        // Scale line/text metrics to the dial size.
        ring.setStrokeWidth(dp(2));
        tickMinor.setStrokeWidth(dp(1.5f));
        tickMajor.setStrokeWidth(dp(3f));
        label.setTextSize(radius * 0.085f);
        cardinal.setTextSize(radius * 0.16f);
        cardinalN.setTextSize(radius * 0.16f);

        // Static face + outer ring.
        canvas.drawCircle(cx, cy, radius, face);
        canvas.drawCircle(cx, cy, radius, ring);

        // --- Rotating dial: rotate opposite the azimuth so N tracks true north.
        canvas.save();
        canvas.rotate(-azimuth, cx, cy);

        final float tickOuter = radius - dp(6);
        for (int deg = 0; deg < 360; deg += 2) {
            double rad = Math.toRadians(deg);
            float sin = (float) Math.sin(rad), cos = (float) Math.cos(rad);
            boolean major = (deg % 30) == 0;
            boolean mid = (deg % 10) == 0;
            float len = major ? dp(22) : (mid ? dp(15) : dp(8));
            Paint p = (major || mid) ? tickMajor : tickMinor;
            float ox = cx + sin * tickOuter;
            float oy = cy - cos * tickOuter;
            float ix = cx + sin * (tickOuter - len);
            float iy = cy - cos * (tickOuter - len);
            canvas.drawLine(ix, iy, ox, oy, p);
        }

        // Degree labels every 30deg (skip where the big cardinal letters sit).
        float labelR = tickOuter - dp(34);
        for (int deg = 0; deg < 360; deg += 30) {
            if (deg % 90 == 0) continue; // 0/90/180/270 shown as letters
            String s = String.valueOf(deg);
            drawRotatedText(canvas, s, cx, cy, labelR, deg, label);
        }

        // Cardinal + intercardinal letters.
        float cardR = tickOuter - dp(30);
        drawRotatedText(canvas, "N", cx, cy, cardR, 0, cardinalN);
        drawRotatedText(canvas, "E", cx, cy, cardR, 90, cardinal);
        drawRotatedText(canvas, "S", cx, cy, cardR, 180, cardinal);
        drawRotatedText(canvas, "W", cx, cy, cardR, 270, cardinal);

        canvas.restore();

        // --- Fixed needle (points up = heading/direction of travel).
        float half = radius * 0.085f;
        float tipY = cy - radius * 0.60f;
        needlePath.reset();
        needlePath.moveTo(cx, tipY);
        needlePath.lineTo(cx - half, cy);
        needlePath.lineTo(cx + half, cy);
        needlePath.close();
        canvas.drawPath(needlePath, needle);

        // Short muted tail below the hub for balance.
        float tailY = cy + radius * 0.34f;
        needlePath.reset();
        needlePath.moveTo(cx, tailY);
        needlePath.lineTo(cx - half * 0.8f, cy);
        needlePath.lineTo(cx + half * 0.8f, cy);
        needlePath.close();
        canvas.drawPath(needlePath, needleTail);

        // Center hub.
        hubRing.setStrokeWidth(dp(2.5f));
        canvas.drawCircle(cx, cy, half * 0.9f, hub);
        canvas.drawCircle(cx, cy, half * 0.9f, hubRing);
    }

    /** Draw text at the given radius/bearing, upright (not following dial spin visually). */
    private void drawRotatedText(Canvas c, String s, float cx, float cy,
                                 float r, float bearingDeg, Paint p) {
        double rad = Math.toRadians(bearingDeg);
        float x = cx + (float) Math.sin(rad) * r;
        float y = cy - (float) Math.cos(rad) * r;
        // Vertically center the glyph on (x, y).
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = y - (fm.ascent + fm.descent) / 2f;
        c.drawText(s, x, baseline, p);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
