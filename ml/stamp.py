"""
Synthetic date stamps for training the date reader.

Film cameras from the 1980s and 90s ("Quartz Date") printed the date into the corner of the picture in
glowing orange seven-segment digits: '87 7 14, 7 14 '87, 14 7 '87. On the print they sit near the
bottom-right corner of the landscape frame, so on a print that's been turned they can be in any corner.

The reader looks at one strip at a time: the bottom-right corner of the print, sized by the print's
short side S (0.2 S tall, 0.8 S wide), and the app tries all four turns of the print. Each sample here is
such a strip and the text it shows: the digits, apostrophe and spaces, or "" when there's no stamp.

    python ml/stamp.py preview      # writes a grid of samples to the scratch folder given as $OUT
"""
import math
import os
import random
import sys

import cv2
import numpy as np

import synth

STRIP_H, STRIP_W = 48, 192
STRIP_TALL, STRIP_WIDE = 0.2, 0.8          # the strip, as shares of the print's short side
ALPHABET = "0123456789' "                  # CTC classes 1..12; 0 is the blank

# Which segments each digit lights: a top, b top right, c bottom right, d bottom, e bottom left,
# f top left, g middle. Some cameras draw 7 with f and 9 without d; both appear.
SEGMENTS = {
    "0": "abcdef", "1": "bc", "2": "abdeg", "3": "abcdg", "4": "bcfg",
    "5": "acdfg", "6": "acdefg", "7": "abc", "8": "abcdefg", "9": "abcdfg",
}
ALT = {"7": "abcf", "9": "abcfg", "6": "cdefg"}


def stamp_text(rng):
    """A date in one of the formats cameras used, and the label (what the reader should say)."""
    year = rng.randint(1976, 2004)
    month = rng.randint(1, 12)
    day = rng.randint(1, 28 if month == 2 else 30 if month in (4, 6, 9, 11) else 31)
    yy = f"{year % 100:02d}"
    pad = rng.random() < 0.15  # a few cameras padded the month and day: '87 07 04
    m = f"{month:02d}" if pad else str(month)
    d = f"{day:02d}" if pad else str(day)
    tick = "'" if rng.random() < 0.85 else ""
    fmt = rng.random()
    if fmt < 0.45:
        parts = [tick + yy, m, d]
    elif fmt < 0.75:
        parts = [m, d, tick + yy]
    else:
        parts = [d, m, tick + yy]
    return " ".join(parts)


