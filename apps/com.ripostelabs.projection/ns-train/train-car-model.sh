#!/usr/bin/env bash
# Re-train the car-tuned RNNoise model from the owner's road-noise captures: one command.
#
#   road-noise/index.jsonl ──▶ forge: ingest, mix ──▶ vile GPU: fine-tune ──▶ forge: export, eval
#                                                                                  │
#   share mic-ns/car-tuned/: report, blob, samples ◀───────────────────────────────┘
#        │ gate: PESQ up, STOI not down vs the model in the car (--publish / --auto only)
#        ▼
#   share models/rnnoise/<version>/weights.bin + manifest.json ──ingest /v1/models──▶ the car
#   (and a copy at launcher.hq/ns-model/rnnoise/ for the emulator farm)
#
#   train-car-model.sh             full run, report to the share, publish nothing
#   train-car-model.sh --publish   the same, then publish if the gate passes
#   train-car-model.sh --auto      the daily timer: only when >= NS_MIN_NEW_MIN minutes of new
#                                  owner noise arrived since the last run; publish if it wins;
#                                  mail on publish or failure, silence otherwise
#
# Runs on x (the share and the launcher webroot live here, and only x reaches both forge and
# vile). Heavy steps run on forge (forge-run.sh) and vile's GPU (vile-train.sh). Needs root
# for the LXC 104 webroot.
set -Eeuo pipefail

readonly MODE="${1:-report}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(dirname "$HERE")"

readonly FORGE=forge
readonly FORGE_DIR=ns-train
readonly ROAD=/z1-pool/share/carlauncher/road-noise
readonly REPORT=/z1-pool/share/carlauncher/mic-ns/car-tuned
# CONTRACT.md section 5: the car fetches these through the ingest service (GET /v1/models/).
readonly MODELS=/z1-pool/share/carlauncher/models/rnnoise
readonly SHARE_OWNER=sasha:smbshare
# The same files for the emulator farm, which is not on the tailnet: launcher.hq's webroot
# (LXC 104 rootfs is disk-1), owned by root inside the CT.
readonly WEB=/z1-pool/subvol-104-disk-1/var/www/launcher/ns-model/rnnoise
readonly WEB_OWNER=100000:100000
readonly URL=https://launcher.hq.ripostelabs.xyz/ns-model/rnnoise
readonly STATE=/var/lib/rav4-ns-train
readonly MAIL_TO=sasha+rav4@ripostelabs.xyz
readonly NS_MIN_NEW_MIN=${NS_MIN_NEW_MIN:-10}

log() { echo "[train-car-model $(date '+%F %T')] $*"; }

# Mail only under the timer: a hand run reports to its terminal.
notify() {
    [ "$MODE" = "--auto" ] || return 0
    printf '%s\n' "$2" | rl-mail --to "$MAIL_TO" --subject "$1" || log "mail failed"
}

fail() {
    log "FAILED: $1"
    echo "$(date '+%F %T') FAILED $1" >> "$REPORT/runs.log" 2>/dev/null || true
    notify "Car NS model training FAILED" "$1. Log: journalctl -u rav4-ns-train"
    exit 1
}
trap 'fail "line $LINENO exited $?"' ERR

report() { python3 "$HERE/ns_report.py" "$@"; }

# install(1) a file as OWNER (user:group), mode 664, making its directory.
put() {
    local src=$1 dest=$2 owner=$3
    install -d -o "${owner%:*}" -g "${owner#*:}" -m 2775 "$(dirname "$dest")"
    install -m 664 -o "${owner%:*}" -g "${owner#*:}" "$src" "$dest"
}

mkdir -p "$STATE" "$REPORT"
exec 9> /run/rav4-ns-train.lock
flock -n 9 || { log "another run holds the lock"; exit 0; }

# The car keeps capturing until each band has its target (CONTRACT.md section 4).
report wants > "$STATE/wants.json"
put "$STATE/wants.json" "$ROAD/wants.json" "$SHARE_OWNER"

if [ "$MODE" = "--auto" ]; then
    fresh=$(report new-minutes "$ROAD" "$STATE/owner.trained")
    if [ -f "$STATE/owner.trained" ] && [ "$fresh" -lt "$NS_MIN_NEW_MIN" ]; then
        log "only $fresh min of new owner noise (need $NS_MIN_NEW_MIN); nothing to do"
        exit 0
    fi
    log "$fresh min of new owner noise: retraining"
fi

