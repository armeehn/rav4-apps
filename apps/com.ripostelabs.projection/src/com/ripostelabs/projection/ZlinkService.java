package com.ripostelabs.projection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import com.ripostelabs.design.MediaCitizen;
import com.ripostelabs.projection.aa.Messages.AudioConfig;
import com.ripostelabs.projection.zlink.Bridge;
import com.ripostelabs.projection.zlink.Messages;

import java.io.IOException;

/**
 * Keeps the daemon's servers open from boot to shutdown, so the OEM projection daemon on
 * Riposte OS 0.2 always has an app to talk to, and owns the decoders so a session outlives the
 * activity that shows it. {@link CarPlayActivity} binds for the surface and the touches.
 */
public final class ZlinkService extends Service implements Bridge.Media, Bridge.Session {

    private static final String TAG = "Projection";
    private static final String CHANNEL_ID = "projection";
    private static final int NOTIFICATION_ID = 1;
    private static final int AUDIO_CHANNEL_MEDIA = 0;
    private static final int PCM_BITS = 16;

    /**
     * The broadcast the OEM app sent and the launcher still listens for (device-reveng
     * carlib/Zlink.kt): action, `status` and `phoneMode` extras, unpermissioned.
     */
    private static final String STATUS_ACTION = "com.zjinnova.zlink";
    private static final String EXTRA_STATUS = "status";
    private static final String EXTRA_PHONE_MODE = "phoneMode";
    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_DISCONNECT = "DISCONNECT";
    private static final String MODE_WIRELESS = "carplay_wireless";
    private static final String MODE_WIRED = "carplay_wired";
    /** Android media key codes; the OEM gateway forwarded these raw to the daemon in mode 32. */
    private static final int KEY_PLAY_PAUSE = 85;
    private static final int KEY_NEXT = 87;
    private static final int KEY_PREVIOUS = 88;
    private static final String CITIZEN_TAG = "carplay";

    /** The SoC's USB role switch on this head unit (QCM6125 "trinket"), run by the daemon as root. */
    private static final String USB_MODE_NODE = "/sys/devices/platform/soc/4e00000.ssusb/mode";

    final class LocalBinder extends Binder {
        ZlinkService service() {
            return ZlinkService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final VideoSink videoSink = new VideoSink();
    private final AudioSink audioSink = new AudioSink(AUDIO_CHANNEL_MEDIA);
    private Bridge bridge;
    private CarPlayWireless wireless;
    private MediaCitizen citizen;
    private boolean hasFocus;

    /** The wheel's media keys and the launcher's card act on the phone through the daemon. */
    private final MediaCitizen.Transport transport = new MediaCitizen.Transport() {
        @Override
        public void onPlay() {
            tap(KEY_PLAY_PAUSE);
        }

        @Override
        public void onPause() {
            tap(KEY_PLAY_PAUSE);
        }

        @Override
        public void onNext() {
            tap(KEY_NEXT);
        }

        @Override
        public void onPrevious() {
            tap(KEY_PREVIOUS);
        }

        @Override
        public void onStop() {
            tap(KEY_PLAY_PAUSE);
        }

        @Override
        public void onDuck(boolean duck) {
            audioSink.setVolume(MediaCitizen.duckVolume(duck));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, notification());

        Messages.InitInfo init = new Messages.InitInfo();
        init.otgToHost = "echo host > " + USB_MODE_NODE + ";";
        init.otgToDevice = "echo peripheral > " + USB_MODE_NODE + ";";
        init.linkTypes = Messages.LINK_WIRED_CARPLAY | Messages.LINK_WIRELESS_CARPLAY;
        bridge = new Bridge(init);
        bridge.setMedia(this);
        wireless = new CarPlayWireless(this, bridge);
        bridge.setWireless(wireless);
        bridge.setSession(this);
        citizen = MediaCitizen.attach(this, CITIZEN_TAG, transport);
        try {
            bridge.start();
        } catch (IOException e) {
            Log.e(TAG, "zlink bridge cannot listen: " + e.getMessage());
            stopSelf();
            return;
        }
        wireless.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (wireless != null) {
            wireless.stop();
        }
        if (bridge != null) {
            bridge.stop();
        }
        videoSink.stop();
        audioSink.stop();
        if (citizen != null) {
            citizen.releaseFocus();
            citizen.release();
        }
        super.onDestroy();
    }

    Bridge bridge() {
        return bridge;
    }

    void setSurface(Surface surface) {
        videoSink.setSurface(surface);
    }

    // ---- Bridge.Session ----------------------------------------------------------------------

    @Override
    public void onSession(boolean up, int linkType) {
        Intent i = new Intent(STATUS_ACTION)
                .putExtra(EXTRA_STATUS, up ? STATUS_CONNECTED : STATUS_DISCONNECT)
                .putExtra(EXTRA_PHONE_MODE, linkType == Messages.LINK_TYPE_WIRELESS_CARPLAY ? MODE_WIRELESS : MODE_WIRED)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        sendBroadcast(i);
        Log.i(TAG, "zlink: session " + (up ? "up" : "down") + ", launcher told");
        if (!up && hasFocus) {
            citizen.releaseFocus();
            citizen.setIdle();
            hasFocus = false;
        }
    }

    private void tap(int keyCode) {
        bridge.key(keyCode, true);
        bridge.key(keyCode, false);
    }

    // ---- Bridge.Media ------------------------------------------------------------------------

    @Override
    public void onVideoSize(int width, int height) {
        videoSink.configure(width, height);
    }

    @Override
    public void onVideo(byte[] data, int off, int len, long timestampUs) {
        videoSink.feed(data, off, len, timestampUs);
    }

    @Override
    public void onAudioFormat(int sampleRate, int channels) {
        audioSink.start(new AudioConfig(sampleRate, PCM_BITS, channels));
        // Focus on the first audio of a session: the radio ducks, the launcher's card sees us.
        if (!hasFocus) {
            hasFocus = citizen.takeFocus(MediaCitizen.Focus.MEDIA);
            citizen.setMetadata(getString(R.string.carplay_name), "", 0);
            citizen.setState(true, 0);
        }
    }

    @Override
    public void onAudio(byte[] data, int off, int len) {
        audioSink.write(data, off, len);
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN));
        }
        Intent open = new Intent(this, CarPlayActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.zlink_notification))
                .setContentIntent(tap)
                .setOngoing(true)
                .build();
    }
}
