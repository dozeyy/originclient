#!/usr/bin/env python3
"""Bake the approved Origin Icon System into Minecraft's icon atlases.

The editable 24 px vector masters live in
design/icon-proposals/origin-icon-system-v2/generate_proposal.py (v2 = the
2.2 px set Will supplied 2026-10-12; v1 is kept as the brand mark's source). This script
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
MASTER = PROJECT / "design" / "icon-proposals" / "origin-icon-system-v2" / "generate_proposal.py"
# v3 (Will, 2026-10-12): the refined export of the v2 family -- weight classes,
# optical sizes and the redraws listed in its README. Its SVGs are FINAL masters
# (stroke width + optical scale already inside each file), so they are baked
# as-is; v2's generate_proposal.py still supplies the PUBLIC_IDS catalogue.
PACK = PROJECT / "design" / "icon-proposals" / "origin-icon-system-v3" / "svg"
OUT = (PROJECT / "src" / "mods" / "versions" / "1.21.1" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui")

CELL = 192
COLS = 6
SDF_RANGE = 16.0
# Small-size optical correction (SDF distance, in atlas px). v1's thin 1.55 px
# stroke needed +0.6 to hold up next to Inter Semibold; v2's 2.2 px stroke is
# already that heavy, so it bakes true to the master.
SDF_WEIGHT_BIAS = 0.0
# Extra browser-window height beyond the atlas (see the screenshot call).
WINDOW_SLACK = 256

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
    # Lunar-parity batch 1 — appended so every existing cell keeps its position.
    "clock": "client.clock",
    "stopwatch": "client.stopwatch",
    "daycounter": "client.daycounter",
    "ping": "client.ping",
    "memory": "client.memory",
    "saturation": "client.saturation",
    "packdisplay": "client.packdisplay",
    "itemcounter": "client.itemcounter",
    "directionhud": "client.directionhud",
    "combo": "client.combo",
    "reach": "client.reach",
    "crosshair": "client.crosshair",
}
# Lunar parity, batch 2 (2026-10-11) -- appended so every earlier cell keeps its
# atlas position.
PUBLIC_IDS.update({
    "hurtcam": "client.hurtcam",
    "hitcolor": "client.hitcolor",
    "glintcolorizer": "client.glintcolorizer",
    "bossbar": "client.bossbar",
    "titles": "client.titles",
    "menublur": "client.menublur",
    "shinypots": "client.shinypots",
    "tntcountdown": "client.tntcountdown",
    "snaplook": "client.snaplook",
    "cooldowns": "client.cooldowns",
    "soundchanger": "client.soundchanger",
    "itemphysics": "client.itemphysics",
})
# Lunar parity, batch 4b (2026-10-11) -- appended after every earlier cell.
PUBLIC_IDS.update({
    "worldeditcui": "client.worldeditcui",
})

# Lunar parity, batch 3b (2026-10-11) -- server integrations, appended after batch 2.
PUBLIC_IDS.update({
    "teamview": "client.teamview",
    "discord": "client.discord",
    "hypixel": "client.hypixel",
})

# Lunar parity, batch 4a (2026-10-11) -- appended after every earlier cell.
PUBLIC_IDS.update({
    "minimap": "client.minimap",
})

# Lunar parity, batch 3a (2026-10-11) -- chat / server mods, appended after batch 3b.
PUBLIC_IDS.update({
    "autotext": "client.autotext",
    "autogg": "client.autogg",
    "nickhider": "client.nickhider",
    "quickplay": "client.quickplay",
    "screenshot": "client.screenshot",
})

# Lunar parity (2026-10-11) -- Hypixel Bed Wars, appended after every earlier cell.
PUBLIC_IDS.update({
    "hypixelbedwars": "client.hypixelbedwars",
})

# Origin originals (2026-10-12) -- Portal Calculator, appended after every earlier cell.
PUBLIC_IDS.update({
    "portalcalc": "client.portalcalc",
})

# Origin originals (2026-10-12) -- FPS Coach, appended after every earlier cell.
PUBLIC_IDS.update({
    "fpscoach": "client.fpscoach",
})

# Menu chrome added by the 2026-10 mod-menu redesign (design/menu-redesign).
# Same 24-grid + stroke as the rest of the set; drawn here because they are
# plain UI symbols, not mod concepts, so they don't belong in the icon masters.
CHROME = {
    "@grid": '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>'
             '<rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/>',
    "@list": '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
    "@pin": '<path d="M9 3h6l-1 6 4 4H6l4-4z" fill="currentColor"/><path d="M12 13v8"/>',
    "@reset": '<path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 3v5h5"/>',
    "@shirt": '<path d="M20.4 6.6 16 4l-1.5 2a3.2 3.2 0 0 1-5 0L8 4 3.6 6.6l1.8 4.2L7 10v10h10V10l1.6.8z"/>',
    "@user": '<circle cx="12" cy="7" r="4"/><path d="M5 21v-2a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v2"/>',
    "@chev-down": '<polyline points="6 9 12 15 18 9"/>',
    "@chev-left": '<polyline points="15 18 9 12 15 6"/>',
    "@chev-right": '<polyline points="9 18 15 12 9 6"/>',
    "@close": '<path d="M18 6 6 18M6 6l12 12"/>',
    "@plus": '<path d="M12 5v14M5 12h14"/>',
    "@trash": '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/>',
    "@check": '<polyline points="20 6 9 17 4 12"/>',
    # A plain magnifier for search FIELDS (the pack's "search" is a find-in-page card).
    "@find": '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
}


def _norm(name: str) -> str:
    return name.replace("@", "").replace("-", "").lower()


_PACK_ROOT = 'fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"'


def pack_entry(public_id: str):
    """The v3 pack's glyph for a public id (pack names are the ids with dashes:
    'freelook' -> free-look.svg, '@hudeditor' -> hud-editor.svg), or None."""
    if not PACK.is_dir():
        return None
    want = _norm(public_id)
    for path in PACK.glob("*.svg"):
        if _norm(path.stem) == want:
            text = path.read_text(encoding="utf-8")
            inner = text[text.index(">", text.index("<svg")) + 1: text.rindex("</svg>")].strip()
            return {"id": public_id, "body": inner, "stroke": _PACK_ROOT}
    return None


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
    # Playwright's bundled Chromium (cloud sandboxes).
    for bundled in sorted(Path("/opt/pw-browsers").glob("chromium-*/chrome-linux/chrome")):
        return bundled
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
        # An entry may carry its own stroke (the brand mark keeps v1's weight).
        # Clipped to its own cell: a stroke on the grid's outer edge would
        # otherwise spill a sliver into the neighbouring cell.
        parts.append(f'<clipPath id="c{index}"><rect x="{x}" y="{y}" width="{CELL}" height="{CELL}"/></clipPath>')
        parts.append(f'<g clip-path="url(#c{index})"><g transform="translate({x} {y}) scale({CELL / 24:g})" '
                     f'{entry.get("stroke", stroke)}>{entry["body"]}</g></g>')
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
        # Shift inside a cell-sized canvas so the moved glyph is cropped to its
        # own cell. Compositing straight into the atlas let a shifted glyph
        # (Bed Wars moves 12 px up) draw into the cell above it.
        shifted = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
        shifted.paste(glyph, (dx, dy), glyph)   # paste (not alpha_composite) accepts negative offsets
        centered.alpha_composite(shifted, (x, y))
        offsets[public_id] = [dx, dy]
    return centered, offsets


def build() -> None:
    master = load_master()
    by_id = {entry["id"]: entry for entry in master.ICONS}
    missing = sorted(set(PUBLIC_IDS.values()) - set(by_id))
    if missing:
        raise RuntimeError(f"Approved master icons missing: {', '.join(missing)}")

    entries = [(public_id, pack_entry(public_id) or by_id[master_id]) for public_id, master_id in PUBLIC_IDS.items()]
    # Drawn at the v3 pack's thin class (1.65) so they sit with its menu glyphs.
    thin = _PACK_ROOT + ' stroke-width="1.65"'
    entries += [(public_id, {"id": public_id, "body": body, "stroke": thin}) for public_id, body in CHROME.items()]
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
            # Window is taller than the atlas on purpose: headless Chromium on
            # Linux leaves the bottom ~90 px of the window unpainted (browser
            # chrome), which silently cut the last icon row in half once the
            # atlas grew to 9 rows. The extra strip is cropped off below.
            f"--window-size={width},{height + WINDOW_SLACK}", f"--user-data-dir={temp / 'browser'}",
            f"--screenshot={rendered}", source.as_uri(),
        ]
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            args.insert(1, "--no-sandbox")   # Chromium refuses to run as root otherwise (containers)
        subprocess.run(args, check=True, capture_output=True, text=True, timeout=45)
        with Image.open(rendered) as image:
            atlas = image.convert("RGBA").crop((0, 0, width, height))
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
        "style": "origin-outline-2.2",
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
