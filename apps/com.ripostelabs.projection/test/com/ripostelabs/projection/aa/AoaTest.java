package com.ripostelabs.projection.aa;

import java.util.List;

/** The accessory-mode switch must look exactly like the one phones already accept. */
public final class AoaTest {

    public static void main(String[] args) {
        constants();
        stringTable();
        sequence();
        protocolReply();
        System.out.println(Check.count + " assertions passed");
    }

    private static void constants() {
        Check.eq(0x18D1, Aoa.GOOGLE_VID, "Google VID");
        Check.eq(0x2D00, Aoa.PID_ACCESSORY, "accessory PID");
        Check.eq(0x2D01, Aoa.PID_ACCESSORY_ADB, "accessory+adb PID");
        Check.eq(51, Aoa.REQ_GET_PROTOCOL, "GET_PROTOCOL");
        Check.eq(52, Aoa.REQ_SEND_STRING, "SEND_STRING");
        Check.eq(53, Aoa.REQ_START, "START");
        Check.eq(0xC0, Aoa.TYPE_VENDOR_IN, "vendor IN");
        Check.eq(0x40, Aoa.TYPE_VENDOR_OUT, "vendor OUT");
        Check.that(Aoa.isAccessory(0x18D1, 0x2D00), "2D00 is accessory");
        Check.that(Aoa.isAccessory(0x18D1, 0x2D01), "2D01 is accessory");
        Check.that(!Aoa.isAccessory(0x18D1, 0x4EE1), "a Pixel in MTP mode is not");
    }

    private static void stringTable() {
        Aoa.Str[] s = Aoa.Str.values();
        Check.eq(6, s.length, "six AOA strings");
        for (int i = 0; i < s.length; i++) {
            Check.eq(i, s[i].index, "string " + s[i] + " index");
        }
        Check.eq("Android", Aoa.Str.MANUFACTURER.value, "manufacturer");
        Check.eq("Android Auto", Aoa.Str.MODEL.value, "model");
        Check.eq("Android Auto", Aoa.Str.DESCRIPTION.value, "description");
        Check.eq("2.0.1", Aoa.Str.VERSION.value, "version");
        Check.eq("HU-AAAAAA001", Aoa.Str.SERIAL.value, "serial");
    }

    private static void sequence() {
        List<Aoa.Step> steps = Aoa.switchSequence();
        Check.eq(8, steps.size(), "GET_PROTOCOL + 6 strings + START");

        Aoa.Step first = steps.get(0);
        Check.eq(Aoa.REQ_GET_PROTOCOL, first.request, "first is GET_PROTOCOL");
        Check.that(first.direction == Aoa.Direction.IN, "GET_PROTOCOL reads");
        Check.eq(0xC0, first.requestType, "GET_PROTOCOL request type");
        Check.eq(2, first.length, "GET_PROTOCOL wants two bytes");

        for (int i = 1; i <= 6; i++) {
            Aoa.Step s = steps.get(i);
            Check.eq(Aoa.REQ_SEND_STRING, s.request, "step " + i + " sends a string");
            Check.eq(i - 1, s.index, "step " + i + " string index");
            Check.eq(0x40, s.requestType, "SEND_STRING request type");
            Check.eq(0, s.data[s.data.length - 1], "string is NUL terminated");
            Check.eq(s.data.length, s.length, "length covers the NUL");
        }
        Check.bytes("Android\0".getBytes(), steps.get(1).data, "manufacturer bytes");

        Aoa.Step last = steps.get(7);
        Check.eq(Aoa.REQ_START, last.request, "last is START");
        Check.eq(0, last.length, "START carries no data");
        Check.eq(0, last.index, "START index");
    }

    private static void protocolReply() {
        Check.eq(2, Aoa.protocolVersion(new byte[] {2, 0}, 2), "little-endian version");
        Check.eq(0, Aoa.protocolVersion(new byte[] {2, 0}, 1), "short reply is 0");
        Check.that(Aoa.protocolSupported(1) && Aoa.protocolSupported(2), "1 and 2 accepted");
        Check.that(!Aoa.protocolSupported(0) && !Aoa.protocolSupported(3), "0 and 3 refused");
    }
}
