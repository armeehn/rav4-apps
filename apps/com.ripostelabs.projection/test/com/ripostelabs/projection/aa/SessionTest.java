package com.ripostelabs.projection.aa;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

/**
 * A scripted phone drives the whole state machine: version, TLS (a real server engine that
 * demands the client certificate), discovery, channel opens, video setup, a fragmented
 * encrypted frame, a touch out, and shutdown. Everything the real phone does, minus the phone.
 */
public final class SessionTest {

    /** Collects what the head unit sends and hands it back frame by frame. */
    private static final class Wire implements Session.Link {
        final Frame.Reader reader = new Frame.Reader();

        @Override
        public void write(byte[] data) {
            reader.push(data, 0, data.length);
        }

        Frame next() {
            Frame f = reader.next();
            Check.that(f != null, "head unit sent a frame");
            return f;
        }

        void drained() {
            Check.that(reader.next() == null, "no stray frames");
        }
    }

    private static final class Screen implements Session.Sink {
        final List<String> log = new ArrayList<>();
        Messages.VideoConfig video;
        final ByteArrayOutputStream videoBytes = new ByteArrayOutputStream();
        long lastTimestamp;
        int audioStarts;
        int audioBytes;
        String shutdown;

        @Override
        public void log(String line) {
            log.add(line);
        }

        @Override
        public void onState(Session.State state) {
        }

        @Override
        public void onVideoStart(Messages.VideoConfig config) {
            video = config;
        }

        @Override
        public void onVideo(long timestampUs, byte[] data, int off, int len) {
            lastTimestamp = timestampUs;
            videoBytes.write(data, off, len);
        }

        @Override
        public void onVideoStop() {
        }

        @Override
        public void onAudioStart(int channel, Messages.AudioConfig config) {
            audioStarts++;
        }

        @Override
        public void onAudio(int channel, byte[] data, int off, int len) {
            audioBytes += len;
        }

        @Override
        public void onAudioStop(int channel) {
        }

        @Override
        public int onAudioFocus(int focusType) {
            return focusType == Messages.FOCUS_RELEASE ? Messages.FOCUS_STATE_LOSS : Messages.FOCUS_STATE_GAIN;
        }

        @Override
        public void onShutdown(String why) {
            shutdown = why;
        }
    }

    /** The phone's TLS end. */
    private static SSLEngine phone;
    private static ByteBuffer phoneNet;
    private static ByteBuffer phoneApp;

    public static void main(String[] args) throws Exception {
        Wire wire = new Wire();
        Screen screen = new Screen();
        Session.Config cfg = new Session.Config();
        cfg.videoConfigs.add(new Messages.VideoConfig(Messages.RES_1920x1080, Messages.FPS_30, 0, 360, 160));
        cfg.videoConfigs.add(new Messages.VideoConfig(Messages.RES_1280x720, Messages.FPS_30, 0, 0, 160));
        cfg.touchWidth = 1920;
        cfg.touchHeight = 1080;
        Session s = new Session(wire, screen, cfg);

        phone = Tls.newEngine(Tls.Role.SERVER);
        phone.setNeedClientAuth(true);
        phone.beginHandshake();
        phoneNet = ByteBuffer.allocate(phone.getSession().getPacketBufferSize() * 4);
        phoneApp = ByteBuffer.allocate(phone.getSession().getApplicationBufferSize() * 4);

        versionExchange(s, wire);
        tlsHandshake(s, wire);
        discovery(s, wire);
        channelOpen(s, wire);
        videoPath(s, wire, screen);
        fragmentedMedia(s, wire, screen);
        audioPath(s, wire, screen);
        sensorsAndInput(s, wire);
        touchOut(s, wire);
        controlOdds(s, wire, screen);
        shutdown(s, wire, screen);

        System.out.println(Check.count + " assertions passed");
    }

    private static void versionExchange(Session s, Wire wire) throws Exception {
        s.start();
        Check.that(s.state() == Session.State.VERSION, "VERSION after start");
        Frame f = wire.next();
        Check.bytes(Check.hex("00 03 00 06 00 01 00 01 00 01"), f.encode(), "version request on the wire");
        wire.drained();

        s.onBytes(Check.hex("00 03 00 08 00 02 00 01 00 01 00 00"), 0, 12);
        Check.that(s.state() == Session.State.HANDSHAKE, "HANDSHAKE after a matching version");
        Frame hello = wire.next();
        Check.eq(Ids.SSL_HANDSHAKE, Frame.messageId(hello.payload), "client hello in SSL_HANDSHAKE");
        Check.that(!hello.encrypted(), "handshake frames are plain");
        Check.eq(0x16, Frame.body(hello.payload)[0] & 0xFF, "TLS handshake record");
        feedPhone(Frame.body(hello.payload));
    }

