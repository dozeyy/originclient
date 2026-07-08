#!/usr/bin/env python3
"""Pre-render the Origin loading-screen background textures: four anti-aliased
elliptical ring strokes + one subtle grain tile.

Why pre-rendered PNGs and not procedural drawing in-game: thin curved strokes
drawn directly through Minecraft's blocky GUI coordinate space come out as
jagged scattered pixels (a prior attempt hit exactly that — MEMORY.md
2026-07-07 "read as messy scattered dots"). Baking each ring as a
high-resolution, supersampled, anti-aliased texture puts the smoothness in the
image itself; in-game we only blit + rotate that texture (magnifying a smooth
texture stays smooth — unlike the font, there's no fine detail being crushed
down). The whole point of doing it here is that the PNGs can be viewed and
verified smooth in this sandbox before they ever reach the game.

Ring geometry/opacity/speed mirror the launcher's own animated background
(`UI/Controls/OriginBackground.xaml(.cs)`) so launcher + website + in-game all
read as one system: 4 tilted ellipses (~0.37 height:width), each spinning at
its own period/direction, back rings fainter + softly blurred for depth.

Usage: python3 generate_textures.py
Requires: Pillow
Output: ../../src/OriginClient.Mod/src/client/resources/assets/originclient/textures/ui/
        ring-{0..3}.png, grain.png, rings.json
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "OriginClient.Mod" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

TEX = 768          # final square texture size per ring, px
SS = 3             # supersample factor for anti-aliasing
HEIGHT_RATIO = 0.37   # ellipse height / width (from the launcher's ~0.37 rings)
FILL_FRAC = 0.90   # ellipse major axis as a fraction of the square canvas
                    # (leaves padding so rotation never clips the stroke/blur)

# Per-ring look, derived from OriginBackground.xaml(.cs). widthFrac is the
# ellipse major axis as a fraction of the game screen width (launcher ring px
# / ~1180px reference window). period/reverse drive the in-game rotation.
RINGS = [
    # name, widthFrac, strokePx(final tex), opacity, blur, angle0, periodSec, reverse
    # Periods are faster than the launcher's slow ambient values (40-120s) so the
    # rotation actually reads as motion on the main menu, where you linger.
    ("A", 0.59, 3.4, 0.35,  0.0,   0, 16, False),
    ("B", 0.81, 3.0, 0.28,  0.0,  45, 24, True),
    ("C", 1.02, 2.7, 0.22,  1.6, 100, 33, False),
    ("D", 1.27, 2.4, 0.18,  2.6,  15, 44, True),
]


def make_ring(stroke_px: float, blur: float) -> Image.Image:
    """One white AA elliptical stroke on transparent, centered in a square."""
    big = TEX * SS
    canvas = Image.new("L", (big, big), 0)  # coverage mask, drawn opaque
    draw = ImageDraw.Draw(canvas)

    major = FILL_FRAC * big
    minor = major * HEIGHT_RATIO
    cx = cy = big / 2
    bbox = [cx - major / 2, cy - minor / 2, cx + major / 2, cy + minor / 2]
    draw.ellipse(bbox, outline=255, width=max(1, round(stroke_px * SS)))

    small = canvas.resize((TEX, TEX), Image.LANCZOS)  # supersample -> AA edges
    if blur > 0:
        small = small.filter(ImageFilter.GaussianBlur(blur))

    white = Image.new("L", (TEX, TEX), 255)
    return Image.merge("RGBA", (white, white, white, small))  # white glyph, coverage as alpha


def make_grain() -> Image.Image:
    """Small tileable, very low-contrast grayscale grain (website-style, lighter)."""
    tile = 128
    noise = Image.effect_noise((tile, tile), 26).filter(ImageFilter.GaussianBlur(0.4))
    # Center the noise on mid-gray and keep it subtle; alpha is applied in-game.
    return Image.merge("RGBA", (noise, noise, noise, Image.new("L", (tile, tile), 255)))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    meta = {"texSize": TEX, "heightRatio": HEIGHT_RATIO, "fillFrac": FILL_FRAC, "rings": []}

    for i, (name, width_frac, stroke, opacity, blur, angle0, period, reverse) in enumerate(RINGS):
        img = make_ring(stroke, blur)
        img.save(OUT / f"ring-{i}.png")
        meta["rings"].append({
            "index": i, "name": name,
            "widthFrac": width_frac, "opacity": opacity,
            "angle0": angle0, "periodSeconds": period, "reverse": reverse,
        })
        print(f"ring {name}: widthFrac={width_frac} stroke={stroke}px blur={blur} opacity={opacity} -> ring-{i}.png")

    make_grain().save(OUT / "grain.png")
    print("grain -> grain.png")

    (OUT / "rings.json").write_text(json.dumps(meta, indent=2))
    print(f"metadata -> rings.json")


if __name__ == "__main__":
    main()
