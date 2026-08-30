package com.reveng.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when an alarm's time is reached; launches the full-screen ring activity. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra(AlarmStore.EXTRA_ID, 0);
        String label = intent.getStringExtra(AlarmStore.EXTRA_LABEL);
        String time = intent.getStringExtra(AlarmStore.EXTRA_TIME);

        Intent ring = new Intent(context, RingActivity.class);
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ring.putExtra(AlarmStore.EXTRA_ID, id);
        ring.putExtra(AlarmStore.EXTRA_LABEL, label);
        ring.putExtra(AlarmStore.EXTRA_TIME, time);

        // Both paths, deliberately. The direct start is instant when it is allowed; the
        // full-screen intent is what still rings when a background activity start is not.
        RingNotice.post(context, ring, label, time);
        context.startActivity(ring);

        // A non-repeating alarm has now consumed itself; reschedule for next day so
        // it recurs daily while still enabled.
        AlarmStore store = new AlarmStore(context);
        for (Alarm a : store.load()) {
            if (a.id == id && a.enabled) {
                store.schedule(a);
                break;
            }
        }
    }
}
