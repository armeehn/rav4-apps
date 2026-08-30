package com.reveng.clock;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Clean-room "Clock" app for the RAV4 GT6 head unit. A left navigation rail
 * switches between four code-built panels (Clock / Alarm / Stopwatch / Timer),
 * all styled from the shared design system. Pure android.* framework, no AndroidX.
 */
public class MainActivity extends Activity {

    private final String[] tabLabels = {"Clock", "Alarm", "Stopwatch", "Timer"};
    private int[] tabIcons;

    private View[] panels;
    private ClockPanel clockPanel;
    private AlarmPanel alarmPanel;
    private StopwatchPanel stopwatchPanel;
    private TimerPanel timerPanel;

    private LinearLayout[] navItems;
    private int selected = 0;

    private int cAccent, cAccentDim, cText3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tabIcons = new int[]{
                R.drawable.ic_clock, R.drawable.ic_alarm,
                R.drawable.ic_stopwatch, R.drawable.ic_timer};

        cAccent = getColor(R.color.accent);
        cAccentDim = getColor(R.color.accent_dim);
        cText3 = getColor(R.color.text3);

        // re-arm any enabled alarms (idempotent) in case they were lost
        new AlarmStore(this).rescheduleAll();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(getColor(R.color.bg));

        root.addView(buildNavRail(), new LinearLayout.LayoutParams(
                Ui.dp(this, 116), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout content = new FrameLayout(this);
        clockPanel = new ClockPanel(this);
        alarmPanel = new AlarmPanel(this);
        stopwatchPanel = new StopwatchPanel(this);
        timerPanel = new TimerPanel(this);
        panels = new View[]{clockPanel, alarmPanel, stopwatchPanel, timerPanel};
        for (View p : panels) {
            content.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            p.setVisibility(View.GONE);
        }
        root.addView(content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
        select(0);
    }

    private View buildNavRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(getColor(R.color.surface));
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        int pv = Ui.dp(this, 18);
        rail.setPadding(0, pv, 0, pv);

        navItems = new LinearLayout[tabLabels.length];
        for (int i = 0; i < tabLabels.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            int ip = Ui.dp(this, 10);
            item.setPadding(ip, Ui.dp(this, 14), ip, Ui.dp(this, 14));

            ImageView icon = new ImageView(this);
            icon.setImageResource(tabIcons[i]);
            icon.setColorFilter(cText3);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));

            TextView label = Ui.text(this, R.style.Overline, tabLabels[i]);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = Ui.dp(this, 6);
            item.addView(label, llp);

            item.setOnClickListener(v -> select(idx));

            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 92), ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = Ui.dp(this, 6);
            ilp.bottomMargin = Ui.dp(this, 6);
            rail.addView(item, ilp);
            navItems[i] = item;
        }
        return rail;
    }

    private void select(int idx) {
        if (idx == selected && panels[idx].getVisibility() == View.VISIBLE) return;

        // notify hide on previous
        onPanelHide(selected);

        selected = idx;
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            LinearLayout item = navItems[i];
            ImageView icon = (ImageView) item.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            boolean active = i == idx;
            icon.setColorFilter(active ? cAccent : cText3);
            label.setTextColor(active ? cAccent : cText3);
            if (active) {
                item.setBackground(Ui.roundedFill(cAccentDim, Ui.dp(this, 18)));
            } else {
                item.setBackground(null);
            }
        }
        onPanelShow(idx);
    }

    private void onPanelShow(int idx) {
        switch (idx) {
            case 0: clockPanel.onShow(); break;
            case 1: alarmPanel.render(); break;
            case 2: stopwatchPanel.onShow(); break;
            case 3: timerPanel.onShow(); break;
        }
    }

    private void onPanelHide(int idx) {
        switch (idx) {
            case 0: clockPanel.onHide(); break;
            case 2: stopwatchPanel.onHide(); break;
            case 3: timerPanel.onHide(); break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        onPanelShow(selected);
    }

    @Override
    protected void onPause() {
        super.onPause();
        onPanelHide(selected);
    }
}
