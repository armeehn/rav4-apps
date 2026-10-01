#!/usr/bin/env python3
"""Score weight blobs on the held-out clips, and decide whether the candidate ships.

    ns_eval.py WORK NS_FILE DRY_MIX NAME=BLOB [NAME=BLOB...]

Each noisy 16 kHz clip goes the car's way: up to 48 kHz, through ns_file (the app's C
denoiser with that blob, mixed at Medium), back to 16 kHz. Scores against the clean clip:
PESQ-WB (ITU-T P.862.2), STOI, SI-SNR. Names "baseline" and "car-tuned" are the gate:
car-tuned ships when its mean PESQ beats the baseline's and its mean STOI is not lower, on
the owner's held-out drives when there are any, else on the public held-out noise.

Writes WORK/eval/metrics.json, WORK/eval/table.md and WORK/eval/samples/.
"""
import json
import os
import subprocess
import sys
from multiprocessing import Pool

import numpy as np
import soundfile as sf
from pesq import pesq
from pystoi import stoi
from scipy.signal import resample_poly

MIC_RATE = 16000
UP = 3
S16_MAX = 32767
# ns_engine.h NS_DELAY_FRAMES x NS_FRAME, at 48 kHz.
DELAY_48K = 2 * 480
# STOI is reported to 3 decimals; a smaller move is no move.
STOI_TOLERANCE = 0.0005
# Listening samples: one reader, these SNRs, every noise.
SAMPLE_SNRS = (0, 5)

NS_FILE = DRY_MIX = None


def denoise(blob, noisy):
    x48 = np.clip(resample_poly(noisy, UP, 1) * S16_MAX, -S16_MAX, S16_MAX).astype("<i2")
    pad = np.zeros(DELAY_48K, dtype="<i2")
    out = subprocess.run([NS_FILE, blob, DRY_MIX], input=np.concatenate([x48, pad]).tobytes(),
                         capture_output=True, check=True).stdout
    y48 = np.frombuffer(out, dtype="<i2").astype(np.float64)[DELAY_48K:] / S16_MAX
    y = resample_poly(y48, 1, UP)
    n = min(len(y), len(noisy))
    return y[:n]


def si_snr(ref, est):
    n = min(len(ref), len(est))
    ref, est = ref[:n] - np.mean(ref[:n]), est[:n] - np.mean(est[:n])
    s = np.dot(est, ref) / (np.dot(ref, ref) + 1e-12) * ref
    return float(10 * np.log10(np.sum(s ** 2) / (np.sum((est - s) ** 2) + 1e-12)))


def scores(clean, x):
    n = min(len(clean), len(x))
    return {"pesq": float(pesq(MIC_RATE, clean[:n], x[:n], "wb")),
            "stoi": float(stoi(clean[:n], x[:n], MIC_RATE, extended=False)),
            "si_snr": si_snr(clean, x)}


def one(job):
    root, clip, models = job
    clean, _ = sf.read(os.path.join(root, clip["id"] + "_clean.wav"), dtype="float64")
    noisy, _ = sf.read(os.path.join(root, clip["id"] + "_noisy.wav"), dtype="float64")
    row = dict(clip, noisy=scores(clean, noisy))
    outs = {}
    for name, blob in models:
        y = denoise(blob, noisy)
        row[name] = scores(clean, y)
        outs[name] = y
    return row, outs


def mean(rows, name, key, **where):
    v = [r[name][key] for r in rows if all(r[k] == w for k, w in where.items())]
    return float(np.mean(v)) if v else float("nan")


def table(rows, names):
    lines = ["| Noise | SNR in | System | PESQ-WB | STOI | SI-SNR | SI-SNR gain |",
             "|---|---|---|---|---|---|---|"]
    for noise in sorted({r["noise"] for r in rows}):
        for snr in sorted({r["snr"] for r in rows}):
            base = mean(rows, "noisy", "si_snr", noise=noise, snr=snr)
            for name in ["noisy"] + names:
                p = mean(rows, name, "pesq", noise=noise, snr=snr)
                s = mean(rows, name, "stoi", noise=noise, snr=snr)
                q = mean(rows, name, "si_snr", noise=noise, snr=snr)
                gain = "" if name == "noisy" else f"{q - base:+.2f}"
                lines.append(f"| {noise} | {snr} dB | {name} | {p:.2f} | {s:.3f} | {q:.2f} | {gain} |")
    return "\n".join(lines)


def gate(rows):
    group = "owner" if any(r["group"] == "owner" for r in rows) else "public"
    g = [r for r in rows if r["group"] == group]
    d_pesq = mean(g, "car-tuned", "pesq") - mean(g, "baseline", "pesq")
    d_stoi = mean(g, "car-tuned", "stoi") - mean(g, "baseline", "stoi")
    return {"group": group, "clips": len(g),
            "pesq_baseline": mean(g, "baseline", "pesq"), "pesq_candidate": mean(g, "car-tuned", "pesq"),
            "stoi_baseline": mean(g, "baseline", "stoi"), "stoi_candidate": mean(g, "car-tuned", "stoi"),
            "delta_pesq": d_pesq, "delta_stoi": d_stoi,
            "pass": bool(d_pesq > 0 and d_stoi >= -STOI_TOLERANCE)}


def main():
    global NS_FILE, DRY_MIX
    work, NS_FILE, DRY_MIX = sys.argv[1], sys.argv[2], sys.argv[3]
    models = [tuple(a.split("=", 1)) for a in sys.argv[4:]]
    root = os.path.join(work, "set")
    out = os.path.join(work, "eval")
    os.makedirs(os.path.join(out, "samples"), exist_ok=True)
    index = json.load(open(os.path.join(root, "index.json")))

    with Pool(int(os.environ.get("NS_JOBS", "8"))) as pool:
        results = pool.map(one, [(root, c, models) for c in index])
    rows = [r for r, _ in results]
    names = [n for n, _ in models]

    # Listening samples: the first reader, a couple of SNRs, every noise.
    reader = sorted({r["id"].split("_")[1] for r in rows})[0]
    for (row, outs) in results:
        if row["snr"] not in SAMPLE_SNRS or row["id"].split("_")[1] != reader:
            continue
        base = os.path.join(out, "samples", row["id"])
        for k, src in (("1-clean", "_clean.wav"), ("2-noisy", "_noisy.wav")):
            x, _ = sf.read(os.path.join(root, row["id"] + src))
            sf.write(f"{base}_{k}.wav", x, MIC_RATE, subtype="PCM_16")
        for i, name in enumerate(n for n in names if n != "baseline"):
            sf.write(f"{base}_{i + 3}-{name}.wav", np.clip(outs[name], -1, 1), MIC_RATE, subtype="PCM_16")

    verdict = gate(rows)
    shown = [n for n in names if n != "baseline"] if dict(models).get("baseline") == dict(models).get("stock") else names
    json.dump({"verdict": verdict, "models": dict(models), "clips": rows}, open(os.path.join(out, "metrics.json"), "w"), indent=1)
    open(os.path.join(out, "table.md"), "w").write(table(rows, shown) + "\n")
    print(table(rows, shown))
    print(json.dumps(verdict, indent=1))


if __name__ == "__main__":
    main()