# 1. Push the kit (this directory + the app's jni sources) and the captures to forge.
ssh "$FORGE" "mkdir -p $FORGE_DIR/kit $FORGE_DIR/work/road-noise"
rsync -a --delete "$HERE" "$APP/jni" "$FORGE:$FORGE_DIR/kit/"
if [ -d "$ROAD" ]; then
    rsync -a --delete "$ROAD/" "$FORGE:$FORGE_DIR/work/road-noise/"
fi

# The gate's baseline is the model the car runs now: the published one, else stock.
baseline=""
parent=$(report blob-path "$MODELS/manifest.json")
if [ -n "$parent" ]; then
    scp -q "$MODELS/$parent" "$FORGE:$FORGE_DIR/work/baseline.bin"
    baseline="NS_BASELINE=\$HOME/$FORGE_DIR/work/baseline.bin"
fi

# 2. Mix on forge (CPU), fine-tune on vile (GPU), export and score on forge.
log "forge: $(ssh "$FORGE" uptime)"
# NS_* knobs set here (see README) go along.
knobs=$(env | grep -E '^NS_[A-Z_]+=[0-9.e-]+$' | grep -v '^NS_MIN_NEW_MIN=' | tr '\n' ' ' || true)
forge_run() {
    ssh "$FORGE" "$knobs $baseline bash $FORGE_DIR/kit/ns-train/forge-run.sh \$HOME/$FORGE_DIR/kit \$HOME/$FORGE_DIR/work $*"
}
forge_run setup corpora data mix
"$HERE/vile-train.sh" "$(ssh "$FORGE" 'echo $HOME')/$FORGE_DIR/work"
forge_run export eval

# 3. Report on the share.
rm -rf "$REPORT/samples"
rsync -a "$FORGE:$FORGE_DIR/work/eval/" "$REPORT/"
for f in rnnoise_car.bin train/history.json data/owner.json data/noise_train.list; do
    scp -q "$FORGE:$FORGE_DIR/work/$f" "$REPORT/"
done
report readme "$REPORT" > "$REPORT/README.md"
report owner-seconds "$ROAD" > "$STATE/owner.trained"

read -r result group dpesq dstoi <<< "$(report verdict "$REPORT/metrics.json")"
log "gate: $result on $group noise (PESQ $dpesq, STOI $dstoi vs the car's model)"
echo "$(date '+%F %T') $result $group pesq$dpesq stoi$dstoi" >> "$REPORT/runs.log"

# 3b. Rollback: the car follows the estate's default (owner-approved 2026-10-01). When the
#     model it runs now lost to stock on the owner's own noise, republish its blob with the
#     default back on standard, so cars on Automatic return to the shipped model.
if [ "$MODE" != "report" ] && [ "$result" != "pass" ] \
    && [ "$(report field "$MODELS/manifest.json" default)" = car-tuned ] \
    && [ "$(report regressed "$REPORT/metrics.json")" = yes ]; then
    version=$(report next-version "$MODELS/manifest.json")
    current=$(report blob-path "$MODELS/manifest.json")
    report rollback "$version" "$MODELS/manifest.json" > "$STATE/manifest.json"
    for root in "$MODELS:$SHARE_OWNER" "$WEB:$WEB_OWNER"; do
        dir=${root%%:*}
        owner=${root#*:}
        put "$MODELS/$current" "$dir/$version/weights.bin" "$owner"
        put "$STATE/manifest.json" "$dir/manifest.json" "$owner"
    done
    log "rolled back: $version restores the standard default"
    notify "Car NS model rolled back ($version)" "$(cat "$STATE/manifest.json")"
    exit 0
fi

if [ "$MODE" = "report" ] || [ "$result" != "pass" ]; then
    exit 0
fi

# 4. Publish: the version folder first, manifest.json last, so the car never sees a manifest
#    that names a missing file. The default is the gate's: car-tuned only on an owner win.
version=$(report next-version "$MODELS/manifest.json")
report manifest "$REPORT" "$version" "$MODELS/manifest.json" > "$STATE/manifest.json"
for root in "$MODELS:$SHARE_OWNER" "$WEB:$WEB_OWNER"; do
    dir=${root%%:*}
    owner=${root#*:}
    put "$REPORT/rnnoise_car.bin" "$dir/$version/weights.bin" "$owner"
    put "$STATE/manifest.json" "$dir/manifest.json" "$owner"
done

want=$(sha256sum < "$REPORT/rnnoise_car.bin" | cut -d' ' -f1)
got=$(curl -sf "$URL/$version/weights.bin" | sha256sum | cut -d' ' -f1)
[ "$want" = "$got" ] || fail "launcher.hq serves a different weights.bin for $version"
log "published $version: $MODELS and $URL"
notify "Car NS model $version published" "$(cat "$STATE/manifest.json")"
