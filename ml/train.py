"""
Trains the print finder on endless synthetic album pages.

    python ml/train.py [--steps 30000] [--batch 16] [--out ~/.cache/attic-ml/runs/printnet]

Every batch is new pages, so the model never sees the same picture twice. Every few thousand steps it is
scored the way the app uses it: outlines from the maps, matched against the drawn prints (IoU >= 0.9),
on a fixed set of validation pages.
"""
import argparse
import os

os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")
import glob
import os
import random
import time

import cv2
import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

import quads as Q
import synth
from model import PrintNet


DATA = os.path.expanduser("~/.cache/attic-ml/data/synth")


class Pages(Dataset):
    """Pre-drawn pages (ml/make_dataset.py), mirrored at random and with a little colour jitter."""

    def __init__(self):
        self.quads = {}
        for f in sorted(glob.glob(os.path.join(DATA, "quads_*.npz"))):
            q = np.load(f)
            self.quads.update({int(k): q[k] for k in q.files})
        self.ids = sorted(self.quads)
        print(f"{len(self.ids)} training pages", flush=True)

    def __len__(self):
        return len(self.ids)

    def __getitem__(self, k):
        i = self.ids[k]
        img = cv2.imread(os.path.join(DATA, f"{i:07d}.jpg"), cv2.IMREAD_COLOR)
        qs = [q.copy() for q in self.quads[i]]
        rng = random.Random()
        if rng.random() < 0.5:
            img = img[:, ::-1].copy()
            for q in qs:
                q[:, 0] = img.shape[1] - q[:, 0]
        if rng.random() < 0.5:
            img = np.clip(img.astype(np.float32) * rng.uniform(0.85, 1.15) + rng.uniform(-15, 15), 0, 255).astype(np.uint8)
        t = synth.targets(qs)
        return torch.from_numpy(cv2.cvtColor(img, cv2.COLOR_BGR2RGB)).permute(2, 0, 1).float(), torch.from_numpy(t)


def loss_fn(logits, t):
    # BCE everywhere, edge pixels weighted up (they're few and they decide the crop), plus dice on the
    # print and core maps so small prints count as much as big ones.
    w = torch.ones_like(t)
    w[:, 2] = 1 + 8 * t[:, 2]
    bce = F.binary_cross_entropy_with_logits(logits, t, weight=w)
    p = torch.sigmoid(logits[:, :2])
    tt = t[:, :2]
    inter = (p * tt).sum((2, 3))
    dice = 1 - (2 * inter + 1) / (p.sum((2, 3)) + tt.sum((2, 3)) + 1)
    return bce + 0.5 * dice.mean()


def validation_set(n=300):
    photos = synth.Photos()
    out = []
    for i in range(n):
        img, qs = synth.scene(random.Random(10_000_000 + i), photos)
        out.append((img, qs))
    return out


@torch.no_grad()
def evaluate(net, val, device):
    net.eval()
    hits = total = extras = 0
    errs = []
    for i in range(0, len(val), 8):
        chunk = val[i:i + 8]
        x = torch.stack([torch.from_numpy(cv2.cvtColor(im, cv2.COLOR_BGR2RGB)).permute(2, 0, 1).float() for im, _ in chunk]).to(device)
        with torch.autocast(device, dtype=torch.float16, enabled=device == "cuda"):
            maps = torch.sigmoid(net(x).float()).cpu().numpy()
        for (im, qs), m in zip(chunk, maps):
            h, w = im.shape[:2]
            truth = [q for q in qs if Q.inside(q, w, h)]
            found = [q for q in Q.find(m) if Q.inside(q, w, h, 1.5)]
            a, b, c, e = Q.score(truth, found)
            hits += a; total += b; extras += c; errs += e
    net.train()
    return hits / max(total, 1), extras / len(val), float(np.median(errs)) if errs else float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--out", default=os.path.expanduser("~/.cache/attic-ml/runs/printnet"))
    ap.add_argument("--resume", default="")
    ap.add_argument("--width", type=float, default=1.5)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    net = PrintNet(args.width).to(device)
    if args.resume:
        net.load_state_dict(torch.load(args.resume, map_location=device)["state"])
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-4)
    scaler = torch.amp.GradScaler(enabled=device == "cuda")
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.steps, pct_start=0.05)
    val = validation_set()
    best = -1.0
    t0 = time.time()
    running = 0.0
    step = 0
    done = False
    while not done:
        # A new loader each pass picks up pages still being drawn by make_dataset.py.
        loader = DataLoader(Pages(), batch_size=args.batch, shuffle=True, num_workers=args.workers,
                            prefetch_factor=4, drop_last=True, pin_memory=True)
        for x, t in loader:
            step += 1
            done = step >= args.steps
            x, t = x.to(device, non_blocking=True), t.to(device, non_blocking=True)
            # Half precision: the laptop GPU has about 2.5 GB to spare.
            with torch.autocast(device, dtype=torch.float16, enabled=device == "cuda"):
                logits = net(x)
            loss = loss_fn(logits.float(), t)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            running = 0.98 * running + 0.02 * loss.item() if step > 1 else loss.item()
            if step % 200 == 0:
                print(f"step {step} loss {running:.4f} {(time.time() - t0) / step * 1000:.0f} ms/step", flush=True)
            if step % 2500 == 0 or step == args.steps:
                recall, extra, err = evaluate(net, val, device)
                score = recall - 0.5 * extra
                print(f"  val: found {recall * 100:.1f}% (IoU>=0.9), {extra:.2f} extra per page, median corner error {err:.2f}px", flush=True)
                torch.save({"width": args.width, "state": net.state_dict()}, os.path.join(args.out, "last.pt"))
                if score > best:
                    best = score
                    torch.save({"width": args.width, "state": net.state_dict()}, os.path.join(args.out, "best.pt"))
            if done:
                break


if __name__ == "__main__":
    main()
