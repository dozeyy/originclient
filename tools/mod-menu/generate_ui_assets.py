#!/usr/bin/env python3
"""Bake the premium in-game UI assets for the mod menu / HUD editor:

- switch_track.png / switch_knob.png — Apple-style toggle. Track is a white
  alpha mask (tinted per state in-game); the knob ships full-color: white
  disc with a baked soft drop shadow + subtle top highlight, drawn untinted.
- mod_icons.png + mod_icons.json — 96x96 stroke icons for every mod, one
  consistent geometric language (round caps, uniform stroke), supersampled
  4x for crisp AA at any scale. Drawn at real screen pixels in-game.

Same methodology as the loading screen/buttons: pre-render smooth assets,
eyeball the preview sheet in-sandbox before they touch Minecraft.

Usage: python3 generate_ui_assets.py
Output: ../../src/OriginClient.Mod/.../assets/originclient/textures/ui/
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
OUT = (HERE / ".." / ".." / "src" / "OriginClient.Mod" / "src" / "client" /
       "resources" / "assets" / "originclient" / "textures" / "ui").resolve()

SS = 4  # supersample


# ---------- switch ----------

def bake_switch():
    # Track: 120x64 pill mask (radius = half height) — pure white alpha mask.
    w, h = 120, 64
    big = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], radius=h * SS // 2, fill=255)
    a = big.resize((w, h), Image.LANCZOS)
    white = Image.new("L", (w, h), 255)
    Image.merge("RGBA", (white, white, white, a)).save(OUT / "switch_track.png")

    # Knob: 72x72 canvas, 52px disc centered, baked soft shadow below-right
    # + subtle top highlight. Full-color, drawn untinted.
    size, disc = 72, 52
    big = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    cx = cy = size * SS // 2
    r = disc * SS // 2
    # shadow: blurred dark disc offset down
    sh = Image.new("RGBA", big.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([cx - r, cy - r + 3 * SS, cx + r, cy + r + 3 * SS], fill=(0, 0, 0, 110))
    sh = sh.filter(ImageFilter.GaussianBlur(4 * SS))
    big.alpha_composite(sh)
    # disc
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(245, 245, 245, 255))
    # top highlight: lighter arc region
    hl = Image.new("RGBA", big.size, (0, 0, 0, 0))
    ImageDraw.Draw(hl).ellipse([cx - r + 4 * SS, cy - r + 3 * SS, cx + r - 4 * SS, cy], fill=(255, 255, 255, 90))
    hl = hl.filter(ImageFilter.GaussianBlur(3 * SS))
    big.alpha_composite(hl)
    big.resize((size, size), Image.LANCZOS).save(OUT / "switch_knob.png")
    print("switch: track 120x64, knob 72x72")


# ---------- icons ----------

CELL = 96
STROKE = 7


def icon_canvas():
    img = Image.new("L", (CELL * SS, CELL * SS), 0)
    return img, ImageDraw.Draw(img)


def P(v):
    return v * CELL * SS / 96.0


def line(d, pts, w=STROKE):
    d.line([(P(x), P(y)) for x, y in pts], fill=255, width=int(P(w)), joint="curve")
    rr = P(w) / 2
    for x, y in pts:
        d.ellipse([P(x) - rr, P(y) - rr, P(x) + rr, P(y) + rr], fill=255)


def circle(d, cx, cy, r, w=STROKE, fill=False):
    if fill:
        d.ellipse([P(cx - r), P(cy - r), P(cx + r), P(cy + r)], fill=255)
    else:
        d.ellipse([P(cx - r), P(cy - r), P(cx + r), P(cy + r)], outline=255, width=int(P(w)))


def rrect(d, x0, y0, x1, y1, rad, w=STROKE, fill=False):
    if fill:
        d.rounded_rectangle([P(x0), P(y0), P(x1), P(y1)], radius=P(rad), fill=255)
    else:
        d.rounded_rectangle([P(x0), P(y0), P(x1), P(y1)], radius=P(rad), outline=255, width=int(P(w)))


def arc(d, x0, y0, x1, y1, a0, a1, w=STROKE):
    d.arc([P(x0), P(y0), P(x1), P(y1)], a0, a1, fill=255, width=int(P(w)))


ICONS = {}


def icon(name):
    def reg(fn):
        ICONS[name] = fn
        return fn
    return reg


@icon("fps")
def _(d):  # speedometer
    arc(d, 16, 20, 80, 84, 150, 390)
    line(d, [(48, 52), (66, 34)])
    circle(d, 48, 52, 5, fill=True)


@icon("cps")
def _(d):  # mouse, split buttons
    rrect(d, 30, 14, 66, 82, 18)
    line(d, [(48, 14), (48, 44)])
    line(d, [(30, 44), (66, 44)])


@icon("coords")
def _(d):  # location pin
    arc(d, 28, 14, 68, 54, 120, 420)
    line(d, [(31, 46), (48, 82)])
    line(d, [(65, 46), (48, 82)])
    circle(d, 48, 34, 7, fill=True)


@icon("keystrokes")
def _(d):  # key cluster
    rrect(d, 36, 12, 60, 36, 6)
    rrect(d, 10, 40, 34, 64, 6)
    rrect(d, 36, 40, 60, 64, 6)
    rrect(d, 62, 40, 86, 64, 6)
    rrect(d, 24, 70, 72, 84, 6)


@icon("potionhud")
def _(d):  # clean Erlenmeyer flask + liquid line
    line(d, [(40, 12), (56, 12)])
    line(d, [(43, 12), (43, 30)])
    line(d, [(53, 12), (53, 30)])
    line(d, [(43, 30), (26, 70), (70, 70), (53, 30)])
    line(d, [(33, 56), (63, 56)], w=5)


@icon("armorhud")
def _(d):  # shield with center seam
    line(d, [(48, 12), (78, 22), (78, 48), (48, 84), (18, 48), (18, 22), (48, 12)])
    line(d, [(48, 22), (48, 74)], w=5)


@icon("serveraddress")
def _(d):  # globe
    circle(d, 48, 48, 34)
    d.ellipse([P(34), P(14), P(62), P(82)], outline=255, width=int(P(STROKE)))
    line(d, [(14, 48), (82, 48)])


# (packdisplay icon removed — mod no longer exists)


@icon("zoom")
def _(d):  # magnifier
    circle(d, 42, 42, 26)
    line(d, [(61, 61), (82, 82)], w=9)


@icon("freelook")
def _(d):  # eye
    arc(d, 10, 22, 86, 74, 15, 165)
    arc(d, 10, 22, 86, 74, 195, 345)
    circle(d, 48, 48, 12, fill=True)


@icon("togglesprint")
def _(d):  # speed chevrons
    line(d, [(24, 20), (52, 48), (24, 76)])
    line(d, [(50, 20), (78, 48), (50, 76)])


# (togglesneak icon removed — merged into Toggle Sneak/Sprint = togglesprint)


@icon("fullbright")
def _(d):  # sun
    circle(d, 48, 48, 16)
    for i in range(8):
        a = math.radians(i * 45)
        line(d, [(48 + 26 * math.cos(a), 48 + 26 * math.sin(a)),
                 (48 + 36 * math.cos(a), 48 + 36 * math.sin(a))])


@icon("blockoverlay")
def _(d):  # highlighted cube
    rrect(d, 18, 18, 78, 78, 10)
    rrect(d, 34, 34, 62, 62, 4, fill=True)


@icon("chunkborders")
def _(d):  # grid
    for t in (18, 48, 78):
        line(d, [(t, 14), (t, 82)], w=5)
        line(d, [(14, t), (82, t)], w=5)


@icon("hitboxes")
def _(d):  # box around figure
    rrect(d, 14, 10, 82, 86, 8, w=5)
    circle(d, 48, 36, 10)
    line(d, [(48, 46), (48, 68)])


@icon("nametags")
def _(d):  # tag above head
    rrect(d, 20, 14, 76, 40, 8)
    line(d, [(48, 40), (48, 52)])
    circle(d, 48, 66, 12)


@icon("weather")
def _(d):  # cloud + rain (weather changer, no slash)
    arc(d, 18, 34, 46, 60, 90, 270)
    arc(d, 28, 20, 68, 56, 180, 360)
    arc(d, 58, 34, 82, 60, 270, 90)
    line(d, [(32, 60), (70, 60)])
    line(d, [(34, 70), (28, 82)])
    line(d, [(50, 70), (44, 82)])
    line(d, [(66, 70), (60, 82)])


# (customsky icon removed — mod no longer exists)


@icon("timechanger")
def _(d):  # clock
    circle(d, 48, 48, 34)
    line(d, [(48, 30), (48, 50), (64, 58)])


@icon("motionblur")
def _(d):  # streaking dot
    circle(d, 64, 48, 14, fill=True)
    line(d, [(14, 36), (42, 36)], w=6)
    line(d, [(20, 48), (44, 48)], w=6)
    line(d, [(14, 60), (42, 60)], w=6)


@icon("particles")
def _(d):  # sparkle scatter
    for cx, cy, r in ((30, 30, 9), (66, 24, 5), (26, 66, 5), (62, 62, 10)):
        circle(d, cx, cy, r, fill=True)
    circle(d, 48, 46, 4, fill=True)


@icon("chat")
def _(d):  # speech bubble
    rrect(d, 14, 16, 82, 64, 14)
    line(d, [(30, 64), (30, 82), (46, 64)])
    line(d, [(30, 34), (66, 34)], w=5)
    line(d, [(30, 46), (56, 46)], w=5)


@icon("scoreboard")
def _(d):  # ranked list
    rrect(d, 16, 12, 80, 84, 10)
    line(d, [(28, 30), (56, 30)], w=5)
    line(d, [(28, 48), (66, 48)], w=5)
    line(d, [(28, 66), (50, 66)], w=5)


def bake_icons():
    names = sorted(ICONS.keys())
    cols = 6
    rows = (len(names) + cols - 1) // cols
    atlas = Image.new("RGBA", (cols * CELL, rows * CELL), (0, 0, 0, 0))
    meta = {"cell": CELL, "cols": cols, "icons": {}}
    for i, name in enumerate(names):
        img, d = icon_canvas()
        ICONS[name](d)
        cellimg = img.resize((CELL, CELL), Image.LANCZOS)
        white = Image.new("L", (CELL, CELL), 255)
        rgba = Image.merge("RGBA", (white, white, white, cellimg))
        col, row = i % cols, i // cols
        atlas.alpha_composite(rgba, (col * CELL, row * CELL))
        meta["icons"][name] = {"x": col * CELL, "y": row * CELL}
    atlas.save(OUT / "mod_icons.png")
    (OUT / "mod_icons.json").write_text(json.dumps(meta, indent=2))
    print(f"icons: {len(names)} at {CELL}px -> mod_icons.png ({atlas.size[0]}x{atlas.size[1]})")

    # preview sheet on dark for eyeballing (best-effort; path may not exist)
    try:
        prev = Image.new("RGB", atlas.size, (10, 10, 10))
        prev.paste(Image.new("RGB", atlas.size, (230, 230, 230)), mask=atlas.split()[3])
        prev.save(HERE / "icons_preview.png")
    except Exception:
        pass


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bake_switch()
    bake_icons()


if __name__ == "__main__":
    main()
