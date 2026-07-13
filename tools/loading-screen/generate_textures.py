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
Output: ../../src/mods/shared/src/client/resources/assets (then run tools/shared-sync/sync.py)/originclient/textures/ui/
        ring-{0..3}.png, grain.png, rings.json
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "mods" / "shared" / "src" / "client" /
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
    # Crisp thin hairlines, matching the original mockup
    # (wordmark_preview.png): the two front rings are razor-sharp (blur 0),
    # the two back rings get only a touch of blur for depth. The "blur all of
    # them, dreamy" pass (commit 9614d07) muddied this; restore the clean look.
    ("A", 0.59, 3.2, 0.42,  0.0,   0, 40, False),
    ("B", 0.81, 2.8, 0.34,  0.0,  45, 65, True),
    ("C", 1.02, 2.5, 0.26,  1.4, 100, 90, False),
    ("D", 1.27, 2.2, 0.20,  2.4,  15, 120, True),
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
    """Tileable, very low-contrast grayscale grain (website-style, lighter).

    256px (not 128): the in-game grain is tiled at 1:1 real pixels, so a bigger
    tile means fewer blits per frame to cover the screen (~135 -> ~40 at 1080p,
    an FPS win on every menu/loading screen) with the same per-pixel look. 256
    balances the blit win against jar size (512 quadruples the PNG for a blit
    count that's already trivially fast). Still tileable.
    """
    tile = 256
    # Fine, per-pixel grain like the website's SVG fractalNoise (high
    # baseFrequency, so grain == one pixel). No blur: blurring enlarges the
    # grain and reads as "low res"; the website's is crisp single-pixel noise,
    # kept flawless by staying subtle (alpha applied in-game) rather than soft.
    noise = Image.effect_noise((tile, tile), 22)
    # Center the noise on mid-gray and keep it subtle; alpha is applied in-game.
    return Image.merge("RGBA", (noise, noise, noise, Image.new("L", (tile, tile), 255)))


def make_radial_glow() -> Image.Image:
    """Circular radial gradient matching the website's cursor-glow layers:
    radial-gradient(circle, white 0%, transparent 70%) — full alpha at the
    center, linear falloff hitting zero at 70% of the radius, fully
    transparent well inside the texture bounds (so a scaled blit can never
    show a square edge; the first hover-glow bake failed exactly that way).
    One texture serves both the core and the halo — peak opacity and size are
    applied at draw time."""
    size = 512
    half = size / 2.0
    fade_end = 0.7 * half
    img = Image.new("L", (size, size), 0)
    px = img.load()
    for y in range(size):
        dy = y + 0.5 - half
        for x in range(size):
            dx = x + 0.5 - half
            r = (dx * dx + dy * dy) ** 0.5
            a = 1.0 - r / fade_end
            if a > 0:
                px[x, y] = int(a * 255)
    white = Image.new("L", (size, size), 255)
    return Image.merge("RGBA", (white, white, white, img))


def make_vignette() -> Image.Image:
    """Edge vignette: transparent core -> soft black at the corners. Blitted
    full-screen (stretched to the GUI size) it darkens the frame edges so the
    centered wordmark reads as the focal point — depth from lightness only, no
    hue, matching the monochrome design language. The ramp starts ~45% out from
    center and eases to ~55% black in the corners; the eased (t*t) falloff keeps
    the transition invisible rather than a hard ring."""
    size = 1024
    half = size / 2.0
    inner = 0.45          # transparent until this fraction of the half-diagonal
    max_alpha = 0.55      # corner darkness
    diag = (half * half + half * half) ** 0.5
    img = Image.new("L", (size, size), 0)
    px = img.load()
    for y in range(size):
        dy = y + 0.5 - half
        for x in range(size):
            dx = x + 0.5 - half
            r = (dx * dx + dy * dy) ** 0.5 / diag  # 0 center .. 1 corner
            t = (r - inner) / (1.0 - inner)
            if t > 0:
                t = min(1.0, t)
                px[x, y] = int(t * t * max_alpha * 255)
    black = Image.new("L", (size, size), 0)
    return Image.merge("RGBA", (black, black, black, img))


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

    make_radial_glow().save(OUT / "radial_glow.png")
    print("radial glow -> radial_glow.png")

    make_vignette().save(OUT / "vignette.png")
    print("vignette -> vignette.png")

    (OUT / "rings.json").write_text(json.dumps(meta, indent=2))
    print(f"metadata -> rings.json")


if __name__ == "__main__":
    main()
