package com.ripostelabs.projection;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import com.ripostelabs.projection.aa.Wifi;
import com.ripostelabs.projection.zlink.Bridge;
import com.ripostelabs.projection.zlink.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;

/**
 * The wireless CarPlay bootstrap on Riposte OS 0.2. The daemon does the iAP2 and AirPlay work;
 * this supplies the two things it cannot reach from a root shell: the phone's RFCOMM socket
 * (Android's Bluetooth stack owns it) and a Wi-Fi access point.
 *
 * <pre>
 *   iPhone ──RFCOMM (iAP2 service)──▶ BtRelay ──1999──▶ daemon   (both ways, raw bytes)
 *   daemon ──AP_INFO_REQUEST──▶ hotspot up ──AP_INFO {ssid, key, band, iface}──▶ daemon
 *   iPhone ──joins the AP──▶ daemon's AirPlay receiver on that interface ──▶ video/audio
 * </pre>
 *
 * <p>The phone is found the way the OEM app found it: an ACL link comes up for a bonded device,
 * its SDP record is fetched, and the iAP2 UUID means CarPlay. The head unit is the RFCOMM
 * client. Pairing itself is the car-kit's job (Settings, or the suite's Bluetooth app).
 */
final class CarPlayWireless implements Bridge.Wireless {

    private static final String TAG = "Projection";
    /** iAP2 over Bluetooth, the service an iPhone with wireless CarPlay exposes. */
    private static final UUID IAP2_SERVICE = UUID.fromString("00000000-DECA-FADE-DECA-DEAFDECACAFE");
    private static final int READ_BUF = 4096;
    private static final String UNKNOWN_MAC = "00:00:00:00:00:00";
    /** Set by riposte-hotspot.sh on Riposte OS 0.2 (device-reveng os/overlay). */
    private static final String PROP_AP_SSID = "riposte.ap.ssid";
    private static final String PROP_AP_KEY = "riposte.ap.psk";
    private static final String PROP_AP_IFACE = "riposte.ap.iface";
    private static final String DEFAULT_AP_IFACE = "wlan1";

    private final Context context;
    private final Bridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private SoftAp softAp;
    private BluetoothSocket phone;
    private OutputStream toPhone;
    private volatile boolean apStarting;

