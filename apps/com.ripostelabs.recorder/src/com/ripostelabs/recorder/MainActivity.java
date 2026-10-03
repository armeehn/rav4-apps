package com.ripostelabs.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
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
import android.view.inputmethod.EditorInfo;
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

import com.ripostelabs.design.PermissionGate;
import com.ripostelabs.projection.ns.NsPipeline;
import com.ripostelabs.projection.ns.RnNoise;
import com.ripostelabs.projection.ns.Strength;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.ripostelabs.design.Palette;
import com.ripostelabs.design.MediaCitizen;

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

    /** The microphone permission, asked and re-asked through the suite's one gate. */
    private PermissionGate gate;
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
        /** A noise test's cleaned half, played after {@link #file} (the raw half); else null. */
        final File second;
        Rec(File file, String name, long duration, long date, File second) {
            this.file = file; this.name = name; this.duration = duration; this.date = date;
            this.second = second;
        }
    }

    /** What the record button captures. */
    private enum Mode {
        /** An AAC memo through MediaRecorder, the app's original job. */
        MEMO,
        /** One mic take saved twice: as captured, and through the CarPlay mic's RNNoise. */
        NOISE_TEST,
        /** Unprocessed 48 kHz cabin noise with a tag and a sidecar, for tuning RNNoise. */
        ROAD_NOISE,
    }

    /**
     * The noise test's capture rate. The phone picks the CarPlay mic's rate per session and
     * calls and Siri ask for 16 kHz, so the test runs the pipeline at 16 kHz too (16k to 48k
     * around RNNoise, as a call does).
     */
    private static final int NOISE_TEST_RATE = 16000;
    /** Projection's MicPrefs default, chosen there by PESQ-WB in car noise. */
    private static final Strength DEFAULT_STRENGTH = Strength.MEDIUM;
    private static final String PREFS = "recorder";
    private static final String KEY_MODE = "mode";
    private static final String KEY_STRENGTH = "ns_strength";
    private static final String KEY_TAG = "road_tag";
    /** A road noise take shorter than this is a mis-tap, not data. */
    private static final double MIN_ROAD_SECONDS = 1.0;

    private Mode mode = Mode.MEMO;
    private Strength strength = DEFAULT_STRENGTH;
    private LinearLayout modeRow, optionRow;
    private TextView modeNote;

    // noise test capture
    private PcmCapture capture;
    private TwinTake twin;
    private String twinBase;

    // road noise capture
    private RoadNoise.Tag tag = RoadNoise.Tag.CITY;
    private Wav.Sink roadSink;
    private File roadWav;
    private Date roadStart;

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
    /** Watches the running take for a mic that hears nothing ([MicSilence]). */
    private MicSilence silence;
    /** Set while a silenced take is stopped: its files are deleted, not offered for a name. */
    private boolean discard;
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
    /** True while a pair's cleaned half plays. */
    private boolean playingSecond = false;

    // palette
    private int cAccent, cText, cText2, cSurface2;

    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long ms = SystemClock.elapsedRealtime() - recStartMs;
            elapsed.setText(fmt(ms));
            int peak = 0;
            if (recorder != null) {
                try { peak = recorder.getMaxAmplitude(); }
                catch (Exception ignored) {}
            }
            if (capture != null) {
                peak = capture.takePeak();
            }
            float level = Math.min(1f, peak / 20000f);
            float scale = 1f + level * 0.7f;
            pulse.setScaleX(scale);
            pulse.setScaleY(scale);
            pulse.setAlpha(0.20f + level * 0.55f);

            // A silenced mic records zeros without an error: stop and say why, never save them.
            MicSilence.Why why = silence.check(new MicSilence.Reading(peak, clientSilenced(),
                    audioMode(), othersCapturing()), SystemClock.elapsedRealtime());
            if (why != MicSilence.Why.NONE) {
                abandon(why);
                return;
            }
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
        gate = PermissionGate.of(this, new String[]{ Manifest.permission.RECORD_AUDIO }, grantBtn,
                new PermissionGate.Listener() {
                    @Override public void onGranted() { loadRecordings(); }
                    @Override public void onDenied() {
                        emptyText.setText(R.string.need_permission);
                        showEmpty(true);
                    }
                });

        adapter = new RecAdapter();
        list.setAdapter(adapter);

        // Mode and strength survive a restart: a test session spans several takes.
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mode = parseMode(prefs.getString(KEY_MODE, null));
        tag = RoadNoise.Tag.parse(prefs.getString(KEY_TAG, null));
        strength = Strength.parse(prefs.getString(KEY_STRENGTH, null), DEFAULT_STRENGTH);
        modeRow = findViewById(R.id.modes);
        optionRow = findViewById(R.id.options);
        modeNote = findViewById(R.id.mode_note);
        renderModes();

        btnRecord.setOnClickListener(v -> {
            if (recording) stopRecording();
            else if (gate.granted()) startRecording();
            else gate.request();
        });
        btnStop.setOnClickListener(v -> { if (recording) stopRecording(); });

        list.setOnItemClickListener((AdapterView<?> p, View vw, int pos, long id) -> togglePlay(pos));

        ui.postDelayed(playTick, 400);

        if (!gate.granted()) {
            gate.request();
        }
        loadRecordings();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (!gate.onResult(req, p, r)) {
            super.onRequestPermissionsResult(req, p, r);
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

        // A call mutes every other capture: refuse rather than record its silence.
        MicSilence.Why early = MicSilence.before(audioMode());
        if (early != MicSilence.Why.NONE) {
            toastLong(whyText(early));
            return;
        }

        // Playback holds media focus; let it go before asking for the capture's.
        stopPlayback();

        // Exclusive focus: a ducked radio is still audible, and still ends up in the
        // capture. A refusal means something else already holds the microphone.
        if (!citizen().takeFocus(MediaCitizen.Focus.RECORDING)) {
            // Say so: a refusal that returns silently reads as a dead button.
            toast(getString(R.string.mic_busy));
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        boolean started;
        switch (mode) {
            case NOISE_TEST:
                started = startNoiseTest(dir, stamp);
                break;
            case ROAD_NOISE:
                started = startRoadNoise();
                break;
            default:
                started = startMemo(dir, stamp);
                break;
        }
        if (!started) {
            // Nothing is capturing, so hand the cabin back rather than hold it silent.
            citizen().releaseFocus();
            toast(getString(R.string.mic_unavailable));
            return;
        }

        recording = true;
        recStartMs = SystemClock.elapsedRealtime();
        silence = new MicSilence(recStartMs);
        recStyleOn(true);
        elapsed.setText(fmt(0));
        ui.postDelayed(recTick, 90);
    }

    private boolean startMemo(File dir, String stamp) {
        recordingFile = new File(dir, "REC_" + stamp + ".m4a");
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
            return false;
        }
        return true;
    }

    /** One capture, two files: "<base> raw.wav" and "<base> cleaned.wav". */
    private boolean startNoiseTest(File dir, String stamp) {
        twinBase = "NR_" + stamp;
        NsPipeline ns = NsPipeline.create(NOISE_TEST_RATE, 1, RnNoise.open(), strength);
        try {
            twin = TwinTake.open(new File(dir, Takes.rawName(twinBase)),
                    new File(dir, Takes.cleanedName(twinBase)), NOISE_TEST_RATE, ns);
        } catch (java.io.IOException e) {
            ns.close();
            return false;
        }

        capture = PcmCapture.start(NOISE_TEST_RATE, twin::accept);
        if (capture == null) {
            closeTwin();
            deleteTwin(dir);
            return false;
        }

        // Without the library both files would be the same; say so before the take, not after.
        if (twin.mode() != NsPipeline.Mode.ACTIVE) {
            toast(getString(R.string.ns_unavailable));
        }
        return true;
    }

    /** The road noise folder: app storage, readable by adb, which is how zero collects it. */
    private File roadDir() {
        File d = new File(getExternalFilesDir(null), RoadNoise.DIR);
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private boolean startRoadNoise() {
        roadStart = new Date();
        String base = RoadNoise.baseName(roadStart, java.util.TimeZone.getDefault(), tag);
        roadWav = new File(roadDir(), base + RoadNoise.WAV);
        try {
            roadSink = Wav.Sink.open(roadWav, RoadNoise.RATE);
        } catch (java.io.IOException e) {
            return false;
        }

        capture = PcmCapture.start(RoadNoise.RATE, roadSink::write);
        if (capture != null) {
            return true;
        }
        closeRoadSink();
        roadWav.delete();
        return false;
    }

    private void closeRoadSink() {
        if (roadSink == null) {
            return;
        }
        try {
            roadSink.close();
        } catch (java.io.IOException ignored) {
            // The header keeps a zero length; the take is dropped as too short below.
        }
        roadSink = null;
    }

    /** Close the WAV, then write its sidecar: the sidecar is what marks a take finished. */
    private void stopRoadNoise() {
        capture.stop();
        String error = capture.error();
        capture = null;
        closeRoadSink();
        if (discard) {
            roadWav.delete();
            return;
        }

        double seconds = Wav.seconds(roadWav);
        if (seconds < MIN_ROAD_SECONDS) {
            roadWav.delete();
            toast(error != null ? error : "Recording too short");
            return;
        }

        String base = Takes.stripExtension(roadWav.getName());
        String json = RoadNoise.sidecar(roadWav.getName(), tag, roadStart, seconds, null);
        try {
            java.nio.file.Files.write(new File(roadDir(), base + RoadNoise.JSON).toPath(),
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            toast("Could not save the sidecar");
            return;
        }
        toast(getString(R.string.road_saved, fmt((long) (seconds * 1000)), tagLabel(tag)));
        renderModes();
    }

    private void closeTwin() {
        if (twin == null) {
            return;
        }
        try {
            twin.close();
        } catch (java.io.IOException ignored) {
            // The headers stay at zero length; the files are dropped as too short below.
        }
        twin = null;
    }

    private void deleteTwin(File dir) {
        new File(dir, Takes.rawName(twinBase)).delete();
        new File(dir, Takes.cleanedName(twinBase)).delete();
    }

    /** Stop a noise test take and offer to name it; drops a take with no samples. */
    private void stopNoiseTest() {
        capture.stop();
        String error = capture.error();
        capture = null;
        closeTwin();
        if (discard) {
            deleteTwin(recordDir());
            return;
        }

        File dir = recordDir();
        File raw = new File(dir, Takes.rawName(twinBase));
        File cleaned = new File(dir, Takes.cleanedName(twinBase));
        if (raw.length() <= Wav.HEADER_BYTES) {
            deleteTwin(dir);
            toast(error != null ? error : "Recording too short");
            return;
        }
        promptPairName(raw, cleaned);
    }

    private void stopRecording() {
        if (citizen != null) {
            citizen.releaseFocus();
        }
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(recTick);
        recStyleOn(false);
        elapsed.setText("0:00");

        if (capture != null && roadSink != null) {
            stopRoadNoise();
            return;
        }
        if (capture != null) {
            stopNoiseTest();
            return;
        }

        boolean ok = true;
        try {
            recorder.stop();
        } catch (Exception e) {
            ok = false; // too short / no data
        }
        releaseRecorder();
        if (discard) {
            if (recordingFile != null) recordingFile.delete();
            return;
        }

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

    /** Stop the take, drop its files and say why the mic heard nothing. */
    private void abandon(MicSilence.Why why) {
        discard = true;
        stopRecording();
        discard = false;
        toastLong(getString(R.string.take_dropped, whyText(why)));
    }

    private String whyText(MicSilence.Why why) {
        switch (why) {
            case CALL:
                return getString(R.string.mic_call);
            case BUSY:
                return getString(R.string.mic_busy);
            case SILENCED:
                return getString(R.string.mic_silenced);
            default:
                return getString(R.string.mic_silent);
        }
    }

    /** Any call mode, cellular or VoIP: each mutes the captures that are not the call's. */
    private MicSilence.Mode audioMode() {
        int m = ((AudioManager) getSystemService(AUDIO_SERVICE)).getMode();
        boolean call = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
                || m == AudioManager.MODE_CALL_SCREENING || m == AudioManager.MODE_CALL_REDIRECT
                || m == AudioManager.MODE_COMMUNICATION_REDIRECT;
        return call ? MicSilence.Mode.CALL : MicSilence.Mode.NORMAL;
    }

    /** The platform's own verdict on this take (API 29+): muted for a higher-priority capture. */
    private boolean clientSilenced() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        if (capture != null) {
            return capture.silenced();
        }
        if (recorder == null) {
            return false;
        }
        AudioRecordingConfiguration c = recorder.getActiveRecordingConfiguration();
        return c != null && c.isClientSilenced();
    }

    /** Someone else (CarPlay's mic, a call) is capturing beside this take. */
    private boolean othersCapturing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        return am.getActiveRecordingConfigurations().size() > 1;
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
        // Without this the IME takes the whole panel for its own editor and the dialog
        // vanishes behind it: the panel is 720 px tall, so the keyboard asks for extract mode.
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
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

    /** Name a noise test take: both halves move together, keeping " raw" / " cleaned". */
    private void promptPairName(final File raw, final File cleaned) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setText(twinBase);
        input.setSelectAllOnFocus(true);

        int pad = dp(20);
        FrameWrap wrap = new FrameWrap(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.name_recording)
                .setView(wrap)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Takes.renamePair(raw, cleaned, input.getText().toString());
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

    // ---------------- modes ----------------

    /** Mode chips, then for the noise test the strength chips and a note on the capture. */
    private void renderModes() {
        chips(modeRow, new String[]{getString(R.string.mode_memo), getString(R.string.mode_noise_test),
                        getString(R.string.mode_road_noise)},
                mode.ordinal(), i -> setMode(Mode.values()[i]));

        boolean options = mode != Mode.MEMO;
        optionRow.setVisibility(options ? View.VISIBLE : View.GONE);
        modeNote.setVisibility(options ? View.VISIBLE : View.GONE);
        if (mode == Mode.ROAD_NOISE) {
            renderRoadNoise();
            return;
        }
        if (!options) {
            return;
        }
        modeNote.setText(R.string.noise_test_note);

        Strength[] all = Strength.values();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = strengthLabel(all[i]);
        }
        chips(optionRow, labels, strength.ordinal(), i -> setStrength(all[i]));
    }

    /** Tag chips, and a note with the minutes captured so far against the target. */
    private void renderRoadNoise() {
        RoadNoise.Tag[] all = RoadNoise.Tag.values();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = tagLabel(all[i]);
        }
        chips(optionRow, labels, tag.ordinal(), i -> setTag(all[i]));

        double minutes = RoadNoise.minutes(RoadNoise.totalSeconds(roadDir()));
        modeNote.setText(getString(R.string.road_note,
                String.format(Locale.US, "%.1f", minutes), RoadNoise.TARGET_MINUTES));
    }

    private String tagLabel(RoadNoise.Tag t) {
        switch (t) {
            case HIGHWAY:
                return getString(R.string.tag_highway);
            case FAN_HIGH:
                return getString(R.string.tag_fan_high);
            case RAIN:
                return getString(R.string.tag_rain);
            case OTHER:
                return getString(R.string.tag_other);
            default:
                return getString(R.string.tag_city);
        }
    }

    private void setTag(RoadNoise.Tag t) {
        if (recording) {
            return;
        }
        tag = t;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TAG, t.name()).apply();
        renderModes();
    }

    private static Mode parseMode(String name) {
        for (Mode m : Mode.values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return Mode.MEMO;
    }

    /** The same three words as the CarPlay mic settings. */
    private String strengthLabel(Strength s) {
        switch (s) {
            case LIGHT:
                return getString(R.string.strength_light);
            case FULL:
                return getString(R.string.strength_full);
            default:
                return getString(R.string.strength_medium);
        }
    }

    private void setMode(Mode m) {
        // A take in progress keeps the mode it started with.
        if (recording) {
            return;
        }
        mode = m;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODE, m.name()).apply();
        renderModes();
    }

    private void setStrength(Strength s) {
        if (recording) {
            return;
        }
        strength = s;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STRENGTH, s.name()).apply();
        renderModes();
    }

    /** A row of tappable labels, one selected, rebuilt on every change. */
    private void chips(LinearLayout row, String[] labels, int selected, java.util.function.IntConsumer onPick) {
        row.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(24));
            if (on) {
                bg.setColor(cAccent);
            } else {
                bg.setStroke(dp(1), cText2);
            }

            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(15);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(on ? Palette.color(this, R.color.on_accent) : cText);
            chip.setBackground(bg);
            chip.setPadding(dp(18), 0, dp(18), 0);
            chip.setMinWidth(dp(48));
            chip.setOnClickListener(v -> onPick.accept(index));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            lp.rightMargin = dp(8);
            row.addView(chip, lp);
        }
    }

    // ---------------- list ----------------

    private void loadRecordings() {
        final File dir = recordDir();
        io.execute(() -> {
            ArrayList<Rec> found = new ArrayList<>();
            if (dir != null) {
                File[] files = dir.listFiles((d, name) -> {
                    String lower = name.toLowerCase(Locale.US);
                    return lower.endsWith(".m4a") || lower.endsWith(".wav");
                });
                if (files != null) {
                    Arrays.sort(files, (a, b) ->
                            Long.compare(b.lastModified(), a.lastModified()));
                    // A noise test's two files become one row that plays raw, then cleaned.
                    List<Takes.Take> takes = Takes.group(Arrays.asList(files));
                    for (Takes.Take t : takes) {
                        found.add(new Rec(t.first, t.name, durationOf(t.first),
                                t.first.lastModified(), t.second));
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
                count.setText(recs.size() == 1 ? getString(R.string.recordings_count_one)
                        : getString(R.string.recordings_count, recs.size()));
                if (recs.isEmpty()) {
                    emptyText.setText(gate.granted() ? getString(R.string.no_recordings)
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
        grantBtn.setVisibility(show && !gate.granted() ? View.VISIBLE : View.GONE);
    }

    private void deleteRec(int pos) {
        if (pos < 0 || pos >= recs.size()) return;
        Rec r = recs.get(pos);
        if (playing == pos) stopPlayback();
        try { r.file.delete(); } catch (Exception ignored) {}
        if (r.second != null) {
            r.second.delete();
        }
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
        playFile(pos, false);
    }

    /** Play a row's first file, or a pair's cleaned half when {@code second}. */
    private void playFile(int pos, boolean second) {
        Rec r = recs.get(pos);
        File f = second ? r.second : r.file;
        // Media focus, as the Music app takes it: the radio stops instead of playing over the
        // take. Refused means a call is on; the second half rides the first half's focus.
        if (!second && !citizen().takeFocus(MediaCitizen.Focus.MEDIA)) {
            toast(getString(R.string.audio_busy));
            return;
        }
        prepared = false;
        playing = pos;
        playingSecond = second;
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setOnCompletionListener(this);
                player.setOnPreparedListener(this);
            } else {
                player.reset();
            }
            player.setDataSource(f.getAbsolutePath());
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
        // A noise test plays back to back: raw, then the same take cleaned.
        if (playing >= 0 && playing < recs.size() && recs.get(playing).second != null && !playingSecond) {
            playFile(playing, true);
            return;
        }
        try { mp.seekTo(0); } catch (Exception ignored) {}
        citizen().releaseFocus();
        adapter.notifyDataSetChanged();
    }

    private void stopPlayback() {
        if (player != null && citizen != null) {
            citizen.releaseFocus();
        }
        prepared = false;
        playing = -1;
        playingSecond = false;
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
            String when = fmt(r.duration) + "  •  " + dfmt.format(new Date(r.date));
            if (r.second != null) {
                boolean cleanedNow = position == playing && playingSecond;
                when = getString(cleanedNow ? R.string.pair_playing_cleaned : R.string.pair_raw_then_cleaned)
                        + "  •  " + when;
            }
            meta.setText(when);

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

    private void toastLong(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

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
        if (capture != null) {
            capture.stop();
            capture = null;
            closeTwin();
            closeRoadSink();
        }
        stopPlayback();
        if (citizen != null) {
            citizen.release();
            citizen = null;
        }
    }

    private MediaCitizen citizen() {
        if (citizen == null) {
            citizen = MediaCitizen.attach(this, "recorder", new SilentTransport());
        }
        return citizen;
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
