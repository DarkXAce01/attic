"""
Pre-draws synthetic album pages to disk, in parallel, so training reads them instead of waiting on the
drawing.

    python ml/make_dataset.py [count] [processes] [first]

Writes ~/.cache/attic-ml/data/synth/NNNNNNN.jpg, and per block of 500 pages quads_NNNNNNN.npz (print
corners per page, output pixels). Blocks already done are skipped, so it can be stopped and resumed, and
training can start on the blocks that exist.
"""
import multiprocessing as mp
import os
import random
import sys

import cv2
import numpy as np

import synth

OUT = os.path.expanduser("~/.cache/attic-ml/data/synth")


def work(args):
    start, end = args
    path = os.path.join(OUT, f"quads_{start:07d}.npz")
    if os.path.exists(path):
        return 0
    photos = synth.Photos()
    quads = {}
    for i in range(start, end):
        img, qs = synth.scene(random.Random(i), photos)
        cv2.imwrite(os.path.join(OUT, f"{i:07d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
        quads[str(i)] = np.float32(qs).reshape(-1, 4, 2)
    np.savez(path + ".tmp.npz", **quads)
    os.replace(path + ".tmp.npz", path)
    return end - start


if __name__ == "__main__":
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 80000
    procs = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    first = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    os.makedirs(OUT, exist_ok=True)
    chunks = [(s, min(first + count, s + 500)) for s in range(first, first + count, 500)]
    with mp.Pool(procs) as pool:
        for k, n in enumerate(pool.imap_unordered(work, chunks), 1):
            if k % 10 == 0:
                print(f"block {k}/{len(chunks)}", flush=True)
    print(f"done: {count} pages in {OUT}")
