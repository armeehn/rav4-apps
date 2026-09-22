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
    /** Bench builds keep the protocol trace on screen; a car build never shows it. */
    private final boolean bench = SystemProps.bench();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ZlinkService.LocalBinder) binder).service();
            service.bridge().setScreen(CarPlayActivity.this);
            if (videoSurface != null) {
                service.setSurface(videoSurface);
            }
            if (bench) {
                onStatus(service.bridge().isDaemonUp() ? "daemon linked" : "waiting for the daemon");
            } else {
                status.setText(getString(R.string.carplay_waiting_phone));
            }
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
     * One finger, panel pixels: the surface fills the panel, so view and panel agree. The
     * daemon's CarPlay HID is single-touch by design (HIDTouchScreenSingleCreateDescriptor;
     * its multi_touch report 0x111 reaches only HiCar in hal_multi_touch_event), so a second
     * finger is ignored and the first finger's up must always go out, or the phone keeps it
     * down and every later tap is dead. The digitiser's batched samples go out too, so a drag
     * reaches the phone at the digitiser's rate, not the display's.
     */
    private boolean forwardTouch(View v, MotionEvent event) {
        if (service == null) {
            return false;
        }
        Bridge bridge = service.bridge();
        switch (event.getActionMasked()) {
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

    // ---- Bridge.Screen -----------------------------------------------------------------------

    /**
     * The daemon's own words are a protocol trace ("zlink: control 0x704"): a bench aid, and
     * what the driver read on a product unit while waiting for the picture (UI sweep,
     * 2026-09-22). On a bench build the rolling trace still shows; otherwise the strip carries
     * one plain line about the phone, set from the session state.
     */
    @Override
    public void onStatus(String line) {
        if (!bench) {
            return;
        }
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
        if (!bench) {
            status.setText(waitingText(state));
        }
    }

    /** What the driver is waiting for, in their words. */
    private String waitingText(int state) {
        switch (state) {
            case Messages.STATE_WAIT_INIT:
                return getString(R.string.carplay_starting);
            case Messages.STATE_WAITING_LINK:
                return getString(R.string.carplay_waiting_phone);
            case Messages.STATE_WIRELESS_CARPLAY:
                return getString(R.string.carplay_connecting);
            case Messages.STATE_STOPPED:
                return getString(R.string.carplay_disconnected);
            default:
                return getString(R.string.carplay_starting);
        }
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
