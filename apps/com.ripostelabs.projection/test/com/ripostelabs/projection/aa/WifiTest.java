package com.ripostelabs.projection.aa;

import java.util.ArrayList;
import java.util.List;

/** The RFCOMM bootstrap: framing vectors, message vectors, and a scripted phone through it. */
public final class WifiTest {

    /** Captures what the head unit sends and hands it back one message at a time. */
    private static final class Wire implements Wifi.Bootstrap.Link {
        final Wifi.Reader reader = new Wifi.Reader();

        @Override
        public void write(byte[] data) {
            reader.push(data, 0, data.length);
        }

        Wifi.Message next() {
            Wifi.Message m = reader.next();
            Check.that(m != null, "head unit sent a message");
            return m;
        }

        void drained() {
            Check.that(reader.next() == null, "no stray messages");
        }
    }

    private static final class Log implements Wifi.Bootstrap.Sink {
        final List<String> lines = new ArrayList<>();
        final List<Wifi.Bootstrap.State> states = new ArrayList<>();

        @Override
        public void log(String line) {
            lines.add(line);
        }

        @Override
        public void onState(Wifi.Bootstrap.State state) {
            states.add(state);
        }
    }

    private static final String SSID = "RAV4-AA";
    private static final String KEY = "correct horse";
    private static final String BSSID = "02:00:00:00:00:01";
    private static final String IP = "192.168.43.1";

    public static void main(String[] args) throws Exception {
        constants();
        framing();
        reassembly();
        vectors();
        fullBootstrap();
        phoneSkipsVersion();
        phoneCannotJoin();
        pingAnyTime();
        System.out.println(Check.count + " assertions passed");
    }

    private static void constants() {
        Check.eq("4de17a00-52cb-11e6-bdf4-0800200c9a66", Wifi.SERVICE_UUID, "service UUID");
        Check.eq(5288, Wifi.DEFAULT_PORT, "wireless AA port");
        Check.eq(1, Wifi.START_REQUEST, "WifiStartRequest");
        Check.eq(2, Wifi.INFO_REQUEST, "WifiInfoRequest");
        Check.eq(3, Wifi.INFO_RESPONSE, "WifiInfoResponse");
        Check.eq(4, Wifi.VERSION_REQUEST, "WifiVersionRequest");
        Check.eq(5, Wifi.VERSION_RESPONSE, "WifiVersionResponse");
        Check.eq(6, Wifi.CONNECT_STATUS, "WifiConnectStatus");
        Check.eq(7, Wifi.START_RESPONSE, "WifiStartResponse");
        Check.eq(8, Wifi.SECURITY_WPA2_PERSONAL, "WPA2_PERSONAL");
        Check.eq(1, Wifi.AP_DYNAMIC, "DYNAMIC access point");
    }

