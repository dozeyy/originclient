#!/usr/bin/env python3
"""Bake the Origin menu-button masks: a rounded-rect fill and a hairline
rounded-rect border.

Same methodology as the rest of the in-game UI: pre-render smooth, high-res
assets here and verify them in-sandbox before they touch Minecraft. The fill and
border are alpha masks (white on transparent) that get tinted to the theme
colors in-game and 9-sliced to any button size (corners drawn from a fixed,
high-res corner region so they stay crisp at small sizes; straight edges are
uniform so they stretch cleanly).

Button/slider/checkbox LABELS are NOT baked: the project uses Minecraft's own
font for all in-game text (see DESIGN_SYSTEM.md), so the earlier per-GUI-scale
baked-label ladder was removed. This generator now only produces the shell.

Usage: python3 generate_buttons.py
Requires: Pillow
Output: ../../src/OriginClient.Mod/.../assets/originclient/textures/ui/
        button_fill.png, button_border.png, buttons.json
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent

OUT = (HERE / ".." / ".." / "src" / "OriginClient.Mod" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

TEX = 96          # square texture size for fill/border
CORNER = 24       # corner region size (px) used for 9-slicing; also the radius
BORDER_PX = 4     # border stroke in texture px (~1px in-game after down-scale)
SS = 4            # supersample for anti-aliasing


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


if __name__ == "__main__":
    main()
