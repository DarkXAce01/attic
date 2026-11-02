"""
Synthetic album pages for training the print finder.

Every sample is a phone's-eye view of an album page: prints in sleeve pockets, on magnetic pages or loose
on a table, with the things that make real pages hard: pockets bigger than their prints, pale prints on
pale pages, white photo borders, glare on the sleeve, shadows, perspective, blur, noise and a fading look.
The prints carry real photos (Unsplash Lite), so the model learns what a photo looks like next to a page,
not a particular picture.

Labels are exact because we drew them: every print's four corners, in output pixels.
"""
import glob
import math
import os
import random

import cv2
import numpy as np

cv2.setNumThreads(1)

OUT_W, OUT_H = 288, 384      # what the model sees: the camera's upright 3:4 frame
SS = 1.5                     # pages are drawn bigger and scaled down, like a real camera
CORE = 0.6                   # a print's core is the print shrunk to this about its centre

PHOTO_DIR = os.path.expanduser("~/.cache/attic-ml/data/photos")


class Photos:
    def __init__(self, root=PHOTO_DIR):
        self.paths = sorted(glob.glob(os.path.join(root, "*.jpg")))
        if not self.paths:
            raise SystemExit(f"no photos in {root}: run ml/fetch_photos.py")

    def any(self, rng, small=False):
        for _ in range(5):
            img = cv2.imread(rng.choice(self.paths), cv2.IMREAD_REDUCED_COLOR_2 if small else cv2.IMREAD_COLOR)
            if img is not None:
                return img
        return np.full((300, 400, 3), 128, np.uint8)


# ---------------------------------------------------------------- helpers

def rand_colour(rng, kind):
    """Plausible colours (BGR) for album pages, sleeves and backgrounds."""
    if kind == "page":
        choice = rng.random()
        if choice < 0.35:   # white / cream
            v = rng.uniform(205, 250); return (v - rng.uniform(0, 25), v - rng.uniform(0, 10), v)
        if choice < 0.55:   # lilac, grey, pale blue
            v = rng.uniform(170, 235); return (v + rng.uniform(0, 15), v - rng.uniform(0, 12), v - rng.uniform(0, 6))
        if choice < 0.75:   # black / dark
            v = rng.uniform(15, 60); return (v, v, v + rng.uniform(0, 10))
        if choice < 0.9:    # brown, kraft, red-brown
            return (rng.uniform(30, 110), rng.uniform(60, 140), rng.uniform(100, 190))
        return tuple(rng.uniform(40, 230) for _ in range(3))
    # backgrounds: anything
    return tuple(rng.uniform(20, 235) for _ in range(3))


