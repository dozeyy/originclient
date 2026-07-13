#!/usr/bin/env python3
"""Bake the "ORIGIN" wordmark into a smooth texture: all-caps, Michroma, with a
touch of letter-spacing and a soft white glow bloom behind it. Used on both the
loading screen and the main menu.

Michroma (chosen over Inter 2026-07-09): a wide aerospace/observatory grotesque
that ties the wordmark to the tri-ring orbital identity — reads as a mark, not
body text. It's already very wide, so tracking is light (Inter needed 0.45x a
space; Michroma only ~0.14x) or the word overruns the on-screen width clamp.

Baked as a texture (not live text) so it shows instantly -- Minecraft's own
font isn't loaded during the first resource load, and this is one fixed word as
an image, not a dynamic glyph atlas, so it carries none of the earlier
custom-font risk.

Usage: python3 generate_wordmark.py
Output: ../../src/mods/shared/.../assets (then run tools/shared-sync/sync.py)/originclient/textures/ui/
        wordmark.png + wordmark.json (pixel dims + ink box, for exact centering)
"""
import json
from pathlib import Path

from bake_text import load_font, render_text

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "mods" / "shared" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

TEXT = "ORIGIN"
CAP = 300                          # glyph size, px (baked large, displayed scaled)
# Letter-spacing as a fraction of the font's own space advance, derived from the
# space glyph so it stays proportional if the size changes. Michroma is already
# a wide face, so this is light — just enough air between glyphs.
SPACING_FRAC_OF_SPACE = 0.14


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    font = load_font("../font-atlas/fonts/Michroma-Regular.ttf", CAP)
    letter_spacing = SPACING_FRAC_OF_SPACE * font.getlength(" ")
    # Soft bloom behind the crisp letters — a halo, not just an edge glow, so
    # blur generously and keep the peak alpha gentle.
    img, meta = render_text(font, TEXT, letter_spacing_px=letter_spacing,
                            pad=150, glow_blur=60, glow_alpha=0.26)
    img.save(OUT / "wordmark.png")
    (OUT / "wordmark.json").write_text(json.dumps(meta, indent=2))
    print(f"wordmark '{TEXT}': texture {meta['width']}x{meta['height']}, "
          f"ink {meta['inkWidth']}x{meta['inkHeight']} -> wordmark.png / wordmark.json")


if __name__ == "__main__":
    main()
