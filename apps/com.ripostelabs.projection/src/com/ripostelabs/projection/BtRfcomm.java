package com.ripostelabs.projection;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.ripostelabs.projection.aa.Wifi;

import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;

/**
 * The RFCOMM server the phone finds on a paired car: one SDP record under the Android Auto
 * wireless UUID, one accept loop, one socket at a time.
 *
 * <pre>
 *   BluetoothAdapter.listenUsingRfcommWithServiceRecord("AA Wireless", 4de17a00-...)
 *          |
 *      accept()  <----  phone (already paired for hands-free) connects
 *          |
 *      Listener.onPhone(socket)  ->  Wifi.Bootstrap over its streams
 * </pre>
 *
 * <p>Both open references register the record as the server side; the phone dials it once a
 * HFP/HSP link to the same address is up. Pairing itself is left to the Bluetooth settings
 * (or the suite's bluetooth app); this only listens.
 *
 * <p>Permissions: {@code BLUETOOTH_CONNECT} is what {@code listenUsingRfcomm...} and
 * {@code accept()} check on API 31+. {@code BLUETOOTH_ADVERTISE} is what a discoverable request
 * checks; it is asked for alongside so the driver can make the unit visible from here later
 * without a second prompt. Both are runtime permissions, requested by the activity.
 */
final class BtRfcomm implements Closeable {

    interface Listener {
        /** Called on the accept thread with a connected phone. */
        void onPhone(BluetoothSocket socket);

        void onLog(String line);
    }

    private static final String TAG = "Projection";
    private static final UUID SERVICE = UUID.fromString(Wifi.SERVICE_UUID);

    private final BluetoothServerSocket server;
    private final Thread acceptor;
    private volatile boolean open = true;

    private BtRfcomm(BluetoothServerSocket server, final Listener listener) {
        this.server = server;
        this.acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(listener);
            }
        }, "projection-rfcomm");
    }

    /** Runtime permissions this needs on the running platform; empty below API 31. */
    static String[] runtimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return new String[0];
        }
        return new String[] {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE};
    }

    static boolean permitted(Context context) {
        for (String p : runtimePermissions()) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Registers the record and starts accepting. Throws when there is no adapter or it is off. */
    static BtRfcomm listen(Context context, Listener listener) throws IOException {
        if (!permitted(context)) {
            throw new IOException("BLUETOOTH_CONNECT not granted");
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IOException("no Bluetooth adapter");
        }
        if (!adapter.isEnabled()) {
            throw new IOException("Bluetooth is off");
        }

        BluetoothServerSocket server;
        try {
            server = adapter.listenUsingRfcommWithServiceRecord(Wifi.SERVICE_NAME, SERVICE);
        } catch (SecurityException e) {
            throw new IOException("rfcomm listen refused: " + e.getMessage());
        }
        BtRfcomm r = new BtRfcomm(server, listener);
        r.acceptor.start();
        return r;
    }

    private void acceptLoop(Listener listener) {
        while (open) {
            BluetoothSocket phone;
            try {
                phone = server.accept();
            } catch (IOException e) {
                if (open) {
                    listener.onLog("rfcomm: accept failed: " + e.getMessage());
                }
                return;
            }
            listener.onPhone(phone);
        }
    }

    @Override
    public void close() {
        open = false;
        try {
            server.close();
        } catch (IOException e) {
            Log.w(TAG, "rfcomm close: " + e.getMessage());
        }
    }
}
