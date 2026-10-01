#!/usr/bin/env python3
"""Training audio and held-out clips for the car-tuned RNNoise model.

    ns_data.py WORK ROAD_NOISE_DIR

Everything passes through 16 kHz first, then up to 48 kHz, because that is the car's path:
the CarPlay mic is 16 kHz and NsPipeline upsamples it for RNNoise. A model tuned on that
band-limited audio is tuned to what it will hear.

Writes, under WORK/data:
  speech_train.sw    LibriSpeech dev-clean, 48 kHz s16 (dump_features input)
  noise_train.sw     background noise for the mix: owner training drives, then public car noise
  noise_fg.sw        "foreground" noise (upstream adds it to 1 in 8 mixes): the same car noise
  noise_train.list   what went into noise_train.sw, one line per source (hashes the mix)
  owner.json         owner captures: drives, tags, minutes, which drives are held out
Under WORK/set: held-out clips, 16 kHz WAV, clean + noisy, with index.json.

Owner captures: ROAD_NOISE_DIR/index.jsonl and the files it names, per road-noise/CONTRACT.md
(48 kHz mono WAV, road-noise/1 sidecar). Test rows (source or band "test", device
car-farm-test) are skipped. A drive is one device's captures less than 30 min apart. The
latest drives, at least a fifth of the minutes, are held out for the test.
"""
import glob
import hashlib
import json
import os
import sys
from datetime import datetime, timezone

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

MIC_RATE = 16000
NS_RATE = 48000
UP = NS_RATE // MIC_RATE
S16_MAX = 32767

# Public car noise, DEMAND (CC BY 4.0): 4 of its 16 array channels per scene for variety.
# TCAR and STRAFFIC only: SPSQUARE and TBUS carry voices. With them the tuned model was gentler
# on speech-like sound (PESQ -0.067 vs stock), without them -0.015. NS_DEMAND_SCENES overrides.
DEMAND_SCENES = tuple(os.environ.get("NS_DEMAND_SCENES", "TCAR STRAFFIC").split())
DEMAND_CHANNELS = (1, 5, 9, 13)
# The last minute of TCAR is held out of training and used as a test noise.
TCAR_HOLDOUT_S = 60

# Rows the trainer must skip (CONTRACT.md section 7): the emulator farm's proofs.
TEST_DEVICES = {"car-farm-test"}
# Captures further apart than this are different drives.
DRIVE_GAP_S = 30 * 60

# Owner noise counts double in the mix: it is the noise the model is for.
OWNER_REPEAT = 2
HOLDOUT_SHARE = 0.2

# Held-out clips: LibriSpeech test-clean readers never heard in training.
EVAL_SPEAKERS = 8
EVAL_CLIP_S = 6
EVAL_SNRS = (-5, 0, 5, 10)
SEED = 20261001


