/*
 * The denoiser does its one job: steady noise comes out much quieter, a voiced tone comes out
 * nearly whole. Runs on the build host (test.sh), the same sources and model the .so carries.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ns_engine.h"

#define SECONDS 3
#define FRAMES (SECONDS * NS_RATE / NS_FRAME)
/* Judge only the last second: the network needs a moment to settle on a noise floor. */
#define JUDGED_FRAMES (NS_RATE / NS_FRAME)
#define NOISE_RMS 3000.0
/*
 * A voiced tone: a 150 Hz harmonic series with a slow pitch wobble, switched on and off in
 * 4 Hz syllables. A perfectly steady tone is exactly what RNNoise is trained to remove (a fan,
 * an engine order), so "a tone survives" has to mean a tone shaped like a voice.
 */
#define TONE_F0 150.0
#define TONE_WOBBLE_HZ 3.0
#define TONE_WOBBLE_DEPTH 0.08
#define TONE_HARMONICS 10
#define TONE_SYLLABLE_HZ 4.0
#define TONE_PEAK 8000.0
#define MIN_NOISE_DROP_DB 15.0
#define MAX_TONE_LOSS_DB 6.0
#define OPEN_CLOSE_CYCLES 50

static int failures;

static void check(int ok, const char *what, double value);
static void dirty_heap(void);

#define NOISE_SEED 12345u
static unsigned int seed = NOISE_SEED;

/* Uniform in [-1, 1) from a fixed LCG, so every host makes the same noise. */
static double noise_sample(void) {
    seed = seed * 1103515245u + 12345u;
    return ((seed >> 8) & 0xFFFF) / 32768.0 - 1.0;
}

static double tone_phase;

static double tone_sample(long n) {
    double t = (double) n / NS_RATE;
    double f0 = TONE_F0 * (1.0 + TONE_WOBBLE_DEPTH * sin(2.0 * M_PI * TONE_WOBBLE_HZ * t));
    tone_phase += 2.0 * M_PI * f0 / NS_RATE;

    // Half of each syllable period is voiced, with a raised-cosine envelope.
    double cycle = fmod(t * TONE_SYLLABLE_HZ, 1.0);
    double envelope = cycle < 0.5 ? 0.5 - 0.5 * cos(4.0 * M_PI * cycle) : 0.0;

    double s = 0.0;
    for (int k = 1; k <= TONE_HARMONICS; k++) {
        s += sin(k * tone_phase) / k;
    }
    return s * envelope;
}

typedef double (*Source)(long n);

static double noise_source(long n) {
    (void) n;
    // sqrt(3) turns a uniform [-1, 1) into unit RMS.
    return noise_sample() * sqrt(3.0) * NOISE_RMS;
}

static double tone_source(long n) {
    return tone_sample(n) * TONE_PEAK / 2.0;
}

/* A downloaded model's bytes: the shipped blob read from disk (argv[1]). */
static unsigned char *blob;
static long blob_len;

typedef NsEngine *(*Opener)(void);

static NsEngine *open_blob(void) {
    return ns_open_blob(blob, (int) blob_len);
}

/* Energy ratio out/in over the judged tail, in dB. */
static double pass_db_with(Source source, Opener opener) {
    NsEngine *ns = opener();
    if (ns == NULL) {
        printf("FAIL the engine did not open\n");
        exit(1);
    }

    float frame[NS_FRAME];
    double in_energy = 0.0;
    double out_energy = 0.0;
    long n = 0;
    for (int f = 0; f < FRAMES; f++) {
        double e = 0.0;
        for (int i = 0; i < NS_FRAME; i++) {
            frame[i] = (float) source(n++);
            e += (double) frame[i] * frame[i];
        }
        ns_frame(ns, frame);
        if (f < FRAMES - JUDGED_FRAMES) {
            continue;
        }
        in_energy += e;
        for (int i = 0; i < NS_FRAME; i++) {
            out_energy += (double) frame[i] * frame[i];
        }
    }
    ns_close(ns);
    return 10.0 * log10((out_energy + 1e-9) / (in_energy + 1e-9));
}

static double pass_db(Source source) {
    return pass_db_with(source, ns_open);
}

static void read_blob(const char *path) {
    FILE *f = fopen(path, "rb");
    if (f == NULL) {
        printf("FAIL cannot read %s\n", path);
        exit(1);
    }
    fseek(f, 0, SEEK_END);
    blob_len = ftell(f);
    fseek(f, 0, SEEK_SET);
    blob = malloc((size_t) blob_len);
    if (blob == NULL || fread(blob, 1, (size_t) blob_len, f) != (size_t) blob_len) {
        printf("FAIL short read of %s\n", path);
        exit(1);
    }
    fclose(f);
}

