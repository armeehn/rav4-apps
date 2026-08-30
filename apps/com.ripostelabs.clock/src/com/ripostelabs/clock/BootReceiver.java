package com.ripostelabs.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms all enabled alarms after a reboot (they are lost from AlarmManager). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            new AlarmStore(context).rescheduleAll();
        }
    }
}
