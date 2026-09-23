package com.ripostelabs.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

/**
 * The {@link Tuner} backend for Riposte OS 0.2, where the launcher owns the MCU link and
 * exports it as ITuner (device-reveng RAV4-97). Hand-rolled Binder like {@link VendorTuner},
 * against the contract written in the launcher's ITuner.aidl:
 *
 *   ITuner          claim 1, release 2, isClaimed 3, sendKey 4, tune 5, getState 6,
 *                   registerCallback 7, unregisterCallback 8, selectPreset 9, storePreset 10
 *   ITunerCallback  onState(TunerState) 1, onSourceLost 2, onReclaim 3   (oneway)
 *   TunerState      band, freq, preset, stationName, stereo, rds, stMono, dxLoc, ta, af,
 *                   stationList[42], pty, scanning, autoStoring, zone
 *                   (a typed object: non-null flag first; the four after the list were appended
 *                   later, so they are read only when the parcel still has them)
 *
 * Unlike the vendor gateway there is nothing to poll: the launcher pushes every state fold
 * over onState, the getters read the last one, and each push becomes onRadioEvent().
 */
final class LauncherTuner extends Tuner {

    static final String SERVICE_ACTION = "com.ripostelabs.carlauncher.tuner.ITuner";
    /** Release first, the farm's debug build second. */
    private static final String[] SERVICE_PACKAGES = {
            "com.ripostelabs.carlauncher", "com.ripostelabs.carlauncher.debug"};
    private static final String DESCRIPTOR = "com.ripostelabs.carlauncher.tuner.ITuner";
    private static final String CALLBACK_DESCRIPTOR = "com.ripostelabs.carlauncher.tuner.ITunerCallback";

    private static final String TAG = "LauncherTuner";

    private static final int TR_CLAIM = 1;
    private static final int TR_RELEASE = 2;
    private static final int TR_IS_CLAIMED = 3;
    private static final int TR_SEND_KEY = 4;
    private static final int TR_TUNE = 5;
    private static final int TR_GET_STATE = 6;
    private static final int TR_REGISTER_CALLBACK = 7;
    private static final int TR_UNREGISTER_CALLBACK = 8;
    private static final int TR_SELECT_PRESET = 9;
    private static final int TR_STORE_PRESET = 10;
    private static final int STATION_LIST_SIZE = 42;

    private static final int CB_ON_STATE = 1;
    private static final int CB_ON_SOURCE_LOST = 2;
    private static final int CB_ON_RECLAIM = 3;

    /** Last TunerState the launcher pushed (or getState returned). Main thread only. */
    private static final class State {
        int band = -1;
        int freq = -1;
        String stationName = "";
        boolean stereo, rds, stMono, dxLoc;
        int[] stationList = new int[STATION_LIST_SIZE];
        int pty;
        boolean scanning, autoStoring;
        int zone = RadioZone.DEFAULT_ZONE;

