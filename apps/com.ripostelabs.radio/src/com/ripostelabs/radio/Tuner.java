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

/**
 * Hand-rolled Binder client for the vendor event gateway
 * (com.szchoiceway.eventcenter.EventService), which owns the MCU serial link
 * that drives the FM/AM tuner chip. Built without the aidl tool: transaction
 * codes and marshalling were recovered from the decompiled IEventService.Stub
 * (jadx, decompiled/com.szchoiceway.eventcenter) and match the vendor radio
 * app's proxy byte-for-byte.
 *
 * Protocol notes (from the vendor radio app, com.szchoiceway.radio):
 *  - Claiming tuner audio = setCurModeCallback(SRC_RADIO, cb) +
 *    setRadioCallback(cb) + sendMode(SRC_RADIO, false). The MCU then routes
 *    tuner audio to the amp.
 *  - The radio callback delivers opaque event ids; the vendor app responds by
 *    re-polling all getters, so we do the same (listener.onRadioEvent()).
 *  - Frequencies: FM in 10 kHz units (9130 = 91.30 MHz), AM in kHz.
 *  - Band from getRadioBand(): 0..2 = FM1..FM3, 3+ = AM.
 */
final class Tuner {

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

    private static final String SERVICE_ACTION = "com.szchoiceway.eventcenter.EventService";
    private static final String SERVICE_PACKAGE = "com.szchoiceway.eventcenter";
    private static final String DESCRIPTOR = "com.szchoiceway.eventcenter.IEventService";
    private static final String CALLBACK_DESCRIPTOR = "com.szchoiceway.eventcenter.ICallbackfn";

    // IEventService transaction codes (decompiled Stub constants).
    private static final int TR_SEND_MODE = 1;
    private static final int TR_SEND_RADIO_KEY = 2;
    private static final int TR_SEND_USER_FREQ = 6;
    private static final int TR_GET_RADIO_FREQ = 12;
    private static final int TR_GET_RADIO_BAND = 14;
    private static final int TR_GET_RDS_STATE = 16;
    private static final int TR_GET_ST_MONO_STATE = 22;
    private static final int TR_GET_DX_LOC_STATE = 23;
    private static final int TR_GET_STEREO_ICON = 26;
    private static final int TR_SET_RADIO_CALLBACK = 29;
    private static final int TR_SET_CUR_MODE_CALLBACK = 30;
    private static final int TR_EXIT_CUR_MODE = 31;
    private static final int TR_GET_VALID_MODE = 46;

    // ICallbackfn transaction codes.
    private static final int CB_NOTIFY_EVT = 1;
    private static final int CB_CHECK_IS_ACTIVE = 2;

    static final int SRC_RADIO = 1; // EventUtils.eSrcMode.SRC_RADIO

    // sendRadioKey opcodes (vendor radio app UI handlers).
    static final int KEY_SCAN = 13;         // preset scan / "sousou"
    static final int KEY_STEP_DOWN = 14;
    static final int KEY_STEP_UP = 15;
    static final int KEY_SEEK_DOWN = 16;
    static final int KEY_SEEK_UP = 17;
    static final int KEY_AUTO_STORE = 18;   // AMS: scan band, store presets
    static final int KEY_ST_MONO = 19;
    static final int KEY_DX_LOC = 20;
    static final int KEY_BAND_FM = 30;
    static final int KEY_BAND_AM = 31;

    // Mode-callback event ids (vendor MainActivity handler).
    private static final int EVT_RECLAIM = 254;
    private static final int EVT_REFRESH = 255;
    private static final int EVT_MODE_CHANGE = 4097;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder service;
    private boolean bound;

