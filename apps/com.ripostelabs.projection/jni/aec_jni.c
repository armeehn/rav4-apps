/*
 * JNI face of aec_engine for com.ripostelabs.projection.ns.Aec3. The handle is the engine
 * pointer. Frames are pinned, not copied, so the audio loop allocates nothing.
 */
#include <jni.h>
#include <stdint.h>

#include "aec_engine.h"

#define STAT_ERLE_DB 0
#define STAT_ERL_DB 1
#define STAT_DELAY_MS 2
#define STATS 3

JNIEXPORT jlong JNICALL
Java_com_ripostelabs_projection_ns_Aec3_nativeOpen(JNIEnv *env, jclass cls, jint rate) {
    (void) env;
    (void) cls;
    return (jlong) (intptr_t) aec_open(rate);
}

JNIEXPORT jint JNICALL
Java_com_ripostelabs_projection_ns_Aec3_nativeRender(JNIEnv *env, jclass cls, jlong handle,
                                                     jshortArray far, jint rate, jint channels) {
    (void) cls;
    if (handle == 0 || rate <= 0 || channels <= 0
        || (*env)->GetArrayLength(env, far) < rate / AEC_FRAMES_PER_SECOND * channels) {
        return -1;
    }

    jshort *pcm = (*env)->GetPrimitiveArrayCritical(env, far, NULL);
    if (pcm == NULL) {
        return -1;
    }
    int r = aec_render((AecEngine *) (intptr_t) handle, pcm, rate, channels);
    (*env)->ReleasePrimitiveArrayCritical(env, far, pcm, JNI_ABORT);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_ripostelabs_projection_ns_Aec3_nativeCapture(JNIEnv *env, jclass cls, jlong handle,
                                                      jshortArray near) {
    (void) cls;
    if (handle == 0) {
        return -1;
    }

    jshort *pcm = (*env)->GetPrimitiveArrayCritical(env, near, NULL);
    if (pcm == NULL) {
        return -1;
    }
    int r = aec_capture((AecEngine *) (intptr_t) handle, pcm);
    (*env)->ReleasePrimitiveArrayCritical(env, near, pcm, 0);
    return r;
}

JNIEXPORT void JNICALL
Java_com_ripostelabs_projection_ns_Aec3_nativeStats(JNIEnv *env, jclass cls, jlong handle,
                                                    jfloatArray out) {
    (void) cls;
    if (handle == 0 || (*env)->GetArrayLength(env, out) < STATS) {
        return;
    }

    AecStats s;
    aec_stats((AecEngine *) (intptr_t) handle, &s);
    jfloat v[STATS];
    v[STAT_ERLE_DB] = s.erle_db;
    v[STAT_ERL_DB] = s.erl_db;
    v[STAT_DELAY_MS] = (jfloat) s.delay_ms;
    (*env)->SetFloatArrayRegion(env, out, 0, STATS, v);
}

JNIEXPORT void JNICALL
Java_com_ripostelabs_projection_ns_Aec3_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    aec_close((AecEngine *) (intptr_t) handle);
}
