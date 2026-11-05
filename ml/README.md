# The print finder

Attic finds prints with a small neural network (about 250k parameters, 1 MB) trained only on synthetic
album pages. It runs in the app through OpenCV's dnn module, which Attic already ships, so there's no
extra runtime and no network access.

## Pipeline

| Step | Script | What it does |
|---|---|---|
| 1 | `fetch_photos.py` | Downloads ~5,000 photos from the Unsplash Lite dataset (free for any use) to put on the prints. |
| 2 | `make_dataset.py` | Draws synthetic album pages to disk: sleeves, pockets bigger than their prints, white borders, glare, shadows, perspective, blur, faded colour. |
| 3 | `train.py` | Trains the network on those pages and scores it on a fixed set of validation pages. |
| 4 | `export.py` | Writes `app/src/main/assets/printnet.onnx` and checks OpenCV gives the same output as PyTorch. |
| 5 | `eval_real.py` | Optional. Scores the network on your own pages in `imgs/` (gitignored), printing numbers only. |

`synth.py` draws the pages, `model.py` is the network, and `quads.py` turns its output into outlines.
The app's `vision/PrintNet.kt` mirrors `quads.py`, so change the two together.

## Setup

Everything lives under `~/.cache/attic-ml`, outside the repo.

```
python3 -m venv ~/.cache/attic-ml/venv
PY=~/.cache/attic-ml/venv/bin/python
mkdir -p ~/.cache/attic-ml/tmp
TMPDIR=~/.cache/attic-ml/tmp $PY -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128
$PY -m pip install opencv-python-headless numpy onnx onnxscript
cd ml
$PY fetch_photos.py 5000
$PY make_dataset.py 80000 8
$PY train.py --steps 30000 --batch 12 --workers 4
$PY export.py
```

Set `TMPDIR` for pip because `/tmp` on WSL is held in RAM and the CUDA wheels are several GB.

## The network's output

Three maps, each 288 x 384:

- **print:** any print paper, including a white border.
- **core:** each print shrunk to 60% about its centre, so neighbouring prints never touch.
- **edge:** a thin band along every print's outline.

To get outlines, each core marks one print. Every print pixel belongs to the nearest core, four corners
are fitted to each print's own pixels, and every side is then settled where the print map crosses one
half. Prints cut off by the frame are left out, since they can't be captured whole.

## The date reader

`stamp.py` draws camera date stamps ('87 7 14 in glowing orange seven-segment digits) onto Unsplash
photos, in the formats cameras used, with the glow, fading and camera blur of a real print. About half
the samples have no stamp, and some have orange text that isn't a date. `python ml/stamp.py preview`
(with `OUT` set to a folder) writes a grid of samples to look at.

`stamp_train.py train` trains a small all-convolution network on endless strips (no dataset to
generate), checked against strips drawn from photos it never trains on. `stamp_train.py export` writes
`app/src/main/assets/datestamp.onnx` and checks OpenCV runs it the same as PyTorch.

The network reads one 48 x 192 strip: the print's bottom-right corner, 0.2 x 0.8 of its short side. It
gives, for each of 48 slices across the strip, how likely each character is (digits, apostrophe, space,
or nothing); the app merges repeats and drops the nothings (CTC), and tries all four turns of the print.
