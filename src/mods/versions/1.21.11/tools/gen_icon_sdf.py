"""
Bakes a single-channel SDF atlas for the Origin menu's vector icons (close X,
chevron-left, chevron-right, edit pencil) — same technique as the MSDF font
atlas, so it reuses the already-proven GuiElementRenderState/pipeline pattern
(Position+UV0+Color) instead of needing custom per-vertex shader parameters
(which VertexConsumer's fixed API doesn't support).

Geometry mirrors the exact capsule-stroke definitions from 1.21.1's
OriginUi.iconClose/iconChevron/iconEdit (same coordinates, just baked into a
distance field offline instead of drawn via the ROUND shader at runtime).

Cell layout: 4 icons, each baked at CELL=64px into a 256x64 atlas, one row.
Icon order: close, chevron-left, chevron-right, edit.

Distance convention: signed distance to the nearest stroke's outer edge
(negative = inside the stroke). Encoded as a single channel:
  pixel = clamp(0.5 - signedDist/DISTANCE_RANGE, 0, 1) * 255
0.5 (=127) is exactly the edge; >0.5 is inside. Matches the MSDF fragment
shader's math (median() step just isn't needed for a single channel).
"""
import math

CELL = 64
DISTANCE_RANGE = 16.0  # in baked-texture pixels; shader divides by this
ICONS = ["close", "chevron_left", "chevron_right", "edit"]
ATLAS_W = CELL * len(ICONS)
ATLAS_H = CELL


def seg_dist(px, py, ax, ay, bx, by):
    abx, aby = bx - ax, by - ay
    apx, apy = px - ax, py - ay
    len2 = abx * abx + aby * aby
    t = 0.0 if len2 <= 1e-9 else max(0.0, min(1.0, (apx * abx + apy * aby) / len2))
    cx, cy = ax + t * abx, ay + t * aby
    return math.hypot(px - cx, py - cy)


def capsules_for(name, size):
    """Returns list of (ax,ay,bx,by,half_width) in a `size`x`size` virtual box,
    matching 1.21.1's OriginUi definitions exactly."""
    h = max(0.9, size * 0.10)
    caps = []
    if name == "close":
        inset = size * 0.22
        caps.append((inset, inset, size - inset, size - inset, h))
        caps.append((size - inset, inset, inset, size - inset, h))
    elif name in ("chevron_left", "chevron_right"):
        left = name == "chevron_left"
        point_x = size * (0.34 if left else 0.66)
        arm_x = size * (0.66 if left else 0.34)
        mid_y = size * 0.5
        top_y = size * 0.24
        bot_y = size * 0.76
        caps.append((point_x, mid_y, arm_x, top_y, h))
        caps.append((point_x, mid_y, arm_x, bot_y, h))
    elif name == "edit":
        ex, ey = size * 0.80, size * 0.20
        mx, my = size * 0.36, size * 0.64
        tx, ty = size * 0.16, size * 0.84
        caps.append((ex, ey, mx, my, size * 0.12))
        caps.append((mx, my, tx, ty, size * 0.055))
    return caps


def signed_distance(px, py, caps):
    best = 1e9
    for (ax, ay, bx, by, hw) in caps:
        d = seg_dist(px, py, ax, ay, bx, by) - hw
        if d < best:
            best = d
    return best


def main():
    from PIL import Image
    img = Image.new("RGBA", (ATLAS_W, ATLAS_H), (0, 0, 0, 255))
    px = img.load()
    virtual_size = CELL * 0.72  # padding so strokes don't touch cell edges
    pad = (CELL - virtual_size) / 2.0
    for idx, name in enumerate(ICONS):
        caps = capsules_for(name, virtual_size)
        ox = idx * CELL
        for y in range(CELL):
            for x in range(CELL):
                vx, vy = x - pad, y - pad
                d = signed_distance(vx, vy, caps)
                v = 0.5 - d / DISTANCE_RANGE
                v = max(0.0, min(1.0, v))
                val = round(v * 255)
                px[ox + x, y] = (val, val, val, 255)
    out_path = r"C:\Users\Will\Documents\Origin Client\src\mods\versions\1.21.11\src\client\resources\assets\originclient\textures\ui\icon_sdf.png"
    img.save(out_path)
    print("saved", out_path, "atlas", ATLAS_W, "x", ATLAS_H)
    print("icon order:", ICONS)
    print("cell size:", CELL, "distance_range:", DISTANCE_RANGE)


if __name__ == "__main__":
    main()
