#!/usr/bin/env python3
"""Reports and small helpers for train-car-model.sh, which runs on x. Stdlib only.

    ns_report.py readme REPORT_DIR                       the share README.md
    ns_report.py verdict METRICS_JSON                    "pass|fail GROUP dPESQ dSTOI"
    ns_report.py owner-seconds ROAD_DIR                  "id seconds" per owner capture
    ns_report.py new-minutes ROAD_DIR TRAINED_LIST       owner minutes not yet trained on
    ns_report.py field MANIFEST KEY                      a manifest field ("" when absent)
    ns_report.py blob-path MANIFEST                      its weight file, relative to it
    ns_report.py next-version MANIFEST                   "YYYY-MM-DD.N", N counting up per day
    ns_report.py manifest REPORT_DIR VERSION PARENT_MANIFEST     manifest.json
    ns_report.py wants                                   wants.json for the car

Formats are road-noise/CONTRACT.md's (sections 3 to 5, 8). The manifest's "default" is
carried over from the previous one (first: standard). Training never changes it;
"owner_gate_pass" records whether this model won on the owner's own held-out drives, which
is the evidence a person needs to change it.
"""
import hashlib
import json
import os
import sys
import wave
from datetime import datetime, timezone

DEFAULT_STANDARD = "standard"
MODEL_NAME = "rnnoise"
BLOB_NAME = "weights.bin"
# The emulator farm's proofs (CONTRACT.md section 7); ns_data.py skips the same rows.
TEST_DEVICES = {"car-farm-test"}
# Seconds of road noise per band the car should capture (CONTRACT.md section 4).
WANTS = {"city": 3600, "highway": 3600, "city-fan": 1800, "highway-fan": 1800}


def load(report):
    m = json.load(open(os.path.join(report, "metrics.json")))
    owner = json.load(open(os.path.join(report, "owner.json")))
    hist = json.load(open(os.path.join(report, "history.json")))
    return m, owner, hist


def mean_of(clips, system, noise):
    v = [c[system]["pesq"] for c in clips if c["noise"] == noise]
    return sum(v) / len(v) if v else float("nan")


def readme(report):
    m, owner, hist = load(report)
    v = m["verdict"]
    table = open(os.path.join(report, "table.md")).read()
    best = min(hist["history"], key=lambda h: h["val"])
    tags = ", ".join(f"{t} {mins:.0f} min" for t, mins in sorted(owner["minutes_by_tag"].items())) or "none yet"
    noise = "the owner's held-out drives" if v["group"] == "owner" else "public car noise (no owner test drives yet)"
    status = "PASS" if v["pass"] else "FAIL"
    per_noise = ", ".join(
        f"{n} {mean_of(m['clips'], 'car-tuned', n) - mean_of(m['clips'], 'baseline', n):+.3f}"
        for n in sorted({c['noise'] for c in m['clips']}))
    return f"""# Car-tuned RNNoise

Gate: **{status}** on {noise}, {v['clips']} clips. Car-tuned vs the model in the car:
PESQ-WB {v['pesq_baseline']:.3f} -> {v['pesq_candidate']:.3f} ({v['delta_pesq']:+.3f}),
STOI {v['stoi_baseline']:.4f} -> {v['stoi_candidate']:.4f} ({v['delta_stoi']:+.4f}).
Ship rule: PESQ up and STOI not down. PESQ change per noise: {per_noise}.

Run {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC. Re-run with
`apps/com.ripostelabs.projection/ns-train/train-car-model.sh` on x (rav4-apps).

## Data

- Owner noise: {owner['minutes']:.1f} min over {len(owner['drives'])} drives ({tags}).
  Held out for the test: {', '.join(owner['test_drives']) or 'none (needs 2+ drives)'}.
- Public noise: DEMAND scenes in `noise_train.list` (CC BY 4.0), last 60 s of TCAR held out.
  Test also uses two archive.org car interiors (CC0): "truck" and "gravel".
- Speech: LibriSpeech dev-clean for training, test-clean readers for the test (CC BY 4.0).
- Mix: upstream dump_features at -5..20 dB SNR, all audio through 16 kHz first like the car.
- Training: fine-tuned from the shipped little model, sparse mask kept, LR {hist['lr']},
  best epoch {best['epoch']} of {hist['epochs']} (held-back loss {best['val']:.5f}, epoch 0 = shipped).

## Numbers

Every clip ran through the app's own C denoiser (jni/rnnoise) with each blob, at Medium.
"stock" is the shipped little model; "baseline" (when shown) is the last published model.

{table}
## Listen

`samples/`: per clip `_1-clean`, `_2-noisy`, `_3-stock`, `_4-car-tuned`.

## Files

- `rnnoise_car.bin`: the candidate blob, same format as `jni/rnnoise/weights/rnnoise_little.bin`.
- `metrics.json` (per clip), `history.json` (training), `owner.json` (captures and split),
  `noise_train.list` (what the mix used), `runs.log` (one line per run).
"""


