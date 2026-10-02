package com.ripostelabs.projection.ns;

import android.util.Log;

/**
 * WebRTC AEC3 (BSD-3-Clause), from {@code jni/} as {@code libaec3_jni.so}. Optional like
 * RNNoise: when the library is missing or will not load, {@link #open} returns null and the
 * mic goes out without echo cancellation.
 */
public final class Aec3 implements Canceller {

    private static final String TAG = "Projection";
    private static final String LIBRARY = "aec3_jni";
    private static final boolean LOADED = load();

    private long handle;

    private Aec3(long handle) {
        this.handle = handle;
    }

    /** A fresh canceller for a mono mic at {@code rate}, or null when the library is not usable. */
    public static Aec3 open(int rate) {
        if (!LOADED) {
            return null;
        }
        long h = nativeOpen(rate);
        return h == 0 ? null : new Aec3(h);
    }

    public static boolean available() {
        return LOADED;
    }

    @Override
    public void render(short[] far, int rate, int channels) {
        nativeRender(handle, far, rate, channels);
    }

    @Override
    public void capture(short[] near) {
        nativeCapture(handle, near);
    }

    @Override
    public void stats(float[] out) {
        nativeStats(handle, out);
    }

    @Override
    public void close() {
        if (handle == 0) {
            return;
        }
        nativeClose(handle);
        handle = 0;
    }

    private static boolean load() {
        try {
            System.loadLibrary(LIBRARY);
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            log("echo canceller unavailable: " + e.getMessage());
            return false;
        }
    }

    /** android.util.Log is a stub off the device; the JVM tests reach this path. */
    private static void log(String line) {
        try {
            Log.w(TAG, line);
        } catch (RuntimeException stub) {
            System.err.println(line);
        }
    }

    private static native long nativeOpen(int rate);

    private static native int nativeRender(long handle, short[] far, int rate, int channels);

    private static native int nativeCapture(long handle, short[] near);

    private static native void nativeStats(long handle, float[] out);

    private static native void nativeClose(long handle);
}
