package com.ripostelabs.projection;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.ripostelabs.design.Palette;
import com.ripostelabs.projection.aa.Aoa;
import com.ripostelabs.projection.aa.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Wired Android Auto receiver, stage 1. The screen is one surface the phone draws on and a
 * status line that says which protocol step we are at; the state machine is in
 * {@link com.ripostelabs.projection.aa.Session}.
 *
 * <p>Two ways in: the system launches us on USB_DEVICE_ATTACHED for a Google accessory-mode
 * device (see res/xml/device_filter.xml), or the driver opens the app and taps Connect, which
 * takes any non-hub USB device, asks permission, and switches it into accessory mode.
 *
 * <p>Stage 2 adds the Wireless toggle: it asks for the Bluetooth and hotspot runtime permissions
 * once, then {@link Wireless} raises the AP and waits for a phone over RFCOMM and TCP.
 */
public class MainActivity extends Activity implements Projector.Screen {

    private static final String TAG = "Projection";
    private static final String ACTION_USB_PERMISSION = "com.ripostelabs.projection.USB_PERMISSION";
    private static final int MAX_POINTERS = 10;
    private static final int REQ_WIRELESS_PERMISSIONS = 1;

    private UsbManager usb;
    private Projector projector;
    private FrameLayout root;
    private SurfaceView video;
    private TextView status;
    private View connect;
    private ToggleButton wireless;
    private Wireless wirelessLink;
    private boolean receiverRegistered;

