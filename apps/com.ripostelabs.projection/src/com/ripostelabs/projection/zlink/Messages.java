package com.ripostelabs.projection.zlink;

import com.ripostelabs.projection.aa.Proto;

import java.util.Locale;

/**
 * The control-channel vocabulary of the OEM daemon, as far as the bench has proven it. Ids
 * came from sweeping the channel with empty frames and reading the daemon's own log names
 * (it logs {@code unhandle message_id = 0x…} for the rest); field numbers come from the
 * app-side protobuf-lite classes, whose {@code *_FIELD_NUMBER} constants survive packing.
 * Field 1 of every message is the id again.
 *
 * <p>Direction: {@code >} app to daemon, {@code <} daemon to app.
 */
public final class Messages {

    private Messages() {
    }

    // ---- session -----------------------------------------------------------------------------
    /** {@code <} state (field 2) + link type (field 3); the first thing on a fresh link. */
    public static final int SESSION_STATE = 0x101;
    /** {@code >} the head unit's geometry and platform facts; answered by state WAITING_LINK. */
    public static final int INIT_INFO = 0x102;
    /** {@code <} every 2 s, empty payload. */
    public static final int HEARTBEAT = 0x118;
    /** {@code <} build hash of the daemon, string in field 2. */
    public static final int DAEMON_VERSION = 0xa02;
    /** {@code >} tears the session down ("received apk stop zlink request"). */
    public static final int STOP = 0x712;

    public static final int STATE_WAIT_INIT = 1;
    public static final int STATE_WAITING_LINK = 2;
    public static final int STATE_STOPPED = 6;

    /** InitInfo field 7: a bit set of transports to arm. Bit 0 printed as "wired carplay". */
    public static final int LINK_WIRED_CARPLAY = 1;

    // ---- input -------------------------------------------------------------------------------
    /** {@code >} x (2), y (3), is_down (4): panel pixels. */
    public static final int TOUCH = 0x112;
    public static final int TOUCH_MULTI = 0x111;
    /** {@code >} key_code (2), is_down (3). */
    public static final int KEY = 0x113;

    // ---- MFi ---------------------------------------------------------------------------------
    /** {@code >} empty; the daemon probes the auth chip on i2c and answers {@link #MFI_INFO}. */
    public static final int MFI_INFO_REQUEST = 0x114;
    /** {@code <} is_fake (2), mfi_uuid (3). is_fake=0 on this unit: the chip is genuine. */
    public static final int MFI_INFO = 0x115;
    /** {@code >} the answer to a challenge the daemon sends; unverified so far. */
    public static final int HANDSHAKE_RESPOND = 0x117;

    // ---- environment -------------------------------------------------------------------------
    public static final int NIGHT_START = 0x705;
    public static final int NIGHT_STOP = 0x706;
    public static final int VIDEO_RESIZE = 0xb02;

    /** What the head unit tells the daemon about itself once per link. */
    public static final class InitInfo {
        public int width = 1920;
        public int height = 720;
        public int fps = 30;
        public boolean leftHandDrive = true;
        public boolean night;
        public int linkTypes = LINK_WIRED_CARPLAY;
        public int aaDensity = 160;
        public int mfiBus = 0;
        public String otgToHost = "";
        public String otgToDevice = "";
        public String logPath = "";
    }

    public static byte[] initInfo(InitInfo i) {
        return new Proto.Writer()
                .int32(1, INIT_INFO)
                .int32(2, i.width)
                .int32(3, i.height)
                .int32(4, i.fps)
                .bool(5, i.leftHandDrive)
                .bool(6, i.night)
                .int32(7, i.linkTypes)
                .string(8, "")            // http_url
                .bool(9, false)           // is_allow_hicar_connect
                .int32(10, i.aaDensity)
                .int32(11, i.width)       // aa_width
                .int32(12, i.height)      // aa_height
                .bool(13, false)          // is_hud_enable
                .int32(14, i.mfiBus)      // mfi_bus_num_1
                .int32(16, 0)             // otg_bus_num
                .string(17, i.otgToHost)
                .string(18, i.otgToDevice)
                .bool(19, false)          // is_wireless_carplay_ipv4
                .bool(20, false)          // is_force_usb_host
                .string(21, i.logPath)
                .bool(22, false)          // is_force_start_session
                .bool(23, false)          // is_use_phone_audio
                .bool(24, false)          // is_cp_mic_force_8k
                .int32(26, 0)             // init_video_res
                .int32(27, i.width)       // view_area_width
                .int32(28, i.height)      // view_area_height
                .int32(29, 0)             // cp_baredge_state
                .bool(30, false)          // is_hicar_qr_mode
                .bool(31, false)          // enable_app_cpw_aac_decoder
                .toBytes();
    }

    public static byte[] touch(int x, int y, boolean down) {
        return new Proto.Writer().int32(1, TOUCH).int32(2, x).int32(3, y).bool(4, down).toBytes();
    }

    public static byte[] key(int keyCode, boolean down) {
        return new Proto.Writer().int32(1, KEY).int32(2, keyCode).bool(3, down).toBytes();
    }

    public static byte[] idOnly(int id) {
        return new Proto.Writer().int32(1, id).toBytes();
    }

    /** A decoded SessionState. */
    public static final class SessionState {
        public int state;
        public int linkType;
    }

    public static SessionState sessionState(byte[] payload) {
        SessionState s = new SessionState();
        Proto.Reader r = new Proto.Reader(payload);
        while (r.next()) {
            if (r.field() == 2) {
                s.state = (int) r.varint();
            } else if (r.field() == 3) {
                s.linkType = (int) r.varint();
            } else {
                skip(r);
            }
        }
        return s;
    }

    /** Field dump for the log: "1=0x115 2=0 3=\"413B…\"". */
    public static String describe(byte[] payload) {
        StringBuilder sb = new StringBuilder();
        Proto.Reader r = new Proto.Reader(payload);
        try {
            while (r.next()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(r.field()).append('=');
                if (r.wire() == Proto.WIRE_VARINT) {
                    sb.append(String.format(Locale.ROOT, "0x%x", r.varint()));
                } else if (r.wire() == Proto.WIRE_LENGTH) {
                    byte[] b = r.bytes();
                    sb.append(printable(b) ? '"' + new String(b) + '"' : b.length + "B");
                } else {
                    skip(r);
                    sb.append('?');
                }
            }
        } catch (RuntimeException e) {
            sb.append(" …");
        }
        return sb.toString();
    }

    private static boolean printable(byte[] b) {
        if (b.length == 0 || b.length > 64) {
            return false;
        }
        for (byte c : b) {
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static void skip(Proto.Reader r) {
        switch (r.wire()) {
            case Proto.WIRE_VARINT:
                r.varint();
                break;
            case Proto.WIRE_LENGTH:
                r.bytes();
                break;
            case Proto.WIRE_FIXED32:
                r.fixed32();
                break;
            case Proto.WIRE_FIXED64:
                r.fixed64();
                break;
            default:
                throw new IllegalStateException("wire " + r.wire());
        }
    }
}
