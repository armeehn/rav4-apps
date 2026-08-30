package com.reveng.music;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.reveng.design.MediaCitizen;
import com.reveng.design.Palette;

/**
 * Clean-room local music player entry point. Lists all device audio from
 * MediaStore in a plain ListView and plays tracks with android.media.MediaPlayer.
 * A persistent bottom now-playing card shows the current track with play/pause,
 * prev, next and a seekable progress bar. Pure framework only, no AndroidX.
 */
public class MainActivity extends Activity
        implements MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener {

    private static final int REQ_PERM = 1;

    private static final class Track {
        final long id;
        final String title;
        final String artist;
        final long duration;
        final Uri uri;
        Track(long id, String title, String artist, long duration, Uri uri) {
            this.id = id; this.title = title; this.artist = artist;
            this.duration = duration; this.uri = uri;
        }
    }

    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ListView list;
    private View empty;
    private TextView emptyText;
    private TextView count;
    private Button grantBtn;

    private TextView nowTitle, nowArtist, posTime, durTime;
    private ImageButton btnPrev, btnPlay, btnNext;
    private SeekBar seek;

    private MediaPlayer player;

    /**
     * v0.6.1-0.6.3 — audio focus, the session the launcher's now-playing card reads, and the
     * steering-wheel media buttons. Created lazily on first playback so merely opening the
     * library does not claim the cabin's audio.
     */
    private MediaCitizen citizen;
    private int current = -1;
    /** Consecutive tracks that would not open; see {@link #skipUnplayable}. */
    private int skips = 0;
    private boolean prepared = false;
    private boolean userSeeking = false;
    private TrackAdapter adapter;

    // resolved palette (from shared design system)
    private int cAccent, cAccentDim, cSurface2, cText, cText2;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && player.isPlaying() && !userSeeking) {
                int pos = player.getCurrentPosition();
                seek.setProgress(pos);
                posTime.setText(fmt(pos));
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        cAccent = Palette.color(this, R.color.accent);
        cAccentDim = Palette.color(this, R.color.accent_dim);
        cSurface2 = Palette.color(this, R.color.surface2);
        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.empty_text);
        count = findViewById(R.id.count);
        grantBtn = findViewById(R.id.grant);

        nowTitle = findViewById(R.id.now_title);
        nowArtist = findViewById(R.id.now_artist);
        posTime = findViewById(R.id.pos_time);
        durTime = findViewById(R.id.dur_time);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        seek = findViewById(R.id.seek);

        adapter = new TrackAdapter();
        list.setAdapter(adapter);

        grantBtn.setOnClickListener(v -> requestPermissions(new String[]{ perm() }, REQ_PERM));

        list.setOnItemClickListener((AdapterView<?> p, View vw, int pos, long id) -> playAt(pos));

        btnPlay.setOnClickListener(v -> togglePlay());
        btnPrev.setOnClickListener(v -> { if (!tracks.isEmpty()) playAt((current <= 0 ? tracks.size() : current) - 1); });
        btnNext.setOnClickListener(v -> playNext());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) posTime.setText(fmt(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (player != null && prepared) player.seekTo(sb.getProgress());
            }
        });

        ui.postDelayed(tick, 500);

        if (hasPerm()) loadTracks();
        else requestPermissions(new String[]{ perm() }, REQ_PERM);
    }

    private String perm() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPerm() {
        return checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            grantBtn.setVisibility(View.GONE);
            loadTracks();
        } else {
            emptyText.setText(R.string.need_permission);
            showEmpty(true);
        }
    }

    private void loadTracks() {
        io.execute(() -> {
            ArrayList<Track> found = new ArrayList<>();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            try (Cursor c = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int tiCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int arCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int duCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String ti = c.getString(tiCol);
                        String ar = c.getString(arCol);
                        long du = c.getLong(duCol);
                        if (ti == null || ti.isEmpty()) ti = "(unknown title)";
                        if (ar == null || ar.isEmpty() || "<unknown>".equals(ar)) ar = "Unknown artist";
                        Uri uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                        found.add(new Track(id, ti, ar, du, uri));
                    }
                }
            } catch (Exception e) {
                // ignore; treated as empty
            }
            ui.post(() -> {
                tracks.clear();
                tracks.addAll(found);
                adapter.notifyDataSetChanged();
                count.setText(getString(R.string.tracks_count, tracks.size()));
                emptyText.setText(R.string.empty_no_tracks);
                showEmpty(tracks.isEmpty());
            });
        });
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        grantBtn.setVisibility(show && !hasPerm() ? View.VISIBLE : View.GONE);
    }

    /** The transport the system and the wheel drive; each action is what the UI button does. */
    private MediaCitizen citizen() {
        if (citizen == null) {
            citizen = MediaCitizen.attach(this, "music", new MediaCitizen.Transport() {
                @Override public void onPlay() {
                    if (player != null && prepared && !player.isPlaying()) togglePlay();
                }

                @Override public void onPause() {
                    if (player != null && prepared && player.isPlaying()) togglePlay();
                }

                @Override public void onNext() { playNext(); }

                @Override public void onPrevious() { playPrevious(); }

                @Override public void onStop() {
                    if (player != null && prepared && player.isPlaying()) togglePlay();
                }

                @Override public void onDuck(boolean duck) {
                    if (player == null) return;
                    float v = MediaCitizen.duckVolume(duck);
                    try { player.setVolume(v, v); } catch (Exception ignored) {}
                }
            });
        }
        return citizen;
    }

    /** Publish what is playing, so the launcher card and the wheel stay in step with the UI. */
    private void publishState() {
        if (citizen == null) return;
        boolean playing = player != null && prepared && player.isPlaying();
        int pos = 0;
        if (player != null && prepared) {
            try { pos = player.getCurrentPosition(); } catch (Exception ignored) {}
        }
        citizen.setState(playing, pos);
    }

    private void playPrevious() {
        if (tracks.isEmpty()) return;
        playAt((current - 1 + tracks.size()) % tracks.size());
    }

    private void playAt(int index) {
        if (index < 0 || index >= tracks.size()) return;

        // Focus BEFORE prepareAsync. Refused focus means something else owns the cabin (a call,
        // usually) and starting anyway would talk over it — but by the time an asked-afterwards
        // refusal is seen, preparation is already in flight and onPrepared starts the player
        // regardless, so the early return would not actually keep us quiet.
        if (!citizen().takeFocus(MediaCitizen.Focus.MEDIA)) {
            return;
        }

        current = index;
        Track t = tracks.get(index);
        prepared = false;
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setOnCompletionListener(this);
                player.setOnPreparedListener(this);
            } else {
                player.reset();
            }
            player.setDataSource(this, t.uri);
            player.prepareAsync();
        } catch (Exception e) {
            skipUnplayable();
            return;
        }
        skips = 0;
        citizen().setMetadata(t.title, t.artist, t.duration);

        nowTitle.setText(t.title);
        nowArtist.setText(t.artist);
        seek.setProgress(0);
        seek.setMax(t.duration > 0 ? (int) t.duration : 0);
        posTime.setText(fmt(0));
        durTime.setText(fmt(t.duration));
        btnPlay.setImageResource(R.drawable.ic_pause);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        int dur = mp.getDuration();
        if (dur > 0) { seek.setMax(dur); durTime.setText(fmt(dur)); }
        mp.start();
        btnPlay.setImageResource(R.drawable.ic_pause);
        publishState();
    }

    private void togglePlay() {
        if (player == null || !prepared) {
            if (!tracks.isEmpty()) playAt(current < 0 ? 0 : current);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            btnPlay.setImageResource(R.drawable.ic_play);
        } else {
            if (!citizen().takeFocus(MediaCitizen.Focus.MEDIA)) {
                return;
            }
            player.start();
            btnPlay.setImageResource(R.drawable.ic_pause);
        }
        publishState();
    }

    private void playNext() {
        if (tracks.isEmpty()) return;
        playAt((current + 1) % tracks.size());
    }

    /**
     * A track that would not open — the file is gone since the last media scan, say. Move on,
     * but only until every entry has been tried: playNext wraps, so a library whose files are
     * all missing would otherwise recurse playAt -> playNext -> playAt until the stack blows.
     */
    private void skipUnplayable() {
        if (++skips < tracks.size()) {
            playNext();
            return;
        }
        skips = 0;
        citizen().releaseFocus();
        nowTitle.setText(R.string.none_playable);
        nowArtist.setText("");
        btnPlay.setImageResource(R.drawable.ic_play);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        playNext();
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        if (citizen != null) {
            citizen.release();
            citizen = null;
        }
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return tracks.size(); }
        @Override public Object getItem(int p) { return tracks.get(p); }
        @Override public long getItemId(int p) { return tracks.get(p).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView avatar;
            TextView title, artist;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                avatar = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                title = (TextView) col.getChildAt(0);
                artist = (TextView) col.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padH = dp(16);
                row.setPadding(padH, dp(8), padH, dp(8));
                row.setMinimumHeight(dp(72));

                avatar = new ImageView(MainActivity.this);
                avatar.setImageResource(R.drawable.ic_music);
                avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int ap = dp(11);
                avatar.setPadding(ap, ap, ap, ap);
                LinearLayout.LayoutParams alp =
                        new LinearLayout.LayoutParams(dp(46), dp(46));
                row.addView(avatar, alp);

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = dp(14);
                row.addView(col, clp);

                title = new TextView(MainActivity.this);
                title.setTextSize(17);
                title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                artist = new TextView(MainActivity.this);
                artist.setTextSize(13);
                artist.setTextColor(cText2);
                artist.setSingleLine(true);
                artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams arp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                arp.topMargin = dp(2);
                col.addView(artist, arp);
            }

            Track t = tracks.get(position);
            title.setText(t.title);
            artist.setText(t.artist);
            boolean active = position == current;

            // rounded avatar oval; accent when active, surface2 otherwise
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(active ? cAccentDim : cSurface2);
            avatar.setBackground(oval);
            avatar.setColorFilter(active ? cAccent : cText2);

            title.setTextColor(active ? cAccent : cText);

            // rounded highlight background for the active row
            if (active) {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp(16));
                bg.setColor(cAccentDim);
                row.setBackground(bg);
            } else {
                row.setBackground(null);
            }
            return row;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
