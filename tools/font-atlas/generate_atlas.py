#!/usr/bin/env python3
"""Bake each Inter static weight (see fetch_fonts.py) into a supersampled,
anti-aliased bitmap glyph atlas + exact metrics JSON.

This is deliberately the "no shader" font-quality experiment from the plan
(milestone M3 depends on it): a real anti-aliased raster, generated once here
and self-inspected as a plain PNG before any of it touches Java or Minecraft,
rather than an SDF atlas (which isn't renderable as clean text without a
threshold shader — see DESIGN_SYSTEM.md and the plan's M4).

Technique, following the one part of the prior (reverted) attempt that was
proven correct by replicating Minecraft's own algorithm (MEMORY.md
2026-07-06): render each glyph opaque black-on-white (no alpha channel
involved during rasterization at all), at a large supersampled size, then
convert luminosity -> alpha as a post-process. This sidesteps the GDI+
alpha-antialiasing-residue bug that broke glyph-width measurement before
(irrelevant here anyway since we use Pillow/FreeType on Linux, not GDI+, but
the luminosity->alpha technique is kept because it's a strictly *cleaner*
way to get coverage-correct alpha than relying on a rasterizer's own alpha
channel).

Metrics (advance width, ink bounding box) come from Pillow's own
font.getbbox()/getlength() at the same supersampled size used for
rendering, then scaled down together with the bitmap -- so the atlas image
and its metrics JSON can never disagree about scale (unlike Minecraft's own
bitmap font provider, which re-derives width by scanning pixels).

Usage: python3 generate_atlas.py
Output: output/inter-<weight>.png + output/inter-<weight>.json
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
FONTS_DIR = HERE / "fonts"
OUTPUT_DIR = HERE / "output"
WEIGHTS = [400, 500, 600, 700, 800]

# Printable ASCII: space (0x20) through tilde (0x7E).
CHARSET = "".join(chr(c) for c in range(0x20, 0x7F))

SUPER_SIZE = 512   # render/measure size (px per em) -- the supersample factor
# Baked size chosen close to real HUD/menu usage (roughly 11-32px), not a
# large size relying on GL to shrink it a lot at draw time -- large
# minification ratios without mipmaps alias/look blocky regardless of the
# filter mode, which is what the first live check (M3) actually surfaced.
EM_SIZE = 32        # target baked-atlas size (px per em) -- 16x oversample
SCALE = EM_SIZE / SUPER_SIZE
PADDING_SUPER = 24  # ink padding at SUPER_SIZE, so linear-filtered glyphs
                     # never bleed into their neighbor in the packed atlas
ATLAS_CELL_GAP = 2  # extra transparent gap between packed cells, at EM_SIZE
ATLAS_WIDTH = 768


def bake_glyph(font: ImageFont.FreeTypeFont, ch: str):
    """Returns (rgba_image_or_None, bearing_x, bearing_y, advance) in EM_SIZE units."""
    advance = font.getlength(ch) * SCALE
    left, top, right, bottom = font.getbbox(ch)
    ink_w, ink_h = right - left, bottom - top

    if ink_w <= 0 or ink_h <= 0:
        return None, 0.0, 0.0, advance

    canvas_w = ink_w + 2 * PADDING_SUPER
    canvas_h = ink_h + 2 * PADDING_SUPER
    canvas = Image.new("L", (canvas_w, canvas_h), color=255)  # opaque white bg
    draw = ImageDraw.Draw(canvas)
    draw.text((PADDING_SUPER - left, PADDING_SUPER - top), ch, font=font, fill=0)  # opaque black glyph

    target_w = max(1, round(canvas_w * SCALE))
    target_h = max(1, round(canvas_h * SCALE))
    small = canvas.resize((target_w, target_h), Image.LANCZOS)

    alpha = small.point(lambda p: 255 - p)  # luminosity -> alpha (post-process, no rasterizer alpha involved)
    white_rgb = Image.new("L", small.size, 255)
    rgba = Image.merge("RGBA", (white_rgb, white_rgb, white_rgb, alpha))

    bearing_x = (left - PADDING_SUPER) * SCALE
    bearing_y = (top - PADDING_SUPER) * SCALE
    return rgba, bearing_x, bearing_y, advance


def pack_shelves(glyphs: dict[str, Image.Image], atlas_width: int):
    """Simple shelf packer: sort tallest-first, fill left-to-right rows."""
    order = sorted(glyphs.keys(), key=lambda c: glyphs[c].height if glyphs[c] else 0, reverse=True)
    x = ATLAS_CELL_GAP
    y = ATLAS_CELL_GAP
    row_h = 0
    positions = {}
    for ch in order:
        img = glyphs[ch]
        w, h = (img.width, img.height) if img else (0, 0)
        if w == 0:
            positions[ch] = (0, 0)
            continue
        if x + w + ATLAS_CELL_GAP > atlas_width:
            x = ATLAS_CELL_GAP
            y += row_h + ATLAS_CELL_GAP
            row_h = 0
        positions[ch] = (x, y)
        x += w + ATLAS_CELL_GAP
        row_h = max(row_h, h)
    atlas_height = y + row_h + ATLAS_CELL_GAP
    return positions, atlas_height


def generate(weight: int):
    font = ImageFont.truetype(str(FONTS_DIR / f"Inter-{weight}.ttf"), size=SUPER_SIZE)
    ascent_super, descent_super = font.getmetrics()

    glyph_images: dict[str, Image.Image] = {}
    glyph_meta: dict[str, dict] = {}
    for ch in CHARSET:
        img, bearing_x, bearing_y, advance = bake_glyph(font, ch)
        glyph_images[ch] = img
        glyph_meta[ch] = {
            "bearingX": round(bearing_x, 3),
            "bearingY": round(bearing_y, 3),
            "advance": round(advance, 3),
            "width": img.width if img else 0,
            "height": img.height if img else 0,
        }

    positions, atlas_height = pack_shelves(glyph_images, ATLAS_WIDTH)
    atlas = Image.new("RGBA", (ATLAS_WIDTH, atlas_height), (255, 255, 255, 0))
    for ch, (x, y) in positions.items():
        img = glyph_images[ch]
        if img:
            atlas.paste(img, (x, y))
        glyph_meta[ch]["x"] = x
        glyph_meta[ch]["y"] = y

    OUTPUT_DIR.mkdir(exist_ok=True)
    png_path = OUTPUT_DIR / f"inter-{weight}.png"
    atlas.save(png_path)

    metrics = {
        "family": "Inter",
        "weight": weight,
        "emSize": EM_SIZE,
        "ascent": round(ascent_super * SCALE, 3),
        "descent": round(descent_super * SCALE, 3),
        "atlasWidth": ATLAS_WIDTH,
        "atlasHeight": atlas_height,
        "glyphs": glyph_meta,
    }
    json_path = OUTPUT_DIR / f"inter-{weight}.json"
    json_path.write_text(json.dumps(metrics, indent=2))

    print(f"weight {weight}: atlas {ATLAS_WIDTH}x{atlas_height}px -> {png_path.name}, {len(glyph_meta)} glyphs -> {json_path.name}")


def main():
    for w in WEIGHTS:
        generate(w)


if __name__ == "__main__":
    main()
