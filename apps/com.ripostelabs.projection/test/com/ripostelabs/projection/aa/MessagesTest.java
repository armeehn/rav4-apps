package com.ripostelabs.projection.aa;

import java.util.Arrays;
import java.util.List;

/**
 * Byte-exact against the literals headunit {@code hu_aap.c} sends (channel open response
 * {@code 08 00}, media ack {@code 08 00 10 01}, video focus {@code 08 01 10 01}, setup response
 * {@code 08 02 10 01 18 00}, driving status {@code 6a 02 08 00}), and structural for the rest.
 */
public final class MessagesTest {

    public static void main(String[] args) {
        version();
        controlLiterals();
        avLiterals();
        sensorLiterals();
        parsers();
        touchEvent();
        serviceDiscovery();
        mediaTimestamp();
        System.out.println(Check.count + " assertions passed");
    }

    private static void version() {
        Check.bytes(Check.hex("00 01 00 01"), Messages.versionRequest(1, 1), "version 1.1");
        Messages.VersionResponse ok = Messages.VersionResponse.parse(Check.hex("00 01 00 01 00 00"));
        Check.eq(1, ok.major, "major");
        Check.eq(1, ok.minor, "minor");
        Check.eq(Messages.VERSION_MATCH, ok.status, "match");
        Messages.VersionResponse bad = Messages.VersionResponse.parse(Check.hex("00 01 00 05 ff ff"));
        Check.eq(Messages.VERSION_MISMATCH, bad.status, "mismatch");
        Check.eq(0, Messages.VersionResponse.parse(Check.hex("00 01")).minor, "short reply reads 0");
    }

    private static void controlLiterals() {
        Check.bytes(Check.hex("08 00"), Messages.authComplete(Messages.STATUS_OK), "auth complete");
        Check.bytes(Check.hex("08 00"), Messages.channelOpenResponse(Messages.STATUS_OK), "channel open ok");
        Check.bytes(Check.hex("08 02"), Messages.navigationFocusResponse(Messages.NAV_FOCUS_ANSWER), "nav focus 2");
        Check.bytes(new byte[0], Messages.shutdownResponse(), "shutdown response is empty");
        Check.bytes(Check.hex("08 01"), Messages.audioFocusResponse(Messages.FOCUS_STATE_GAIN), "focus gain");
        Check.bytes(Check.hex("08 ac 02"), Messages.ping(300), "ping timestamp");
        Check.eq(300, Messages.pingTimestamp(Check.hex("08 ac 02")), "ping timestamp back");
    }

    private static void avLiterals() {
        Check.bytes(Check.hex("08 02 10 01 18 00"),
                Messages.setupResponse(Messages.SETUP_OK, 1, 0), "setup response");
        Check.bytes(Check.hex("08 00 10 01"), Messages.mediaAck(0, 1), "media ack");
        Check.bytes(Check.hex("08 01 10 01"), Messages.videoFocusIndication(Messages.VIDEO_FOCUSED, true), "video focus");
        Check.bytes(Check.hex("08 01 10 00"), Messages.videoFocusIndication(Messages.VIDEO_FOCUSED, false), "openauto's");
        Check.bytes(Check.hex("08 00"), Messages.bindingResponse(Messages.STATUS_OK), "binding response");
    }

    private static void sensorLiterals() {
        Check.bytes(Check.hex("08 00"), Messages.sensorStartResponse(Messages.STATUS_OK), "sensor start");
        Check.bytes(Check.hex("6a 02 08 00"), Messages.drivingStatusEvent(Messages.DRIVING_UNRESTRICTED), "driving status");
        Check.bytes(Check.hex("52 02 08 00"), Messages.nightModeEvent(false), "night mode field 10");
    }

    private static void parsers() {
        // headunit log: "R 2 VID f 00000000 08 00 10 02" = priority 0, channel 2
        Messages.ChannelOpenRequest open = Messages.ChannelOpenRequest.parse(Check.hex("08 00 10 02"));
        Check.eq(0, open.priority, "priority");
        Check.eq(2, open.channelId, "channel id");

        Check.eq(3, Messages.setupConfigIndex(Check.hex("08 03")), "config index");
        Check.eq(13, Messages.sensorStartType(Check.hex("08 0d 10 00")), "sensor type");
        Check.eq(1, Messages.shutdownReason(Check.hex("08 01")), "shutdown reason");
        Check.eq(Messages.FOCUS_RELEASE, Messages.audioFocusType(Check.hex("08 04")), "focus type");
        Check.eq(Messages.VIDEO_FOCUSED, Messages.videoFocusMode(Check.hex("08 00 10 01 18 01")), "focus mode");

        Messages.StartIndication start = Messages.StartIndication.parse(Check.hex("08 07 10 00"));
        Check.eq(7, start.session, "session");
        Check.eq(0, start.config, "config");

        byte[] sdrq = new Proto.Writer().string(4, "Pixel 7").string(5, "Google").toBytes();
        Messages.ServiceDiscoveryRequest req = Messages.ServiceDiscoveryRequest.parse(sdrq);
        Check.eq("Pixel 7", req.deviceName, "device name");
        Check.eq("Google", req.deviceBrand, "device brand");

        List<Integer> codes = Messages.bindingScanCodes(Check.hex("08 54 08 55 08 7e"));
        Check.that(codes.equals(Arrays.asList(0x54, 0x55, 0x7e)), "scan codes");
    }

