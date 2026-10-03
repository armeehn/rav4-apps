package com.ripostelabs.projection;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.provider.Settings;
import android.graphics.SurfaceTexture;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.ripostelabs.design.Palette;
import com.ripostelabs.projection.zlink.Bridge;
import com.ripostelabs.projection.zlink.Messages;
import com.ripostelabs.projection.zlink.Waiting;

/**
 * The CarPlay screen on Riposte OS 0.2: the phone's picture on a full-panel surface, touches
 * straight back to the daemon in panel pixels. The session itself lives in {@link ZlinkService};
 * this screen only lends it a surface while it is in front.
 *
 * <p>Until the picture arrives the screen is a waiting panel ({@link Waiting}): what it waits
 * for, why when the unit can tell (Bluetooth off, Wi-Fi off, no iPhone), a one-tap fix and
 * Home. The system bars stay until then: the launcher hides its own bar over this app, so an
 * immersive screen with no session had no way out (car, 2026-10-01). Back always leaves.
 */
public final class CarPlayActivity extends Activity implements Bridge.Screen {

    private static final String TAG = "Projection";
    private static final int STATUS_LINES = 6;

    /** How often the panel re-reads the radios and the connect timeout while in front. */
    private static final long TICK_MS = 2_000L;

    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    private TextureView video;
    /** The one surface the decoder draws into for this screen's whole life. */
    private Surface videoSurface;
    /** The texture behind [videoSurface], handed back to the view after every stop. */
    private final KeptTexture<SurfaceTexture> texture = new KeptTexture<>();
    private TextView status;
    private View waitPanel;
    private TextView waitTitle;
    private TextView waitReason;
    private Button fixButton;
    private ZlinkService service;
    /** The session state, when it last changed, and whether a picture has arrived in it. */
    private int state = Messages.STATE_WAIT_INIT;
    private long stateSince = SystemClock.uptimeMillis();
    private boolean frame;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, TICK_MS);
        }
    };
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
            }
            // A screen that returns mid-session has the picture already; no panel over it.
            setState(service.bridge().state());
            frame = state == Messages.STATE_SESSION;
            render();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_carplay);
        Palette.apply(this);
        video = findViewById(R.id.carplay_video);
        status = findViewById(R.id.carplay_status);
        status.setVisibility(bench ? View.VISIBLE : View.GONE);
        waitPanel = findViewById(R.id.carplay_wait);
        waitTitle = findViewById(R.id.carplay_title);
        waitReason = findViewById(R.id.carplay_reason);
        fixButton = findViewById(R.id.carplay_fix);
        findViewById(R.id.carplay_home).setOnClickListener(v -> goHome());
        render();

        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture fresh, int width, int height) {
                KeptTexture.Use use = texture.onAvailable(fresh);
                if (use == KeptTexture.Use.RESTORE) {
                    restoreTexture();
                }
                if (use != KeptTexture.Use.ADOPT) {
                    return;
                }
                videoSurface = new Surface(fresh);
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
            render();
        }
    }

    /** Back in front: the latest picture is on screen at once, no key frame waited for. */
    @Override
    protected void onStart() {
        super.onStart();
        if (texture.onStart(video.getSurfaceTexture()) == KeptTexture.Use.RESTORE) {
            restoreTexture();
        }
    }

    /** The stop took the view's texture; give it back the one the decoder still draws into. */
    private void restoreTexture() {
        try {
            video.setSurfaceTexture(texture.texture());
            Log.i(TAG, "screen: picture kept");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "screen: kept texture refused: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        super.onPause();
    }

    /** Back leaves in every state; the session keeps running behind the launcher. */
    @Override
    public void onBackPressed() {
        if (!moveTaskToBack(true)) {
            finish();
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
        main.removeCallbacks(tick);
        if (service != null) {
            service.bridge().setScreen(null);
            service.setSurface(null);
        }
        unbindService(connection);
        SurfaceTexture shown = texture.texture();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (shown != null) {
            shown.release();
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
        setState(state);
        render();
    }

    @Override
    public void onVideoSize(int width, int height) {
        frame = true;
        render();
    }

    /** A new state restarts the timeout clock; leaving the session drops the old picture. */
    private void setState(int next) {
        if (next != state) {
            stateSince = SystemClock.uptimeMillis();
        }
        state = next;
        if (state != Messages.STATE_SESSION) {
            frame = false;
        }
    }

    /** The picture full screen, or the waiting panel with the bars, a reason and a fix. */
    private void render() {
        boolean picture = Waiting.picture(state, frame);
        waitPanel.setVisibility(picture ? View.GONE : View.VISIBLE);
        if (bench) {
            status.setVisibility(picture ? View.GONE : View.VISIBLE);
        }
        // The phone draws for the whole panel only once it draws at all.
        getWindow().getDecorView().setSystemUiVisibility(picture ? IMMERSIVE : View.SYSTEM_UI_FLAG_VISIBLE);
        if (picture) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        waitTitle.setText(titleText(Waiting.title(state, stateSince, now)));

        Waiting.Reason why = Waiting.reason(radios(), state, stateSince, now);
        String reason = reasonText(why);
        waitReason.setVisibility(reason == null ? View.GONE : View.VISIBLE);
        waitReason.setText(reason);

        Waiting.Fix fix = Waiting.fix(why);
        String label = fixText(fix);
        fixButton.setVisibility(label == null ? View.GONE : View.VISIBLE);
        fixButton.setText(label);
        fixButton.setOnClickListener(v -> apply(fix));
    }

    /** What Android says about the radios; anything unreadable counts as on (no false alarm). */
    private Waiting.Radios radios() {
        boolean bt = true;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            bt = adapter == null || adapter.isEnabled();
        } catch (SecurityException e) {
            // keep "on"
        }

        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        boolean wifiOn = wifi == null || wifi.isWifiEnabled();
        boolean airplane = Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        return new Waiting.Radios(bt, wifiOn, airplane);
    }

    private String titleText(Waiting.Title title) {
        switch (title) {
            case WAITING:
                return getString(R.string.carplay_waiting_phone);
            case CONNECTING:
                return getString(R.string.carplay_connecting);
            case DISCONNECTED:
                return getString(R.string.carplay_disconnected);
            default:
                return getString(R.string.carplay_starting);
        }
    }

    private String reasonText(Waiting.Reason reason) {
        switch (reason) {
            case AIRPLANE:
                return getString(R.string.carplay_why_airplane);
            case BLUETOOTH_OFF:
                return getString(R.string.carplay_why_bluetooth);
            case WIFI_OFF:
                return getString(R.string.carplay_why_wifi);
            case NO_PHONE:
                return getString(R.string.carplay_why_no_phone);
            default:
                return null;
        }
    }

    private String fixText(Waiting.Fix fix) {
        switch (fix) {
            case AIRPLANE_OFF:
                return getString(R.string.carplay_fix_airplane);
            case BLUETOOTH_ON:
                return getString(R.string.carplay_fix_bluetooth);
            case WIFI_ON:
                return getString(R.string.carplay_fix_wifi);
            default:
                return null;
        }
    }

    /**
     * A normal app targeting API 33 cannot switch a radio itself, so the fix opens the system's
     * own one-tap control: the Bluetooth enable dialog, the Wi-Fi panel, the airplane setting.
     */
    private void apply(Waiting.Fix fix) {
        switch (fix) {
            case BLUETOOTH_ON:
                open(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), Settings.ACTION_BLUETOOTH_SETTINGS);
                return;
            case WIFI_ON:
                String panel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS;
                open(new Intent(panel), Settings.ACTION_WIFI_SETTINGS);
                return;
            case AIRPLANE_OFF:
                open(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), Settings.ACTION_SETTINGS);
                return;
            default:
        }
    }

    /** The intent, or the settings page behind it when the unit has no such screen. */
    private void open(Intent intent, String fallback) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(fallback));
            } catch (ActivityNotFoundException ignored) {
                // Nothing to open; the panel still says what is off.
            }
        }
    }

    /** The launcher, whatever task this screen sits in. */
    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }

    @Override
    public void onLeave() {
        moveTaskToBack(true);
    }
}
