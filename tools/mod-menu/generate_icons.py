#!/usr/bin/env python3
"""Bake the approved Origin Icon System into Minecraft's icon atlases.

The editable 24 px vector masters live in
design/icon-proposals/origin-icon-system-v1/generate_proposal.py. This script
does not keep a second copy of any geometry: it imports those masters, maps the
public Minecraft ids, renders a transparent review/fallback atlas with Chromium,
then derives a signed-distance atlas for resolution-independent in-game edges.
Resources are atomically replaced only after both PNGs are validated.
"""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from PIL import Image
import numpy as np


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
MASTER = PROJECT / "design" / "icon-proposals" / "origin-icon-system-v1" / "generate_proposal.py"
OUT = (PROJECT / "src" / "mods" / "versions" / "1.21.1" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui")

CELL = 192
COLS = 6
SDF_RANGE = 16.0
# Small-size optical correction: roughly +0.15 master-grid pixels across the
# full stroke, enough to match Inter Semibold without returning to a heavy icon.
SDF_WEIGHT_BIAS = 0.6

# Stable public ids used by the Java UI. Title entries stay separate from the
# intentionally separate from the existing mod/chrome ids.
PUBLIC_IDS = {
    "@backing": "client.@backing",
    "@origin-mark": "brand.origin-mark",
    "@general": "client.@general",
    "@hudeditor": "client.@hudeditor",
    "@performance": "client.@performance",
    "@search": "client.@search",
    "@title-singleplayer": "title.singleplayer",
    "@title-multiplayer": "title.multiplayer",
    "@title-realms": "title.realms",
    "@title-mods": "launcher.mods",
    "@title-options": "launcher.settings",
    "@title-quit": "title.quit",
    "@title-language": "title.language",
    "@title-accessibility": "title.accessibility",
    "fps": "client.fps",
    "cps": "client.cps",
    "keystrokes": "client.keystrokes",
    "coords": "client.coords",
    "armorhud": "client.armorhud",
    "potionhud": "client.potionhud",
    "serveraddress": "client.serveraddress",
    "scoreboard": "client.scoreboard",
    "tablist": "client.tablist",
    "zoom": "client.zoom",
    "freelook": "client.freelook",
    "fullbright": "client.fullbright",
    "blockoverlay": "client.blockoverlay",
    "chunkborders": "client.chunkborders",
    "hitboxes": "client.hitboxes",
    "nametags": "client.nametags",
    "itemsize": "client.itemsize",
    "weather": "client.weather",
    "timechanger": "client.timechanger",
    "motionblur": "client.motionblur",
    "colorsaturation": "client.colorsaturation",
    "particles": "client.particles",
    "togglesprint": "client.togglesprint",
    "chat": "client.chat",
    "waypoints": "client.waypoints",
    "jei": "client.jei",
}


def load_master():
    spec = importlib.util.spec_from_file_location("origin_icon_masters", MASTER)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load icon masters: {MASTER}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def find_browser() -> Path:
    candidates = [
        Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
        Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
        Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    for name in ("chrome", "msedge", "chromium"):
        found = shutil.which(name)
        if found:
            return Path(found)
    raise RuntimeError("Chrome, Edge, or Chromium is required to rasterize the approved SVG atlas")


def atlas_svg(entries: list[tuple[str, dict]], stroke: str, width: int, height: int) -> str:
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" color="#FFFFFF">'
    ]
    for index, (_, entry) in enumerate(entries):
        x = (index % COLS) * CELL
        y = (index // COLS) * CELL
        # 24 -> CELL px. Keeping the authored viewBox intact preserves the
        # approved spacing and optical stroke across every glyph.
        parts.append(f'<g transform="translate({x} {y}) scale({CELL / 24:g})" {stroke}>{entry["body"]}</g>')
    parts.append("</svg>")
    return "\n".join(parts)


def atomic_text(path: Path, value: str) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(value, encoding="utf-8")
    os.replace(tmp, path)


def edt_1d(values: np.ndarray) -> np.ndarray:
    """Exact squared Euclidean distance transform for one scanline.

    This is the linear-time lower-envelope algorithm from Felzenszwalb and
    Huttenlocher. Keeping it here avoids adding SciPy to the icon build just to
    create one deterministic asset.
    """
    count = len(values)
    sites = np.empty(count, dtype=np.int32)
    cuts = np.empty(count + 1, dtype=np.float64)
    result = np.empty(count, dtype=np.float64)
    envelope = 0
    sites[0] = 0
    cuts[0] = -np.inf
    cuts[1] = np.inf
    for point in range(1, count):
        site = sites[envelope]
        cut = ((values[point] + point * point) - (values[site] + site * site)) / (2.0 * (point - site))
        while cut <= cuts[envelope]:
            envelope -= 1
            site = sites[envelope]
            cut = ((values[point] + point * point) - (values[site] + site * site)) / (2.0 * (point - site))
        envelope += 1
        sites[envelope] = point
        cuts[envelope] = cut
        cuts[envelope + 1] = np.inf
    envelope = 0
    for point in range(count):
        while cuts[envelope + 1] < point:
            envelope += 1
        delta = point - sites[envelope]
        result[point] = delta * delta + values[sites[envelope]]
    return result


def distance_to_background(mask: np.ndarray) -> np.ndarray:
    """Distance from every True pixel to the nearest False pixel."""
    infinity = 1.0e12
    field = np.where(mask, infinity, 0.0)
    horizontal = np.empty_like(field)
    for row in range(field.shape[0]):
        horizontal[row, :] = edt_1d(field[row, :])
    squared = np.empty_like(horizontal)
    for column in range(horizontal.shape[1]):
        squared[:, column] = edt_1d(horizontal[:, column])
    return np.sqrt(squared)


def make_sdf(mask_atlas: Image.Image) -> Image.Image:
    """Convert the white alpha mask into an RGB signed-distance texture.

    The existing Origin MSDF shader can read a monochrome SDF when the same
    distance is stored in all three channels. Its screen-space derivatives then
    reconstruct a one-physical-pixel antialiased edge at every GUI scale.
    """
    alpha = np.asarray(mask_atlas.getchannel("A"), dtype=np.uint8)
    inside = alpha >= 128
    signed = distance_to_background(inside) - distance_to_background(~inside) + SDF_WEIGHT_BIAS
    distance = np.clip(0.5 + signed / SDF_RANGE, 0.0, 1.0)
    channel = np.rint(distance * 255.0).astype(np.uint8)
    rgba = np.empty((channel.shape[0], channel.shape[1], 4), dtype=np.uint8)
    rgba[:, :, 0] = channel
    rgba[:, :, 1] = channel
    rgba[:, :, 2] = channel
    rgba[:, :, 3] = 255
    return Image.fromarray(rgba, mode="RGBA")


def center_cells(atlas: Image.Image, entries: list[tuple[str, dict]]) -> tuple[Image.Image, dict[str, list[int]]]:
    """Center every visible glyph inside its square cell by alpha bounds.

    SVG path coordinates can be mathematically centred while their visible mass
    is not (a globe tail and directional marks are common examples). Normalising
    the baked cells guarantees identical centring in title dock squares, mod
    cards, inspector tiles, and every other consumer without per-screen nudges.
    """
    centered = Image.new("RGBA", atlas.size, (0, 0, 0, 0))
    offsets: dict[str, list[int]] = {}
    for index, (public_id, _) in enumerate(entries):
        x = (index % COLS) * CELL
        y = (index // COLS) * CELL
        glyph = atlas.crop((x, y, x + CELL, y + CELL))
        bounds = glyph.getchannel("A").getbbox()
        if bounds is None:
            offsets[public_id] = [0, 0]
            continue
        left, top, right, bottom = bounds
        dx = round((CELL - left - right) / 2.0)
        dy = round((CELL - top - bottom) / 2.0)
        centered.alpha_composite(glyph, (x + dx, y + dy))
        offsets[public_id] = [dx, dy]
    return centered, offsets


def build() -> None:
    master = load_master()
    by_id = {entry["id"]: entry for entry in master.ICONS}
    missing = sorted(set(PUBLIC_IDS.values()) - set(by_id))
    if missing:
        raise RuntimeError(f"Approved master icons missing: {', '.join(missing)}")

    entries = [(public_id, by_id[master_id]) for public_id, master_id in PUBLIC_IDS.items()]
    rows = (len(entries) + COLS - 1) // COLS
    width, height = COLS * CELL, rows * CELL
    svg = atlas_svg(entries, master.STROKE, width, height)

    OUT.mkdir(parents=True, exist_ok=True)
    browser = find_browser()
    with tempfile.TemporaryDirectory(prefix="origin-icons-") as raw_temp:
        temp = Path(raw_temp)
        source = temp / "atlas.svg"
        rendered = temp / "atlas.png"
        source.write_text(svg, encoding="utf-8")
        args = [
            str(browser), "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=1", "--default-background-color=00000000",
            f"--window-size={width},{height}", f"--user-data-dir={temp / 'browser'}",
            f"--screenshot={rendered}", source.as_uri(),
        ]
        subprocess.run(args, check=True, capture_output=True, text=True, timeout=45)
        with Image.open(rendered) as image:
            atlas = image.convert("RGBA")
        if atlas.size != (width, height):
            raise RuntimeError(f"Browser rendered {atlas.size}, expected {(width, height)}")
        if atlas.getchannel("A").getbbox() is None:
            raise RuntimeError("Rendered icon atlas is empty")

        atlas, center_offsets = center_cells(atlas, entries)

        png_tmp = OUT / "mod_icons.png.tmp"
        atlas.save(png_tmp, format="PNG", optimize=True)
        os.replace(png_tmp, OUT / "mod_icons.png")

        sdf = make_sdf(atlas)
        sdf_tmp = OUT / "mod_icons_sdf.png.tmp"
        sdf.save(sdf_tmp, format="PNG", optimize=True)
        with Image.open(sdf_tmp) as check:
            if check.size != (width, height) or check.getbbox() is None:
                raise RuntimeError("Generated SDF icon atlas failed validation")
        os.replace(sdf_tmp, OUT / "mod_icons_sdf.png")

    meta = {
        "cell": CELL,
        "cols": COLS,
        "grid": 24,
        "style": "approved-origin-outline-1.55",
        "rendering": "screen-space-sdf",
        "distanceRange": SDF_RANGE,
        "opticalWeightBias": SDF_WEIGHT_BIAS,
        "centerOffsets": center_offsets,
        "source": str(MASTER.relative_to(PROJECT)).replace("\\", "/"),
        "icons": {
            public_id: {"x": (index % COLS) * CELL, "y": (index // COLS) * CELL}
            for index, (public_id, _) in enumerate(entries)
        },
    }
    atomic_text(OUT / "mod_icons.json", json.dumps(meta, indent=2) + "\n")

    # Review image on the actual Ion Jade surface.
    preview = Image.new("RGBA", atlas.size, (0x12, 0x19, 0x18, 0xFF))
    preview.alpha_composite(atlas)
    preview.convert("RGB").save(HERE / "icons_preview.png", optimize=True)
    print(f"{len(entries)} approved icons -> {OUT / 'mod_icons.png'} ({width}x{height})")


if __name__ == "__main__":
    build()
