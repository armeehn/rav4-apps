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
    /** {@code <} every 2 s, empty payload; not to be echoed. */
    public static final int HEARTBEAT = 0x118;
    /** {@code <} build hash of the daemon, string in field 2. */
    public static final int DAEMON_VERSION = 0xa02;
    /** {@code >} tears the session down ("received apk stop zlink request"). */
    public static final int STOP = 0x712;

    public static final int STATE_WAIT_INIT = 1;
    public static final int STATE_WAITING_LINK = 2;
    /** A phone is on the way in over Bluetooth; the daemon now wants the access point. */
    public static final int STATE_WIRELESS_CARPLAY = 3;
    public static final int STATE_STOPPED = 6;

    /** InitInfo field 7, a bit set of transports to arm; the daemon prints each bit's name. */
    public static final int LINK_WIRED_CARPLAY = 1;
    public static final int LINK_WIRELESS_CARPLAY = 2;
    public static final int LINK_WIRED_AA = 4;
    public static final int LINK_WIRELESS_AA = 8;

    /** SessionState field 3 while a wireless CarPlay phone is linking. */
    public static final int LINK_TYPE_WIRELESS_CARPLAY = 2;

    // ---- wireless bootstrap (control channel) ------------------------------------------------
    /** {@code <} empty, every 5 s until answered: the daemon wants the access point. */
    public static final int AP_INFO_REQUEST = 0x607;
    /** {@code >} ssid (2), passphrase (3), band (4), interface name (5). */
    public static final int AP_INFO = 0x608;
    /** {@code >} hotspot up or down: type (2), enabled (3). */
    public static final int AP_STATE = 0x503;
    /** AP_INFO band: the platform's WifiConfiguration.AP_BAND_* values, 2.4 GHz is 0. */
    public static final int AP_BAND_2GHZ = 0;
    public static final int AP_BAND_5GHZ = 1;

    // ---- Bluetooth relay (its own channel, port 1999) ----------------------------------------
    /** {@code <} empty, on connect: the daemon wants to know about the phone. */
    public static final int BT_INFO_REQUEST = 0x602;
    /**
     * {@code >} local MAC (2), the matched service UUID as 32 uppercase hex digits (3), paired
     * (4), pair mode (5), pair code (6). The CarPlay UUID here is what moves the session to
     * {@link #STATE_WIRELESS_CARPLAY}; the daemon compares the string byte for byte.
     */
    public static final int BT_INFO = 0x603;
    /**
     * Both ways: raw RFCOMM bytes. From the phone as {@code >}, and the daemon's own iAP2
     * packets for the phone as {@code <} under the same id (its first one is the six-byte link
     * detect). Empty bodies close the channel; never send one.
     */
    public static final int BT_DATA = 0x604;
    /** {@code <} empty: the session moved to Wi-Fi, the RFCOMM link may go. */
    public static final int BT_RELEASE = 0x605;
    /** {@code >} the RFCOMM link closed. */
    public static final int BT_DISCONNECTED = 0x606;

    /** iAP2 over RFCOMM, as the daemon spells it (no dashes, uppercase). */
    public static final String CARPLAY_BT_SERVICE = "00000000DECAFADEDECADEAFDECACAFE";

    // ---- input -------------------------------------------------------------------------------
    /** {@code >} x (2), y (3), is_down (4): panel pixels. */
    public static final int TOUCH = 0x112;
    /** multi_touch {repeated touch_data {id, x, y, is_down}}: the daemon acts on it for HiCar only. */
    public static final int TOUCH_MULTI = 0x111;
    /** {@code >} key_code (2), is_down (3). */
    public static final int KEY = 0x113;

    // ---- MFi ---------------------------------------------------------------------------------
    /** {@code >} empty; the daemon probes the auth chip on i2c and answers {@link #MFI_INFO}. */
    public static final int MFI_INFO_REQUEST = 0x114;
    /** {@code <} is_fake (2), mfi_uuid (3). is_fake=0 on this unit: the chip is genuine. */
    public static final int MFI_INFO = 0x115;
    /** {@code <} empty, once per session; the session runs without an answer. */
    public static final int HANDSHAKE_REQUEST = 0x116;
    /** {@code >} the answer to a challenge the daemon sends; unverified so far. */
    public static final int HANDSHAKE_RESPOND = 0x117;
    /** SessionState 4: the phone's session is up and media flows. */
    public static final int STATE_SESSION = 4;

    // ---- video channel -----------------------------------------------------------------------
    /**
     * Every video frame: a 20-byte raw header, then one Annex-B H.264 access unit. The header
     * is five big-endian u32: width, height, available width, available height, zero (bench
     * capture 2026-09-20: {@code 00000780 000002d0 00000780 000002d0 00000000 | 00000001 27…}).
     */
    public static final int VIDEO_FRAME = 0x302;
    public static final int VIDEO_HEADER_LEN = 20;

    // ---- audio channel -----------------------------------------------------------------------
    /**
     * Every audio frame: a 24-byte raw header, then PCM. The header is six big-endian u32:
     * sample rate, channels, bits per sample, zero, audio type, zero (bench capture
     * 2026-09-20: {@code 0000ac44 00000002 00000010 00000000 00000001 00000000}, then 3840
     * bytes of 16-bit little-endian stereo).
     */
    public static final int AUDIO_FRAME = 0x202;
    public static final int AUDIO_HEADER_LEN = 24;

    /**
     * {@code <} the phone's audio and call picture, as {@code phone_call_state}: call on (2),
     * turn-by-turn (3), speech (4), main audio (5), alt audio (6). Seen idle as
     * {@code 2=0 3=0 4=-1 5=0 6=0}.
     */
    public static final int CALL_STATE = 0x710;

    // ---- microphone --------------------------------------------------------------------------
    /**
     * {@code <} {@code zj.mic.MicStart}: sample rate (2), channels (3), bits (4), bt aec (5).
     * Sent on the audio channel; the daemon logs "AudioMicStart".
     */
    public static final int MIC_START = 0x402;
    /** {@code <} empty, audio channel; the daemon logs "AudioMicStop". */
    public static final int MIC_STOP = 0x403;
    /**
     * {@code >} {@code zj.mic.MIC_DATA} on the audio channel, the one inbound id its reader
     * handles: rate (2), channels (3), bits (4), aec_enable (5), delay_ms (7), data (8).
     */
    public static final int MIC_DATA = 0x404;

    /** A decoded {@link #MIC_START}. */
    public static final class MicStart {
        public int sampleRate;
        public int channels;
        public int bits;
    }

    public static MicStart micStart(byte[] payload) {
        MicStart m = new MicStart();
        Proto.Reader r = new Proto.Reader(payload);
        while (r.next()) {
            if (r.wire() != Proto.WIRE_VARINT) {
                skip(r);
                continue;
            }
            long v = r.varint();
            if (r.field() == 2) {
                m.sampleRate = (int) v;
            } else if (r.field() == 3) {
                m.channels = (int) v;
            } else if (r.field() == 4) {
                m.bits = (int) v;
            }
        }
        return m;
    }

    public static byte[] micData(int sampleRate, int channels, int bits, byte[] pcm, int len) {
        byte[] data = new byte[len];
        System.arraycopy(pcm, 0, data, 0, len);
        return new Proto.Writer()
                .int32(1, MIC_DATA)
                .int32(2, sampleRate)
                .int32(3, channels)
                .int32(4, bits)
                .bool(5, false)      // aec_enable: the daemon's own AEC stays off; the HAL did its part
                .int32(7, 0)         // delay_ms
                .bytes(8, data)
                .toBytes();
    }

    /** {@code <} empty: the driver tapped CarPlay's "back to the car" button. */
    public static final int LEAVE_TO_CAR = 0x501;

    // ---- environment -------------------------------------------------------------------------
    public static final int NIGHT_START = 0x705;
    public static final int NIGHT_STOP = 0x706;
    public static final int VIDEO_RESIZE = 0xb02;

    /** What the head unit tells the daemon about itself once per link. */
    public static final class InitInfo {
        public int width = 1920;
        public int height = 720;
        public int fps = 60;
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

    public static byte[] btInfo(String localMac, String serviceHex, boolean paired) {
        return new Proto.Writer()
                .int32(1, BT_INFO)
                .string(2, localMac)
                .string(3, serviceHex)
                .bool(4, paired)
                .int32(5, 0)
                .string(6, "")
                .toBytes();
    }

    public static byte[] apInfo(String ssid, String passphrase, int band, String iface) {
        return new Proto.Writer()
                .int32(1, AP_INFO)
                .string(2, ssid)
                .string(3, passphrase)
                .int32(4, band)
                .string(5, iface)
                .toBytes();
    }

    public static byte[] apState(boolean up) {
        return new Proto.Writer().int32(1, AP_STATE).int32(2, 1).bool(3, up).toBytes();
    }

    /** {@code zj.control.resize}: res_index (2), width (3), height (4), aa_density (5). */
    public static byte[] videoResize(int width, int height, int aaDensity) {
        return new Proto.Writer().int32(1, VIDEO_RESIZE).int32(2, 0).int32(3, width).int32(4, height)
                .int32(5, aaDensity).toBytes();
    }

    public static byte[] idOnly(int id) {
        return new Proto.Writer().int32(1, id).toBytes();
    }

    /** A decoded {@link #CALL_STATE}. */
    public static final class CallState {
        public boolean callOn;
        public boolean turnByTurn;
        public boolean mainAudio;
    }

    public static CallState callState(byte[] payload) {
        CallState c = new CallState();
        Proto.Reader r = new Proto.Reader(payload);
        while (r.next()) {
            if (r.wire() != Proto.WIRE_VARINT) {
                skip(r);
                continue;
            }
            long v = r.varint();
            if (r.field() == 2) {
                c.callOn = v != 0;
            } else if (r.field() == 3) {
                c.turnByTurn = v != 0;
            } else if (r.field() == 5) {
                c.mainAudio = v != 0;
            }
        }
        return c;
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
