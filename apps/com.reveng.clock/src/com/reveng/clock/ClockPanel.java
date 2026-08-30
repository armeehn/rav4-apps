package com.reveng.clock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import com.reveng.design.Palette;

/** Live local clock (updates every second) with date + a row of world-clock cards. */
class ClockPanel extends LinearLayout {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView bigTime, bigDate, tzLabel;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());

    private final String[][] cities = {
            {"New York", "America/New_York"},
            {"London", "Europe/London"},
            {"Tokyo", "Asia/Tokyo"},
    };
    private TextView[] cityTimes;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            long delay = 1000 - (System.currentTimeMillis() % 1000);
            ui.postDelayed(this, delay);
        }
    };

    ClockPanel(Context c) {
        super(c);
        build();
    }

    private void build() {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        int pad = Ui.dp(getContext(), 32);
        setPadding(pad, pad, pad, pad);

        tzLabel = Ui.text(getContext(), R.style.Overline, "Local • " + TimeZone.getDefault().getID());
        tzLabel.setGravity(Gravity.CENTER);
        addView(tzLabel);

        bigTime = Ui.styled(getContext(), R.style.Display);
        bigTime.setTextSize(120);
        bigTime.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(getContext(), 4);
        addView(bigTime, tlp);

        bigDate = Ui.styled(getContext(), R.style.H1);
        bigDate.setTextColor(Palette.color(getContext(), R.color.text2));
        bigDate.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(getContext(), 6);
        addView(bigDate, dlp);

        // world-clock row
        LinearLayout worldLabelRow = new LinearLayout(getContext());
        worldLabelRow.setOrientation(HORIZONTAL);
        // section overline
        TextView worldLabel = Ui.text(getContext(), R.style.Overline, getContext().getString(R.string.world_clock));
        LinearLayout.LayoutParams wllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wllp.topMargin = Ui.dp(getContext(), 28);
        wllp.bottomMargin = Ui.dp(getContext(), 10);
        addView(worldLabel, wllp);

        LinearLayout cardsRow = new LinearLayout(getContext());
        cardsRow.setOrientation(HORIZONTAL);
        cardsRow.setGravity(Gravity.CENTER);
        addView(cardsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cityTimes = new TextView[cities.length];
        for (int i = 0; i < cities.length; i++) {
            LinearLayout card = Ui.card(getContext(), VERTICAL, 18);
            card.setGravity(Gravity.CENTER);
            card.setMinimumWidth(Ui.dp(getContext(), 180));

            TextView name = Ui.text(getContext(), R.style.Overline, cities[i][0].toUpperCase(Locale.US));
            name.setGravity(Gravity.CENTER);
            card.addView(name);

            TextView t = Ui.styled(getContext(), R.style.H1);
            t.setTextSize(30);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Ui.dp(getContext(), 4);
            card.addView(t, clp);
            cityTimes[i] = t;

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) cardLp.leftMargin = Ui.dp(getContext(), 16);
            cardsRow.addView(card, cardLp);
        }

        update();
    }

    private void update() {
        Date now = new Date();
        timeFmt.setTimeZone(TimeZone.getDefault());
        bigTime.setText(timeFmt.format(now));
        bigDate.setText(dateFmt.format(now));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (int i = 0; i < cities.length; i++) {
            hm.setTimeZone(TimeZone.getTimeZone(cities[i][1]));
            cityTimes[i].setText(hm.format(now));
        }
    }

    void onShow() {
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    void onHide() {
        ui.removeCallbacks(tick);
    }
}
