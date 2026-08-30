package com.reveng.clock;

import android.app.Activity;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.reveng.design.Palette;

/** Full-screen alarm ring: alarm-stream Ringtone + vibration, Dismiss / Snooze. */
public class RingActivity extends Activity {

    private static final int SNOOZE_MIN = 5;

    private Ringtone ringtone;
    private Vibrator vibrator;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int alarmId;
    private String label;
    private String timeText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wakeAndShow();

        alarmId = getIntent().getIntExtra(AlarmStore.EXTRA_ID, 0);
        label = getIntent().getStringExtra(AlarmStore.EXTRA_LABEL);
        timeText = getIntent().getStringExtra(AlarmStore.EXTRA_TIME);
        if (label == null) label = "";
        if (timeText == null) timeText = "";

        setContentView(buildUi());
        startRinging();
    }

    private void wakeAndShow() {
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }

    private View buildUi() {
        int cText = Palette.color(this, R.color.text);
        int cText2 = Palette.color(this, R.color.text2);
        int cAccent = Palette.color(this, R.color.accent);
        int cBg = Palette.color(this, R.color.bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(cBg);
        int pad = Ui.dp(this, 40);
        root.setPadding(pad, pad, pad, pad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_alarm);
        icon.setColorFilter(cAccent);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        ilp.bottomMargin = Ui.dp(this, 16);
        root.addView(icon, ilp);

        TextView time = Ui.text(this, R.style.Display, timeText.isEmpty() ? "Alarm" : timeText);
        time.setGravity(Gravity.CENTER);
        root.addView(time);

        TextView lbl = Ui.text(this, R.style.H2, label.isEmpty() ? "Alarm" : label);
        lbl.setTextColor(cText2);
        lbl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, 8);
        root.addView(lbl, llp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 40);
        root.addView(buttons, blp);

        TextView snooze = Ui.pill(this, getString(R.string.snooze) + "  (" + SNOOZE_MIN + " min)", false, cText);
        snooze.setOnClickListener(v -> snooze());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = Ui.dp(this, 16);
        buttons.addView(snooze, slp);

        TextView dismiss = Ui.pill(this, getString(R.string.dismiss), true, 0xFFFFFFFF);
        dismiss.setOnClickListener(v -> dismiss());
        buttons.addView(dismiss);

        return root;
    }

    private void startRinging() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), uri);
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Exception ignored) {}
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 600, 800};
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}
        // Auto-dismiss after 60s so it never rings forever.
        ui.postDelayed(this::dismiss, 60000);
    }

    private void stopRinging() {
        ui.removeCallbacksAndMessages(null);
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Exception ignored) {}
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
    }

    private void dismiss() {
        stopRinging();
        finish();
    }

    private void snooze() {
        stopRinging();
        Alarm snoozed = new Alarm(alarmId, 0, 0, true, label);
        // Schedule a one-shot in SNOOZE_MIN minutes using the same receiver path.
        long when = System.currentTimeMillis() + SNOOZE_MIN * 60000L;
        new AlarmStore(this).scheduleAt(alarmId, when, label, timeText);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
    }
}
