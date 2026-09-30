package com.ripostelabs.video;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
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

import com.ripostelabs.design.PermissionGate;

import java.util.ArrayList;
import java.util.List;
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

    /** The media-read permission, asked and re-asked through the suite's one gate. */
    private PermissionGate gate;

    /** One row's worth of metadata. */
    static final class Item {
        Uri uri;
        String title;
        long durationMs;
        long id;
        /** File path, for the folder view. */
        String path;
    }

    /** What the list shows: every video, the folders, or one folder's videos. */
    private enum Browse {
        VIDEOS,
        FOLDERS,
        FOLDER,
    }

    private Browse browse = Browse.VIDEOS;
    private List<Folders.Folder> folderList = new ArrayList<>();
    /** The folder shown under {@link Browse#FOLDER}. */
    private Folders.Folder openFolder;
    private Button btnBrowse;

    private final ArrayList<Item> videos = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LruCache<Long, Bitmap> cache;
    private ListView list;
    private View empty;
    private Button grantBtn;
    private TextView count;
    private TextView emptyText;
    private TextView emptyHint;
    private RowAdapter adapter;
    private Button continueBtn;

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
        emptyText = findViewById(R.id.empty_text);
        emptyHint = findViewById(R.id.empty_hint);
        grantBtn = findViewById(R.id.grant);
        gate = PermissionGate.of(this, new String[]{ perm() }, grantBtn, new PermissionGate.Listener() {
            @Override public void onGranted() { loadVideos(); }
            @Override public void onDenied() { showEmpty(true); }
        });
        count = findViewById(R.id.count);
        continueBtn = findViewById(R.id.continue_btn);

        int max = (int) (Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<Long, Bitmap>(max) {
            @Override protected int sizeOf(Long key, Bitmap b) { return b.getByteCount(); }
        };

        adapter = new RowAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener((AdapterView<?> p, View vw, int pos, long id) -> onRow(pos));
        btnBrowse = findViewById(R.id.btn_browse);
        btnBrowse.setOnClickListener(v -> showBrowse(browse == Browse.VIDEOS ? Browse.FOLDERS : Browse.VIDEOS));

        gate.request();   // granted → loadVideos() at once
    }

    private void play(int pos, long startMs) {
        play(uriStrings(), pos, videos.get(pos).title, startMs);
    }

    /** Open the player on {@code uris}, the playlist its loop modes walk. */
    private void play(String[] uris, int pos, String title, long startMs) {
        Intent v = new Intent(this, PlayerActivity.class);
        v.putExtra("uris", uris);
        v.putExtra("index", pos);
        v.putExtra("title", title);
        v.putExtra(ResumeSpot.EXTRA_START, startMs);
        startActivity(v);
    }

    private String[] uriStrings() {
        String[] arr = new String[videos.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = videos.get(i).uri.toString();
        }
        return arr;
    }

    /**
     * A tap on list row {@code pos}: open a folder, or play. Inside a folder the playlist is
     * that folder, so "repeat all" loops it, as stock's folder play did.
     */
    private void onRow(int pos) {
        if (browse == Browse.FOLDERS) {
            openFolder = folderList.get(pos);
            showBrowse(Browse.FOLDER);
            return;
        }
        if (browse != Browse.FOLDER || openFolder == null) {
            play(pos, 0);
            return;
        }

        String[] uris = new String[openFolder.rows.length];
        for (int i = 0; i < uris.length; i++) {
            uris[i] = videos.get(openFolder.rows[i]).uri.toString();
        }
        play(uris, pos, videos.get(openFolder.rows[pos]).title, 0);
    }

    /** The video behind list row {@code pos} in the video views. */
    private Item videoAt(int pos) {
        return videos.get(browse == Browse.FOLDER && openFolder != null ? openFolder.rows[pos] : pos);
    }

    /** Rebuild the folder level after a load; a folder that went with its stick closes. */
    private void regroup() {
        String[] paths = new String[videos.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = videos.get(i).path;
        }
        folderList = Folders.group(paths);

        String was = openFolder == null ? null : openFolder.path;
        openFolder = null;
        for (Folders.Folder f : folderList) {
            if (f.path.equals(was)) {
                openFolder = f;
            }
        }
        if (openFolder == null && browse == Browse.FOLDER) {
            browse = Browse.FOLDERS;
        }
    }

    private void showBrowse(Browse b) {
        browse = b;
        btnBrowse.setText(b == Browse.VIDEOS ? R.string.browse_folders : R.string.browse_videos);
        showCount();
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    /** "12 videos", "3 folders", or "Trips · USB · 2 videos" inside a folder. */
    private void showCount() {
        if (browse == Browse.FOLDERS) {
            count.setText(getString(R.string.folders_count, folderList.size()));
            return;
        }
        if (browse == Browse.FOLDER && openFolder != null) {
            count.setText(openFolder.name + " · " + folderSummary(openFolder));
            return;
        }
        int n = videos.size();
        count.setText(n == 0 ? "" : (n == 1 ? "1 video" : n + " videos"));
    }

    /** "USB · 2 videos". */
    private String folderSummary(Folders.Folder f) {
        String where = getString(f.where == Folders.Where.USB ? R.string.where_usb : R.string.where_internal);
        int n = f.rows.length;
        return where + " · " + (n == 1 ? "1 video" : n + " videos");
    }

    /** Back inside a folder returns to the folders. */
    @Override
    public void onBackPressed() {
        if (browse == Browse.FOLDER) {
            showBrowse(Browse.FOLDERS);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (continueBtn != null) {
            showContinue();
        }
    }

    /**
     * Offer the video the player saved last, from where it stopped, when it is still in the
     * library and not already watched to the credits.
     */
    private void showContinue() {
        continueBtn.setVisibility(View.GONE);
        SharedPreferences prefs = getSharedPreferences(ResumeSpot.PREFS, MODE_PRIVATE);

        int row = ResumeSpot.indexOf(uriStrings(), prefs.getString(ResumeSpot.KEY_URI, null));
        if (row == ResumeSpot.GONE) {
            return;
        }
        Item it = videos.get(row);
        long at = ResumeSpot.seekTo(prefs.getLong(ResumeSpot.KEY_POS, 0), it.durationMs);
        if (at == ResumeSpot.WATCHED) {
            return;
        }

        continueBtn.setText(getString(R.string.continue_at, it.title, fmtDuration(at)));
        continueBtn.setOnClickListener(v -> play(row, at));
        continueBtn.setVisibility(View.VISIBLE);
    }

    private String perm() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (!gate.onResult(req, p, r)) {
            super.onRequestPermissionsResult(req, p, r);
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
                    MediaStore.Video.Media.DATA,
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
                        int paCol = c.getColumnIndex(MediaStore.Video.Media.DATA);
                        it.path = paCol >= 0 ? c.getString(paCol) : null;
                        found.add(it);
                    }
                }
            } catch (Exception ignored) { }
            ui.post(() -> {
                videos.clear();
                videos.addAll(found);
                regroup();
                showEmpty(videos.isEmpty());
                adapter.notifyDataSetChanged();
                showContinue();
            });
        });
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        grantBtn.setVisibility(show && !gate.granted() ? View.VISIBLE : View.GONE);
        // "No videos found" under a Grant button states a fact that is not the reason.
        boolean granted = gate.granted();
        emptyText.setText(granted ? R.string.empty_no_videos : R.string.need_permission_title);
        emptyHint.setText(granted ? R.string.empty_hint : R.string.need_permission);
        showCount();
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
        @Override public int getCount() {
            if (browse == Browse.FOLDERS) {
                return folderList.size();
            }
            if (browse == Browse.FOLDER && openFolder != null) {
                return openFolder.rows.length;
            }
            return videos.size();
        }
        @Override public Object getItem(int p) { return browse == Browse.FOLDERS ? folderList.get(p) : videoAt(p); }
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
            // A folder row wears its first video's thumbnail and a "USB · 3 videos" chip.
            Folders.Folder folder = browse == Browse.FOLDERS ? folderList.get(position) : null;
            Item it = folder != null ? videos.get(folder.rows[0]) : videoAt(position);
            TextView title = v.findViewById(R.id.title);
            TextView dur = v.findViewById(R.id.duration);
            final ImageView thumb = v.findViewById(R.id.thumb);
            title.setText(it.title);
            String d = fmtDuration(it.durationMs);
            dur.setText(d.isEmpty() ? "Video" : d);
            if (folder != null) {
                title.setText(folder.name);
                dur.setText(folderSummary(folder));
            }

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