def draw_text(text, digit_h, rng):
    """The stamp's light as a float mask (0..1), drawn 4x larger than needed and slanted like the real font."""
    ss = 4
    h = digit_h * ss
    w = h * rng.uniform(0.48, 0.6)        # digit width
    t = h * rng.uniform(0.11, 0.16)       # segment thickness
    gap = w * rng.uniform(0.35, 0.55)     # between digits
    space = w * rng.uniform(0.8, 1.4)     # between groups
    tick_w = w * 0.35
    total = 0.0
    for ch in text:
        total += space if ch == " " else tick_w + gap * 0.5 if ch == "'" else w + gap
    pad = int(h * 0.6)
    canvas = np.zeros((int(h + 2 * pad), int(total + 2 * pad)), np.float32)
    x = float(pad)
    y0 = float(pad)
    alt = rng.random() < 0.5

    def seg(p, q):
        cv2.line(canvas, (int(p[0] * 8), int(p[1] * 8)), (int(q[0] * 8), int(q[1] * 8)), 1.0, max(1, int(t)), cv2.LINE_AA, shift=3)

    for ch in text:
        if ch == " ":
            x += space
            continue
        if ch == "'":
            seg((x + tick_w * 0.8, y0 + t * 0.6), (x + tick_w * 0.3, y0 + h * 0.3))
            x += tick_w + gap * 0.5
            continue
        on = ALT.get(ch, SEGMENTS[ch]) if alt and ch in ALT else SEGMENTS[ch]
        i = t * 0.7  # segments stop short of each other, the seven-segment look
        l, r, top, mid, bot = x + t / 2, x + w - t / 2, y0 + t / 2, y0 + h / 2, y0 + h - t / 2
        pts = {
            "a": ((l + i, top), (r - i, top)), "g": ((l + i, mid), (r - i, mid)), "d": ((l + i, bot), (r - i, bot)),
            "f": ((l, top + i), (l, mid - i)), "b": ((r, top + i), (r, mid - i)),
            "e": ((l, mid + i), (l, bot - i)), "c": ((r, mid + i), (r, bot - i)),
        }
        for s in on:
            seg(*pts[s])
        x += w + gap
    # Italic slant, as on most cameras.
    slant = rng.uniform(0.0, 0.25)
    m = np.float32([[1, -slant, slant * canvas.shape[0] / 2], [0, 1, 0]])
    canvas = cv2.warpAffine(canvas, m, (canvas.shape[1], canvas.shape[0]))
    return cv2.resize(canvas, (canvas.shape[1] // ss, canvas.shape[0] // ss), interpolation=cv2.INTER_AREA)


def stamp_colour(rng):
    """BGR: mostly orange, sometimes red-orange or yellow, as different films and cameras gave."""
    r = rng.random()
    if r < 0.7:
        return np.float32([rng.uniform(20, 70), rng.uniform(110, 175), 255])
    if r < 0.88:
        return np.float32([rng.uniform(20, 60), rng.uniform(50, 100), 255])
    return np.float32([rng.uniform(40, 110), rng.uniform(190, 235), 255])


def print_image(rng, photos, w, h):
    """A photo the size of the print, toned a little like an old print."""
    img = cv2.resize(photos.any(rng, small=True), (w, h), interpolation=cv2.INTER_AREA).astype(np.float32)
    if rng.random() < 0.6:  # faded, warm, low contrast
        fade = rng.uniform(0.0, 0.35)
        img = img * (1 - fade) + np.float32([rng.uniform(150, 200), rng.uniform(170, 210), rng.uniform(190, 230)]) * fade
    return np.clip(img, 0, 255)


def add_stamp(rng, img, s, text):
    """Burns [text] into [img] near the bottom-right corner, the way the camera exposed it onto the film."""
    h, w = img.shape[:2]
    digit_h = s * rng.uniform(0.032, 0.06)
    mask = draw_text(text, digit_h, rng)
    mh, mw = mask.shape
    if mw > w * 0.9 or mh > h * 0.5:
        return False
    right = s * rng.uniform(0.03, 0.12)
    bottom = s * rng.uniform(0.02, 0.09)
    x0 = int(w - right - mw)
    y0 = int(h - bottom - mh)
    if x0 < 0 or y0 < 0:
        return False
    colour = stamp_colour(rng)
    # Light added onto the film: a bright core and a soft halo around it (halation).
    core = mask * rng.uniform(0.75, 1.0)
    halo = cv2.GaussianBlur(mask, (0, 0), max(0.8, digit_h * rng.uniform(0.08, 0.2))) * rng.uniform(0.3, 0.8)
    light = np.clip(core + halo, 0, 1.2)[..., None]
    roi = img[y0:y0 + mh, x0:x0 + mw]
    strength = rng.uniform(0.6, 1.0)
    # Additive light that saturates, like exposure: dark areas take the full colour, bright ones a little.
    roi[:] = 255 - (255 - roi) * np.exp(-light * strength * 2.2 * (colour / 255.0)) if rng.random() < 0.5 else \
        np.clip(roi * (1 - light * strength * 0.8) + colour * light * strength, 0, 255)
    return True


def camera(rng, img):
    """What the phone does to the print: a little blur, noise, uneven light, JPEG."""
    if rng.random() < 0.6:
        img = cv2.GaussianBlur(img, (0, 0), rng.uniform(0.3, 1.4))
    if rng.random() < 0.5:  # light falling across the print
        h, w = img.shape[:2]
        gx = np.linspace(rng.uniform(0.75, 1.1), rng.uniform(0.75, 1.1), w, dtype=np.float32)[None, :, None]
        img = img * gx
    img = img + np.float32(rng.gauss(0, 1)) * rng.uniform(0, 6) + np.random.default_rng(rng.randrange(1 << 30)).normal(0, rng.uniform(1, 6), img.shape).astype(np.float32)
    img = np.clip(img, 0, 255).astype(np.uint8)
    if rng.random() < 0.7:
        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, rng.randint(45, 92)])
        img = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    return img


def strip(img):
    """The bottom-right strip of a print, as the app cuts it: 0.2 x 0.8 of the short side, to 48 x 192."""
    h, w = img.shape[:2]
    s = min(h, w)
    sh, sw = int(s * STRIP_TALL), int(s * STRIP_WIDE)
    cut = img[h - sh:, w - sw:]
    return cv2.resize(cut, (STRIP_W, STRIP_H), interpolation=cv2.INTER_AREA)


