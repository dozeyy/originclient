#!/usr/bin/env python3
"""Bake the loading-bar caption glyphs into a small strip texture + per-glyph
metrics, so the in-game "LOADING xx%" caption (with the live percentage) shows
instantly and smoothly instead of Minecraft's not-yet-loaded font tofu.

Fixed, tiny charset ("LOADING 0123456789%"), one size, one baked strip -- the
in-game code composes the caption by blitting each glyph's sub-rect. This is a
sprite strip for a known set of characters, not the general dynamic glyph atlas
that failed earlier.

Usage: python3 generate_caption.py
Output: ../../src/OriginClient.Mod/.../assets/originclient/textures/ui/
        caption.png + caption.json (per-glyph x/width/advance, in Inter 500)
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

from bake_text import load_font

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "OriginClient.Mod" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

CHARS = "LOADING 0123456789%"
CAP = 110                       # baked glyph size, px
LETTER_SPACING = 0.16 * CAP     # 0.16em, matching the mockup's .bar__cap
PAD = 6


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    font = load_font("../font-atlas/fonts/Inter-500.ttf", CAP)
    ascent, descent = font.getmetrics()
    cell_h = ascent + descent + 2 * PAD
    baseline = ascent + PAD

    # Lay glyphs left-to-right in one strip, recording each glyph's rect.
    glyphs = {}
    x = 0
    for ch in dict.fromkeys(CHARS):  # unique, preserve order
        if ch == " ":
            glyphs[ch] = {"x": 0, "y": 0, "width": 0, "advance": round(font.getlength(" ") + LETTER_SPACING, 2)}
            continue
        l, t, r, b = font.getbbox(ch)
        w = r - l
        glyphs[ch] = {
            "x": x, "y": 0, "width": w,
            "bearingX": l,
            "advance": round(font.getlength(ch) + LETTER_SPACING, 2),
        }
        x += w + 4  # gap between packed cells

    strip_w = x
    strip = Image.new("RGBA", (strip_w, cell_h), (255, 255, 255, 0))
    draw = ImageDraw.Draw(strip)
    for ch, g in glyphs.items():
        if g["width"] == 0:
            continue
        # draw glyph so its ink-left sits at g["x"]
        draw.text((g["x"] - g["bearingX"], baseline), ch, fill=(255, 255, 255, 255), font=font, anchor="ls")

    strip.save(OUT / "caption.png")
    meta = {"charset": CHARS, "cellHeight": cell_h, "capHeight": CAP,
            "atlasWidth": strip_w, "atlasHeight": cell_h, "glyphs": glyphs}
    (OUT / "caption.json").write_text(json.dumps(meta, indent=2))
    print(f"caption: strip {strip_w}x{cell_h}, {len(glyphs)} glyphs -> caption.png / caption.json")


if __name__ == "__main__":
    main()