def owner_seconds(road):
    """Owner captures in the index, test rows skipped: {id: seconds}."""
    out = {}
    index = os.path.join(road, "index.jsonl")
    for line in open(index) if os.path.exists(index) else []:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get("source") == "test" or row.get("band") == "test" or row.get("device") in TEST_DEVICES:
            continue
        try:
            w = wave.open(os.path.join(road, row["path"]))
            out[row["id"]] = w.getnframes() / w.getframerate()
        except (OSError, KeyError, EOFError, wave.Error):
            continue
    return out


def new_minutes(road, trained):
    old = set(l.split()[0] for l in open(trained) if l.strip()) if os.path.exists(trained) else set()
    return int(sum(s for i, s in owner_seconds(road).items() if i not in old) / 60)


def field(path, key):
    try:
        return json.load(open(path)).get(key) or ""
    except (OSError, ValueError):
        return ""


def blob_path(path):
    """The weight file a manifest names, relative to its folder ("" when none)."""
    try:
        return json.load(open(path))["files"][0]["path"]
    except (OSError, ValueError, KeyError, IndexError):
        return ""


def next_version(path):
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    day, _, n = str(field(path, "version")).partition(".")
    return f"{today}.{int(n) + 1 if day == today and n.isdigit() else 1}"


def manifest(report, version, parent_manifest):
    m, owner, _ = load(report)
    v = m["verdict"]
    blob = open(os.path.join(report, "rnnoise_car.bin"), "rb").read()
    won = bool(v["pass"] and v["group"] == "owner")
    return json.dumps({
        "name": MODEL_NAME,
        "version": version,
        "published_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "files": [{"path": f"{version}/{BLOB_NAME}", "sha256": hashlib.sha256(blob).hexdigest(),
                   "bytes": len(blob)}],
        "min_app_version": None,
        "notes": (f"eval on {v['group']} noise: {v['delta_pesq']:+.3f} PESQ-WB, "
                  f"{v['delta_stoi']:+.4f} STOI vs {field(parent_manifest, 'version') or 'stock'}"),
        "parent": field(parent_manifest, "version") or None,
        "default": field(parent_manifest, "default") or DEFAULT_STANDARD,
        "owner_gate_pass": won,
        "owner_minutes": round(owner["minutes"], 1),
        "metrics": {k: v[k] for k in ("group", "clips", "pesq_baseline", "pesq_candidate",
                                      "stoi_baseline", "stoi_candidate", "delta_pesq", "delta_stoi")},
    }, indent=1)


def verdict(path):
    v = json.load(open(path))["verdict"]
    return f"{'pass' if v['pass'] else 'fail'} {v['group']} {v['delta_pesq']:+.3f} {v['delta_stoi']:+.4f}"


def wants():
    return json.dumps({"updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                       "bands": {b: {"target_s": t} for b, t in WANTS.items()}}, indent=1)


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "readme":
        print(readme(*args), end="")
    elif cmd == "verdict":
        print(verdict(*args))
    elif cmd == "owner-seconds":
        for i, sec in sorted(owner_seconds(*args).items()):
            print(i, round(sec, 1))
    elif cmd == "new-minutes":
        print(new_minutes(*args))
    elif cmd == "field":
        print(field(*args))
    elif cmd == "blob-path":
        print(blob_path(*args))
    elif cmd == "next-version":
        print(next_version(*args))
    elif cmd == "manifest":
        print(manifest(*args))
    elif cmd == "wants":
        print(wants())
    else:
        sys.exit(f"unknown command {cmd}")


if __name__ == "__main__":
    main()
