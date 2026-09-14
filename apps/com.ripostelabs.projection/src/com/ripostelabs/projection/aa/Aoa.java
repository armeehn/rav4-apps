package com.ripostelabs.projection.aa;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android Open Accessory (AOA) protocol: the control transfers that flip a phone from a plain
 * USB device into an accessory exposing two bulk endpoints.
 *
 * <p>Pure description, no USB. The Android side replays {@link #switchSequence()} through
 * {@code UsbDeviceConnection.controlTransfer}. The sequence and the string table are the ones
 * every open receiver presents (aasdk {@code AccessoryModeQueryChain} / headunit
 * {@code hu_usb.c}), so the phone treats us as the same kind of accessory it already knows.
 *
 * <pre>
 *   head unit                                   phone
 *   ---------                                   -----
 *   GET_PROTOCOL (51)  ------------------------>
 *                      <----- u16 LE version (1 or 2)
 *   SEND_STRING (52) x6 ----------------------->   manufacturer, model, ...
 *   START (53)         ------------------------>
 *                              (phone re-enumerates as 18D1:2D00 or 18D1:2D01)
 * </pre>
 */
public final class Aoa {

    private Aoa() {
    }

    /** Google's USB vendor id; the phone re-enumerates under it once in accessory mode. */
    public static final int GOOGLE_VID = 0x18D1;
    /** Accessory mode. */
    public static final int PID_ACCESSORY = 0x2D00;
    /** Accessory mode with ADB still exposed. */
    public static final int PID_ACCESSORY_ADB = 0x2D01;

    /** Vendor requests (AOA specification; aasdk {@code ACC_REQ_*}). */
    public static final int REQ_GET_PROTOCOL = 51;
    public static final int REQ_SEND_STRING = 52;
    public static final int REQ_START = 53;

    /** bmRequestType for a vendor request, device to host (LIBUSB_ENDPOINT_IN | USB_TYPE_VENDOR). */
    public static final int TYPE_VENDOR_IN = 0xC0;
    /** bmRequestType for a vendor request, host to device (LIBUSB_ENDPOINT_OUT | USB_TYPE_VENDOR). */
    public static final int TYPE_VENDOR_OUT = 0x40;

    /** Protocol versions a receiver accepts (aasdk rejects anything else). */
    public static final int PROTOCOL_MIN = 1;
    public static final int PROTOCOL_MAX = 2;

    /** Timeout aasdk applies to every control transfer of the switch. */
    public static final int TRANSFER_TIMEOUT_MS = 1000;

    /**
     * The identity strings, indexed by the AOA string id that goes in {@code wIndex}. Manufacturer
     * and model are what the phone matches Android Auto on; headunit sends only those two.
     */
    public enum Str {
        MANUFACTURER(0, "Android"),
        MODEL(1, "Android Auto"),
        DESCRIPTION(2, "Android Auto"),
        VERSION(3, "2.0.1"),
        URI(4, "https://ripostelabs.xyz"),
        SERIAL(5, "HU-AAAAAA001");

        public final int index;
        public final String value;

        Str(int index, String value) {
            this.index = index;
            this.value = value;
        }
    }

    /** What to do in a control transfer. */
    public enum Direction {
        IN,
        OUT
    }

    /** One control transfer of the switch sequence. */
    public static final class Step {
        public final Direction direction;
        public final int requestType;
        public final int request;
        public final int value;
        public final int index;
        /** Bytes to send (OUT) or the number of bytes expected (IN, {@code data == null}). */
        public final byte[] data;
        public final int length;

        private Step(Direction direction, int request, int index, byte[] data, int length) {
            this.direction = direction;
            this.requestType = direction == Direction.IN ? TYPE_VENDOR_IN : TYPE_VENDOR_OUT;
            this.request = request;
            this.value = 0;
            this.index = index;
            this.data = data;
            this.length = length;
        }
    }

    /** GET_PROTOCOL, the six SEND_STRINGs in AOA index order, then START. */
    public static List<Step> switchSequence() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Direction.IN, REQ_GET_PROTOCOL, 0, null, 2));

        for (Str s : Str.values()) {
            byte[] text = s.value.getBytes(StandardCharsets.US_ASCII);
            byte[] withNul = new byte[text.length + 1];
            System.arraycopy(text, 0, withNul, 0, text.length);
            steps.add(new Step(Direction.OUT, REQ_SEND_STRING, s.index, withNul, withNul.length));
        }

        steps.add(new Step(Direction.OUT, REQ_START, 0, null, 0));
        return Collections.unmodifiableList(steps);
    }

    /** The two bytes GET_PROTOCOL returns, little-endian. */
    public static int protocolVersion(byte[] reply, int read) {
        if (read < 2) {
            return 0;
        }
        return (reply[0] & 0xFF) | ((reply[1] & 0xFF) << 8);
    }

    public static boolean protocolSupported(int version) {
        return version >= PROTOCOL_MIN && version <= PROTOCOL_MAX;
    }

    public static boolean isAccessory(int vid, int pid) {
        return vid == GOOGLE_VID && (pid == PID_ACCESSORY || pid == PID_ACCESSORY_ADB);
    }
}
