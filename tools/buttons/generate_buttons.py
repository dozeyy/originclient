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
Output: ../../src/mods/shared/.../assets (then run tools/shared-sync/sync.py)/originclient/textures/ui/
        button_fill.png, button_border.png, buttons.json
"""
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

HERE = Path(__file__).resolve().parent

OUT = (HERE / ".." / ".." / "src" / "mods" / "shared" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

TEX = 96          # square texture size for fill/border
CORNER = 24       # corner region size (px) used for 9-slicing; also the radius
BORDER_PX = 4     # border stroke in texture px (~1px in-game after down-scale)
SS = 4            # supersample for anti-aliasing
# Keep the fill this many texture-px inside the border's OUTER edge, so the
# darker fill can never show a fringe past the lighter border under GL_LINEAR
# filtering / LANCZOS down-scale (A1: "no overhang, no subpixel bleed").
FILL_INSET_PX = 1


def _to_rgba(alpha_L):
    white = Image.new("L", alpha_L.size, 255)
    return Image.merge("RGBA", (white, white, white, alpha_L))


def bake_fill():
    # Inset the fill by FILL_INSET_PX and shrink its radius to match, so its
    # rounded corner tucks just inside the border's outer arc everywhere.
    big = TEX * SS
    inset = FILL_INSET_PX * SS
    img = Image.new("L", (big, big), 0)
    ImageDraw.Draw(img).rounded_rectangle(
        [inset, inset, big - 1 - inset, big - 1 - inset],
        radius=(CORNER - FILL_INSET_PX) * SS, fill=255)
    return _to_rgba(img.resize((TEX, TEX), Image.LANCZOS))


def bake_border():
    # A TRUE ring: an outer rounded-rect at the full extent (identical geometry
    # to the fill's outer edge before its 1px inset) minus an inner rounded-rect
    # inset by the stroke width. This guarantees the border's outer edge is the
    # outermost lit pixel and its corner arc concentrically contains the fill's —
    # the earlier "inset rect, same radius" made the fill's corner poke outside.
    big = TEX * SS
    stroke = BORDER_PX * SS
    outer = Image.new("L", (big, big), 0)
    ImageDraw.Draw(outer).rounded_rectangle(
        [0, 0, big - 1, big - 1], radius=CORNER * SS, fill=255)
    inner = Image.new("L", (big, big), 0)
    ImageDraw.Draw(inner).rounded_rectangle(
        [stroke, stroke, big - 1 - stroke, big - 1 - stroke],
        radius=max(1, (CORNER - BORDER_PX) * SS), fill=255)
    ring = ImageChops.subtract(outer, inner)
    return _to_rgba(ring.resize((TEX, TEX), Image.LANCZOS))


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
