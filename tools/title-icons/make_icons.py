#!/usr/bin/env python3
"""Origin's title-screen dock icons (1.21.1), built from PREMADE open-licence pixel icons.

Will's picks (2026-10-27, "c1 r1 q2"): Cosmetics = HackerNoon crown, Realms = Teenyicons outline
server rack, Quit = Kenney X. Friends, Language and Accessibility are vanilla sprites the mod
draws at runtime by ResourceLocation (toast/social_interactions, icon/language,
icon/accessibility), so no Mojang pixel is ever copied into this sheet. Big buttons are words
only (no lead icons).

  make_icons.py <out.png> --packs <dir> [--preview]

  <dir> holds the two pack downloads exactly as fetched (read straight from the zips, nothing
  extracted or run):
    <dir>/kenney-1bit/kenney_1-bit-pack.zip        https://kenney.nl/assets/1-bit-pack (CC0)
    <dir>/hackernoon/pixel-icon-library-main.zip   https://github.com/hackernoon/pixel-icon-library
                                                   (icons CC BY 4.0, credit in CREDITS.txt)
  Teenyicons needs no download: teenyicons-servers-outline-15px.png next to this script is
  outline/servers.svg (teenyicons 0.4.1, MIT) rendered 1:1 on its own 15 px grid, then
  thresholded at 50 % luminance, so no browser is needed to rebuild.

  --preview  also writes <out>-gui3.png (the title menu mocked at GUI scale 3 with the real
             vanilla font and sprites read from the local 1.21.1 jar, read-only) and
             <out>-dock-zoom.png (the dock at 12x with each button's centre lines, to check centring).

Outputs <out>.png (the 48 x 16 sheet) and <out>.json (the rects below).

Rules (Will): icons are used AS DRAWN. The only changes are (1) recolour: every shape pixel becomes
vanilla white 252, everything else clear, (2) vanilla's 1 px drop shadow, dark 42 at (+1,+1) on
empty pixels, like gui/sprites/icon/accessibility.png, (3) a 1:1 crop to the shape.
No redrawing, no scaling.

Sheet contract: 48 x 16, one 16 x 16 cell per icon in SLOTS order (cosmetics x=0, realms x=16,
quit x=32). The white body starts at the cell's top-left; the shadow adds 1 px right and below.
<out>.json gives per icon "body" {u,v,w,h} (white shape only) and "blit" {u,v,w,h} (body + shadow).
CENTRING (Will: "make sure the icons are centered"): the mod centres the BODY in the 20 x 20 dock
button the way vanilla's SpriteIconButton does, int maths, the odd pixel going left/top:
    bodyX = x + 20 / 2 - body.w / 2      bodyY = y + 20 / 2 - body.h / 2
then blits the "blit" rect at (bodyX, bodyY). Language (13 px body) and Accessibility (14 px body)
already land exactly there under vanilla's own placement; social_interactions is a 20 x 20 frame
whose bubble+tail (12 x 14) is centred inside it, so it is blitted whole at (x, y).
"""
import io
import json
import sys
import zipfile
from pathlib import Path

from PIL import Image

WHITE = (252, 252, 252, 255)
SHADOW = (42, 42, 42, 255)
CELL = 16
BUTTON = 20

KENNEY_ZIP = "kenney-1bit/kenney_1-bit-pack.zip"
KENNEY_SHEET = "Tilesheet/colored-transparent_packed.png"   # 49 x 22 tiles of 16 px, no spacing
HN_ZIP = "hackernoon/pixel-icon-library-main.zip"
HN_DIR = "pixel-icon-library-main/icons/PNG/for-dark-mode/{size}px/solid/{name}-solid.png"
TEENY_PNG = Path(__file__).with_name("teenyicons-servers-outline-15px.png")

# Sheet order = cell order. Sources are the only three in use.
SLOTS = [
    ("cosmetics", ("hackernoon", 16, "crown")),      # 14 x 12 shape
    ("realms", ("teenyicons", TEENY_PNG)),           # 15 x 13 shape
    ("quit", ("kenney", 13, 40)),                    # tile row 13, col 40: 12 x 12 X
]


