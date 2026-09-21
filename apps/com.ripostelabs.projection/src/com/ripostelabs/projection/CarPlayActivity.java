package com.ripostelabs.projection;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.graphics.SurfaceTexture;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.ripostelabs.design.Palette;
import com.ripostelabs.projection.zlink.Bridge;
import com.ripostelabs.projection.zlink.Messages;

/**
 * The CarPlay screen on Riposte OS 0.2: the phone's picture on a full-panel surface, touches
 * straight back to the daemon in panel pixels. The session itself lives in {@link ZlinkService};
 * this screen only lends it a surface while it is in front.
 */
public final class CarPlayActivity extends Activity implements Bridge.Screen {

    private static final int STATUS_LINES = 6;

    private TextureView video;
    /** The one surface the decoder draws into for this screen's whole life. */
    private Surface videoSurface;
    private TextView status;
    private ZlinkService service;
    private final StringBuilder log = new StringBuilder();
    private int lines;
    /** A second finger arrived: the gesture stays multi-touch until every finger is up. */
    private boolean multiGesture;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ZlinkService.LocalBinder) binder).service();
            service.bridge().setScreen(CarPlayActivity.this);
            if (videoSurface != null) {
                service.setSurface(videoSurface);
            }
            onStatus(service.bridge().isDaemonUp() ? "daemon linked" : "waiting for the daemon");
            // A screen that returns mid-session has the picture already; no strip over it.
            if (service.bridge().state() == Messages.STATE_SESSION) {
                status.setVisibility(View.GONE);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The phone draws for the whole panel; no Android bars over it.
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(R.layout.activity_carplay);
        Palette.apply(this);
        video = findViewById(R.id.carplay_video);
        status = findViewById(R.id.carplay_status);

        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                videoSurface = new Surface(texture);
                if (service != null) {
                    service.setSurface(videoSurface);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            }

            /** Kept: the decoder goes on drawing into it while the screen is away. */
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
        video.setOnTouchListener(this::forwardTouch);

        startService(new Intent(this, ZlinkService.class));
        bindService(new Intent(this, ZlinkService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Day and night are handled in place: the phone's picture stays up, only the strip recolours. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Palette.apply(this);
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.bridge().setScreen(null);
            service.setSurface(null);
        }
        unbindService(connection);
        SurfaceTexture texture = video.getSurfaceTexture();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (texture != null) {
            texture.release();
        }
        super.onDestroy();
    }

    /**
     * Panel pixels: the surface fills the panel, so view and panel agree. One finger goes as
     * the single-touch report the daemon has always had; a second finger switches the gesture
     * to multi-touch reports (pinch in Maps) until every finger is up. The digitiser's batched
     * samples go out too, so a drag reaches the phone at the digitiser's rate, not the
     * display's.
     */
    private boolean forwardTouch(View v, MotionEvent event) {
        if (service == null) {
            return false;
        }
        Bridge bridge = service.bridge();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiGesture = true;
        }
        if (multiGesture) {
            forwardFingers(bridge, event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                multiGesture = false;
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                bridge.touch((int) event.getX(), (int) event.getY(), true);
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int h = 0; h < event.getHistorySize(); h++) {
                    bridge.touch((int) event.getHistoricalX(h), (int) event.getHistoricalY(h), true);
                }
                bridge.touch((int) event.getX(), (int) event.getY(), true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                bridge.touch((int) event.getX(), (int) event.getY(), false);
                return true;
            default:
                return false;
        }
    }

    /** Every finger in the event; the one this action lifts is reported up. */
    private static void forwardFingers(Bridge bridge, MotionEvent event) {
        int action = event.getActionMasked();
        boolean lifting = action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL;
        int lifted = lifting ? event.getActionIndex() : -1;
        boolean all = action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP;
        Messages.Finger[] fingers = new Messages.Finger[event.getPointerCount()];
        for (int i = 0; i < fingers.length; i++) {
            boolean down = !(all || i == lifted);
            fingers[i] = new Messages.Finger(event.getPointerId(i),
                    (int) event.getX(i), (int) event.getY(i), down);
        }
        bridge.touch(fingers);
    }

    // ---- Bridge.Screen -----------------------------------------------------------------------

    @Override
    public void onStatus(String line) {
        if (lines >= STATUS_LINES) {
            int cut = log.indexOf("\n");
            log.delete(0, cut + 1);
        } else {
            lines++;
        }
        log.append(line).append('\n');
        status.setText(log);
    }

    @Override
    public void onSessionState(int state, int linkType) {
        // The status strip only matters before the picture arrives.
        status.setVisibility(state == Messages.STATE_WAITING_LINK ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onVideoSize(int width, int height) {
        status.setVisibility(View.GONE);
    }

    @Override
    public void onLeave() {
        moveTaskToBack(true);
    }
}
