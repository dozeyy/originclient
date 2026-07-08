#!/usr/bin/env python3
"""Bake the Origin menu-button assets: a rounded-rect fill mask, a hairline
rounded-rect border mask, a soft hover glow, and the Inter button labels.

Same methodology as the rest of the in-game UI: pre-render smooth, high-res
assets here and verify them in-sandbox before they touch Minecraft. The fill and
border are alpha masks (white on transparent) that get tinted to the theme
colors in-game and 9-sliced to any button size (corners drawn from a fixed,
high-res corner region so they stay crisp at small sizes; straight edges are
uniform so they stretch cleanly). Labels are baked per fixed English string and
keyed by text (fallback to vanilla font in-game for anything without a texture).

Usage: python3 generate_buttons.py
Requires: Pillow, ../loading-screen/bake_text.py, ../font-atlas/fonts/Inter-500.ttf
Output: ../../src/OriginClient.Mod/.../assets/originclient/textures/ui/
        button_fill.png, button_border.png, button_glow.png,
        buttons.json, label_*.png, labels.json
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str((HERE / ".." / "loading-screen").resolve()))
from bake_text import load_font  # noqa: E402

OUT = (HERE / ".." / ".." / "src" / "OriginClient.Mod" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

TEX = 96          # square texture size for fill/border
CORNER = 24       # corner region size (px) used for 9-slicing; also the radius
BORDER_PX = 4     # border stroke in texture px (~1px in-game after down-scale)
SS = 4            # supersample for anti-aliasing

# Bake the likely vanilla title-menu strings (and variants) so the in-game
# label lookup, keyed by the button's exact getString(), matches; anything
# unmatched falls back to vanilla font.
LABELS = ["Singleplayer", "Multiplayer", "Options", "Quit", "Quit Game",
          "Realms", "Minecraft Realms"]
LABEL_CAP = 96


def _to_rgba(alpha_L):
    white = Image.new("L", alpha_L.size, 255)
    return Image.merge("RGBA", (white, white, white, alpha_L))


def bake_fill():
    big = TEX * SS
    img = Image.new("L", (big, big), 0)
    ImageDraw.Draw(img).rounded_rectangle([0, 0, big - 1, big - 1], radius=CORNER * SS, fill=255)
    return _to_rgba(img.resize((TEX, TEX), Image.LANCZOS))


def bake_border():
    big = TEX * SS
    img = Image.new("L", (big, big), 0)
    half = BORDER_PX * SS / 2.0
    ImageDraw.Draw(img).rounded_rectangle(
        [half, half, big - 1 - half, big - 1 - half],
        radius=CORNER * SS, outline=255, width=BORDER_PX * SS)
    return _to_rgba(img.resize((TEX, TEX), Image.LANCZOS))


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    bake_fill().save(OUT / "button_fill.png")
    bake_border().save(OUT / "button_border.png")
    (OUT / "buttons.json").write_text(json.dumps({
        "texSize": TEX, "corner": CORNER, "borderPx": BORDER_PX,
    }, indent=2))
    print(f"button masks: {TEX}x{TEX} (corner {CORNER}) -> button_*.png")

    # Labels baked as uniform, baseline-aligned cells (same height for every
    # label) so they all render at the same visual size regardless of
    # descenders -- scaling by the shared cellHeight in-game keeps them
    # consistent (an ink-box height varies with p/g/y and looked wrong).
    #
    # Rendered large (LABEL_CAP) for quality, then LANCZOS-downscaled to a
    # cell height close to the actual on-screen pixel size. GL samples these
    # linearly with no mipmaps, so a big draw-time minification ratio aliases
    # ("not the same quality as the ORIGIN logo" -- the logo only shrinks
    # ~1.4x at draw, the first label bake shrank ~5x). Doing the downscale
    # here in Pillow keeps draw-time scaling near 1:1, same as the wordmark.
    font = load_font("../font-atlas/fonts/Inter-500.ttf", LABEL_CAP)
    ascent, descent = font.getmetrics()
    pad = 4
    cell_h = ascent + descent + 2 * pad
    baseline = pad + ascent
    ls = 0.02 * LABEL_CAP
    target_cell_h = 32
    scale = target_cell_h / cell_h
    labels = {}
    for text in LABELS:
        positions = []
        pen = 0.0
        for ch in text:
            positions.append((ch, pen))
            pen += font.getlength(ch) + ls
        width = int(pen - ls) + 2 * pad
        layer = Image.new("L", (max(width, 1), cell_h), 0)
        draw = ImageDraw.Draw(layer)
        for ch, px in positions:
            draw.text((pad + px, baseline), ch, fill=255, font=font, anchor="ls")
        small = layer.resize((max(1, round(layer.width * scale)), target_cell_h), Image.LANCZOS)
        slug = "".join(c if c.isalnum() else "_" for c in text.lower())
        fname = "label_" + slug + ".png"
        _to_rgba(small).save(OUT / fname)
        labels[text] = {"file": fname, "width": small.width}
        print(f"label '{text}': {small.width}x{target_cell_h} -> {fname}")

    (OUT / "labels.json").write_text(json.dumps({"cellHeight": target_cell_h, "labels": labels}, indent=2))


if __name__ == "__main__":
    main()
