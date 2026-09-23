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
 * The {@link Tuner} backend for the vendor stack (Riposte OS 0.1 and stock): a hand-rolled
 * Binder client for the vendor event gateway
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
final class VendorTuner extends Tuner {

    private static final String SERVICE_ACTION = "com.szchoiceway.eventcenter.EventService";
    static final String SERVICE_PACKAGE = "com.szchoiceway.eventcenter";
    private static final String DESCRIPTOR = "com.szchoiceway.eventcenter.IEventService";
    private static final String CALLBACK_DESCRIPTOR = "com.szchoiceway.eventcenter.ICallbackfn";

    // IEventService transaction codes (decompiled Stub constants).
    private static final int TR_SEND_MODE = 1;
    private static final int TR_SEND_RADIO_KEY = 2;
    private static final int TR_SEND_USER_FREQ = 6;
    private static final int TR_GET_RADIO_FREQ = 12;
    private static final int TR_GET_RADIO_FREQ_LIST = 13;
    private static final int TR_GET_RADIO_BAND = 14;
    private static final int TR_GET_RADIO_PTY_NUM = 18;
    private static final int TR_GET_RDS_STATE = 16;
    // Named PTY in the stub, but it returns mRadioPSName: the RDS PS station name
    // from the MCU PS frame (EventService.java:2836-2842, getter :7510-7512).
    private static final int TR_GET_RADIO_PTY_NAME = 19;
    private static final int TR_GET_ST_MONO_STATE = 22;
    private static final int TR_GET_DX_LOC_STATE = 23;
    private static final int TR_GET_AMS_STATE = 24;
    private static final int TR_GET_APS_STATE = 25;
    private static final int TR_GET_STEREO_ICON = 26;
    private static final int TR_SET_RADIO_CALLBACK = 29;
    private static final int TR_SET_CUR_MODE_CALLBACK = 30;
    private static final int TR_EXIT_CUR_MODE = 31;
    private static final int TR_GET_VALID_MODE = 46;

    // The gateway relays a raw MCU body from this broadcast (EvtModel.java:364-373 →
    // EventService.sendCmdData); the vendor radio sends its preset cmds 100/101 this way.
    private static final String ACTION_MCU_CMD = "com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT";
    private static final String EXTRA_MCU_CMD = "EventUtils.MCU_CMD_DATA";
    private static final byte OP_RADIO_KEY = 0x02;
    private static final byte CMD_PRESET_SELECT = 100;
    private static final byte CMD_PRESET_STORE = 101;
    /** The gateway keeps the zone in its own settings provider, unread here: the unit's plan is North America. */
    private static final int ZONE_NORTH_AMERICA = 1;
    private static final int STATION_LIST_SIZE = 42;

    // ICallbackfn transaction codes.
    private static final int CB_NOTIFY_EVT = 1;
    private static final int CB_CHECK_IS_ACTIVE = 2;

    // Radio-callback event ids (EventService.notifyRadioEvt): 0 = status bits,
    // 3 = frequency in arg2, 6 = PS station name in str. Every one of them lands
    // in listener.onRadioEvent(), which re-polls the getters (getStationName()
    // included) instead of parsing the payload, so no dispatch on these here.
    private static final int EVT_RADIO_STATUS = 0;
    private static final int EVT_RADIO_FREQ = 3;
    private static final int EVT_RADIO_PS_NAME = 6;

    // Mode-callback event ids (vendor MainActivity handler).
    private static final int EVT_RECLAIM = 254;
    private static final int EVT_REFRESH = 255;
    private static final int EVT_MODE_CHANGE = 4097;

    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder service;
    private boolean bound;

    VendorTuner(Context context, Listener listener) {
        super(context, listener);
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

    @Override boolean bind() {
        if (bound) return true;
        Intent intent = new Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE);
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            bound = false;
        }
        return bound;
    }

    @Override void unbind() {
        if (!bound) return;
        try { context.unbindService(connection); } catch (Exception ignored) {}
        bound = false;
        service = null;
    }

    @Override boolean isConnected() { return service != null && service.isBinderAlive(); }

    // ---- Mode / audio path -------------------------------------------------

    /** Claim the tuner audio source, exactly as the vendor radio app does. */
    @Override void claimAudio() {
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
    @Override void releaseAudio() {
        transactVoid(TR_EXIT_CUR_MODE, p -> p.writeInt(SRC_RADIO));
    }

    // ---- Commands ----------------------------------------------------------

    @Override void sendKey(int key) {
        transactVoid(TR_SEND_RADIO_KEY, p -> p.writeInt(key));
    }

    /** Direct tune. FM freq in 10 kHz units (9130 = 91.30 MHz), AM in kHz. */
    @Override void tune(int freq, boolean isFm) {
        transactVoid(TR_SEND_USER_FREQ, p -> { p.writeInt(freq); p.writeInt(isFm ? 1 : 0); });
    }

    // ---- Getters -----------------------------------------------------------

    @Override int getFreq() { return transactInt(TR_GET_RADIO_FREQ); }
    @Override int getBand() { return transactInt(TR_GET_RADIO_BAND); }
    @Override int getValidMode() { return transactInt(TR_GET_VALID_MODE); }
    @Override boolean getStereoIcon() { return transactBool(TR_GET_STEREO_ICON); }
    @Override boolean getRdsState() { return transactBool(TR_GET_RDS_STATE); }
    @Override boolean getStMono() { return transactBool(TR_GET_ST_MONO_STATE); }
    @Override boolean getDxLoc() { return transactBool(TR_GET_DX_LOC_STATE); }
    /** RDS PS station name; empty until the MCU sends a PS frame. */
    @Override String getStationName() { return transactString(TR_GET_RADIO_PTY_NAME); }
    @Override int getPty() { return Math.max(0, transactInt(TR_GET_RADIO_PTY_NUM)); }
    @Override boolean isScanning() { return transactBool(TR_GET_APS_STATE); }
    @Override boolean isAutoStoring() { return transactBool(TR_GET_AMS_STATE); }
    @Override int getZone() { return ZONE_NORTH_AMERICA; }
    @Override int[] getStationList() { return transactIntArray(TR_GET_RADIO_FREQ_LIST); }

    @Override void selectPreset(int slot) { sendMcuCmd(CMD_PRESET_SELECT, slot); }
    @Override void storePreset(int slot) { sendMcuCmd(CMD_PRESET_STORE, slot); }

    /** sendRadioCmd (vendor MainActivity.java:614-619): {02, cmd, arg} as a broadcast the gateway forwards. */
    private void sendMcuCmd(byte cmd, int arg) {
        byte[] body = {OP_RADIO_KEY, cmd, (byte) (arg & 0xFF)};
        context.sendBroadcast(new Intent(ACTION_MCU_CMD).putExtra(EXTRA_MCU_CMD, body));
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

    private int[] transactIntArray(int code) {
        IBinder s = service;
        if (s == null) return new int[STATION_LIST_SIZE];
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            s.transact(code, data, reply, 0);
            reply.readException();
            int[] list = reply.createIntArray();
            return list == null ? new int[STATION_LIST_SIZE] : list;
        } catch (Exception e) {
            return new int[STATION_LIST_SIZE];
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private String transactString(int code) {
        IBinder s = service;
        if (s == null) return "";
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            s.transact(code, data, reply, 0);
            reply.readException();
            String str = reply.readString();
            return str == null ? "" : str;
        } catch (Exception e) {
            return "";
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
