package com.ripostelabs.projection;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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

    private SurfaceView video;
    private TextView status;
    private ZlinkService service;
    private final StringBuilder log = new StringBuilder();
    private int lines;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ZlinkService.LocalBinder) binder).service();
            service.bridge().setScreen(CarPlayActivity.this);
            if (video.getHolder().getSurface().isValid()) {
                service.setSurface(video.getHolder().getSurface());
            }
            onStatus(service.bridge().isDaemonUp() ? "daemon linked" : "waiting for the daemon");
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

        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (service != null) {
                    service.setSurface(holder.getSurface());
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (service != null) {
                    service.setSurface(null);
                }
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

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.bridge().setScreen(null);
            service.setSurface(null);
            unbindService(connection);
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
