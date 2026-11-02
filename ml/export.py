"""
Exports a trained print finder to ONNX for the app, and checks OpenCV's dnn gives the same maps as PyTorch.

    python ml/export.py ~/.cache/attic-ml/runs/printnet/best.pt
"""
import os
import sys

import cv2
import numpy as np
import torch

from model import Exported, load

ASSET = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "printnet.onnx")


def main():
    ckpt = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/attic-ml/runs/printnet/best.pt")
    net = load(ckpt)
    model = Exported(net).eval()
    x = torch.rand(1, 3, 384, 288) * 255
    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    torch.onnx.export(model, x, ASSET, input_names=["rgb"], output_names=["maps"], opset_version=13, dynamo=False)
    with torch.no_grad():
        ref = model(x).numpy()
    dnn = cv2.dnn.readNetFromONNX(ASSET)
    dnn.setInput(x.numpy())
    got = dnn.forward()
    print(f"exported {os.path.getsize(ASSET) / 1e6:.2f} MB, OpenCV vs PyTorch max diff {np.abs(got - ref).max():.5f}")


if __name__ == "__main__":
    main()