def _zip_png(zpath, member):
    with zipfile.ZipFile(zpath) as z:
        return Image.open(io.BytesIO(z.read(member))).convert("RGBA")


def load_icon(packs, src):
    """The pack icon as drawn, as a 1-bit mask cropped 1:1 to its shape."""
    if src[0] == "kenney":
        sheet = _zip_png(f"{packs}/{KENNEY_ZIP}", KENNEY_SHEET)
        r, c = src[1], src[2]
        mask = sheet.crop((c * 16, r * 16, c * 16 + 16, r * 16 + 16)).split()[3]
    elif src[0] == "hackernoon":
        # Soft-alpha downscales: snap at 50 %.
        mask = _zip_png(f"{packs}/{HN_ZIP}", HN_DIR.format(size=src[1], name=src[2])).split()[3]
    else:
        mask = Image.open(src[1]).convert("L")
    mask = mask.point(lambda a: 255 if a >= 128 else 0)
    return mask.crop(mask.getbbox())


def stamp(dst, ox, oy, mask):
    """Recolour to white + vanilla's (+1,+1) drop shadow; the shape always wins."""
    w, h = mask.size
    for y in range(h):
        for x in range(w):
            if mask.getpixel((x, y)):
                sx, sy = x + 1, y + 1
                if not (sx < w and sy < h and mask.getpixel((sx, sy))):
                    dst.putpixel((ox + sx, oy + sy), SHADOW)
    for y in range(h):
        for x in range(w):
            if mask.getpixel((x, y)):
                dst.putpixel((ox + x, oy + y), WHITE)


def build(packs):
    img = Image.new("RGBA", (CELL * len(SLOTS), CELL), (0, 0, 0, 0))
    info = {}
    for i, (name, src) in enumerate(SLOTS):
        m = load_icon(packs, src)
        assert m.width + 1 <= CELL and m.height + 1 <= CELL, (name, m.size)
        u = i * CELL
        stamp(img, u, 0, m)
        info[name] = {"body": {"u": u, "v": 0, "w": m.width, "h": m.height},
                      "blit": {"u": u, "v": 0, "w": m.width + 1, "h": m.height + 1}}
    return img, info


def body_origin(bx, by, w, h):
    """Vanilla SpriteIconButton placement: int halves, odd pixel left/top."""
    return bx + BUTTON // 2 - w // 2, by + BUTTON // 2 - h // 2


# ---- preview (read-only use of the local Minecraft jar) -------------------------------------
FACE, EDGE = (48, 48, 51, 255), (11, 11, 12, 255)
DOCK = ["friends", "cosmetics", "realms", "language", "accessibility", "quit"]


def _jar():
    import glob
    import os
    pat = os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/"
                             "minecraft-clientonly/1.21.1*/*.jar")
    hits = [p for p in glob.glob(pat) if not p.endswith("-sources.jar")]
    return zipfile.ZipFile(hits[0])


def _vanilla(z, sprite):
    return Image.open(io.BytesIO(z.read(f"assets/minecraft/textures/gui/sprites/{sprite}.png"))).convert("RGBA")


