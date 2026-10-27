#!/usr/bin/env python3
"""Seam check for a 6-face title panorama: are the cube edges continuous?

  seam_check.py <dir-with-panorama_0..5.png> [--max N]

Minecraft's title screen draws panorama_0..3 as the four walls of a cube (face i+1 is face i turned
90 degrees right), 4 the ceiling, 5 the floor. Where two faces meet, the last pixel column of one and
the first of the next show (almost) the same ray, so a clean capture has a small difference there --
about the same as between two neighbouring columns inside a face. A per-face darkening (vignette,
auto-exposure that changed between faces, a lens effect) shows as a dark line at every wall edge.

For each of the 4 wall edges it prints, over the sky rows (top third) and the whole height:
  seam  = mean |a[:, -1] - b[:, 0]|   (RGB 0..255) across the edge
  inner = mean |a[:, -2] - a[:, -1]|  the same measure one column inside the face (the noise floor)
  dark  = mean luminance of the 3 edge columns / luminance 24 px further in (1.0 = no vignette)
and the 4 ceiling edges (face 4's border against the top row of each wall), same seam measure.
Exit 1 when any wall seam (sky rows) exceeds max(--max (default 6), 1.5 x inner) or dark < 0.93.
The reported worst seam is scaled to --max (so 6.0 = at the limit).
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image


def load(d):
    out = []
    for i in range(6):
        im = Image.open(os.path.join(d, f"panorama_{i}.png")).convert("RGB")
        out.append(np.asarray(im).astype(np.float32))
    return out


def lum(a):
    return (0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]).mean()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--max", type=float, default=6.0)
    a = ap.parse_args()
    f = load(a.dir)
    n = f[0].shape[0]
    sky = slice(0, n // 3)
    worst = 0.0
    darkest = 1.0
    for i in range(4):
        x, y = f[i], f[(i + 1) % 4]
        seam_sky = float(np.abs(x[sky, -1] - y[sky, 0]).mean())
        seam_all = float(np.abs(x[:, -1] - y[:, 0]).mean())
        inner = float(np.abs(x[sky, -2] - x[sky, -1]).mean())
        edge = (lum(x[sky, -3:]) + lum(y[sky, :3])) / 2
        mid = (lum(x[sky, -27:-24]) + lum(y[sky, 24:27])) / 2
        dark = edge / mid if mid > 1 else 1.0
        # Busy skies (clouds, stars) differ column to column anyway: judge the edge against that noise floor.
        limit = max(a.max, 1.5 * inner)
        worst = max(worst, seam_sky / limit * a.max)
        darkest = min(darkest, dark)
        print(f"wall {i}|{(i + 1) % 4}: seam sky {seam_sky:5.1f} all {seam_all:5.1f}  inner {inner:4.1f}  dark {dark:.3f}")
    # Ceiling (face 4) borders: which border meets which wall depends on vanilla's cube orientation, so
    # report the best match per wall (the smallest difference among face 4's four borders).
    top = f[4]
    borders = {"top": top[0], "bottom": top[-1], "left": top[:, 0], "right": top[:, -1]}
    for i in range(4):
        row = f[i][0]
        best = min(min(float(np.abs(row - b).mean()), float(np.abs(row - b[::-1]).mean())) for b in borders.values())
        print(f"ceiling|wall {i}: best seam {best:5.1f}")
    ok = worst <= a.max and darkest >= 0.93
    print(f"seam_check: worst wall seam {worst:.1f} (max {a.max}), darkest edge {darkest:.3f} -> {'PASS' if ok else 'FAIL'}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
