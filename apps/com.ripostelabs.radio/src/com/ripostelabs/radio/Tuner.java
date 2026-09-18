package com.ripostelabs.radio;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * The FM/AM tuner as the radio screen sees it, behind whichever process owns the MCU link:
 *
 *   stock / Riposte OS 0.1   com.szchoiceway.eventcenter  ──►  {@link VendorTuner}
 *   Riposte OS 0.2           com.ripostelabs.carlauncher  ──►  {@link LauncherTuner} (ITuner)
 *
 * {@link #open} picks by what is installed; the vendor gateway wins when both are, because
 * on 0.1 the launcher does not own the serial link. Units are the vendor's throughout:
 * FM in 10 kHz (9130 = 91.30 MHz), AM in kHz, band 0..2 = FM1..FM3, 3+ = AM.
 */
abstract class Tuner {

    interface Listener {
        /** Some tuner state changed — re-poll getters and refresh the UI. */
        void onRadioEvent();
        /** Another source (BT, USB, CarPlay…) took the audio path over. */
        void onModeLost();
        /** Gateway asked us to re-announce ourselves (its process restarted). */
        void onReclaimRequested();
        void onConnected();
        void onDisconnected();
    }

    static final int SRC_RADIO = 1; // EventUtils.eSrcMode.SRC_RADIO

    // sendRadioKey opcodes (vendor radio app UI handlers); the launcher's ITuner takes the same.
    static final int KEY_SCAN = 13;         // preset scan / "sousou"
    static final int KEY_STEP_DOWN = 14;
    static final int KEY_STEP_UP = 15;
    static final int KEY_SEEK_DOWN = 16;
    static final int KEY_SEEK_UP = 17;
    static final int KEY_AUTO_STORE = 18;   // AMS: scan band, store presets
    static final int KEY_ST_MONO = 19;
    static final int KEY_DX_LOC = 20;
    // RDS toggles (RadioUIController.java:647 btnAF, :690 btnTA). Constants only:
    // the transport row has no spare slot, so nothing sends them yet.
    static final int KEY_AF = 21;
    static final int KEY_TA = 23;
    static final int KEY_BAND_FM = 30;
    static final int KEY_BAND_AM = 31;

    protected final Context context;
    protected final Listener listener;

    Tuner(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** The backend for this OS image. Never null: with no owner installed, bind() just fails. */
    static Tuner open(Context context, Listener listener) {
        TunerBackend backend = TunerBackend.choose(
                installed(context, VendorTuner.SERVICE_PACKAGE),
                LauncherTuner.installedPackage(context) != null);
        if (backend == TunerBackend.LAUNCHER) return new LauncherTuner(context, listener);
        return new VendorTuner(context, listener);
    }

    static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    abstract boolean bind();
    abstract void unbind();
    abstract boolean isConnected();

    /** Claim the tuner audio source. */
    abstract void claimAudio();
    /** Release the tuner audio source (switching to streaming / leaving). */
    abstract void releaseAudio();

    abstract void sendKey(int key);
    /** Direct tune. FM freq in 10 kHz units (9130 = 91.30 MHz), AM in kHz. */
    abstract void tune(int freq, boolean isFm);

    abstract int getFreq();
    abstract int getBand();
    /** The current audio source: {@link #SRC_RADIO} when the tuner has it, -1 when unknown. */
    abstract int getValidMode();
    abstract boolean getStereoIcon();
    abstract boolean getRdsState();
    abstract boolean getStMono();
    abstract boolean getDxLoc();
    /** RDS PS station name; empty until the MCU sends a PS frame. */
    abstract String getStationName();
}
