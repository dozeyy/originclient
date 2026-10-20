#!/usr/bin/env python3
"""Rebuild the launcher's version-family art (src/OriginLauncher.App/Assets/Art/*.jpg).

Re-runnable: downloads Mojang's official wallpaper packs (SHA-256 checked), takes the
largest lossless 16:9 image of each, and writes every tier the launcher loads:

    <key>-c<W>.jpg   colour layer (the launch bloom), W = 3840 / 2560 / 1280
    <key>-g<W>.jpg   greyscale Home layer
    <key>-tc.jpg     picker thumbnail, colour (480x270)
    <key>-tg.jpg     picker thumbnail, grey

Look (measured from the art the launcher shipped before, so nothing changes but sharpness):
    colour = source * 0.55          (uniform darken, sRGB)
    grey   = Rec.601 luma * 0.40 + 3.5/255
The tone is applied AFTER every resize, in float, so an upscale never sees darkened pixels.

Tiers without --upscale: Mojang's packs top out at 2560 px, so a tier is only written when the
source has at least that many pixels across (plain Lanczos downscales, nothing upscaled). A
source that sits well between two tiers (1.20: 1842 px) is stored at its native width in the
next tier up; ArtStage.cs probes 3840 -> 2560 -> 1280 and stretches whatever file it finds.

Tiers with --upscale <realesrgan-ncnn-vulkan.exe>: no official art is 3840 wide, so the 3840
tier (every family) and 1.20's 2560 tier are made by running the source through Real-ESRGAN
(model realesr-animevideov3, the smallest of x2/x3/x4 that reaches 3840 px), then a Lanczos
downscale to exactly 3840x2160 / 2560x1440. The 2560 and 1280 tiers of the other seven families
stay native. The shipped art is built with --upscale; see Assets/SOURCES.md.

Usage (from the repo root):
    python tools/launcher-art/build_art.py [--cache DIR] [--out DIR] [--only 1.21,26]
        [--upscale PATH\\realesrgan-ncnn-vulkan.exe] [--q3840 88]

Needs: Pillow, numpy. minecraft.net serves the zips only to a browser User-Agent.
The Real-ESRGAN tool: https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0
(realesrgan-ncnn-vulkan-20220424-windows.zip, 45474481 bytes, its models/ folder next to the exe).
"""
from __future__ import annotations

import argparse
import hashlib
import io
import os
import subprocess
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
UPSCALE_MODEL = "realesr-animevideov3"   # kept block edges and textures; realesrgan-x4plus smeared stone textures

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

# SHA-256 of each pack as downloaded 2026-10-20 (Last-Modified of the first: 2026-04-09).
ZIP_SHA256 = {
    "wallpapers_tiny_takeover_drop.zip": "c3c3c28065ef868e70cfeec16343b81449757518f70e8238079945b545087526",
    "wallpapers_tricky_trials_update2.zip": "45b9ebcad3ee4784f6a198116ccf20c76bfe49afa1030bf393fad8c285695003",
    "Minecraft_Trails_and_Tales_.Net.zip": "aaf762a8c5ca31e3d9c862f1a86cb2d4923a28b68471ce39c702ddb555220857",
    "wallpaper_minecraft_wild_update.zip": "853713c99b2f14a2f761928f27b0da07c216f799cba183da48910a3523f15242",
    "wallpapers_minecraft_caves_cliffs(part2).zip": "4eb81a2a9a2659626ce900307f99cfb92a1c6413ed7ef03c7ef6003b949dbe67",
    "wallpapers_minecraft_caves_cliffs(part1).zip": "0d0d87d354238994730c27553c06b20db289c2aca29f7d65cbc632092c4067f3",
    "wallpapers_minecraft_nether_update.zip": "d963067c264ed2eb332cf6f943d1a9568fbbd97a26ca5de868cb6decd5198775",
    "wallpapers_minecraft_java_edition.zip": "084c1cbefe0a224f13cdfcfdcf039508fef5e417e370944bc7ed61bc5765c929",
}


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_zip(name: str, cache: str) -> str:
    path = os.path.join(cache, name)
    if not (os.path.exists(path) and os.path.getsize(path) > 0):
        url = BASE + urllib.parse.quote(name, safe="._-")
        print(f"  downloading {url}")
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=300) as r, open(path, "wb") as f:
            f.write(r.read())
    got = sha256(path)
    if got != ZIP_SHA256[name]:
        sys.exit(f"{name}: SHA-256 {got} does not match the recorded {ZIP_SHA256[name]}. "
                 "Mojang replaced the pack; check the art still matches, then update ZIP_SHA256.")
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
    if a.shape[1] == w and a.shape[0] == h:
        return a
    planes = [np.asarray(Image.fromarray(a[..., c]).resize((w, h), Image.LANCZOS)) for c in range(a.shape[2])]
    return np.stack(planes, axis=2)


