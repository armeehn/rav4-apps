package com.reveng.sketch;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Clean-room standalone Sketch (drawing pad) for the Toyota RAV4 GT6 head unit.
 *
 * A top toolbar carries a colour palette, a small/medium/large brush selector,
 * an eraser toggle, and undo / clear / save actions; the rest of the screen is a
 * full-bleed {@link DrawView} the user draws on with a finger. Saving flattens
 * the strokes to a PNG in the app's external Pictures directory (no storage
 * permission required) and, on API 29+, also publishes a copy to the shared
 * gallery via {@link MediaStore}. Pure {@code android.*}: no AndroidX.
 */
public class SketchActivity extends Activity {

    private DrawView canvas;
    private ImageButton eraserBtn;

    // Palette swatches keyed to their colour, so we can redraw the selection ring.
    private final int[] paletteColors = new int[7];
    private final View[] swatches = new View[7];
    private int accentColor, surfaceColor, strokeColor, text2Color;

    // Brush sizes in dp: small / medium / large.
    private static final float[] SIZE_DP = { 4f, 10f, 20f };
    private final TextView[] sizeChips = new TextView[3];
    private int selectedSize = 1; // medium

    private int selectedSwatch = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        canvas = (DrawView) findViewById(R.id.canvas);
        eraserBtn = (ImageButton) findViewById(R.id.eraser);

        accentColor  = getResources().getColor(R.color.accent);
        surfaceColor = getResources().getColor(R.color.surface2);
        strokeColor  = getResources().getColor(R.color.stroke);
        text2Color   = getResources().getColor(R.color.text2);

        paletteColors[0] = accentColor;                 // accent
        paletteColors[1] = Color.WHITE;                 // white
        paletteColors[2] = Color.parseColor("#111111"); // near-black (visible ring)
        paletteColors[3] = Color.parseColor("#FF5A5F"); // red
        paletteColors[4] = Color.parseColor("#3DDC84"); // green
        paletteColors[5] = Color.parseColor("#4C9AFF"); // blue
        paletteColors[6] = Color.parseColor("#FFD54A"); // yellow

        buildPalette();
        buildSizes();
        applyBrush();

        eraserBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleEraser(); }
        });
        findViewById(R.id.undo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { canvas.undo(); }
        });
        findViewById(R.id.clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { canvas.clear(); }
        });
        findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveDrawing(); }
        });
    }

    // --- Toolbar construction -------------------------------------------------

    private void buildPalette() {
        LinearLayout palette = (LinearLayout) findViewById(R.id.palette);
        int size = dp(34);
        int margin = dp(4);
        for (int i = 0; i < paletteColors.length; i++) {
            final int idx = i;
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            sw.setLayoutParams(lp);
            sw.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectSwatch(idx); }
            });
            swatches[i] = sw;
            palette.addView(sw);
        }
        renderSwatches();
    }

    private void selectSwatch(int idx) {
        selectedSwatch = idx;
        canvas.setPenColor(paletteColors[idx]);
        setEraserActive(false);
        renderSwatches();
    }

    /** Repaint each swatch: filled circle, accent ring on the selected one. */
    private void renderSwatches() {
        for (int i = 0; i < swatches.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(paletteColors[i]);
            boolean sel = (i == selectedSwatch) && !canvas.isEraser();
            if (sel) {
                g.setStroke(dp(3), accentColor);
            } else {
                // Subtle outline so white/black swatches read on the surface.
                g.setStroke(dp(1), strokeColor);
            }
            swatches[i].setBackground(g);
        }
    }

    private void buildSizes() {
        LinearLayout sizes = (LinearLayout) findViewById(R.id.sizes);
        String[] labels = { "S", "M", "L" };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextColor(getResources().getColor(R.color.text));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            chip.setGravity(Gravity.CENTER);
            int s = dp(40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(dp(3), 0, dp(3), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectSize(idx); }
            });
            sizeChips[i] = chip;
            sizes.addView(chip);
        }
        renderSizes();
    }

    private void selectSize(int idx) {
        selectedSize = idx;
        applyBrush();
        renderSizes();
    }

    private void renderSizes() {
        for (int i = 0; i < sizeChips.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(12));
            boolean sel = i == selectedSize;
            g.setColor(sel ? accentColor : surfaceColor);
            g.setStroke(dp(1), sel ? accentColor : strokeColor);
            sizeChips[i].setBackground(g);
            sizeChips[i].setTextColor(sel
                    ? Color.WHITE : getResources().getColor(R.color.text2));
        }
    }

    private void applyBrush() {
        canvas.setPenWidth(dp((int) SIZE_DP[selectedSize]));
    }

    private void toggleEraser() {
        setEraserActive(!canvas.isEraser());
    }

    private void setEraserActive(boolean on) {
        canvas.setEraser(on);
        eraserBtn.setBackgroundResource(on ? R.drawable.btn_accent : R.drawable.btn_icon);
        if (!on) {
            // Restore the previously chosen pen colour.
            canvas.setPenColor(paletteColors[selectedSwatch]);
        }
        renderSwatches();
    }

    // --- Save -----------------------------------------------------------------

    private void saveDrawing() {
        if (canvas.isEmpty()) {
            toast("Nothing to save yet");
            return;
        }
        Bitmap bmp = canvas.exportBitmap();
        String name = "sketch_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";

        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir != null && !dir.exists()) dir.mkdirs();
        File out = new File(dir, name);
        try {
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
            return;
        }

        // Best-effort: also publish to the shared gallery so it shows in Photos.
        boolean inGallery = publishToGallery(bmp, name);

        toast("Saved: " + out.getAbsolutePath() + (inGallery ? "  (+gallery)" : ""));
    }

    /** Insert a copy into MediaStore.Images (API 29+). Returns true on success. */
    private boolean publishToGallery(Bitmap bmp, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Sketch");
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return false;
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) return false;
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
