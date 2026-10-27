#!/usr/bin/env python3
"""Origin's dark widget sprites: the buttons, sliders, text fields, checkboxes, tabs,
scroll bars and lock buttons every vanilla screen draws (Will, 2026-10-26: "change all
them besides the main menu custom ones to be the same ones in the default dark mode
texture pack").

Drawn from scratch here, pixel by pixel. Default Dark Mode (nebulR) is CC BY-NC-SA 4.0:
no commercial use and share-alike, and Origin sells cosmetics, so none of its files are
copied; this draws Origin's own flat-charcoal set in that look. Nothing is traced from
Mojang's textures either: vanilla only supplies the sizes and nine-slice borders.

The sprites ship INSIDE the mod jar under assets/minecraft/..., so Fabric's mod resources
override vanilla's and no resource pack ever appears in the pack list. A player's own
resource pack still sits above them and wins.

Eras:
  sprites  1.20.2+ (gui/sprites/widget/*.png + nine-slice .mcmeta)
  sheet    pre-1.20.2 (one 200 x 60 strip: disabled, normal, highlighted rows of
           20 px, the order vanilla's widgets.png uses; the module's button mixin
           blits it, so Mojang's widgets.png is never redistributed)

Usage:
  python tools/dark-widgets/make_widgets.py sprites <module resources dir>
  python tools/dark-widgets/make_widgets.py sheet   <module resources dir>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image

# ---- palette (ARGB-free RGB tuples; every pixel opaque unless noted) ----
OUTLINE = (11, 11, 12)          # the 1 px ring round every control
HOVER = (255, 255, 255)         # highlighted/focused ring, like vanilla's white hover
FILL = (48, 48, 51)             # button face
LIGHT = (61, 61, 65)            # top/left bevel
SHADE = (35, 35, 38)            # bottom/right bevel (2 px bottom, like vanilla's lip)
FILL_HI = (62, 62, 66)
LIGHT_HI = (78, 78, 83)
SHADE_HI = (46, 46, 49)
FILL_OFF = (28, 28, 30)         # disabled: flat, no bevel
LIGHT_OFF = (34, 34, 36)
FIELD = (14, 14, 15)            # text field / checkbox / slider track well
FIELD_EDGE = (80, 80, 85)
TRACK = (26, 26, 28)
HANDLE = (74, 74, 79)
HANDLE_LIGHT = (92, 92, 97)
HANDLE_SHADE = (54, 54, 58)
TAB_OFF = (28, 28, 30)
TAB_OFF_LIGHT = (40, 40, 43)
GLYPH = (232, 232, 232)
GLYPH_OFF = (122, 122, 126)


def img(w: int, h: int) -> Image.Image:
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def rect(im: Image.Image, x0: int, y0: int, x1: int, y1: int, c) -> None:
    """Fill [x0, x1) x [y0, y1)."""
    px = im.load()
    for y in range(max(0, y0), min(im.height, y1)):
        for x in range(max(0, x0), min(im.width, x1)):
            px[x, y] = c + (255,) if len(c) == 3 else c


def ring(im: Image.Image, c, x0=0, y0=0, x1=None, y1=None, open_bottom=False) -> None:
    x1 = im.width if x1 is None else x1
    y1 = im.height if y1 is None else y1
    rect(im, x0, y0, x1, y0 + 1, c)
    if not open_bottom:
        rect(im, x0, y1 - 1, x1, y1, c)
    rect(im, x0, y0, x0 + 1, y1, c)
    rect(im, x1 - 1, y0, x1, y1, c)


def bevel_box(w: int, h: int, edge, fill, light, shade, lip: int = 2) -> Image.Image:
    """A raised control: outline ring, light top/left, shaded bottom (lip px) and right."""
    im = img(w, h)
    rect(im, 0, 0, w, h, fill)
    rect(im, 1, 1, w - 1, 2, light)
    rect(im, 1, 1, 2, h - 1, light)
    rect(im, 1, h - 1 - lip, w - 1, h - 1, shade)
    rect(im, w - 2, 1, w - 1, h - 1, shade)
    ring(im, edge)
    return im


def button(state: str, w: int = 200, h: int = 20) -> Image.Image:
    if state == "disabled":
        im = img(w, h)
        rect(im, 0, 0, w, h, FILL_OFF)
        rect(im, 1, 1, w - 1, 2, LIGHT_OFF)
        ring(im, OUTLINE)
        return im
    if state == "highlighted":
        return bevel_box(w, h, HOVER, FILL_HI, LIGHT_HI, SHADE_HI)
    return bevel_box(w, h, OUTLINE, FILL, LIGHT, SHADE)


def well(w: int, h: int, edge, fill=FIELD) -> Image.Image:
    im = img(w, h)
    rect(im, 0, 0, w, h, fill)
    ring(im, edge)
    return im


CHECK = [  # 10 x 8 check mark, drawn at (5, 6) in the 20 x 20 box
    "........##",
    ".......##.",
    "......##..",
    "#....##...",
    "##..##....",
    ".####.....",
    "..##......",
    "..........",
]

LOCK_SHUT = [  # 10 x 12 padlock, drawn at (5, 4)
    "...####...",
    "..#....#..",
    "..#....#..",
    "..#....#..",
    "##########",
    "##########",
    "####..####",
    "####..####",
    "#####.####",
    "##########",
    "##########",
    "..........",
]

LOCK_OPEN = [
    "......####",
    ".....#....",
    ".....#....",
    ".....#....",
    "######....",
    "######....",
    "##..##....",
    "##..##....",
    "###.##....",
    "######....",
    "######....",
    "..........",
]


def stamp(im: Image.Image, pattern: list[str], ox: int, oy: int, c) -> None:
    for y, row in enumerate(pattern):
        for x, ch in enumerate(row):
            if ch == "#":
                rect(im, ox + x, oy + y, ox + x + 1, oy + y + 1, c)


def lock_button(state: str, shut: bool) -> Image.Image:
    im = button(state, 20, 20)
    glyph = GLYPH_OFF if state == "disabled" else GLYPH
    pattern = LOCK_SHUT if shut else LOCK_OPEN
    # The open padlock is narrower: centre its 6 px body instead of the 10 px box.
    stamp(im, pattern, 5 if shut else 7, 4, glyph)
    return im


def tab(selected: bool, highlighted: bool) -> Image.Image:
    w, h = 130, 24
    im = img(w, h)
    if selected:
        rect(im, 0, 0, w, h, FILL)
        rect(im, 1, 1, w - 1, 2, LIGHT)
        rect(im, 1, 1, 2, h, LIGHT)
    else:
        # Unselected tabs sit 2 px lower and darker, like vanilla's.
        rect(im, 0, 2, w, h, TAB_OFF)
        rect(im, 1, 3, w - 1, 4, TAB_OFF_LIGHT)
    top = 0 if selected else 2
    ring(im, HOVER if highlighted else OUTLINE, 0, top, w, h, open_bottom=True)
    return im


def scroller() -> Image.Image:
    return bevel_box(6, 32, OUTLINE, HANDLE, HANDLE_LIGHT, HANDLE_SHADE, lip=1)


def nine(w: int, h: int, border) -> dict:
    return {"gui": {"scaling": {"type": "nine_slice", "width": w, "height": h, "border": border}}}


def save(im: Image.Image, path: Path, meta: dict | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, optimize=True)
    if meta is not None:
        Path(str(path) + ".mcmeta").write_text(json.dumps(meta, indent=4) + "\n", encoding="ascii")


def sprites(res: Path) -> None:
    d = res / "assets" / "minecraft" / "textures" / "gui" / "sprites" / "widget"
    btn = nine(200, 20, 3)
    save(button("normal"), d / "button.png", btn)
    save(button("highlighted"), d / "button_highlighted.png", btn)
    save(button("disabled"), d / "button_disabled.png", btn)

    track = nine(200, 20, 1)
    save(well(200, 20, OUTLINE, TRACK), d / "slider.png", track)
    save(well(200, 20, HOVER, TRACK), d / "slider_highlighted.png", track)
    save(bevel_box(8, 20, OUTLINE, HANDLE, HANDLE_LIGHT, HANDLE_SHADE), d / "slider_handle.png", nine(8, 20, 1))
    save(bevel_box(8, 20, HOVER, HANDLE, HANDLE_LIGHT, HANDLE_SHADE), d / "slider_handle_highlighted.png", nine(8, 20, 1))

    field = nine(200, 20, 1)
    save(well(200, 20, FIELD_EDGE), d / "text_field.png", field)
    save(well(200, 20, HOVER), d / "text_field_highlighted.png", field)

    for sel in (False, True):
        for hi in (False, True):
            im = well(20, 20, HOVER if hi else FIELD_EDGE)
            if sel:
                stamp(im, CHECK, 5, 6, GLYPH)
            name = "checkbox" + ("_selected" if sel else "") + ("_highlighted" if hi else "")
            save(im, d / f"{name}.png")

    tab_meta = nine(130, 24, {"left": 2, "top": 2, "right": 2, "bottom": 0})
    save(tab(False, False), d / "tab.png", tab_meta)
    save(tab(False, True), d / "tab_highlighted.png", tab_meta)
    save(tab(True, False), d / "tab_selected.png", tab_meta)
    save(tab(True, True), d / "tab_selected_highlighted.png", tab_meta)

    save(scroller(), d / "scroller.png", nine(6, 32, 1))
    save(well(6, 32, OUTLINE, FIELD), d / "scroller_background.png", nine(6, 32, 1))

    for shut, base in ((True, "locked_button"), (False, "unlocked_button")):
        save(lock_button("normal", shut), d / f"{base}.png")
        save(lock_button("highlighted", shut), d / f"{base}_highlighted.png")
        save(lock_button("disabled", shut), d / f"{base}_disabled.png")


def sheet(res: Path) -> None:
    """Pre-1.20.2: one strip in widgets.png's row order; the module's button mixin blits it."""
    d = res / "assets" / "originclient" / "textures" / "gui"
    strip = img(200, 60)
    strip.paste(button("disabled"), (0, 0))
    strip.paste(button("normal"), (0, 20))
    strip.paste(button("highlighted"), (0, 40))
    save(strip, d / "dark_button.png")


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ("sprites", "sheet"):
        print(__doc__)
        return 2
    res = Path(sys.argv[2])
    (sprites if sys.argv[1] == "sprites" else sheet)(res)
    print(f"wrote {sys.argv[1]} set under {res}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
