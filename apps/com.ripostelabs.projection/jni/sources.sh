# The one list of what goes into the denoiser, shared by build.sh (NDK) and test.sh (host cc).
# USE_WEIGHTS_FILE: the model comes from the embedded blob, not compiled-in arrays.
# RNNOISE_BUILD: export the rnnoise_* symbols the engine calls.
NS_CFLAGS="-DUSE_WEIGHTS_FILE -DRNNOISE_BUILD -DDISABLE_DEBUG_FLOAT -fvisibility=hidden"

# Echo the C sources, given the jni directory.
ns_sources() {
    local d="$1/rnnoise/src"
    echo "$1/ns_engine.c" \
        "$d/denoise.c" "$d/rnn.c" "$d/pitch.c" "$d/kiss_fft.c" "$d/celt_lpc.c" "$d/nnet.c" \
        "$d/nnet_default.c" "$d/parse_lpcnet_weights.c" "$d/rnnoise_tables.c" \
        "$d/rnnoise_data_little.c" \
        -I"$1/rnnoise/include" -I"$d"
}
