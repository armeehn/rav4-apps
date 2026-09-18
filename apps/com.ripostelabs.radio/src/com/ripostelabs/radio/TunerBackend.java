package com.ripostelabs.radio;

/** Which process the radio talks to for the tuner. Pure choice, so it has a test. */
enum TunerBackend {
    VENDOR, LAUNCHER, NONE;

    /**
     * The vendor gateway wins whenever it is installed: on stock and on Riposte OS 0.1 it
     * owns the MCU serial link and the launcher's ITuner is a passenger. Only 0.2, where the
     * vendor stack is gone and the launcher is the car owner, takes the launcher.
     */
    static TunerBackend choose(boolean vendorInstalled, boolean launcherInstalled) {
        if (vendorInstalled) return VENDOR;
        if (launcherInstalled) return LAUNCHER;
        return NONE;
    }
}
