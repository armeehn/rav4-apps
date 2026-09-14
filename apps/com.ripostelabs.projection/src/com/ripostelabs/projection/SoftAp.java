package com.ripostelabs.projection;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ripostelabs.projection.aa.Wifi;

import java.io.Closeable;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The access point the phone joins. Two ways to raise one, chosen by {@link #USE_TETHERING}:
 *
 * <pre>
 *   normal app (this build)          priv-app on Riposte OS 0.2 (flag on)
 *   WifiManager                      ConnectivityManager
 *     .startLocalOnlyHotspot()         .startTethering(TETHERING_WIFI, ...)   [hidden API]
 *     random SSID + passphrase         Settings > Hotspot SSID/passphrase, band choice
 *     2.4 GHz, platform's choice       needs TETHER_PRIVILEGED, i.e. a system signature
 *     dies with the app process        survives the app
 * </pre>
 *
 * <p>Local-only hotspot is the only soft-AP a normal app can raise on Android 13. Its limits
 * are exactly the things a wireless AA head unit would like to control: the SSID and passphrase
 * are generated per start (which does not matter here, since the phone is handed both over
 * Bluetooth); the band is whatever the platform picks, 2.4 GHz on every device seen, and the
 * overload that takes a {@link SoftApConfiguration} to ask for 5 GHz is {@code @SystemApi};
 * it stops when the reservation closes or the process dies; and on API 33 it wants
 * {@code NEARBY_WIFI_DEVICES} at runtime. The OEM app read the unit's hotspot settings and
 * started tethering (device-reveng {@code ZLINK_REWRITE.md} section 8), which is what the
 * flagged path does once the app is signed as a priv-app.
 *
 * <p>The AP's own IPv4 address is what the phone is told to dial. There is no API for it, so
 * the interfaces are snapshotted before the start and the one that appears is taken.
 */
final class SoftAp implements Closeable {

    interface Listener {
        /** Called on the main thread once the AP is up and its address is known. */
        void onUp(Wifi.ApInfo ap, String ip);

        void onDown(String why);
    }

    /**
     * Tethering path for the priv-app build. Off: the reflective call would be blocked by the
     * hidden-API policy and by the missing TETHER_PRIVILEGED permission on a normal install.
     */
    static final boolean USE_TETHERING = false;

    private static final String TAG = "Projection";
    /** ConnectivityManager.TETHERING_WIFI, hidden. */
    private static final int TETHERING_WIFI = 0;
    private static final String UNKNOWN_BSSID = "";

    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;

    SoftAp(Context context) {
        wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Runtime permissions the hotspot call checks; NEARBY_WIFI_DEVICES from API 33 on. */
    static String[] runtimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[] {Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

    static boolean permitted(Context context) {
        for (String p : runtimePermissions()) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    void start(final Listener listener) {
        if (USE_TETHERING) {
            startTethering(listener);
            return;
        }
        if (wifi == null) {
            listener.onDown("no Wi-Fi service");
            return;
        }

        final Set<String> before = ipv4Addresses();
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    reservation = r;
                    Wifi.ApInfo ap = describe(r);
                    String ip = newAddress(before);
                    if (ip == null) {
                        listener.onDown("hotspot up but no new IPv4 address appeared");
                        return;
                    }
                    listener.onUp(ap, ip);
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    listener.onDown("hotspot stopped by the platform");
                }

                @Override
                public void onFailed(int reason) {
                    listener.onDown("hotspot failed: " + reasonName(reason));
                }
            }, main);
        } catch (SecurityException | IllegalStateException e) {
            listener.onDown("hotspot refused: " + e.getMessage());
        }
    }

    /**
     * The priv-app path: {@code ConnectivityManager.startTethering(int, boolean, OnStartTetheringCallback)}
     * is hidden, so it is reached by reflection and the AP details come from the unit's own
     * hotspot settings. Untested here; only reachable with {@link #USE_TETHERING}.
     */
    private void startTethering(Listener listener) {
        try {
            Class<?> cb = Class.forName("android.net.ConnectivityManager$OnStartTetheringCallback");
            java.lang.reflect.Method m = ConnectivityManager.class.getMethod("startTethering", int.class, boolean.class, cb);
            m.invoke(connectivity, TETHERING_WIFI, false, null);
            listener.onDown("tethering requested; reading the AP settings back is not wired yet");
        } catch (ReflectiveOperationException | RuntimeException e) {
            listener.onDown("tethering path unavailable: " + e);
        }
    }

    private static Wifi.ApInfo describe(WifiManager.LocalOnlyHotspotReservation r) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SoftApConfiguration c = r.getSoftApConfiguration();
            String bssid = c.getBssid() == null ? UNKNOWN_BSSID : c.getBssid().toString();
            int security = c.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_OPEN
                    ? Wifi.SECURITY_OPEN : Wifi.SECURITY_WPA2_PERSONAL;
            String key = c.getPassphrase() == null ? "" : c.getPassphrase();
            // getBand() is @SystemApi: a normal app cannot even read which band it got.
            Log.i(TAG, String.format(Locale.ROOT, "softap: ssid %s security %d", c.getSsid(), c.getSecurityType()));
            return new Wifi.ApInfo(c.getSsid(), key, bssid, security, Wifi.AP_DYNAMIC);
        }
        WifiConfiguration c = r.getWifiConfiguration();
        String ssid = c.SSID == null ? "" : c.SSID.replace("\"", "");
        String key = c.preSharedKey == null ? "" : c.preSharedKey;
        return new Wifi.ApInfo(ssid, key, UNKNOWN_BSSID, Wifi.SECURITY_WPA2_PERSONAL, Wifi.AP_DYNAMIC);
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "no channel";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                return "generic";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "incompatible mode (Wi-Fi Direct or tethering already up)";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "tethering disallowed";
            default:
                return String.valueOf(reason);
        }
    }

    private static Set<String> ipv4Addresses() {
        Set<String> out = new HashSet<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                for (InetAddress a : java.util.Collections.list(ifs.nextElement().getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "softap: interfaces: " + e.getMessage());
        }
        return out;
    }

    private static String newAddress(Set<String> before) {
        for (String a : ipv4Addresses()) {
            if (!before.contains(a)) {
                return a;
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (reservation == null) {
            return;
        }
        reservation.close();
        reservation = null;
    }
}
