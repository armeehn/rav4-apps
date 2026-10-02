/*
 * The cabin-mic echo canceller: WebRTC AEC3 (BSD-3-Clause, the webrtc-audio-processing
 * release pinned in aec-build.sh), linked statically into libaec3_jni.so.
 *
 *   phone's downlink ──▶ aec_render()  ┐ one 10 ms frame each, in the order they were played
 *   cabin mic        ──▶ aec_capture() ┘ ──▶ the mic with the downlink's echo removed
 *
 * AEC3 finds the downlink-to-mic delay itself (its delay estimator covers about 500 ms) and
 * runs its own double-talk handling: the suppressor backs off while the near end talks. Only
 * the echo canceller and the 80 Hz high-pass it is tuned with run; noise suppression is
 * RNNoise's job, after this. No call allocates except aec_open(), and a render format change.
 */
#ifndef AEC_ENGINE_H
#define AEC_ENGINE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AEC_FRAMES_PER_SECOND 100

typedef struct AecEngine AecEngine;

/* What AEC3 reports about the echo it is cancelling; a field is -1000 until it knows. */
typedef struct {
    float erle_db;      /* echo return loss enhancement: how much echo it removes */
    float erl_db;       /* echo return loss: how far below the downlink the echo arrives */
    int delay_ms;       /* the downlink-to-mic delay it found */
} AecStats;

#define AEC_UNKNOWN (-1000)

/* An echo canceller for a mono mic at capture_rate (8, 16, 32 or 48 kHz). NULL on failure. */
AecEngine *aec_open(int capture_rate);

/* One 10 ms downlink frame, interleaved s16 at its own rate and channel count. 0 on success. */
int aec_render(AecEngine *aec, const int16_t *far, int rate, int channels);

/* One 10 ms mono mic frame at the capture rate, echo removed in place. 0 on success. */
int aec_capture(AecEngine *aec, int16_t *near);

void aec_stats(AecEngine *aec, AecStats *out);

void aec_close(AecEngine *aec);

#ifdef __cplusplus
}
#endif

#endif