    private final BroadcastReceiver btEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                fetchUuids(device);
            } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                onUuids(device);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                dropPhone("bluetooth link to " + device.getAddress() + " went down");
            }
        }
    };

    CarPlayWireless(Context context, Bridge bridge) {
        this.context = context;
        this.bridge = bridge;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
    }

    void start() {
        if (adapter == null) {
            Log.w(TAG, "wireless: no Bluetooth adapter");
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_UUID);
        context.registerReceiver(btEvents, f);
        if (!btPermitted()) {
            Log.w(TAG, "wireless: BLUETOOTH_CONNECT not granted; phones will not be found");
            return;
        }
        // A phone already linked for hands-free is found through its cached record.
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            onUuids(d);
        }
    }

    void stop() {
        context.unregisterReceiver(btEvents);
        dropPhone("service stopping");
        closeAp("service stopping");
    }

    // ---- Bluetooth ---------------------------------------------------------------------------

    private void fetchUuids(BluetoothDevice device) {
        if (!btPermitted()) {
            return;
        }
        try {
            device.fetchUuidsWithSdp();
        } catch (SecurityException e) {
            Log.w(TAG, "wireless: sdp fetch refused: " + e.getMessage());
        }
    }

    private synchronized void onUuids(BluetoothDevice device) {
        if (phone != null || !btPermitted()) {
            return;
        }
        ParcelUuid[] uuids;
        try {
            uuids = device.getUuids();
        } catch (SecurityException e) {
            return;
        }
        if (uuids == null || !hasService(uuids, IAP2_SERVICE)) {
            return;
        }
        Log.i(TAG, "wireless: " + device.getAddress() + " offers iAP2, connecting");
        new Thread(() -> connect(device), "carplay-rfcomm").start();
    }

    /** The head unit dials the phone's iAP2 record, then relays bytes until either side closes. */
    private void connect(BluetoothDevice device) {
        BluetoothSocket s;
        InputStream in;
        try {
            s = device.createInsecureRfcommSocketToServiceRecord(IAP2_SERVICE);
            s.connect();
            in = s.getInputStream();
            synchronized (this) {
                phone = s;
                toPhone = s.getOutputStream();
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "wireless: rfcomm connect failed: " + e.getMessage());
            return;
        }
        bridge.btConnected(localMac(), Messages.CARPLAY_BT_SERVICE);
        Log.i(TAG, "wireless: rfcomm open to " + device.getAddress());

        byte[] buf = new byte[READ_BUF];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                bridge.btData(buf, 0, n);
            }
        } catch (IOException e) {
            Log.i(TAG, "wireless: rfcomm closed: " + e.getMessage());
        }
        dropPhone("phone closed the link");
    }

    private synchronized void dropPhone(String why) {
        if (phone == null) {
            return;
        }
        Log.i(TAG, "wireless: dropping the phone: " + why);
        try {
            phone.close();
        } catch (IOException ignored) {
            // already gone
        }
        phone = null;
        toPhone = null;
        bridge.btDisconnected();
    }

    @Override
    public void onBtDataToPhone(byte[] data) {
        OutputStream o;
        synchronized (this) {
            o = toPhone;
        }
        if (o == null) {
            return;
        }
        try {
            o.write(data);
            o.flush();
        } catch (IOException e) {
            dropPhone("write to the phone failed: " + e.getMessage());
        }
    }

    private String localMac() {
        // The adapter's own address is hidden from apps since Android 6; the daemon only echoes
        // it into an Android Auto field, so a placeholder costs CarPlay nothing.
        return UNKNOWN_MAC;
    }

    private boolean btPermitted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasService(ParcelUuid[] uuids, UUID wanted) {
        for (ParcelUuid u : uuids) {
            if (wanted.equals(u.getUuid())) {
                return true;
            }
        }
        return false;
    }

    // ---- hotspot -----------------------------------------------------------------------------

    @Override
    public void onApInfoRequested() {
        main.post(this::raiseAp);
    }

    /**
     * The OS's own access point comes first: Riposte OS raises a 5 GHz local-only hotspot from
     * init (wireless CarPlay refuses 2.4 GHz, and only a shell can pick the band) and publishes
     * it as properties. A normal app's hotspot is the fallback, 2.4 GHz and all.
     */
    private void raiseAp() {
        String ssid = SystemProps.get(PROP_AP_SSID);
        String key = SystemProps.get(PROP_AP_KEY);
        if (!ssid.isEmpty() && !key.isEmpty()) {
            String iface = SystemProps.get(PROP_AP_IFACE);
            Log.i(TAG, "wireless: the OS access point " + ssid + " on " + iface);
            bridge.apUp(ssid, key, Messages.AP_BAND_5GHZ, iface.isEmpty() ? DEFAULT_AP_IFACE : iface);
            return;
        }
        if (apStarting || softAp != null) {
            return;
        }
        if (!SoftAp.permitted(context)) {
            Log.w(TAG, "wireless: hotspot permissions missing");
            return;
        }
        apStarting = true;
        final SoftAp ap = new SoftAp(context);
        ap.start(new SoftAp.Listener() {
            @Override
            public void onUp(Wifi.ApInfo info, String ip) {
                apStarting = false;
                softAp = ap;
                String iface = interfaceFor(ip);
                Log.i(TAG, String.format(Locale.ROOT, "wireless: hotspot %s on %s (%s)", info.ssid, iface, ip));
                bridge.apUp(info.ssid, info.key, Messages.AP_BAND_2GHZ, iface);
            }

            @Override
            public void onDown(String why) {
                apStarting = false;
                Log.w(TAG, "wireless: hotspot down: " + why);
                closeAp(why);
                bridge.apDown();
            }
        });
    }

    private void closeAp(String why) {
        if (softAp == null) {
            return;
        }
        softAp.close();
        softAp = null;
        Log.i(TAG, "wireless: hotspot closed: " + why);
    }

    /** The interface that owns the AP's address; the daemon binds its receiver to it by name. */
    private static String interfaceFor(String ip) {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && ip.equals(a.getHostAddress())) {
                        return ni.getName();
                    }
                }
            }
        } catch (SocketException ignored) {
            // fall through
        }
        return "";
    }
}
