package com.ripostelabs.level;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import com.ripostelabs.design.Palette;

/**
 * A two-axis bubble level drawn entirely with {@link Canvas}. A circular target
 * (concentric guide rings + crosshair) is fixed to the view centre; a bubble is
 * displaced from centre by the measured (roll, pitch) so that it drifts toward
 * the raised side of the surface, exactly like a physical spirit level. When the
 * surface is within tolerance of level, the bubble, centre tolerance ring and
 * crosshair all switch to the accent colour.
 *
 * Pure {@code android.*}: no AndroidX, no external libraries.
 */
public class BubbleLevelView extends View {

    // Design-system palette, pulled from the copied res/values/colors.xml.
    private int cAccent, cSurface, cSurface2, cText2, cText3, cStroke;

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tolRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Full-scale tilt (degrees) mapped to the outer travel radius of the bubble.
    private static final float MAX_ANGLE = 25f;

    private float pitch = 0f, roll = 0f;
    private boolean isLevel = false;
    private float tolDeg = 1f;

    public BubbleLevelView(Context c) { super(c); init(c); }
    public BubbleLevelView(Context c, AttributeSet a) { super(c, a); init(c); }
    public BubbleLevelView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        cAccent   = Palette.color(c, R.color.accent);
        cSurface  = Palette.color(c, R.color.surface);
        cSurface2 = Palette.color(c, R.color.surface2);
        cText2    = Palette.color(c, R.color.text2);
        cText3    = Palette.color(c, R.color.text3);
        cStroke   = Palette.color(c, R.color.stroke);

        face.setStyle(Paint.Style.FILL);
        face.setColor(cSurface);

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(cSurface2);

        tolRing.setStyle(Paint.Style.STROKE);

        cross.setStyle(Paint.Style.STROKE);
        cross.setStrokeCap(Paint.Cap.ROUND);

        tick.setStyle(Paint.Style.FILL);
        tick.setColor(cText3);

        bubble.setStyle(Paint.Style.FILL);
        bubbleGlow.setStyle(Paint.Style.FILL);
        bubbleRing.setStyle(Paint.Style.STROKE);
    }

    /** Update the displayed tilt. {@code pitch}/{@code roll} are degrees. */
    public void setTilt(float pitch, float roll, boolean isLevel, float tolDeg) {
        this.pitch = pitch;
        this.roll = roll;
        this.isLevel = isLevel;
        this.tolDeg = tolDeg;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(8);
        if (radius <= 0) return;

        // Face + outer boundary ring.
        canvas.drawCircle(cx, cy, radius, face);
        ring.setStrokeWidth(dp(2));
        canvas.drawCircle(cx, cy, radius, ring);

        // Concentric guide rings.
        ring.setStrokeWidth(dp(1));
        for (int i = 1; i <= 3; i++) {
            canvas.drawCircle(cx, cy, radius * i / 4f, ring);
        }

        // Cardinal tick marks around the rim.
        for (int a = 0; a < 360; a += 90) {
            double rad = Math.toRadians(a);
            float tx = cx + (float) Math.cos(rad) * radius;
            float ty = cy + (float) Math.sin(rad) * radius;
            canvas.drawCircle(tx, ty, dp(3), tick);
        }

        int accentOrIdle = isLevel ? cAccent : cText2;

        // Crosshair through the centre.
        cross.setColor(isLevel ? cAccent : cStroke);
        cross.setStrokeWidth(dp(1.5f));
        canvas.drawLine(cx - radius, cy, cx + radius, cy, cross);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, cross);

        // Centre tolerance ring: the "target" the bubble must sit inside to be level.
        float tolRadius = radius * (tolDeg / MAX_ANGLE);
        // Keep the target visually meaningful even for a tight tolerance.
        float targetRadius = Math.max(tolRadius, dp(30));
        tolRing.setColor(isLevel ? cAccent : cText3);
        tolRing.setStrokeWidth(dp(isLevel ? 2.5f : 1.5f));
        canvas.drawCircle(cx, cy, targetRadius, tolRing);

        // Bubble position: displaced toward the raised side, clamped to the rim.
        float nx = clamp(roll / MAX_ANGLE, -1f, 1f);
        float ny = clamp(-pitch / MAX_ANGLE, -1f, 1f);
        float travel = radius - dp(24);
        float bx = cx + nx * travel;
        float by = cy - ny * travel; // screen y grows downward
        // Re-clamp to stay inside the circular face.
        float dx = bx - cx, dy = by - cy;
        float dist = (float) Math.hypot(dx, dy);
        if (dist > travel && dist > 0) {
            bx = cx + dx / dist * travel;
            by = cy + dy / dist * travel;
        }

        float bubbleR = dp(22);
        int bubbleColor = isLevel ? cAccent : cText2;

        // Soft glow.
        bubbleGlow.setColor((bubbleColor & 0x00FFFFFF) | 0x33000000);
        canvas.drawCircle(bx, by, bubbleR + dp(6), bubbleGlow);
        // Bubble body.
        bubble.setColor((bubbleColor & 0x00FFFFFF) | 0xCC000000);
        canvas.drawCircle(bx, by, bubbleR, bubble);
        // Bubble outline.
        bubbleRing.setColor(bubbleColor);
        bubbleRing.setStrokeWidth(dp(2));
        canvas.drawCircle(bx, by, bubbleR, bubbleRing);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
