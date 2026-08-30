package com.ripostelabs.clock;

import android.content.Context;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import com.ripostelabs.design.Palette;

/** Countdown timer with presets, start/pause/reset, and an alarm sound on finish. */
class TimerPanel extends LinearLayout {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long durationMs = 5 * 60000L;   // selected preset
    private long remainingMs = 5 * 60000L;  // remaining when paused
    private long endAt = 0;                  // SystemClock.elapsedRealtime target while running
    private boolean running = false;
    private boolean finished = false;
    private Ringtone ringtone;

    private TextView big, status;
    private ImageButton startBtn;
    private TextView startLabel;

    private final long[] presets = {60000L, 5 * 60000L, 10 * 60000L, 30 * 60000L};
    private final String[] presetLabels = {"1 min", "5 min", "10 min", "30 min"};

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long rem = endAt - SystemClock.elapsedRealtime();
            if (rem <= 0) {
                remainingMs = 0;
                big.setText(fmt(0));
                onFinished();
                return;
            }
            big.setText(fmt(rem));
            ui.postDelayed(this, 100);
        }
    };

    TimerPanel(Context c) {
        super(c);
        build();
    }

    private void build() {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        int pad = Ui.dp(getContext(), 28);
        setPadding(pad, pad, pad, pad);

        status = Ui.text(getContext(), R.style.Overline, getContext().getString(R.string.tab_timer));
        status.setGravity(Gravity.CENTER);
        addView(status);

        big = Ui.styled(getContext(), R.style.Display);
        big.setTextSize(120);
        big.setGravity(Gravity.CENTER);
        big.setText(fmt(durationMs));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(getContext(), 4);
        addView(big, blp);

        // preset pills
        LinearLayout presetRow = new LinearLayout(getContext());
        presetRow.setOrientation(HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prlp.topMargin = Ui.dp(getContext(), 22);
        addView(presetRow, prlp);

        for (int i = 0; i < presets.length; i++) {
            final long ms = presets[i];
            TextView pill = Ui.pill(getContext(), presetLabels[i], false,
                    Palette.color(getContext(), R.color.text));
            pill.setOnClickListener(v -> setPreset(ms));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) plp.leftMargin = Ui.dp(getContext(), 12);
            presetRow.addView(pill, plp);
        }

        // controls
        LinearLayout controls = new LinearLayout(getContext());
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(getContext(), 28);
        addView(controls, clp);

        ImageButton reset = Ui.iconButton(getContext(), R.drawable.ic_back,
                Palette.color(getContext(), R.color.text2), 64);
        reset.setBackgroundResource(R.drawable.btn_ghost);
        reset.setOnClickListener(v -> reset());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 64), Ui.dp(getContext(), 64));
        rlp.gravity = Gravity.CENTER;
        controls.addView(reset, rlp);

        LinearLayout startWrap = new LinearLayout(getContext());
        startWrap.setOrientation(VERTICAL);
        startWrap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swlp.leftMargin = Ui.dp(getContext(), 20);
        controls.addView(startWrap, swlp);

        startBtn = Ui.fab(getContext(), R.drawable.ic_play, 84);
        startBtn.setOnClickListener(v -> toggle());
        startWrap.addView(startBtn, new LinearLayout.LayoutParams(Ui.dp(getContext(), 84), Ui.dp(getContext(), 84)));
        startLabel = Ui.text(getContext(), R.style.Overline, getContext().getString(R.string.start));
        startLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sllp.topMargin = Ui.dp(getContext(), 8);
        startWrap.addView(startLabel, sllp);
    }

    private void setPreset(long ms) {
        stopSound();
        running = false;
        finished = false;
        ui.removeCallbacks(tick);
        durationMs = ms;
        remainingMs = ms;
        big.setText(fmt(ms));
        status.setText(R.string.tab_timer);
        big.setTextColor(Palette.color(getContext(), R.color.text));
        startBtn.setImageResource(R.drawable.ic_play);
        startLabel.setText(R.string.start);
    }

    private void toggle() {
        if (finished) { reset(); return; }
        if (running) {
            // pause
            remainingMs = endAt - SystemClock.elapsedRealtime();
            if (remainingMs < 0) remainingMs = 0;
            running = false;
            ui.removeCallbacks(tick);
            startBtn.setImageResource(R.drawable.ic_play);
            startLabel.setText(R.string.start);
            status.setText(R.string.paused);
        } else {
            if (remainingMs <= 0) remainingMs = durationMs;
            endAt = SystemClock.elapsedRealtime() + remainingMs;
            running = true;
            ui.post(tick);
            startBtn.setImageResource(R.drawable.ic_pause);
            startLabel.setText(R.string.stop);
            status.setText(R.string.running);
        }
    }

    private void reset() {
        stopSound();
        running = false;
        finished = false;
        ui.removeCallbacks(tick);
        remainingMs = durationMs;
        big.setText(fmt(durationMs));
        big.setTextColor(Palette.color(getContext(), R.color.text));
        status.setText(R.string.tab_timer);
        startBtn.setImageResource(R.drawable.ic_play);
        startLabel.setText(R.string.start);
    }

    private void onFinished() {
        running = false;
        finished = true;
        ui.removeCallbacks(tick);
        status.setText(R.string.finished);
        big.setTextColor(Palette.color(getContext(), R.color.accent));
        startBtn.setImageResource(R.drawable.ic_play);
        startLabel.setText(R.string.reset);
        playSound();
    }

    private void playSound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ringtone = RingtoneManager.getRingtone(getContext().getApplicationContext(), uri);
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) {}
        // auto-stop the sound after 30s if untouched
        ui.postDelayed(this::stopSound, 30000);
    }

    private void stopSound() {
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Exception ignored) {}
    }

    static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = (ms + 999) / 1000; // round up so it shows the ceiling second
        long s = totalSec % 60;
        long m = (totalSec / 60) % 60;
        long h = totalSec / 3600;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    void onHide() {
        ui.removeCallbacks(tick);
    }

    void onShow() {
        if (running) ui.post(tick);
    }
}
