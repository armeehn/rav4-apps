package com.ripostelabs.music;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ripostelabs.design.PermissionGate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.ripostelabs.design.MediaCitizen;
import com.ripostelabs.design.Palette;

/**
 * Clean-room local music player entry point. Lists all device audio from
 * MediaStore in a plain ListView and plays tracks with android.media.MediaPlayer.
 * A persistent bottom now-playing card shows the current track with play/pause,
 * prev, next and a seekable progress bar. Pure framework only, no AndroidX.
 */
public class MainActivity extends Activity
        implements MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener {

    /** The media-read permission, asked and re-asked through the suite's one gate. */
    private PermissionGate gate;

    private static final class Track {
        final long id;
        final String title;
        final String artist;
        final String album;
        final long duration;
        final Uri uri;
        /** File path, to tell which volume (USB stick) the track sits on. */
        final String path;
        Track(long id, String title, String artist, String album, long duration, Uri uri, String path) {
            this.id = id; this.title = title; this.artist = artist; this.album = album;
            this.duration = duration; this.uri = uri; this.path = path;
        }
    }

    /** Prefs file and keys for the resume point (track id and position). */
    private static final String PREFS_RESUME = "resume";
    private static final String KEY_TRACK = "track_id";
    private static final String KEY_POS = "position_ms";
    private static final String KEY_MODE = "loop_mode";

    /** How often the playing position is saved, so a hard ACC cut loses at most this much. */
    private static final long SAVE_EVERY_MS = 5_000;

    /** A stick mounting fires a burst of MediaStore changes; reload once they settle. */
    private static final long RELOAD_SETTLE_MS = 1_000;

    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ListView list;
    private View empty;
    private TextView emptyText;
    private TextView count;
    private Button grantBtn;

    private TextView nowTitle, nowArtist, posTime, durTime;
    private ImageView nowArt;
    private ImageButton btnPrev, btnPlay, btnNext, btnMode;
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
    /** Where the next prepared track starts, in ms (the resume point); 0 is the top. */
    private long startFrom = 0;
    /** The saved resume point is applied once, on the first library load of a fresh start. */
    private boolean resumePending = false;
    /** Loop mode, kept across restarts like stock's SAVE_LAST_MUSIC_LOOP_MODE. */
    private PlayOrder.Mode mode = PlayOrder.Mode.ALL;
    private final Random random = new Random();
    private SharedPreferences resume;
    private ContentObserver libraryWatch;
    private BroadcastReceiver usbWatch;
    private TrackAdapter adapter;

    /** What the list shows: every track, the folders, one folder's tracks, or the starred ones. */
    private enum Browse {
        TRACKS,
        FOLDERS,
        FOLDER,
        FAVOURITES,
    }

    /** RAV4-179: starred track URIs, kept apart from the resume point. */
    private static final String PREFS_FAVOURITES = "favourites";
    private static final String KEY_STARS = "uris";
    /** GPS fixes for the parked-only keyboard: one a second is plenty at walking pace. */
    private static final long SPEED_EVERY_MS = 1_000;
    private static final float MS_TO_KMH = 3.6f;

    private SharedPreferences favourites;
    private Set<String> stars = new HashSet<>();
    /** The tags the search reads, one per track, rebuilt with the library. */
    private TrackFilter.Row[] rows = new TrackFilter.Row[0];
    /** Track indices the list shows in the track views, after favourites and search. */
    private int[] shown = new int[0];
    private String query = "";
    private EditText search;
    private ImageButton btnStar;
    private Button btnFavs;
    private DrivingState.Motion motion = DrivingState.Motion.UNKNOWN;
    private final LocationListener speedWatch = this::onFix;

    private Browse browse = Browse.TRACKS;
    private List<Folders.Folder> folderList = new ArrayList<>();
    /** The folder shown under {@link Browse#FOLDER}. */
    private Folders.Folder openFolder;
    private Button btnBrowse;

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

    /** Saves the position while playing: ACC off cuts power without an onPause. */
    private final Runnable saver = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && player.isPlaying()) {
                savePoint();
            }
            ui.postDelayed(this, SAVE_EVERY_MS);
        }
    };

    private final Runnable reload = () -> {
        if (gate.granted()) {
            loadTracks();
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
        gate = PermissionGate.of(this, new String[]{ perm() }, grantBtn, new PermissionGate.Listener() {
            @Override public void onGranted() { loadTracks(); }
            @Override public void onDenied() {
                emptyText.setText(R.string.need_permission);
                showEmpty(true);
            }
        });

        nowTitle = findViewById(R.id.now_title);
        nowArtist = findViewById(R.id.now_artist);
        nowArt = findViewById(R.id.now_art);
        posTime = findViewById(R.id.pos_time);
        durTime = findViewById(R.id.dur_time);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnMode = findViewById(R.id.btn_mode);
        seek = findViewById(R.id.seek);

        adapter = new TrackAdapter();
        list.setAdapter(adapter);


        list.setOnItemClickListener((AdapterView<?> p, View vw, int pos, long id) -> onRow(pos));
        btnBrowse = findViewById(R.id.btn_browse);
        btnBrowse.setOnClickListener(v -> showBrowse(inFolders() ? Browse.TRACKS : Browse.FOLDERS));

        // RAV4-179: favourites and search.
        favourites = getSharedPreferences(PREFS_FAVOURITES, MODE_PRIVATE);
        stars = new HashSet<>(favourites.getStringSet(KEY_STARS, new HashSet<>()));
        btnFavs = findViewById(R.id.btn_favs);
        btnFavs.setOnClickListener(v -> showBrowse(browse == Browse.FAVOURITES ? Browse.TRACKS : Browse.FAVOURITES));
        btnStar = findViewById(R.id.btn_star);
        btnStar.setOnClickListener(v -> toggleStar());
        search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { onQuery(e.toString()); }
        });

        btnPlay.setOnClickListener(v -> togglePlay());
        btnPrev.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> playNext());
        btnMode.setOnClickListener(v -> setMode(mode.onButton()));

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
        ui.postDelayed(saver, SAVE_EVERY_MS);

        // Stock parity: opening the player picks up the last track where it stopped
        // (musicplayer/MainActivity.java:70-85). A recreate keeps its own state instead.
        resume = getSharedPreferences(PREFS_RESUME, MODE_PRIVATE);
        resumePending = savedInstanceState == null;
        mode = PlayOrder.Mode.parse(resume.getString(KEY_MODE, null));
        showMode();
        showStar();
        watchLibrary();

        gate.request();   // granted → loadTracks() at once
    }

    private String perm() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (!gate.onResult(req, p, r)) {
            super.onRequestPermissionsResult(req, p, r);
        }
    }

    private void loadTracks() {
        io.execute(() -> {
            ArrayList<Track> found = new ArrayList<>();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            try (Cursor c = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int tiCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int arCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int alCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int duCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int paCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String ti = c.getString(tiCol);
                        String ar = c.getString(arCol);
                        long du = c.getLong(duCol);
                        if (ti == null || ti.isEmpty()) ti = "(unknown title)";
                        if (ar == null || ar.isEmpty() || "<unknown>".equals(ar)) ar = "Unknown artist";
                        Uri uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                        found.add(new Track(id, ti, ar, c.getString(alCol), du, uri, c.getString(paCol)));
                    }
                }
            } catch (Exception e) {
                // ignore; treated as empty
            }
            ui.post(() -> {
                adopt(found);
                regroup();
                refilter(); // the folder level may have moved under the open view
                adapter.notifyDataSetChanged();
                showCount();
                emptyText.setText(R.string.empty_no_tracks);
                showEmpty(tracks.isEmpty());
            });
        });
    }

    /**
     * Swap in a freshly loaded library. The current track is followed by id, since a rescan
     * re-sorts rows; if it has gone (its USB stick was pulled) playback stops. On the first load
     * of a fresh start the saved resume point is played.
     */
    private void adopt(ArrayList<Track> found) {
        long playingId = current >= 0 && current < tracks.size() ? tracks.get(current).id : ResumePoint.NONE;

        tracks.clear();
        tracks.addAll(found);
        long[] ids = new long[tracks.size()];
        rows = new TrackFilter.Row[tracks.size()];
        for (int i = 0; i < ids.length; i++) {
            Track t = tracks.get(i);
            ids[i] = t.id;
            rows[i] = new TrackFilter.Row(t.title, t.artist, t.album, t.uri.toString());
        }
        refilter();

        current = ResumePoint.indexOf(ids, playingId);
        if (playingId != ResumePoint.NONE && current == ResumePoint.GONE) {
            stopGone();
        }
        adapter.notifyDataSetChanged();

        if (!resumePending) {
            return;
        }
        resumePending = false;

        int saved = ResumePoint.indexOf(ids, resume.getLong(KEY_TRACK, ResumePoint.NONE));
        if (saved == ResumePoint.GONE) {
            return;
        }
        playAt(saved, ResumePoint.seekTo(resume.getLong(KEY_POS, 0), tracks.get(saved).duration));
    }

    /** The playing track left the library: stop, and clear the card and the launcher's session. */
    private void stopGone() {
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
        }
        prepared = false;
        if (citizen != null) {
            citizen.setIdle();
            citizen.releaseFocus();
        }
        nowTitle.setText(R.string.nothing_playing);
        nowArtist.setText("");
        nowArt.setVisibility(View.GONE);
        showStar();
        seek.setProgress(0);
        posTime.setText(fmt(0));
        durTime.setText(fmt(0));
        btnPlay.setImageResource(R.drawable.ic_play);
    }

    /** Reload the list when MediaStore changes or a volume mounts or ejects (USB stick). */
    private void watchLibrary() {
        libraryWatch = new ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) { reloadSoon(); }
        };
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, libraryWatch);

        usbWatch = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                stopIfOn(i);
                reloadSoon();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addDataScheme("file");
        registerReceiver(usbWatch, f);
    }

    /** A volume is going away: stop now if the playing track is on it. */
    private void stopIfOn(Intent i) {
        if (Intent.ACTION_MEDIA_MOUNTED.equals(i.getAction()) || i.getData() == null) {
            return;
        }
        if (current < 0 || current >= tracks.size()) {
            return;
        }
        if (!ResumePoint.onVolume(tracks.get(current).path, i.getData().getPath())) {
            return;
        }
        stopGone();
        current = ResumePoint.GONE;
        adapter.notifyDataSetChanged();
    }

    private void reloadSoon() {
        ui.removeCallbacks(reload);
        ui.postDelayed(reload, RELOAD_SETTLE_MS);
    }

    /** Remember the current track and position for the next start. */
    private void savePoint() {
        if (current < 0 || current >= tracks.size()) {
            return;
        }

        long pos = 0;
        if (player != null && prepared) {
            try { pos = player.getCurrentPosition(); } catch (Exception ignored) {}
        }
        resume.edit()
                .putLong(KEY_TRACK, tracks.get(current).id)
                .putLong(KEY_POS, pos)
                .apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePoint();
        stopSpeedWatch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSpeedWatch();
    }

    /**
     * RAV4-179: GPS speed for the parked-only keyboard. Without the location grant there is no
     * reading, the verdict stays UNKNOWN and the keyboard stays open, as the launcher's rule does.
     */
    private void startSpeedWatch() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, SPEED_EVERY_MS, 0f, speedWatch, Looper.getMainLooper());
        } catch (RuntimeException e) {
            // No GPS provider on this unit: no reading, keyboard open.
        }
    }

    private void stopSpeedWatch() {
        getSystemService(LocationManager.class).removeUpdates(speedWatch);
    }

    private void onFix(Location loc) {
        float kmh = loc.hasSpeed() ? loc.getSpeed() * MS_TO_KMH : Float.NaN;
        DrivingState.Motion next = DrivingState.next(motion, kmh);
        if (next == motion) {
            return;
        }
        motion = next;
        showSearchGate();
    }

    /** Typing only while parked: moving greys the field and drops the keyboard. */
    private void showSearchGate() {
        boolean allowed = DrivingState.keyboardAllowed(motion);
        search.setEnabled(allowed);
        search.setHint(allowed ? R.string.search_hint : R.string.search_parked);
        if (allowed) {
            return;
        }
        search.clearFocus();
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void onQuery(String q) {
        query = q;
        refilter();
        showCount();
        adapter.notifyDataSetChanged();
    }

    /** Star or unstar the playing track. */
    private void toggleStar() {
        if (current < 0 || current >= tracks.size()) {
            return;
        }
        stars = TrackFilter.toggle(stars, tracks.get(current).uri.toString());
        favourites.edit().putStringSet(KEY_STARS, stars).apply();
        showStar();
        if (browse == Browse.FAVOURITES) {
            onQuery(query);
        }
    }

    private void showStar() {
        boolean on = current >= 0 && current < tracks.size() && stars.contains(tracks.get(current).uri.toString());
        btnStar.setImageResource(on ? R.drawable.ic_star : R.drawable.ic_star_border);
        btnStar.setColorFilter(on ? cAccent : cText);
        btnStar.setContentDescription(getString(on ? R.string.star_remove : R.string.star_add));
    }

    /** The track rows for the current view: its base set, then the search. */
    private void refilter() {
        int[] base;
        if (browse == Browse.FOLDER && openFolder != null) {
            base = openFolder.rows;
        } else {
            base = new int[rows.length];
            for (int i = 0; i < base.length; i++) {
                base[i] = i;
            }
            if (browse == Browse.FAVOURITES) {
                base = TrackFilter.starred(rows, base, stars);
            }
        }
        shown = TrackFilter.search(rows, base, query);
    }

    private boolean inFolders() {
        return browse == Browse.FOLDERS || browse == Browse.FOLDER;
    }

    /** Rebuild the folder level after a reload; a folder that went with its stick closes. */
    private void regroup() {
        String[] paths = new String[tracks.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = tracks.get(i).path;
        }
        folderList = Folders.group(paths);

        if (openFolder == null) {
            return;
        }
        String was = openFolder.path;
        openFolder = null;
        for (Folders.Folder f : folderList) {
            if (f.path.equals(was)) {
                openFolder = f;
            }
        }
        if (openFolder == null && browse == Browse.FOLDER) {
            showBrowse(Browse.FOLDERS);
        }
    }

    private void showBrowse(Browse b) {
        browse = b;
        btnBrowse.setText(inFolders() ? R.string.browse_tracks : R.string.browse_folders);
        btnFavs.setText(b == Browse.FAVOURITES ? R.string.browse_tracks : R.string.browse_favourites);
        // The folder list has no tags to search; the field comes back with the tracks.
        search.setVisibility(b == Browse.FOLDERS ? View.INVISIBLE : View.VISIBLE);
        refilter();
        showCount();
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    /** "12 tracks", "3 folders", or "Jazz · USB · 2 tracks" inside a folder. */
    private void showCount() {
        if (browse == Browse.FOLDERS) {
            count.setText(getString(R.string.folders_count, folderList.size()));
            return;
        }
        if (browse == Browse.FOLDER && openFolder != null) {
            count.setText(folderLine(openFolder));
            return;
        }
        if (browse == Browse.FAVOURITES) {
            count.setText(shown.length == 0 && query.isEmpty() ? getString(R.string.favourites_none)
                    : getString(R.string.favourites_count, shown.length));
            return;
        }
        count.setText(shown.length == 1 ? getString(R.string.tracks_count_one)
                : getString(R.string.tracks_count, shown.length));
    }

    private String folderLine(Folders.Folder f) {
        String where = getString(f.where == Folders.Where.USB ? R.string.where_usb : R.string.where_internal);
        String n = f.rows.length == 1 ? getString(R.string.tracks_count_one)
                : getString(R.string.tracks_count, f.rows.length);
        return f.name + " · " + where + " · " + n;
    }

    /** A tap on list row {@code pos}: open a folder, or play a track. */
    private void onRow(int pos) {
        if (browse == Browse.FOLDERS) {
            openFolder = folderList.get(pos);
            showBrowse(Browse.FOLDER);
            return;
        }
        playAt(trackAt(pos));
    }

    /** The track index behind list row {@code pos} in the track views. */
    private int trackAt(int pos) {
        return shown[pos];
    }

    /** Back inside a folder returns to the folders, as stock's file list did. */
    @Override
    public void onBackPressed() {
        if (browse == Browse.FOLDER) {
            showBrowse(Browse.FOLDERS);
            return;
        }
        super.onBackPressed();
    }

    /** Switch loop mode, from the button or the wheel, and say so: the wheel has no screen. */
    private void setMode(PlayOrder.Mode m) {
        mode = m;
        resume.edit().putString(KEY_MODE, m.name()).apply();
        showMode();
        Toast.makeText(this, modeLabel(m), Toast.LENGTH_SHORT).show();
    }

    private void showMode() {
        btnMode.setImageResource(modeIcon(mode));
        btnMode.setContentDescription(getString(modeLabel(mode)));
    }

    private static int modeIcon(PlayOrder.Mode m) {
        switch (m) {
            case ONE:
                return R.drawable.ic_repeat_one;
            case FOLDER:
                return R.drawable.ic_repeat_folder;
            case SHUFFLE:
                return R.drawable.ic_shuffle;
            default:
                return R.drawable.ic_repeat;
        }
    }

    private static int modeLabel(PlayOrder.Mode m) {
        switch (m) {
            case ONE:
                return R.string.mode_one;
            case FOLDER:
                return R.string.mode_folder;
            case SHUFFLE:
                return R.string.mode_shuffle;
            default:
                return R.string.mode_all;
        }
    }

    /** Each row's folder, for folder loop. Rows are title-sorted, so a folder is not a run. */
    private String[] folders() {
        String[] out = new String[tracks.size()];
        for (int i = 0; i < out.length; i++) {
            String p = tracks.get(i).path;
            int slash = p == null ? -1 : p.lastIndexOf('/');
            out[i] = slash > 0 ? p.substring(0, slash) : null;
        }
        return out;
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        grantBtn.setVisibility(show && !gate.granted() ? View.VISIBLE : View.GONE);
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

                @Override public void onCustomAction(String action) {
                    if (MediaCitizen.ACTION_REPEAT.equals(action)) {
                        setMode(mode.onRepeatKey());
                    } else if (MediaCitizen.ACTION_SHUFFLE.equals(action)) {
                        setMode(mode.onShuffleKey());
                    }
                }

                @Override public void onDuck(boolean duck) {
                    if (player == null) return;
                    float v = MediaCitizen.duckVolume(duck);
                    try { player.setVolume(v, v); } catch (Exception ignored) {}
                }
            });
            citizen.offer(MediaCitizen.ACTION_REPEAT, getString(R.string.mode_all), R.drawable.ic_repeat);
            citizen.offer(MediaCitizen.ACTION_SHUFFLE, getString(R.string.mode_shuffle), R.drawable.ic_shuffle);
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
        playAt(PlayOrder.previous(mode, current, folders()));
    }

    private void playAt(int index) {
        playAt(index, 0);
    }

    private void playAt(int index, long fromMs) {
        if (index < 0 || index >= tracks.size()) return;

        // Focus BEFORE prepareAsync. Refused focus means something else owns the cabin (a call,
        // usually) and starting anyway would talk over it — but by the time an asked-afterwards
        // refusal is seen, preparation is already in flight and onPrepared starts the player
        // regardless, so the early return would not actually keep us quiet.
        if (!citizen().takeFocus(MediaCitizen.Focus.MEDIA)) {
            return;
        }

        current = index;
        startFrom = fromMs;
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
        citizen().setMetadata(t.title, t.artist, t.album, t.duration, null);
        showArt(t);

        nowTitle.setText(t.title);
        nowArtist.setText(t.artist);
        showStar();
        seek.setProgress(0);
        seek.setMax(t.duration > 0 ? (int) t.duration : 0);
        posTime.setText(fmt(0));
        durTime.setText(fmt(t.duration));
        btnPlay.setImageResource(R.drawable.ic_pause);
        adapter.notifyDataSetChanged();
    }

    /**
     * Load the cover off the UI thread, then show it and republish the metadata with it, unless
     * the driver has moved on to another track meanwhile.
     */
    private void showArt(Track t) {
        nowArt.setVisibility(View.GONE);
        io.execute(() -> {
            Bitmap art = CoverArt.load(t.path);
            ui.post(() -> {
                if (art == null || current < 0 || current >= tracks.size() || tracks.get(current) != t) {
                    return;
                }
                nowArt.setImageBitmap(art);
                nowArt.setVisibility(View.VISIBLE);
                citizen().setMetadata(t.title, t.artist, t.album, t.duration, art);
            });
        });
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        int dur = mp.getDuration();
        if (dur > 0) { seek.setMax(dur); durTime.setText(fmt(dur)); }

        // The resume point: seek before start so the first audible frame is the right one.
        if (startFrom > 0) {
            mp.seekTo((int) startFrom);
            seek.setProgress((int) startFrom);
            posTime.setText(fmt(startFrom));
            startFrom = 0;
        }
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
        playNext(PlayOrder.Cause.SKIP);
    }

    private void playNext(PlayOrder.Cause cause) {
        if (tracks.isEmpty()) return;
        playAt(PlayOrder.next(mode, current, folders(), cause, random));
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
        playNext(PlayOrder.Cause.ENDED);
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
        ui.removeCallbacks(saver);
        ui.removeCallbacks(reload);
        getContentResolver().unregisterContentObserver(libraryWatch);
        unregisterReceiver(usbWatch);
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
        @Override public int getCount() {
            if (browse == Browse.FOLDERS) {
                return folderList.size();
            }
            return shown.length;
        }
        @Override public Object getItem(int p) {
            return browse == Browse.FOLDERS ? folderList.get(p) : tracks.get(trackAt(p));
        }
        @Override public long getItemId(int p) { return p; }

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
                title.setTypeface(face(true));
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

            boolean active;
            if (browse == Browse.FOLDERS) {
                Folders.Folder f = folderList.get(position);
                avatar.setImageResource(R.drawable.ic_folder);
                title.setText(f.name);
                artist.setText(folderLine(f).substring(f.name.length() + 3));
                active = openFolder == f;
            } else {
                Track t = tracks.get(trackAt(position));
                avatar.setImageResource(R.drawable.ic_music);
                title.setText(t.title);
                artist.setText(t.artist);
                active = trackAt(position) == current;
            }

            // avatar badge: a circle, or a hard-edged square when the theme asks for it
            GradientDrawable badge = new GradientDrawable();
            if (!Palette.hardEdge(MainActivity.this)) badge.setShape(GradientDrawable.OVAL);
            badge.setColor(active ? cAccentDim : cSurface2);
            avatar.setBackground(badge);
            avatar.setColorFilter(active ? cAccent : cText2);

            title.setTextColor(active ? cAccent : cText);

            // dim-accent highlight for the active row, cornered per the active theme
            if (active) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(16) * Palette.cornerScale(MainActivity.this));
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

    /** The face the active theme asks for (brand mono under Riposte, sans otherwise). */
    private android.graphics.Typeface face(boolean bold) {
        return Palette.typeface(this, bold);
    }
}
