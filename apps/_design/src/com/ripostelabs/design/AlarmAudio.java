package com.ripostelabs.design;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/**
 * Audio focus for an alarm, and nothing else.
 *
 * <p>An alarm that plays without focus is not a small defect in a car. It comes out on top of the
 * radio at whatever the radio is doing, the radio does not duck for it, and the driver hears two
 * things at once at the moment they are meant to hear one. The clock shipped that way.
 *
 * <h3>Why this is not MediaCitizen</h3>
 * MediaCitizen is the right front door for anything that plays <em>media</em>, and it publishes a
 * MediaSession so the launcher's now-playing card and the steering-wheel transport keys can reach
 * it. An alarm must not be reachable that way. It would appear as if it were something the driver
 * had chosen to listen to, and the wheel's skip button would address the alarm instead of the
 * music it interrupted. So this takes focus and does not pretend to be a player.
 *
 * <h3>Why GAIN_TRANSIENT</h3>
 * Not GAIN, which tells everything else to stop for good and leaves the radio dead after the
 * alarm is dismissed. Not TRANSIENT_EXCLUSIVE, which is for capture, where a ducked radio would
 * still end up on the recording. TRANSIENT is the one that means "quiet down, I will hand this
 * back", which is exactly what an alarm is doing.
 *
 * <h3>Why the focus listener does nothing</h3>
 * Deliberate, and the opposite of what a player should do. An alarm is the one sound that must not
 * pause because something else asked for the cabin. Yielding would mean an alarm that a navigation
 * prompt can silence. The listener exists because focus must be abandoned with the same object it
 * was requested with, not because there is anything to handle.
 */
public final class AlarmAudio {

    private final Context appContext;
    private AudioFocusRequest request;

    /**
     * One instance for the life of this object. Before Android O, {@code abandonAudioFocus}
     * matches the listener by identity, and a lambda evaluated twice is two different objects — so
     * requesting with one and abandoning with another leaves the entry on the focus stack forever
     * and the whole cabin stays ducked. Same trap MediaCitizen documents; same fix.
     */
    private final AudioManager.OnAudioFocusChangeListener listener = focusChange -> { };

    private AlarmAudio(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static AlarmAudio of(Context context) {
        return new AlarmAudio(context);
    }

    /**
     * Ask the cabin to go quiet for an alarm.
     *
     * @return whether focus was granted. The caller should ring either way: an alarm nobody
     *         granted focus to is still an alarm the driver asked for, and refusing to ring
     *         because the request was denied is a worse failure than ringing over the radio.
     */
    public boolean take() {
        AudioManager am = appContext.getSystemService(AudioManager.class);
        if (am == null) {
            return false;
        }

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(listener)
                    .build();
            result = am.requestAudioFocus(request);
        } else {
            result = am.requestAudioFocus(
                    listener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }

        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /**
     * Give the cabin back. Safe to call when focus was never granted, and safe to call twice,
     * because the paths that stop an alarm are not all reachable from each other.
     */
    public void release() {
        AudioManager am = appContext.getSystemService(AudioManager.class);
        if (am == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (request != null) {
                am.abandonAudioFocusRequest(request);
                request = null;
            }
            return;
        }

        am.abandonAudioFocus(listener);
    }
}
