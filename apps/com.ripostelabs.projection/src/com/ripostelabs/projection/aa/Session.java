package com.ripostelabs.projection.aa;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One Android Auto session, from the first byte after the accessory switch to shutdown.
 * Transport-agnostic: bytes come in through {@link #onBytes}, go out through {@link Link}, and
 * everything the screen or the speakers need arrives on {@link Sink}. No threads, no Android;
 * the caller serialises calls.
 *
 * <pre>
 *   head unit (this)                         phone
 *   VERSION_REQUEST 1.1        ---------->
 *                              <----------  VERSION_RESPONSE (status 0)
 *   SSL_HANDSHAKE ClientHello  ---------->                       \
 *                              <----------  SSL_HANDSHAKE ...     | TLS 1.2, we are the client
 *   SSL_HANDSHAKE Finished     ---------->                       /
 *   AUTH_COMPLETE (ok)         ---------->
 *                              <----------  SERVICE_DISCOVERY_REQUEST     (encrypted from here)
 *   SERVICE_DISCOVERY_RESPONSE ---------->   channels: video, input, sensor, 3 x audio
 *                              <----------  CHANNEL_OPEN_REQUEST on each channel
 *   CHANNEL_OPEN_RESPONSE      ---------->   (CONTROL flag)
 *                              <----------  SETUP_REQUEST / START_INDICATION / media ...
 * </pre>
 *
 * <p>Every transition logs one line through {@link Sink#log}, so the first real phone session
 * says exactly where it stops.
 */
public final class Session {

    /** Where outbound frames go. */
    public interface Link {
        void write(byte[] data) throws IOException;
    }

    /** What the head unit app has to do with what the phone sends. */
    public interface Sink {
        void log(String line);

        void onState(State state);

        void onVideoStart(Messages.VideoConfig config);

        void onVideo(long timestampUs, byte[] data, int off, int len);

        void onVideoStop();

        void onAudioStart(int channel, Messages.AudioConfig config);

        void onAudio(int channel, byte[] data, int off, int len);

        void onAudioStop(int channel);

        /** Answer an AudioFocusRequest with an AudioFocusState (see {@link Messages}). */
        int onAudioFocus(int focusType);

        void onShutdown(String why);
    }

    public enum State {
        IDLE,
        VERSION,
        HANDSHAKE,
        DISCOVERY,
        RUNNING,
        CLOSED
    }

    /** What this head unit announces about itself. */
    public static final class Config {
        public String headUnitName = "Projection";
        public String carModel = "Car";
        public String carYear = "2020";
        public String carSerial = "0";
        public boolean leftHandDrive = true;
        public String manufacturer = "Riposte Laboratories";
        public String model = "Projection";
        public String swBuild = "1";
        public String swVersion = "1.0";
        public final List<Messages.VideoConfig> videoConfigs = new ArrayList<>();
        public int touchWidth;
        public int touchHeight;
        public Messages.AudioConfig mediaAudio = new Messages.AudioConfig(48000, 16, 2);
        public Messages.AudioConfig speechAudio = new Messages.AudioConfig(16000, 16, 1);
        public Messages.AudioConfig systemAudio = new Messages.AudioConfig(16000, 16, 1);
    }

    /** Protocol version both references request. */
    public static final int VERSION_MAJOR = 1;
    public static final int VERSION_MINOR = 1;
    /** Frames in flight before the phone waits for an ack; both references use 1. */
    private static final int MAX_UNACKED = 1;
    private static final int MICROS_PER_MILLI = 1000;
    private static final int MIN_MESSAGE = 2;

    private final Link link;
    private final Sink sink;
    private final Config config;
    private final Frame.Reader reader = new Frame.Reader();
    private final Frame.Assembler assembler = new Frame.Assembler();
    private final Object writeLock = new Object();
    private final int[] avSession = new int[256];

    private Tls tls;
    private State state = State.IDLE;
    private Messages.VideoConfig chosenVideo;

    public Session(Link link, Sink sink, Config config) {
        this.link = link;
        this.sink = sink;
        this.config = config;
        if (config.videoConfigs.isEmpty()) {
            throw new IllegalArgumentException("declare at least one video config");
        }
    }

    public State state() {
        return state;
    }

    /** Kicks the session off with the version request. */
    public void start() throws IOException {
        setState(State.VERSION);
        sendPlain(Ids.CH_CONTROL, Ids.VERSION_REQUEST, Messages.versionRequest(VERSION_MAJOR, VERSION_MINOR));
    }

    /** Bytes from the phone, in any slicing. */
    public void onBytes(byte[] data, int off, int len) throws IOException {
        reader.push(data, off, len);
        Frame f;
        while ((f = reader.next()) != null) {
            handleFrame(f);
        }
    }

    // ---- outbound API for the app --------------------------------------------------------------

    public void ping() throws IOException {
        if (state != State.RUNNING) {
            return;
        }
        // aasdk sends its ping request PLAIN even after the handshake; mirrored deliberately.
        sendPlain(Ids.CH_CONTROL, Ids.PING_REQUEST, Messages.ping(System.currentTimeMillis()));
    }

    /** A touch in the coordinate space declared by {@code touchWidth} x {@code touchHeight}. */
    public void sendTouch(int action, int actionIndex, int[] xs, int[] ys, int[] pointerIds) throws IOException {
        if (state != State.RUNNING) {
            return;
        }
        long micros = System.currentTimeMillis() * MICROS_PER_MILLI;
        sendEncrypted(Ids.CH_INPUT, Ids.INPUT_EVENT_INDICATION,
                Messages.touchEvent(micros, action, actionIndex, xs, ys, pointerIds));
    }

    /** Tells the phone we are going away; it answers with SHUTDOWN_RESPONSE. */
    public void requestShutdown() throws IOException {
        if (state != State.RUNNING) {
            return;
        }
        sendEncrypted(Ids.CH_CONTROL, Ids.SHUTDOWN_REQUEST, new Proto.Writer().varint(1, 1).toBytes());
    }

    // ---- inbound -------------------------------------------------------------------------------

    private void handleFrame(Frame f) throws IOException {
        byte[] plain = f.payload;
        if (f.encrypted()) {
            if (tls == null) {
                log("encrypted frame before the handshake, dropped");
                return;
            }
            plain = tls.decrypt(f.payload);
        }

        byte[] message = assembler.feed(f.channel, f.flags, plain);
        if (message == null) {
            return;
        }
        if (message.length < MIN_MESSAGE) {
            log("channel " + f.channel + ": message shorter than an id, dropped");
            return;
        }
        dispatch(f.channel, Frame.messageId(message), Frame.body(message));
    }

    private void dispatch(int channel, int id, byte[] body) throws IOException {
        // Channel open requests arrive on the channel being opened, not on channel 0.
        if (id == Ids.CHANNEL_OPEN_REQUEST && channel != Ids.CH_CONTROL) {
            Messages.ChannelOpenRequest req = Messages.ChannelOpenRequest.parse(body);
            log("channel " + req.channelId + " open request, priority " + req.priority);
            sendEncrypted(channel, Ids.CHANNEL_OPEN_RESPONSE, Messages.channelOpenResponse(Messages.STATUS_OK));
            return;
        }

        switch (channel) {
            case Ids.CH_CONTROL:
                control(id, body);
                return;
            case Ids.CH_VIDEO:
            case Ids.CH_MEDIA_AUDIO:
            case Ids.CH_SPEECH_AUDIO:
            case Ids.CH_SYSTEM_AUDIO:
                av(channel, id, body);
                return;
            case Ids.CH_INPUT:
                input(id, body);
                return;
            case Ids.CH_SENSOR:
                sensor(id, body);
                return;
            default:
                log("channel " + channel + ": unexpected message " + hex(id));
        }
    }

    private void control(int id, byte[] body) throws IOException {
        switch (id) {
            case Ids.VERSION_RESPONSE: {
                Messages.VersionResponse v = Messages.VersionResponse.parse(body);
                log("version response " + v.major + "." + v.minor + " status " + hex(v.status));
                if (v.status != Messages.VERSION_MATCH) {
                    fail("version mismatch");
                    return;
                }
                startHandshake();
                return;
            }
            case Ids.SSL_HANDSHAKE:
                continueHandshake(body);
                return;
            case Ids.SERVICE_DISCOVERY_REQUEST: {
                Messages.ServiceDiscoveryRequest req = Messages.ServiceDiscoveryRequest.parse(body);
                log("service discovery request from " + req.deviceBrand + " " + req.deviceName);
                sendEncrypted(Ids.CH_CONTROL, Ids.SERVICE_DISCOVERY_RESPONSE, discoveryResponse());
                setState(State.RUNNING);
                return;
            }
            case Ids.PING_REQUEST:
                sendEncrypted(Ids.CH_CONTROL, Ids.PING_RESPONSE, Messages.ping(Messages.pingTimestamp(body)));
                return;
            case Ids.PING_RESPONSE:
                log("ping " + (System.currentTimeMillis() - Messages.pingTimestamp(body)) + " ms");
                return;
            case Ids.NAVIGATION_FOCUS_REQUEST:
                log("navigation focus request type " + Messages.navigationFocusType(body));
                sendEncrypted(Ids.CH_CONTROL, Ids.NAVIGATION_FOCUS_RESPONSE,
                        Messages.navigationFocusResponse(Messages.NAV_FOCUS_ANSWER));
                return;
            case Ids.AUDIO_FOCUS_REQUEST: {
                int type = Messages.audioFocusType(body);
                int answer = sink.onAudioFocus(type);
                log("audio focus request " + type + " -> state " + answer);
                sendEncrypted(Ids.CH_CONTROL, Ids.AUDIO_FOCUS_RESPONSE, Messages.audioFocusResponse(answer));
                return;
            }
            case Ids.SHUTDOWN_REQUEST:
                log("shutdown request, reason " + Messages.shutdownReason(body));
                sendEncrypted(Ids.CH_CONTROL, Ids.SHUTDOWN_RESPONSE, Messages.shutdownResponse());
                close("phone asked");
                return;
            case Ids.SHUTDOWN_RESPONSE:
                close("shutdown acknowledged");
                return;
            case Ids.VOICE_SESSION_REQUEST:
                log("voice session request (ignored: no microphone channel in stage 1)");
                return;
            default:
                log("control: unexpected message " + hex(id));
        }
    }

    private void startHandshake() throws IOException {
        setState(State.HANDSHAKE);
        try {
            tls = Tls.client();
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS setup: " + e, e);
        }
        byte[] hello = tls.handshake(null);
        log("TLS client hello, " + hello.length + " bytes");
        sendPlain(Ids.CH_CONTROL, Ids.SSL_HANDSHAKE, hello);
    }

    private void continueHandshake(byte[] fromPhone) throws IOException {
        if (tls == null) {
            log("handshake data before the version exchange, dropped");
            return;
        }
        byte[] out = tls.handshake(fromPhone);
        log("TLS in " + fromPhone.length + " bytes, out " + out.length);
        if (out.length > 0) {
            sendPlain(Ids.CH_CONTROL, Ids.SSL_HANDSHAKE, out);
        }
        if (!tls.handshakeDone()) {
            return;
        }
        log("TLS established");
        sendPlain(Ids.CH_CONTROL, Ids.AUTH_COMPLETE, Messages.authComplete(Messages.STATUS_OK));
        setState(State.DISCOVERY);
    }

    private byte[] discoveryResponse() {
        Messages.ServiceDiscoveryResponse sdr = new Messages.ServiceDiscoveryResponse();
        sdr.channels.add(Messages.ChannelDescriptor.sensor(Ids.CH_SENSOR,
                Messages.SENSOR_DRIVING_STATUS, Messages.SENSOR_NIGHT_DATA));
        sdr.channels.add(Messages.ChannelDescriptor.video(Ids.CH_VIDEO, config.videoConfigs));
        sdr.channels.add(Messages.ChannelDescriptor.input(Ids.CH_INPUT, config.touchWidth, config.touchHeight));
        sdr.channels.add(Messages.ChannelDescriptor.audio(Ids.CH_MEDIA_AUDIO, Messages.AUDIO_MEDIA, config.mediaAudio));
        sdr.channels.add(Messages.ChannelDescriptor.audio(Ids.CH_SPEECH_AUDIO, Messages.AUDIO_SPEECH, config.speechAudio));
        sdr.channels.add(Messages.ChannelDescriptor.audio(Ids.CH_SYSTEM_AUDIO, Messages.AUDIO_SYSTEM, config.systemAudio));
        sdr.headUnitName = config.headUnitName;
        sdr.carModel = config.carModel;
        sdr.carYear = config.carYear;
        sdr.carSerial = config.carSerial;
        sdr.leftHandDrive = config.leftHandDrive;
        sdr.manufacturer = config.manufacturer;
        sdr.model = config.model;
        sdr.swBuild = config.swBuild;
        sdr.swVersion = config.swVersion;
        return sdr.encode();
    }

    private void av(int channel, int id, byte[] body) throws IOException {
        switch (id) {
            case Ids.AV_SETUP_REQUEST: {
                int index = Messages.setupConfigIndex(body);
                log("channel " + channel + " setup request, config " + index);
                if (channel == Ids.CH_VIDEO) {
                    int safe = index < config.videoConfigs.size() ? index : 0;
                    chosenVideo = config.videoConfigs.get(safe);
                }
                sendEncrypted(channel, Ids.AV_SETUP_RESPONSE, Messages.setupResponse(Messages.SETUP_OK, MAX_UNACKED, 0));
                return;
            }
            case Ids.AV_START_INDICATION: {
                Messages.StartIndication s = Messages.StartIndication.parse(body);
                avSession[channel] = s.session;
                log("channel " + channel + " start, session " + s.session + " config " + s.config);
                if (channel == Ids.CH_VIDEO) {
                    sink.onVideoStart(chosenVideo);
                } else {
                    sink.onAudioStart(channel, audioConfig(channel));
                }
                return;
            }
            case Ids.AV_STOP_INDICATION:
                log("channel " + channel + " stop");
                if (channel == Ids.CH_VIDEO) {
                    sink.onVideoStop();
                } else {
                    sink.onAudioStop(channel);
                }
                return;
            case Ids.VIDEO_FOCUS_REQUEST:
                log("video focus request mode " + Messages.videoFocusMode(body));
                sendEncrypted(channel, Ids.VIDEO_FOCUS_INDICATION,
                        Messages.videoFocusIndication(Messages.VIDEO_FOCUSED, false));
                return;
            case Ids.AV_MEDIA_WITH_TIMESTAMP:
                media(channel, Messages.mediaTimestamp(body), body, Messages.TIMESTAMP_LENGTH);
                return;
            case Ids.AV_MEDIA:
                media(channel, 0, body, 0);
                return;
            default:
                log("channel " + channel + ": unexpected message " + hex(id));
        }
    }

    private void media(int channel, long timestamp, byte[] body, int off) throws IOException {
        if (channel == Ids.CH_VIDEO) {
            sink.onVideo(timestamp, body, off, body.length - off);
        } else {
            sink.onAudio(channel, body, off, body.length - off);
        }
        sendEncrypted(channel, Ids.AV_MEDIA_ACK, Messages.mediaAck(avSession[channel], 1));
    }

    private Messages.AudioConfig audioConfig(int channel) {
        switch (channel) {
            case Ids.CH_SPEECH_AUDIO:
                return config.speechAudio;
            case Ids.CH_SYSTEM_AUDIO:
                return config.systemAudio;
            default:
                return config.mediaAudio;
        }
    }

    private void input(int id, byte[] body) throws IOException {
        if (id != Ids.BINDING_REQUEST) {
            log("input: unexpected message " + hex(id));
            return;
        }
        log("input binding request for " + Messages.bindingScanCodes(body).size() + " scan codes");
        sendEncrypted(Ids.CH_INPUT, Ids.BINDING_RESPONSE, Messages.bindingResponse(Messages.STATUS_OK));
    }

    private void sensor(int id, byte[] body) throws IOException {
        if (id != Ids.SENSOR_START_REQUEST) {
            log("sensor: unexpected message " + hex(id));
            return;
        }
        int type = Messages.sensorStartType(body);
        log("sensor start request, type " + type);
        sendEncrypted(Ids.CH_SENSOR, Ids.SENSOR_START_RESPONSE, Messages.sensorStartResponse(Messages.STATUS_OK));

        // The phone will not draw until it has a driving status; parked and daytime is the answer.
        if (type == Messages.SENSOR_DRIVING_STATUS) {
            sendEncrypted(Ids.CH_SENSOR, Ids.SENSOR_EVENT_INDICATION,
                    Messages.drivingStatusEvent(Messages.DRIVING_UNRESTRICTED));
        } else if (type == Messages.SENSOR_NIGHT_DATA) {
            sendEncrypted(Ids.CH_SENSOR, Ids.SENSOR_EVENT_INDICATION, Messages.nightModeEvent(false));
        }
    }

    // ---- outbound ------------------------------------------------------------------------------

    private void sendPlain(int channel, int id, byte[] body) throws IOException {
        send(channel, id, body, false);
    }

    private void sendEncrypted(int channel, int id, byte[] body) throws IOException {
        send(channel, id, body, true);
    }

    private void send(int channel, int id, byte[] body, boolean encrypted) throws IOException {
        byte[] message = Frame.withId(id, body);
        int base = 0;
        if (channel != Ids.CH_CONTROL && id < Ids.CHANNEL_SPECIFIC_BASE) {
            base |= Frame.FLAG_CONTROL;
        }
        if (encrypted) {
            base |= Frame.FLAG_ENCRYPTED;
        }

        // Encryption order must equal write order: TLS records carry a sequence number.
        synchronized (writeLock) {
            for (Frame.Chunk chunk : Frame.split(message)) {
                byte[] data = encrypted ? tls.encrypt(chunk.data) : chunk.data;
                byte[] header = Frame.header(channel, base | chunk.frameType, data.length, message.length);
                byte[] wire = new byte[header.length + data.length];
                System.arraycopy(header, 0, wire, 0, header.length);
                System.arraycopy(data, 0, wire, header.length, data.length);
                link.write(wire);
            }
        }
    }

    // ---- state ---------------------------------------------------------------------------------

    private void setState(State s) {
        state = s;
        log("state " + s);
        sink.onState(s);
    }

    private void fail(String why) {
        log("failed: " + why);
        close(why);
    }

    private void close(String why) {
        if (state == State.CLOSED) {
            return;
        }
        setState(State.CLOSED);
        sink.onShutdown(why);
    }

    private void log(String line) {
        sink.log(line);
    }

    private static String hex(int id) {
        return String.format(Locale.ROOT, "0x%04x", id);
    }
}