def sample(rng, photos):
    """(strip as BGR uint8 48 x 192, label). About 55% have a stamp."""
    s = rng.randint(300, 700)                       # the print's short side in pixels
    aspect = rng.choice([1.5, 1.5, 1.4, 1.33, 1.0])
    w, h = int(s * aspect), s
    if rng.random() < 0.08:  # a print taken upright: the reader sees it turned, short side across
        w, h = h, int(s * aspect)
    img = print_image(rng, photos, w, h)
    label = ""
    if rng.random() < 0.55:
        text = stamp_text(rng)
        if add_stamp(rng, img, min(w, h), text):
            label = text
    elif rng.random() < 0.15:  # something orange that isn't a date: a sign, a sunset, a red-orange shirt
        img8 = img.astype(np.uint8)
        cv2.putText(img8, rng.choice(["SALE", "OPEN", "CAFE", "TAXI", "HOTEL", "12", "5"]), (int(w * rng.uniform(0.4, 0.7)), int(h * rng.uniform(0.85, 0.95))),
                    cv2.FONT_HERSHEY_SIMPLEX, rng.uniform(0.5, 1.4), tuple(float(v) for v in stamp_colour(rng)), 2, cv2.LINE_AA)
        img = img8.astype(np.float32)
    if rng.random() < 0.3:  # the print's white border (the stamp is inside the picture, the border round it)
        b = int(min(w, h) * rng.uniform(0.02, 0.06))
        paper = np.float32([rng.uniform(215, 245)] * 3)
        img = cv2.copyMakeBorder(img, b, b, b, b, cv2.BORDER_CONSTANT, value=tuple(float(v) for v in paper))
    # The app's crop of the print is good to a few pixels, never exact.
    hh, ww = img.shape[:2]
    j = int(min(hh, ww) * 0.02)
    t, l = rng.randint(0, j), rng.randint(0, j)
    bo, ri = rng.randint(0, j), rng.randint(0, j)
    img = img[t:hh - bo, l:ww - ri]
    # Only the strip is ever looked at, so the camera's blur, noise and JPEG are done on it alone.
    h2, w2 = img.shape[:2]
    s2 = min(h2, w2)
    cut = img[h2 - int(s2 * STRIP_TALL):, w2 - int(s2 * STRIP_WIDE):]
    return cv2.resize(camera(rng, cut), (STRIP_W, STRIP_H), interpolation=cv2.INTER_AREA), label


class Reused:
    """Photos kept in memory and each used several times, a different crop each time: disk reads were
    most of the cost of a sample. One in [fresh] calls replaces a photo with a new one."""

    def __init__(self, photos, keep=48, fresh=4):
        self.photos, self.keep, self.fresh = photos, keep, fresh
        self.cache = []

    def any(self, rng, small=False):
        if len(self.cache) < self.keep or rng.random() < 1 / self.fresh:
            img = self.photos.any(rng, small=True)
            if len(self.cache) < self.keep:
                self.cache.append(img)
            else:
                self.cache[rng.randrange(self.keep)] = img
        img = rng.choice(self.cache)
        h, w = img.shape[:2]
        ch, cw = int(h * rng.uniform(0.5, 1.0)), int(w * rng.uniform(0.5, 1.0))
        y, x = rng.randint(0, h - ch), rng.randint(0, w - cw)
        img = img[y:y + ch, x:x + cw]
        return img[:, ::-1] if rng.random() < 0.5 else img


def encode(label):
    return [ALPHABET.index(c) + 1 for c in label]


if __name__ == "__main__" and len(sys.argv) > 1 and sys.argv[1] == "preview":
    photos = synth.Photos()
    rows = []
    labels = []
    for i in range(24):
        st, lab = sample(random.Random(i), photos)
        rows.append(st)
        labels.append(lab)
    grid = np.vstack([np.hstack(rows[k:k + 3]) for k in range(0, 24, 3)])
    grid = cv2.resize(grid, None, fx=2, fy=2, interpolation=cv2.INTER_NEAREST)
    out = os.environ.get("OUT", ".")
    cv2.imwrite(os.path.join(out, "stamps.png"), grid)
    for k in range(0, 24, 3):
        print(" | ".join(repr(l) for l in labels[k:k + 3]))
