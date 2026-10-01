#!/usr/bin/env python3
"""Fine-tune the shipped RNNoise little model on car-noise features, keeping it little.

    ns_finetune.py --init LITTLE.pth --features F.f32 --val V.f32 --out DIR [--lr --epochs]

Upstream's train_rnnoise.py loop and loss (torch/rnnoise, same commit as the app), with
three changes for fine-tuning:

  1. It starts from the shipped weights, not from scratch.
  2. The little model's sparsity is a fixed mask: every GRU weight that is zero in the
     shipped checkpoint stays zero after every step. The export then keeps the same block
     layout, so the blob stays 1.5 MB and runs at the same cost in the car.
  3. Each epoch is scored on held-back features; DIR/best.pth is the best one, and an
     epoch 0 score (the shipped model) is the bar to beat.

Runs on CUDA when there is a GPU (vile-train.sh: vile's GTX 1650, about 2.5 min an epoch of 4000 mixes);
on CPU it works, but a loaded forge needed 7 min for one forward-and-back of a batch.
"""
import argparse
import json
import os

import numpy as np
import torch

import rnnoise

FEATURES = 65
BANDS = 32
DIM = FEATURES + BANDS + 1
SEQUENCE = 2000
# Upstream's optimiser settings (train_rnnoise.py).
ADAM_BETAS = (0.8, 0.98)
ADAM_EPS = 1e-8
GAMMA = 0.25
LR_DECAY = 5e-5
VAD_WEIGHT = 0.001
# Upstream drops the first 3 frames (conv warm-up) and the last (lookahead) from the loss.
HEAD, TAIL = 3, 1


class Features(torch.utils.data.Dataset):
    def __init__(self, path):
        data = np.memmap(path, dtype="float32", mode="r")
        n = data.shape[0] // SEQUENCE // DIM
        self.data = np.reshape(data[: n * SEQUENCE * DIM], (n, SEQUENCE, DIM))

    def __len__(self):
        return self.data.shape[0]

    def __getitem__(self, i):
        x = self.data[i]
        return x[:, :FEATURES].copy(), x[:, FEATURES:-1].copy(), x[:, -1:].copy()


def loss_of(model, features, gain, vad, states):
    """Upstream's loss: perceptual gain error weighted by voice, plus a small VAD term."""
    device = next(model.parameters()).device
    features, gain, vad = features.to(device), gain.to(device), vad.to(device)
    pred_gain, pred_vad, states = model(features, states=states)
    gain = gain[:, HEAD:-TAIL, :]
    vad = vad[:, HEAD:-TAIL, :]
    target = torch.clamp(gain, min=0)
    target = target * torch.tanh(8 * target) ** 2
    e = pred_gain ** GAMMA - target ** GAMMA
    # gain == -1 marks bands with no target (silence, above the low-pass): masked out.
    mask = torch.clamp(gain + 1, max=1)
    gain_loss = torch.mean((1 + 5.0 * vad) * mask * e ** 2)
    vad_loss = torch.mean(torch.abs(2 * vad - 1) * (-vad * torch.log(0.01 + pred_vad)
                                                   - (1 - vad) * torch.log(1.01 - pred_vad)))
    return gain_loss + VAD_WEIGHT * vad_loss, [s.detach() for s in states]


def sparse_masks(model):
    """The zero pattern of every GRU weight matrix, to re-apply after each step."""
    masks = {}
    for name, p in model.named_parameters():
        if name.startswith("gru") and "weight" in name:
            masks[name] = (p.detach() != 0).float()
    return masks


def score(model, loader):
    model.eval()
    total, n = 0.0, 0
    with torch.no_grad():
        for features, gain, vad in loader:
            loss, _ = loss_of(model, features, gain, vad, None)
            total += loss.item()
            n += 1
    model.train()
    return total / max(n, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--init", required=True)
    ap.add_argument("--features", required=True)
    ap.add_argument("--val", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch-size", type=int, default=32)
    ap.add_argument("--threads", type=int, default=int(os.environ.get("NS_THREADS", "12")))
    args = ap.parse_args()

    torch.manual_seed(20261001)
    torch.set_num_threads(args.threads)
    os.makedirs(args.out, exist_ok=True)

    ckpt = torch.load(args.init, map_location="cpu", weights_only=False)
    model = rnnoise.RNNoise(*ckpt["model_args"], **ckpt["model_kwargs"])
    model.load_state_dict(ckpt["state_dict"])
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)
    print(f"device: {torch.cuda.get_device_name(0) if device.type == 'cuda' else 'cpu'}", flush=True)
    masks = sparse_masks(model)
    params = dict(model.named_parameters())

    train = torch.utils.data.DataLoader(Features(args.features), batch_size=args.batch_size,
                                        shuffle=True, drop_last=True, num_workers=2)
    val = torch.utils.data.DataLoader(Features(args.val), batch_size=args.batch_size)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, betas=ADAM_BETAS, eps=ADAM_EPS)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda x: 1 / (1 + LR_DECAY * x))

    best = score(model, val)
    history = [{"epoch": 0, "val": best}]
    print(f"epoch 0 (shipped): val {best:.5f}", flush=True)
    torch.save(ckpt, os.path.join(args.out, "best.pth"))

    for epoch in range(1, args.epochs + 1):
        states, total = None, 0.0
        for i, (features, gain, vad) in enumerate(train):
            opt.zero_grad()
            loss, states = loss_of(model, features, gain, vad, states)
            loss.backward()
            opt.step()
            sched.step()
            # Keep the little model little: pruned weights stay pruned.
            with torch.no_grad():
                for name, m in masks.items():
                    params[name].mul_(m)
            total += loss.item()
        v = score(model, val)
        history.append({"epoch": epoch, "train": total / (i + 1), "val": v})
        print(f"epoch {epoch}: train {total / (i + 1):.5f} val {v:.5f}", flush=True)
        if v < best:
            best = v
            ckpt["state_dict"] = {k: v.cpu() for k, v in model.state_dict().items()}
            ckpt["epoch"] = epoch
            ckpt["loss"] = v
            torch.save(ckpt, os.path.join(args.out, "best.pth"))

    json.dump({"lr": args.lr, "epochs": args.epochs, "history": history},
              open(os.path.join(args.out, "history.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
