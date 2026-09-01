package com.ripostelabs.radio;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import com.ripostelabs.design.Palette;
import com.ripostelabs.design.MediaCitizen;

/**
 * Clean-room radio for the GT6 head unit: a real FM/AM tuner driven through
 * the vendor event gateway (see {@link Tuner}), plus the curated internet
 * streams the previous version shipped, kept as a third NET tab.
 *
 * Tuner tab feature parity with the vendor app: in-app FM/AM band switch,
 * seek up/down, single-step up/down, preset scan, stereo/mono and DX/LOC
 * toggles, direct tuning via slider, six presets per band (tap to tune,
 * hold to save), live stereo/RDS indicators.
 */
public class MainActivity extends Activity
        implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, Tuner.Listener {

    // ---- Bands -------------------------------------------------------------
    private static final int TAB_FM = 0, TAB_AM = 1, TAB_NET = 2;
    // FM range in 10 kHz units, AM in kHz (NA raster; the MCU snaps anyway).
    private static final int FM_MIN = 8750, FM_MAX = 10790, FM_STEP = 20;
    private static final int AM_MIN = 530, AM_MAX = 1710, AM_STEP = 10;
    private static final int PRESET_SLOTS = 6;

    private Tuner tuner;
    private int tab = TAB_FM;
    private int curFreq = FM_MIN;
    private int curBand = 0;            // getRadioBand(): 0..2 FM, >=3 AM
    private boolean modeLost = false;
    /** v0.8: the driver paused the tuner (wheel/card); distinct from losing the mode. */
    private boolean tunerPaused = false;
    private boolean dragging = false;
    private SharedPreferences prefs;

    // Tuner views
    private View tunerPane, netPane;
    private TextView tabFm, tabAm, tabNet;
    private TextView freqDisplay, freqUnit, bandLabel, chipStatus, freqMin, freqMax;
    private SeekBar slider;
    private final ArrayList<TextView> presetViews = new ArrayList<>();

    // ---- Streaming (NET) ---------------------------------------------------
    private static final class Station {
        final String name, genre, url;
        Station(String name, String genre, String url) {
            this.name = name; this.genre = genre; this.url = url;
        }
    }

    private static final int STOPPED = 0, BUFFERING = 1, PLAYING = 2;
    private final ArrayList<Station> stations = new ArrayList<>();
    private final ArrayList<Object> rows = new ArrayList<>(); // String header or Station
    private TextView nowTitle, nowStatus;
    private ImageView nowAvatar;
    private ImageButton btnPlay;
    private MediaPlayer player;
    private Station current;
    private int state = STOPPED;

    /**
     * v0.6.2 gave the NET streams a MediaSession (the session half of MediaCitizen only —
     * the stream path manages its own focus and predates the helper). v0.8 extends the same
     * session to the FM/AM tuner: the launcher's now-playing card now shows the tuned
     * frequency, and the wheel's next/previous ride seek up/down. One session, one card
     * entry, whichever tab owns the cabin.
     */
    private MediaCitizen citizen;
    private StationAdapter adapter;
    private AudioFocusRequest focusRequest;

    private int cAccent, cAccentDim, cSurface2, cText, cText2, cText3, cStroke;

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
        cText3 = Palette.color(this, R.color.text3);
        cStroke = Palette.color(this, R.color.stroke);

        prefs = getSharedPreferences("presets", MODE_PRIVATE);
        tuner = new Tuner(this, this);

        bindTunerViews();
        bindNetViews();
        selectTab(TAB_FM);
        tuner.bind();
    }

    private void bindTunerViews() {
        tunerPane = findViewById(R.id.tuner_pane);
        netPane = findViewById(R.id.net_pane);
        tabFm = findViewById(R.id.tab_fm);
        tabAm = findViewById(R.id.tab_am);
        tabNet = findViewById(R.id.tab_net);
        freqDisplay = findViewById(R.id.freq_display);
        freqUnit = findViewById(R.id.freq_unit);
        bandLabel = findViewById(R.id.band_label);
        chipStatus = findViewById(R.id.chip_status);
        freqMin = findViewById(R.id.freq_min);
        freqMax = findViewById(R.id.freq_max);
        slider = findViewById(R.id.freq_slider);

        tabFm.setOnClickListener(v -> selectTab(TAB_FM));
        tabAm.setOnClickListener(v -> selectTab(TAB_AM));
        tabNet.setOnClickListener(v -> selectTab(TAB_NET));

        setCtl(R.id.btn_seek_down, Tuner.KEY_SEEK_DOWN);
        setCtl(R.id.btn_step_down, Tuner.KEY_STEP_DOWN);
        setCtl(R.id.btn_scan, Tuner.KEY_SCAN);
        setCtl(R.id.btn_st_mono, Tuner.KEY_ST_MONO);
        setCtl(R.id.btn_dx_loc, Tuner.KEY_DX_LOC);
        setCtl(R.id.btn_step_up, Tuner.KEY_STEP_UP);
        setCtl(R.id.btn_seek_up, Tuner.KEY_SEEK_UP);
        // Long-press SCAN = auto-store (AMS), mirroring the vendor app.
        findViewById(R.id.btn_scan).setOnLongClickListener(v -> {
            onTunerInteraction();
            tuner.sendKey(Tuner.KEY_AUTO_STORE);
            return true;
        });

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) freqDisplay.setText(formatFreq(sliderToFreq(progress)));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                onTunerInteraction();
                int freq = sliderToFreq(sb.getProgress());
                curFreq = freq;
                tuner.tune(freq, isFm());
                freqDisplay.setText(formatFreq(freq));
            }
        });

        LinearLayout presetRow = findViewById(R.id.preset_row);
        for (int i = 0; i < PRESET_SLOTS; i++) {
            final int slot = i;
            TextView p = new TextView(this);
            p.setTextSize(16);
            p.setTypeface(face(true));
            p.setGravity(Gravity.CENTER);
            p.setPadding(0, dp(14), 0, dp(14));
            p.setBackground(presetBg(false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(10);
            presetRow.addView(p, lp);
            presetViews.add(p);
            p.setOnClickListener(v -> {
                int f = getPreset(slot);
                if (f > 0) {
                    onTunerInteraction();
                    curFreq = f;
                    tuner.tune(f, isFm());
                    refreshTuner();
                }
            });
            p.setOnLongClickListener(v -> {
                savePreset(slot, curFreq);
                refreshPresets();
                Toast.makeText(this, getString(R.string.preset_saved, String.valueOf(slot + 1)),
                        Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    private void setCtl(int id, int key) {
        findViewById(id).setOnClickListener(v -> {
            onTunerInteraction();
            tuner.sendKey(key);
        });
    }

    /** Any tuner control reclaims the audio path if another source stole it. */
    private void onTunerInteraction() {
        if (tunerPaused || modeLost || tuner.getValidMode() != Tuner.SRC_RADIO) {
            claimTuner();
        }
    }

    /**
     * v0.8 — become the thing that is playing, on both sides of the fence: Android audio
     * focus first (so an app that is playing pauses, and a refusal — a call — keeps the
     * tuner quiet), then the MCU audio path. Returns false when focus was refused.
     */
    private boolean claimTuner() {
        if (!citizen().takeFocus(MediaCitizen.Focus.MEDIA)) return false;
        tunerPaused = false;
        modeLost = false;
        tuner.claimAudio();
        return true;
    }

    /** v0.8 — wheel/card pause for a tuner: hand the audio path back and go quiet. */
    private void pauseTuner() {
        tunerPaused = true;
        tuner.releaseAudio();
        citizen().releaseFocus();
        if (tab != TAB_NET) chipStatus.setText(R.string.status_paused);
        publishTunerSession();
    }

    /**
     * v0.8 — what the launcher's media card shows for FM/AM. The MCU owns the audio, so
     * this is presence and transport only: frequency as title, band as artist, no duration
     * (the card hides its seek bar for a live source), seek up/down riding next/previous.
     * When the mode belongs to another source the session goes idle rather than paused —
     * a pause card for audio we do not own would lie.
     */
    private void publishTunerSession() {
        if (tab == TAB_NET) return;
        if (!tuner.isConnected() || modeLost) {
            citizen().setIdle();
            return;
        }
        boolean fmNow = curBand <= 2;
        String unit = getString(fmNow ? R.string.unit_mhz : R.string.unit_khz);
        citizen().setMetadata(formatFreq(curFreq) + " " + unit,
                fmNow ? "FM" + (curBand + 1) : "AM", 0);
        citizen().setState(!tunerPaused, 0);
    }

    // ---- Tabs --------------------------------------------------------------

    private void selectTab(int newTab) {
        tab = newTab;
        boolean net = tab == TAB_NET;
        tunerPane.setVisibility(net ? View.GONE : View.VISIBLE);
        netPane.setVisibility(net ? View.VISIBLE : View.GONE);
        styleTab(tabFm, tab == TAB_FM);
        styleTab(tabAm, tab == TAB_AM);
        styleTab(tabNet, net);

        if (net) {
            // Hand the audio path back to Android media.
            tuner.releaseAudio();
            citizen().releaseFocus();
            chipStatus.setText("");
            updateNowPlaying();
        } else {
            stopPlayback();
            abandonStreamFocus();
            if (tuner.isConnected()) {
                claimTuner();
                // Nudge the MCU onto the requested band; refresh follows via events.
                boolean wantFm = tab == TAB_FM;
                boolean haveFm = curBand <= 2;
                if (wantFm != haveFm) tuner.sendKey(wantFm ? Tuner.KEY_BAND_FM : Tuner.KEY_BAND_AM);
            }
            refreshTuner();
        }
    }

    private void styleTab(TextView v, boolean active) {
        v.setTextColor(active ? cAccent : cText2);
        v.setBackground(active ? getDrawable(R.drawable.bg_tab_active) : null);
    }

    // ---- Tuner.Listener ----------------------------------------------------

    @Override public void onConnected() {
        if (tab != TAB_NET) {
            claimTuner();
            refreshTuner();
        }
    }

    @Override public void onDisconnected() {
        chipStatus.setText(R.string.status_no_gateway);
        publishTunerSession();
    }

    @Override public void onRadioEvent() {
        if (tab != TAB_NET) refreshTuner();
    }

    @Override public void onModeLost() {
        modeLost = true;
        if (tab != TAB_NET) {
            chipStatus.setText(R.string.status_mode_lost);
            citizen().releaseFocus();
            publishTunerSession();
        }
    }

    @Override public void onReclaimRequested() {
        if (tab != TAB_NET && !tunerPaused) claimTuner();
    }

    // ---- Tuner state -> UI -------------------------------------------------

    private boolean isFm() { return tab == TAB_FM; }

    private void refreshTuner() {
        if (!tuner.isConnected()) {
            chipStatus.setText(R.string.status_connecting);
            publishTunerSession();
            return;
        }
        int freq = tuner.getFreq();
        int band = tuner.getBand();
        if (band >= 0) curBand = band;
        boolean fmNow = curBand <= 2;

        // Follow the hardware band: if the MCU is on the other band than the
        // selected tab (e.g. SWC band button), switch the tab to match.
        if (tab == TAB_FM && !fmNow) { tab = TAB_AM; styleTab(tabFm, false); styleTab(tabAm, true); }
        else if (tab == TAB_AM && fmNow) { tab = TAB_FM; styleTab(tabAm, false); styleTab(tabFm, true); }

        if (freq > 0) curFreq = freq;
        bandLabel.setText(fmNow ? "FM" + (curBand + 1) : "AM");
        freqUnit.setText(fmNow ? R.string.unit_mhz : R.string.unit_khz);
        freqMin.setText(fmNow ? "87.5" : "530");
        freqMax.setText(fmNow ? "107.9" : "1710");
        if (!dragging) {
            freqDisplay.setText(formatFreq(curFreq));
            int min = fmNow ? FM_MIN : AM_MIN, max = fmNow ? FM_MAX : AM_MAX,
                step = fmNow ? FM_STEP : AM_STEP;
            slider.setMax((max - min) / step);
            slider.setProgress(Math.max(0, Math.min(slider.getMax(), (curFreq - min) / step)));
        }

        if (!modeLost && !tunerPaused) {
            StringBuilder sb = new StringBuilder();
            if (tuner.getStereoIcon()) sb.append(getString(R.string.chip_stereo));
            if (tuner.getRdsState()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(getString(R.string.chip_rds));
            }
            chipStatus.setText(sb);
        }
        refreshPresets();
        publishTunerSession();
    }

    private int sliderToFreq(int progress) {
        boolean fm = isFm();
        return (fm ? FM_MIN : AM_MIN) + progress * (fm ? FM_STEP : AM_STEP);
    }

    private String formatFreq(int freq) {
        if (isFm()) return String.format(Locale.US, "%d.%02d", freq / 100, freq % 100);
        return String.valueOf(freq);
    }

    // ---- Presets -----------------------------------------------------------

    private String presetKey(int slot) { return (isFm() ? "fm" : "am") + slot; }
    private int getPreset(int slot) { return prefs.getInt(presetKey(slot), 0); }
    private void savePreset(int slot, int freq) { prefs.edit().putInt(presetKey(slot), freq).apply(); }

    private void refreshPresets() {
        for (int i = 0; i < presetViews.size(); i++) {
            TextView p = presetViews.get(i);
            int f = getPreset(i);
            boolean active = f > 0 && f == curFreq;
            p.setText(f > 0 ? formatFreq(f) : getString(R.string.preset_empty));
            p.setTextColor(active ? cAccent : (f > 0 ? cText : cText3));
            p.setBackground(presetBg(active));
        }
    }

    /** Preset chip: a rounded dim pill normally, a bordered hard-edged square under Riposte. */
    private GradientDrawable presetBg(boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14) * Palette.cornerScale(this));
        bg.setColor(active ? cAccentDim : cSurface2);
        if (Palette.hardEdge(this)) {
            bg.setStroke(dp(2), active ? cAccent : (cStroke | 0xFF000000));
        }
        return bg;
    }

    /** The face the active theme asks for (brand mono under Riposte, sans otherwise). */
    private Typeface face(boolean bold) {
        return Palette.typeface(this, bold);
    }

    // ---- Lifecycle ---------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        if (tab != TAB_NET && tuner.isConnected() && !tunerPaused) {
            claimTuner();
            refreshTuner();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (citizen != null) {
            citizen.release();
            citizen = null;
        }
        tuner.unbind();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    // =======================================================================
    // Streaming (NET tab) — carried over from the previous internet-radio app.
    // =======================================================================

    private void bindNetViews() {
        buildStations();
        ListView list = findViewById(R.id.list);
        nowTitle = findViewById(R.id.now_title);
        nowStatus = findViewById(R.id.now_status);
        nowAvatar = findViewById(R.id.now_avatar);
        btnPlay = findViewById(R.id.btn_play);

        adapter = new StationAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            Object o = rows.get(pos);
            if (o instanceof Station) playStation((Station) o);
        });
        btnPlay.setOnClickListener(v -> toggle());
        updateNowPlaying();
    }

    /** Curated always-free public internet streams, grouped by genre. */
    private void buildStations() {
        add("Groove Salad", "Chillout", "https://ice1.somafm.com/groovesalad-128-mp3");
        add("Secret Agent", "Chillout", "https://ice1.somafm.com/secretagent-128-mp3");
        add("Drone Zone", "Ambient", "https://ice1.somafm.com/dronezone-128-mp3");
        add("Deep Space One", "Ambient", "https://ice1.somafm.com/deepspaceone-128-mp3");
        add("Indie Pop Rocks", "Indie & Rock", "https://ice1.somafm.com/indiepop-128-mp3");
        add("Underground 80s", "Indie & Rock", "https://ice1.somafm.com/u80s-128-mp3");
        add("DEF CON Radio", "Beats & Electronic", "https://ice1.somafm.com/defcon-128-mp3");
        add("Fluid", "Beats & Electronic", "https://ice1.somafm.com/fluid-128-mp3");
        add("Boot Liquor", "Americana", "https://ice1.somafm.com/bootliquor-128-mp3");

        String lastGenre = null;
        for (Station s : stations) {
            if (!s.genre.equals(lastGenre)) { rows.add(s.genre); lastGenre = s.genre; }
            rows.add(s);
        }
    }

    private void add(String n, String g, String u) { stations.add(new Station(n, g, u)); }

    private void requestStreamFocus() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .build();
        }
        am.requestAudioFocus(focusRequest);
    }

    private void abandonStreamFocus() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null && focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
    }

    private void playStation(Station s) {
        current = s;
        state = BUFFERING;
        requestStreamFocus();
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                player.setOnPreparedListener(this);
                player.setOnErrorListener(this);
            } else {
                player.reset();
            }
            player.setDataSource(s.url);
            player.prepareAsync();
        } catch (Exception e) {
            onStreamError();
            return;
        }
        updateNowPlaying();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        state = PLAYING;
        mp.start();
        updateNowPlaying();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        onStreamError();
        return true; // handled; prevents OnCompletion from firing
    }

    private void onStreamError() {
        Toast.makeText(this, R.string.stream_error, Toast.LENGTH_SHORT).show();
        state = STOPPED;
        current = null;
        if (player != null) { try { player.reset(); } catch (Exception ignored) {} }
        updateNowPlaying();
        adapter.notifyDataSetChanged();
    }

    private void toggle() {
        if (state != STOPPED) {
            stopPlayback();
        } else if (current != null) {
            playStation(current);
        } else if (!stations.isEmpty()) {
            playStation(stations.get(0));
        }
    }

    private void stopPlayback() {
        if (player != null) { try { player.reset(); } catch (Exception ignored) {} }
        state = STOPPED;
        updateNowPlaying();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private MediaCitizen citizen() {
        if (citizen == null) {
            citizen = MediaCitizen.attach(this, "radio", new MediaCitizen.Transport() {
                @Override public void onPlay() {
                    if (tab == TAB_NET) togglePlayFromSession();
                    else if (claimTuner()) refreshTuner();
                }

                @Override public void onPause() {
                    if (tab == TAB_NET) stopPlayback();
                    else pauseTuner();
                }

                @Override public void onNext() {
                    if (tab == TAB_NET) step(1);
                    else { onTunerInteraction(); tuner.sendKey(Tuner.KEY_SEEK_UP); }
                }

                @Override public void onPrevious() {
                    if (tab == TAB_NET) step(-1);
                    else { onTunerInteraction(); tuner.sendKey(Tuner.KEY_SEEK_DOWN); }
                }

                @Override public void onStop() {
                    if (tab == TAB_NET) stopPlayback();
                    else pauseTuner();
                }

                @Override public void onDuck(boolean duck) {
                    // A stream that ducks to a whisper is just noise; pause instead.
                    // The tuner has nothing to duck: the MCU owns its volume.
                    if (tab == TAB_NET && duck) stopPlayback();
                }
            });
        }
        return citizen;
    }

    /** Play the current station, or the first one if nothing has been chosen yet. */
    private void togglePlayFromSession() {
        if (state != STOPPED) {
            return;
        }
        if (current != null) {
            playStation(current);
        } else if (!stations.isEmpty()) {
            playStation(stations.get(0));
        }
    }

    /** Move [delta] stations from the current one, wrapping. */
    private void step(int delta) {
        if (stations.isEmpty()) {
            return;
        }
        int i = current == null ? -1 : stations.indexOf(current);
        int next = ((i + delta) % stations.size() + stations.size()) % stations.size();
        playStation(stations.get(next));
    }

    private void updateNowPlaying() {
        // One funnel for every stream state change, so the card and the wheel cannot drift
        // from the UI. Only while NET owns the screen — the tuner has its own publisher —
        // and an untouched stream list publishes nothing rather than an empty pause card.
        if (tab == TAB_NET) {
            if (current == null && state == STOPPED) {
                citizen().setIdle();
            } else {
                if (current != null) citizen().setMetadata(current.name, current.genre, 0);
                citizen().setState(state == PLAYING, 0);
            }
        }

        if (current == null) {
            nowTitle.setText(R.string.nothing_playing);
            nowStatus.setText(R.string.tap_to_play);
            nowAvatar.setColorFilter(cText2);
            setBadge(nowAvatar, cSurface2);
            btnPlay.setImageResource(R.drawable.ic_play);
            return;
        }
        nowTitle.setText(current.name);
        String label;
        if (state == BUFFERING) label = getString(R.string.buffering);
        else if (state == PLAYING) label = getString(R.string.on_air);
        else label = getString(R.string.stopped);
        nowStatus.setText(label + "  ·  " + current.genre);

        boolean live = state != STOPPED;
        nowAvatar.setColorFilter(live ? cAccent : cText2);
        setBadge(nowAvatar, live ? cAccentDim : cSurface2);
        btnPlay.setImageResource(live ? R.drawable.ic_stop : R.drawable.ic_play);
    }

    /** Avatar badge: a circle, or a hard-edged square when the theme asks for it. */
    private void setBadge(ImageView v, int color) {
        GradientDrawable badge = new GradientDrawable();
        if (!Palette.hardEdge(this)) badge.setShape(GradientDrawable.OVAL);
        badge.setColor(color);
        v.setBackground(badge);
    }

    private final class StationAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof Station ? 1 : 0; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { return rows.get(p) instanceof Station; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object item = rows.get(position);

            if (item instanceof String) { // genre header (Overline style)
                TextView header = (convertView instanceof TextView) ? (TextView) convertView : null;
                if (header == null) {
                    header = new TextView(MainActivity.this);
                    header.setTextColor(cText3);
                    header.setTextSize(11);
                    header.setLetterSpacing(0.14f);
                    header.setAllCaps(true);
                    header.setTypeface(face(true));
                    header.setPadding(dp(16), dp(18), dp(16), dp(8));
                }
                header.setText((String) item);
                return header;
            }

            LinearLayout row;
            ImageView avatar;
            TextView title, genre;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                avatar = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                title = (TextView) col.getChildAt(0);
                genre = (TextView) col.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padH = dp(16);
                row.setPadding(padH, dp(8), padH, dp(8));
                row.setMinimumHeight(dp(68));

                avatar = new ImageView(MainActivity.this);
                avatar.setImageResource(R.drawable.ic_radio);
                avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int ap = dp(11);
                avatar.setPadding(ap, ap, ap, ap);
                row.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

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
                title.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                genre = new TextView(MainActivity.this);
                genre.setTextSize(13);
                genre.setTextColor(cText2);
                genre.setSingleLine(true);
                genre.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                gp.topMargin = dp(2);
                col.addView(genre, gp);
            }

            Station s = (Station) item;
            title.setText(s.name);
            genre.setText(s.genre);
            boolean active = s == current;

            setBadge(avatar, active ? cAccentDim : cSurface2);
            avatar.setColorFilter(active ? cAccent : cText2);
            title.setTextColor(active ? cAccent : cText);

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
}
