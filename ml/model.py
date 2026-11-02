"""
The print finder: a small U-Net that reads a 288 x 384 RGB frame and gives three maps, all 0..1:
  0  print  any print paper (border included)
  1  core   each print shrunk to 60% about its centre, so prints that touch still come apart
  2  edge   a thin band along every print's outline, for sharp sides

Only operations OpenCV's dnn module runs (conv, batch norm, ReLU, nearest resize, concat, sigmoid), so
the same ONNX file runs in the app and in the desktop unit tests with no extra runtime.
"""
import torch
import torch.nn as nn


def conv_bn(cin, cout, k=3, stride=1, groups=1, dilation=1):
    pad = dilation * (k // 2)
    return nn.Sequential(
        nn.Conv2d(cin, cout, k, stride, pad, dilation=dilation, groups=groups, bias=False),
        nn.BatchNorm2d(cout),
        nn.ReLU(inplace=True),
    )


class Block(nn.Module):
    """Two depthwise-separable convs; the first may downsample."""

    def __init__(self, cin, cout, stride=1, dilation=1):
        super().__init__()
        self.body = nn.Sequential(
            conv_bn(cin, cin, 3, stride, groups=cin, dilation=dilation),
            conv_bn(cin, cout, 1),
            conv_bn(cout, cout, 3, 1, groups=cout, dilation=dilation),
            conv_bn(cout, cout, 1),
        )
        self.skip = stride == 1 and cin == cout

    def forward(self, x):
        y = self.body(x)
        return x + y if self.skip else y


class Up(nn.Module):
    def __init__(self, cin, cskip, cout):
        super().__init__()
        self.up = nn.Upsample(scale_factor=2, mode="nearest")
        self.fuse = nn.Sequential(conv_bn(cin + cskip, cout, 1), conv_bn(cout, cout, 3, groups=cout), conv_bn(cout, cout, 1))

    def forward(self, x, skip):
        return self.fuse(torch.cat([self.up(x), skip], 1))


class PrintNet(nn.Module):
    def __init__(self, w=1.0):
        super().__init__()
        c = [int(v * w) for v in (16, 24, 40, 64, 112)]
        self.full = conv_bn(3, 8)                       # 1/1, for sharp edges
        self.stem = conv_bn(3, c[0], 3, 2)              # 1/2
        self.e1 = Block(c[0], c[1], 2)                  # 1/4
        self.e2 = Block(c[1], c[2], 2)                  # 1/8
        self.e3 = Block(c[2], c[3], 2)                  # 1/16
        self.e4 = Block(c[3], c[4], 2)                  # 1/32
        # Wide context at the bottom: a print is told from its pocket by what's around it.
        self.ctx = nn.Sequential(Block(c[4], c[4], dilation=2), Block(c[4], c[4], dilation=4))
        self.d3 = Up(c[4], c[3], c[3])
        self.d2 = Up(c[3], c[2], c[2])
        self.d1 = Up(c[2], c[1], c[1])
        self.d0 = Up(c[1], c[0], c[0])
        self.dfull = Up(c[0], 8, 16)
        self.head = nn.Conv2d(16, 3, 3, padding=1)

    def forward(self, x):
        # x: RGB 0..255, N x 3 x H x W
        x = (x - 127.5) * (1 / 64.0)
        f = self.full(x)
        s = self.stem(x)
        e1 = self.e1(s)
        e2 = self.e2(e1)
        e3 = self.e3(e2)
        e4 = self.ctx(self.e4(e3))
        y = self.d3(e4, e3)
        y = self.d2(y, e2)
        y = self.d1(y, e1)
        y = self.d0(y, s)
        y = self.dfull(y, f)
        return self.head(y)  # logits; the exported model adds the sigmoid


class Exported(nn.Module):
    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, x):
        return torch.sigmoid(self.net(x))


def load(path):
    ck = torch.load(path, map_location="cpu")
    net = PrintNet(ck["width"])
    net.load_state_dict(ck["state"])
    return net.eval()


if __name__ == "__main__":
    n = PrintNet(1.5)
    print(sum(p.numel() for p in n.parameters()), "parameters")
    print(n(torch.zeros(1, 3, 384, 288)).shape)
