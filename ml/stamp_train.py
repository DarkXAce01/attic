"""
Trains the date reader: a small all-convolution network that reads a 48 x 192 strip of a print's corner
and gives, for each of 48 slices across it, how likely each character is (CTC). Endless synthetic strips
from ml/stamp.py; scored on a fixed set drawn from photos it never trains on.

    python ml/stamp_train.py train [--steps 20000] [--out ~/.cache/attic-ml/runs/stamp]
    python ml/stamp_train.py export ~/.cache/attic-ml/runs/stamp/best.pt

Only operations OpenCV's dnn runs (conv, batch norm, ReLU, max pool), so the app runs it with no extra
runtime, like the print finder.
"""
import argparse
import os
import random
import sys
import time
import zlib

import cv2
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, IterableDataset, get_worker_info

import stamp
import synth

CLASSES = len(stamp.ALPHABET) + 1
ASSET = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "datestamp.onnx")


def cbr(cin, cout, k=3, pad=1):
    return nn.Sequential(nn.Conv2d(cin, cout, k, 1, pad, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True))


class StampNet(nn.Module):
    """48 x 192 RGB (0..255) in; CLASSES x 48 logits out (one column per 4 pixels across)."""

    def __init__(self):
        super().__init__()
        self.body = nn.Sequential(
            cbr(3, 32), nn.MaxPool2d(2),                       # 24 x 96
            cbr(32, 64), nn.MaxPool2d(2),                      # 12 x 48
            cbr(64, 96), cbr(96, 96), nn.MaxPool2d((2, 1)),    # 6 x 48
            cbr(96, 128), cbr(128, 128), nn.MaxPool2d((2, 1)),  # 3 x 48
            cbr(128, 160, (3, 1), (0, 0)),                     # 1 x 48
            cbr(160, 160, (1, 5), (0, 2)),                     # wider view across: neighbouring characters
            nn.Conv2d(160, CLASSES, 1),
        )

    def forward(self, x):
        return self.body(x / 255.0 - 0.5)  # B x CLASSES x 1 x 48


class Strips(IterableDataset):
    def __init__(self, photos, seed=None):
        self.photos = photos
        self.seed = seed

    def __iter__(self):
        info = get_worker_info()
        rng = random.Random(None if self.seed is None else self.seed + (info.id if info else 0))
        photos = stamp.Reused(self.photos)
        while True:
            img, label = stamp.sample(rng, photos)
            yield torch.from_numpy(cv2.cvtColor(img, cv2.COLOR_BGR2RGB)).permute(2, 0, 1).float(), label


def collate(batch):
    x = torch.stack([b[0] for b in batch])
    labels = [b[1] for b in batch]
    targets = torch.tensor([c for l in labels for c in stamp.encode(l)], dtype=torch.long)
    lengths = torch.tensor([len(l) for l in labels], dtype=torch.long)
    return x, targets, lengths, labels


def decode(logits):
    """Greedy CTC: best class per column, repeats merged, blanks dropped."""
    best = logits.argmax(1)
    out = []
    for row in best.tolist():
        s, prev = [], 0
        for c in row:
            if c != prev and c != 0:
                s.append(stamp.ALPHABET[c - 1])
            prev = c
        out.append("".join(s).strip())
    return out


def split_photos():
    """Training photos and held-out ones for validation."""
    photos = synth.Photos()
    paths = photos.paths
    train, val = synth.Photos(), synth.Photos()
    train.paths = [p for p in paths if zlib.crc32(os.path.basename(p).encode()) % 10 != 0]
    val.paths = [p for p in paths if zlib.crc32(os.path.basename(p).encode()) % 10 == 0]
    return train, val


def validation_set(photos, n=3000):
    rng = random.Random(424242)
    return [stamp.sample(rng, photos) for _ in range(n)]


def squash(s):
    return " ".join(s.split())


@torch.no_grad()
def evaluate(net, val, device):
    net.eval()
    exact = stamped = false_pos = empty = 0
    for i in range(0, len(val), 256):
        chunk = val[i:i + 256]
        x = torch.stack([torch.from_numpy(cv2.cvtColor(im, cv2.COLOR_BGR2RGB)).permute(2, 0, 1).float() for im, _ in chunk]).to(device)
        got = decode(net(x)[:, :, 0].float().cpu())
        for (_, label), g in zip(chunk, got):
            if label:
                stamped += 1
                exact += squash(g) == squash(label)
            else:
                empty += 1
                false_pos += g != ""
    net.train()
    return exact / max(stamped, 1), false_pos / max(empty, 1)


def train(args):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    os.makedirs(args.out, exist_ok=True)
    tr, va = split_photos()
    print(f"{len(tr.paths)} training photos, {len(va.paths)} held out", flush=True)
    val = validation_set(va)
    net = StampNet().to(device)
    if args.resume:
        net.load_state_dict(torch.load(args.resume, map_location=device)["state"])
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.steps, pct_start=0.05)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    loader = DataLoader(Strips(tr), batch_size=args.batch, num_workers=args.workers, collate_fn=collate, prefetch_factor=4, pin_memory=True)
    best = -1.0
    t0 = time.time()
    running = 0.0
    for step, (x, targets, lengths, _) in enumerate(loader, 1):
        x = x.to(device, non_blocking=True)
        logits = net(x)[:, :, 0]                                   # B x C x T
        logp = F.log_softmax(logits.float(), 1).permute(2, 0, 1)  # T x B x C
        loss = ctc(logp, targets, torch.full((x.shape[0],), logp.shape[0], dtype=torch.long), lengths)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        opt.step()
        sched.step()
        running = 0.98 * running + 0.02 * loss.item() if step > 1 else loss.item()
        if step % 500 == 0:
            print(f"step {step} loss {running:.4f} {(time.time() - t0) / step * 1000:.0f} ms/step", flush=True)
        if step % 2500 == 0 or step == args.steps:
            acc, fp = evaluate(net, val, device)
            print(f"  val: read exactly {acc * 100:.1f}% of stamps, a date on {fp * 100:.1f}% of strips without one", flush=True)
            score = acc - 2 * fp
            torch.save({"state": net.state_dict()}, os.path.join(args.out, "last.pt"))
            if score > best:
                best = score
                torch.save({"state": net.state_dict()}, os.path.join(args.out, "best.pt"))
        if step >= args.steps:
            break


class Exported(nn.Module):
    """For the app: softmax probabilities, B x CLASSES x 1 x 48."""

    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, x):
        return torch.softmax(self.net(x), 1)


def export(ckpt):
    net = StampNet()
    net.load_state_dict(torch.load(ckpt, map_location="cpu")["state"])
    model = Exported(net).eval()
    x = torch.rand(1, 3, stamp.STRIP_H, stamp.STRIP_W) * 255
    torch.onnx.export(model, x, ASSET, input_names=["rgb"], output_names=["chars"], opset_version=13, dynamo=False)
    with torch.no_grad():
        ref = model(x).numpy()
    dnn = cv2.dnn.readNetFromONNX(ASSET)
    dnn.setInput(x.numpy())
    got = dnn.forward()
    print(f"exported {os.path.getsize(ASSET) / 1e6:.2f} MB, OpenCV vs PyTorch max diff {np.abs(got - ref).max():.5f}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["train", "export"])
    ap.add_argument("ckpt", nargs="?", default=os.path.expanduser("~/.cache/attic-ml/runs/stamp/best.pt"))
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--out", default=os.path.expanduser("~/.cache/attic-ml/runs/stamp"))
    ap.add_argument("--resume", default="")
    a = ap.parse_args()
    if a.what == "train":
        train(a)
    else:
        export(a.ckpt)
