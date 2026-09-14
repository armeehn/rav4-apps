package com.ripostelabs.projection.aa;

/**
 * Channel numbers and message ids of the Android Auto head-unit protocol.
 *
 * <p>Every value is a wire-format fact taken from the public reverse-engineering (aasdk's
 * {@code aasdk_proto/*IdsEnum.proto} and {@code Messenger/ChannelId.hpp}; mikereidis/headunit
 * {@code hu_aap.c} agrees on all of them). Channel numbers are chosen by the head unit and
 * announced in the service discovery response; these are the conventional ones every open
 * receiver uses.
 */
public final class Ids {

    private Ids() {
    }

    // ---- channels ------------------------------------------------------------------------

    public static final int CH_CONTROL = 0;
    public static final int CH_INPUT = 1;
    public static final int CH_SENSOR = 2;
    public static final int CH_VIDEO = 3;
    public static final int CH_MEDIA_AUDIO = 4;
    public static final int CH_SPEECH_AUDIO = 5;
    public static final int CH_SYSTEM_AUDIO = 6;
    public static final int CH_AV_INPUT = 7;
    public static final int CH_BLUETOOTH = 8;

    // ---- control channel (ControlMessageIdsEnum.proto) ------------------------------------

    public static final int VERSION_REQUEST = 0x0001;
    public static final int VERSION_RESPONSE = 0x0002;
    public static final int SSL_HANDSHAKE = 0x0003;
    public static final int AUTH_COMPLETE = 0x0004;
    public static final int SERVICE_DISCOVERY_REQUEST = 0x0005;
    public static final int SERVICE_DISCOVERY_RESPONSE = 0x0006;
    public static final int CHANNEL_OPEN_REQUEST = 0x0007;
    public static final int CHANNEL_OPEN_RESPONSE = 0x0008;
    public static final int PING_REQUEST = 0x000b;
    public static final int PING_RESPONSE = 0x000c;
    public static final int NAVIGATION_FOCUS_REQUEST = 0x000d;
    public static final int NAVIGATION_FOCUS_RESPONSE = 0x000e;
    public static final int SHUTDOWN_REQUEST = 0x000f;
    public static final int SHUTDOWN_RESPONSE = 0x0010;
    public static final int VOICE_SESSION_REQUEST = 0x0011;
    public static final int AUDIO_FOCUS_REQUEST = 0x0012;
    public static final int AUDIO_FOCUS_RESPONSE = 0x0013;

    // ---- AV channels: video and the three audio sinks (AVChannelMessageIdsEnum.proto) -----

    public static final int AV_MEDIA_WITH_TIMESTAMP = 0x0000;
    public static final int AV_MEDIA = 0x0001;
    public static final int AV_SETUP_REQUEST = 0x8000;
    public static final int AV_START_INDICATION = 0x8001;
    public static final int AV_STOP_INDICATION = 0x8002;
    public static final int AV_SETUP_RESPONSE = 0x8003;
    public static final int AV_MEDIA_ACK = 0x8004;
    public static final int AV_INPUT_OPEN_REQUEST = 0x8005;
    public static final int AV_INPUT_OPEN_RESPONSE = 0x8006;
    public static final int VIDEO_FOCUS_REQUEST = 0x8007;
    public static final int VIDEO_FOCUS_INDICATION = 0x8008;

    // ---- input channel (InputChannelMessageIdsEnum.proto) ---------------------------------

    public static final int INPUT_EVENT_INDICATION = 0x8001;
    public static final int BINDING_REQUEST = 0x8002;
    public static final int BINDING_RESPONSE = 0x8003;

    // ---- sensor channel (SensorChannelMessageIdsEnum.proto) -------------------------------

    public static final int SENSOR_START_REQUEST = 0x8001;
    public static final int SENSOR_START_RESPONSE = 0x8002;
    public static final int SENSOR_EVENT_INDICATION = 0x8003;

    /**
     * Message ids below this are "control type" on every channel. On a non-control channel a
     * frame carrying one gets the CONTROL flag (aasdk sends CHANNEL_OPEN_RESPONSE that way;
     * headunit sets 0x04 for any id whose high byte is zero).
     */
    public static final int CHANNEL_SPECIFIC_BASE = 0x8000;
}
