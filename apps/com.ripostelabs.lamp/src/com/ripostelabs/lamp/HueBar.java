package com.ripostelabs.lamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A horizontal hue strip. Drag anywhere along it to pick a fully saturated colour; the marker
 * follows the finger. Sized for a gloved thumb on the 720 px panel: no fine targets.
 */
public final class HueBar extends View {

    interface OnHueListener {
        void onHue(int rgb, boolean fromUser);
    }

    private static final int STEPS = 12;
    private static final float MARKER_STROKE_DP = 3f;
    private static final float MARKER_WIDTH_DP = 10f;

    private final Paint strip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue;
    private OnHueListener listener;

    public HueBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        marker.setStyle(Paint.Style.STROKE);
        marker.setColor(Color.WHITE);
        marker.setStrokeWidth(MARKER_STROKE_DP * getResources().getDisplayMetrics().density);
    }

    void setListener(OnHueListener l) {
        listener = l;
    }

    /** Point the marker at the hue of an existing colour without telling the listener. */
    void setColour(int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb | 0xFF000000, hsv);
        hue = hsv[0];
        invalidate();
    }

    int colour() {
        return Color.HSVToColor(new float[] {hue, 1f, 1f}) & 0xFFFFFF;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        int[] colours = new int[STEPS + 1];
        for (int i = 0; i <= STEPS; i++) {
            colours[i] = Color.HSVToColor(new float[] {360f * i / STEPS, 1f, 1f});
        }
        strip.setShader(new LinearGradient(0, 0, w, 0, colours, null, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawRect(0, 0, w, h, strip);

        float x = hue / 360f * w;
        float half = MARKER_WIDTH_DP * getResources().getDisplayMetrics().density / 2f;
        c.drawRect(x - half, 0, x + half, h, marker);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                break;
            default:
                return super.onTouchEvent(e);
        }
        getParent().requestDisallowInterceptTouchEvent(true);
        float x = Math.max(0f, Math.min(getWidth(), e.getX()));
        hue = 360f * x / getWidth();
        if (hue >= 360f) {
            hue = 359.9f;
        }
        invalidate();
        if (listener != null) {
            listener.onHue(colour(), true);
        }
        return true;
    }
}
