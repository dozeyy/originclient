#!/usr/bin/env python3
"""flywheel-vulkan CI: read one boot's run.log + PNGs and print what happened.

usage: analyze.py <out-dir> <must-regex>...   (out-dir holds run.log and the PNG folders)
Each <must-regex> has to match a line of run.log. Exit 1 when one is missing, when the
title was never reached, or when no PNG exists (0 shots is never green).
Writes the same text to $GITHUB_STEP_SUMMARY.
"""
import glob
import os
import re
import sys

out, musts = sys.argv[1], sys.argv[2:]
p = os.path.join(out, "run.log")
log = open(p, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(p) else []
lines, bad = [], []


def say(s=""):
    print(s)
    lines.append(s)


def grep(rx, limit=None):
    r = re.compile(rx, re.I)
    hits = [l for l in log if r.search(l)]
    return hits[:limit] if limit else hits


say("## flywheel-vulkan boot")
say("```")
for rx in musts:
    hits = grep(rx, 3)
    say(f"{'OK  ' if hits else 'MISS'} {rx}")
    for h in hits:
        say("     " + h.strip()[-220:])
    if not hits:
        bad.append(rx)
title = grep(r"\[origin-smoke\] title screen reached", 1)
say(f"title reached: {'yes' if title else 'NO'}")
world = grep(r"\[origin-smoke\] in-world: HUD up", 1)
say(f"in-world reached: {'yes' if world else 'no'}")
for rx in (r"origin-flywheel-vulkan|origin_flywheel_vulkan", r"flywheel", r"Mixin apply .* failed",
           r"Exception|Error:"):
    hits = grep(rx, 15)
    if hits:
        say(f"-- {rx} ({len(grep(rx))} lines, first {len(hits)})")
        for h in hits:
            say("   " + h.strip()[-220:])
pngs = sorted(glob.glob(os.path.join(out, "**", "*.png"), recursive=True))
pngs = [x for x in pngs if "_sheet" not in os.path.basename(x)]
say(f"PNGs: {len(pngs)}")
for x in pngs[:60]:
    say("   " + os.path.relpath(x, out))
say("```")
if not title:
    bad.append("title")
if not pngs:
    bad.append("0 PNGs")
summ = os.environ.get("GITHUB_STEP_SUMMARY")
if summ:
    with open(summ, "a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
if bad:
    print("FAILED: " + ", ".join(bad))
    sys.exit(1)
