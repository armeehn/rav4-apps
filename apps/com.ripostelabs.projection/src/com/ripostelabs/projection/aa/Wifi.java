package com.ripostelabs.projection.aa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wireless Android Auto bootstrap: the short conversation over a Bluetooth RFCOMM link that
 * hands the phone the head unit's Wi-Fi credentials and the TCP endpoint to project to. After
 * it the phone joins the soft-AP, opens the socket, and stage 1's {@link Session} continues
 * unchanged over TCP instead of USB.
 *
 * <pre>
 *   head unit (this)                              phone
 *   RFCOMM server, UUID 4de17a00-...  <---------  connects (after HFP pairing)
 *   WifiVersionRequest  (4)           --------->
 *                                     <---------  WifiVersionResponse (5)      \ optional:
 *   WifiStartRequest    (1) ip, port  --------->                                / dongles skip it
 *                                     <---------  WifiInfoRequest (2)
 *   WifiInfoResponse    (3) ssid, key --------->
 *                                     <---------  WifiStartResponse (7)  status
 *                                     <---------  WifiConnectStatus (6)  status 0 = joined
 *                                     ---------> TCP :5288  ->  Session (VERSION_REQUEST ...)
 * </pre>
 *
 * <p>Framing on the RFCOMM link (nisargjhaveri/WirelessAndroidAutoDongle {@code
 * bluetoothProfiles.cpp}, aa-proxy-rs {@code bluetooth.rs}; both agree): a four-byte header of
 * {@code u16 payload length} then {@code u16 message id}, both big-endian, then a protobuf
 * payload. The length does not count the header. Message ids are the enum both references
 * carry; field numbers are cited per message below. No code from either project is here.
 */
public final class Wifi {

    private Wifi() {
    }

    // ---- the RFCOMM service ---------------------------------------------------------------

    /** The SDP record the phone looks for on a paired car (dongle and aa-proxy-rs literals). */
    public static final String SERVICE_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66";
    public static final String SERVICE_NAME = "AA Wireless";

    /**
     * The TCP port announced in WifiStartRequest. The dongle's default ({@code AAWG_PROXY_PORT})
     * and what AAWireless adapters use. mikereidis/headunit's 5277 is the other way round: its
     * head unit <em>connects</em> to a server app on the phone over an adb forward, a developer
     * transport, not the wireless bootstrap. Its wifi-direct mode listens on 30515 for that same
     * companion app. Neither is what a stock phone dials.
     */
    public static final int DEFAULT_PORT = 5288;

    // ---- message ids (dongle bluetoothProfiles.cpp, aa-proxy-rs bluetooth.rs MessageId) --

    public static final int START_REQUEST = 1;
    public static final int INFO_REQUEST = 2;
    public static final int INFO_RESPONSE = 3;
    public static final int VERSION_REQUEST = 4;
    public static final int VERSION_RESPONSE = 5;
    public static final int CONNECT_STATUS = 6;
    public static final int START_RESPONSE = 7;
    public static final int PING_REQUEST = 8;
    public static final int PING_RESPONSE = 9;

    /** WifiInfoResponse.SecurityMode (dongle WifiInfoResponse.proto). */
    public static final int SECURITY_OPEN = 1;
    public static final int SECURITY_WPA2_PERSONAL = 8;
    public static final int SECURITY_WPA_WPA2_PERSONAL = 12;

    /** WifiInfoResponse.AccessPointType (dongle WifiInfoResponse.proto). */
    public static final int AP_STATIC = 0;
    public static final int AP_DYNAMIC = 1;

    /** Status 0 is success in every status field; the phone reports failure as a negative int32. */
    public static final int STATUS_OK = 0;

    /**
     * The bootstrap protocol version we claim. Not confirmed against a phone: aa-proxy-rs ships
     * 5.1 as its override default for head units whose own value the phone rejects, which is
     * the strongest hint available without a capture.
     */
    public static final int VERSION_MAJOR = 5;
    public static final int VERSION_MINOR = 1;

    // ---- framing ---------------------------------------------------------------------------

    public static final int HEADER_LEN = 4;
    private static final int MAX_PAYLOAD = 0xFFFF;
    private static final int BYTE_MASK = 0xFF;
    private static final int BYTE_SHIFT = 8;

    /** One framed message: id and protobuf payload. */
    public static final class Message {
        public final int id;
        public final byte[] payload;

        public Message(int id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }
    }