    private static void touchEvent() {
        byte[] b = Messages.touchEvent(1234, Messages.TOUCH_PRESS, 0, new int[] {100}, new int[] {200}, new int[] {0});
        Proto.Reader r = new Proto.Reader(b);
        r.next();
        Check.eq(1, r.field(), "timestamp first");
        Check.eq(1234, r.varint(), "timestamp");
        r.next();
        Check.eq(3, r.field(), "touch_event is field 3");
        Proto.Reader touch = r.message();
        touch.next();
        Check.eq(1, touch.field(), "touch_location");
        Proto.Reader loc = touch.message();
        loc.next();
        Check.eq(100, loc.varint(), "x");
        loc.next();
        Check.eq(200, loc.varint(), "y");
        loc.next();
        Check.eq(3, loc.field(), "pointer_id is field 3");
        touch.next();
        Check.eq(2, touch.field(), "action_index");
        touch.varint();
        touch.next();
        Check.eq(3, touch.field(), "touch_action");
        Check.eq(Messages.TOUCH_PRESS, touch.varint(), "press");
        Check.that(!r.next(), "no button/absolute/relative events");
    }

    private static void serviceDiscovery() {
        Messages.ServiceDiscoveryResponse sdr = new Messages.ServiceDiscoveryResponse();
        sdr.channels.add(Messages.ChannelDescriptor.video(Ids.CH_VIDEO, Arrays.asList(
                new Messages.VideoConfig(Messages.RES_1920x1080, Messages.FPS_30, 0, 360, 160))));
        sdr.channels.add(Messages.ChannelDescriptor.input(Ids.CH_INPUT, 1920, 1080));
        sdr.channels.add(Messages.ChannelDescriptor.sensor(Ids.CH_SENSOR, Messages.SENSOR_DRIVING_STATUS, Messages.SENSOR_NIGHT_DATA));
        sdr.channels.add(Messages.ChannelDescriptor.audio(Ids.CH_MEDIA_AUDIO, Messages.AUDIO_MEDIA,
                new Messages.AudioConfig(48000, 16, 2)));
        sdr.headUnitName = "Riposte";
        sdr.carModel = "RAV4";
        byte[] b = sdr.encode();

        int channels = 0;
        int[] ids = new int[4];
        Proto.Reader r = new Proto.Reader(b);
        while (r.next()) {
            switch (r.field()) {
                case 1: {
                    Proto.Reader d = r.message();
                    d.next();
                    ids[channels++] = (int) d.varint();
                    break;
                }
                case 2:
                    Check.eq("Riposte", r.string(), "head unit name");
                    break;
                case 3:
                    Check.eq("RAV4", r.string(), "car model");
                    break;
                case 6:
                    Check.that(r.bool(), "left hand drive");
                    break;
                default:
                    r.skip();
            }
        }
        Check.eq(4, channels, "four descriptors");
        Check.that(Arrays.equals(new int[] {3, 1, 2, 4}, ids), "channel ids in declared order");

        // The video descriptor: field 3 (av_channel) > field 4 (video_configs) > field 1 (resolution)
        Proto.Reader d = new Proto.Reader(b);
        d.next();
        Proto.Reader desc = d.message();
        desc.next();
        desc.varint();
        desc.next();
        Check.eq(3, desc.field(), "av_channel");
        Proto.Reader av = desc.message();
        av.next();
        Check.eq(Messages.STREAM_VIDEO, av.varint(), "stream type video");
        av.next();
        Check.eq(4, av.field(), "video_configs");
        Proto.Reader vc = av.message();
        vc.next();
        Check.eq(Messages.RES_1920x1080, vc.varint(), "resolution");
        vc.next();
        Check.eq(Messages.FPS_30, vc.varint(), "fps");
        vc.next();
        Check.eq(3, vc.field(), "margin_width");
        Check.eq(0, vc.varint(), "no width margin");
        vc.next();
        Check.eq(4, vc.field(), "margin_height");
        Check.eq(360, vc.varint(), "360 rows off a 1080 frame leaves the 720 panel");
        vc.next();
        Check.eq(5, vc.field(), "dpi");
        Check.eq(160, vc.varint(), "dpi value");

        Messages.VideoConfig cfg = new Messages.VideoConfig(Messages.RES_1280x720, Messages.FPS_30, 0, 0, 160);
        Check.eq(1280, cfg.width(), "720p width");
        Check.eq(720, cfg.height(), "720p height");
    }

    private static void mediaTimestamp() {
        byte[] body = Check.hex("00 00 00 00 00 01 e2 40 00 00 00 01 67");
        Check.eq(123456, Messages.mediaTimestamp(body), "u64 big-endian timestamp");
        Check.eq(8, Messages.TIMESTAMP_LENGTH, "H.264 starts after eight bytes");
    }
}
