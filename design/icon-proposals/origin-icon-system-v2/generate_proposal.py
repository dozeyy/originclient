#!/usr/bin/env python3
"""Origin Icon System v2 -- the icon masters (Will, 2026-10-12).

Will supplied the set: a rounded-outline family on a 24 px grid with a bold
2.2 px stroke, mostly custom drawings on Feather Icons geometry (MIT, see
LICENSE-Feather.txt). Its SVGs are copied verbatim into svg/<id>.svg, one per
icon id (the same ids as v1, so every consumer keeps working).

Seven entries in the supplied pack were Lunar Client's own PNG artwork wrapped
in an SVG (FPS, CPS, Reach Display, Hitboxes, Freelook, Toggle Sprint,
Quickplay). Origin can't ship another client's art, so those seven are
REDRAWN below in the same family -- same grid, stroke, caps and joins.

The Origin mark and app icon are Origin's brand, not part of the pack, so they
come from v1 unchanged (BRAND), at their own lighter stroke.

Consumers: tools/mod-menu/generate_icons.py bakes these into the in-game atlas;
the launcher's Theme/Icons.xaml carries hand-converted copies of the launcher
ids (see the comment there). Run this file to write contact-sheet.svg for review.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
SVG_DIR = ROOT / "svg"
V1 = ROOT.parent / "origin-icon-system-v1" / "generate_proposal.py"

STROKE = ('fill="none" stroke="currentColor" stroke-width="2.2" '
          'stroke-linecap="round" stroke-linejoin="round"')

# The seven Origin drawings that replace the pack's Lunar PNG wrappers.
REDRAWN = {
    # A speed gauge: the dial and its needle.
    "client.fps": '<path d="M3.5 17a8.5 8.5 0 1 1 17 0"/><path d="m12 17 4.5-5.5"/>'
                  '<path d="M6.5 11.5 7.8 12.6M12 6v1.6M17.5 11.5l-1.3 1.1"/>',
    # A mouse with click marks above it.
    "client.cps": '<rect x="7" y="9" width="10" height="13" rx="5"/><path d="M12 9v4"/>'
                  '<path d="M12 2.5v2.5M6 4.5l1.6 1.6M18 4.5l-1.6 1.6"/>',
    # Reach: an arrow carried to a wall -- the distance of a hit.
    "client.reach": '<path d="M3 12h13"/><path d="m12.5 8 4 4-4 4"/><path d="M21 4.5v15"/>',
    # Hitboxes: a player inside a dashed box.
    "client.hitboxes": '<rect x="4" y="2.5" width="16" height="19" rx="1.5" stroke-dasharray="2.6 3"/>'
                       '<circle cx="12" cy="9" r="2.4"/><path d="M8 17.5c0-2.4 1.8-3.8 4-3.8s4 1.4 4 3.8"/>',
    # Freelook: a look-around arrow orbiting the eye.
    "client.freelook": '<path d="M20.5 12a8.5 8.5 0 1 1-2.5-6"/><path d="M20.5 3.5V8H16"/><circle cx="12" cy="12" r="2.6"/>',
    # Toggle Sprint: fast-forward chevrons.
    "client.togglesprint": '<path d="m4.5 6 6 6-6 6"/><path d="m12.5 6 6 6-6 6"/>',
    # Quickplay: play inside a tile.
    "client.quickplay": '<rect x="3" y="3" width="18" height="18" rx="4"/><path d="M10 8.2 15.8 12 10 15.8z"/>',
}


def _load_v1():
    spec = importlib.util.spec_from_file_location("origin_icon_masters_v1", V1)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _body(svg_text: str) -> str:
    body = re.sub(r"^.*?<svg[^>]*>", "", svg_text, flags=re.S)
    return re.sub(r"</svg>\s*$", "", body).strip()


def _build() -> list[dict]:
    v1 = _load_v1()
    v1_by_id = {e["id"]: e for e in v1.ICONS}
    icons = []
    for path in sorted(SVG_DIR.glob("*.svg")):
        icon_id = path.stem
        icons.append({"id": icon_id, "label": icon_id, "body": _body(path.read_text(encoding="utf-8"))})
    for icon_id, body in REDRAWN.items():
        icons.append({"id": icon_id, "label": icon_id, "body": body})
    for icon_id in ("brand.origin-mark", "brand.app"):
        entry = dict(v1_by_id[icon_id])
        entry["stroke"] = v1.STROKE
        icons.append(entry)
    ids = [e["id"] for e in icons]
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        raise RuntimeError(f"icon ids defined twice: {sorted(dupes)}")
    return icons


ICONS = _build()


def write_contact_sheet() -> Path:
    cols, cell = 10, 64
    rows = (len(ICONS) + cols - 1) // cols
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{cols * cell}" height="{rows * (cell + 14)}" '
             f'style="background:#121918" color="#E0E0E0" font-family="sans-serif" font-size="7">']
    for i, e in enumerate(ICONS):
        x, y = (i % cols) * cell, (i // cols) * (cell + 14)
        parts.append(f'<g transform="translate({x + 14} {y + 8}) scale(1.5)" {e.get("stroke", STROKE)}>{e["body"]}</g>')
        parts.append(f'<text x="{x + cell / 2}" y="{y + cell + 8}" fill="#9a9a9a" text-anchor="middle">{e["id"].split(".", 1)[1]}</text>')
    parts.append("</svg>")
    out = ROOT / "contact-sheet.svg"
    out.write_text("\n".join(parts), encoding="utf-8")
    return out


if __name__ == "__main__":
    print(write_contact_sheet())