def texture(rng, h, w, base, strength):
    """A flat colour with paper grain, linen weave or carpet-like structure."""
    img = np.empty((h, w, 3), np.float32)
    img[:] = base
    kind = rng.random()
    if kind < 0.4:
        noise = rng_normal(rng, (h // 2 + 1, w // 2 + 1)) * strength
        noise = cv2.resize(noise, (w, h))
        img += noise[..., None]
    elif kind < 0.65:  # linen / weave
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        p = rng.uniform(2.5, 6)
        weave = (np.sin(xx * 2 * math.pi / p) * np.sin(yy * 2 * math.pi / p)) * strength * 0.8
        img += weave[..., None] + cv2.resize(rng_normal(rng, (h // 2 + 1, w // 2 + 1)), (w, h))[..., None] * strength * 0.3
    elif kind < 0.85:  # blotchy (carpet, fabric)
        n = cv2.resize(rng_normal(rng, (max(2, h // 24), max(2, w // 24))), (w, h), interpolation=cv2.INTER_CUBIC)
        img += n[..., None] * strength * 2 + cv2.resize(rng_normal(rng, (h // 2 + 1, w // 2 + 1)), (w, h))[..., None] * strength * 0.5
    else:  # repeating motif (patterned carpet, tablecloth)
        tile = int(rng.uniform(14, 60))
        yy, xx = np.mgrid[0:h, 0:w]
        pattern = (((xx // tile) + (yy // tile)) % 2).astype(np.float32) * 2 - 1
        if rng.random() < 0.5:
            pattern = np.sin((xx + yy) * 2 * math.pi / tile).astype(np.float32)
        img += pattern[..., None] * strength * rng.uniform(1, 4)
        img += cv2.resize(rng_normal(rng, (h // 2 + 1, w // 2 + 1)), (w, h))[..., None] * strength * 0.4
    return img


def rng_normal(rng, shape):
    return np.random.default_rng(rng.randrange(1 << 30)).standard_normal(shape).astype(np.float32)


def photo_background(rng, photos, h, w):
    img = photos.any(rng).astype(np.float32)
    img = cv2.resize(img, (w, h), interpolation=cv2.INTER_LINEAR)
    if rng.random() < 0.6:
        img = cv2.GaussianBlur(img, (0, 0), rng.uniform(2, 10))
    return img


def fill_quad(img, quad, colour_or_img):
    """Paints [colour_or_img] (a colour, or an image warped into the quad) into [img] where [quad] is."""
    h, w = img.shape[:2]
    if isinstance(colour_or_img, np.ndarray):
        # Work only in the quad's bounding box.
        x0 = max(0, int(np.floor(quad[:, 0].min())) - 1); y0 = max(0, int(np.floor(quad[:, 1].min())) - 1)
        x1 = min(w, int(np.ceil(quad[:, 0].max())) + 2); y1 = min(h, int(np.ceil(quad[:, 1].max())) + 2)
        if x1 <= x0 or y1 <= y0:
            return
        local = (np.float32(quad) - np.float32([x0, y0])).astype(np.float32)
        src = colour_or_img
        sh, sw = src.shape[:2]
        m = cv2.getPerspectiveTransform(np.float32([[0, 0], [sw, 0], [sw, sh], [0, sh]]), local)
        roi = img[y0:y1, x0:x1]
        warped = cv2.warpPerspective(src, m, (x1 - x0, y1 - y0), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
        cover = np.zeros((y1 - y0, x1 - x0), np.float32)
        cv2.fillConvexPoly(cover, np.round(local * 8).astype(np.int32), 1.0, lineType=cv2.LINE_AA, shift=3)
        cover = cover[..., None]
        roi[:] = roi * (1 - cover) + warped * cover
    else:
        cv2.fillConvexPoly(img, np.round(np.float32(quad) * 8).astype(np.int32), colour_or_img, lineType=cv2.LINE_AA, shift=3)


def rect(x, y, w, h):
    return np.float32([[x, y], [x + w, y], [x + w, y + h], [x, y + h]])


def rotate(quad, angle, centre=None):
    c = quad.mean(0) if centre is None else centre
    a = math.radians(angle)
    r = np.float32([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]])
    return (quad - c) @ r.T + c


def scale(quad, s):
    c = quad.mean(0)
    return (quad - c) * s + c


# ---------------------------------------------------------------- prints

def make_print(rng, photos, w, h):
    """A photo print w x h pixels (BGR float): picture, maybe a white border, an old-print look."""
    src = photos.any(rng, small=max(w, h) < 280)
    sh, sw = src.shape[:2]
    # Crop the photo to the print's aspect.
    target = w / h
    if sw / sh > target:
        cw = int(sh * target); x0 = rng.randrange(0, sw - cw + 1); src = src[:, x0:x0 + cw]
    else:
        ch = int(sw / target); y0 = rng.randrange(0, sh - ch + 1); src = src[y0:y0 + ch]
    pic = cv2.resize(src, (w, h), interpolation=cv2.INTER_AREA).astype(np.float32)
    if rng.random() < 0.1:
        pic = graphic(rng, w, h)
    if rng.random() < 0.3:
        shapes_inside(rng, photos, pic)

    # Old prints: faded, warm or magenta cast, lifted blacks, soft.
    if rng.random() < 0.6:
        fade = rng.uniform(0.55, 0.95)
        lift = rng.uniform(0, 60)
        cast = np.float32([rng.uniform(0.8, 1.05), rng.uniform(0.85, 1.05), rng.uniform(0.95, 1.15)])
        pic = pic * fade * cast + lift
    if rng.random() < 0.3:
        pic = cv2.GaussianBlur(pic, (0, 0), rng.uniform(0.5, 1.5))
    # Pictures that are mostly pale or mostly dark at the edges, the hard cases for edges.
    if rng.random() < 0.15:
        sky = np.linspace(1, 0, h, dtype=np.float32)[:, None, None] ** rng.uniform(1, 4)
        pic = pic * (1 - sky) + np.float32(rand_colour(rng, "page")) * sky
    # Date stamp, like the orange digits from compact cameras.
    if rng.random() < 0.25:
        txt = f"{rng.randint(1, 31)} {rng.randint(1, 12)} '{rng.randint(80, 99)}" if rng.random() < 0.5 else f"{rng.randint(1, 12)}:{rng.randint(10, 59)}PM"
        fs = h / 220
        stamp = np.zeros((h, w), np.uint8)
        cv2.putText(stamp, txt, (int(w * 0.62), int(h * 0.92)), cv2.FONT_HERSHEY_SIMPLEX, fs, 255, max(1, int(fs * 2)), cv2.LINE_AA)
        a = (stamp.astype(np.float32) / 255)[..., None]
        pic = np.clip(pic, 0, 255) * (1 - a) + np.float32([40, 120, 255]) * a

    if rng.random() < 0.3:  # white (or cream) border, even all round
        b = max(2, int(min(w, h) * rng.uniform(0.03, 0.08)))
        paper = np.float32([rng.uniform(215, 250)] * 3) - np.float32([rng.uniform(0, 20), rng.uniform(0, 8), 0])
        bordered = np.empty((h, w, 3), np.float32)
        bordered[:] = paper
        inner = cv2.resize(pic, (w - 2 * b, h - 2 * b), interpolation=cv2.INTER_AREA)
        bottom = b if rng.random() < 0.85 else min(h // 4, b * 3)  # Polaroid-style deep bottom now and then
        inner = cv2.resize(pic, (w - 2 * b, h - b - bottom), interpolation=cv2.INTER_AREA)
        bordered[b:h - bottom, b:w - b] = inner
        pic = bordered
    return np.clip(pic, 0, 255)


def graphic(rng, w, h):
    """A flat, cartoon-like picture: a gradient with discs and blocks."""
    top, bottom = np.float32(rand_colour(rng, "bg")), np.float32(rand_colour(rng, "bg"))
    t = np.linspace(0, 1, h, dtype=np.float32)[:, None, None]
    pic = np.ascontiguousarray(np.broadcast_to(top * (1 - t) + bottom * t, (h, w, 3)).astype(np.float32))
    for _ in range(rng.randint(1, 5)):
        c = tuple(float(v) for v in rand_colour(rng, "bg"))
        if rng.random() < 0.5:
            cv2.circle(pic, (int(rng.uniform(0, w)), int(rng.uniform(0, h))), int(rng.uniform(0.05, 0.25) * min(w, h)), c, -1, cv2.LINE_AA)
        else:
            x, y = rng.uniform(0, w * 0.8), rng.uniform(0, h * 0.8)
            cv2.rectangle(pic, (int(x), int(y)), (int(x + rng.uniform(0.05, 0.3) * w), int(y + rng.uniform(0.05, 0.3) * h)), c, -1)
    return pic


def shapes_inside(rng, photos, pic):
    """Rectangles inside the picture (windows, doors, frames on a wall, a photo held in the photo): they
    belong to the picture and must never count as prints."""
    h, w = pic.shape[:2]
    for _ in range(rng.randint(1, 3)):
        rw, rh = w * rng.uniform(0.12, 0.5), h * rng.uniform(0.12, 0.5)
        q = rotate(rect(rng.uniform(0, w - rw), rng.uniform(0, h - rh), rw, rh), rng.uniform(-8, 8))
        kind = rng.random()
        if kind < 0.4:
            fill_quad(pic, q, tuple(float(v) for v in rand_colour(rng, "bg")))
        else:
            inner = cv2.resize(photos.any(rng, small=True), (max(4, int(rw)), max(4, int(rh)))).astype(np.float32)
            if kind < 0.7:  # a framed picture or a held photo: with a border
                b = max(1, int(min(rw, rh) * rng.uniform(0.04, 0.12)))
                inner = cv2.copyMakeBorder(inner[b:-b or None, b:-b or None], b, b, b, b, cv2.BORDER_CONSTANT,
                                           value=tuple(float(v) for v in rand_colour(rng, "page")))
                inner = cv2.resize(inner, (max(4, int(rw)), max(4, int(rh))))
            fill_quad(pic, q, inner)


# ---------------------------------------------------------------- pages

def layout_slots(rng, pw, ph):
    """Pocket rectangles on a page pw x ph."""
    kind = rng.random()
    if kind < 0.15:  # one or two big prints, loose on a table or a plain page
        n = 1 if rng.random() < 0.7 else 2
        slots = []
        for _ in range(20):
            if len(slots) >= n:
                break
            w = pw * (rng.uniform(0.55, 0.92) if n == 1 else rng.uniform(0.4, 0.7))
            h = w * rng.choice([2 / 3, 3 / 4, 1.0, 4 / 3, 3 / 2])
            if h > ph * 0.92:
                h = ph * rng.uniform(0.6, 0.9); w = h * rng.choice([2 / 3, 3 / 4, 1.0])
            q = rotate(rect(rng.uniform(0, pw - w), rng.uniform(0, ph - h), w, h), rng.uniform(-10, 10))
            if all(not overlaps(q, s_) for s_ in slots):
                slots.append(q)
        return "free", slots
    if kind < 0.8:  # sleeve grid
        rows, cols = rng.choice([(3, 1), (2, 1), (1, 1), (2, 2), (3, 2), (2, 3), (1, 2), (4, 2), (3, 3)])
        mx, my = pw * rng.uniform(0.03, 0.1), ph * rng.uniform(0.03, 0.08)
        gx, gy = pw * rng.uniform(0.015, 0.06), ph * rng.uniform(0.012, 0.05)
        cw = (pw - 2 * mx - (cols - 1) * gx) / cols
        chh = (ph - 2 * my - (rows - 1) * gy) / rows
        return "grid", [rect(mx + c * (cw + gx), my + r * (chh + gy), cw, chh) for r in range(rows) for c in range(cols)]
    # free layout: magnetic page or scrapbook, prints at small angles
    n = rng.randint(1, 5)
    slots = []
    for _ in range(n * 6):
        if len(slots) >= n:
            break
        w = pw * rng.uniform(0.3, 0.6); h = w * rng.choice([2 / 3, 3 / 4, 1.0, 4 / 3, 3 / 2])
        if h > ph * 0.6:
            continue
        x = rng.uniform(0, pw - w); y = rng.uniform(0, ph - h)
        q = rotate(rect(x, y, w, h), rng.uniform(-12, 12))
        if all(not overlaps(q, s) for s in slots):
            slots.append(q)
    return "free", slots


def overlaps(a, b, gap=6):
    ax0, ay0 = a.min(0) - gap; ax1, ay1 = a.max(0) + gap
    bx0, by0 = b.min(0); bx1, by1 = b.max(0)
    return not (ax1 < bx0 or bx1 < ax0 or ay1 < by0 or by1 < ay0)


def draw_page(rng, photos, canvas, page_quad, prints_out):
    """Draws an album page with its pockets and prints onto [canvas] (page-aligned). Appends print quads."""
    x0, y0 = page_quad[0]
    pw = page_quad[1][0] - x0
    ph = page_quad[2][1] - y0
    page_col = np.float32(rand_colour(rng, "page"))
    page = texture(rng, int(ph), int(pw), page_col, rng.uniform(1, 6))
    kind, slots = layout_slots(rng, pw, ph)
    sleeve = kind == "grid" and rng.random() < 0.85
    for slot in slots:
        if sleeve:
            # Pocket card: page-coloured or a paler card, with a seam line round it.
            card = page_col + np.float32([rng.uniform(-12, 25)] * 3) if rng.random() < 0.6 else page_col
            fill_quad(page, slot, tuple(float(v) for v in np.clip(card, 0, 255)))
            seam = tuple(float(v) for v in np.clip(page_col * rng.uniform(0.75, 1.1), 0, 255))
            cv2.polylines(page, [np.round(slot * 8).astype(np.int32)], True, seam, max(1, int(rng.uniform(1, 4))), cv2.LINE_AA, shift=3)
        sw = slot[:, 0].max() - slot[:, 0].min()
        sh = slot[:, 1].max() - slot[:, 1].min()
        if sleeve:
            # The print is a bit smaller than its pocket and slid somewhere inside.
            fw, fh = rng.uniform(0.78, 0.99), rng.uniform(0.78, 0.99)
            w, h = sw * fw, sh * fh
            q = rect(slot[0][0] + rng.uniform(0, sw - w), slot[0][1] + rng.uniform(0, sh - h), w, h)
            q = rotate(q, rng.uniform(-1.5, 1.5))
        elif kind == "grid":
            q = rotate(scale(slot, rng.uniform(0.8, 0.98)), rng.uniform(-3, 3))
            w, h = sw * 0.9, sh * 0.9
        else:
            q = slot
            w = np.linalg.norm(q[1] - q[0]); h = np.linalg.norm(q[3] - q[0])
        if rng.random() < 0.06:
            continue  # an empty pocket
        pr = make_print(rng, photos, max(8, int(w)), max(8, int(h)))
        if rng.random() < 0.5:  # a soft shadow under the print
            sh_q = q + np.float32([rng.uniform(-3, 3), rng.uniform(0, 4)])
            ph_, pw_ = page.shape[:2]
            sx0 = max(0, int(sh_q[:, 0].min()) - 8); sy0 = max(0, int(sh_q[:, 1].min()) - 8)
            sx1 = min(pw_, int(sh_q[:, 0].max()) + 9); sy1 = min(ph_, int(sh_q[:, 1].max()) + 9)
            if sx1 > sx0 and sy1 > sy0:
                roi = page[sy0:sy1, sx0:sx1]
                shadow = roi.copy()
                fill_quad(shadow, sh_q - [sx0, sy0], tuple(float(v) for v in page_col * 0.6))
                shadow = cv2.GaussianBlur(shadow, (0, 0), 2)
                a = rng.uniform(0.2, 0.6)
                roi[:] = roi * (1 - a) + shadow * a
        fill_quad(page, q, pr)
        if not sleeve and kind == "free" and rng.random() < 0.3:  # photo corners
            for c in q:
                tri = np.float32([c, c + (q.mean(0) - c) * np.float32([0.12, 0]), c + (q.mean(0) - c) * np.float32([0, 0.12])])
                cv2.fillConvexPoly(page, np.round(tri * 8).astype(np.int32), (20, 20, 20), cv2.LINE_AA, shift=3)
        prints_out.append(q + np.float32([x0, y0]))
    if sleeve and rng.random() < 0.8:  # the sleeve itself: a faint haze over everything
        haze = rng.uniform(0.02, 0.12)
        page = page * (1 - haze) + 255 * haze
    ys, xs = int(y0), int(x0)
    h = min(page.shape[0], canvas.shape[0] - ys)
    w = min(page.shape[1], canvas.shape[1] - xs)
    canvas[ys:ys + h, xs:xs + w] = page[:h, :w]


def scene(rng, photos):
    """Returns (bgr uint8 OUT_H x OUT_W, list of print quads in output pixels)."""
    W, H = int(OUT_W * SS), int(OUT_H * SS)
    # Draw on a flat canvas bigger than the frame, then view it through a tilted camera.
    pages = 1 if rng.random() < 0.6 else 2
    pw = W * rng.uniform(0.75, 1.1)
    ph = pw * rng.uniform(1.15, 1.45) if rng.random() < 0.75 else pw * rng.uniform(0.7, 1.0)
    cw, ch = int(max(W * 1.6, pw * pages + W * 0.5)), int(max(H * 1.6, ph + H * 0.5))
    if rng.random() < 0.7:
        canvas = texture(rng, ch, cw, np.float32(rand_colour(rng, "bg")), rng.uniform(3, 20))
    else:
        canvas = photo_background(rng, photos, ch, cw)
    prints = []
    ox = (cw - pw * pages) / 2
    oy = (ch - ph) / 2
    for p in range(pages):
        draw_page(rng, photos, canvas, rect(ox + p * pw, oy, pw - 2, ph), prints)
    if pages == 2:  # the spine
        sx = int(ox + pw)
        band = int(rng.uniform(6, 30))
        dark = np.linspace(1, 0, band, dtype=np.float32) ** 2 * rng.uniform(0.2, 0.6)
        for i, d in enumerate(dark):
            for xx in (sx + i, sx - i - 1):
                if 0 <= xx < cw:
                    canvas[:, xx] *= 1 - d

    # Camera: mostly framed on one page, the way people scan; now and then wider or closer.
    page = rng.randrange(pages)
    centre = np.float32([ox + pw * page + pw / 2, oy + ph / 2])
    fit = max(pw / W, ph / H)  # zoom at which the page just fits
    view_w = W * fit * (rng.uniform(0.95, 1.3) if rng.random() < 0.75 else rng.uniform(0.6, 1.8))
    view_h = view_w * H / W
    src = rect(centre[0] - view_w / 2, centre[1] - view_h / 2, view_w, view_h)
    r = rng.random()
    angle = rng.uniform(-6, 6) if r < 0.7 else rng.uniform(-18, 18) if r < 0.9 else rng.choice([90, -90]) + rng.uniform(-8, 8)
    src = rotate(src, angle)
    src = src + np.float32([rng.uniform(-0.1, 0.1) * view_w, rng.uniform(-0.08, 0.08) * view_h])
    # Perspective: pull two adjacent corners in.
    tilt = rng.uniform(0, 0.15)
    side = rng.randrange(4)
    a, b = side, (side + 1) % 4
    for i in (a, b):
        src[i] = src[i] + (src.mean(0) - src[i]) * tilt
    dst = rect(0, 0, W, H)
    m = cv2.getPerspectiveTransform(np.float32(src), dst)
    img = cv2.warpPerspective(canvas, m, (W, H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    # Past the drawn canvas: more background (never a mirror image, which would copy prints without labels).
    seen = cv2.warpPerspective(np.ones((ch, cw), np.float32), m, (W, H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    if seen.min() < 1:
        more = texture(rng, H, W, np.float32(rand_colour(rng, "bg")), rng.uniform(3, 20))
        img = img + more * (1 - seen[..., None])
    quads = [cv2.perspectiveTransform(q[None], m)[0] for q in prints]

    img = lighting(rng, img)
    img = cv2.resize(img, (OUT_W, OUT_H), interpolation=cv2.INTER_AREA)
    img = camera(rng, img)
    quads = [q / SS for q in quads]
    return img, quads


def lighting(rng, img):
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    # Uneven light: a tilted gradient and a vignette.
    g = 1 + ((xx / w - 0.5) * rng.uniform(-0.5, 0.5) + (yy / h - 0.5) * rng.uniform(-0.5, 0.5))
    if rng.random() < 0.5:
        g *= 1 - rng.uniform(0, 0.35) * (((xx / w - 0.5) ** 2 + (yy / h - 0.5) ** 2) * 2)
    img = img * g[..., None]
    # A soft shadow (phone, hand).
    if rng.random() < 0.35:
        pts = np.float32([[rng.uniform(-0.2, 1.2) * w, rng.uniform(-0.2, 1.2) * h] for _ in range(5)])
        hull = cv2.convexHull(pts).astype(np.int32)
        sh = np.zeros((h, w), np.float32)
        cv2.fillConvexPoly(sh, hull, 1.0)
        sh = cv2.GaussianBlur(sh, (0, 0), rng.uniform(10, 40))
        img = img * (1 - sh[..., None] * rng.uniform(0.2, 0.55))
    # Glare on the sleeve: bright blobs and streaks that wash out whatever is under them.
    for _ in range(rng.choice([0, 0, 0, 1, 1, 2])):
        gl = np.zeros((h, w), np.float32)
        c = (int(rng.uniform(0, w)), int(rng.uniform(0, h)))
        axes = (int(rng.uniform(0.03, 0.3) * w), int(rng.uniform(0.01, 0.12) * h))
        cv2.ellipse(gl, c, axes, rng.uniform(0, 180), 0, 360, 1.0, -1)
        gl = cv2.GaussianBlur(gl, (0, 0), rng.uniform(3, 25))
        img = img * (1 - gl[..., None] * 0.85) + 255 * gl[..., None] * rng.uniform(0.6, 0.95)
    # Exposure and white balance.
    img = img * rng.uniform(0.7, 1.25) * np.float32([rng.uniform(0.85, 1.15), 1, rng.uniform(0.85, 1.15)])
    return img


def camera(rng, img):
    if rng.random() < 0.4:
        img = cv2.GaussianBlur(img, (0, 0), rng.uniform(0.3, 1.2))
    if rng.random() < 0.15:  # a little motion blur
        k = rng.choice([3, 5])
        kern = np.zeros((k, k), np.float32)
        kern[k // 2] = 1
        kern = cv2.warpAffine(kern, cv2.getRotationMatrix2D((k / 2 - 0.5, k / 2 - 0.5), rng.uniform(0, 180), 1), (k, k))
        img = cv2.filter2D(img, -1, kern / max(kern.sum(), 1e-6))
    img = img + np.random.default_rng(rng.randrange(1 << 30)).normal(0, rng.uniform(0, 6), img.shape).astype(np.float32)
    img = np.clip(img, 0, 255).astype(np.uint8)
    if rng.random() < 0.6:
        ok, enc = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, int(rng.uniform(55, 95))])
        img = cv2.imdecode(enc, cv2.IMREAD_COLOR)
    return img


# ---------------------------------------------------------------- targets

def targets(quads, w=OUT_W, h=OUT_H):
    """Three maps: print (any print), core (each print shrunk about its centre, never touching another),
    and edge (a thin band along every print's outline)."""
    pr = np.zeros((h, w), np.float32)
    core = np.zeros((h, w), np.float32)
    edge = np.zeros((h, w), np.float32)
    for q in quads:
        cv2.fillConvexPoly(pr, np.round(q * 8).astype(np.int32), 1.0, cv2.LINE_AA, shift=3)
    for q in quads:
        cv2.fillConvexPoly(core, np.round(scale(q, CORE) * 8).astype(np.int32), 1.0, cv2.LINE_AA, shift=3)
        cv2.polylines(edge, [np.round(q * 8).astype(np.int32)], True, 1.0, 2, cv2.LINE_AA, shift=3)
    return np.stack([pr, core, np.clip(edge, 0, 1)])


if __name__ == "__main__":
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/synth"
    os.makedirs(out, exist_ok=True)
    photos = Photos()
    for i in range(int(sys.argv[2]) if len(sys.argv) > 2 else 12):
        rng = random.Random(i)
        img, quads = scene(rng, photos)
        t = targets(quads)
        vis = img.copy()
        for q in quads:
            cv2.polylines(vis, [np.round(q).astype(np.int32)], True, (0, 0, 255), 1)
        cv2.imwrite(f"{out}/{i:03d}.jpg", np.hstack([vis, cv2.cvtColor((t[1] * 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)]))
