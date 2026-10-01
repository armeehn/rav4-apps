#!/usr/bin/env bash
# The heavy half of train-car-model.sh, run on forge (CPU only, no GPU). Steps, each
# skipped when its output already exists, so a re-run only redoes what changed:
#
#   setup    upstream RNNoise at the app's pinned commit + model, dump_features, torch venv;
#            proves the export path by re-exporting the shipped little model byte for byte
#   corpora  LibriSpeech dev/test-clean, DEMAND (4 scenes), archive.org car interiors
#   data     16 kHz -> 48 kHz raw training audio, owner noise split by drive, eval clips
#   mix      dump_features: speech + car noise at -5..20 dB SNR -> features.f32
#   train    fine-tune the shipped little model (sparse mask kept), small LR. CPU fallback
#            only: train-car-model.sh trains on vile's GPU (vile-train.sh) instead
#   export   checkpoint -> C arrays -> weight blob in the .incbin format
#   eval     stock vs car-tuned on the held-out clips, through the app's own C code
#
#   forge-run.sh KIT WORK [step...]     (no step: all of them, training on forge's CPU)
#
# KIT holds this directory and ../jni (pushed by train-car-model.sh); WORK keeps state.
set -euo pipefail

KIT="$1"
WORK="$2"
shift 2
STEPS="${*:-setup corpora data mix train export eval}"

# Pins come from the vendoring script, so training and the app can never disagree.
VENDOR="$KIT/jni/vendor-rnnoise.sh"
COMMIT=$(sed -n 's/^readonly COMMIT=//p' "$VENDOR")
SRC_SHA256=$(sed -n 's/^readonly SRC_SHA256=//p' "$VENDOR")
MODEL=$(sed -n 's/^readonly MODEL=//p' "$VENDOR")
SHIPPED="$KIT/jni/rnnoise/weights/rnnoise_little.bin"

# The little model's checkpoint inside the model tarball: the sparse one of the two. Its
# export is the shipped blob (setup proves it).
readonly LITTLE_PTH=models/rnnoise10Gb_15.pth

# Training mix. Upstream draws noise at about -25..+30 dB SNR; the car needs -5..20.
readonly SNR_MIN=-5
readonly SNR_MAX=20
# 20 s sequences; 4000 is 22 h of mixes and 3.1 GB of features.
readonly SEQUENCES=${NS_SEQUENCES:-4000}
readonly VAL_SEQUENCES=${NS_VAL_SEQUENCES:-200}
readonly JOBS=${NS_JOBS:-8}

# Fine-tuning: a tenth of upstream's LR, a few epochs.
readonly LR=${NS_LR:-1e-4}
readonly EPOCHS=${NS_EPOCHS:-6}

# Medium, the app's default strength: Strength.MEDIUM.dryMix() = 10^(-24/20).
readonly DRY_MIX=0.0630957

UP="$WORK/upstream"
PY="$WORK/venv/bin/python"
TORCH_PATH="$UP/torch/rnnoise:$UP/torch/weight-exchange:$UP/torch"

log() { echo "[forge-run $(date +%H:%M:%S)] $*"; }

# Fetch URL to FILE once.
fetch() {
    [ -s "$2" ] && return 0
    curl -sSfL --retry 3 -o "$2.part" "$1"
    mv "$2.part" "$2"
}

