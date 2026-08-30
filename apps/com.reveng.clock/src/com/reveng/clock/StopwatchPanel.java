package com.reveng.clock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import com.reveng.design.Palette;

/** Stopwatch with start/stop, lap and reset, driven by a Handler ticker. */
class StopwatchPanel extends LinearLayout {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private long baseElapsed = 0;      // accumulated while paused
    private long startedAt = 0;        // SystemClock.elapsedRealtime at last start
    private long lastLapTotal = 0;
    private final ArrayList<Long> laps = new ArrayList<>();

    private TextView big;
    private TextView startLabel;
    private ImageButton startBtn, lapBtn;
    private LinearLayout lapsContainer;
    private View lapsEmpty;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (running) {
                big.setText(fmt(elapsed()));
                ui.postDelayed(this, 31);
            }
        }
    };

    StopwatchPanel(Context c) {
        super(c);
        build();
    }

    private long elapsed() {
        return baseElapsed + (running ? SystemClock.elapsedRealtime() - startedAt : 0);
    }

    private void build() {
        setOrientation(HORIZONTAL);
        int pad = Ui.dp(getContext(), 28);
        setPadding(pad, pad, pad, pad);

        // left: big time + controls
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(VERTICAL);
        left.setGravity(Gravity.CENTER);
        addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));

        big = Ui.styled(getContext(), R.style.Display);
        big.setTextSize(96);
        big.setGravity(Gravity.CENTER);
        big.setText(fmt(0));
        left.addView(big);

        LinearLayout controls = new LinearLayout(getContext());
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(getContext(), 28);
        left.addView(controls, clp);

        // reset
        ImageButton reset = Ui.iconButton(getContext(), R.drawable.ic_back,
                Palette.color(getContext(), R.color.text2), 64);
        reset.setBackgroundResource(R.drawable.btn_ghost);
        reset.setOnClickListener(v -> reset());
        controls.addView(reset, ctrlLp(64, 0));

        // start/stop primary
        LinearLayout startWrap = new LinearLayout(getContext());
        startWrap.setOrientation(VERTICAL);
        startWrap.setGravity(Gravity.CENTER);
        startBtn = Ui.fab(getContext(), R.drawable.ic_play, 84);
        startBtn.setOnClickListener(v -> toggle());
        startWrap.addView(startBtn, new LinearLayout.LayoutParams(Ui.dp(getContext(), 84), Ui.dp(getContext(), 84)));
        startLabel = Ui.text(getContext(), R.style.Overline, getContext().getString(R.string.start));
        startLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sllp.topMargin = Ui.dp(getContext(), 8);
        startWrap.addView(startLabel, sllp);
        controls.addView(startWrap, ctrlLp(ViewGroup.LayoutParams.WRAP_CONTENT, 20));

        // lap
        lapBtn = Ui.iconButton(getContext(), R.drawable.ic_timer,
                Palette.color(getContext(), R.color.text2), 64);
        lapBtn.setBackgroundResource(R.drawable.btn_ghost);
        lapBtn.setOnClickListener(v -> lap());
        controls.addView(lapBtn, ctrlLp(64, 20));

        // right: laps list
        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = Ui.dp(getContext(), 24);
        addView(right, rlp);

        TextView lapsTitle = Ui.text(getContext(), R.style.Overline, getContext().getString(R.string.laps));
        LinearLayout.LayoutParams ltlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ltlp.bottomMargin = Ui.dp(getContext(), 10);
        right.addView(lapsTitle, ltlp);

        ScrollView scroll = new ScrollView(getContext());
        lapsContainer = new LinearLayout(getContext());
        lapsContainer.setOrientation(VERTICAL);
        scroll.addView(lapsContainer);
        right.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        lapsEmpty = Ui.text(getContext(), R.style.Caption, getContext().getString(R.string.no_laps));
        renderLaps();
    }

    private LinearLayout.LayoutParams ctrlLp(int size, int leftDp) {
        int s = size < 0 ? size : Ui.dp(getContext(), size);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s,
                size < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : s);
        lp.leftMargin = Ui.dp(getContext(), leftDp);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private void toggle() {
        if (running) {
            baseElapsed = elapsed();
            running = false;
            ui.removeCallbacks(tick);
            startBtn.setImageResource(R.drawable.ic_play);
            startLabel.setText(R.string.start);
        } else {
            startedAt = SystemClock.elapsedRealtime();
            running = true;
            ui.post(tick);
            startBtn.setImageResource(R.drawable.ic_pause);
            startLabel.setText(R.string.stop);
        }
    }

    private void lap() {
        if (!running && baseElapsed == 0) return;
        long total = elapsed();
        long lapTime = total - lastLapTotal;
        lastLapTotal = total;
        laps.add(0, lapTime);
        renderLaps();
    }

    private void reset() {
        running = false;
        ui.removeCallbacks(tick);
        baseElapsed = 0;
        lastLapTotal = 0;
        laps.clear();
        big.setText(fmt(0));
        startBtn.setImageResource(R.drawable.ic_play);
        startLabel.setText(R.string.start);
        renderLaps();
    }

    private void renderLaps() {
        lapsContainer.removeAllViews();
        if (laps.isEmpty()) {
            lapsContainer.addView(lapsEmpty);
            return;
        }
        int n = laps.size();
        for (int i = 0; i < n; i++) {
            LinearLayout card = Ui.card(getContext(), HORIZONTAL, 14);
            card.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = Ui.dp(getContext(), 8);
            card.setLayoutParams(clp);

            TextView idx = Ui.text(getContext(), R.style.Caption,
                    getContext().getString(R.string.lap) + " " + (n - i));
            card.addView(idx, Ui.weight(1f));

            TextView t = Ui.styled(getContext(), R.style.H2);
            t.setText(fmt(laps.get(i)));
            card.addView(t);
            lapsContainer.addView(card);
        }
    }

    static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long cs = (ms / 10) % 100;
        long totalSec = ms / 1000;
        long s = totalSec % 60;
        long m = (totalSec / 60) % 60;
        long h = totalSec / 3600;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
        }
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs);
    }

    void onHide() {
        // keep timing across tab switches, just stop UI refresh
        ui.removeCallbacks(tick);
    }

    void onShow() {
        if (running) ui.post(tick);
        else big.setText(fmt(elapsed()));
    }
}
