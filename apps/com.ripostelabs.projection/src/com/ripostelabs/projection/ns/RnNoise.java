package com.ripostelabs.projection.ns;

import android.util.Log;

/**
 * RNNoise (Xiph, BSD-3-Clause) with its little model, from {@code jni/} as
 * {@code librnnoise_jni.so}. The library is optional: when it is missing or will not load,
 * {@link #open} returns null and the mic goes out unprocessed.
 */
public final class RnNoise implements Engine {

    private static final String TAG = "Projection";
    private static final String LIBRARY = "rnnoise_jni";
    private static final boolean LOADED = load();

    private final int delayFrames;
    private long handle;

    private RnNoise(long handle) {
        this.handle = handle;
        this.delayFrames = nativeDelayFrames();
    }

    /** A fresh denoiser, or null when the native library is not usable. */
    public static RnNoise open() {
        if (!LOADED || nativeFrameSize() != FRAME) {
            return null;
        }
        long h = nativeOpen();
        return h == 0 ? null : new RnNoise(h);
    }

    /**
     * A fresh denoiser on another model's weights (a car-tuned download), or null when the
     * library is not usable or the blob is not a model of this shape.
     */
    public static RnNoise open(byte[] blob) {
        if (!LOADED || blob == null || nativeFrameSize() != FRAME) {
            return null;
        }
        long h = nativeOpenBlob(blob);
        return h == 0 ? null : new RnNoise(h);
    }

    public static boolean available() {
        return LOADED;
    }

    @Override
    public void frame(float[] pcm) {
        nativeFrame(handle, pcm);
    }

    @Override
    public int delayFrames() {
        return delayFrames;
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
            log("noise suppressor unavailable: " + e.getMessage());
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

    private static native long nativeOpen();

    private static native long nativeOpenBlob(byte[] blob);

    private static native float nativeFrame(long handle, float[] pcm);

    private static native int nativeFrameSize();

    private static native int nativeDelayFrames();

    private static native void nativeClose(long handle);
}
