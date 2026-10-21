#!/usr/bin/env python3
"""Minecraft-style pixel icons for the main-menu icon row (Will, 2026-10-20).

"the icons at the bottom look off on the buttons, make them Minecraft style --
not text but icons -- so they fit in for all versions."

ONE source of truth for the six title-dock icons (Realms, Friends, Cosmetics,
Language, Accessibility, Quit). The art is hand-set bitmaps below, drawn on a
12 x 12 grid: ONE grid pixel = ONE logical GUI pixel at the 12 px the dock draws
them at, exactly like vanilla's own 1 px button glyphs and font. Each grid pixel
is a hard 16 x 16 texel block of the 192 px atlas cell, so at every integer GUI
scale the block edges land on whole screen pixels -- both the linear mask path
(pre-1.20) and the SDF path (1.20+) therefore draw crisp square pixels with no
stair-stepping (the 1.16.5 "icons look jagged" playtest).

They are added to the shared atlas under NEW ids ("@px-realms", "@px-friends",
"@px-shirt", "@px-language", "@px-access", "@px-quit") so the vector glyphs the
other screens use (Friends list, Cosmetics screen at 16-32 px) are untouched.
Only the TitleScreenMixins' dock icons point at the new ids.

Run:  python tools/mod-menu/pixel_title_icons.py
  - patches the 1.21.1 atlas (mod_icons.png / mod_icons_sdf.png / mod_icons.json)
    in place, adding the cells (idempotent: re-running rewrites the same cells),
  - copies the three files to every other LIVE module (same rule as
    generate_icons.py, which also calls apply() so a regenerated atlas keeps them),
  - writes tools/mod-menu/title_pixel_icons_preview.png (3x review sheet).
"""

from __future__ import annotations

import json
import os
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import generate_icons as gen  # noqa: E402  (make_sdf's distance maths, OUT, PROJECT)

GRID = 12
BLOCK = gen.CELL // GRID  # 16 texels per art pixel

# '#' = lit. Each bitmap is centred on the 12 grid by its own (even) width/height.
ART: dict[str, list[str]] = {
    # Realms: a cut gem -- crown, table facets (unlit seams), pavilion to a point.
    "@px-realms": [
        "...######...",
        ".##########.",
        "##.#.##.#.##",
        "#####..#####",
        ".##########.",
        "..#.####.#..",
        "...#.##.#...",
        "....####....",
        ".....##.....",
        "............",
    ],
    # Friends: two players side by side, head + shoulders, a seam between them.
    "@px-friends": [
        "..##....##..",
        ".####..####.",
        ".####..####.",
        "..##....##..",
        "............",
        ".####..####.",
        "#####..#####",
        "#####..#####",
    ],
    # Cosmetics: a tee shirt -- collar notch, sleeve seams.
    "@px-shirt": [
        "..###..###..",
        ".####..####.",
        "#####..#####",
        "#####..#####",
        "##.######.##",
        "##.######.##",
        "...######...",
        "...######...",
    ],
    # Language: a globe -- ring with a meridian and an equator.
    "@px-language": [
        "....####....",
        "..##.##.##..",
        ".#...##...#.",
        ".#...##...#.",
        "#....##....#",
        "############",
        "############",
        "#....##....#",
        ".#...##...#.",
        ".#...##...#.",
        "..##.##.##..",
        "....####....",
    ],
    # Accessibility: the universal-access figure inside its ring.
    "@px-access": [
        "....####....",
        "..##....##..",
        ".#...##...#.",
        ".#...##...#.",
        "#..######..#",
        "#....##....#",
        "#....##....#",
        "#...#..#...#",
        ".#..#..#..#.",
        ".#.#....#.#.",
        "..##....##..",
        "....####....",
    ],
    # Quit: the power symbol -- ring with a gap at the top, stem through it.
    "@px-quit": [
        ".....##.....",
        "..#..##..#..",
        ".#...##...#.",
        ".#...##...#.",
        "#....##....#",
        "#..........#",
        "#..........#",
        "#..........#",
        ".#........#.",
        ".#........#.",
        "..#......#..",
        "...######...",
    ],
}


def cell_mask(rows: list[str]) -> np.ndarray:
    """192 x 192 alpha (0/255) for one bitmap, centred on the 12 grid."""
    h, w = len(rows), len(rows[0])
    if w % 2 or h % 2 or w > GRID or h > GRID or any(len(r) != w for r in rows):
        raise ValueError(f"bitmap must be even-sized, <= {GRID}, rectangular: {w}x{h}")
    ox, oy = (GRID - w) // 2, (GRID - h) // 2
    out = np.zeros((gen.CELL, gen.CELL), dtype=np.uint8)
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == "#":
                y, x = (oy + r) * BLOCK, (ox + c) * BLOCK
                out[y:y + BLOCK, x:x + BLOCK] = 255
    return out


