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

    /** One finger, panel pixels: the surface fills the panel, so view and panel agree. */
    private boolean forwardTouch(View v, MotionEvent event) {
        if (service == null) {
            return false;
        }
        int x = (int) event.getX();
        int y = (int) event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                service.bridge().touch(x, y, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                service.bridge().touch(x, y, false);
                return true;
            default:
                return false;
        }
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
