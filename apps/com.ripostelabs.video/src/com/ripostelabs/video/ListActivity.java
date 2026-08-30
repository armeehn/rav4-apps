package com.ripostelabs.video;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.ripostelabs.design.Palette;

/**
 * Clean-room video library entry point. Lists device videos from MediaStore as a
 * scrollable list of cards (thumbnail + title + duration chip) and opens the
 * full-screen {@link PlayerActivity} on tap. Also handles an incoming ACTION_VIEW
 * video intent.
 */
public class ListActivity extends Activity {

    private static final int REQ_PERM = 1;

    /** One row's worth of metadata. */
    static final class Item {
        Uri uri;
        String title;
        long durationMs;
        long id;
    }

    private final ArrayList<Item> videos = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LruCache<Long, Bitmap> cache;
    private ListView list;
    private View empty;
    private Button grantBtn;
    private TextView count;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);

        // ACTION_VIEW of a single video -> jump straight into the player
        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            Intent v = new Intent(this, PlayerActivity.class);
            v.setData(in.getData());
            startActivity(v);
            finish();
            return;
        }

        setContentView(R.layout.activity_list);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        grantBtn = findViewById(R.id.grant);
        count = findViewById(R.id.count);

        int max = (int) (Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<Long, Bitmap>(max) {
            @Override protected int sizeOf(Long key, Bitmap b) { return b.getByteCount(); }
        };

        adapter = new RowAdapter();
        list.setAdapter(adapter);

        grantBtn.setOnClickListener(v -> requestPermissions(new String[]{ perm() }, REQ_PERM));
        list.setOnItemClickListener((AdapterView<?> p, View vw, int pos, long id) -> {
            String[] arr = new String[videos.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = videos.get(i).uri.toString();
            Intent v = new Intent(this, PlayerActivity.class);
            v.putExtra("uris", arr);
            v.putExtra("index", pos);
            v.putExtra("title", videos.get(pos).title);
            startActivity(v);
        });

        if (hasPerm()) loadVideos();
        else requestPermissions(new String[]{ perm() }, REQ_PERM);
    }

    private String perm() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPerm() {
        return checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            grantBtn.setVisibility(View.GONE);
            loadVideos();
        } else {
            showEmpty(true);
        }
    }

    private void loadVideos() {
        io.execute(() -> {
            ArrayList<Item> found = new ArrayList<>();
            String[] proj = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
            };
            try (Cursor c = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int titleCol = c.getColumnIndex(MediaStore.Video.Media.TITLE);
                    int nameCol = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                    while (c.moveToNext()) {
                        Item it = new Item();
                        it.id = c.getLong(idCol);
                        it.uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.id);
                        String t = titleCol >= 0 ? c.getString(titleCol) : null;
                        if (t == null || t.trim().isEmpty()) {
                            t = nameCol >= 0 ? c.getString(nameCol) : null;
                        }
                        it.title = (t == null || t.trim().isEmpty()) ? ("Video " + it.id) : t;
                        it.durationMs = durCol >= 0 ? c.getLong(durCol) : 0;
                        found.add(it);
                    }
                }
            } catch (Exception ignored) { }
            ui.post(() -> {
                videos.clear();
                videos.addAll(found);
                showEmpty(videos.isEmpty());
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        grantBtn.setVisibility(show && !hasPerm() ? View.VISIBLE : View.GONE);
        int n = videos.size();
        count.setText(n == 0 ? "" : (n == 1 ? "1 video" : n + " videos"));
    }

    static String fmtDuration(long ms) {
        if (ms <= 0) return "";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return videos.size(); }
        @Override public Object getItem(int p) { return videos.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(ListActivity.this)
                        .inflate(R.layout.list_item, parent, false);
                // clip the thumbnail frame to its rounded-corner background
                View frame = v.findViewById(R.id.thumb_frame);
                frame.setClipToOutline(true);
            }
            Item it = videos.get(position);
            TextView title = v.findViewById(R.id.title);
            TextView dur = v.findViewById(R.id.duration);
            final ImageView thumb = v.findViewById(R.id.thumb);
            title.setText(it.title);
            String d = fmtDuration(it.durationMs);
            dur.setText(d.isEmpty() ? "Video" : d);

            thumb.setImageDrawable(null);
            thumb.setTag(it.id);
            Bitmap cached = cache.get(it.id);
            if (cached != null) {
                thumb.setImageBitmap(cached);
            } else {
                final long wantId = it.id;
                final Uri uri = it.uri;
                io.execute(() -> {
                    Bitmap bm = loadThumb(wantId, uri);
                    if (bm == null) return;
                    cache.put(wantId, bm);
                    ui.post(() -> {
                        Object tag = thumb.getTag();
                        if (tag != null && (Long) tag == wantId) thumb.setImageBitmap(bm);
                    });
                });
            }
            return v;
        }
    }

    /** Thumbnail via the API 29+ content resolver loadThumbnail, with a legacy fallback. */
    private Bitmap loadThumb(long id, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(
                        uri, new Size(320, 180), new CancellationSignal());
            }
        } catch (Exception ignored) { }
        try {
            return MediaStore.Video.Thumbnails.getThumbnail(
                    getContentResolver(), id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null);
        } catch (Exception ignored) { }
        return null;
    }
}