def colour_layer(rgb: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(np.rint(rgb * COLOUR_GAIN), 0, 255).astype(np.uint8))


def grey_layer(rgb: np.ndarray) -> Image.Image:
    luma = rgb @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    return Image.fromarray(np.clip(np.rint(luma * GREY_GAIN + GREY_LIFT * 255), 0, 255).astype(np.uint8))


def save(im: Image.Image, path: str, quality: int = QUALITY) -> int:
    im.save(path, "JPEG", quality=quality, subsampling=0, optimize=True)
    return os.path.getsize(path)


def ai_upscale(tool: str, key: str, src: Image.Image, work: str) -> np.ndarray:
    """Run the source through Real-ESRGAN; returns float RGB at least 3840 px wide."""
    scale = next(s for s in (2, 3, 4) if src.width * s >= 3840)
    os.makedirs(work, exist_ok=True)
    inp = os.path.join(work, f"{key}-in.png")
    outp = os.path.join(work, f"{key}-up{scale}.png")
    if not os.path.exists(outp):
        src.save(inp)
        print(f"  upscaling x{scale} with {UPSCALE_MODEL} ...")
        subprocess.run([tool, "-i", inp, "-o", outp, "-n", UPSCALE_MODEL, "-s", str(scale),
                        "-m", os.path.join(os.path.dirname(os.path.abspath(tool)), "models"), "-f", "png"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return to_float(Image.open(outp).convert("RGB"))


def write_tier(rgb: np.ndarray, key: str, tier: int, w: int, h: int, out: str, quality: int, note: str) -> None:
    scaled = resize_float(rgb, w, h)
    sc = save(colour_layer(scaled), os.path.join(out, f"{key}-c{tier}.jpg"), quality)
    sg = save(grey_layer(scaled), os.path.join(out, f"{key}-g{tier}.jpg"), quality)
    print(f"  tier {tier}: {w}x{h}  c {sc // 1024} KB  g {sg // 1024} KB  {note}")


def build(key: str, args: argparse.Namespace) -> None:
    src = load_source(key, args.cache)
    sw, sh = src.size
    print(f"{key}: source {sw}x{sh}")
    rgb = to_float(src)
    big = ai_upscale(args.upscale, key, src, args.upscale_cache) if args.upscale else None

    # Tiers the source can fill are plain downscales. If it sits well between two tiers
    # (1.20: 1842 px), its native width goes in the next tier up, so the launcher still gets
    # the whole picture at full detail without a pre-upscaled file. With --upscale the
    # upscaled image fills every tier above the native one instead (exactly 16:9).
    fill = [t for t in TIERS if t <= sw]
    over = [t for t in TIERS if t > sw]
    plan = {t: (rgb, t, round(t * sh / sw), "native") for t in fill}
    if big is not None:
        for t in over:
            plan[t] = (big, t, round(t * 9 / 16), "AI-upscaled")
    elif over and fill and sw >= 1.3 * max(fill):
        plan[min(over)] = (rgb, sw, sh, "native, stored in the next tier up")
    for tier in sorted(plan, reverse=True):
        data, w, h, note = plan[tier]
        write_tier(data, key, tier, w, h, args.out, args.q3840 if tier == 3840 else QUALITY, note)
    # Thumbnails are always downscales of the real source.
    th = resize_float(rgb, *THUMB)
    save(colour_layer(th), os.path.join(args.out, f"{key}-tc.jpg"))
    save(grey_layer(th), os.path.join(args.out, f"{key}-tg.jpg"))


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.join(tempfile.gettempdir(), "origin-launcher-art-src"))
    ap.add_argument("--out", default=os.path.join(root, "src", "OriginLauncher.App", "Assets", "Art"))
    ap.add_argument("--only", default="", help="comma-separated family keys")
    ap.add_argument("--upscale", default="", help="path to realesrgan-ncnn-vulkan.exe (makes the 3840 tier)")
    ap.add_argument("--upscale-cache", default=os.path.join(tempfile.gettempdir(), "origin-launcher-art-up"))
    ap.add_argument("--q3840", type=int, default=QUALITY, help="JPEG quality of the 3840 tier only")
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs(args.out, exist_ok=True)
    keys = [k for k in args.only.split(",") if k] or list(FAMILIES)
    for k in keys:
        build(k, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
