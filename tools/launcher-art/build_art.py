#!/usr/bin/env python3
"""Rebuild the launcher's version-family art (src/OriginLauncher.App/Assets/Art/*.jpg).

Re-runnable: downloads Mojang's official wallpaper packs, takes the largest lossless
16:9 image of each (never an upscale), and writes every tier the launcher loads:

    <key>-c<W>.jpg   colour layer (the launch bloom), W = 3840 / 2560 / 1280
    <key>-g<W>.jpg   greyscale Home layer
    <key>-tc.jpg     picker thumbnail, colour (480x270)
    <key>-tg.jpg     picker thumbnail, grey

Look (measured from the art the launcher shipped before, so nothing changes but sharpness):
    colour = source * 0.55          (uniform darken, sRGB)
    grey   = Rec.601 luma * 0.40 + 3.5/255

A tier is only written when the source has at least that many pixels across. A family
whose best source is narrower gets its native width stored in the highest tier it can
fill (the launcher stretches it; nothing is resampled upward here). ArtStage.cs probes
3840 -> 2560 -> 1280 and uses the first file that exists.

Usage (from the repo root):
    python tools/launcher-art/build_art.py [--cache DIR] [--out DIR] [--only 1.21,26]

Needs: Pillow, numpy. minecraft.net serves the zips only to a browser User-Agent.
Every source URL is also recorded in src/OriginLauncher.App/Assets/SOURCES.md.
"""
from __future__ import annotations

import argparse
import io
import os
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None

BASE = "https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/software/"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

TIERS = (3840, 2560, 1280)
THUMB = (480, 270)
QUALITY = 92          # 4:4:4 chroma, so colour edges stay as sharp as the luma
COLOUR_GAIN = 0.55
GREY_GAIN = 0.40
GREY_LIFT = 3.5 / 255

# key -> (zip file name, nested zips to open in order, member path, crop box or None)
FAMILIES = {
    "26": ("wallpapers_tiny_takeover_drop.zip", [],
           "wallpaper_tiny_takeover_drop/wallpaper_tiny_takeover_drop/MCV_SPR26Drop_TT_DotNet_Wallpaper_2560x1440.png", None),
    "1.21": ("wallpapers_tricky_trials_update2.zip", [],
             "wallpaper_minecraft_trickytrials_2560x1440.png", None),
    # Largest source with the logo is 2058x1440; this box (the art below the logo) is 1842x1036 native
    # (the logo's lowest pixels end at row 402, so the box starts at 404).
    "1.20": ("Minecraft_Trails_and_Tales_.Net.zip", [],
             "Minecraft_Trails&Tales_.Net_2058x1440.png", (108, 404, 1950, 1440)),
    "1.19": ("wallpaper_minecraft_wild_update.zip", ["wallpaper_minecraft_wild_update.zip"],
             "wallpaper_minecraft_wild_update_2560x1440.png", None),
    "1.18": ("wallpapers_minecraft_caves_cliffs(part2).zip", [],
             "wallpaper_minecraft_caves_cliffs(part2)_2560x1440.png", None),
    "1.17": ("wallpapers_minecraft_caves_cliffs(part1).zip", [],
             "wallpaper_minecraft_caves_cliffs(part1)_2560x1440.png", None),
    "1.16": ("wallpapers_minecraft_nether_update.zip", [],
             "wallpaper_minecraft_nether_update_2560x1440.png", None),
    "1.8": ("wallpapers_minecraft_java_edition.zip", [],
            "wallpaper_minecraft_java_edition_2560x1440.png", None),
}


def fetch_zip(name: str, cache: str) -> str:
    path = os.path.join(cache, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    url = BASE + urllib.parse.quote(name, safe="._-")
    print(f"  downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=300) as r, open(path, "wb") as f:
        f.write(r.read())
    return path


def load_source(key: str, cache: str) -> Image.Image:
    name, nested, member, crop = FAMILIES[key]
    z = zipfile.ZipFile(fetch_zip(name, cache))
    for inner in nested:
        z = zipfile.ZipFile(io.BytesIO(z.read(inner)))
    im = Image.open(io.BytesIO(z.read(member))).convert("RGB")
    if crop:
        im = im.crop(crop)
    return im


def to_float(im: Image.Image) -> np.ndarray:
    return np.asarray(im, dtype=np.float32)


def resize_float(a: np.ndarray, w: int, h: int) -> np.ndarray:
    """Lanczos on float planes (no 8-bit rounding before the tone curve)."""
    if a.shape[1] == w:
        return a
    planes = [np.asarray(Image.fromarray(a[..., c], mode="F").resize((w, h), Image.LANCZOS)) for c in range(a.shape[2])]
    return np.stack(planes, axis=2)


def colour_layer(rgb: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(rgb * COLOUR_GAIN), 0, 255).astype(np.uint8), "RGB")


def grey_layer(rgb: np.ndarray) -> Image.Image:
    luma = rgb @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    return Image.fromarray(np.clip(np.rint(luma * GREY_GAIN + GREY_LIFT * 255), 0, 255).astype(np.uint8), "L")


def save(im: Image.Image, path: str) -> int:
    im.save(path, "JPEG", quality=QUALITY, subsampling=0, optimize=True)
    return os.path.getsize(path)


def build(key: str, cache: str, out: str) -> None:
    src = load_source(key, cache)
    sw, sh = src.size
    print(f"{key}: source {sw}x{sh}")
    rgb = to_float(src)
    # Tiers the source can fill are plain downscales. If the source sits well between two tiers
    # (1.20: 1852 px), its native width goes in the next tier up, so the launcher still gets
    # the whole picture at full detail without a pre-upscaled file.
    fill = [t for t in TIERS if t <= sw]
    over = [t for t in TIERS if t > sw]
    plan = [(t, t) for t in fill]
    if over and fill and sw >= 1.3 * max(fill):
        plan.append((min(over), sw))
    elif over and not fill:
        plan.append((min(over), sw))
    for tier, w in sorted(plan, reverse=True):
        h = round(w * sh / sw)
        scaled = resize_float(rgb, w, h)
        sc = save(colour_layer(scaled), os.path.join(out, f"{key}-c{tier}.jpg"))
        sg = save(grey_layer(scaled), os.path.join(out, f"{key}-g{tier}.jpg"))
        print(f"  tier {tier}: {w}x{h}  c {sc // 1024} KB  g {sg // 1024} KB")
    # Thumbnails are always downscales of the full source.
    th = resize_float(rgb, *THUMB)
    save(colour_layer(th), os.path.join(out, f"{key}-tc.jpg"))
    save(grey_layer(th), os.path.join(out, f"{key}-tg.jpg"))


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.join(tempfile.gettempdir(), "origin-launcher-art-src"))
    ap.add_argument("--out", default=os.path.join(root, "src", "OriginLauncher.App", "Assets", "Art"))
    ap.add_argument("--only", default="", help="comma-separated family keys")
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs(args.out, exist_ok=True)
    keys = [k for k in args.only.split(",") if k] or list(FAMILIES)
    for k in keys:
        build(k, args.cache, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
