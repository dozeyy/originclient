#!/usr/bin/env python3
"""Bake the "Origin" wordmark into a smooth texture, matching the original
loading-screen mockup (tools/loading-screen/wordmark_preview.png): mixed-case
"Origin" — same casing as the website nav brand and hero — set in Inter 700
at natural (slightly tight) letter-spacing, with a soft white glow bloom behind
it. Used on both the loading screen and the main menu.

The earlier all-caps "ORIGIN" + 0.22em treatment (commit 2fd52e1) drifted away
from this mockup; this restores the intended mark. Baked as a texture (not live
text) so it shows instantly -- Minecraft's own font isn't loaded during the
first resource load, and this is one fixed word as an image, not a dynamic
glyph atlas, so it carries none of the earlier custom-font risk.

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

TEXT = "Origin"
CAP = 300                          # glyph size, px (baked large, displayed scaled)
# Natural spacing, a hair tight like the website's big display type
# (--hero__title letter-spacing: -0.02em). Not the wide all-caps treatment.
LETTER_SPACING = -0.015 * CAP


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    font = load_font("../font-atlas/fonts/Inter-700.ttf", CAP)
    # Broad, soft bloom behind the crisp letters — the mockup's glow reads
    # wider and softer than the letters themselves (a halo, not just an
    # edge glow), so blur generously and keep the peak alpha gentle.
    img, meta = render_text(font, TEXT, letter_spacing_px=LETTER_SPACING,
                            pad=180, glow_blur=70, glow_alpha=0.28)
    img.save(OUT / "wordmark.png")
    (OUT / "wordmark.json").write_text(json.dumps(meta, indent=2))
    print(f"wordmark '{TEXT}': texture {meta['width']}x{meta['height']}, "
          f"ink {meta['inkWidth']}x{meta['inkHeight']} -> wordmark.png / wordmark.json")


if __name__ == "__main__":
    main()
