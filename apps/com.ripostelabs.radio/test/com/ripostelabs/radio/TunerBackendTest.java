package com.ripostelabs.radio;

/**
 * The backend choice decides which process gets the MCU: picking the launcher while the
 * vendor gateway is alive (0.1) would leave two writers on one serial port.
 */
public final class TunerBackendTest {

    public static void main(String[] args) {
        check(TunerBackend.choose(true, true) == TunerBackend.VENDOR, "vendor wins when both are installed (0.1)");
        check(TunerBackend.choose(true, false) == TunerBackend.VENDOR, "stock");
        check(TunerBackend.choose(false, true) == TunerBackend.LAUNCHER, "0.2: launcher owns the car");
        check(TunerBackend.choose(false, false) == TunerBackend.NONE, "emulator without a launcher");
        System.out.println("TunerBackendTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
