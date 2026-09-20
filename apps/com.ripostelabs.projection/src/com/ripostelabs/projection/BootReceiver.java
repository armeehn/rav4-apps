package com.ripostelabs.projection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The daemon dials in from boot; the servers must be up before the first phone is plugged in. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        context.startForegroundService(new Intent(context, ZlinkService.class));
    }
}
