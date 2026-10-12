#!/usr/bin/env python3
"""Builds the redesign mock-ups (mock.html) from the real icon masters, so the
mock uses exactly the in-game icon set. Render with render.cjs. One canvas is
one Minecraft GUI at 1920x1080, GUI scale 3 = 640x360 GUI px (CSS px here)."""
import json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SVG = ROOT / "design/icon-proposals/origin-icon-system-v2/svg"
def icon(i):
    p = SVG / f"client.{i}.svg"
    if not p.exists():
        return ""
    body = re.sub(r"^.*?<svg[^>]*>|</svg>\s*$", "", p.read_text(), flags=re.S)
    return body
ids = ["keystrokes","coords","armorhud","potionhud","zoom","freelook","fullbright","minimap","combo","reach",
       "crosshair","itemcounter","saturation","clock","ping","memory","directionhud","waypoints","chat","nametags",
       "itemphysics","hitcolor","weather","particles","menublur","togglesprint","screenshot","nickhider","autogg","bossbar"]
icons = {i: icon(i) for i in ids}
# Origin's redrawn icons live in the v2 master module, not as SVG files.
import importlib.util
spec = importlib.util.spec_from_file_location("v2", SVG.parent / "generate_proposal.py")
v2 = importlib.util.module_from_spec(spec); spec.loader.exec_module(v2)
for k, body in v2.REDRAWN.items():
    icons[k.split(".", 1)[1]] = body
for extra in ["@search", "@hudeditor", "@general", "@backing"]:
    icons[extra] = icon(extra)
icons["cog"] = re.sub(r"^.*?<svg[^>]*>|</svg>\s*$", "", (SVG / "launcher.settings.svg").read_text(), flags=re.S)
import sys
name = sys.argv[1] if len(sys.argv) > 1 else "mock"
tpl = (Path(__file__).parent / f"{name}.tpl.html").read_text()
(Path(__file__).parent / f"{name}.html").write_text(tpl.replace("/*ICONS*/{}", json.dumps(icons)))
print(f"{name}.html written")
