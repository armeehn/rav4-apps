#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "ns_engine.h"
#include "rnnoise.h"

/*
 * The model weights, embedded at build time from weights/rnnoise_little.bin (see
 * vendor-rnnoise.sh). 64-byte aligned because the parser maps the int8 arrays in place.
 * Hidden, so only this library sees them.
 */
__asm__(
    ".section .rodata\n"
    ".balign 64\n"
    ".hidden ns_weights\n"
    ".globl ns_weights\n"
    "ns_weights:\n"
    ".incbin \"rnnoise_little.bin\"\n"
    ".hidden ns_weights_end\n"
    ".globl ns_weights_end\n"
    "ns_weights_end:\n"
    ".previous\n");

extern const unsigned char ns_weights[];
extern const unsigned char ns_weights_end[];

/* The parser maps int8 arrays in place, so a loaded blob needs the .incbin's alignment. */
#define BLOB_ALIGN 64

struct NsEngine {
    DenoiseState *state;
    /* A loaded model's weights (ns_open_blob); the state's layers point into it. NULL for the
     * embedded model. */
    void *blob;
};

/*
 * One model handle for the process, made once and never freed. Upstream's
 * rnnoise_model_from_buffer() leaves the handle's FILE pointer uninitialised and
 * rnnoise_model_free() fcloses it, which crashes on a used heap (test/ns_test.c). The handle
 * is only a view of the static blob; each engine's layers are its own.
 */
static RNNModel *model;
static pthread_once_t model_once = PTHREAD_ONCE_INIT;

static void model_init(void) {
    model = rnnoise_model_from_buffer(ns_weights, (int) (ns_weights_end - ns_weights));
}

NsEngine *ns_open(void) {
    pthread_once(&model_once, model_init);
    if (model == NULL) {
        return NULL;
    }

    NsEngine *ns = calloc(1, sizeof(*ns));
    if (ns == NULL) {
        return NULL;
    }
    ns->state = rnnoise_create(model);
    if (ns->state == NULL) {
        free(ns);
        return NULL;
    }
    return ns;
}

float ns_frame(NsEngine *ns, float *frame) {
    // In place is safe: RNNoise high-passes the input into its own buffer before writing out.
    return rnnoise_process_frame(ns->state, frame, frame);
}

NsEngine *ns_open_blob(const void *blob, int len) {
    if (blob == NULL || len <= 0) {
        return NULL;
    }

    NsEngine *ns = calloc(1, sizeof(*ns));
    if (ns == NULL) {
        return NULL;
    }
    // posix_memalign, not aligned_alloc: bionic has the latter only from API 28.
    if (posix_memalign(&ns->blob, BLOB_ALIGN, (size_t) len) != 0) {
        free(ns);
        return NULL;
    }
    memcpy(ns->blob, blob, (size_t) len);

    // The handle is only needed while the layers are mapped. Freed with free(), not
    // rnnoise_model_free(), which fcloses a FILE pointer this constructor never sets.
    RNNModel *model = rnnoise_model_from_buffer(ns->blob, len);
    if (model == NULL) {
        ns_close(ns);
        return NULL;
    }
    ns->state = rnnoise_create(model);
    free(model);
    if (ns->state == NULL) {
        ns_close(ns);
        return NULL;
    }
    return ns;
}

void ns_close(NsEngine *ns) {
    if (ns == NULL) {
        return;
    }
    if (ns->state != NULL) {
        rnnoise_destroy(ns->state);
    }
    free(ns->blob);
    free(ns);
}
