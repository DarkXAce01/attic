"""
Downloads the photos that go on the synthetic prints: a few thousand pictures from the Unsplash Lite
dataset (free for commercial and non-commercial use), small, since a print is at most a few hundred
pixels in a training frame.

    python ml/fetch_photos.py [count]
"""
import concurrent.futures
import csv
import io
import os
import sys
import urllib.request
import zipfile

ROOT = os.path.expanduser("~/.cache/attic-ml/data")
ZIP_URL = "https://unsplash-datasets.s3.amazonaws.com/lite/latest/unsplash-research-dataset-lite-latest.zip"
OUT = os.path.join(ROOT, "photos")


class RemoteFile(io.RawIOBase):
    """A read-only, seekable view of a file on a server, fetched in ranges: only the photo table is read
    out of the 320 MB archive."""

    def __init__(self, url):
        self.url = url
        self.pos = 0
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD")) as r:
            self.size = int(r.headers["Content-Length"])

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=0):
        self.pos = {0: offset, 1: self.pos + offset, 2: self.size + offset}[whence]
        return self.pos

    def readinto(self, b):
        if self.pos >= self.size:
            return 0
        end = min(self.size, self.pos + max(len(b), 1 << 20)) - 1
        req = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{end}"})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        n = min(len(b), len(data))
        b[:n] = data[:n]
        self.pos += n
        return n


def rows():
    with zipfile.ZipFile(io.BufferedReader(RemoteFile(ZIP_URL), 1 << 20)) as z:
        name = next(n for n in z.namelist() if n.startswith("photos.tsv"))
        with z.open(name) as f:
            yield from csv.DictReader(io.TextIOWrapper(f, "utf-8"), delimiter="\t")


def fetch(row):
    path = os.path.join(OUT, row["photo_id"] + ".jpg")
    if os.path.exists(path):
        return True
    url = row["photo_image_url"] + "?w=640&q=80&fm=jpg"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            data = r.read()
        with open(path, "wb") as f:
            f.write(data)
        return True
    except Exception:
        return False


if __name__ == "__main__":
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 4000
    os.makedirs(OUT, exist_ok=True)
    todo = []
    for r in rows():
        if len(todo) >= count:
            break
        todo.append(r)
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        ok = sum(ex.map(fetch, todo))
    print(f"{ok}/{len(todo)} photos in {OUT}")