step_setup() {
    mkdir -p "$WORK/dl"
    if [ ! -f "$UP/model_version" ]; then
        fetch "https://github.com/xiph/rnnoise/archive/$COMMIT.tar.gz" "$WORK/dl/src.tar.gz"
        fetch "https://media.xiph.org/rnnoise/models/rnnoise_data-$MODEL.tar.gz" "$WORK/dl/model.tar.gz"
        echo "$SRC_SHA256  $WORK/dl/src.tar.gz" | sha256sum -c --quiet
        echo "$MODEL  $WORK/dl/model.tar.gz" | sha256sum -c --quiet
        mkdir -p "$UP"
        tar xzf "$WORK/dl/src.tar.gz" -C "$UP" --strip-components=1
        tar xzf "$WORK/dl/model.tar.gz" -C "$UP"
    fi

    if [ ! -x "$WORK/venv/bin/python" ]; then
        python3 -m venv "$WORK/venv"
        "$WORK/venv/bin/pip" -q install --index-url https://download.pytorch.org/whl/cpu torch
        "$WORK/venv/bin/pip" -q install numpy scipy soundfile tqdm pesq pystoi
    fi

    # dump_features with the car's SNR range in place of upstream's noise level draw.
    local src="$WORK/dump_features_car.c"
    sed "s|noise_gain = pow(10., (-30+randf(40.f)+randf(15.f))/20.);|noise_gain = pow(10., -($SNR_MIN+randf($SNR_MAX-($SNR_MIN)))/20.);|" \
        "$UP/src/dump_features.c" > "$src"
    grep -q "randf($SNR_MAX" "$src" || { log "SNR patch did not apply"; exit 1; }
    cc -O3 -march=native -w -DTRAINING -I"$UP" -I"$UP/include" -I"$UP/src" "$src" "$UP/src/denoise.c" \
        "$UP/src/pitch.c" "$UP/src/celt_lpc.c" "$UP/src/kiss_fft.c" "$UP/src/parse_lpcnet_weights.c" \
        "$UP/src/rnnoise_tables.c" -o "$WORK/dump_features" -lm

    # The app's denoiser on files, from the app's own sources and flags.
    # shellcheck source=../jni/sources.sh
    . "$KIT/jni/sources.sh"
    local d="$KIT/jni/rnnoise/src"
    cc -O2 -w $NS_CFLAGS -I"$KIT/jni" -I"$KIT/jni/rnnoise/include" -I"$d" "$KIT/ns-train/ns_file.c" \
        "$d/denoise.c" "$d/rnn.c" "$d/pitch.c" "$d/kiss_fft.c" "$d/celt_lpc.c" "$d/nnet.c" \
        "$d/nnet_default.c" "$d/parse_lpcnet_weights.c" "$d/rnnoise_tables.c" \
        "$d/rnnoise_data_little.c" -o "$WORK/ns_file" -lm

    # Proof the export path is the app's format: the shipped checkpoint must come back as
    # the shipped blob, byte for byte.
    export_blob "$UP/$LITTLE_PTH" "$WORK/roundtrip"
    cmp "$WORK/roundtrip/weights_blob.bin" "$SHIPPED"
    log "setup ok: export of $LITTLE_PTH == shipped rnnoise_little.bin"
}

# Checkpoint PTH -> DIR/weights_blob.bin, the way vendor-rnnoise.sh makes the shipped one.
export_blob() {
    local pth="$1" dir="$2"
    rm -rf "$dir"
    mkdir -p "$dir"
    PYTHONPATH="$TORCH_PATH" "$PY" "$UP/torch/rnnoise/dump_rnnoise_weights.py" --quantize \
        "$pth" "$dir" > "$dir/dump.log"
    # write_weights.c includes "rnnoise_data.c" from its own directory, so it must sit
    # beside the export, or it silently picks upstream's full model.
    cp "$UP/src/write_weights.c" "$dir/"
    cc -O1 -w -DDISABLE_DEBUG_FLOAT -I"$dir" -I"$UP/include" -I"$UP/src" "$dir/write_weights.c" \
        "$UP/src/parse_lpcnet_weights.c" -o "$dir/write_weights" -lm
    (cd "$dir" && ./write_weights)
}

