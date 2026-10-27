#!/usr/bin/env python3
"""Turn a panorama capture (6 raw 1024 px faces from OriginDevPanorama) into what ships.

  finish.py <shots-dir> <out-dir> [--size N]

- out/panorama_0..5.png : the faces at N px (spots.json faceSize), Lanczos-downscaled from the
  square capture, then palette-quantised with pngquant when it is installed (the menus blur the
  panorama, so 256 dithered colours are invisible there) -- these are the files that ship.
- out/preview.jpg : a 16:9 view of the cube from face 0's direction, the way the title opens on it,
  so a session can judge the scene from one image.
- prints one line per face with its byte size, and the total.
Exit 1 when a face is missing or not square.
"""
import argparse
import math
import os
import shutil
import subprocess
import sys

from PIL import Image


def faces_from(shots):
    out = []
    for i in range(6):
        p = os.path.join(shots, f"panorama_{i}.png")
        if not os.path.isfile(p):
            sys.exit(f"finish: {p} missing")
        im = Image.open(p).convert("RGB")
        if im.width != im.height:
            sys.exit(f"finish: {p} is {im.width}x{im.height}, not square")
        out.append(im)
    return out


def preview(faces, w=960, h=540, hfov=100.0):
    """Perspective view looking along face 0 (faces in Minecraft.grabPanoramixScreenshot order)."""
    n = faces[0].width
    px = [f.load() for f in faces]
    img = Image.new("RGB", (w, h))
    dst = img.load()
    tx = math.tan(math.radians(hfov) / 2)
    ty = tx * h / w
    for j in range(h):
        y = (1 - 2 * (j + 0.5) / h) * ty
        for i in range(w):
            x = (2 * (i + 0.5) / w - 1) * tx
            z = 1.0
            ax, ay, az = abs(x), abs(y), abs(z)
            if az >= ax and az >= ay:
                f, u, v = (0, x / z, -y / z) if z > 0 else (2, x / z, y / z)
            elif ax >= ay:
                f, u, v = (1, -z / x, -y / x) if x > 0 else (3, -z / x, y / x)
            else:
                f, u, v = (4, x / y, z / y) if y > 0 else (5, -x / y, z / y)
            sx = min(n - 1, max(0, int((u + 1) / 2 * n)))
            sy = min(n - 1, max(0, int((v + 1) / 2 * n)))
            dst[i, j] = px[f][sx, sy]
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("shots")
    ap.add_argument("out")
    ap.add_argument("--size", type=int, default=768)
    a = ap.parse_args()
    faces = faces_from(a.shots)
    os.makedirs(a.out, exist_ok=True)
    preview(faces).save(os.path.join(a.out, "preview.jpg"), quality=88)
    quant = shutil.which("pngquant")
    total = 0
    for i, im in enumerate(faces):
        dst = os.path.join(a.out, f"panorama_{i}.png")
        small = im.resize((a.size, a.size), Image.LANCZOS) if im.width != a.size else im
        small.save(dst, optimize=True)
        if quant:
            subprocess.run([quant, "--force", "--skip-if-larger", "--speed", "1", "--quality", "80-98",
                            "--output", dst, dst], check=False)
        size = os.path.getsize(dst)
        total += size
        print(f"finish: panorama_{i}.png {a.size}px {size} bytes{' (pngquant)' if quant else ''}")
    print(f"finish: total {total} bytes for 6 faces")


if __name__ == "__main__":
    main()
