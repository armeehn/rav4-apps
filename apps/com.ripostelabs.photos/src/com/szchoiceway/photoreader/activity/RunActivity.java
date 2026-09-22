package com.szchoiceway.photoreader.activity;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.ripostelabs.photos.R;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.ripostelabs.design.Palette;
import com.ripostelabs.design.PermissionGate;

/**
 * Clean-room gallery entry point. Lists device images from MediaStore in a grid and
 * opens the full-screen zoomable {@link ViewerActivity} on tap. Also handles an
 * incoming ACTION_VIEW image intent by going straight to the viewer.
 */
public class RunActivity extends Activity {

    /** The media-read permission, asked and re-asked through the suite's one gate. */
    private PermissionGate gate;
    private static final int COLUMNS = 6;
    private final ArrayList<Uri> images = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LruCache<Uri, Bitmap> cache;
    private GridView grid;
    private View empty;
    private Button grantBtn;
    private View emptyHint;
    private TextView emptyText;
    private TextView count;
    private int cornerPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);

        // ACTION_VIEW of a single image -> jump straight into the viewer
        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            Intent v = new Intent(this, ViewerActivity.class);
            v.setData(in.getData());
            startActivity(v);
            finish();
            return;
        }

        setContentView(R.layout.activity_run);
        grid = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        grantBtn = findViewById(R.id.grant);
        emptyHint = findViewById(R.id.empty_hint);
        emptyText = findViewById(R.id.empty_text);
        count = findViewById(R.id.count);

        cornerPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics());

        int max = (int) (Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<Uri, Bitmap>(max) {
            @Override protected int sizeOf(Uri key, Bitmap b) { return b.getByteCount(); }
        };

        gate = PermissionGate.of(this, new String[]{ perm() }, grantBtn, new PermissionGate.Listener() {
            @Override public void onGranted() { loadImages(); }
            @Override public void onDenied() { showEmpty(true); }
        });
        grid.setOnItemClickListener((AdapterView<?> p, View vw, int pos, long id) -> {
            String[] arr = new String[images.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = images.get(i).toString();
            Intent v = new Intent(this, ViewerActivity.class);
            v.putExtra("uris", arr);
            v.putExtra("index", pos);
            startActivity(v);
        });

        gate.request();   // granted → loadImages() at once
    }

    private String perm() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (!gate.onResult(req, p, r)) {
            super.onRequestPermissionsResult(req, p, r);
        }
    }

    private void loadImages() {
        io.execute(() -> {
            ArrayList<Uri> found = new ArrayList<>();
            String[] proj = { MediaStore.Images.Media._ID };
            try (Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (c.moveToNext()) {
                        found.add(ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)));
                    }
                }
            }
            ui.post(() -> {
                images.clear();
                images.addAll(found);
                showEmpty(images.isEmpty());
                updateCount();
                grid.setAdapter(new ThumbAdapter());
            });
        });
    }

    private void updateCount() {
        if (count == null) return;
        if (images.isEmpty()) {
            count.setText("");
            return;
        }

        count.setText(images.size() == 1 ? getString(R.string.photo_count_one)
                : getString(R.string.photo_count, images.size()));
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        grid.setVisibility(show ? View.GONE : View.VISIBLE);
        // The hint asks for storage permission; with it granted, an empty grid just means no photos.
        int needPerm = show && !gate.granted() ? View.VISIBLE : View.GONE;
        grantBtn.setVisibility(needPerm);
        emptyHint.setVisibility(needPerm);
        // "No photos found" under a Grant button states a fact that is not the reason.
        emptyText.setText(gate.granted() ? R.string.empty_no_images : R.string.need_permission_title);
        if (show) updateCount();
    }

    private final class ThumbAdapter extends BaseAdapter {
        @Override public int getCount() { return images.size(); }
        @Override public Object getItem(int p) { return images.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView iv = (ImageView) convertView;
            if (iv == null) {
                iv = new ImageView(RunActivity.this);
                int side = Math.max(1, parent.getWidth() / COLUMNS);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(side, side));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                // rounded thumbnail corners
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cornerPx);
                    }
                });
                iv.setClipToOutline(true);
                iv.setBackground(getDrawable(R.drawable.bg_card));
                iv.setForeground(getDrawable(R.drawable.thumb_fg));
            }
            final Uri uri = images.get(position);
            iv.setTag(uri);
            iv.setImageDrawable(null);
            Bitmap cached = cache.get(uri);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                final ImageView target = iv;
                io.execute(() -> {
                    Bitmap bm = decodeSampled(uri, 256);
                    if (bm == null) return;
                    cache.put(uri, bm);
                    ui.post(() -> { if (uri.equals(target.getTag())) target.setImageBitmap(bm); });
                });
            }
            return iv;
        }
    }

    /** Decode an image downsampled so its longest edge is ~reqPx, to avoid OOM. */
    private Bitmap decodeSampled(Uri uri, int reqPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, o);
            }
            int sample = 1;
            int longest = Math.max(o.outWidth, o.outHeight);
            while (longest / sample > reqPx * 2) sample *= 2;
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
