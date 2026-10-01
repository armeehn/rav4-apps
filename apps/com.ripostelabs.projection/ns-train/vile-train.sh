#!/usr/bin/env bash
# The fine-tune on vile's GPU (GTX 1650), between forge's mix and forge's export. Run on x,
# the one host that reaches both (forge cannot see vile).
#
#   forge: features.f32, upstream torch code, shipped checkpoint ──ssh|ssh──▶ vile
#   vile:  docker pytorch (CUDA) ns_finetune.py ──▶ best.pth, history.json ──▶ forge work/train/
#
#   vile-train.sh FORGE_WORK_DIR
#
# Nothing is installed on TrueNAS: the stock pytorch image runs the script from a bind mount.
# The card is shared with Jellyfin's NVENC and Ollama; batch 16 needs about 1.5 GB of its
# 3.6 GB (32 runs out).
set -euo pipefail

FORGE_WORK="$1"
HERE="$(cd "$(dirname "$0")" && pwd)"

readonly FORGE=forge
readonly VILE=${VILE_SSH:-vile}
readonly ROOT=/mnt/solid-state/ns-train
readonly IMAGE=pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime
# The checkpoint fine-tuning starts from: forge-run.sh's LITTLE_PTH, under upstream/.
readonly LITTLE_PTH=models/rnnoise10Gb_15.pth
# Below this much free VRAM the run would OOM or starve a transcode: wait for the next day.
readonly MIN_FREE_MIB=2000

LR=${NS_LR:-1e-4}
EPOCHS=${NS_EPOCHS:-6}
BATCH=${NS_BATCH:-16}

log() { echo "[vile-train $(date +%H:%M:%S)] $*"; }

free=$(ssh "$VILE" nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
[ "$free" -ge "$MIN_FREE_MIB" ] || { log "only $free MiB free on vile's GPU (need $MIN_FREE_MIB)"; exit 1; }

# 1. Inputs, streamed through x without touching its disk.
ssh "$VILE" "mkdir -p $ROOT/kit $ROOT/torch $ROOT/train"
tar -C "$HERE" -cf - ns_finetune.py | ssh "$VILE" "tar -C $ROOT/kit -xf -"
ssh "$FORGE" "tar -C $FORGE_WORK/upstream -cf - torch $LITTLE_PTH" | ssh "$VILE" "tar -C $ROOT/torch -xf -"
for f in features.f32 features_val.f32; do
    # Same size on both sides means the same mix (forge rewrites it only when the noise changes).
    a=$(ssh "$FORGE" "stat -c %s $FORGE_WORK/$f")
    b=$(ssh "$VILE" "stat -c %s $ROOT/$f 2>/dev/null || echo 0")
    if [ "$a" != "$b" ] || ! ssh "$FORGE" "cat $FORGE_WORK/features.stamp" | ssh "$VILE" "cmp -s - $ROOT/features.stamp"; then
        log "copying $f ($((a / 1024 / 1024)) MB)"
        ssh "$FORGE" "cat $FORGE_WORK/$f" | ssh "$VILE" "cat > $ROOT/$f"
    fi
done
ssh "$FORGE" "cat $FORGE_WORK/features.stamp" | ssh "$VILE" "cat > $ROOT/features.stamp"

# 2. Train.
log "training on vile: lr $LR, $EPOCHS epochs, batch $BATCH"
ssh "$VILE" "rm -rf $ROOT/train && mkdir -p $ROOT/train && docker run --rm --gpus all --shm-size=2g \
    -v $ROOT:/w -e PYTHONPATH=/w/torch/torch/rnnoise:/w/torch/torch \
    $IMAGE python /w/kit/ns_finetune.py --init /w/torch/$LITTLE_PTH \
    --features /w/features.f32 --val /w/features_val.f32 --out /w/train \
    --lr $LR --epochs $EPOCHS --batch-size $BATCH"

# 3. Back to forge for export and eval.
ssh "$FORGE" "rm -rf $FORGE_WORK/train && mkdir -p $FORGE_WORK/train"
ssh "$VILE" "tar -C $ROOT/train -cf - best.pth history.json" | ssh "$FORGE" "tar -C $FORGE_WORK/train -xf -"
log "best.pth on forge"