    private static void tlsHandshake(Session s, Wire wire) throws Exception {
        int rounds = 0;
        while (s.state() == Session.State.HANDSHAKE && rounds++ < 10) {
            byte[] flight = phoneFlight();
            byte[] msg = Frame.withId(Ids.SSL_HANDSHAKE, flight);
            s.onBytes(new Frame(Ids.CH_CONTROL, Frame.FLAG_BULK, 0, msg).encode(), 0, msg.length + 4);

            Frame f;
            while ((f = wire.reader.next()) != null) {
                int id = Frame.messageId(f.payload);
                if (id == Ids.SSL_HANDSHAKE) {
                    feedPhone(Frame.body(f.payload));
                    continue;
                }
                Check.eq(Ids.AUTH_COMPLETE, id, "only AUTH_COMPLETE follows the handshake");
                Check.bytes(Check.hex("00 03 00 04 00 04 08 00"), f.encode(), "auth complete literal");
            }
        }
        Check.that(s.state() == Session.State.DISCOVERY, "DISCOVERY after AUTH_COMPLETE");
        Check.that(phone.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, "phone side done");
        Check.eq(1, phone.getSession().getPeerCertificates().length, "phone received the head-unit cert");
    }

    private static void discovery(Session s, Wire wire) throws Exception {
        byte[] req = new Proto.Writer().string(4, "Pixel 7").string(5, "Google").toBytes();
        fromPhone(s, Ids.CH_CONTROL, Ids.SERVICE_DISCOVERY_REQUEST, req);
        Check.that(s.state() == Session.State.RUNNING, "RUNNING after discovery");

        byte[] sdr = decryptedMessage(wire);
        Check.eq(Ids.SERVICE_DISCOVERY_RESPONSE, Frame.messageId(sdr), "SDR id");
        int channels = 0;
        Proto.Reader r = new Proto.Reader(Frame.body(sdr));
        while (r.next()) {
            if (r.field() == 1) {
                channels++;
            }
            r.skip();
        }
        Check.eq(6, channels, "sensor, video, input, media, speech, system");
        wire.drained();
    }

    private static void channelOpen(Session s, Wire wire) throws Exception {
        byte[] req = new Proto.Writer().varint(1, 0).varint(2, Ids.CH_VIDEO).toBytes();
        fromPhone(s, Ids.CH_VIDEO, Ids.CHANNEL_OPEN_REQUEST, req);
        Frame f = wire.next();
        Check.eq(Ids.CH_VIDEO, f.channel, "answered on the video channel");
        Check.eq(0x0F, f.flags, "BULK | CONTROL | ENCRYPTED, headunit's 0x0f");
        Check.bytes(Check.hex("00 08 08 00"), phoneDecrypt(f.payload), "channel open response ok");
        wire.drained();
    }

    private static void videoPath(Session s, Wire wire, Screen screen) throws Exception {
        fromPhone(s, Ids.CH_VIDEO, Ids.AV_SETUP_REQUEST, new Proto.Writer().varint(1, 0).toBytes());
        Frame f = wire.next();
        Check.eq(0x0B, f.flags, "channel-specific message: no CONTROL flag");
        Check.bytes(Check.hex("80 03 08 02 10 01 18 00"), phoneDecrypt(f.payload), "setup response literal");
        wire.drained();

        fromPhone(s, Ids.CH_VIDEO, Ids.VIDEO_FOCUS_REQUEST, new Proto.Writer().varint(2, 1).toBytes());
        Check.bytes(Check.hex("80 08 08 01 10 00"), decryptedMessage(wire), "focus indication");

        fromPhone(s, Ids.CH_VIDEO, Ids.AV_START_INDICATION, new Proto.Writer().varint(1, 5).varint(2, 0).toBytes());
        Check.that(screen.video != null, "video start reached the screen");
        Check.eq(1920, screen.video.width(), "config 0 is the 1080p one");
        wire.drained();

        byte[] nal = Check.hex("00 00 00 01 67 42 00 1e");
        byte[] body = new byte[8 + nal.length];
        body[7] = 42;
        System.arraycopy(nal, 0, body, 8, nal.length);
        fromPhone(s, Ids.CH_VIDEO, Ids.AV_MEDIA_WITH_TIMESTAMP, body);
        Check.eq(42, screen.lastTimestamp, "timestamp parsed");
        Check.bytes(nal, screen.videoBytes.toByteArray(), "NAL delivered without the timestamp");
        Check.bytes(Check.hex("80 04 08 05 10 01"), decryptedMessage(wire), "ack carries the session id");
    }

