package com.ripostelabs.sketch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import com.ripostelabs.design.Palette;

/**
 * Full-canvas finger-drawing surface built purely with {@link Canvas} and
 * {@link Path}. Pointer motion is smoothed with quadratic beziers (each segment
 * curves through the midpoint of consecutive touch samples) so freehand strokes
 * come out rounded instead of jagged. Every finished stroke is kept in a list
 * together with its colour and width, which makes undo and full re-rendering
 * (for PNG export) trivial. Pure {@code android.*}: no AndroidX, no libraries.
 */
public class DrawView extends View {

    /** One committed pen stroke: its geometry plus how it should be painted. */
    private static class Stroke {
        final Path path;
        final int color;
        final float width;
        Stroke(Path p, int c, float w) { path = p; color = c; width = w; }
    }

    private final List<Stroke> strokes = new ArrayList<Stroke>();

    // The stroke currently under the finger (null when not drawing).
    private Path activePath;
    private float lastX, lastY;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int bgColor;
    private int penColor;
    private float penWidth;
    private boolean eraser;

    // Touch tolerance before we register movement, in px.
    private static final float TOUCH_TOLERANCE = 3f;

    public DrawView(Context c) { super(c); init(c); }
    public DrawView(Context c, AttributeSet a) { super(c, a); init(c); }
    public DrawView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        bgColor  = Palette.color(c, R.color.canvas_dark);
        penColor = Palette.color(c, R.color.accent);
        penWidth = dp(6);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(penWidth);
        stroke.setColor(penColor);
    }

    // --- Public tool controls -------------------------------------------------

    public void setPenColor(int color) {
        eraser = false;
        penColor = color;
    }

    public void setPenWidth(float widthPx) { penWidth = widthPx; }

    public void setEraser(boolean on) { eraser = on; }

    public boolean isEraser() { return eraser; }

    public int getPenColor() { return penColor; }

    public void setBackgroundColorInt(int color) {
        bgColor = color;
        invalidate();
    }

    public int getBackgroundColorInt() { return bgColor; }

    /** Remove the most recently drawn stroke. */
    public void undo() {
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            invalidate();
        }
    }

    /** Discard every stroke. */
    public void clear() {
        strokes.clear();
        activePath = null;
        invalidate();
    }

    public boolean isEmpty() { return strokes.isEmpty(); }

    // --- Rendering ------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(bgColor);
        paintStrokes(canvas);
        if (activePath != null) {
            applyPaint(currentColor(), penWidth);
            canvas.drawPath(activePath, stroke);
        }
    }

    private void paintStrokes(Canvas canvas) {
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            applyPaint(s.color, s.width);
            canvas.drawPath(s.path, stroke);
        }
    }

    private void applyPaint(int color, float width) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
    }

    /** Erasing simply paints with the background colour. */
    private int currentColor() { return eraser ? bgColor : penColor; }

    /**
     * Flatten every stroke onto an opaque {@link Bitmap} the size of this view,
     * ready to be saved as a PNG. The live view is not modified.
     */
    public Bitmap exportBitmap() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(bgColor);
        paintStrokes(c);
        return bmp;
    }

    // --- Touch handling -------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePath = new Path();
                activePath.moveTo(x, y);
                // A dot: a zero-length line so a single tap still leaves a mark.
                activePath.lineTo(x, y);
                lastX = x;
                lastY = y;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activePath == null) return true;
                float dx = Math.abs(x - lastX);
                float dy = Math.abs(y - lastY);
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    // Quadratic bezier: control point at the previous sample,
                    // ending at the midpoint -> smooth, continuous curve.
                    activePath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f);
                    lastX = x;
                    lastY = y;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activePath != null) {
                    activePath.lineTo(lastX, lastY);
                    strokes.add(new Stroke(activePath, currentColor(), penWidth));
                    activePath = null;
                    invalidate();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
