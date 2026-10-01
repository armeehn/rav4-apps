/*
 * JNI face of ns_engine for com.ripostelabs.projection.ns.RnNoise. The handle is the engine
 * pointer. nativeFrame pins the Java array instead of copying it, so the audio loop allocates
 * nothing.
 */
#include <jni.h>
#include <stdint.h>

#include "ns_engine.h"

JNIEXPORT jlong JNICALL
Java_com_ripostelabs_projection_ns_RnNoise_nativeOpen(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return (jlong) (intptr_t) ns_open();
}

JNIEXPORT jlong JNICALL
Java_com_ripostelabs_projection_ns_RnNoise_nativeOpenBlob(JNIEnv *env, jclass cls, jbyteArray blob) {
    (void) cls;
    if (blob == NULL) {
        return 0;
    }

    jsize len = (*env)->GetArrayLength(env, blob);
    jbyte *bytes = (*env)->GetByteArrayElements(env, blob, NULL);
    if (bytes == NULL) {
        return 0;
    }
    NsEngine *ns = ns_open_blob(bytes, (int) len);
    (*env)->ReleaseByteArrayElements(env, blob, bytes, JNI_ABORT);
    return (jlong) (intptr_t) ns;
}

JNIEXPORT jfloat JNICALL
Java_com_ripostelabs_projection_ns_RnNoise_nativeFrame(JNIEnv *env, jclass cls, jlong handle,
                                                       jfloatArray frame) {
    (void) cls;
    if (handle == 0 || (*env)->GetArrayLength(env, frame) < NS_FRAME) {
        return -1.0f;
    }

    float *pcm = (*env)->GetPrimitiveArrayCritical(env, frame, NULL);
    if (pcm == NULL) {
        return -1.0f;
    }
    float vad = ns_frame((NsEngine *) (intptr_t) handle, pcm);
    (*env)->ReleasePrimitiveArrayCritical(env, frame, pcm, 0);
    return vad;
}

JNIEXPORT jint JNICALL
Java_com_ripostelabs_projection_ns_RnNoise_nativeFrameSize(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return NS_FRAME;
}

JNIEXPORT jint JNICALL
Java_com_ripostelabs_projection_ns_RnNoise_nativeDelayFrames(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return NS_DELAY_FRAMES;
}

JNIEXPORT void JNICALL
Java_com_ripostelabs_projection_ns_RnNoise_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    ns_close((NsEngine *) (intptr_t) handle);
}