    private static void fragmentedMedia(Session s, Wire wire, Screen screen) throws Exception {
        screen.videoBytes.reset();
        byte[] body = new byte[8 + Frame.MAX_PAYLOAD * 2 + 100];
        for (int i = 8; i < body.length; i++) {
            body[i] = (byte) (i * 3);
        }
        byte[] message = Frame.withId(Ids.AV_MEDIA_WITH_TIMESTAMP, body);
        List<Frame.Chunk> chunks = Frame.split(message);
        Check.eq(3, chunks.size(), "phone would send three frames");
        for (Frame.Chunk c : chunks) {
            byte[] cipher = phoneEncrypt(c.data);
            byte[] header = Frame.header(Ids.CH_VIDEO, c.frameType | Frame.FLAG_ENCRYPTED, cipher.length, message.length);
            s.onBytes(header, 0, header.length);
            // Deliberately odd slicing: the bulk reads never line up with frames.
            s.onBytes(cipher, 0, 7);
            s.onBytes(cipher, 7, cipher.length - 7);
        }
        byte[] got = screen.videoBytes.toByteArray();
        Check.eq(body.length - 8, got.length, "reassembled length");
        Check.eq(body[8], got[0], "first byte");
        Check.eq(body[body.length - 1], got[got.length - 1], "last byte");
        Check.bytes(Check.hex("80 04 08 05 10 01"), decryptedMessage(wire), "one ack for the whole message");
    }

    private static void audioPath(Session s, Wire wire, Screen screen) throws Exception {
        fromPhone(s, Ids.CH_MEDIA_AUDIO, Ids.AV_SETUP_REQUEST, new Proto.Writer().varint(1, 0).toBytes());
        decryptedMessage(wire);
        fromPhone(s, Ids.CH_MEDIA_AUDIO, Ids.AV_START_INDICATION, new Proto.Writer().varint(1, 9).toBytes());
        Check.eq(1, screen.audioStarts, "audio start");
        byte[] pcm = new byte[8 + 960];
        fromPhone(s, Ids.CH_MEDIA_AUDIO, Ids.AV_MEDIA_WITH_TIMESTAMP, pcm);
        Check.eq(960, screen.audioBytes, "PCM delivered");
        Check.bytes(Check.hex("80 04 08 09 10 01"), decryptedMessage(wire), "audio ack with its own session");
    }

    private static void sensorsAndInput(Session s, Wire wire) throws Exception {
        fromPhone(s, Ids.CH_SENSOR, Ids.SENSOR_START_REQUEST,
                new Proto.Writer().varint(1, Messages.SENSOR_DRIVING_STATUS).toBytes());
        Check.bytes(Check.hex("80 02 08 00"), decryptedMessage(wire), "sensor start response");
        Check.bytes(Check.hex("80 03 6a 02 08 00"), decryptedMessage(wire), "driving status follows");
        wire.drained();

        fromPhone(s, Ids.CH_SENSOR, Ids.SENSOR_START_REQUEST,
                new Proto.Writer().varint(1, Messages.SENSOR_NIGHT_DATA).toBytes());
        decryptedMessage(wire);
        Check.bytes(Check.hex("80 03 52 02 08 00"), decryptedMessage(wire), "night mode follows");

        fromPhone(s, Ids.CH_INPUT, Ids.BINDING_REQUEST, new Proto.Writer().varint(1, 0x54).toBytes());
        Check.bytes(Check.hex("80 03 08 00"), decryptedMessage(wire), "binding response");
    }

    private static void touchOut(Session s, Wire wire) throws Exception {
        s.sendTouch(Messages.TOUCH_PRESS, 0, new int[] {640}, new int[] {360}, new int[] {0});
        Frame f = wire.next();
        Check.eq(Ids.CH_INPUT, f.channel, "touch goes on the input channel");
        Check.eq(0x0B, f.flags, "encrypted, not control");
        byte[] m = phoneDecrypt(f.payload);
        Check.eq(Ids.INPUT_EVENT_INDICATION, Frame.messageId(m), "input event id");
        wire.drained();
    }