/*
 * A model from bytes (the car-tuned download) must run like the embedded one when it is the
 * same model, and must be refused, not crash, when it is not a model of this shape.
 */
#define GARBAGE_LEN 65536
static void blob_checks(void) {
    // The same noise for both: the generator restarts from its seed.
    seed = NOISE_SEED;
    double embedded = pass_db(noise_source);
    seed = NOISE_SEED;
    double loaded = pass_db_with(noise_source, open_blob);
    check(fabs(embedded - loaded) < 0.01, "blob copy of the shipped model matches it (dB apart)", embedded - loaded);

    unsigned char *junk = malloc(GARBAGE_LEN);
    for (int i = 0; i < GARBAGE_LEN; i++) {
        junk[i] = (unsigned char) (noise_sample() * 127.0);
    }
    check(ns_open_blob(junk, GARBAGE_LEN) == NULL, "random bytes are refused", 0);
    free(junk);

    check(ns_open_blob(blob, (int) (blob_len / 2)) == NULL, "a truncated blob is refused", blob_len / 2);
    check(ns_open_blob(blob, 0) == NULL, "an empty blob is refused", 0);
    check(ns_open_blob(NULL, 16) == NULL, "no blob is refused", 0);

    for (int i = 0; i < OPEN_CLOSE_CYCLES; i++) {
        dirty_heap();
        ns_close(open_blob());
    }
    check(1, "blob open/close cycles on a dirty heap", OPEN_CLOSE_CYCLES);
}

/*
 * The engine's delay is what ns_engine.h promises: the Java side delays the dry mix by exactly
 * NS_DELAY_FRAMES, and a wrong figure comb-filters the voice. Found by cross-correlating the
 * voiced tone in and out.
 */
#define LAG_SEARCH (4 * NS_FRAME)
#define LAG_TOLERANCE 2
static int measured_lag(void) {
    static float in[FRAMES * NS_FRAME];
    static float out[FRAMES * NS_FRAME];
    NsEngine *ns = ns_open();
    tone_phase = 0.0;
    for (int f = 0; f < FRAMES; f++) {
        float *frame = &out[f * NS_FRAME];
        for (int i = 0; i < NS_FRAME; i++) {
            in[f * NS_FRAME + i] = frame[i] = (float) tone_source(f * NS_FRAME + i);
        }
        ns_frame(ns, frame);
    }
    ns_close(ns);

    int best_lag = 0;
    double best = -1.0;
    int n = FRAMES * NS_FRAME - LAG_SEARCH;
    for (int lag = 0; lag < LAG_SEARCH; lag++) {
        double c = 0.0;
        for (int i = 0; i < n; i++) {
            c += (double) in[i] * out[i + lag];
        }
        if (c > best) {
            best = c;
            best_lag = lag;
        }
    }
    return best_lag;
}

/*
 * Leave freed small blocks full of junk, as a long-running app's heap is. A fresh test
 * process hands out zeroed memory, which hid a teardown bug that crashed the JVM: upstream's
 * rnnoise_model_from_buffer() leaves a FILE pointer uninitialised and rnnoise_model_free()
 * fcloses it.
 */
#define JUNK_BLOCKS 64
#define JUNK_SIZE 32
static void dirty_heap(void) {
    void *blocks[JUNK_BLOCKS];
    for (int i = 0; i < JUNK_BLOCKS; i++) {
        blocks[i] = malloc(JUNK_SIZE);
        memset(blocks[i], 0xA5, JUNK_SIZE);
    }
    for (int i = 0; i < JUNK_BLOCKS; i++) {
        free(blocks[i]);
    }
}

static void check(int ok, const char *what, double value) {
    printf("%s %s: %+.1f\n", ok ? "ok  " : "FAIL", what, value);
    if (!ok) {
        failures++;
    }
}

int main(int argc, char **argv) {
    if (argc != 2) {
        printf("usage: %s weights/rnnoise_little.bin\n", argv[0]);
        return 2;
    }
    read_blob(argv[1]);
    double noise = pass_db(noise_source);
    check(noise <= -MIN_NOISE_DROP_DB, "noise only is attenuated (dB)", noise);

    double tone = pass_db(tone_source);
    check(tone >= -MAX_TONE_LOSS_DB, "voiced tone survives (dB)", tone);

    int lag = measured_lag();
    check(abs(lag - NS_DELAY_FRAMES * NS_FRAME) <= LAG_TOLERANCE, "delay is NS_DELAY_FRAMES (samples)", lag);

    // One engine per Siri request or call: opening and closing must not leak or crash.
    for (int i = 0; i < OPEN_CLOSE_CYCLES; i++) {
        dirty_heap();
        ns_close(ns_open());
    }
    check(1, "open/close cycles on a dirty heap", OPEN_CLOSE_CYCLES);

    blob_checks();

    return failures == 0 ? 0 : 1;
}
