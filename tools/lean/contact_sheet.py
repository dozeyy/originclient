#!/usr/bin/env python3
"""Contact sheet: every screenshot in a folder tiled into one small labelled image.

    python tools/lean/contact_sheet.py <folder> [<folder> ...]

Writes <folder>/_sheet.jpg (and _sheet-2.jpg ... when there are more than 9
shots). Why (Claude Lean): every image Claude opens stays in the conversation
for good and costs tokens by pixel area, so one 1568 px sheet of 6-9 shots
replaces 6-9 full-size reads. Open a single full-size shot only for detail.

Needs Pillow (`pip install pillow`). dev-shots.yml runs this for every module.
"""
import glob
import math
import os
import re
import sys

SHEET_W = 1568          # Claude downsizes anything wider, so pixels past this are wasted
PER_SHEET = 9
GAP = 6
LABEL_H = 20
BG = (18, 18, 18)
FG = (224, 224, 224)    # Origin's accent tone


VARIANT = re.compile(r"^(.*)_(\d+)x(\d+)(_s\d+)?\.\w+$")


def smallest_variants(paths):
    """dev-shots captures each screen at 1366x768 AND 1920x1080: keep only the
    smallest of each (same screen, same GUI scale) - the sheet is for spotting
    problems; open the big one only when its detail matters."""
    best = {}
    for p in paths:
        m = VARIANT.match(os.path.basename(p))
        if not m:
            best[p] = (0, p)
            continue
        key = (m.group(1), m.group(4))
        area = int(m.group(2)) * int(m.group(3))
        if key not in best or area < best[key][0]:
            best[key] = (area, p)
    return sorted(p for _, p in best.values())


def sheets(folder):
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        sys.exit("contact_sheet.py needs Pillow: pip install pillow")
    shots = sorted(p for p in glob.glob(os.path.join(folder, "*.png")) + glob.glob(os.path.join(folder, "*.jpg"))
                   if not os.path.basename(p).startswith("_sheet"))
    shots = smallest_variants(shots)
    for old in glob.glob(os.path.join(folder, "_sheet*.jpg")):
        os.remove(old)
    if not shots:
        return []
    try:
        font = ImageFont.load_default(size=14)
    except TypeError:           # Pillow < 10.1
        font = ImageFont.load_default()
    written = []
    for n, start in enumerate(range(0, len(shots), PER_SHEET)):
        batch = shots[start:start + PER_SHEET]
        cols = 1 if len(batch) == 1 else 2 if len(batch) <= 4 else 3
        tile_w = (SHEET_W - GAP * (cols + 1)) // cols
        tiles = []
        for p in batch:
            with Image.open(p) as im:
                im = im.convert("RGB")
                h = max(1, round(im.height * tile_w / im.width))
                tiles.append((os.path.basename(p), im.resize((tile_w, h), Image.LANCZOS)))
        rows = math.ceil(len(tiles) / cols)
        row_h = [max(t.height for _, t in tiles[r * cols:(r + 1) * cols]) + LABEL_H for r in range(rows)]
        sheet = Image.new("RGB", (SHEET_W, sum(row_h) + GAP * (rows + 1)), BG)
        draw = ImageDraw.Draw(sheet)
        y = GAP
        for r in range(rows):
            for c, (name, tile) in enumerate(tiles[r * cols:(r + 1) * cols]):
                x = GAP + c * (tile_w + GAP)
                draw.text((x + 2, y + 2), name, fill=FG, font=font)
                sheet.paste(tile, (x, y + LABEL_H))
            y += row_h[r] + GAP
        out = os.path.join(folder, "_sheet.jpg" if n == 0 else "_sheet-%d.jpg" % (n + 1))
        sheet.save(out, quality=85)
        written.append(out)
    return written


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for folder in sys.argv[1:]:
        for out in sheets(folder):
            print(out)


if __name__ == "__main__":
    main()
