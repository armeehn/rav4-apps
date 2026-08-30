package com.reveng.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.reveng.design.Palette;
import com.reveng.design.MediaCitizen;

/**
 * Clean-room standalone voice recorder. Captures AAC audio to an m4a file in the
 * app-scoped external files dir via android.media.MediaRecorder (no storage
 * permission needed to write), and plays recordings back with MediaPlayer.
 *
 * Left pane: a large record FAB with a live elapsed-time readout and an
 * amplitude-driven pulse ring. Right pane: recordings as cards with inline
 * play/pause, a slim seek bar and delete. Pure framework only, no AndroidX.
 */
public class MainActivity extends Activity
        implements MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener {

    private static final int REQ_PERM = 1;
    /**
     * v0.5.1 — the record affordance follows the launcher's error role. Resolved per call
     * rather than cached in a static: the palette can change while the app is running.
     */
    private int red() {
        return Palette.color(this, R.color.error);
    }

    private static final class Rec {
        final File file;
        final String name;
        final long duration; // ms
        final long date;     // epoch ms
        Rec(File file, String name, long duration, long date) {
            this.file = file; this.name = name; this.duration = duration; this.date = date;
        }
    }

    private final ArrayList<Rec> recs = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // hero
    private TextView status, elapsed, count;
    private ImageButton btnRecord, btnStop;
    private View pulse;

    // list
    private ListView list;
    private View empty;
    private TextView emptyText;
    private Button grantBtn;
    private RecAdapter adapter;

    // recording state
    private MediaRecorder recorder;
    private boolean recording = false;

    /** v0.6.1 — exclusive audio focus while recording, so nothing else is captured through the cabin mic. */
    private MediaCitizen citizen;
    private File recordingFile;
    private long recStartMs;

    // playback state
    private MediaPlayer player;
    private int playing = -1;
    private boolean prepared = false;
    private boolean userSeeking = false;

    // palette
    private int cAccent, cText, cText2, cSurface2;

    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long ms = SystemClock.elapsedRealtime() - recStartMs;
            elapsed.setText(fmt(ms));
            float level = 0f;
            if (recorder != null) {
                try { level = Math.min(1f, recorder.getMaxAmplitude() / 20000f); }
                catch (Exception ignored) {}
            }
            float scale = 1f + level * 0.7f;
            pulse.setScaleX(scale);
            pulse.setScaleY(scale);
            pulse.setAlpha(0.20f + level * 0.55f);
            ui.postDelayed(this, 90);
        }
    };

    private final Runnable playTick = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && player.isPlaying() && !userSeeking) {
                updateActiveRow(player.getCurrentPosition());
            }
            ui.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        cAccent = Palette.color(this, R.color.accent);
        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);
        cSurface2 = Palette.color(this, R.color.surface2);

        status = findViewById(R.id.status);
        elapsed = findViewById(R.id.elapsed);
        count = findViewById(R.id.count);
        btnRecord = findViewById(R.id.btn_record);
        btnStop = findViewById(R.id.btn_stop);
        pulse = findViewById(R.id.pulse);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.empty_text);
        grantBtn = findViewById(R.id.grant);

        adapter = new RecAdapter();
        list.setAdapter(adapter);

        btnRecord.setOnClickListener(v -> {
            if (recording) stopRecording();
            else if (hasPerm()) startRecording();
            else requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM);
        });
        btnStop.setOnClickListener(v -> { if (recording) stopRecording(); });
        grantBtn.setOnClickListener(v ->
                requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM));

        list.setOnItemClickListener((AdapterView<?> p, View vw, int pos, long id) -> togglePlay(pos));

        ui.postDelayed(playTick, 400);

        if (!hasPerm()) {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM);
        }
        loadRecordings();
    }

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) {
            boolean granted = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                grantBtn.setVisibility(View.GONE);
                loadRecordings();
            } else {
                emptyText.setText(R.string.need_permission);
                showEmpty(true);
            }
        }
    }

    // ---------------- recording ----------------

    private File recordDir() {
        File d = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    private void startRecording() {
        // Anything that can fail without touching the microphone happens before focus is
        // taken: an exclusive focus grabbed and then dropped on the floor keeps the radio
        // silent for as long as this screen stays open.
        File dir = recordDir();
        if (dir == null) { toast("Storage unavailable"); return; }

        // Exclusive focus: a ducked radio is still audible, and still ends up in the
        // capture. A refusal means something else already holds the microphone.
        if (citizen == null) {
            citizen = MediaCitizen.attach(this, "recorder", new SilentTransport());
        }
        if (!citizen.takeFocus(MediaCitizen.Focus.RECORDING)) {
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        recordingFile = new File(dir, "REC_" + stamp + ".m4a");

        stopPlayback();
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            releaseRecorder();
            // Nothing is capturing, so hand the cabin back rather than hold it silent.
            citizen.releaseFocus();
            toast("Could not start recording");
            return;
        }

        recording = true;
        recStartMs = SystemClock.elapsedRealtime();
        recStyleOn(true);
        elapsed.setText(fmt(0));
        ui.postDelayed(recTick, 90);
    }

    private void stopRecording() {
        if (citizen != null) {
            citizen.releaseFocus();
        }
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(recTick);
        recStyleOn(false);

        boolean ok = true;
        try {
            recorder.stop();
        } catch (Exception e) {
            ok = false; // too short / no data
        }
        releaseRecorder();

        if (!ok || recordingFile == null || !recordingFile.exists()
                || recordingFile.length() == 0) {
            if (recordingFile != null) recordingFile.delete();
            toast("Recording too short");
            elapsed.setText("0:00");
            return;
        }
        elapsed.setText("0:00");
        promptName(recordingFile);
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private void recStyleOn(boolean on) {
        if (on) {
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(red());
            btnRecord.setBackground(oval);
            status.setText(R.string.recording);
            status.setTextColor(red());
            btnStop.setVisibility(View.VISIBLE);
        } else {
            btnRecord.setBackgroundResource(R.drawable.btn_fab);
            status.setText(R.string.tap_to_record);
            status.setTextColor(cText2);
            btnStop.setVisibility(View.INVISIBLE);
            pulse.setScaleX(1f); pulse.setScaleY(1f); pulse.setAlpha(0f);
        }
    }

    private void promptName(final File file) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(defaultName(file));
        input.setSelectAllOnFocus(true);

        int pad = dp(20);
        FrameWrap wrap = new FrameWrap(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.name_recording)
                .setView(wrap)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String chosen = input.getText().toString().trim();
                    File saved = renameTo(file, chosen);
                    loadRecordings();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> loadRecordings())
                .setOnCancelListener(d -> loadRecordings())
                .show();
    }

    /** minimal FrameLayout replacement so the EditText gets side padding */
    private static final class FrameWrap extends LinearLayout {
        FrameWrap(android.content.Context c) { super(c); setOrientation(VERTICAL); }
    }

    private String defaultName(File file) {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private File renameTo(File file, String chosen) {
        if (chosen == null || chosen.isEmpty()) return file;
        String safe = chosen.replaceAll("[\\\\/:*?\"<>|]", "_");
        File dir = file.getParentFile();
        File dest = new File(dir, safe + ".m4a");
        int i = 2;
        while (dest.exists() && !dest.equals(file)) {
            dest = new File(dir, safe + " (" + i++ + ").m4a");
        }
        if (file.renameTo(dest)) return dest;
        return file;
    }

    // ---------------- list ----------------

    private void loadRecordings() {
        final File dir = recordDir();
        io.execute(() -> {
            ArrayList<Rec> found = new ArrayList<>();
            if (dir != null) {
                File[] files = dir.listFiles((d, name) ->
                        name.toLowerCase(Locale.US).endsWith(".m4a"));
                if (files != null) {
                    Arrays.sort(files, (a, b) ->
                            Long.compare(b.lastModified(), a.lastModified()));
                    for (File f : files) {
                        long dur = durationOf(f);
                        String n = f.getName();
                        int dot = n.lastIndexOf('.');
                        if (dot > 0) n = n.substring(0, dot);
                        found.add(new Rec(f, n, dur, f.lastModified()));
                    }
                }
            }
            ui.post(() -> {
                // keep playback of the currently-playing file valid across reloads
                File playingFile = (playing >= 0 && playing < recs.size())
                        ? recs.get(playing).file : null;
                recs.clear();
                recs.addAll(found);
                playing = -1;
                if (playingFile != null) {
                    for (int i = 0; i < recs.size(); i++)
                        if (recs.get(i).file.equals(playingFile)) { playing = i; break; }
                    if (playing < 0) stopPlayback();
                }
                adapter.notifyDataSetChanged();
                count.setText(getString(R.string.recordings_count, recs.size()));
                if (recs.isEmpty()) {
                    emptyText.setText(hasPerm() ? getString(R.string.no_recordings)
                            : getString(R.string.need_permission));
                }
                showEmpty(recs.isEmpty());
            });
        });
    }

    private long durationOf(File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String s = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return s != null ? Long.parseLong(s) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        grantBtn.setVisibility(show && !hasPerm() ? View.VISIBLE : View.GONE);
    }

    private void deleteRec(int pos) {
        if (pos < 0 || pos >= recs.size()) return;
        Rec r = recs.get(pos);
        if (playing == pos) stopPlayback();
        try { r.file.delete(); } catch (Exception ignored) {}
        toast(getString(R.string.deleted));
        loadRecordings();
    }

    // ---------------- playback ----------------

    private void togglePlay(int pos) {
        if (pos < 0 || pos >= recs.size()) return;
        if (playing == pos && player != null) {
            if (player.isPlaying()) { player.pause(); }
            else if (prepared) { player.start(); }
            adapter.notifyDataSetChanged();
            return;
        }
        playAt(pos);
    }

    private void playAt(int pos) {
        Rec r = recs.get(pos);
        prepared = false;
        playing = pos;
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setOnCompletionListener(this);
                player.setOnPreparedListener(this);
            } else {
                player.reset();
            }
            player.setDataSource(r.file.getAbsolutePath());
            player.prepareAsync();
        } catch (Exception e) {
            toast("Could not play recording");
            stopPlayback();
            return;
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        mp.start();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        try { mp.seekTo(0); } catch (Exception ignored) {}
        adapter.notifyDataSetChanged();
    }

    private void stopPlayback() {
        prepared = false;
        playing = -1;
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    /** Update the seek bar / position label of the currently playing row, if visible. */
    private void updateActiveRow(int posMs) {
        if (playing < 0) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Integer && (Integer) tag == playing) {
                LinearLayout card = (LinearLayout) child;
                LinearLayout seekRow = (LinearLayout) card.getChildAt(1);
                if (seekRow.getVisibility() != View.VISIBLE) return;
                TextView pos = (TextView) seekRow.getChildAt(0);
                SeekBar sb = (SeekBar) seekRow.getChildAt(1);
                sb.setProgress(posMs);
                pos.setText(fmt(posMs));
                return;
            }
        }
    }

    // ---------------- adapter ----------------

    private final class RecAdapter extends BaseAdapter {
        private final SimpleDateFormat dfmt =
                new SimpleDateFormat("MMM d, h:mm a", Locale.US);

        @Override public int getCount() { return recs.size(); }
        @Override public Object getItem(int p) { return recs.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            if (convertView instanceof LinearLayout) {
                card = (LinearLayout) convertView;
            } else {
                card = buildCard();
            }
            card.setTag(position);

            LinearLayout topRow = (LinearLayout) card.getChildAt(0);
            ImageButton playBtn = (ImageButton) topRow.getChildAt(0);
            LinearLayout col = (LinearLayout) topRow.getChildAt(1);
            TextView name = (TextView) col.getChildAt(0);
            TextView meta = (TextView) col.getChildAt(1);
            ImageButton delBtn = (ImageButton) topRow.getChildAt(2);
            LinearLayout seekRow = (LinearLayout) card.getChildAt(1);
            TextView posT = (TextView) seekRow.getChildAt(0);
            final SeekBar seek = (SeekBar) seekRow.getChildAt(1);
            TextView durT = (TextView) seekRow.getChildAt(2);

            final Rec r = recs.get(position);
            name.setText(r.name);
            meta.setText(fmt(r.duration) + "  •  " + dfmt.format(new Date(r.date)));

            boolean active = position == playing;
            boolean isPlaying = active && player != null && prepared && player.isPlaying();
            playBtn.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            playBtn.setColorFilter(active ? cAccent : cText);
            name.setTextColor(active ? cAccent : cText);

            playBtn.setOnClickListener(v -> togglePlay(position));
            delBtn.setOnClickListener(v -> confirmDelete(position));

            if (active) {
                seekRow.setVisibility(View.VISIBLE);
                int dur = (int) (r.duration > 0 ? r.duration : 0);
                if (player != null && prepared && player.getDuration() > 0)
                    dur = player.getDuration();
                seek.setMax(dur);
                int cur = (player != null && prepared) ? safePos() : 0;
                seek.setProgress(cur);
                posT.setText(fmt(cur));
                durT.setText(fmt(dur));
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                        if (fromUser) posT.setText(fmt(p));
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
                    @Override public void onStopTrackingTouch(SeekBar sb) {
                        userSeeking = false;
                        if (player != null && prepared) {
                            try { player.seekTo(sb.getProgress()); } catch (Exception ignored) {}
                        }
                    }
                });
            } else {
                seekRow.setVisibility(View.GONE);
                seek.setOnSeekBarChangeListener(null);
            }
            return card;
        }

        private LinearLayout buildCard() {
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            int m = dp(6);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = m; clp.bottomMargin = m;
            card.setLayoutParams(clp);
            int pad = dp(12);
            card.setPadding(pad, dp(10), pad, dp(10));

            // top row
            LinearLayout topRow = new LinearLayout(MainActivity.this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(topRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageButton playBtn = iconBtn(R.drawable.ic_play);
            topRow.addView(playBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));

            LinearLayout col = new LinearLayout(MainActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = dp(12); colLp.rightMargin = dp(8);
            topRow.addView(col, colLp);

            TextView name = new TextView(MainActivity.this);
            name.setTextSize(16);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
            name.setTextColor(cText);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView meta = new TextView(MainActivity.this);
            meta.setTextSize(12);
            meta.setTextColor(cText2);
            meta.setSingleLine(true);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(2);
            col.addView(meta, mlp);

            ImageButton delBtn = iconBtn(R.drawable.ic_delete);
            delBtn.setColorFilter(cText2);
            topRow.addView(delBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));

            // seek row
            LinearLayout seekRow = new LinearLayout(MainActivity.this);
            seekRow.setOrientation(LinearLayout.HORIZONTAL);
            seekRow.setGravity(Gravity.CENTER_VERTICAL);
            seekRow.setVisibility(View.GONE);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srlp.topMargin = dp(2);
            card.addView(seekRow, srlp);

            TextView posT = timeLabel();
            seekRow.addView(posT, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            SeekBar seek = new SeekBar(MainActivity.this);
            seek.getProgressDrawable().setTint(cAccent);
            seek.getThumb().setTint(cAccent);
            LinearLayout.LayoutParams seLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            seLp.leftMargin = dp(6); seLp.rightMargin = dp(6);
            seekRow.addView(seek, seLp);

            TextView durT = timeLabel();
            seekRow.addView(durT, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            return card;
        }

        private ImageButton iconBtn(int icon) {
            ImageButton b = new ImageButton(MainActivity.this);
            b.setBackgroundResource(R.drawable.btn_icon);
            b.setImageResource(icon);
            b.setColorFilter(cText);
            b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int p = dp(14);
            b.setPadding(p, p, p, p);
            return b;
        }

        private TextView timeLabel() {
            TextView t = new TextView(MainActivity.this);
            t.setTextSize(12);
            t.setTextColor(cText2);
            t.setMinWidth(dp(40));
            t.setText("0:00");
            return t;
        }
    }

    private int safePos() {
        try { return player.getCurrentPosition(); } catch (Exception e) { return 0; }
    }

    private void confirmDelete(final int pos) {
        if (pos < 0 || pos >= recs.size()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(recs.get(pos).name)
                .setPositiveButton(R.string.delete, (d, w) -> deleteRec(pos))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------- misc ----------------

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // don't leave the mic hot in the background
        if (recording) stopRecording();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(recTick);
        ui.removeCallbacks(playTick);
        releaseRecorder();
        stopPlayback();
        if (citizen != null) {
            citizen.release();
            citizen = null;
        }
    }

    /**
     * Capture has no transport to offer: there is nothing for the wheel or the launcher to
     * play, pause or skip. Only the focus half of MediaCitizen is used here.
     */
    private static final class SilentTransport implements MediaCitizen.Transport {
        @Override public void onPlay() { }

        @Override public void onPause() { }

        @Override public void onNext() { }

        @Override public void onPrevious() { }

        @Override public void onStop() { }

        @Override public void onDuck(boolean duck) { }
    }
}
