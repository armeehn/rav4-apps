/*
 * Run the app's denoiser on a file, with any weight blob: the evaluation's stand-in for the
 * car. Built from the same jni/rnnoise sources and flags as librnnoise_jni.so, so the only
 * thing that differs between "stock" and "car-tuned" is the blob.
 *
 *   ns_file BLOB DRY_MIX < in.s16 > out.s16
 *
 * Audio is raw 16-bit mono at 48 kHz. DRY_MIX is Strength.dryMix() (Medium: 10^(-24/20)),
 * mixed with the input as late as the network's output, like NsPipeline does. The output
 * lags the input by NS_DELAY_FRAMES frames.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ns_engine.h"
#include "rnnoise.h"

/* The parser maps int8 arrays in place, so the blob needs the .incbin's alignment. */
#define BLOB_ALIGN 64
#define RING (NS_DELAY_FRAMES + 1)
#define S16_MAX 32767.0f

static void *read_blob(const char *path, int *len) {
    FILE *f = fopen(path, "rb");
    if (f == NULL) {
        return NULL;
    }

    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);

    // Rounded up: aligned_alloc wants a multiple of the alignment.
    void *buf = aligned_alloc(BLOB_ALIGN, (size_t) (n + BLOB_ALIGN - 1) / BLOB_ALIGN * BLOB_ALIGN);
    if (buf == NULL || fread(buf, 1, (size_t) n, f) != (size_t) n) {
        fclose(f);
        free(buf);
        return NULL;
    }
    fclose(f);
    *len = (int) n;
    return buf;
}

static short clip16(float x) {
    if (x > S16_MAX) {
        return (short) S16_MAX;
    }
    if (x < -S16_MAX) {
        return (short) -S16_MAX;
    }
    return (short) x;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s BLOB DRY_MIX < in.s16 > out.s16\n", argv[0]);
        return 2;
    }

    int len = 0;
    void *blob = read_blob(argv[1], &len);
    if (blob == NULL) {
        fprintf(stderr, "cannot read %s\n", argv[1]);
        return 1;
    }
    RNNModel *model = rnnoise_model_from_buffer(blob, len);
    DenoiseState *st = model == NULL ? NULL : rnnoise_create(model);
    if (st == NULL) {
        fprintf(stderr, "blob does not parse: %s\n", argv[1]);
        return 1;
    }

    float dry_mix = (float) atof(argv[2]);
    float wet = 1.0f - dry_mix;
    float dry[RING][NS_FRAME] = {{0}};
    int head = 0;
    short pcm[NS_FRAME];
    float frame[NS_FRAME];

    // One 10 ms frame at a time; a short tail is dropped, as the car's capture would.
    while (fread(pcm, sizeof(short), NS_FRAME, stdin) == NS_FRAME) {
        for (int i = 0; i < NS_FRAME; i++) {
            frame[i] = pcm[i];
        }
        memcpy(dry[head], frame, sizeof(frame));
        rnnoise_process_frame(st, frame, frame);

        // The dry frame as late as the network's output: the oldest in the ring.
        head = (head + 1) % RING;
        for (int i = 0; i < NS_FRAME; i++) {
            pcm[i] = clip16(wet * frame[i] + dry_mix * dry[head][i]);
        }
        fwrite(pcm, sizeof(short), NS_FRAME, stdout);
    }

    rnnoise_destroy(st);
    return 0;
}
