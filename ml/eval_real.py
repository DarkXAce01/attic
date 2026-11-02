"""
Scores the print finder on real album pages, privately: reads imgs/*.jpg (gitignored, never uploaded)
and imgs/labels.json, and prints only numbers.

labels.json: {"<file name without .jpg>": {"size": [w, h], "prints": [[[x, y] x 4], ...]}, ...}
with corners in the given size's pixels.

    python ml/eval_real.py [checkpoint.pt]
"""
import json
import os
import sys

import cv2
import numpy as np
import torch

import quads as Q
import synth
from model import load

IMGS = os.path.join(os.path.dirname(__file__), "..", "imgs")


def main():
    ckpt = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/attic-ml/runs/printnet/best.pt")
    net = load(ckpt)
    net.eval()
    labels = json.load(open(os.path.join(IMGS, "labels.json")))
    tot = [0, 0, 0]
    errs = []
    for name, lab in sorted(labels.items()):
        img = cv2.imread(os.path.join(IMGS, name + ".jpg"))
        if img is None:
            continue
        h, w = img.shape[:2]
        if w > h:  # the camera screen is always upright
            continue
        small = cv2.resize(img, (synth.OUT_W, synth.OUT_H), interpolation=cv2.INTER_AREA)
        x = torch.from_numpy(cv2.cvtColor(small, cv2.COLOR_BGR2RGB)).permute(2, 0, 1).float()[None]
        with torch.no_grad():
            maps = torch.sigmoid(net(x))[0].numpy()
        lw, lh = lab["size"]
        truth = [np.float32(p) * [synth.OUT_W / lw, synth.OUT_H / lh] for p in lab["prints"]]
        found = [q for q in Q.find(maps) if Q.inside(q, synth.OUT_W, synth.OUT_H, 1.5)]
        a, b, c, e = Q.score(truth, found)
        best = [max([Q.iou(t, f) for f in found] or [0]) for t in truth]
        tot[0] += a; tot[1] += b; tot[2] += c; errs += e
        print(f"{name}: {a}/{b} found, {c} extra, best IoU per print {', '.join(f'{v:.2f}' for v in best)}")
    med = float(np.median(errs)) * (1280 / synth.OUT_H) if errs else float("nan")
    print(f"total {tot[0]}/{tot[1]} found, {tot[2]} extra, median corner error {med:.1f}px at 720x1280")


if __name__ == "__main__":
    main()
