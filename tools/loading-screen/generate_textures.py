#!/usr/bin/env python3
"""Pre-render the Origin loading-screen backdrop textures: grain tile, radial
glow and vignette.

The old ring textures (ring-0..3.png + rings.json) are gone on purpose: Will,
2026-10-25, "the only place you can use the oval shapes thing is our logo".
Ring shapes exist only inside textures/ui/origin_logo.png; tools/design/check_logo.py
fails the build if ring art comes back.

Usage: python3 generate_textures.py
Requires: Pillow
Output: ../../src/mods/shared/src/client/resources/assets/originclient/textures/ui/
        grain.png, radial_glow.png, vignette.png (then run tools/shared-sync/sync.py)
"""
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "mods" / "shared" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

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
    make_grain().save(OUT / "grain.png")
    print("grain -> grain.png")

    make_radial_glow().save(OUT / "radial_glow.png")
    print("radial glow -> radial_glow.png")

    make_vignette().save(OUT / "vignette.png")
    print("vignette -> vignette.png")


if __name__ == "__main__":
    main()