class Font:
    """Minecraft's ascii.png metrics: 8x8 cells, advance = glyph width + 1, shadow at (+1,+1)."""

    def __init__(self, z):
        self.img = Image.open(io.BytesIO(z.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA")

    def _glyph(self, c):
        o = ord(c)
        return self.img.crop((o % 16 * 8, o // 16 * 8, o % 16 * 8 + 8, o // 16 * 8 + 8))

    def _adv(self, c):
        if c == " ":
            return 4
        g = self._glyph(c)
        xs = [x for x in range(8) for y in range(8) if g.getpixel((x, y))[3]]
        return (max(xs) + 2) if xs else 4

    def width(self, s):
        return sum(self._adv(c) for c in s) - 1

    def draw(self, dst, x, y, s, rgb=(255, 255, 255)):
        sh = tuple(v // 4 for v in rgb)
        for col, off in ((sh, 1), (rgb, 0)):
            cx = x
            for c in s:
                if c != " ":
                    g = self._glyph(c)
                    for gy in range(8):
                        for gx in range(8):
                            if g.getpixel((gx, gy))[3]:
                                dst.putpixel((cx + gx + off, y + gy + off), col + (255,))
                cx += self._adv(c)


def _dock_icon(z, sheet, info, name, bx, by):
    """(image, x, y) exactly as the mod draws it."""
    if name == "friends":
        return _vanilla(z, "toast/social_interactions"), bx, by
    if name in ("language", "accessibility"):
        im = _vanilla(z, "icon/" + name)       # vanilla's own placement on the whole 15 x 15 sprite
        return im, bx + BUTTON // 2 - im.width // 2, by + BUTTON // 2 - im.height // 2
    r = info[name]
    b = r["blit"]
    x, y = body_origin(bx, by, r["body"]["w"], r["body"]["h"])
    return sheet.crop((b["u"], b["v"], b["u"] + b["w"], b["v"] + b["h"])), x, y


def mock(sheet, info):
    from PIL import ImageDraw
    z = _jar()
    font = Font(z)
    W, H = 240, 136
    bg = Image.new("RGBA", (W, H), (58, 66, 72, 255))
    d = ImageDraw.Draw(bg)
    for i, label in enumerate(["Singleplayer", "Multiplayer", "Options...", "Mods"]):
        bx, by = 20, 8 + i * 24
        d.rectangle([bx, by, bx + 199, by + 19], fill=FACE, outline=EDGE)
        font.draw(bg, bx + (200 - font.width(label)) // 2, by + 6, label)
    x0 = (W - (len(DOCK) * 24 - 4)) // 2
    boxes = []
    for i, name in enumerate(DOCK):
        bx, by = x0 + i * 24, 108
        d.rectangle([bx, by, bx + BUTTON - 1, by + BUTTON - 1], fill=FACE, outline=EDGE)
        im, x, y = _dock_icon(z, sheet, info, name, bx, by)
        bg.alpha_composite(im, (x, y))
        boxes.append((bx, by))
    return bg, boxes


def margins(img, bx, by):
    """Left/right/top/bottom gap between the button edge and the WHITE body (shadow ignored)."""
    pts = [(x, y) for y in range(by, by + BUTTON) for x in range(bx, bx + BUTTON)
           if min(img.getpixel((x, y))[:3]) > 150]
    xs = [p[0] - bx for p in pts]
    ys = [p[1] - by for p in pts]
    return min(xs), BUTTON - 1 - max(xs), min(ys), BUTTON - 1 - max(ys)


if __name__ == "__main__":
    out = sys.argv[1]
    packs = sys.argv[sys.argv.index("--packs") + 1]
    sheet, info = build(packs)
    sheet.save(out)
    with open(out[:-4] + ".json", "w") as f:
        json.dump(info, f, indent=1)
    print(json.dumps(info))
    if "--preview" in sys.argv:
        m, boxes = mock(sheet, info)
        m.resize((m.width * 3, m.height * 3), Image.NEAREST).save(out[:-4] + "-gui3.png")
        # Dock crop at 12x with the button centre lines (the 1 px edge of each 20 px button is
        # drawn too, so a body gap counts the edge pixel; equal numbers = centred).
        x0, y0 = boxes[0]
        dock = m.crop((x0 - 2, y0 - 2, boxes[-1][0] + BUTTON + 2, y0 + BUTTON + 2))
        s = 12
        zoom = dock.resize((dock.width * s, dock.height * s), Image.NEAREST)
        from PIL import ImageDraw
        zd = ImageDraw.Draw(zoom)
        for name, (bx, by) in zip(DOCK, boxes):
            cx = (bx - x0 + 2 + BUTTON / 2) * s
            cy = (by - y0 + 2 + BUTTON / 2) * s
            zd.line([(cx, 0), (cx, zoom.height)], fill=(255, 64, 64, 255))
            zd.line([((bx - x0 + 2) * s, cy), ((bx - x0 + 2 + BUTTON) * s, cy)], fill=(255, 64, 64, 255))
            print(name, "body gap L,R,T,B =", margins(m, bx, by))
        zoom.save(out[:-4] + "-dock-zoom.png")
    print("wrote", out)
