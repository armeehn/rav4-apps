package com.ripostelabs.bluetooth;

import java.nio.charset.StandardCharsets;

/**
 * RAV4-178: what this app asks of the car kit, with no Android in it.
 *
 * Connect, disconnect and forget a bonded phone need BLUETOOTH_PRIVILEGED (the HF client and A2DP
 * sink proxies, BluetoothDevice.removeBond). The launcher is the priv-app that holds it, so this
 * app sends it one broadcast per press and the launcher runs it on its car kit:
 *
 *   this app --BT_DEVICE {op, address}--> launcher (needs our BLUETOOTH_CONNECT) --> BtCarKit
 */
final class CarKit {

    static final String LAUNCHER_PKG = "com.ripostelabs.carlauncher";
    static final String ACTION = "com.ripostelabs.carlauncher.action.BT_DEVICE";
    static final String EXTRA_OP = "op";
    static final String EXTRA_ADDRESS = "address";

    /** The Bluetooth spec's name limit (Core 5.3, Vol 3 Part C 3.2.2): 248 bytes of UTF-8. */
    static final int MAX_NAME_BYTES = 248;

    enum Op { CONNECT, DISCONNECT, FORGET }

    private CarKit() {}

    /** The car name as the adapter will take it: trimmed, cut to 248 bytes; null if blank. */
    static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return null;
        }

        // Drop whole characters from the end until it fits: a byte cut could split one.
        while (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
        }
        return name;
    }
}