    private static void controlOdds(Session s, Wire wire, Screen screen) throws Exception {
        fromPhone(s, Ids.CH_CONTROL, Ids.PING_REQUEST, Messages.ping(777));
        Check.bytes(Check.hex("00 0c 08 89 06"), decryptedMessage(wire), "ping echoed");

        fromPhone(s, Ids.CH_CONTROL, Ids.NAVIGATION_FOCUS_REQUEST, new Proto.Writer().varint(1, 1).toBytes());
        Check.bytes(Check.hex("00 0e 08 02"), decryptedMessage(wire), "nav focus 2");

        fromPhone(s, Ids.CH_CONTROL, Ids.AUDIO_FOCUS_REQUEST, new Proto.Writer().varint(1, Messages.FOCUS_GAIN).toBytes());
        Check.bytes(Check.hex("00 13 08 01"), decryptedMessage(wire), "audio focus gain");
        fromPhone(s, Ids.CH_CONTROL, Ids.AUDIO_FOCUS_REQUEST, new Proto.Writer().varint(1, Messages.FOCUS_RELEASE).toBytes());
        Check.bytes(Check.hex("00 13 08 03"), decryptedMessage(wire), "audio focus loss on release");

        s.ping();
        Frame f = wire.next();
        Check.that(!f.encrypted(), "our ping request is plain, as aasdk sends it");
        Check.eq(Ids.PING_REQUEST, Frame.messageId(f.payload), "ping request id");
        wire.drained();
        Check.that(screen.shutdown == null, "still up");
    }

    private static void shutdown(Session s, Wire wire, Screen screen) throws Exception {
        fromPhone(s, Ids.CH_CONTROL, Ids.SHUTDOWN_REQUEST, new Proto.Writer().varint(1, 1).toBytes());
        Check.bytes(Check.hex("00 10"), decryptedMessage(wire), "empty shutdown response");
        Check.that(s.state() == Session.State.CLOSED, "CLOSED");
        Check.that(screen.shutdown != null, "screen told");
        s.ping();
        wire.drained();
        Check.that(screen.log.size() > 10, "one line per step was logged");
    }

    // ---- the phone's side of the wire -----------------------------------------------------------

    private static void fromPhone(Session s, int channel, int id, byte[] body) throws Exception {
        byte[] message = Frame.withId(id, body);
        int flags = Frame.FLAG_BULK | Frame.FLAG_ENCRYPTED;
        if (channel != Ids.CH_CONTROL && id < Ids.CHANNEL_SPECIFIC_BASE) {
            flags |= Frame.FLAG_CONTROL;
        }
        byte[] cipher = phoneEncrypt(message);
        byte[] wire = new Frame(channel, flags, 0, cipher).encode();
        s.onBytes(wire, 0, wire.length);
    }

    private static byte[] decryptedMessage(Wire wire) throws Exception {
        Frame f = wire.next();
        Check.that(f.encrypted(), "post-handshake traffic is encrypted");
        return phoneDecrypt(f.payload);
    }

    private static byte[] phoneEncrypt(byte[] plain) throws Exception {
        phoneNet.clear();
        SSLEngineResult r = phone.wrap(ByteBuffer.wrap(plain), phoneNet);
        Check.that(r.getStatus() == SSLEngineResult.Status.OK, "phone wrap " + r.getStatus());
        phoneNet.flip();
        byte[] out = new byte[phoneNet.remaining()];
        phoneNet.get(out);
        return out;
    }

    private static byte[] phoneDecrypt(byte[] cipher) throws Exception {
        phoneApp.clear();
        ByteBuffer in = ByteBuffer.wrap(cipher);
        while (in.hasRemaining()) {
            SSLEngineResult r = phone.unwrap(in, phoneApp);
            Check.that(r.getStatus() == SSLEngineResult.Status.OK, "phone unwrap " + r.getStatus());
        }
        phoneApp.flip();
        byte[] out = new byte[phoneApp.remaining()];
        phoneApp.get(out);
        return out;
    }

    private static void feedPhone(byte[] handshakeBytes) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(handshakeBytes);
        while (in.hasRemaining()) {
            phoneApp.clear();
            SSLEngineResult r = phone.unwrap(in, phoneApp);
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                break;
            }
            runTasks();
        }
    }

    private static byte[] phoneFlight() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            runTasks();
            if (phone.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                break;
            }
            phoneNet.clear();
            phone.wrap(ByteBuffer.allocate(0), phoneNet);
            phoneNet.flip();
            out.write(phoneNet.array(), phoneNet.position(), phoneNet.remaining());
        }
        return out.toByteArray();
    }

    private static void runTasks() {
        Runnable t;
        while ((t = phone.getDelegatedTask()) != null) {
            t.run();
        }
    }
}