        /** Reads the parcel in TunerState.writeToParcel order, after the non-null flag. */
        static State read(Parcel p) {
            State s = new State();
            s.band = p.readInt();
            s.freq = p.readInt();
            p.readInt();                       // preset: the screen keeps its own
            String name = p.readString();
            s.stationName = name == null ? "" : name;
            s.stereo = p.readInt() != 0;
            s.rds = p.readInt() != 0;
            s.stMono = p.readInt() != 0;
            s.dxLoc = p.readInt() != 0;
            p.readInt();                       // ta
            p.readInt();                       // af
            int[] list = p.createIntArray();
            if (list != null && list.length == STATION_LIST_SIZE) s.stationList = list;
            if (p.dataAvail() <= 0) return s;  // a launcher from before the appended fields
            s.pty = p.readInt();
            s.scanning = p.readInt() != 0;
            s.autoStoring = p.readInt() != 0;
            s.zone = p.readInt();
            return s;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder service;
    private boolean bound;
    private State state = new State();

    LauncherTuner(Context context, Listener listener) {
        super(context, listener);
    }

    /** The launcher package that is installed here, or null. */
    static String installedPackage(Context context) {
        for (String pkg : SERVICE_PACKAGES) {
            if (installed(context, pkg)) return pkg;
        }
        return null;
    }

    // ---- Callback binder ---------------------------------------------------

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code != CB_ON_STATE && code != CB_ON_SOURCE_LOST && code != CB_ON_RECLAIM) {
                try {
                    return super.onTransact(code, data, reply, flags);
                } catch (android.os.RemoteException e) {
                    return false;
                }
            }
            data.enforceInterface(CALLBACK_DESCRIPTOR);
            if (code == CB_ON_STATE) {
                if (data.readInt() == 0) return true;      // null TunerState: nothing to show
                State s = State.read(data);
                main.post(() -> { state = s; listener.onRadioEvent(); });
            } else if (code == CB_ON_SOURCE_LOST) {
                main.post(listener::onModeLost);
            } else {
                main.post(listener::onReclaimRequested);
            }
            return true;                                   // oneway: no reply
        }
    };

    // ---- Connection --------------------------------------------------------

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = binder;
            transactVoid(TR_REGISTER_CALLBACK, p -> p.writeStrongBinder(callback));
            refreshState();
            listener.onConnected();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            listener.onDisconnected();
        }
    };

    @Override boolean bind() {
        if (bound) return true;
        String pkg = installedPackage(context);
        if (pkg == null) return false;
        Intent intent = new Intent(SERVICE_ACTION).setPackage(pkg);
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            bound = false;
        }
        return bound;
    }

    @Override void unbind() {
        if (!bound) return;
        transactVoid(TR_UNREGISTER_CALLBACK, p -> p.writeStrongBinder(callback));
        try { context.unbindService(connection); } catch (Exception ignored) {}
        bound = false;
        service = null;
    }

    @Override boolean isConnected() { return service != null && service.isBinderAlive(); }

    // ---- Mode / audio path -------------------------------------------------

    @Override void claimAudio() {
        transactVoid(TR_CLAIM, p -> {});
        refreshState();
    }

    @Override void releaseAudio() {
        transactVoid(TR_RELEASE, p -> {});
    }

    // ---- Commands ----------------------------------------------------------

    @Override void sendKey(int key) {
        transactVoid(TR_SEND_KEY, p -> p.writeInt(key));
    }

    @Override void tune(int freq, boolean isFm) {
        transactVoid(TR_TUNE, p -> { p.writeInt(freq); p.writeInt(isFm ? 1 : 0); });
    }

    // ---- Getters: the last pushed state ------------------------------------

    @Override int getFreq() { return state.freq; }
    @Override int getBand() { return state.band; }
    @Override int getValidMode() { return isClaimed() ? SRC_RADIO : -1; }
    @Override boolean getStereoIcon() { return state.stereo; }
    @Override boolean getRdsState() { return state.rds; }
    @Override boolean getStMono() { return state.stMono; }
    @Override boolean getDxLoc() { return state.dxLoc; }
    @Override String getStationName() { return state.stationName; }
    @Override int getPty() { return state.pty; }
    @Override boolean isScanning() { return state.scanning; }
    @Override boolean isAutoStoring() { return state.autoStoring; }
    @Override int getZone() { return state.zone; }
    @Override int[] getStationList() { return state.stationList; }

    @Override void selectPreset(int slot) {
        transactVoid(TR_SELECT_PRESET, p -> p.writeInt(slot));
    }

    @Override void storePreset(int slot) {
        transactVoid(TR_STORE_PRESET, p -> p.writeInt(slot));
    }

    private boolean isClaimed() {
        IBinder s = service;
        if (s == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            s.transact(TR_IS_CLAIMED, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** getState: a typed object in the reply, non-null flag then the fields. */
    private void refreshState() {
        IBinder s = service;
        if (s == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            s.transact(TR_GET_STATE, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 0) state = State.read(reply);
        } catch (Exception e) {
            Log.w(TAG, "getState failed", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ---- Binder plumbing ---------------------------------------------------

    private interface ParcelWriter { void write(Parcel p); }

    private void transactVoid(int code, ParcelWriter writer) {
        IBinder s = service;
        if (s == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            writer.write(data);
            s.transact(code, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Log.w(TAG, "transaction " + code + " failed", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
