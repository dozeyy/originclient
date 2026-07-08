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

from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str((HERE / ".." / "loading-screen").resolve()))
from bake_text import load_font  # noqa: E402

OUT = (HERE / ".." / ".." / "src" / "OriginClient.Mod" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

TEX = 96          # square texture size for fill/border
CORNER = 24       # corner region size (px) used for 9-slicing; also the radius
BORDER_PX = 4     # border stroke in texture px (~1px in-game after down-scale)
SS = 4            # supersample for anti-aliasing

LABELS = ["Singleplayer", "Multiplayer", "Options", "Quit", "Realms"]
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


def bake_glow():
    size = 128
    big = size * 2
    blur = 22
    img = Image.new("L", (big, big), 0)
    m = int(blur * 2 * 1.5)
    ImageDraw.Draw(img).rounded_rectangle([m, m, big - 1 - m, big - 1 - m], radius=CORNER * 2, fill=255)
    img = img.filter(ImageFilter.GaussianBlur(blur * 2))
    return _to_rgba(img.resize((size, size), Image.LANCZOS)), size


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    bake_fill().save(OUT / "button_fill.png")
    bake_border().save(OUT / "button_border.png")
    glow, glow_size = bake_glow()
    glow.save(OUT / "button_glow.png")
    (OUT / "buttons.json").write_text(json.dumps({
        "texSize": TEX, "corner": CORNER, "borderPx": BORDER_PX, "glowSize": glow_size,
    }, indent=2))
    print(f"button masks: {TEX}x{TEX} (corner {CORNER}), glow {glow_size} -> button_*.png")

    # Labels baked as uniform, baseline-aligned cells (same height for every
    # label) so they all render at the same visual size regardless of
    # descenders -- scaling by the shared cellHeight in-game keeps them
    # consistent (an ink-box height varies with p/g/y and looked wrong).
    font = load_font("../font-atlas/fonts/Inter-500.ttf", LABEL_CAP)
    ascent, descent = font.getmetrics()
    pad = 4
    cell_h = ascent + descent + 2 * pad
    baseline = pad + ascent
    ls = 0.02 * LABEL_CAP
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
        fname = "label_" + text.lower() + ".png"
        _to_rgba(layer).save(OUT / fname)
        labels[text] = {"file": fname, "width": layer.width}
        print(f"label '{text}': {layer.width}x{cell_h} -> {fname}")

    (OUT / "labels.json").write_text(json.dumps({"cellHeight": cell_h, "labels": labels}, indent=2))


if __name__ == "__main__":
    main()
