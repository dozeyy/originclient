#!/usr/bin/env python3
"""Origin mod-menu icon set — built to the Design Assets / Icon Packs rules:

  24 x 24 grid, 2 px padding (live area 20 x 20), 2 px stroke, ROUND caps and
  joins, 2 px corner radius on rects, keylines (circle d=20, square 18).
  One concept = one glyph; no text, no 3D, no gradients. Icons are DATA drawn
  by shared primitives so the family stays one weight.

Each icon is rasterised at CELL=96 px (24 grid x 4) with 4x supersampling and
saved as a WHITE alpha mask in one atlas — in-game the mask is tinted with the
theme colour (OriginTheme.TEXT on cards, ACCENT when active), so the icons
follow the palette by construction. Icons that share a metaphor with the
Origin chrome (search, general/performance settings, HUD editor, backing) live
in the same atlas under their '@' ids.

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

GRID = 24        # the Icon Packs grid
CELL = 96        # output cell (grid x 4)
SS = 4           # supersample
STROKE = 2.0     # grid units
K = CELL * SS / GRID


def P(v):
    return v * K


class Canvas:
    def __init__(self):
        self.img = Image.new("L", (CELL * SS, CELL * SS), 0)
        self.d = ImageDraw.Draw(self.img)

    # -- primitives (all in 24-grid units, round caps everywhere) --
    def line(self, pts, w=STROKE):
        pts = [(P(x), P(y)) for x, y in pts]
        self.d.line(pts, fill=255, width=int(round(P(w))), joint="curve")
        r = P(w) / 2
        for x, y in pts:
            self.d.ellipse([x - r, y - r, x + r, y + r], fill=255)

    def circle(self, cx, cy, r, w=STROKE, fill=False):
        box = [P(cx - r), P(cy - r), P(cx + r), P(cy + r)]
        if fill:
            self.d.ellipse(box, fill=255)
        else:
            self.d.ellipse(box, outline=255, width=int(round(P(w))))

    def rect(self, x0, y0, x1, y1, rad=2, w=STROKE, fill=False):
        box = [P(x0), P(y0), P(x1), P(y1)]
        if fill:
            self.d.rounded_rectangle(box, radius=P(rad), fill=255)
        else:
            self.d.rounded_rectangle(box, radius=P(rad), outline=255, width=int(round(P(w))))

    def arc(self, cx, cy, r, a0, a1, w=STROKE):
        # PIL arcs are pixel-aligned; draw with round caps by adding end dots.
        self.d.arc([P(cx - r), P(cy - r), P(cx + r), P(cy + r)], a0, a1, fill=255, width=int(round(P(w))))
        for a in (a0, a1):
            x, y = cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))
            rr = P(w) / 2
            self.d.ellipse([P(x) - rr, P(y) - rr, P(x) + rr, P(y) + rr], fill=255)

    def dot(self, cx, cy, r=1.5):
        self.circle(cx, cy, r, fill=True)

    def arrow(self, x0, y0, x1, y1, head=3.2):
        # a line with an open arrowhead at (x1, y1)
        self.line([(x0, y0), (x1, y1)])
        ang = math.atan2(y1 - y0, x1 - x0)
        for s in (1, -1):
            a = ang + s * math.radians(150)
            self.line([(x1, y1), (x1 + head * math.cos(a), y1 + head * math.sin(a))])


ICONS = {}


def icon(name):
    def reg(fn):
        ICONS[name] = fn
        return fn
    return reg


# ------------------------------------------------------------------ HUD readouts

@icon("fps")
def _(c):  # gauge: 3/4 arc + needle from the hub
    c.arc(12, 13, 9, 135, 405)
    c.line([(12, 13), (17, 8)])
    c.dot(12, 13, 1.6)


@icon("cps")
def _(c):  # mouse with the button split inside the shell
    c.rect(7, 3, 17, 21, 5)
    c.line([(12, 6), (12, 11)])


@icon("keystrokes")
def _(c):  # W above A S D
    c.rect(9, 3, 15, 9, 2)
    c.rect(2, 11, 8, 17, 2)
    c.rect(9, 11, 15, 17, 2)
    c.rect(16, 11, 22, 17, 2)
    c.line([(6, 20.5), (18, 20.5)])


@icon("coords")
def _(c):  # map pin: head arc + tapered stem + hole
    c.arc(12, 9, 6, 145, 395)
    c.line([(6.9, 12.2), (12, 21)])
    c.line([(17.1, 12.2), (12, 21)])
    c.circle(12, 9, 2)


@icon("armorhud")
def _(c):  # shield
    c.line([(12, 3), (19, 6), (19, 12), (12, 21), (5, 12), (5, 6), (12, 3)])


@icon("potionhud")
def _(c):  # flask with liquid line
    c.line([(9, 3), (15, 3)])
    c.line([(10, 3), (10, 9), (5, 19), (19, 19), (14, 9), (14, 3)])
    c.line([(8, 15), (16, 15)])


@icon("serveraddress")
def _(c):  # globe
    c.circle(12, 12, 9)
    c.d.ellipse([P(8), P(3), P(16), P(21)], outline=255, width=int(round(P(STROKE))))
    c.line([(3, 12), (21, 12)])


@icon("scoreboard")
def _(c):  # ranked list with a leading marker
    c.rect(3, 3, 21, 21, 2)
    for y in (8, 12, 16):
        c.dot(7.5, y, 1.1)
        c.line([(10.5, y), (17 if y != 12 else 14.5, y)])


@icon("tablist")
def _(c):  # player list: head+shoulders + two lines
    c.circle(7, 8, 3)
    c.arc(7, 17, 5, 200, 340)
    c.line([(14, 8), (20, 8)])
    c.line([(14, 14), (20, 14)])


# ------------------------------------------------------------------ visual

@icon("zoom")
def _(c):  # magnifier with a plus
    c.circle(10.5, 10.5, 6.5)
    c.line([(15.3, 15.3), (20.5, 20.5)], w=2.6)
    c.line([(8, 10.5), (13, 10.5)])
    c.line([(10.5, 8), (10.5, 13)])


@icon("freelook")
def _(c):  # eye
    c.line([(3, 12), (6, 8.2), (12, 6), (18, 8.2), (21, 12), (18, 15.8), (12, 18), (6, 15.8), (3, 12)])
    c.circle(12, 12, 2.6, fill=True)


@icon("fullbright")
def _(c):  # sun
    c.circle(12, 12, 4)
    for i in range(8):
        a = math.radians(i * 45)
        c.line([(12 + 7 * math.cos(a), 12 + 7 * math.sin(a)), (12 + 9.5 * math.cos(a), 12 + 9.5 * math.sin(a))])


@icon("blockoverlay")
def _(c):  # outlined isometric block
    c.line([(12, 3), (20, 7.5), (12, 12), (4, 7.5), (12, 3)])
    c.line([(4, 7.5), (4, 16.5), (12, 21), (12, 12)])
    c.line([(20, 7.5), (20, 16.5), (12, 21)])


@icon("chunkborders")
def _(c):  # 3x3 grid
    c.rect(3, 3, 21, 21, 2)
    c.line([(9, 3), (9, 21)])
    c.line([(15, 3), (15, 21)])
    c.line([(3, 9), (21, 9)])
    c.line([(3, 15), (21, 15)])


@icon("hitboxes")
def _(c):  # dashed-corner box around a figure
    for (x, y, dx, dy) in ((3, 3, 1, 1), (21, 3, -1, 1), (3, 21, 1, -1), (21, 21, -1, -1)):
        c.line([(x, y + 4 * dy), (x, y), (x + 4 * dx, y)])
    c.circle(12, 9, 2.4)
    c.line([(12, 11.5), (12, 16)])
    c.line([(9, 13.5), (15, 13.5)])


@icon("nametags")
def _(c):  # tag above a head
    c.rect(4, 3, 20, 10, 2)
    c.line([(12, 10), (12, 13)])
    c.circle(12, 17.5, 3.5)


@icon("itemsize")
def _(c):  # scale: small box + two diagonal expand arrows
    c.rect(9, 9, 15, 15, 1.5)
    c.arrow(16.5, 7.5, 20.5, 3.5)
    c.arrow(7.5, 16.5, 3.5, 20.5)


@icon("weather")
def _(c):  # cloud + rain
    c.arc(9, 12, 4.5, 90, 270)
    c.arc(13, 9.5, 4.5, 200, 360)
    c.arc(16.5, 12, 3.5, 270, 90)
    c.line([(9, 16.5), (16.5, 16.5)])
    c.line([(9, 8), (16, 8)])
    for x in (8.5, 12, 15.5):
        c.line([(x, 19), (x - 0.8, 21.5)])


@icon("timechanger")
def _(c):  # clock
    c.circle(12, 12, 9)
    c.line([(12, 7), (12, 12.5), (15.5, 14.5)])


@icon("motionblur")
def _(c):  # dot with speed lines
    c.circle(16, 12, 3.5, fill=True)
    c.line([(3, 8), (10, 8)])
    c.line([(5, 12), (10, 12)])
    c.line([(3, 16), (10, 16)])


@icon("colorsaturation")
def _(c):  # droplet
    c.line([(12, 3), (17.5, 10), (18, 14)])
    c.line([(12, 3), (6.5, 10), (6, 14)])
    c.arc(12, 14, 6, 0, 180)


@icon("particles")
def _(c):  # sparkle cluster
    c.line([(9, 4), (9, 14)])
    c.line([(4, 9), (14, 9)])
    c.line([(17, 13), (17, 21)])
    c.line([(13, 17), (21, 17)])
    c.dot(18, 5, 1.2)


# ------------------------------------------------------------------ gameplay

@icon("togglesprint")
def _(c):  # runner
    c.circle(15, 4.5, 1.9, fill=True)
    c.line([(13.5, 7.5), (11, 12.5)])
    c.line([(11, 12.5), (7.5, 15.5)])
    c.line([(11, 12.5), (14.5, 14.5), (15.5, 19.5)])
    c.line([(12.5, 9.5), (8.5, 9)])
    c.line([(12.5, 9.5), (16.5, 11.5)])


@icon("chat")
def _(c):  # speech bubble
    c.line([(4, 5), (20, 5), (20, 15), (10, 15), (6, 19), (6, 15), (4, 15), (4, 5)])
    c.line([(8, 9), (16, 9)])
    c.line([(8, 12), (13, 12)])


@icon("waypoints")
def _(c):  # flag on a pole
    c.line([(6, 3), (6, 21)])
    c.line([(6, 4), (18, 4), (15, 8.5), (18, 13), (6, 13)])


@icon("jei")
def _(c):  # open recipe book
    c.line([(3, 5), (10, 5), (12, 7), (12, 20)])
    c.line([(21, 5), (14, 5), (12, 7)])
    c.line([(3, 5), (3, 19), (12, 20), (21, 19), (21, 5)])
    c.line([(6, 9), (9, 9)])
    c.line([(15, 9), (18, 9)])


# ------------------------------------------------------------------ menu chrome

@icon("@general")
def _(c):  # sliders
    for y, kx in ((6, 9), (12, 15), (18, 7)):
        c.line([(3, y), (21, y)])
        c.circle(kx, y, 2.2, fill=True)


@icon("@performance")
def _(c):  # bolt
    c.line([(13, 3), (6, 13), (11.5, 13), (10, 21), (18, 10), (12.5, 10), (13, 3)])


@icon("@hudeditor")
def _(c):  # layout: sidebar + two panels
    c.rect(3, 3, 21, 21, 2)
    c.line([(9, 3), (9, 21)])
    c.line([(9, 12), (21, 12)])


@icon("@search")
def _(c):  # magnifier
    c.circle(10.5, 10.5, 6.5)
    c.line([(15.3, 15.3), (20.5, 20.5)], w=2.6)


@icon("@backing")
def _(c):  # stacked squares (translucency)
    c.rect(3, 8, 16, 21, 2)
    c.line([(8, 8), (8, 5), (21, 5), (21, 16), (16, 16)])


def build():
    names = sorted(ICONS)
    cols = 6
    rows = (len(names) + cols - 1) // cols
    atlas = Image.new("RGBA", (cols * CELL, rows * CELL), (0, 0, 0, 0))
    meta = {"cell": CELL, "cols": cols, "grid": GRID, "stroke": STROKE, "icons": {}}
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
    # contact sheet on the Slate panel colour, tinted text colour
    prev = Image.new("RGB", atlas.size, (0x12, 0x15, 0x1C))
    prev.paste(Image.new("RGB", atlas.size, (0xEE, 0xF1, 0xF6)), mask=atlas.split()[3])
    prev.save(HERE / "icons_preview.png")
    print(f"{len(names)} icons -> {OUT / 'mod_icons.png'} ({atlas.size[0]}x{atlas.size[1]})")
    print("ids:", " ".join(names))


if __name__ == "__main__":
    build()
