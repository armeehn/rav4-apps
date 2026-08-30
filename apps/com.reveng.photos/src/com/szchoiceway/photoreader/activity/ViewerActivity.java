package com.szchoiceway.photoreader.activity;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;

import com.reveng.photos.R;

import java.io.InputStream;
import java.util.ArrayList;

/**
 * Full-screen image viewer with pinch-to-zoom, pan, double-tap zoom, and
 * swipe (fling) between images. Matrix-based; no external libraries.
 */
public class ViewerActivity extends Activity {

    private ImageView image;
    private final Matrix matrix = new Matrix();
    private ScaleGestureDetector scaleDet;
    private GestureDetector gestureDet;
    private float minScale = 1f, curScale = 1f, maxScale = 6f;
    private final ArrayList<Uri> uris = new ArrayList<>();
    private int index = 0;
    private Bitmap current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        image = findViewById(R.id.image);

        String[] arr = getIntent().getStringArrayExtra("uris");
        if (arr != null) {
            for (String s : arr) uris.add(Uri.parse(s));
            index = getIntent().getIntExtra("index", 0);
        } else if (getIntent().getData() != null) {
            uris.add(getIntent().getData());
        }

        scaleDet = new ScaleGestureDetector(this, new ScaleListener());
        gestureDet = new GestureDetector(this, new GestureListener());
        image.setOnTouchListener((View v, MotionEvent e) -> {
            scaleDet.onTouchEvent(e);
            gestureDet.onTouchEvent(e);
            return true;
        });

        if (!uris.isEmpty()) showAt(index);
        else finish();
    }

    private void showAt(int i) {
        if (i < 0 || i >= uris.size()) return;
        index = i;
        current = decodeToScreen(uris.get(i));
        image.setImageBitmap(current);
        image.post(this::fitCenter);
    }

    /** Scale the bitmap to fit the screen, centered — the reset/home transform. */
    private void fitCenter() {
        if (current == null) return;
        float vw = image.getWidth(), vh = image.getHeight();
        float bw = current.getWidth(), bh = current.getHeight();
        if (vw == 0 || bh == 0) return;
        minScale = Math.min(vw / bw, vh / bh);
        curScale = minScale;
        matrix.reset();
        matrix.postScale(minScale, minScale);
        matrix.postTranslate((vw - bw * minScale) / 2f, (vh - bh * minScale) / 2f);
        image.setImageMatrix(matrix);
    }

    private void clampPan() {
        RectF r = new RectF(0, 0, current.getWidth(), current.getHeight());
        matrix.mapRect(r);
        float vw = image.getWidth(), vh = image.getHeight();
        float dx = 0, dy = 0;
        if (r.width() <= vw) dx = (vw - r.width()) / 2f - r.left;
        else { if (r.left > 0) dx = -r.left; else if (r.right < vw) dx = vw - r.right; }
        if (r.height() <= vh) dy = (vh - r.height()) / 2f - r.top;
        else { if (r.top > 0) dy = -r.top; else if (r.bottom < vh) dy = vh - r.bottom; }
        matrix.postTranslate(dx, dy);
        image.setImageMatrix(matrix);
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector d) {
            float f = d.getScaleFactor();
            float next = curScale * f;
            if (next < minScale) f = minScale / curScale;
            else if (next > maxScale) f = maxScale / curScale;
            curScale *= f;
            matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
            clampPan();
            return true;
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (curScale > minScale * 1.01f) { matrix.postTranslate(-dx, -dy); clampPan(); }
            return true;
        }
        @Override public boolean onDoubleTap(MotionEvent e) {
            if (curScale > minScale * 1.05f) fitCenter();
            else { float f = (minScale * 3f) / curScale; curScale *= f;
                   matrix.postScale(f, f, e.getX(), e.getY()); clampPan(); }
            return true;
        }
        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            // only page when not zoomed in
            if (curScale <= minScale * 1.05f && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 800) {
                if (vx < 0 && index < uris.size() - 1) showAt(index + 1);
                else if (vx > 0 && index > 0) showAt(index - 1);
                return true;
            }
            return false;
        }
    }

    /** Decode downsampled to roughly the display size to keep memory sane. */
    private Bitmap decodeToScreen(Uri uri) {
        try {
            int screen = Math.max(getResources().getDisplayMetrics().widthPixels,
                                  getResources().getDisplayMetrics().heightPixels);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, o);
            }
            int sample = 1, longest = Math.max(o.outWidth, o.outHeight);
            while (longest / sample > screen * 2) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