    private static void framing() {
        // length (u16 BE, payload only) then id (u16 BE) then payload.
        Check.bytes(Check.hex("00 02 00 06 08 00"), Wifi.frame(Wifi.CONNECT_STATUS, Check.hex("08 00")), "framed status");
        Check.bytes(Check.hex("00 00 00 02"), Wifi.frame(Wifi.INFO_REQUEST, new byte[0]), "empty payload");
        byte[] big = Wifi.frame(0x1234, new byte[300]);
        Check.bytes(Check.hex("01 2c 12 34"), new byte[] {big[0], big[1], big[2], big[3]}, "300-byte length, high byte first");

        boolean refused = false;
        try {
            Wifi.frame(1, new byte[0x10000]);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        Check.that(refused, "payload over u16 refused");
    }

    private static void reassembly() {
        Wifi.Reader r = new Wifi.Reader();
        byte[] two = Check.hex("00 02 00 06 08 00  00 00 00 02");
        r.push(two, 0, 3);
        Check.that(r.next() == null, "three bytes is not a header");
        r.push(two, 3, 2);
        Check.that(r.next() == null, "header without its payload waits");
        r.push(two, 5, two.length - 5);
        Wifi.Message a = r.next();
        Check.eq(Wifi.CONNECT_STATUS, a.id, "first id");
        Check.bytes(Check.hex("08 00"), a.payload, "first payload");
        Wifi.Message b = r.next();
        Check.eq(Wifi.INFO_REQUEST, b.id, "second id");
        Check.eq(0, b.payload.length, "second is empty");
        Check.that(r.next() == null, "drained");
    }

    private static void vectors() {
        // WifiStartRequest{ip_address=1 "10.0.0.1", port=2 5288}: 5288 = 0x14A8 -> a8 29.
        Check.bytes(Check.hex("0a 08 31 30 2e 30 2e 30 2e 31 10 a8 29"), Wifi.startRequest("10.0.0.1", 5288), "start request");

        // WifiInfoResponse{ssid=1, key=2, bssid=3, security_mode=4 WPA2 (8), access_point_type=5 DYNAMIC (1)}.
        Wifi.ApInfo ap = new Wifi.ApInfo("ab", "pw", "00:11", Wifi.SECURITY_WPA2_PERSONAL, Wifi.AP_DYNAMIC);
        Check.bytes(Check.hex("0a 02 61 62 12 02 70 77 1a 05 30 30 3a 31 31 20 08 28 01"), Wifi.infoResponse(ap), "info response");

        // WifiConnectStatus{status=1}: ok, and the ten-byte negative a failing phone sends.
        Check.eq(0, Wifi.connectStatus(Check.hex("08 00")), "status ok");
        Check.that(Wifi.connectStatus(Check.hex("08 fd ff ff ff ff ff ff ff ff 01")) < 0, "negative status");

        // WifiStartResponse{ip_address=1, port=2, status=3}.
        Wifi.StartResponse sr = Wifi.StartResponse.parse(Check.hex("0a 01 78 10 01 18 00"));
        Check.eq("x", sr.ip, "start response ip");
        Check.eq(1, sr.port, "start response port");
        Check.eq(0, sr.status, "start response status");

        // WifiVersionResponse{major=1, minor=2, serial=3, status=4, channel_type=5, device_info=6{device_id=1}}.
        Wifi.VersionResponse vr = Wifi.VersionResponse.parse(Check.hex("08 05 10 01 1a 01 53 20 00 28 02 32 03 0a 01 64"));
        Check.eq(5, vr.major, "major");
        Check.eq(1, vr.minor, "minor");
        Check.eq("S", vr.serial, "serial");
        Check.eq(0, vr.status, "version status");
        Check.eq(2, vr.channelType, "channel type");
        Check.eq("d", vr.deviceId, "device id");

        // WifiVersionRequest: major 1, minor 2, channels 3, head_unit_info 4, projection 5{ip 1, port 2}.
        Wifi.HeadUnitInfo hu = new Wifi.HeadUnitInfo();
        hu.carMake = "T";
        hu.headUnitModel = "G";
        byte[] req = Wifi.versionRequest(5, 1, new int[] {1, 2}, hu, "h", 7);
        Proto.Reader r = new Proto.Reader(req);
        List<Integer> fields = new ArrayList<>();
        while (r.next()) {
            fields.add(r.field());
            if (r.field() == 4) {
                Proto.Reader info = r.message();
                Check.that(info.next() && info.field() == 1, "car_make is field 1");
                Check.eq("T", info.string(), "car make");
                continue;
            }
            if (r.field() == 5) {
                Proto.Reader p = r.message();
                Check.that(p.next() && p.field() == 1, "ip is field 1");
                Check.eq("h", p.string(), "endpoint ip");
                Check.that(p.next() && p.field() == 2, "port is field 2");
                Check.eq(7, p.varint(), "endpoint port");
                continue;
            }
            r.skip();
        }
        Check.eq("[1, 2, 3, 3, 4, 5]", fields.toString(), "version request field order");

        // Ping echoes the timestamp.
        Check.eq(1234567L, Wifi.pingTimestamp(Wifi.ping(1234567L)), "ping round trip");
    }

    private static Wifi.Bootstrap.Config config() {
        Wifi.Bootstrap.Config c = new Wifi.Bootstrap.Config();
        c.ip = IP;
        c.ap = new Wifi.ApInfo(SSID, KEY, BSSID, Wifi.SECURITY_WPA2_PERSONAL, Wifi.AP_DYNAMIC);
        c.headUnit.headUnitMake = "Riposte";
        return c;
    }

    private static void fullBootstrap() throws Exception {
        Wire wire = new Wire();
        Log log = new Log();
        Wifi.Bootstrap b = new Wifi.Bootstrap(wire, log, config());
        Check.that(b.state() == Wifi.Bootstrap.State.IDLE, "starts idle");

        b.start();
        Wifi.Message m = wire.next();
        Check.eq(Wifi.VERSION_REQUEST, m.id, "opens with the version request");
        Check.that(b.state() == Wifi.Bootstrap.State.VERSION, "VERSION");
        wire.drained();

        fromPhone(b, Wifi.VERSION_RESPONSE, Check.hex("08 05 10 01 1a 01 53 20 00"));
        m = wire.next();
        Check.eq(Wifi.START_REQUEST, m.id, "then the start request");
        Check.bytes(Wifi.startRequest(IP, Wifi.DEFAULT_PORT), m.payload, "start request carries our endpoint");
        Check.that(b.state() == Wifi.Bootstrap.State.INFO, "INFO");

        fromPhone(b, Wifi.INFO_REQUEST, new byte[0]);
        m = wire.next();
        Check.eq(Wifi.INFO_RESPONSE, m.id, "credentials on request");
        Check.bytes(Wifi.infoResponse(config().ap), m.payload, "credentials are the configured AP");
        Check.that(b.state() == Wifi.Bootstrap.State.START, "START");

        fromPhone(b, Wifi.START_RESPONSE, Check.hex("18 00"));
        Check.that(b.startAcknowledged(), "start acknowledged");
        Check.that(b.state() == Wifi.Bootstrap.State.START, "still START until the phone joins");

        fromPhone(b, Wifi.CONNECT_STATUS, Check.hex("08 00"));
        Check.that(b.state() == Wifi.Bootstrap.State.CONNECTED, "CONNECTED");
        wire.drained();
        Check.eq("[VERSION, INFO, START, CONNECTED]", log.states.toString(), "state sequence");
        Check.eq(6, log.lines.size(), "one log line per step");
    }

    private static void phoneSkipsVersion() throws Exception {
        Wire wire = new Wire();
        Log log = new Log();
        Wifi.Bootstrap b = new Wifi.Bootstrap(wire, log, config());
        b.start();
        wire.next();

        // Dongle-style phone: never answers the version request, asks for credentials.
        fromPhone(b, Wifi.INFO_REQUEST, new byte[0]);
        Check.eq(Wifi.START_REQUEST, wire.next().id, "start request goes out anyway");
        Check.eq(Wifi.INFO_RESPONSE, wire.next().id, "credentials follow");
        Check.that(b.state() == Wifi.Bootstrap.State.START, "START without a version response");
        wire.drained();

        fromPhone(b, Wifi.CONNECT_STATUS, Check.hex("08 00"));
        Check.that(b.state() == Wifi.Bootstrap.State.CONNECTED, "CONNECTED without a start response");
    }

    private static void phoneCannotJoin() throws Exception {
        Wire wire = new Wire();
        Log log = new Log();
        Wifi.Bootstrap b = new Wifi.Bootstrap(wire, log, config());
        b.start();
        fromPhone(b, Wifi.VERSION_RESPONSE, Check.hex("08 05 10 01"));
        fromPhone(b, Wifi.INFO_REQUEST, new byte[0]);
        fromPhone(b, Wifi.CONNECT_STATUS, Check.hex("08 fd ff ff ff ff ff ff ff ff 01"));
        Check.that(b.state() == Wifi.Bootstrap.State.FAILED, "FAILED on a negative status");

        // Nothing after a failure is acted on.
        int before = log.lines.size();
        fromPhone(b, Wifi.CONNECT_STATUS, Check.hex("08 00"));
        Check.eq(before, log.lines.size(), "silent after FAILED");
        Check.that(b.state() == Wifi.Bootstrap.State.FAILED, "stays FAILED");

        // A refused version fails too.
        Wire w2 = new Wire();
        Wifi.Bootstrap b2 = new Wifi.Bootstrap(w2, new Log(), config());
        b2.start();
        fromPhone(b2, Wifi.VERSION_RESPONSE, Check.hex("20 01"));
        Check.that(b2.state() == Wifi.Bootstrap.State.FAILED, "FAILED on version status 1");
    }

    private static void pingAnyTime() throws Exception {
        Wire wire = new Wire();
        Log log = new Log();
        Wifi.Bootstrap b = new Wifi.Bootstrap(wire, log, config());
        b.start();
        wire.next();

        fromPhone(b, Wifi.PING_REQUEST, Wifi.ping(99));
        Wifi.Message m = wire.next();
        Check.eq(Wifi.PING_RESPONSE, m.id, "ping answered");
        Check.eq(99, Wifi.pingTimestamp(m.payload), "same timestamp");
        Check.that(b.state() == Wifi.Bootstrap.State.VERSION, "ping does not move the state");

        // An out-of-turn message is logged and ignored.
        int before = log.lines.size();
        fromPhone(b, Wifi.START_RESPONSE, Check.hex("18 00"));
        Check.eq(before + 1, log.lines.size(), "out-of-turn logged");
        Check.that(b.state() == Wifi.Bootstrap.State.VERSION, "and ignored");
        fromPhone(b, 42, new byte[0]);
        Check.eq(before + 2, log.lines.size(), "unknown id logged");
        wire.drained();
    }

    /** Feeds one framed message in two chunks, so reassembly is exercised on every step. */
    private static void fromPhone(Wifi.Bootstrap b, int id, byte[] body) throws Exception {
        byte[] f = Wifi.frame(id, body);
        int cut = f.length > 1 ? f.length / 2 : 0;
        b.onBytes(f, 0, cut);
        b.onBytes(f, cut, f.length - cut);
    }
}