def cell_sdf(mask: np.ndarray) -> np.ndarray:
    """Same signed-distance encoding as generate_icons.make_sdf, for one cell."""
    img = Image.fromarray(np.dstack([np.full_like(mask, 255)] * 3 + [mask]), mode="RGBA")
    return np.asarray(gen.make_sdf(img))  # RGBA, channel = encoded distance


def apply(ui: Path) -> list[str]:
    """Add/refresh the pixel cells in the atlas triple under `ui`. Returns the ids."""
    meta_path = ui / "mod_icons.json"
    meta = json.loads(meta_path.read_text())
    cols, cell = meta["cols"], meta["cell"]
    assert cell == gen.CELL, "atlas cell size changed"
    icons = meta["icons"]
    with Image.open(ui / "mod_icons.png") as im:
        mask_atlas = im.convert("RGBA")
    with Image.open(ui / "mod_icons_sdf.png") as im:
        sdf_atlas = im.convert("RGBA")

    # Cells for the new ids: reuse existing ones, else append after the last cell.
    positions: dict[str, tuple[int, int]] = {}
    next_index = len(icons)
    for pid in ART:
        if pid in icons:
            positions[pid] = (icons[pid]["x"], icons[pid]["y"])
        else:
            positions[pid] = ((next_index % cols) * cell, (next_index // cols) * cell)
            next_index += 1

    need_h = max(y for _, y in positions.values()) + cell
    if need_h > mask_atlas.height:
        for name in ("mask", "sdf"):
            atlas = mask_atlas if name == "mask" else sdf_atlas
            grown = Image.new("RGBA", (atlas.width, need_h),
                              (0, 0, 0, 0) if name == "mask" else (0, 0, 0, 255))
            grown.paste(atlas, (0, 0))
            if name == "mask":
                mask_atlas = grown
            else:
                sdf_atlas = grown

    for pid, rows in ART.items():
        x, y = positions[pid]
        mask = cell_mask(rows)
        m = np.dstack([np.full_like(mask, 255)] * 3 + [mask])
        mask_atlas.paste(Image.fromarray(m, mode="RGBA"), (x, y))
        sdf_atlas.paste(Image.fromarray(cell_sdf(mask), mode="RGBA"), (x, y))
        icons[pid] = {"x": x, "y": y}
        meta.setdefault("centerOffsets", {})[pid] = [0, 0]

    for name, atlas in (("mod_icons.png", mask_atlas), ("mod_icons_sdf.png", sdf_atlas)):
        tmp = ui / (name + ".tmp")
        atlas.save(tmp, format="PNG", optimize=True)
        os.replace(tmp, ui / name)
    tmp = ui / "mod_icons.json.tmp"
    tmp.write_text(json.dumps(meta, indent=2) + "\n")
    os.replace(tmp, meta_path)
    return list(ART)


def preview(ui: Path) -> None:
    """3x sheet on the vanilla button grey: the dock at 12 px (3x) beside the 1x size."""
    meta = json.loads((ui / "mod_icons.json").read_text())
    with Image.open(ui / "mod_icons.png") as im:
        atlas = im.convert("RGBA")
    n = len(ART)
    sheet = Image.new("RGBA", (n * 20 * 6 + 8, 20 * 6 + 20 * 2 + 16), (0x30, 0x30, 0x30, 255))
    for i, pid in enumerate(ART):
        x, y = meta["icons"][pid]["x"], meta["icons"][pid]["y"]
        icon = atlas.crop((x, y, x + gen.CELL, y + gen.CELL))
        # nearest down to 12 px = exactly what the dock draws at guiScale 1
        small = icon.resize((GRID, GRID), Image.NEAREST)
        big = small.resize((GRID * 6, GRID * 6), Image.NEAREST)
        bx = 4 + i * 20 * 6
        sheet.alpha_composite(big, (bx + (20 * 6 - GRID * 6) // 2, 4 + (20 * 6 - GRID * 6) // 2))
        sheet.alpha_composite(small, (bx + 20 * 3 - GRID // 2, 20 * 6 + 12))
    sheet.convert("RGB").save(HERE / "title_pixel_icons_preview.png", optimize=True)


def main() -> None:
    ids = apply(gen.OUT)
    preview(gen.OUT)
    live = {m["module"] for m in json.loads((gen.PROJECT / "src" / "mods" / "live-modules.json").read_text())["modules"]}
    copied = []
    for module in sorted((gen.PROJECT / "src" / "mods" / "versions").iterdir()):
        if module.name not in live and module.name != "1.8.9":
            continue
        for ui in module.glob("src/*/resources/assets/originclient/textures/ui"):
            if ui.resolve() == gen.OUT.resolve() or not (ui / "mod_icons.json").exists():
                continue
            for name in ("mod_icons.png", "mod_icons.json", "mod_icons_sdf.png"):
                shutil.copyfile(gen.OUT / name, ui / name)
            copied.append(module.name)
    print("pixel icons: " + ", ".join(ids))
    print("copied to: " + ", ".join(copied))


if __name__ == "__main__":
    main()