    Tuner(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    // ---- Callback binders --------------------------------------------------

    /** Replies exactly like the decompiled ICallbackfn.Stub.onTransact. */
    private abstract class CallbackBinder extends Binder {
        abstract void notifyEvt(int what, int arg1, int arg2, byte[] data, String str);

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == CB_NOTIFY_EVT) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                notifyEvt(data.readInt(), data.readInt(), data.readInt(),
                        data.createByteArray(), data.readString());
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == CB_CHECK_IS_ACTIVE) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeInt(0);
                }
                return true;
            }
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (android.os.RemoteException e) {
                return false;
            }
        }
    }

    private final CallbackBinder radioCallback = new CallbackBinder() {
        @Override void notifyEvt(int what, int a1, int a2, byte[] d, String s) {
            main.post(listener::onRadioEvent);
        }
    };

    private final CallbackBinder modeCallback = new CallbackBinder() {
        @Override void notifyEvt(int what, int a1, int a2, byte[] d, String s) {
            main.post(() -> {
                if (what == EVT_MODE_CHANGE) {
                    int mode = getValidMode();
                    if (mode != SRC_RADIO && mode != -1) listener.onModeLost();
                } else if (what == EVT_RECLAIM) {
                    listener.onReclaimRequested();
                } else if (what == EVT_REFRESH) {
                    listener.onRadioEvent();
                }
            });
        }
    };

    // ---- Connection --------------------------------------------------------

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = binder;
            listener.onConnected();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            listener.onDisconnected();
        }
    };

    boolean bind() {
        if (bound) return true;
        Intent intent = new Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE);
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            bound = false;
        }
        return bound;
    }

    void unbind() {
        if (!bound) return;
        try { context.unbindService(connection); } catch (Exception ignored) {}
        bound = false;
        service = null;
    }

    boolean isConnected() { return service != null && service.isBinderAlive(); }

    // ---- Mode / audio path -------------------------------------------------

    /** Claim the tuner audio source, exactly as the vendor radio app does. */
    void claimAudio() {
        transactVoid(TR_SET_CUR_MODE_CALLBACK, p -> {
            p.writeInt(SRC_RADIO);
            p.writeStrongBinder(modeCallback);
        });
        transactVoid(TR_SET_RADIO_CALLBACK, p -> p.writeStrongBinder(radioCallback));
        // The vendor app sends this twice back-to-back; keep the quirk.
        transactVoid(TR_SEND_MODE, p -> { p.writeInt(SRC_RADIO); p.writeInt(0); });
        transactVoid(TR_SEND_MODE, p -> { p.writeInt(SRC_RADIO); p.writeInt(0); });
    }

    /** Release the tuner audio source (switching to streaming / leaving). */
    void releaseAudio() {
        transactVoid(TR_EXIT_CUR_MODE, p -> p.writeInt(SRC_RADIO));
    }

    // ---- Commands ----------------------------------------------------------

    void sendKey(int key) {
        transactVoid(TR_SEND_RADIO_KEY, p -> p.writeInt(key));
    }

    /** Direct tune. FM freq in 10 kHz units (9130 = 91.30 MHz), AM in kHz. */
    void tune(int freq, boolean isFm) {
        transactVoid(TR_SEND_USER_FREQ, p -> { p.writeInt(freq); p.writeInt(isFm ? 1 : 0); });
    }

    // ---- Getters -----------------------------------------------------------

    int getFreq() { return transactInt(TR_GET_RADIO_FREQ); }
    int getBand() { return transactInt(TR_GET_RADIO_BAND); }
    int getValidMode() { return transactInt(TR_GET_VALID_MODE); }
    boolean getStereoIcon() { return transactBool(TR_GET_STEREO_ICON); }
    boolean getRdsState() { return transactBool(TR_GET_RDS_STATE); }
    boolean getStMono() { return transactBool(TR_GET_ST_MONO_STATE); }
    boolean getDxLoc() { return transactBool(TR_GET_DX_LOC_STATE); }

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
        } catch (Exception ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int transactInt(int code) {
        IBinder s = service;
        if (s == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            s.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean transactBool(int code) {
        return transactInt(code) == 1;
    }
}
