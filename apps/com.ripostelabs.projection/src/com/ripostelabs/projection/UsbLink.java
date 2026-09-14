package com.ripostelabs.projection;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.ripostelabs.projection.aa.Aoa;
import com.ripostelabs.projection.aa.Session;

import java.io.IOException;
import java.util.Map;

/**
 * The USB half: finds the phone, replays the AOA switch, and owns the two bulk endpoints of
 * the accessory interface. Bytes only; the protocol lives in {@link Session}.
 *
 * <pre>
 *   phone (MTP/charging)  --switchToAccessory()-->  re-enumerates as 18D1:2D00/2D01
 *                                                     --open()--> bulk IN / bulk OUT
 * </pre>
 */
final class UsbLink implements Projector.Pipe {

    private static final String TAG = "Projection";
    private static final int USB_CLASS_HUB = 9;
    /** bulkTransfer caps a single call at 16 KiB before API 28; loop in that unit everywhere. */
    private static final int MAX_TRANSFER = 16384;
    private static final int READ_TIMEOUT_MS = 500;
    private static final int WRITE_TIMEOUT_MS = 2000;

    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private final UsbEndpoint in;
    private final UsbEndpoint out;

    private UsbLink(UsbDeviceConnection connection, UsbInterface iface, UsbEndpoint in, UsbEndpoint out) {
        this.connection = connection;
        this.iface = iface;
        this.in = in;
        this.out = out;
    }

    /** A phone already in accessory mode, if one is plugged in. */
    static UsbDevice findAccessory(UsbManager manager) {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (Aoa.isAccessory(d.getVendorId(), d.getProductId())) {
                return d;
            }
        }
        return null;
    }

    /** Anything that is not a hub and not yet an accessory: a candidate for the switch. */
    static UsbDevice findCandidate(UsbManager manager) {
        for (Map.Entry<String, UsbDevice> e : manager.getDeviceList().entrySet()) {
            UsbDevice d = e.getValue();
            if (d.getDeviceClass() == USB_CLASS_HUB) {
                continue;
            }
            if (!Aoa.isAccessory(d.getVendorId(), d.getProductId())) {
                return d;
            }
        }
        return null;
    }

    static String describe(UsbDevice d) {
        return String.format("%04x:%04x %s", d.getVendorId(), d.getProductId(),
                d.getProductName() == null ? "" : d.getProductName());
    }

    /**
     * Replays {@link Aoa#switchSequence()}. On success the phone drops off the bus and comes back
     * as an accessory, which arrives as a new USB_DEVICE_ATTACHED; there is nothing to keep open.
     */
    static boolean switchToAccessory(UsbManager manager, UsbDevice device) {
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) {
            Log.w(TAG, "aoa: openDevice failed for " + describe(device));
            return false;
        }

        try {
            for (Aoa.Step step : Aoa.switchSequence()) {
                byte[] buf = step.direction == Aoa.Direction.IN ? new byte[step.length] : step.data;
                int n = c.controlTransfer(step.requestType, step.request, step.value, step.index,
                        buf, step.length, Aoa.TRANSFER_TIMEOUT_MS);
                if (n < 0) {
                    Log.w(TAG, "aoa: request " + step.request + " index " + step.index + " failed (" + n + ")");
                    return false;
                }

                if (step.request == Aoa.REQ_GET_PROTOCOL) {
                    int version = Aoa.protocolVersion(buf, n);
                    Log.i(TAG, "aoa: phone speaks AOA " + version);
                    if (!Aoa.protocolSupported(version)) {
                        return false;
                    }
                }
            }
            Log.i(TAG, "aoa: START sent, waiting for re-enumeration");
            return true;
        } finally {
            c.close();
        }
    }

    /** Claims the accessory interface and finds its bulk pair. */
    static UsbLink open(UsbManager manager, UsbDevice device) throws IOException {
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) {
            throw new IOException("openDevice failed");
        }

        UsbInterface iface = device.getInterface(0);
        if (!c.claimInterface(iface, true)) {
            c.close();
            throw new IOException("claimInterface failed");
        }

        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                in = ep;
            } else {
                out = ep;
            }
        }
        if (in == null || out == null) {
            c.releaseInterface(iface);
            c.close();
            throw new IOException("accessory interface has no bulk pair");
        }
        Log.i(TAG, "usb: accessory open, in ep " + in.getAddress() + " out ep " + out.getAddress());
        return new UsbLink(c, iface, in, out);
    }

    /** One bulk read; -1 on timeout, which the reader loop simply retries. */
    @Override
    public int read(byte[] buf) {
        return connection.bulkTransfer(in, buf, Math.min(buf.length, MAX_TRANSFER), READ_TIMEOUT_MS);
    }

    @Override
    public void write(byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            int len = Math.min(MAX_TRANSFER, data.length - off);
            int n = connection.bulkTransfer(out, data, off, len, WRITE_TIMEOUT_MS);
            if (n < 0) {
                throw new IOException("bulk write failed at " + off + "/" + data.length);
            }
            off += n;
        }
    }

    @Override
    public void close() {
        connection.releaseInterface(iface);
        connection.close();
    }
}