    /** True once the phone has started a video stream, which is when touches mean something. */
    private boolean videoActive;

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null || action == null) {
                return;
            }
            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    connect(device);
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    onStatus("usb: detached " + UsbLink.describe(device));
                    if (projector.isRunning()) {
                        projector.stop("usb detached");
                    }
                    break;
                case ACTION_USB_PERMISSION:
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connect(device);
                    } else {
                        onStatus("usb: permission refused for " + UsbLink.describe(device));
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Palette.apply(this);

        root = findViewById(R.id.root);
        video = findViewById(R.id.video);
        status = findViewById(R.id.status);
        connect = findViewById(R.id.connect);
        wireless = findViewById(R.id.wireless);

        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        projector = new Projector(this, this);
        wirelessLink = new Wireless(this, projector, this);

        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                projector.setSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                projector.setSurface(null);
            }
        });
        video.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return forwardTouch(event);
            }
        });
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });
        wireless.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleWireless();
            }
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbEvents, filter);
        receiverRegistered = true;

        handleIntent(getIntent());
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            onStatus("this device has no USB host: nothing to plug a phone into");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        wirelessLink.close();
        projector.stop("activity destroyed");
        if (receiverRegistered) {
            unregisterReceiver(usbEvents);
        }
        super.onDestroy();
    }

    /** The Wireless toggle: permissions first, then the whole stage 2 chain. */
    private void toggleWireless() {
        if (!wireless.isChecked()) {
            wirelessLink.disable("switched off");
            return;
        }
        String[] wanted = missingWirelessPermissions();
        if (wanted.length > 0) {
            onStatus("wireless: asking for " + wanted.length + " permission(s)");
            requestPermissions(wanted, REQ_WIRELESS_PERMISSIONS);
            return;
        }
        wirelessLink.enable();
    }

    private String[] missingWirelessPermissions() {
        List<String> missing = new ArrayList<>();
        for (String[] set : new String[][] {BtRfcomm.runtimePermissions(), SoftAp.runtimePermissions()}) {
            for (String p : set) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(p);
                }
            }
        }
        return missing.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQ_WIRELESS_PERMISSIONS) {
            return;
        }
        if (missingWirelessPermissions().length > 0) {
            wireless.setChecked(false);
            onStatus("wireless: permission denied");
            return;
        }
        wirelessLink.enable();
    }

    /** Launched by the system for an attached device, or by the driver from the launcher. */
    private void handleIntent(Intent intent) {
        UsbDevice device = intent == null ? null : (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null) {
            connect(device);
            return;
        }
        scan();
    }

    /** No intent told us which device: prefer one already in accessory mode. */
    private void scan() {
        UsbDevice device = UsbLink.findAccessory(usb);
        if (device == null) {
            device = UsbLink.findCandidate(usb);
        }
        if (device == null) {
            onStatus("no phone on the USB bus. Plug one in and tap Connect.");
            return;
        }
        connect(device);
    }

    private void connect(UsbDevice device) {
        if (!usb.hasPermission(device)) {
            onStatus("usb: asking permission for " + UsbLink.describe(device));
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usb.requestPermission(device, pi);
            return;
        }

        if (Aoa.isAccessory(device.getVendorId(), device.getProductId())) {
            onStatus("usb: accessory " + UsbLink.describe(device) + ", starting session");
            projector.start(usb, device);
            return;
        }

        onStatus("usb: switching " + UsbLink.describe(device) + " to accessory mode");
        final UsbDevice target = device;
        // Seven control transfers with one-second timeouts: off the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = UsbLink.switchToAccessory(usb, target);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onStatus(ok ? "usb: switched, waiting for the phone to re-attach"
                                : "usb: " + UsbLink.describe(target) + " did not accept the accessory switch");
                    }
                });
            }
        }, "projection-aoa").start();
    }

    // ---- touch ---------------------------------------------------------------------------------

    private boolean forwardTouch(MotionEvent event) {
        if (!videoActive || video.getWidth() == 0) {
            return false;
        }
        int action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                action = Messages.TOUCH_PRESS;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                action = Messages.TOUCH_RELEASE;
                break;
            case MotionEvent.ACTION_MOVE:
                action = Messages.TOUCH_DRAG;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                action = Messages.TOUCH_POINTER_DOWN;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                action = Messages.TOUCH_POINTER_UP;
                break;
            default:
                return false;
        }

        // Scale view pixels into the touch space declared at discovery (the first config's size).
        int count = Math.min(event.getPointerCount(), MAX_POINTERS);
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] ids = new int[count];
        float sx = (float) Projector.config().touchWidth / video.getWidth();
        float sy = (float) Projector.config().touchHeight / video.getHeight();
        for (int i = 0; i < count; i++) {
            xs[i] = Math.max(0, Math.round(event.getX(i) * sx));
            ys[i] = Math.max(0, Math.round(event.getY(i) * sy));
            ids[i] = event.getPointerId(i);
        }
        projector.touch(action, event.getActionIndex(), xs, ys, ids);
        return true;
    }

    // ---- Projector.Screen (main thread) --------------------------------------------------------

    @Override
    public void onStatus(String line) {
        status.setText(line);
        if (wireless.isChecked() && !wirelessLink.isOn()) {
            wireless.setChecked(false);
        }
    }

    /**
     * Places the stream on the panel. A stream as wide as the panel sits top-left and the rows
     * below the panel are simply off-screen (the 1080p-with-margin case). Anything narrower is
     * scaled to the panel height and centred.
     */
    @Override
    public void onVideoSize(int width, int height) {
        videoActive = true;
        int panelW = root.getWidth() > 0 ? root.getWidth() : Projector.PANEL_WIDTH;
        int panelH = root.getHeight() > 0 ? root.getHeight() : Projector.PANEL_HEIGHT;

        FrameLayout.LayoutParams lp;
        if (width == panelW) {
            lp = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.START);
        } else {
            float scale = (float) panelH / height;
            lp = new FrameLayout.LayoutParams(Math.round(width * scale), panelH, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        }
        video.setLayoutParams(lp);
        connect.setVisibility(View.GONE);
        Log.i(TAG, "video: " + width + "x" + height + " on a " + panelW + "x" + panelH + " panel");
    }

    @Override
    public void onEnded(String why) {
        videoActive = false;
        connect.setVisibility(View.VISIBLE);
        onStatus("session ended: " + why);
    }
}
