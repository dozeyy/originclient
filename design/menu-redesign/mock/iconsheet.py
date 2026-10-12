#!/usr/bin/env python3
"""Builds iconsheet.html: every mod's icon exactly as the mock-ups draw it (same
v2 SVG masters + Origin's REDRAWN overrides as build.py), in the mock's card style.
Render: NODE_PATH=$(npm root -g) node render.cjs icons iconsheet 1600x<H>x1 sheet"""
import json, re, importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SVG = ROOT / "design/icon-proposals/origin-icon-system-v2/svg"
JAVA = ROOT / "src/mods/versions/1.21.1/src/client/java/com/origin/client/client/mods/Mods.java"
strip = lambda t: re.sub(r"^.*?<svg[^>]*>|</svg>\s*$", "", t, flags=re.S)
spec = importlib.util.spec_from_file_location("v2", SVG.parent / "generate_proposal.py")
v2 = importlib.util.module_from_spec(spec); spec.loader.exec_module(v2)
redrawn = {k.split(".", 1)[1]: b for k, b in v2.REDRAWN.items()}

def body(i):
    if i in redrawn:
        return redrawn[i], "Origin redraw"
    p = SVG / f"client.{i}.svg"
    return (strip(p.read_text()), "") if p.exists() else ("", "MISSING")

src = JAVA.read_text()
mods = re.findall(r'add\("([a-z]+)",\s*"([^"]+)"', src)
mods.insert([m[0] for m in mods].index("hypixelbedwars"), ("portalcalc", "Portal Calculator"))
cards = []
for i, name in mods:
    b, note = body(i)
    cards.append({"id": i, "name": name, "svg": b, "note": note})
(Path(__file__).parent / "iconsheet.html").write_text(
    (Path(__file__).parent / "iconsheet.tpl.html").read_text().replace("/*CARDS*/[]", json.dumps(cards)))
print(len(cards), "icons;", sum(1 for c in cards if c["note"] == "MISSING"), "missing")
