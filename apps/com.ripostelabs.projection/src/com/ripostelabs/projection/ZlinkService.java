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

import com.ripostelabs.projection.aa.Messages.AudioConfig;
import com.ripostelabs.projection.zlink.Bridge;
import com.ripostelabs.projection.zlink.Messages;

import java.io.IOException;

/**
 * Keeps the daemon's servers open from boot to shutdown, so the OEM projection daemon on
 * Riposte OS 0.2 always has an app to talk to, and owns the decoders so a session outlives the
 * activity that shows it. {@link CarPlayActivity} binds for the surface and the touches.
 */
public final class ZlinkService extends Service implements Bridge.Media {

    private static final String TAG = "Projection";
    private static final String CHANNEL_ID = "projection";
    private static final int NOTIFICATION_ID = 1;
    private static final int AUDIO_CHANNEL_MEDIA = 0;
    private static final int PCM_BITS = 16;

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

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, notification());

        Messages.InitInfo init = new Messages.InitInfo();
        init.otgToHost = "echo host > " + USB_MODE_NODE + ";";
        init.otgToDevice = "echo peripheral > " + USB_MODE_NODE + ";";
        bridge = new Bridge(init);
        bridge.setMedia(this);
        try {
            bridge.start();
        } catch (IOException e) {
            Log.e(TAG, "zlink bridge cannot listen: " + e.getMessage());
            stopSelf();
        }
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
        if (bridge != null) {
            bridge.stop();
        }
        videoSink.stop();
        audioSink.stop();
        super.onDestroy();
    }

    Bridge bridge() {
        return bridge;
    }

    void setSurface(Surface surface) {
        videoSink.setSurface(surface);
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
