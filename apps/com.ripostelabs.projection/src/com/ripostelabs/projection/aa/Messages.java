package com.ripostelabs.projection.aa;

import java.util.ArrayList;
import java.util.List;

/**
 * The protocol messages stage 1 speaks, as plain encode/parse functions over {@link Proto}.
 *
 * <p>Field numbers are cited per message from the public aasdk {@code .proto} files
 * ({@code aasdk_proto/<Name>.proto}). Only the fields this receiver reads or writes are
 * modelled; unknown fields are skipped on parse.
 */
public final class Messages {

    private Messages() {
    }

    // ---- shared enums (StatusEnum.proto and friends) ----------------------------------------

    public static final int STATUS_OK = 0;
    public static final int STATUS_FAIL = 1;

    /** VersionResponseStatusEnum.proto */
    public static final int VERSION_MATCH = 0;
    public static final int VERSION_MISMATCH = 0xFFFF;

    /** AVStreamTypeEnum.proto */
    public static final int STREAM_AUDIO = 1;
    public static final int STREAM_VIDEO = 3;

    /** AudioTypeEnum.proto */
    public static final int AUDIO_SPEECH = 1;
    public static final int AUDIO_SYSTEM = 2;
    public static final int AUDIO_MEDIA = 3;

    /** VideoResolutionEnum.proto */
    public static final int RES_800x480 = 1;
    public static final int RES_1280x720 = 2;
    public static final int RES_1920x1080 = 3;

    /** VideoFPSEnum.proto */
    public static final int FPS_30 = 1;
    public static final int FPS_60 = 2;

    /** AVChannelSetupStatusEnum.proto */
    public static final int SETUP_FAIL = 1;
    public static final int SETUP_OK = 2;

    /** VideoFocusModeEnum.proto */
    public static final int VIDEO_FOCUSED = 1;
    public static final int VIDEO_UNFOCUSED = 2;

    /** TouchActionEnum.proto (aasdk lists PRESS/RELEASE/DRAG; 5 and 6 are multi-touch). */
    public static final int TOUCH_PRESS = 0;
    public static final int TOUCH_RELEASE = 1;
    public static final int TOUCH_DRAG = 2;
    public static final int TOUCH_POINTER_DOWN = 5;
    public static final int TOUCH_POINTER_UP = 6;

    /** SensorTypeEnum.proto */
    public static final int SENSOR_NIGHT_DATA = 10;
    public static final int SENSOR_DRIVING_STATUS = 13;

    /** DrivingStatusEnum.proto */
    public static final int DRIVING_UNRESTRICTED = 0;

    /** AudioFocusTypeEnum.proto (what the phone asks for). */
    public static final int FOCUS_GAIN = 1;
    public static final int FOCUS_GAIN_TRANSIENT = 2;
    public static final int FOCUS_GAIN_NAVI = 3;
    public static final int FOCUS_RELEASE = 4;

    /** AudioFocusStateEnum.proto (what the head unit answers). */
    public static final int FOCUS_STATE_GAIN = 1;
    public static final int FOCUS_STATE_GAIN_TRANSIENT = 2;
    public static final int FOCUS_STATE_LOSS = 3;
    public static final int FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY = 7;

    // ---- version handshake: raw u16 pairs, not protobuf --------------------------------------

    /** Body of VERSION_REQUEST: {@code u16 major, u16 minor} big-endian. aasdk sends 1.1. */
    public static byte[] versionRequest(int major, int minor) {
        return new byte[] {(byte) (major >>> 8), (byte) major, (byte) (minor >>> 8), (byte) minor};
    }

    public static final class VersionResponse {
        public final int major;
        public final int minor;
        public final int status;

        VersionResponse(int major, int minor, int status) {
            this.major = major;
            this.minor = minor;
            this.status = status;
        }

        /** {@code u16 major, u16 minor, u16 status}; missing halves read as 0 like aasdk. */
        public static VersionResponse parse(byte[] body) {
            return new VersionResponse(u16(body, 0), u16(body, 2), u16(body, 4));
        }
    }