def load16(path, start_s=None, stop_s=None):
    """Any WAV/FLAC -> float32 mono at 16 kHz, through the car's sample rate."""
    x, rate = sf.read(path, dtype="float32", always_2d=True)
    x = x[:, 0]
    if start_s is not None or stop_s is not None:
        a = int((start_s or 0) * rate)
        b = len(x) if stop_s is None else int(stop_s * rate)
        x = x[a:b]
    if rate == MIC_RATE:
        return x
    g = np.gcd(rate, MIC_RATE)
    return resample_poly(x, MIC_RATE // g, rate // g).astype(np.float32)


def to48_s16(x16):
    """16 kHz float -> 48 kHz int16, as NsPipeline's upsampler feeds RNNoise."""
    y = resample_poly(x16, UP, 1)
    peak = np.max(np.abs(y)) + 1e-9
    # Level does not matter to dump_features (it normalises); avoid clipping only.
    gain = min(1.0, 0.9 / peak)
    return np.clip(y * gain * S16_MAX, -S16_MAX, S16_MAX).astype("<i2")


def is_test(row):
    """Emulator-farm proofs, not road noise (CONTRACT.md section 7)."""
    return (row.get("source") == "test" or row.get("band") == "test"
            or row.get("device") in TEST_DEVICES)


def when(row):
    """started_at (else received_at) as epoch seconds; 0 when neither parses."""
    for key in ("started_at", "received_at"):
        v = row.get(key)
        if not isinstance(v, str):
            continue
        try:
            return datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp()
        except ValueError:
            continue
    return 0.0


def label(row):
    """The capture's condition for the report: its tags ("Highway/Fan"), else its band."""
    tags = row.get("tags") or []
    if tags:
        return "/".join(str(t) for t in tags)
    return str(row.get("band") or "unknown")


def owner_captures(root):
    """Owner captures from index.jsonl, grouped into drives, split into train and test drives."""
    caps = []
    index = os.path.join(root, "index.jsonl")
    for line in open(index) if os.path.exists(index) else []:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        wav = os.path.join(root, row.get("path", ""))
        if is_test(row) or not os.path.isfile(wav):
            continue
        side = os.path.splitext(wav)[0] + ".json"
        try:
            meta = dict(row, **json.load(open(side)))
        except (OSError, ValueError):
            meta = row
        caps.append({"path": wav, "device": meta.get("device", "car"), "t": when(meta),
                     "tag": label(meta), "speed": meta.get("speed_kmh"),
                     "seconds": sf.info(wav).duration})

    # A drive: one device's captures with no gap longer than DRIVE_GAP_S between them.
    caps.sort(key=lambda c: (c["device"], c["t"]))
    drives, last = {}, None
    for c in caps:
        if last is None or c["device"] != last["device"] or c["t"] - last["t"] > DRIVE_GAP_S:
            name = f"{c['device']}-{datetime.fromtimestamp(c['t'], timezone.utc):%Y-%m-%dT%H%MZ}"
        c["drive"] = name
        drives.setdefault(name, []).append(c)
        last = c
    order = sorted(drives, key=lambda d: drives[d][0]["t"])
    total = sum(c["seconds"] for c in caps)

    # Latest drives go to the test until they hold a fifth of the minutes; one drive alone
    # cannot be split by drive, so it trains and the test stays public.
    test = []
    if len(order) >= 2:
        held = 0.0
        for d in reversed(order[1:]):
            test.append(d)
            held += sum(c["seconds"] for c in drives[d])
            if held >= HOLDOUT_SHARE * total:
                break
    for c in caps:
        c["split"] = "test" if c["drive"] in test else "train"

    tags = {}
    for c in caps:
        tags[c["tag"]] = tags.get(c["tag"], 0) + c["seconds"] / 60
    return {"captures": caps, "drives": order, "test_drives": sorted(test),
            "minutes": total / 60, "minutes_by_tag": tags}


def demand(work, scene, ch):
    return os.path.join(work, "corpora", "demand", scene, f"ch{ch:02d}.wav")


def write_noise(work, owner, d):
    parts, listing = [], []
    for c in owner["captures"]:
        if c["split"] != "train":
            continue
        x = load16(c["path"])
        parts += [x] * OWNER_REPEAT
        listing.append(f"owner {c['drive']} {c['tag']} {c['path']} {c['seconds']:.1f}")

    for scene in DEMAND_SCENES:
        for ch in DEMAND_CHANNELS:
            stop = -TCAR_HOLDOUT_S if scene == "TCAR" else None
            x = load16(demand(work, scene, ch))
            if stop:
                x = x[: stop * MIC_RATE]
            parts.append(x)
            listing.append(f"demand {scene} ch{ch:02d}")

    noise = np.concatenate(parts)
    to48_s16(noise).tofile(os.path.join(d, "noise_train.sw"))
    to48_s16(noise).tofile(os.path.join(d, "noise_fg.sw"))
    open(os.path.join(d, "noise_train.list"), "w").write("\n".join(listing) + "\n")
    print(f"noise: {len(noise) / MIC_RATE / 60:.1f} min from {len(listing)} sources")


def write_speech(work, d):
    out = os.path.join(d, "speech_train.sw")
    if os.path.exists(out):
        return
    files = sorted(glob.glob(os.path.join(work, "corpora", "LibriSpeech", "dev-clean", "*", "*", "*.flac")))
    x = np.concatenate([load16(f) for f in files])
    to48_s16(x).tofile(out)
    print(f"speech: {len(x) / MIC_RATE / 3600:.1f} h from {len(files)} utterances")


def active_rms(x):
    """Speech level over 20 ms frames within 40 dB of the loudest (P.56 in spirit)."""
    n = MIC_RATE // 50
    f = x[: len(x) // n * n].reshape(-1, n).astype(np.float64)
    e = np.sqrt(np.mean(f ** 2, axis=1)) + 1e-12
    return float(np.sqrt(np.mean(f[e > e.max() * 0.01] ** 2)))


def test_noises(work, owner):
    """Held-out noise per set name. Owner test drives by tag; else the public set."""
    sets = {}
    for c in owner["captures"]:
        if c["split"] == "test":
            name = "owner-" + c["tag"].lower().replace("/", "-")
            sets.setdefault(name, []).append(load16(c["path"]))
    owner_sets = {k: np.concatenate(v) for k, v in sets.items()}

    tcar = np.concatenate([load16(demand(work, "TCAR", ch))[-TCAR_HOLDOUT_S * MIC_RATE:]
                           for ch in DEMAND_CHANNELS])
    public = {"tcar-holdout": tcar,
              "truck": load16(os.path.join(work, "corpora", "cc0", "truck_speeding.flac")),
              "gravel": load16(os.path.join(work, "corpora", "cc0", "forest_road.flac"))}
    return owner_sets, public


def write_set(work, owner):
    rng = np.random.default_rng(SEED)
    root = os.path.join(work, "set")
    os.makedirs(root, exist_ok=True)

    speakers = sorted(os.listdir(os.path.join(work, "corpora", "LibriSpeech", "test-clean")))
    picks = rng.choice(speakers, EVAL_SPEAKERS, replace=False)
    n = EVAL_CLIP_S * MIC_RATE
    speech = []
    for spk in picks:
        for f in sorted(glob.glob(os.path.join(work, "corpora", "LibriSpeech", "test-clean", spk, "*", "*.flac"))):
            x = load16(f)
            if len(x) >= n:
                speech.append((f"{spk}-{os.path.basename(f)[:-5]}", x[:n]))
                break

    owner_sets, public = test_noises(work, owner)
    index = []
    for group, sets in (("owner", owner_sets), ("public", public)):
        for name, noise in sets.items():
            for sid, clean in speech:
                for snr in EVAL_SNRS:
                    a = rng.integers(0, len(noise) - n)
                    nz = noise[a:a + n].astype(np.float64)
                    nz *= active_rms(clean) / (np.sqrt(np.mean(nz ** 2)) + 1e-12) * 10 ** (-snr / 20)
                    noisy = clean + nz
                    peak = max(np.max(np.abs(noisy)), np.max(np.abs(clean))) + 1e-9
                    g = min(1.0, 0.9 / peak)
                    cid = f"{name}_{sid}_{snr}dB"
                    sf.write(os.path.join(root, cid + "_clean.wav"), clean * g, MIC_RATE, subtype="PCM_16")
                    sf.write(os.path.join(root, cid + "_noisy.wav"), noisy * g, MIC_RATE, subtype="PCM_16")
                    index.append({"id": cid, "group": group, "noise": name, "snr": snr})
    json.dump(index, open(os.path.join(root, "index.json"), "w"), indent=1)
    print(f"set: {len(index)} clips, owner sets {sorted(owner_sets)}, public {sorted(public)}")


def main():
    work, road = sys.argv[1], sys.argv[2]
    d = os.path.join(work, "data")
    os.makedirs(d, exist_ok=True)

    owner = owner_captures(road) if os.path.isdir(road) else {
        "captures": [], "drives": [], "test_drives": [], "minutes": 0, "minutes_by_tag": {}}
    json.dump(owner, open(os.path.join(d, "owner.json"), "w"), indent=1)
    print(f"owner: {owner['minutes']:.1f} min, {len(owner['drives'])} drives, "
          f"held out {owner['test_drives']}")

    write_speech(work, d)
    write_noise(work, owner, d)
    write_set(work, owner)
    digest = hashlib.sha256(open(os.path.join(d, "noise_train.list"), "rb").read()).hexdigest()[:16]
    print(f"data ok: noise list {digest}")


if __name__ == "__main__":
    main()
