#!/usr/bin/env python3
"""Origin mod-menu icon set v2 — SOLID silhouettes with negative-space cutouts.

Style (Will, 2026-09-03: "more creative, better icons"): each glyph is a bold
filled shape on the Design Assets 24-grid (2 px padding, 20 x 20 live area),
with its detail CUT OUT of the fill rather than stroked on top — the way
Phosphor Fill / SF Symbols fill weights read. Chunkier than the old outline
set, instantly recognisable at 16-24 px on a card, and still one family:
one grid, 2 px cut lines, round joins, no text, no 3D, no gradients.

The atlas is a WHITE ALPHA MASK — in-game the glyph is tinted with the theme
colour (TEXT on a card, ACCENT when the mod is on, MUTED in a field), so the
set follows the palette by construction. Icons that share a metaphor with the
menu chrome (search, settings tabs, HUD editor, backing) live in the same
atlas under their '@' ids.

Usage:  python generate_icons.py
Output: src/mods/versions/1.21.1/.../textures/ui/mod_icons.png + .json
        tools/mod-menu/icons_preview.png (contact sheet on the Slate panel colour)
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "mods" / "versions" / "1.21.1" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

GRID = 24
CELL = 96
SS = 4
K = CELL * SS / GRID
CUT = 2.0          # width of a cut line, grid units
ON, OFF = 255, 0


def P(v):
    return v * K


class Canvas:
    """Draw on a 24-grid; `v` chooses ink (ON) or cut (OFF)."""

    def __init__(self):
        self.img = Image.new("L", (CELL * SS, CELL * SS), 0)
        self.d = ImageDraw.Draw(self.img)

    def line(self, pts, w=CUT, v=ON):
        pts = [(P(x), P(y)) for x, y in pts]
        self.d.line(pts, fill=v, width=int(round(P(w))), joint="curve")
        r = P(w) / 2
        for x, y in pts:
            self.d.ellipse([x - r, y - r, x + r, y + r], fill=v)

    def disc(self, cx, cy, r, v=ON):
        self.d.ellipse([P(cx - r), P(cy - r), P(cx + r), P(cy + r)], fill=v)

    def ring(self, cx, cy, r, w=CUT, v=ON):
        self.d.ellipse([P(cx - r), P(cy - r), P(cx + r), P(cy + r)], outline=v, width=int(round(P(w))))

    def rect(self, x0, y0, x1, y1, rad=2, v=ON):
        self.d.rounded_rectangle([P(x0), P(y0), P(x1), P(y1)], radius=P(rad), fill=v)

    def poly(self, pts, v=ON):
        self.d.polygon([(P(x), P(y)) for x, y in pts], fill=v)

    def pie(self, cx, cy, r, a0, a1, v=ON):
        self.d.pieslice([P(cx - r), P(cy - r), P(cx + r), P(cy + r)], a0, a1, fill=v)

    def arc(self, cx, cy, r, a0, a1, w=CUT, v=ON):
        self.d.arc([P(cx - r), P(cy - r), P(cx + r), P(cy + r)], a0, a1, fill=v, width=int(round(P(w))))
        for a in (a0, a1):
            x, y = cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))
            rr = P(w) / 2
            self.d.ellipse([P(x) - rr, P(y) - rr, P(x) + rr, P(y) + rr], fill=v)

    def ellipse(self, x0, y0, x1, y1, v=ON):
        self.d.ellipse([P(x0), P(y0), P(x1), P(y1)], fill=v)


ICONS = {}


def icon(name):
    def reg(fn):
        ICONS[name] = fn
        return fn
    return reg


# ------------------------------------------------------------------ HUD readouts

@icon("fps")
def _(c):  # speedometer: solid 3/4 dial, cut needle + a red-line notch
    c.pie(12, 13, 10, 135, 405)
    c.disc(12, 13, 10, OFF); c.pie(12, 13, 10, 135, 405)
    c.line([(12, 13), (17.5, 7.5)], w=2.2, v=OFF)
    c.disc(12, 13, 2.4, OFF)
    c.disc(12, 13, 1.1)
    c.line([(18.6, 5.6), (20.4, 7.2)], w=1.6, v=OFF)


@icon("cps")
def _(c):  # mouse: solid shell, cut button split + cut scroll wheel
    c.rect(6.5, 2.5, 17.5, 21.5, 5.5)
    c.line([(12, 4.5), (12, 10.5)], w=1.6, v=OFF)
    c.rect(10.8, 5.5, 13.2, 8.5, 1.2, v=OFF)
    c.line([(6.5, 10.5), (17.5, 10.5)], w=1.4, v=OFF)


@icon("keystrokes")
def _(c):  # WASD: four solid keycaps + space bar
    c.rect(9, 2.5, 15, 8.5, 1.8)
    c.rect(2, 10, 8, 16, 1.8)
    c.rect(9, 10, 15, 16, 1.8)
    c.rect(16, 10, 22, 16, 1.8)
    c.rect(5, 17.5, 19, 21.5, 1.8)


@icon("coords")
def _(c):  # map pin: solid head + point, cut centre, ground shadow
    c.disc(12, 9, 7)
    c.poly([(6.2, 12.5), (17.8, 12.5), (12, 21)])
    c.disc(12, 9, 2.8, OFF)
    c.ellipse(7.5, 20.4, 16.5, 23.2)
    c.ellipse(9, 21, 15, 22.6, OFF)


@icon("armorhud")
def _(c):  # shield: solid, with a cut check mark
    c.poly([(12, 2.5), (20, 5.5), (20, 12), (12, 21.5), (4, 12), (4, 5.5)])
    c.line([(8.2, 11.6), (11, 14.4), (16, 8.6)], w=2.2, v=OFF)


@icon("potionhud")
def _(c):  # flask: solid, cut neck line + two bubbles
    c.rect(8.5, 2.5, 15.5, 5.5, 1)
    c.poly([(9.5, 5), (14.5, 5), (14.5, 9), (20, 19.5), (4, 19.5), (9.5, 9)])
    c.rect(4, 17.5, 20, 21.5, 2)
    c.line([(6.4, 14.5), (17.6, 14.5)], w=1.4, v=OFF)
    c.disc(10, 17.6, 1.1, OFF); c.disc(14.2, 16.6, 0.9, OFF)


@icon("serveraddress")
def _(c):  # globe: solid disc, cut meridian + latitudes
    c.disc(12, 12, 9.5)
    c.ring(12, 12, 9.5, 1.2, OFF)
    c.d.ellipse([P(8.2), P(2.5), P(15.8), P(21.5)], outline=OFF, width=int(round(P(1.4))))
    c.line([(2.5, 12), (21.5, 12)], w=1.4, v=OFF)
    c.line([(4.2, 7.2), (19.8, 7.2)], w=1.2, v=OFF)
    c.line([(4.2, 16.8), (19.8, 16.8)], w=1.2, v=OFF)


@icon("scoreboard")
def _(c):  # trophy: cup + handles + stem + base
    c.poly([(7, 3), (17, 3), (16.2, 11), (12, 14.5), (7.8, 11)])
    c.arc(5.3, 6, 2.6, 60, 300, w=1.8); c.arc(18.7, 6, 2.6, 240, 480, w=1.8)
    c.rect(10.8, 14, 13.2, 18, 0.8)
    c.rect(7, 18, 17, 21.5, 1.2)
    c.line([(9.6, 6), (10.2, 9.2)], w=1.3, v=OFF)


@icon("tablist")
def _(c):  # two players: near (solid) + far (behind, cut edge)
    c.disc(15.5, 7.5, 3.4)
    c.pie(15.5, 17.6, 6.2, 180, 360)
    c.disc(8.5, 8.5, 3.6, OFF); c.disc(8.5, 8.5, 3.6)
    c.pie(8.5, 19.2, 6.6, 180, 360, v=OFF); c.pie(8.5, 19.2, 6.6, 180, 360)
    c.ring(8.5, 8.5, 3.6, 1.2, OFF); c.arc(8.5, 19.2, 6.6, 180, 360, w=1.2, v=OFF)
    c.disc(8.5, 8.5, 3.6); c.pie(8.5, 19.2, 6.6, 180, 360)
    c.arc(15.5, 7.5, 3.4, 130, 250, w=1.4, v=OFF)


# ------------------------------------------------------------------ visual

@icon("zoom")
def _(c):  # magnifier: solid lens with cut plus, thick handle
    c.disc(10.5, 10.5, 7.5)
    c.line([(15.8, 15.8), (20.5, 20.5)], w=3.2)
    c.line([(7.5, 10.5), (13.5, 10.5)], w=2, v=OFF)
    c.line([(10.5, 7.5), (10.5, 13.5)], w=2, v=OFF)


@icon("freelook")
def _(c):  # eye: solid almond, cut iris ring, solid pupil
    c.poly([(2.5, 12), (6.5, 7), (12, 5), (17.5, 7), (21.5, 12), (17.5, 17), (12, 19), (6.5, 17)])
    c.disc(12, 12, 4.6, OFF)
    c.disc(12, 12, 3)
    c.disc(13.2, 10.8, 0.9, OFF)


@icon("fullbright")
def _(c):  # sun: solid disc + eight tapered rays
    c.disc(12, 12, 4.6)
    for i in range(8):
        a = math.radians(i * 45)
        c.poly([(12 + 7 * math.cos(a) - 1.1 * math.sin(a), 12 + 7 * math.sin(a) + 1.1 * math.cos(a)),
                (12 + 7 * math.cos(a) + 1.1 * math.sin(a), 12 + 7 * math.sin(a) - 1.1 * math.cos(a)),
                (12 + 10.2 * math.cos(a), 12 + 10.2 * math.sin(a))])


@icon("blockoverlay")
def _(c):  # isometric block: three faces separated by cut edges, top face lighter (cut hatch)
    c.poly([(12, 2.5), (20.5, 7.3), (12, 12), (3.5, 7.3)])
    c.poly([(3.5, 7.3), (12, 12), (12, 21.5), (3.5, 16.7)])
    c.poly([(20.5, 7.3), (12, 12), (12, 21.5), (20.5, 16.7)])
    c.line([(3.5, 7.3), (12, 12), (20.5, 7.3)], w=1.5, v=OFF)
    c.line([(12, 12), (12, 21.5)], w=1.5, v=OFF)


@icon("chunkborders")
def _(c):  # chunk: solid tile, cut 3x3 grid
    c.rect(2.5, 2.5, 21.5, 21.5, 2.5)
    for t in (8.8, 15.2):
        c.line([(t, 2.5), (t, 21.5)], w=1.6, v=OFF)
        c.line([(2.5, t), (21.5, t)], w=1.6, v=OFF)


@icon("hitboxes")
def _(c):  # bracket corners + solid figure
    for (x, y, dx, dy) in ((2.5, 2.5, 1, 1), (21.5, 2.5, -1, 1), (2.5, 21.5, 1, -1), (21.5, 21.5, -1, -1)):
        c.line([(x, y + 5 * dy), (x, y), (x + 5 * dx, y)], w=2.4)
    c.disc(12, 8.4, 2.6)
    c.rect(9.8, 11.4, 14.2, 17.2, 1.6)
    c.line([(8, 12.6), (16, 12.6)], w=2)


@icon("nametags")
def _(c):  # name tag above a solid head, cut name bar
    c.rect(3.5, 2.5, 20.5, 9.5, 2)
    c.line([(6.5, 6), (17.5, 6)], w=1.8, v=OFF)
    c.poly([(10.5, 9.5), (13.5, 9.5), (12, 12)])
    c.disc(12, 17, 4.5)


@icon("itemsize")
def _(c):  # scale: solid box + two arrowheads pushing outward
    c.rect(8.5, 8.5, 15.5, 15.5, 1.5)
    c.line([(15.5, 8.5), (20, 4)], w=2.2)
    c.poly([(21.5, 2.5), (21.5, 8), (16, 2.5)])
    c.line([(8.5, 15.5), (4, 20)], w=2.2)
    c.poly([(2.5, 21.5), (2.5, 16), (8, 21.5)])


@icon("weather")
def _(c):  # cloud: solid, three solid drops
    c.disc(8.5, 11.5, 4.5); c.disc(13, 9, 5); c.disc(17, 12, 3.8)
    c.rect(8.5, 11.5, 17, 15.8, 1)
    c.rect(4.5, 12.5, 20.5, 15.8, 1.8)
    for x in (8.5, 12, 15.5):
        c.poly([(x, 17.5), (x + 1.4, 20.2), (x - 1.4, 20.2)])
        c.disc(x, 20.2, 1.4)


@icon("timechanger")
def _(c):  # clock: solid dial, cut hands + 12/6 ticks
    c.disc(12, 12, 9.5)
    c.line([(12, 6.4), (12, 12.4), (16.2, 14.8)], w=2, v=OFF)
    c.disc(12, 12.4, 1.2, OFF)
    c.disc(12, 12.4, 0.6)


@icon("motionblur")
def _(c):  # solid dot + tapered streaks
    c.disc(16.5, 12, 4.2)
    for (y, x0, h) in ((8, 3, 1.0), (12, 5.5, 1.3), (16, 3, 1.0)):
        c.poly([(x0, y), (11.5, y - h), (11.5, y + h)])


@icon("colorsaturation")
def _(c):  # droplet: solid, cut highlight
    c.poly([(12, 2.5), (18.5, 11), (5.5, 11)])
    c.disc(12, 14.2, 6.6)
    c.ellipse(8.4, 12.6, 11, 17.2, OFF)
    c.ellipse(9.2, 13.6, 10.2, 15.4)


@icon("particles")
def _(c):  # four-point sparkles
    def spark(cx, cy, r, w):
        c.poly([(cx, cy - r), (cx + w, cy), (cx, cy + r), (cx - w, cy)])
    spark(9, 9.5, 7, 3.2)
    spark(18, 6, 3, 1.4)
    spark(17.5, 17, 4.2, 2)
    spark(6, 19, 2.4, 1.1)


# ------------------------------------------------------------------ gameplay

@icon("togglesprint")
def _(c):  # runner: solid figure, thick limbs
    c.disc(15.2, 4.6, 2.2)
    c.line([(13.6, 8), (11, 13)], w=3)
    c.line([(11, 13), (7, 16.4)], w=2.6)
    c.line([(11, 13), (15, 15.2), (16, 20.6)], w=2.6)
    c.line([(12.8, 9.6), (8, 9)], w=2.2)
    c.line([(12.8, 9.6), (17.2, 12)], w=2.2)


@icon("chat")
def _(c):  # speech bubble: solid, cut text lines
    c.rect(2.5, 3.5, 21.5, 16.5, 3)
    c.poly([(5, 15), (9.5, 15), (5, 20.5)])
    c.line([(7, 8), (17, 8)], w=1.7, v=OFF)
    c.line([(7, 12), (13.5, 12)], w=1.7, v=OFF)


@icon("waypoints")
def _(c):  # flag: solid pennant on a pole, cut stripe
    c.line([(5, 2.5), (5, 21.5)], w=2.4)
    c.poly([(6, 3.5), (20.5, 3.5), (16.5, 8.5), (20.5, 13.5), (6, 13.5)])
    c.line([(8.5, 8.5), (14, 8.5)], w=1.5, v=OFF)


@icon("jei")
def _(c):  # open book: two solid pages, cut spine + lines
    c.poly([(2.5, 4.5), (11, 6), (11, 20.5), (2.5, 19)])
    c.poly([(21.5, 4.5), (13, 6), (13, 20.5), (21.5, 19)])
    c.line([(5, 9), (8.6, 9.6)], w=1.3, v=OFF); c.line([(5, 12.5), (8.6, 13.1)], w=1.3, v=OFF)
    c.line([(15.4, 9.6), (19, 9)], w=1.3, v=OFF); c.line([(15.4, 13.1), (19, 12.5)], w=1.3, v=OFF)
    c.rect(11, 7, 13, 21.5, 0.8)


# ------------------------------------------------------------------ menu chrome

@icon("@general")
def _(c):  # sliders: three solid bars with cut knobs
    for y, kx in ((6, 9), (12, 15.5), (18, 7)):
        c.rect(2.5, y - 1.6, 21.5, y + 1.6, 1.6)
        c.disc(kx, y, 3)
        c.disc(kx, y, 1.5, OFF)


@icon("@performance")
def _(c):  # bolt: solid
    c.poly([(13.5, 2.5), (5.5, 13.5), (11, 13.5), (9.5, 21.5), (18.5, 10), (13, 10)])


@icon("@hudeditor")
def _(c):  # layout: solid tile, cut divisions
    c.rect(2.5, 2.5, 21.5, 21.5, 2.5)
    c.line([(9.5, 2.5), (9.5, 21.5)], w=1.8, v=OFF)
    c.line([(9.5, 12), (21.5, 12)], w=1.8, v=OFF)


@icon("@search")
def _(c):  # magnifier: solid lens with cut glass
    c.disc(10.5, 10.5, 7.5)
    c.disc(10.5, 10.5, 4.6, OFF)
    c.line([(15.8, 15.8), (20.5, 20.5)], w=3.2)


@icon("@backing")
def _(c):  # stacked cards: back cut-outlined, front solid
    c.rect(7.5, 2.5, 21.5, 16.5, 2)
    c.rect(9.5, 4.5, 19.5, 14.5, 1.2, OFF)
    c.rect(2.5, 7.5, 16.5, 21.5, 2)


def build():
    names = sorted(ICONS)
    cols = 6
    rows = (len(names) + cols - 1) // cols
    atlas = Image.new("RGBA", (cols * CELL, rows * CELL), (0, 0, 0, 0))
    meta = {"cell": CELL, "cols": cols, "grid": GRID, "style": "solid+cutout", "icons": {}}
    for i, name in enumerate(names):
        c = Canvas()
        ICONS[name](c)
        a = c.img.resize((CELL, CELL), Image.LANCZOS)
        white = Image.new("L", (CELL, CELL), 255)
        col, row = i % cols, i // cols
        atlas.alpha_composite(Image.merge("RGBA", (white, white, white, a)), (col * CELL, row * CELL))
        meta["icons"][name] = {"x": col * CELL, "y": row * CELL}
    OUT.mkdir(parents=True, exist_ok=True)
    atlas.save(OUT / "mod_icons.png")
    (OUT / "mod_icons.json").write_text(json.dumps(meta, indent=2))
    prev = Image.new("RGB", atlas.size, (0x12, 0x15, 0x1C))
    prev.paste(Image.new("RGB", atlas.size, (0xEE, 0xF1, 0xF6)), mask=atlas.split()[3])
    prev.save(HERE / "icons_preview.png")
    print(f"{len(names)} icons -> {OUT / 'mod_icons.png'} ({atlas.size[0]}x{atlas.size[1]})")


if __name__ == "__main__":
    build()