step_corpora() {
    local dl="$WORK/dl"
    local ls=https://www.openslr.org/resources/12
    local demand=https://zenodo.org/records/1227121/files
    local veh=https://archive.org/download/Designers-Choice-Collection-Vehicles/VEHICLES

    for split in dev-clean test-clean; do
        [ -d "$WORK/corpora/LibriSpeech/$split" ] && continue
        fetch "$ls/$split.tar.gz" "$dl/$split.tar.gz"
        mkdir -p "$WORK/corpora"
        tar xzf "$dl/$split.tar.gz" -C "$WORK/corpora"
        rm -f "$dl/$split.tar.gz"
    done

    for scene in TCAR STRAFFIC SPSQUARE TBUS; do
        [ -d "$WORK/corpora/demand/$scene" ] && continue
        fetch "$demand/${scene}_16k.zip" "$dl/$scene.zip"
        mkdir -p "$WORK/corpora/demand"
        "$PY" -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
            "$dl/$scene.zip" "$WORK/corpora/demand"
        rm -f "$dl/$scene.zip"
    done

    mkdir -p "$WORK/corpora/cc0"
    fetch "$veh/INTERIOR/VEHInt-Samsung%20Galaxy%20Smartphone%2C%20CU_Truck%2C%20Driving%2C%20Speeding_Nicholas%20Judy_TDC.flac" \
        "$WORK/corpora/cc0/truck_speeding.flac"
    fetch "https://archive.org/download/CarInteriorAmbienceDrivingUpAForestServiceRoad/FSR033.flac" \
        "$WORK/corpora/cc0/forest_road.flac"
    log "corpora ok: $(du -sh "$WORK/corpora" | cut -f1)"
}

step_data() {
    "$PY" "$KIT/ns-train/ns_data.py" "$WORK" "$WORK/road-noise"
}

# N processes of dump_features, COUNT sequences each, into OUT.
dump() {
    local count="$1" out="$2"
    local per=$(( (count + JOBS - 1) / JOBS ))
    local d="$WORK/data"
    rm -f "$out".part.*
    for i in $(seq 1 "$JOBS"); do
        nice -n 10 "$WORK/dump_features" "$d/speech_train.sw" "$d/noise_train.sw" \
            "$d/noise_fg.sw" "$out.part.$i" "$per" > /dev/null &
    done
    wait
    cat "$out".part.* > "$out"
    rm -f "$out".part.*
}

step_mix() {
    local d="$WORK/data"
    # Owner noise changes the mix, so the features are tied to the noise list's hash.
    local stamp
    stamp=$(sha256sum "$d/noise_train.list" | cut -c1-16)
    if [ "$(cat "$WORK/features.stamp" 2>/dev/null)" = "$stamp-$SEQUENCES" ]; then
        log "mix: features up to date"
        return 0
    fi
    dump "$SEQUENCES" "$WORK/features.f32"
    dump "$VAL_SEQUENCES" "$WORK/features_val.f32"
    echo "$stamp-$SEQUENCES" > "$WORK/features.stamp"
    log "mix ok: $(du -h "$WORK/features.f32" | cut -f1)"
}

step_train() {
    rm -rf "$WORK/train"
    PYTHONPATH="$TORCH_PATH" nice -n 10 "$PY" "$KIT/ns-train/ns_finetune.py" \
        --init "$UP/$LITTLE_PTH" --features "$WORK/features.f32" --val "$WORK/features_val.f32" \
        --out "$WORK/train" --lr "$LR" --epochs "$EPOCHS"
}

step_export() {
    export_blob "$WORK/train/best.pth" "$WORK/export"
    cp "$WORK/export/weights_blob.bin" "$WORK/rnnoise_car.bin"
    log "export ok: $(stat -c %s "$WORK/rnnoise_car.bin") bytes, sha256 $(sha256sum "$WORK/rnnoise_car.bin" | cut -c1-16)"
}

step_eval() {
    # BASELINE: the model the car runs now (the last published car model, or stock).
    local base="${NS_BASELINE:-$SHIPPED}"
    "$PY" "$KIT/ns-train/ns_eval.py" "$WORK" "$WORK/ns_file" "$DRY_MIX" \
        "stock=$SHIPPED" "baseline=$base" "car-tuned=$WORK/rnnoise_car.bin"
}

for s in $STEPS; do
    log "== $s"
    "step_$s"
done
