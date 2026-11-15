#!/usr/bin/env python3
"""Origin's title-dock sheet, 2026-11-14 (Will: keep Quit/Friends/Language/Accessibility, new Realms and Cosmetics).

  origin_icons.py <old_sheet.png> <out.png> [--preview <vanilla_sprites_dir> <preview.png>]

Realms (castle keep with a pennant) and Cosmetics (a cape hanging from its rod) are Origin's own icons:
the designs were generated with Recraft (recraftv4_1_raster, 2026-11-14) and are set here on the real
pixel grid, one texture pixel per pixel, so they match the other dock icons pixel for pixel. Quit is the
Kenney X, copied unchanged from the old sheet (cell x=32). Friends, Language and Accessibility are still
vanilla's own sprites, drawn by the mod at runtime.

Style = vanilla's accessibility icon (the dock's neighbour): white 252 body, no outline, detail drawn in
its own grey tones 214 / 170 / 110 (holes would fill with shadow), 1 px dark 42 drop shadow at (+1,+1)
on empty pixels.

Sheet contract is UNCHANGED (so no module's code changes): 48 x 16, one 16 x 16 cell per icon,
cosmetics x=0 body 14 x 12, realms x=16 body 15 x 13, quit x=32 body 12 x 12; body at the cell's top-left.
"""
import sys
from PIL import Image

WHITE = (252, 252, 252, 255)
SHADOW = (42, 42, 42, 255)
# vanilla accessibility icon's own tones: W white, L light shade, M mid, D dark detail
TONES = {"W": WHITE, "L": (214, 214, 214, 255), "M": (170, 170, 170, 255), "D": (110, 110, 110, 255)}

COSMETICS = [   # 14 x 12: the rod, the cape hanging from it with a diamond crest, two folds, a solid hem
    "MMMMMMMMMMMMMM",
    "..WWWWWWWWWL..",
    "..WWWWWWWWWL..",
    "..WWWWDWWWWL..",
    "..WWWDDDWWWL..",
    "..WWDDDDDWWL..",
    "..WWWDDDWWWL..",
    ".WWWWWDWWWWLL.",
    ".WWWLWWWWLWLL.",
    ".WWWLWWWWLWLL.",
    ".WWWLWWWWLWLL.",
    ".LLLLLLLLLLLL.",
]

REALMS = [      # 15 x 13: pennant on a pole, three merlons, the keep, an arched gate
    ".......WWW.....",
    ".......WW......",
    ".......M.......",
    "WWL...WWL...WWL",
    "WWWWWWWWWWWWWWL",
    "MMMMMMMMMMMMMMM",
    ".WWWWWWWWWWWWL.",
    ".WWWWWWWWWWWWL.",
    ".WWWWWDDDWWWWL.",
    ".WWWWDDDDDWWWL.",
    ".WWWWDDDDDWWWL.",
    ".WWWWDDDDDWWWL.",
    ".LLLLDDDDDLLLL.",
]

EXPECT = {"cosmetics": (14, 12), "realms": (15, 13)}


def stamp(sheet, rows, ox):
    h, w = len(rows), len(rows[0])
    assert all(len(r) == w for r in rows)
    body = {(x, y): TONES[c] for y, r in enumerate(rows) for x, c in enumerate(r) if c != "."}
    for (x, y), col in body.items():
        sheet.putpixel((ox + x, y), col)
    for x, y in body:
        if (x + 1, y + 1) not in body:
            sheet.putpixel((ox + x + 1, y + 1), SHADOW)
    return w, h


def build(old_path, out_path):
    old = Image.open(old_path).convert("RGBA")
    assert old.size == (48, 16), old.size
    sheet = Image.new("RGBA", (48, 16), (0, 0, 0, 0))
    sheet.paste(old.crop((32, 0, 48, 16)), (32, 0))     # Quit: the Kenney X, unchanged
    assert stamp(sheet, COSMETICS, 0) == EXPECT["cosmetics"]
    assert stamp(sheet, REALMS, 16) == EXPECT["realms"]
    sheet.save(out_path)
    return sheet


def preview(sheet, sprites, out, scale=8):
    """The dock as the title shows it (Realms, Friends, Cosmetics, Language, Accessibility, Quit), on
    the dark 20 x 20 buttons, 1 GUI px = `scale` screen px, nearest-neighbour."""
    btn = Image.open(f"{sprites}/widget/button.png").convert("RGBA")
    def nine(w, h):   # vanilla button sprite is 200 x 20; 20 x 20 = left 10 + right 10 columns
        b = Image.new("RGBA", (w, h))
        b.paste(btn.crop((0, 0, w // 2, h)), (0, 0))
        b.paste(btn.crop((200 - w // 2, 0, 200, h)), (w // 2, 0))
        return b
    rects = {"cosmetics": (0, 0, 15, 13, 14, 12), "realms": (16, 0, 16, 14, 15, 13), "quit": (32, 0, 13, 13, 12, 12)}
    order = ["realms", "friends", "cosmetics", "language", "accessibility", "quit"]
    gap, pad = 4, 6
    W = pad * 2 + len(order) * 20 + (len(order) - 1) * gap
    canvas = Image.new("RGBA", (W, 32), (24, 26, 25, 255))
    x = pad
    for k in order:
        canvas.alpha_composite(nine(20, 20), (x, 6))
        if k in rects:
            u, v, bw, bh, w, h = rects[k]
            canvas.alpha_composite(sheet.crop((u, v, u + bw, v + bh)), (x + 10 - w // 2, 6 + 10 - h // 2))
        elif k == "friends":
            canvas.alpha_composite(Image.open(f"{sprites}/toast/social_interactions.png").convert("RGBA"), (x, 6))
        else:   # vanilla SpriteIconButton: 15 x 15 sprite centred, odd pixel left/top
            s = Image.open(f"{sprites}/icon/{k}.png").convert("RGBA")
            canvas.alpha_composite(s, (x + 10 - s.width // 2, 6 + 10 - s.height // 2))
        x += 20 + gap
    canvas.resize((W * scale, 32 * scale), Image.NEAREST).save(out)


if __name__ == "__main__":
    s = build(sys.argv[1], sys.argv[2])
    if len(sys.argv) > 3 and sys.argv[3] == "--preview":
        preview(s, sys.argv[4], sys.argv[5])
