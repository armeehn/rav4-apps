/*
 * The cabin-mic denoiser: RNNoise with its "little" model compiled into the library.
 *
 *   ns_open() or ns_open_blob() ──▶ ns_frame() per 10 ms at 48 kHz, in place ──▶ ns_close()
 *
 * A frame is NS_FRAME floats in int16 scale (±32768). The output lags the input by exactly
 * NS_DELAY_FRAMES frames (20 ms): one for the overlap-add window, one because RNNoise applies
 * each frame's gains to the frame before it (its pitch filter looks ahead).
 * No call allocates except ns_open().
 */
#ifndef NS_ENGINE_H
#define NS_ENGINE_H

#define NS_RATE 48000
#define NS_FRAME 480
#define NS_DELAY_FRAMES 2

typedef struct NsEngine NsEngine;

/* NULL when the model blob does not parse or memory runs out. */
NsEngine *ns_open(void);

/*
 * The same denoiser with another model: a weight blob in the format of
 * weights/rnnoise_little.bin (ns-train/ makes them). The blob is copied. NULL when it does not
 * parse as a model of this shape, so a bad download costs a fallback, not a crash.
 */
NsEngine *ns_open_blob(const void *blob, int len);

/* Denoise one frame in place; returns RNNoise's voice probability, 0..1. */
float ns_frame(NsEngine *ns, float *frame);

void ns_close(NsEngine *ns);

#endif
