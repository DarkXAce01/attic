"""
Model maps -> print outlines. Mirrored in the app by vision/PrintNet.kt; keep the two in step.

1. A patch of print paper that is one clean four-sided shape is one print. Patches that aren't (prints
   touching) are split between the cores inside them, every pixel going to the nearest core. Four corners
   are fitted to each print's own pixels; shapes that don't fill their four corners are dropped.
2. Each side then moves to where the print map actually crosses 0.5, found along short lines across the
   side, and a straight line is fitted through those crossings. Corners are where the lines meet.
"""
import math

import cv2
import numpy as np

from synth import CORE


def quad_from_blob(mask):
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cnts:
        return None
    hull = cv2.convexHull(max(cnts, key=cv2.contourArea))
    peri = cv2.arcLength(hull, True)
    for eps in (0.02, 0.03, 0.04, 0.06, 0.08):
        ap = cv2.approxPolyDP(hull, eps * peri, True)
        if len(ap) == 4:
            return order(ap.reshape(4, 2).astype(np.float32))
    return order(cv2.boxPoints(cv2.minAreaRect(hull)).astype(np.float32))


def order(q):
    """Clockwise from top-left."""
    c = q.mean(0)
    ang = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0])
    q = q[np.argsort(ang)]
    start = np.argmin(q.sum(1))
    return np.roll(q, -start, 0)


def sample(m, x, y):
    h, w = m.shape
    if x < 0 or y < 0 or x > w - 1 or y > h - 1:
        return None
    x0, y0 = int(x), int(y)
    x1, y1 = min(x0 + 1, w - 1), min(y0 + 1, h - 1)
    fx, fy = x - x0, y - y0
    return (m[y0, x0] * (1 - fx) + m[y0, x1] * fx) * (1 - fy) + (m[y1, x0] * (1 - fx) + m[y1, x1] * fx) * fy


def refine(q, pr):
    lines = []
    c = q.mean(0)
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        d = b - a
        length = float(np.linalg.norm(d))
        if length < 4:
            return q
        d = d / length
        n = np.float32([-d[1], d[0]])
        if np.dot(c - (a + b) / 2, n) > 0:
            n = -n  # outward
        across = float(np.linalg.norm(q[(i + 2) % 4] - q[(i + 1) % 4]))
        reach = max(3.0, 0.12 * across)
        pts = []
        for t in np.linspace(0.1, 0.9, 24):
            p = a + (b - a) * t
            prev = None
            best = None
            # Walk from inside to outside; the crossing nearest the guess wins.
            for s in np.arange(-reach, reach + 0.01, 0.5):
                v = sample(pr, p[0] + n[0] * s, p[1] + n[1] * s)
                if v is None:
                    break
                if prev is not None and prev[1] >= 0.5 > v:
                    s0 = prev[0] + (prev[1] - 0.5) / (prev[1] - v) * 0.5
                    if best is None or abs(s0) < abs(best):
                        best = s0
                prev = (s, v)
            if best is not None:
                pts.append(p + n * best)
        if len(pts) >= 6:
            pts = np.float32(pts)
            vx, vy, x0, y0 = cv2.fitLine(pts, cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
            lines.append((np.float32([x0, y0]), np.float32([vx, vy])))
        else:
            lines.append((a, d))
    out = []
    for i in range(4):
        p1, d1 = lines[(i + 3) % 4]
        p2, d2 = lines[i]
        den = d1[0] * d2[1] - d1[1] * d2[0]
        if abs(den) < 1e-4:
            return q
        t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / den
        out.append(p1 + d1 * t)
    out = np.float32(out)
    if np.abs(out - q).max() > 0.25 * min(np.linalg.norm(q[1] - q[0]), np.linalg.norm(q[3] - q[0])):
        return q
    return out


def filled_quad(region, lo=0.85, hi=1.15):
    """The four-sided outline of [region] if the region fills it, else None."""
    q = quad_from_blob(region)
    if q is None:
        return None
    area = cv2.contourArea(q)
    n = region.sum()
    return q if area > 0 and lo * area <= n <= hi * area else None


def find(maps, min_area_frac=0.004):
    """maps: 3 x H x W probabilities. Returns print quads (H x W pixel coordinates).

    Each patch of print paper holding two or more cores is split between them (every pixel goes to the
    nearest core) when every piece is a clean four-sided shape: prints touching, or stacked so closely
    that together they look like one rectangle. Otherwise the patch is one print if it is a clean
    four-sided shape itself."""
    pr, core = maps[0], maps[1]
    h, w = pr.shape
    min_area = min_area_frac * h * w
    paper = (pr > 0.5).astype(np.uint8)
    cores = (core > 0.5).astype(np.uint8)
    n, comp = cv2.connectedComponents(paper, connectivity=4)
    _, labels = cv2.distanceTransformWithLabels(1 - cores, cv2.DIST_L2, 5, labelType=cv2.DIST_LABEL_CCOMP) if cores.any() else (None, None)
    out = []
    for c in range(1, n):
        patch = (comp == c).astype(np.uint8)
        if patch.sum() < min_area:
            continue
        whole = filled_quad(patch, 0.9, 1.1)
        pieces = []
        if labels is not None:
            ks = [k for k in np.unique(labels[(patch > 0) & (cores > 0)])
                  if ((labels == k) & (cores > 0) & (patch > 0)).sum() >= min_area * CORE * CORE]
            if len(ks) >= 2:
                pieces = [filled_quad((patch & (labels == k)).astype(np.uint8)) for k in ks]
        # Several cores: one print per core where its piece is a clean four-sided shape. If some pieces
        # aren't and the whole patch is, the cores were fragments of one print.
        good = [q for q in pieces if q is not None]
        if whole is not None and (not pieces or len(good) < len(pieces)):
            out.append(refine(whole, pr))
        else:
            out += [refine(q, pr) for q in good]
    return out


def iou(a, b, w=None, h=None):
    pts = np.vstack([a, b])
    x0, y0 = np.floor(pts.min(0)).astype(int) - 1
    x1, y1 = np.ceil(pts.max(0)).astype(int) + 1
    W, H = (x1 - x0) * 4, (y1 - y0) * 4
    ma = np.zeros((H, W), np.uint8); mb = np.zeros((H, W), np.uint8)
    cv2.fillConvexPoly(ma, np.round((a - [x0, y0]) * 4).astype(np.int32), 1)
    cv2.fillConvexPoly(mb, np.round((b - [x0, y0]) * 4).astype(np.int32), 1)
    inter = np.logical_and(ma, mb).sum()
    union = np.logical_or(ma, mb).sum()
    return inter / max(union, 1)


def inside(q, w, h, margin=1.0):
    return q[:, 0].min() >= margin and q[:, 1].min() >= margin and q[:, 0].max() <= w - 1 - margin and q[:, 1].max() <= h - 1 - margin


def score(truth, found, thr=0.9):
    """(matched, truths, extras, corner errors of the matches)."""
    left = list(found)
    hits, errs = 0, []
    for t in truth:
        if not left:
            break
        j = max(range(len(left)), key=lambda k: iou(t, left[k]))
        if iou(t, left[j]) >= thr:
            f = left.pop(j)
            hits += 1
            errs.append(min(np.linalg.norm(np.roll(f, r, 0) - t, axis=1).max() for r in range(4)))
    return hits, len(truth), len(left), errs
