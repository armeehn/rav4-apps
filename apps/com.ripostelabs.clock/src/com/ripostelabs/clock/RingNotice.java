package com.ripostelabs.clock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * The alarm's full-screen intent.
 *
 * <p>A broadcast receiver calling {@code startActivity} is a background activity start, and the
 * platform is free to drop it — an alarm that silently never rings is the worst failure this app
 * has, and nothing about it is visible on a desk. A full-screen-intent notification is the
 * supported way to put a screen in front of the driver from the background: the system launches
 * {@link RingActivity} itself when the display is off or locked, and shows a heads-up the driver
 * can tap when it is not.
 *
 * <p>{@link AlarmReceiver} still starts the activity directly as well. Between the two, one
 * lands. This is also what the manifest's long-declared USE_FULL_SCREEN_INTENT was for.
 */
final class RingNotice {

    private RingNotice() {}

    private static final String CHANNEL = "alarm_ring";

    /** One notification, replaced per alarm: only one ring screen exists at a time. */
    private static final int NOTIFICATION_ID = 1;

    /** Put {@code ring} in front of the driver. Silent: RingActivity plays the tone itself. */
    static void post(Context ctx, Intent ring, String label, String timeText) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        ensureChannel(ctx, nm);

        PendingIntent pi = PendingIntent.getActivity(ctx, 0, ring,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);

        boolean unlabelled = label == null || label.isEmpty();
        b.setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(unlabelled ? ctx.getString(R.string.app_name) : label)
                .setContentText(timeText == null ? "" : timeText)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MAX);
        }

        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS refused on API 33+. The direct start is then all there is,
            // which is exactly where this app already was.
        }
    }

    static void clear(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
    }

    private static void ensureChannel(Context ctx, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel c = new NotificationChannel(
                CHANNEL, ctx.getString(R.string.channel_alarms),
                NotificationManager.IMPORTANCE_HIGH);
        c.setDescription(ctx.getString(R.string.channel_alarms_desc));
        // The ring screen plays the alarm tone and drives the vibrator itself; letting the
        // channel do it too would double both.
        c.setSound(null, null);
        c.enableVibration(false);
        nm.createNotificationChannel(c);
    }
}
