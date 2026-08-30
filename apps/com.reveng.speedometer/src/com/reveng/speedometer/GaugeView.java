package com.reveng.speedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * A self-contained analog speedometer dial drawn entirely with {@link Canvas}.
 *
 * The dial is a 270-degree sweep (a 90-degree gap at the bottom, automotive
 * style). A muted track arc spans the full 0..max range; an {@code accent}
 * progress arc fills from zero up to the current speed. Tick marks and numeric
 * labels sit just inside the arc, and a needle points at the current speed. The
 * needle position is eased toward the target each frame so it sweeps smoothly.
 *
 * The big digital readout + unit that live in the middle of the dial are a
 * separate {@code @style/Display} TextView overlaid by the layout; this view
 * only owns the dial + needle. Pure {@code android.*}: no AndroidX.
 */
public class GaugeView extends View {

    // Design-system palette, pulled from the copied res/values/colors.xml.
    private int cAccent, cAccentDim, cSurface, cSurface2, cText, cText2, cText3;

    private final Paint track   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path needlePath = new Path();
    private final RectF arcRect = new RectF();

    // Dial geometry: start at 135 deg (down-left), sweep 270 deg clockwise.
    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    private float maxSpeed = 180f;      // full-scale value in the current unit
    private float labelStep = 20f;      // numeric label every N units
    private float targetSpeed = 0f;     // requested speed (current unit)
    private float animSpeed = 0f;       // eased needle value

    public GaugeView(Context c) { super(c); init(c); }
    public GaugeView(Context c, AttributeSet a) { super(c, a); init(c); }
    public GaugeView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        cAccent    = c.getResources().getColor(R.color.accent);
        cAccentDim = c.getResources().getColor(R.color.accent_dim);
        cSurface   = c.getResources().getColor(R.color.surface);
        cSurface2  = c.getResources().getColor(R.color.surface2);
        cText      = c.getResources().getColor(R.color.text);
        cText2     = c.getResources().getColor(R.color.text2);
        cText3     = c.getResources().getColor(R.color.text3);

        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(cSurface2);

        progress.setStyle(Paint.Style.STROKE);
        progress.setStrokeCap(Paint.Cap.ROUND);
        progress.setColor(cAccent);

        tickMinor.setStyle(Paint.Style.STROKE);
        tickMinor.setStrokeCap(Paint.Cap.ROUND);
        tickMinor.setColor(cText3);

        tickMajor.setStyle(Paint.Style.STROKE);
        tickMajor.setStrokeCap(Paint.Cap.ROUND);
        tickMajor.setColor(cText2);

        label.setColor(cText2);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        needle.setStyle(Paint.Style.FILL);
        needle.setColor(cAccent);

        hub.setStyle(Paint.Style.FILL);
        hub.setColor(cSurface2);

        hubRing.setStyle(Paint.Style.STROKE);
        hubRing.setColor(cAccent);
    }

    /** Full-scale value and label spacing for the current unit. */
    public void setScale(float max, float step) {
        this.maxSpeed = max;
        this.labelStep = step;
        invalidate();
    }

    /** Set the current speed (in the displayed unit); the needle eases toward it. */
    public void setSpeed(float speed) {
        if (speed < 0) speed = 0;
        if (speed > maxSpeed) speed = maxSpeed;
        this.targetSpeed = speed;
        invalidate();
    }

    /** Map a speed value onto its angle (degrees, drawArc convention). */
    private float angleFor(float speed) {
        float frac = maxSpeed > 0 ? speed / maxSpeed : 0f;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        return START_ANGLE + SWEEP_ANGLE * frac;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        final float cx = w / 2f, cy = h / 2f;
        final float radius = Math.min(w, h) / 2f - dp(14);
        if (radius <= 0) return;

        // Ease the needle toward the target; repaint until it settles.
        float diff = targetSpeed - animSpeed;
        if (Math.abs(diff) > 0.05f) {
            animSpeed += diff * 0.18f;
            postInvalidateOnAnimation();
        } else {
            animSpeed = targetSpeed;
        }

        final float stroke = radius * 0.09f;
        track.setStrokeWidth(stroke);
        progress.setStrokeWidth(stroke);
        tickMinor.setStrokeWidth(dp(2f));
        tickMajor.setStrokeWidth(dp(3.5f));
        label.setTextSize(radius * 0.085f);

        // Arc bounding box (inset so the round cap stays inside the view).
        float arcInset = stroke / 2f + dp(2);
        arcRect.set(cx - radius + arcInset, cy - radius + arcInset,
                    cx + radius - arcInset, cy + radius - arcInset);

        // Track (full range) then accent progress up to the current speed.
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, track);
        float sweep = angleFor(animSpeed) - START_ANGLE;
        if (sweep > 0.5f) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, progress);
        }

        // Tick marks + numeric labels along the dial.
        float tickOuter = radius - stroke - dp(6);
        int steps = Math.round(maxSpeed / (labelStep / 2f)); // minor ticks at half-step
        float minorStep = maxSpeed / steps;
        for (int i = 0; i <= steps; i++) {
            float value = i * minorStep;
            float ang = angleFor(value);
            double rad = Math.toRadians(ang);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
            boolean major = Math.abs((value / labelStep) - Math.round(value / labelStep)) < 0.001f;
            float len = major ? dp(20) : dp(11);
            Paint p = major ? tickMajor : tickMinor;
            float ox = cx + cos * tickOuter;
            float oy = cy + sin * tickOuter;
            float ix = cx + cos * (tickOuter - len);
            float iy = cy + sin * (tickOuter - len);
            canvas.drawLine(ix, iy, ox, oy, p);

            if (major) {
                float lr = tickOuter - len - dp(20);
                float lx = cx + cos * lr;
                float ly = cy + sin * lr;
                Paint.FontMetrics fm = label.getFontMetrics();
                float baseline = ly - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(String.valueOf(Math.round(value)), lx, baseline, label);
            }
        }

        // Needle: a slim triangle from the hub out toward the current speed.
        float nAng = angleFor(animSpeed);
        double nr = Math.toRadians(nAng);
        double perp = Math.toRadians(nAng + 90);
        float ncos = (float) Math.cos(nr), nsin = (float) Math.sin(nr);
        float pcos = (float) Math.cos(perp), psin = (float) Math.sin(perp);
        float half = dp(9);
        float tipR = tickOuter - dp(6);
        float tailR = radius * 0.14f;
        float tipX = cx + ncos * tipR,  tipY = cy + nsin * tipR;
        float bx1 = cx + pcos * half - ncos * tailR;
        float by1 = cy + psin * half - nsin * tailR;
        float bx2 = cx - pcos * half - ncos * tailR;
        float by2 = cy - psin * half - nsin * tailR;
        needlePath.reset();
        needlePath.moveTo(tipX, tipY);
        needlePath.lineTo(bx1, by1);
        needlePath.lineTo(bx2, by2);
        needlePath.close();
        canvas.drawPath(needlePath, needle);

        // Center hub over the needle base.
        float hubR = radius * 0.11f;
        hubRing.setStrokeWidth(dp(3f));
        canvas.drawCircle(cx, cy, hubR, hub);
        canvas.drawCircle(cx, cy, hubR, hubRing);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
