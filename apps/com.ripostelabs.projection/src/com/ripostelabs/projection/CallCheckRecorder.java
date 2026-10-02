package com.ripostelabs.projection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.ripostelabs.projection.ns.CallCheck;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The owner's call audio check on the device: arms a {@link CallCheck} take when the Mic
 * screen's button is tapped during a call, shows a notification for as long as it records, and
 * writes the WAVs when the take ends.
 *
 * <pre>
 *   MicSettingsActivity ─ tap ─▶ start() ─▶ CallCheck.arm
 *   MicSource pump      ─ RAW / ECHO_CANCELLED / PROCESSED ─▶ feed ─┐
 *   ZlinkService.onAudio ─ DOWNLINK ──────────────────────────▶ feed ─┴▶ take full or call over ─▶ files
 * </pre>
 *
 * Files go to /sdcard/Riposte/CallCheck when the app holds All files access, else to the app's
 * own folder on shared storage (/sdcard/Android/data/&lt;pkg&gt;/files/CallCheck). Nothing ever
 * starts a take but the button. Beside the WAVs, STAMP-echo.txt holds what AEC3 measured at the
 * end of the take (ERLE, echo return, delay): the echo answer from one real call, without a
 * second one. ns-train/echo_report.py measures the same from the WAVs, offline.
 */
final class CallCheckRecorder {

    /** What a tap did. */
    enum Start { STARTED, NO_CALL, BUSY }

    static final int SECONDS = 20;

    private static final String TAG = "Projection";
    private static final String DIR = "Riposte/CallCheck";
    private static final String APP_DIR = "CallCheck";
    private static final String CHANNEL_ID = "callcheck";
    private static final int NOTIFICATION_ID = 0xCA11;
    private static final String STAMP = "yyyyMMdd-HHmmss";

    private static final CallCheckRecorder INSTANCE = new CallCheckRecorder();

    private final CallCheck take = new CallCheck(SECONDS);
    private Context context;
    private int micRate;
    private int downRate;
    private int downChannels;
    private boolean writing;
    private String lastFolder;
    /** The echo canceller's last once-a-second report; written with the take. */
    private String echoStats = "";

    static CallCheckRecorder get() {
        return INSTANCE;
    }

    private CallCheckRecorder() {
    }

    /** The Mic screen's button. Only a running capture (a call or Siri) can be checked. */
    synchronized Start start(Context ctx) {
        if (micRate <= 0) {
            return Start.NO_CALL;
        }
        if (writing || !take.arm(micRate, downRate, downChannels)) {
            return Start.BUSY;
        }

        context = ctx.getApplicationContext();
        notifyRecording();
        Log.i(TAG, "callcheck: recording " + SECONDS + " s of the mic path");
        return Start.STARTED;
    }

    synchronized void micOpened(int rate) {
        micRate = rate;
    }

    /**
     * MicStop or the session going down: the call is over, and a take cut short keeps what it
     * has. An A/B switch reopens the capture without this, so a take runs on across it.
     */
    void callEnded() {
        synchronized (this) {
            micRate = 0;
        }
        take.finish();
        flushIfDone();
    }

    /** The capture's echo canceller report, once a second while the mic runs. */
    synchronized void echoStats(String line) {
        echoStats = line;
    }

    synchronized void downlinkFormat(int rate, int channels) {
        downRate = rate;
        downChannels = channels;
    }

    void feed(CallCheck.Tap tap, byte[] pcm, int off, int len) {
        if (take.state() != CallCheck.State.RECORDING) {
            return;
        }

        take.feed(tap, pcm, off, len);
        flushIfDone();
    }

    int secondsLeft() {
        return take.secondsLeft();
    }

    boolean recording() {
        return take.state() == CallCheck.State.RECORDING;
    }

    /** Where the last take was written; null before the first. */
    synchronized String lastFolder() {
        return lastFolder;
    }

    /** The take filled or the call ended: write once, off the audio threads. */
    private void flushIfDone() {
        synchronized (this) {
            if (take.state() != CallCheck.State.DONE || writing) {
                return;
            }
            writing = true;
        }
        new Thread(this::write, "callcheck-write").start();
    }

    private void write() {
        File dir = folder();
        String stamp = new SimpleDateFormat(STAMP, Locale.ROOT).format(new Date());
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            for (CallCheck.Tap tap : CallCheck.Tap.values()) {
                byte[] wav = take.wav(tap);
                if (wav == null) {
                    continue;
                }
                File f = new File(dir, stamp + "-" + tap.name().toLowerCase(Locale.ROOT) + ".wav");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(wav);
                }
            }
            String echo;
            synchronized (this) {
                echo = echoStats;
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir, stamp + "-echo.txt"))) {
                out.write(("aec " + echo + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "callcheck: saved " + stamp + "-*.wav in " + dir + ", aec " + echo);
        } catch (IOException e) {
            Log.w(TAG, "callcheck: not saved: " + e.getMessage());
        }

        synchronized (this) {
            take.reset();
            writing = false;
            lastFolder = dir.getPath();
        }
        cancelNotification();
    }

    /** The shared folder when the owner granted All files access, else the app's own. */
    private File folder() {
        boolean shared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
        if (shared) {
            return new File(Environment.getExternalStorageDirectory(), DIR);
        }

        File own = context.getExternalFilesDir(APP_DIR);
        return own != null ? own : new File(context.getFilesDir(), APP_DIR);
    }

    /** Ongoing and visible from CarPlay's pull-down: nobody records a call without seeing it. */
    private void notifyRecording() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.callcheck_channel), NotificationManager.IMPORTANCE_HIGH));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        nm.notify(NOTIFICATION_ID, b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.callcheck_recording_title))
                .setContentText(context.getString(R.string.callcheck_recording_text, SECONDS))
                .setOngoing(true)
                .build());
    }

    private void cancelNotification() {
        Context c;
        synchronized (this) {
            c = context;
        }
        if (c != null) {
            c.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        }
    }
}