    /** Header + payload, ready for the socket. */
    public static byte[] frame(int id, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too long for a u16 length: " + payload.length);
        }
        byte[] out = new byte[HEADER_LEN + payload.length];
        out[0] = (byte) (payload.length >>> BYTE_SHIFT);
        out[1] = (byte) payload.length;
        out[2] = (byte) (id >>> BYTE_SHIFT);
        out[3] = (byte) id;
        System.arraycopy(payload, 0, out, HEADER_LEN, payload.length);
        return out;
    }

    /** Reassembles messages from a byte stream that arrives in arbitrary chunks. */
    public static final class Reader {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        public void push(byte[] data, int off, int len) {
            pending.write(data, off, len);
        }

        /** The next complete message, or null until one is whole. */
        public Message next() {
            byte[] buf = pending.toByteArray();
            if (buf.length < HEADER_LEN) {
                return null;
            }
            int len = ((buf[0] & BYTE_MASK) << BYTE_SHIFT) | (buf[1] & BYTE_MASK);
            int id = ((buf[2] & BYTE_MASK) << BYTE_SHIFT) | (buf[3] & BYTE_MASK);
            if (buf.length < HEADER_LEN + len) {
                return null;
            }

            byte[] payload = new byte[len];
            System.arraycopy(buf, HEADER_LEN, payload, 0, len);
            pending.reset();
            pending.write(buf, HEADER_LEN + len, buf.length - HEADER_LEN - len);
            return new Message(id, payload);
        }
    }

    // ---- messages --------------------------------------------------------------------------

    /**
     * The soft-AP the phone is told to join. Field numbers from the dongle's
     * {@code WifiInfoResponse.proto}: ssid 1, key 2, bssid 3, security_mode 4,
     * access_point_type 5. All five are {@code required} there, so all five are always written.
     */
    public static final class ApInfo {
        public final String ssid;
        public final String key;
        public final String bssid;
        public final int securityMode;
        public final int accessPointType;

        public ApInfo(String ssid, String key, String bssid, int securityMode, int accessPointType) {
            this.ssid = ssid;
            this.key = key;
            this.bssid = bssid;
            this.securityMode = securityMode;
            this.accessPointType = accessPointType;
        }
    }

    /** What the head unit says about itself in WifiVersionRequest.HeadUnitInfo. */
    public static final class HeadUnitInfo {
        public String carMake = "";
        public String carModel = "";
        public String carYear = "";
        public String vehicleId = "";
        public String headUnitMake = "";
        public String headUnitModel = "";
        public String softwareBuild = "";
        public String softwareVersion = "";
    }

    /**
     * WifiVersionRequest. Fields as aa-proxy-rs decodes them from Gearhead: major_version 1,
     * minor_version 2, supported_wifi_channels 3 (repeated int32), head_unit_info 4,
     * projection_protocol_info 5 {ip_address 1, port 2}. HeadUnitInfo: car_make 1, car_model 2,
     * car_year 3, vehicle_id 4, head_unit_make 5, head_unit_model 6, head_unit_software_build 7,
     * head_unit_software_version 8. aa-proxy-rs notes a second layout in MBUX captures
     * (HeadUnitInfo at 5); the Gearhead one is followed here.
     */
    public static byte[] versionRequest(int major, int minor, int[] channels, HeadUnitInfo hu, String ip, int port) {
        Proto.Writer w = new Proto.Writer().varint(1, major).varint(2, minor);
        for (int ch : channels) {
            w.int32(3, ch);
        }
        Proto.Writer info = new Proto.Writer()
                .string(1, hu.carMake)
                .string(2, hu.carModel)
                .string(3, hu.carYear)
                .string(4, hu.vehicleId)
                .string(5, hu.headUnitMake)
                .string(6, hu.headUnitModel)
                .string(7, hu.softwareBuild)
                .string(8, hu.softwareVersion);
        w.message(4, info);
        w.message(5, new Proto.Writer().string(1, ip).int32(2, port));
        return w.toBytes();
    }

    /**
     * WifiVersionResponse (aa-proxy-rs decoder): major_version 1, minor_version 2,
     * device_serial 3, status 4 (int32), selected_wifi_channel_type 5, device_info 6
     * {device_id 1, connectivity_lifetime_id 2}.
     */
    public static final class VersionResponse {
        public int major;
        public int minor;
        public String serial = "";
        public int status = STATUS_OK;
        public int channelType;
        public String deviceId = "";

        public static VersionResponse parse(byte[] body) {
            VersionResponse v = new VersionResponse();
            Proto.Reader r = new Proto.Reader(body);
            while (r.next()) {
                switch (r.field()) {
                    case 1:
                        v.major = (int) r.varint();
                        break;
                    case 2:
                        v.minor = (int) r.varint();
                        break;
                    case 3:
                        v.serial = r.string();
                        break;
                    case 4:
                        v.status = (int) r.varint();
                        break;
                    case 5:
                        v.channelType = (int) r.varint();
                        break;
                    case 6:
                        v.deviceId = deviceId(r.message());
                        break;
                    default:
                        r.skip();
                }
            }
            return v;
        }

        private static String deviceId(Proto.Reader r) {
            String id = "";
            while (r.next()) {
                if (r.field() == 1) {
                    id = r.string();
                    continue;
                }
                r.skip();
            }
            return id;
        }
    }

    /** WifiStartRequest (dongle WifiStartRequest.proto): ip_address 1, port 2. */
    public static byte[] startRequest(String ip, int port) {
        return new Proto.Writer().string(1, ip).int32(2, port).toBytes();
    }

    /** WifiInfoResponse, fields per {@link ApInfo}. */
    public static byte[] infoResponse(ApInfo ap) {
        return new Proto.Writer()
                .string(1, ap.ssid)
                .string(2, ap.key)
                .string(3, ap.bssid)
                .varint(4, ap.securityMode)
                .varint(5, ap.accessPointType)
                .toBytes();
    }

    /**
     * WifiStartResponse (aa-proxy-rs): ip_address 1, port 2, status 3 (int32). The status sits
     * at 3, not 1; a bare {@code 08 00} would be a broken string field.
     */
    public static final class StartResponse {
        public String ip = "";
        public int port;
        public int status = STATUS_OK;

        public static StartResponse parse(byte[] body) {
            StartResponse s = new StartResponse();
            Proto.Reader r = new Proto.Reader(body);
            while (r.next()) {
                switch (r.field()) {
                    case 1:
                        s.ip = r.string();
                        break;
                    case 2:
                        s.port = (int) r.varint();
                        break;
                    case 3:
                        s.status = (int) r.varint();
                        break;
                    default:
                        r.skip();
                }
            }
            return s;
        }
    }

    /**
     * WifiConnectStatus (aa-proxy-rs): status 1 (int32). {@code 08 00} is success; a phone that
     * could not join sends a large negative value, ten bytes long on the wire.
     */
    public static int connectStatus(byte[] body) {
        Proto.Reader r = new Proto.Reader(body);
        while (r.next()) {
            if (r.field() == 1) {
                return (int) r.varint();
            }
            r.skip();
        }
        return STATUS_OK;
    }

    /** WifiPingRequest / WifiPingResponse (aa-proxy-rs): timestamp 1, int64. Echoed back. */
    public static long pingTimestamp(byte[] body) {
        Proto.Reader r = new Proto.Reader(body);
        while (r.next()) {
            if (r.field() == 1) {
                return r.varint();
            }
            r.skip();
        }
        return 0;
    }

    public static byte[] ping(long timestamp) {
        return new Proto.Writer().varint(1, timestamp).toBytes();
    }

    // ---- the bootstrap state machine ---------------------------------------------------------

    /**
     * Drives one phone from RFCOMM accept to "joined the AP". Transport-agnostic like
     * {@link Session}: bytes in through {@link #onBytes}, out through {@link Link}, one log
     * line per transition through {@link Sink}. The caller serialises calls.
     *
     * <pre>
     *   IDLE --start()--> VERSION --VersionResponse--> INFO --InfoRequest--> START
     *                        |                                                 |
     *                        +--InfoRequest (phone skipped version)------------+
     *                                                                          |
     *                   CONNECTED <--ConnectStatus 0-- (StartResponse noted) --+
     *   any status != 0 --> FAILED
     * </pre>
     */
    public static final class Bootstrap {

        public interface Link {
            void write(byte[] data) throws IOException;
        }

        public interface Sink {
            void log(String line);

            void onState(State state);
        }

        public enum State {
            IDLE,
            VERSION,
            INFO,
            START,
            CONNECTED,
            FAILED
        }

        /** What the phone is told. */
        public static final class Config {
            public final HeadUnitInfo headUnit = new HeadUnitInfo();
            public String ip = "";
            public int port = DEFAULT_PORT;
            public ApInfo ap;
            /** Wi-Fi channels offered in WifiVersionRequest; empty means "not stated". */
            public final List<Integer> channels = new ArrayList<>();
        }

        private final Link link;
        private final Sink sink;
        private final Config config;
        private final Reader reader = new Reader();
        private State state = State.IDLE;
        private boolean startAcknowledged;

        public Bootstrap(Link link, Sink sink, Config config) {
            if (config.ap == null) {
                throw new IllegalArgumentException("no access point to hand out");
            }
            this.link = link;
            this.sink = sink;
            this.config = config;
        }

        public State state() {
            return state;
        }

        public boolean startAcknowledged() {
            return startAcknowledged;
        }

        /** The phone has connected: open with the version request. */
        public void start() throws IOException {
            int[] channels = new int[config.channels.size()];
            for (int i = 0; i < channels.length; i++) {
                channels[i] = config.channels.get(i);
            }
            send(VERSION_REQUEST, versionRequest(VERSION_MAJOR, VERSION_MINOR, channels, config.headUnit, config.ip, config.port));
            sink.log("wifi: version request " + VERSION_MAJOR + "." + VERSION_MINOR + ", endpoint " + config.ip + ":" + config.port);
            enter(State.VERSION);
        }

        public void onBytes(byte[] data, int off, int len) throws IOException {
            reader.push(data, off, len);
            Message m;
            while ((m = reader.next()) != null) {
                handle(m);
            }
        }

        private void handle(Message m) throws IOException {
            if (state == State.FAILED) {
                return;
            }
            switch (m.id) {
                case PING_REQUEST:
                    send(PING_RESPONSE, ping(pingTimestamp(m.payload)));
                    return;
                case VERSION_RESPONSE:
                    onVersion(VersionResponse.parse(m.payload));
                    return;
                case INFO_REQUEST:
                    onInfoRequest();
                    return;
                case START_RESPONSE:
                    onStartResponse(StartResponse.parse(m.payload));
                    return;
                case CONNECT_STATUS:
                    onConnectStatus(connectStatus(m.payload));
                    return;
                default:
                    sink.log(String.format(Locale.ROOT, "wifi: ignoring message %d (%d bytes) in %s", m.id, m.payload.length, state));
            }
        }

        private void onVersion(VersionResponse v) throws IOException {
            if (state != State.VERSION) {
                sink.log("wifi: version response out of turn in " + state);
                return;
            }
            sink.log("wifi: phone version " + v.major + "." + v.minor + " serial " + v.serial + " status " + v.status);
            if (v.status != STATUS_OK) {
                fail("phone refused version " + VERSION_MAJOR + "." + VERSION_MINOR + ": " + v.status);
                return;
            }
            sendStart();
        }

        private void sendStart() throws IOException {
            send(START_REQUEST, startRequest(config.ip, config.port));
            sink.log("wifi: start request " + config.ip + ":" + config.port);
            enter(State.INFO);
        }

        private void onInfoRequest() throws IOException {
            if (state == State.VERSION) {
                // A phone that never answers the version request (the dongle path has no
                // version exchange at all) asks for credentials straight away.
                sink.log("wifi: phone skipped the version exchange");
                sendStart();
            }
            if (state != State.INFO && state != State.START) {
                sink.log("wifi: info request out of turn in " + state);
                return;
            }
            send(INFO_RESPONSE, infoResponse(config.ap));
            sink.log("wifi: sent credentials for " + config.ap.ssid + " (security " + config.ap.securityMode + ")");
            enter(State.START);
        }

        private void onStartResponse(StartResponse s) {
            if (state != State.START) {
                sink.log("wifi: start response out of turn in " + state);
                return;
            }
            if (s.status != STATUS_OK) {
                fail("phone declined to start: " + s.status);
                return;
            }
            startAcknowledged = true;
            sink.log("wifi: phone accepted start (" + s.ip + ":" + s.port + ")");
        }

        private void onConnectStatus(int status) {
            if (state != State.START) {
                sink.log("wifi: connect status out of turn in " + state);
                return;
            }
            if (status != STATUS_OK) {
                fail("phone could not join " + config.ap.ssid + ": " + status);
                return;
            }
            sink.log("wifi: phone joined " + config.ap.ssid + "; expecting TCP on " + config.port);
            enter(State.CONNECTED);
        }

        private void fail(String why) {
            sink.log("wifi: " + why);
            enter(State.FAILED);
        }

        private void enter(State next) {
            state = next;
            sink.onState(next);
        }

        private void send(int id, byte[] payload) throws IOException {
            link.write(frame(id, payload));
        }
    }
}
