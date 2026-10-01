# ns-train: the car-tuned RNNoise model

Fine-tunes the CarPlay mic's RNNoise model (the "little" model in `../jni`) on this car's
road noise, scores it against the model the car runs, and publishes it only when it wins.
The result is a weight blob in the same format as `jni/rnnoise/weights/rnnoise_little.bin`.

```
road-noise/index.jsonl ──▶ forge: ingest, mix ──▶ vile GPU: fine-tune ──▶ forge: export, eval
                                                                                   │
share mic-ns/car-tuned/: README, metrics, samples, blob ◀──────────────────────────┘
       │ gate: PESQ-WB up, STOI not down (--publish, --auto)
       ▼
share models/rnnoise/<version>/weights.bin + manifest.json
       ──ingest GET /v1/models──▶ the car (Projection's ModelUpdater)
       (copy at launcher.hq/ns-model/rnnoise/ for the emulator farm)
```

Inputs and outputs follow `share/carlauncher/road-noise/CONTRACT.md` (sections 3 to 5, 7
and 8): captures come from `index.jsonl`, test rows are skipped, and the trainer writes
`wants.json` and `models/rnnoise/`.

## Run it

On x, as root. It writes the share and launcher.hq's webroot, and x is the one host that
reaches both forge (mixing, export, eval on CPU) and vile (fine-tuning on its GTX 1650):

```bash
apps/com.ripostelabs.projection/ns-train/train-car-model.sh            # train + report
apps/com.ripostelabs.projection/ns-train/train-car-model.sh --publish  # ... and publish if it wins
```

The report lands in `/z1-pool/share/carlauncher/mic-ns/car-tuned/README.md`. Steps that are
already done are skipped on forge, so a re-run after new captures only redoes the data,
the mix, the training and the eval.

Daily: `systemd/rav4-ns-train.timer` runs `--auto` at 03:30. It retrains only when at least
10 minutes of owner noise arrived since the last run, publishes only when the gate passes,
and mails `sasha+rav4@` only on a publish or a failure. A full run takes about 30 min
(downloads add 12 min the first time). Every run adds a line to `runs.log`
on the share. Install:

```bash
install -m 644 systemd/rav4-ns-train.* /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now rav4-ns-train.timer
```

## What each piece does

| File | Where | Does |
|---|---|---|
| `train-car-model.sh` | x | sync, run forge, report, gate, publish, mail |
| `forge-run.sh` | forge | setup, corpora, data, mix, export, eval (train: CPU fallback) |
| `vile-train.sh` | x | features forge -> vile, fine-tune in the stock pytorch CUDA image, best.pth back |
| `ns_data.py` | forge | 16 kHz then 48 kHz audio, owner split by drive, eval clips |
| `ns_finetune.py` | vile | fine-tune from the shipped checkpoint, sparse mask kept |
| `ns_eval.py` | forge | PESQ-WB, STOI, SI-SNR per blob through the app's C code |
| `ns_file.c` | forge | the app's denoiser on a file, any blob, Medium mix |
| `ns_report.py` | x | share README and the car's manifest |

## Why it is built this way

- **Same model, same format.** Upstream xiph/rnnoise and its model tarball at the commit
  pinned in `jni/vendor-rnnoise.sh`. The little model is `models/rnnoise10Gb_15.pth` in that
  tarball; `setup` re-exports it and checks it equals the shipped blob byte for byte, so
  the export path is proven before any training.
- **GPU, not forge.** On a loaded forge one batch of 32 mixes took 7 min forward and back;
  vile's GTX 1650 (cuDNN GRU) does a whole epoch of 4000 mixes in about 2.5 min. Batch 32
  runs out of its 3.6 GB; 16 fits in about 1.5 GB, and the run waits a day when less than
  2 GB is free (Jellyfin, Ollama).
- **Kept little.** The little model is two thirds zeros in its GRU weights (8x4 blocks).
  Fine-tuning re-applies that mask after every step, so the export keeps the same layout,
  size (1.5 MB) and CPU cost.
- **Trained on what the car hears.** The CarPlay mic is 16 kHz and NsPipeline upsamples it
  to 48 kHz. All training and test audio goes through 16 kHz first.
- **Car SNRs.** Upstream's `dump_features` draws noise from about -25 to +30 dB SNR; the
  copy here is patched (one line) to -5..20 dB.
- **No voices in the noise.** DEMAND's SPSQUARE and TBUS scenes carry people talking, which
  a noise suppressor should not learn to keep, so they stay out (`NS_DEMAND_SCENES` puts
  them back). Their effect on the score is not resolved: two runs on the same data scored
  PESQ -0.015 and -0.068 vs stock, which is wider than the -0.067 seen with them in.
- **Run to run.** The mix is random each run, so a retrain moves PESQ by about 0.05 on the
  public set. What held in all three runs on public noise: STOI up 0.009 to 0.010, PESQ up on
  held-out in-car noise (TCAR, +0.02 to +0.06), down on the truck and gravel recordings.
- **Fair test.** Owner noise is split by drive: the latest drives, at least a fifth of the
  minutes, are held out. Speech in the test is LibriSpeech test-clean, readers never heard
  in training. With one drive or none, the gate uses public held-out noise.
- **The car's default is not touched.** The manifest carries `default` over from the last
  one (first: `standard`) and records `owner_gate_pass`. The app does not read `default`.

## Data and licences

| Data | Use | Licence |
|---|---|---|
| Owner captures, `share/carlauncher/road-noise/` (CONTRACT.md: WAV + `road-noise/1` sidecar) | train + test | own |
| LibriSpeech dev-clean / test-clean (openslr.org/12) | speech train / test | CC BY 4.0 |
| DEMAND TCAR, STRAFFIC (zenodo 1227121), 4 channels each | noise train; TCAR's last 60 s test | CC BY 4.0 |
| archive.org car interiors: truck at speed, forest service road | noise test | CC0 |
| xiph/rnnoise code and model | starting point | BSD-3-Clause |

## Knobs

Environment, all optional: `NS_SEQUENCES` (4000 mixes of 20 s), `NS_EPOCHS` (6), `NS_LR`
(1e-4), `NS_BATCH` (16), `NS_JOBS` (8 mixers), `NS_MIN_NEW_MIN` (10).
