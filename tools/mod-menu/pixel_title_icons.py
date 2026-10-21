#!/usr/bin/env python3
"""Minecraft-style COLOUR pixel icons (Will, 2026-10-20): "the icons in game suck, make them
way better ... real Minecraft item/menu icons: colour + material, 3-4 tones, dark outline".

ONE source of truth for the coloured pixel icon set (title dock first, mod-menu categories
next). Hard pixels only -- no gradients, no anti-aliasing: every icon is a 12 x 12 bitmap,
one art pixel = one logical GUI pixel at the 12 px the dock draws at, so every integer GUI
scale lands the pixels on whole screen pixels (drawn NEAREST).

Vanilla lighting convention in every icon: light from the top-left (highlight), a mid base,
a darker bottom-right shade, and a 1 px dark outline. The shared draw helper adds vanilla's
text drop shadow (+1 GUI px right/down, rgb/4) under every icon, so the art carries none.

Output (per module, next to mod_icons.png): mod_icons_px.png (12 px cells, RGBA, nearest)
and mod_icons_px.json ({"cell": 12, "cols": N, "icons": {id: {x, y}}}). ModIcons draws an id
from this atlas when it has one (colour, tinted only by a brightness factor) and otherwise
falls back to the monochrome SDF atlas -- so the vector glyphs other screens use are intact.

Run:  python tools/mod-menu/pixel_title_icons.py
  - writes the atlas into the 1.21.1 module and copies it to every other LIVE module + 1.8.9,
  - writes tools/mod-menu/title_pixel_icons_preview.png (8x, on vanilla button grey + dark).
generate_icons.py also calls apply(OUT) so a regenerated vector atlas keeps the colour one.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import generate_icons as gen  # noqa: E402  (OUT, PROJECT)

G = 12  # grid size == atlas cell size
COLS = 8

OUTLINE = (0x1b, 0x1b, 0x24)


def rgb(h: str) -> tuple[int, int, int]:
    return int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16)


def bitmap(rows: list[str], pal: dict[str, str]) -> list[list[tuple | None]]:
    if len(rows) > G or any(len(r) != G for r in rows):
        raise ValueError(f"bitmap must be 12 wide, <= 12 tall: {[len(r) for r in rows]}")
    px: list[list[tuple | None]] = [[None] * G for _ in range(G)]
    oy = (G - len(rows)) // 2
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            px[oy + y][x] = OUTLINE if ch == "k" else rgb(pal[ch])
    return px


# 12 px disc: half-widths per row (cols [6-w, 6+w))
DISC_W = [2, 4, 5, 6, 6, 6, 6, 6, 6, 5, 4, 2]


def disc(pal: tuple[str, str, str, str]) -> list[list[tuple | None]]:
    """Shaded sphere: outline ring + highlight / base / shade bands lit from the top-left."""
    hi, base, shade, deep = (rgb(c) for c in pal)
    px: list[list[tuple | None]] = [[None] * G for _ in range(G)]

    def inside(x, y):
        return 0 <= y < G and 6 - DISC_W[y] <= x < 6 + DISC_W[y]

    for y in range(G):
        for x in range(G):
            if not inside(x, y):
                continue
            edge = not (inside(x - 1, y) and inside(x + 1, y) and inside(x, y - 1) and inside(x, y + 1))
            if edge:
                px[y][x] = OUTLINE
                continue
            d = (x - 3.2) ** 2 + (y - 3.2) ** 2
            px[y][x] = hi if d < 5 else base if d < 24 else shade if d < 52 else deep
    return px


def overlay(px, rows: list[str], pal: dict[str, str], dx=0, dy=0) -> None:
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and 0 <= y + dy < G and 0 <= x + dx < G:
                px[y + dy][x + dx] = OUTLINE if ch == "k" else rgb(pal[ch])


# ---------------------------------------------------------------------------------------------
ICONS: dict[str, list[list[tuple | None]]] = {}

# Realms: a diamond-tone gem -- facets (light crown, base, shade), outline all round.
ICONS["@px-realms"] = bitmap([
    "...kkkkkk...",
    "..kwwcccck..",
    ".kwwcbbcddk.",
    "kwcccbbcbddk",
    "kkkkkkkkkkkk",
    ".kwcccbbdddk",
    "..kwcbbbddk.",
    "...kcbbddk..",
    "....kbbdk...",
    ".....kdk....",
    "......k.....",
], {"w": "#e6ffff", "c": "#7af2ea", "b": "#2fc8c4", "d": "#188a97"})

# Friends: two player heads (blonde behind, brown in front), skin + hair tones, eyes.
ICONS["@px-friends"] = bitmap([
    ".....kkkkkkk",
    ".....kYyyyyk",
    ".....kyyyyyk",
    ".....kfefefk",
    ".....kfffffk",
    "kkkkkkkssssk",
    "kgggggkkkkkk",
    "khhhhhk.....",
    "kfefefk.....",
    "kfffffk.....",
    "ksssssk.....",
    "kkkkkkk.....",
], {"Y": "#fbe88a", "y": "#e3b93f", "g": "#9a6a3c", "h": "#6b4423",
    "f": "#efb98c", "s": "#c4825a", "e": "#3b3fa8"})

# Cosmetics: a dyed (purple) chestplate with gold buttons.
ICONS["@px-shirt"] = bitmap([
    ".kkkk..kkkk.",
    "kwcck..kbbdk",
    "kwccckkcbbdk",
    ".kwccccbbdk.",
    ".kwccyybbdk.",
    ".kwccyybbdk.",
    ".kwccccbbdk.",
    ".kwccccbbdk.",
    ".kcccbbbbdk.",
    ".kkkkkkkkkk.",
], {"w": "#e3a8f7", "c": "#b560e0", "b": "#8a36b8", "d": "#5a1f80", "y": "#ffd84a"})

# Language: Earth -- blue ocean sphere with green continents.
_globe = disc(("#8ec2ff", "#3a7de0", "#2459b4", "#183c80"))
overlay(_globe, [
    "............",
    "...gg.......",
    "..gGgg......",
    "..gggg..gg..",
    "...ggg.gGgg.",
    "....g..gggg.",
    "......ggGg..",
    "......gggg..",
    ".......gg...",
    "............",
], {"g": "#5cc24a", "G": "#2f8a32"}, 0, 1)
ICONS["@px-language"] = _globe

# Accessibility: blue badge with the white universal-access figure.
_acc = disc(("#7ab8ff", "#2f7bf0", "#1f56c0", "#143a8a"))
overlay(_acc, [
    "............",
    "............",
    ".....ww.....",
    ".....ww.....",
    "..wwwwwwww..",
    ".....ww.....",
    ".....ww.....",
    "....wwww....",
    "....w..w....",
    "....w..w....",
], {"w": "#ffffff"})
ICONS["@px-access"] = _acc

# Quit: red power button, white power symbol.
_quit = disc(("#ff9a8a", "#e8392b", "#b3201a", "#7a1210"))
overlay(_quit, [
    "............",
    "............",
    ".....ww.....",
    "...w.ww.w...",
    "..w..ww..w..",
    "..w..ww..w..",
    "..w......w..",
    "..w......w..",
    "...w....w...",
    "....wwww....",
], {"w": "#ffffff"})
ICONS["@px-quit"] = _quit

# Mod-menu module + tab icons (data in pixel_module_icons.py): same palette rules, same grid.
from pixel_module_icons import ART as _MODULE_ART  # noqa: E402

for _id, (_rows, _pal) in _MODULE_ART.items():
    if _id != "@general_old":
        ICONS[_id] = bitmap(_rows, _pal)

# Settings tab: a cog -- shaded steel disc, dark hub, notches cut at the compass points.
_cog = disc(("#e4e4ee", "#b0b0c0", "#7c7c92", "#565668"))
overlay(_cog, [
    "............",
    "............",
    "............",
    "............",
    "....kkkk....",
    "....knnk....",
    "....knnk....",
    "....kkkk....",
], {"n": "#23232e"}, 0, 2)
for _x, _y in ((5, 0), (6, 0), (5, 11), (6, 11), (0, 5), (0, 6), (11, 5), (11, 6),
               (1, 2), (2, 1), (9, 1), (10, 2), (1, 9), (2, 10), (9, 10), (10, 9)):
    _cog[_y][_x] = None
ICONS["@general"] = _cog
# ---------------------------------------------------------------------------------------------


def build() -> tuple[Image.Image, dict]:
    n = len(ICONS)
    rows = (n + COLS - 1) // COLS
    atlas = Image.new("RGBA", (COLS * G, rows * G), (0, 0, 0, 0))
    meta = {"cell": G, "cols": COLS, "icons": {}}
    for i, (pid, px) in enumerate(ICONS.items()):
        ox, oy = (i % COLS) * G, (i // COLS) * G
        for y in range(G):
            for x in range(G):
                c = px[y][x]
                if c is not None:
                    atlas.putpixel((ox + x, oy + y), (*c, 255))
        meta["icons"][pid] = {"x": ox, "y": oy}
    return atlas, meta


def apply(ui: Path) -> list[str]:
    atlas, meta = build()
    tmp = ui / "mod_icons_px.png.tmp"
    atlas.save(tmp, format="PNG", optimize=True)
    os.replace(tmp, ui / "mod_icons_px.png")
    (ui / "mod_icons_px.json").write_text(json.dumps(meta, indent=1) + "\n")
    return list(ICONS)


def preview(ui: Path, out: Path | None = None) -> None:
    """6x nearest sheet, 10 per row, alternating vanilla button grey / dark backdrop, with the
    1 px drop shadow the draw helper adds (rgb/4, +1 right, +1 down)."""
    atlas = Image.open(ui / "mod_icons_px.png").convert("RGBA")
    meta = json.loads((ui / "mod_icons_px.json").read_text())
    S, PER, T = 6, 10, 16
    n = len(ICONS)
    rows = (n + PER - 1) // PER
    sheet = Image.new("RGBA", (PER * T * S, rows * T * S), (0x20, 0x20, 0x20, 255))
    for i, pid in enumerate(ICONS):
        x, y = meta["icons"][pid]["x"], meta["icons"][pid]["y"]
        icon = atlas.crop((x, y, x + G, y + G))
        shadow = icon.copy()
        sp = shadow.load()
        for yy in range(G):
            for xx in range(G):
                r, g, b, a = sp[xx, yy]
                sp[xx, yy] = (r // 4, g // 4, b // 4, a)
        bg = (0x6f, 0x6f, 0x6f) if (i + i // PER) % 2 == 0 else (0x18, 0x18, 0x1c)
        tile = Image.new("RGBA", (T, T), (*bg, 255))
        tile.alpha_composite(shadow, (2 + 1, 2 + 1))
        tile.alpha_composite(icon, (2, 2))
        sheet.alpha_composite(tile.resize((T * S, T * S), Image.NEAREST), ((i % PER) * T * S, (i // PER) * T * S))
    sheet.convert("RGB").save(out or HERE / "title_pixel_icons_preview.png", optimize=True)


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
            for name in ("mod_icons_px.png", "mod_icons_px.json"):
                shutil.copyfile(gen.OUT / name, ui / name)
            copied.append(module.name)
    print("colour pixel icons: " + ", ".join(ids))
    print("copied to: " + ", ".join(copied))


if __name__ == "__main__":
    main()
