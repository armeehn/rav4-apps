package com.ripostelabs.lamp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * BLE driver for one lamp. Owns the scan, the GATT connection and a write queue, and
 * reports back on the main thread. The Activity never sees a GATT object.
 *
 *   LampActivity ── send(frame) ──▶ LampLink ── queue ──▶ GATT FFF3 ──▶ lamp
 *                ◀── Listener ─────
 *
 * Writes are serialised (one in flight, then a short gap) because the firmware drops frames
 * that arrive back to back. A slider produces many frames a second, so a queued frame with
 * the same command byte as a new one is replaced rather than appended: the lamp only ever
 * needs the latest value.
 */
final class LampLink {

    private static final String TAG = "LampLink";
    private static final long SCAN_MS = 8_000;
    private static final long WRITE_GAP_MS = 50;

    enum State { NO_BLUETOOTH, BLUETOOTH_OFF, IDLE, SCANNING, CONNECTING, READY }

    interface Listener {
        void onState(State state, String detail);
        void onFound(BluetoothDevice device, String name);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private final Set<String> seen = new HashSet<>();

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing;

    LampLink(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    State state() {
        if (adapter == null) {
            return State.NO_BLUETOOTH;
        }
        if (!adapter.isEnabled()) {
            return State.BLUETOOTH_OFF;
        }
        if (writeChar != null) {
            return State.READY;
        }
        if (gatt != null) {
            return State.CONNECTING;
        }
        if (scanner != null) {
            return State.SCANNING;
        }
        return State.IDLE;
    }

    /** Scan for SCAN_MS and report each lamp once. Anything else advertising is ignored. */
    void scan() {
        if (adapter == null || !adapter.isEnabled()) {
            report(state(), null);
            return;
        }
        stopScan();
        seen.clear();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            report(State.IDLE, "No BLE scanner");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            scanner = null;
            report(State.IDLE, "Scan permission missing");
            return;
        }
        report(State.SCANNING, null);
        main.postDelayed(stopScanTask, SCAN_MS);
    }

    void stopScan() {
        main.removeCallbacks(stopScanTask);
        if (scanner == null) {
            return;
        }
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "stopScan: " + e);
        }
        scanner = null;
        report(state(), null);
    }

    void connect(String address) {
        if (adapter == null || address == null) {
            return;
        }
        stopScan();
        disconnect();
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            report(State.IDLE, "Bad address");
            return;
        }
        open(device, false);
    }

    void disconnect() {
        BluetoothGatt g = gatt;
        gatt = null;
        writeChar = null;
        synchronized (queue) {
            queue.clear();
            writing = false;
        }
        if (g == null) {
            return;
        }
        try {
            g.disconnect();
            g.close();
        } catch (SecurityException e) {
            Log.w(TAG, "disconnect: " + e);
        }
    }

    void close() {
        stopScan();
        disconnect();
    }

    /** Queue a frame. Frames with the same command byte collapse to the newest. */
    void send(byte[] frame) {
        synchronized (queue) {
            int cmd = Elk.command(frame);
            for (Iterator<byte[]> it = queue.iterator(); it.hasNext();) {
                if (Elk.command(it.next()) == cmd) {
                    it.remove();
                }
            }
            queue.addLast(frame);
        }
        pump();
    }

    // ---- internals -------------------------------------------------------------------

    private void open(BluetoothDevice device, boolean autoConnect) {
        report(State.CONNECTING, null);
        try {
            gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            report(State.IDLE, "Connect permission missing");
        }
    }

    private void pump() {
        byte[] next;
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = writeChar;
        synchronized (queue) {
            if (writing || g == null || c == null || queue.isEmpty()) {
                return;
            }
            next = queue.pollFirst();
            writing = true;
        }
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(next);
        boolean ok;
        try {
            ok = g.writeCharacteristic(c);
        } catch (SecurityException e) {
            ok = false;
        }
        if (!ok) {
            Log.w(TAG, "write refused " + Elk.hex(next));
            writeDone();
        }
    }

    private void writeDone() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (queue) {
                    writing = false;
                }
                pump();
            }
        }, WRITE_GAP_MS);
    }

    private void report(final State s, final String detail) {
        main.post(new Runnable() {
            @Override public void run() { listener.onState(s, detail); }
        });
    }

    private final Runnable stopScanTask = new Runnable() {
        @Override public void run() { stopScan(); }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            final BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) {
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    name = null;
                }
            }
            if (!Elk.isLamp(name) && !advertisesService(result)) {
                return;
            }
            if (!seen.add(device.getAddress())) {
                return;
            }
            final String shown = name == null ? device.getAddress() : name;
            main.post(new Runnable() {
                @Override public void run() { listener.onFound(device, shown); }
            });
        }

        @Override public void onScanFailed(int errorCode) {
            scanner = null;
            report(State.IDLE, "Scan failed (" + errorCode + ")");
        }
    };

    private static boolean advertisesService(ScanResult result) {
        if (result.getScanRecord() == null) {
            return false;
        }
        List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
        return uuids != null && uuids.contains(new ParcelUuid(Elk.SERVICE));
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    report(State.IDLE, "Connect permission missing");
                }
                return;
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED || g != gatt) {
                return;
            }
            // Lost the lamp (ignition, range, lamp unplugged). Wait for it to come back
            // rather than making the driver tap Scan again.
            writeChar = null;
            synchronized (queue) {
                queue.clear();
                writing = false;
            }
            report(State.CONNECTING, "Lost link, waiting for the lamp");
            try {
                g.connect();
            } catch (SecurityException e) {
                report(State.IDLE, "Connect permission missing");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(Elk.SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(Elk.WRITE);
            if (c == null) {
                report(State.IDLE, "Not a Magic Lantern lamp (no FFF0/FFF3)");
                disconnect();
                return;
            }
            writeChar = c;
            report(State.READY, null);
            pump();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "write status " + status);
            }
            writeDone();
        }
    };
}