    private static int u16(byte[] b, int off) {
        if (off + 2 > b.length) {
            return 0;
        }
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    // ---- control channel ---------------------------------------------------------------------

    /** AuthCompleteIndicationMessage.proto: status = 1. */
    public static byte[] authComplete(int status) {
        return new Proto.Writer().varint(1, status).toBytes();
    }

    /** ServiceDiscoveryRequestMessage.proto: device_name = 4, device_brand = 5. */
    public static final class ServiceDiscoveryRequest {
        public String deviceName = "";
        public String deviceBrand = "";

        public static ServiceDiscoveryRequest parse(byte[] body) {
            ServiceDiscoveryRequest m = new ServiceDiscoveryRequest();
            Proto.Reader r = new Proto.Reader(body);
            while (r.next()) {
                switch (r.field()) {
                    case 4:
                        m.deviceName = r.string();
                        break;
                    case 5:
                        m.deviceBrand = r.string();
                        break;
                    default:
                        r.skip();
                }
            }
            return m;
        }
    }

    /** ChannelOpenRequestMessage.proto: priority = 1, channel_id = 2. */
    public static final class ChannelOpenRequest {
        public int priority;
        public int channelId;

        public static ChannelOpenRequest parse(byte[] body) {
            ChannelOpenRequest m = new ChannelOpenRequest();
            Proto.Reader r = new Proto.Reader(body);
            while (r.next()) {
                switch (r.field()) {
                    case 1:
                        m.priority = (int) r.varint();
                        break;
                    case 2:
                        m.channelId = (int) r.varint();
                        break;
                    default:
                        r.skip();
                }
            }
            return m;
        }
    }

    /** ChannelOpenResponseMessage.proto: status = 1. */
    public static byte[] channelOpenResponse(int status) {
        return new Proto.Writer().varint(1, status).toBytes();
    }

    /** PingRequestMessage.proto / PingResponseMessage.proto: timestamp = 1 (int64). */
    public static byte[] ping(long timestamp) {
        return new Proto.Writer().varint(1, timestamp).toBytes();
    }

    public static long pingTimestamp(byte[] body) {
        return firstVarint(body, 1);
    }

    /** NavigationFocusRequest/Response: type = 1. openauto always answers 2. */
    public static final int NAV_FOCUS_ANSWER = 2;

    public static int navigationFocusType(byte[] body) {
        return (int) firstVarint(body, 1);
    }

    public static byte[] navigationFocusResponse(int type) {
        return new Proto.Writer().varint(1, type).toBytes();
    }

    /** ShutdownRequestMessage.proto: reason = 1. */
    public static int shutdownReason(byte[] body) {
        return (int) firstVarint(body, 1);
    }

    /** ShutdownResponseMessage.proto has no fields. */
    public static byte[] shutdownResponse() {
        return new byte[0];
    }

    /** AudioFocusRequestMessage.proto: audio_focus_type = 1. */
    public static int audioFocusType(byte[] body) {
        return (int) firstVarint(body, 1);
    }

    /** AudioFocusResponseMessage.proto: audio_focus_state = 1. */
    public static byte[] audioFocusResponse(int state) {
        return new Proto.Writer().varint(1, state).toBytes();
    }

    // ---- service discovery response ---------------------------------------------------------

    /** AudioConfigData.proto: sample_rate = 1, bit_depth = 2, channel_count = 3. */
    public static final class AudioConfig {
        public final int sampleRate;
        public final int bitDepth;
        public final int channels;

        public AudioConfig(int sampleRate, int bitDepth, int channels) {
            this.sampleRate = sampleRate;
            this.bitDepth = bitDepth;
            this.channels = channels;
        }

        Proto.Writer write() {
            return new Proto.Writer().varint(1, sampleRate).varint(2, bitDepth).varint(3, channels);
        }
    }

    /**
     * VideoConfigData.proto: video_resolution = 1, video_fps = 2, margin_width = 3,
     * margin_height = 4, dpi = 5.
     */
    public static final class VideoConfig {
        public final int resolution;
        public final int fps;
        public final int marginWidth;
        public final int marginHeight;
        public final int dpi;

        public VideoConfig(int resolution, int fps, int marginWidth, int marginHeight, int dpi) {
            this.resolution = resolution;
            this.fps = fps;
            this.marginWidth = marginWidth;
            this.marginHeight = marginHeight;
            this.dpi = dpi;
        }

        public int width() {
            switch (resolution) {
                case RES_800x480:
                    return 800;
                case RES_1280x720:
                    return 1280;
                default:
                    return 1920;
            }
        }

        public int height() {
            switch (resolution) {
                case RES_800x480:
                    return 480;
                case RES_1280x720:
                    return 720;
                default:
                    return 1080;
            }
        }

        Proto.Writer write() {
            return new Proto.Writer().varint(1, resolution).varint(2, fps)
                    .varint(3, marginWidth).varint(4, marginHeight).varint(5, dpi);
        }
    }

    /**
     * ChannelDescriptorData.proto: channel_id = 1, sensor_channel = 2, av_channel = 3,
     * input_channel = 4. AVChannelData.proto: stream_type = 1, audio_type = 2,
     * audio_configs = 3, video_configs = 4, available_while_in_call = 5.
     * SensorChannelData.proto: sensors = 1 (SensorData.proto: type = 1).
     * InputChannelData.proto: supported_keycodes = 1, touch_screen_config = 2
     * (TouchConfigData.proto: width = 1, height = 2).
     */
    public static final class ChannelDescriptor {
        private final Proto.Writer w = new Proto.Writer();

        private ChannelDescriptor(int channelId) {
            w.varint(1, channelId);
        }

        public static ChannelDescriptor video(int channelId, List<VideoConfig> configs) {
            ChannelDescriptor d = new ChannelDescriptor(channelId);
            Proto.Writer av = new Proto.Writer().varint(1, STREAM_VIDEO);
            for (VideoConfig c : configs) {
                av.message(4, c.write());
            }
            av.bool(5, true);
            d.w.message(3, av);
            return d;
        }

        public static ChannelDescriptor audio(int channelId, int audioType, AudioConfig config) {
            ChannelDescriptor d = new ChannelDescriptor(channelId);
            Proto.Writer av = new Proto.Writer().varint(1, STREAM_AUDIO).varint(2, audioType)
                    .message(3, config.write()).bool(5, true);
            d.w.message(3, av);
            return d;
        }

        public static ChannelDescriptor sensor(int channelId, int... sensorTypes) {
            ChannelDescriptor d = new ChannelDescriptor(channelId);
            Proto.Writer s = new Proto.Writer();
            for (int t : sensorTypes) {
                s.message(1, new Proto.Writer().varint(1, t));
            }
            d.w.message(2, s);
            return d;
        }

        public static ChannelDescriptor input(int channelId, int touchWidth, int touchHeight, int... keycodes) {
            ChannelDescriptor d = new ChannelDescriptor(channelId);
            Proto.Writer in = new Proto.Writer();
            for (int k : keycodes) {
                in.varint(1, k);
            }
            in.message(2, new Proto.Writer().varint(1, touchWidth).varint(2, touchHeight));
            d.w.message(4, in);
            return d;
        }

        byte[] toBytes() {
            return w.toBytes();
        }
    }

    /**
     * ServiceDiscoveryResponseMessage.proto: channels = 1, head_unit_name = 2, car_model = 3,
     * car_year = 4, car_serial = 5, left_hand_drive_vehicle = 6, headunit_manufacturer = 7,
     * headunit_model = 8, sw_build = 9, sw_version = 10, can_play_native_media_during_vr = 11,
     * hide_clock = 12.
     */
    public static final class ServiceDiscoveryResponse {
        public final List<ChannelDescriptor> channels = new ArrayList<>();
        public String headUnitName = "";
        public String carModel = "";
        public String carYear = "";
        public String carSerial = "";
        public boolean leftHandDrive = true;
        public String manufacturer = "";
        public String model = "";
        public String swBuild = "";
        public String swVersion = "";
        public boolean nativeMediaDuringVr = false;
        public boolean hideClock = false;

        public byte[] encode() {
            Proto.Writer w = new Proto.Writer();
            for (ChannelDescriptor c : channels) {
                w.bytes(1, c.toBytes());
            }
            return w.string(2, headUnitName).string(3, carModel).string(4, carYear)
                    .string(5, carSerial).bool(6, leftHandDrive).string(7, manufacturer)
                    .string(8, model).string(9, swBuild).string(10, swVersion)
                    .bool(11, nativeMediaDuringVr).bool(12, hideClock).toBytes();
        }
    }

    // ---- AV channels -------------------------------------------------------------------------

    /** AVChannelSetupRequestMessage.proto: config_index = 1. */
    public static int setupConfigIndex(byte[] body) {
        return (int) firstVarint(body, 1);
    }

    /** AVChannelSetupResponseMessage.proto: media_status = 1, max_unacked = 2, configs = 3. */
    public static byte[] setupResponse(int status, int maxUnacked, int... configs) {
        Proto.Writer w = new Proto.Writer().varint(1, status).varint(2, maxUnacked);
        for (int c : configs) {
            w.varint(3, c);
        }
        return w.toBytes();
    }

    /** AVChannelStartIndicationMessage.proto: session = 1, config = 2. */
    public static final class StartIndication {
        public int session;
        public int config;

        public static StartIndication parse(byte[] body) {
            StartIndication m = new StartIndication();
            Proto.Reader r = new Proto.Reader(body);
            while (r.next()) {
                switch (r.field()) {
                    case 1:
                        m.session = (int) r.varint();
                        break;
                    case 2:
                        m.config = (int) r.varint();
                        break;
                    default:
                        r.skip();
                }
            }
            return m;
        }
    }

    /** AVMediaAckIndicationMessage.proto: session = 1, value = 2. Open receivers ack with 1. */
    public static byte[] mediaAck(int session, int value) {
        return new Proto.Writer().varint(1, session).varint(2, value).toBytes();
    }

    /** VideoFocusRequestMessage.proto: disp_index = 1, focus_mode = 2, focus_reason = 3. */
    public static int videoFocusMode(byte[] body) {
        return (int) firstVarint(body, 2);
    }

    /** VideoFocusIndicationMessage.proto: focus_mode = 1, unrequested = 2. */
    public static byte[] videoFocusIndication(int mode, boolean unrequested) {
        return new Proto.Writer().varint(1, mode).bool(2, unrequested).toBytes();
    }

    /**
     * Media data is not protobuf. AV_MEDIA_WITH_TIMESTAMP: {@code u64 BE timestamp} then the
     * raw H.264 / PCM bytes (aasdk {@code Messenger/Timestamp.cpp}). AV_MEDIA: raw bytes only.
     */
    public static final int TIMESTAMP_LENGTH = 8;

    public static long mediaTimestamp(byte[] body) {
        long t = 0;
        for (int i = 0; i < TIMESTAMP_LENGTH; i++) {
            t = (t << 8) | (body[i] & 0xFF);
        }
        return t;
    }

    // ---- input channel -----------------------------------------------------------------------

    /**
     * InputEventIndicationMessage.proto: timestamp = 1 (uint64), touch_event = 3.
     * TouchEventData.proto: touch_location = 1, action_index = 2, touch_action = 3.
     * TouchLocationData.proto: x = 1, y = 2, pointer_id = 3.
     */
    public static byte[] touchEvent(long timestampUs, int action, int actionIndex, int[] xs, int[] ys, int[] pointerIds) {
        Proto.Writer touch = new Proto.Writer();
        for (int i = 0; i < xs.length; i++) {
            touch.message(1, new Proto.Writer().varint(1, xs[i]).varint(2, ys[i]).varint(3, pointerIds[i]));
        }
        touch.varint(2, actionIndex).varint(3, action);
        return new Proto.Writer().varint(1, timestampUs).message(3, touch).toBytes();
    }

    /** BindingRequestMessage.proto: scan_codes = 1 (repeated). */
    public static List<Integer> bindingScanCodes(byte[] body) {
        List<Integer> codes = new ArrayList<>();
        Proto.Reader r = new Proto.Reader(body);
        while (r.next()) {
            if (r.field() == 1 && r.wire() == Proto.WIRE_VARINT) {
                codes.add((int) r.varint());
            } else {
                r.skip();
            }
        }
        return codes;
    }

    /** BindingResponseMessage.proto: status = 1. */
    public static byte[] bindingResponse(int status) {
        return new Proto.Writer().varint(1, status).toBytes();
    }

    // ---- sensor channel ----------------------------------------------------------------------

    /** SensorStartRequestMessage.proto: sensor_type = 1, refresh_interval = 2. */
    public static int sensorStartType(byte[] body) {
        return (int) firstVarint(body, 1);
    }

    /** SensorStartResponseMessage.proto: status = 1. */
    public static byte[] sensorStartResponse(int status) {
        return new Proto.Writer().varint(1, status).toBytes();
    }

    /** SensorEventIndicationMessage.proto: driving_status = 13 (DrivingStatusData.proto: status = 1). */
    public static byte[] drivingStatusEvent(int status) {
        return new Proto.Writer().message(13, new Proto.Writer().varint(1, status)).toBytes();
    }

    /** SensorEventIndicationMessage.proto: night_mode = 10 (NightModeData.proto: is_night = 1). */
    public static byte[] nightModeEvent(boolean night) {
        return new Proto.Writer().message(10, new Proto.Writer().bool(1, night)).toBytes();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static long firstVarint(byte[] body, int wantedField) {
        Proto.Reader r = new Proto.Reader(body);
        while (r.next()) {
            if (r.field() == wantedField && r.wire() == Proto.WIRE_VARINT) {
                return r.varint();
            }
            r.skip();
        }
        return 0;
    }
}
