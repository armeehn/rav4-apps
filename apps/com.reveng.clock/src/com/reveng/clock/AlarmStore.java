package com.reveng.clock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Persistence (SharedPreferences) + AlarmManager scheduling for alarms. */
final class AlarmStore {
    static final String PREFS = "clock_prefs";
    private static final String KEY_ALARMS = "alarms";
    private static final String KEY_NEXT_ID = "next_id";

    static final String EXTRA_ID = "alarm_id";
    static final String EXTRA_LABEL = "alarm_label";
    static final String EXTRA_TIME = "alarm_time";

    private final Context ctx;
    private final SharedPreferences prefs;

    AlarmStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Alarm> load() {
        List<Alarm> out = new ArrayList<>();
        String raw = prefs.getString(KEY_ALARMS, "");
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.trim().isEmpty()) continue;
            Alarm a = Alarm.parse(line);
            if (a != null) out.add(a);
        }
        return out;
    }

    void save(List<Alarm> alarms) {
        StringBuilder sb = new StringBuilder();
        for (Alarm a : alarms) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(a.serialize());
        }
        prefs.edit().putString(KEY_ALARMS, sb.toString()).apply();
    }

    int nextId() {
        int id = prefs.getInt(KEY_NEXT_ID, 1);
        prefs.edit().putInt(KEY_NEXT_ID, id + 1).apply();
        return id;
    }

    /** (Re)schedule all enabled alarms. Call after any change and on boot. */
    void rescheduleAll() {
        for (Alarm a : load()) {
            if (a.enabled) schedule(a);
            else cancel(a.id);
        }
    }

    private PendingIntent intentFor(Alarm a, boolean forShow) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.reveng.clock.FIRE_" + a.id);
        i.putExtra(EXTRA_ID, a.id);
        i.putExtra(EXTRA_LABEL, a.label);
        i.putExtra(EXTRA_TIME, a.timeText());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, a.id, i, flags);
    }

    void schedule(Alarm a) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long when = nextTrigger(a.hour, a.minute);
        PendingIntent op = intentFor(a, false);
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                // setAlarmClock is exempt from exact-alarm restrictions and shows a
                // system alarm indicator. A second PI opens the app when tapped.
                Intent show = new Intent(ctx, MainActivity.class);
                int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) sflags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent showPi = PendingIntent.getActivity(ctx, 10000 + a.id, show, sflags);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi), op);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, when, op);
            }
        } catch (SecurityException se) {
            // Fall back to an inexact alarm if exact scheduling is denied.
            am.set(AlarmManager.RTC_WAKEUP, when, op);
        }
    }

    /** Schedule a one-shot (e.g. snooze) at an absolute time for an existing id. */
    void scheduleAt(int id, long whenMillis, String label, String timeText) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.reveng.clock.SNOOZE_" + id);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_LABEL, label == null ? "" : label);
        i.putExtra(EXTRA_TIME, timeText == null ? "" : timeText);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent op = PendingIntent.getBroadcast(ctx, 20000 + id, i, flags);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, op);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, whenMillis, op);
            }
        } catch (SecurityException se) {
            am.set(AlarmManager.RTC_WAKEUP, whenMillis, op);
        }
    }

    void cancel(int id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Alarm stub = new Alarm(id, 0, 0, false, "");
        am.cancel(intentFor(stub, false));
    }

    static long nextTrigger(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (!c.after(now)) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }
}
