#!/usr/bin/env python3
"""Bake the "ORIGIN" wordmark into a smooth texture, matching the first
loading-screen mockup: all-caps, Inter 600, wide letter-spacing (0.22em), soft
white glow. Used on both the loading screen and the main menu.

Baked as a texture (not live text) so it shows instantly -- Minecraft's own
font isn't loaded during the first resource load, and this is one fixed word as
an image, not a dynamic glyph atlas, so it carries none of the earlier
custom-font risk.

Usage: python3 generate_wordmark.py
Output: ../../src/OriginClient.Mod/.../assets/originclient/textures/ui/
        wordmark.png + wordmark.json (pixel dims + ink box, for exact centering)
"""
import json
from pathlib import Path

from bake_text import load_font, render_text

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "OriginClient.Mod" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

TEXT = "ORIGIN"
CAP = 260                          # glyph size, px (baked large, displayed scaled)
LETTER_SPACING = 0.22 * CAP        # 0.22em, matching the mockup's .mark


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    font = load_font("../font-atlas/fonts/Inter-600.ttf", CAP)
    img, meta = render_text(font, TEXT, letter_spacing_px=LETTER_SPACING,
                            pad=90, glow_blur=40, glow_alpha=0.30)
    img.save(OUT / "wordmark.png")
    (OUT / "wordmark.json").write_text(json.dumps(meta, indent=2))
    print(f"wordmark '{TEXT}': texture {meta['width']}x{meta['height']}, "
          f"ink {meta['inkWidth']}x{meta['inkHeight']} -> wordmark.png / wordmark.json")


if __name__ == "__main__":
    main()
