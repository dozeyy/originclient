#!/usr/bin/env python3
"""Vulkan probe only: read one probe row's run.log + PNGs and print the answers.

usage: analyze.py <case> <out-dir>   (out-dir holds run.log, timing.txt, screenshots/, x11/)
Writes the same text to $GITHUB_STEP_SUMMARY. Exit 1 when the title was never reached or no
PNG with real content exists (so a red row means "did not render", never a false green).
"""
import glob
import os
import re
import sys

try:
    from PIL import Image, ImageStat
except ImportError:  # the contact sheet needs Pillow too; without it, counts only
    Image = None

case, out = sys.argv[1], sys.argv[2]
log_path = os.path.join(out, "run.log")
log = open(log_path, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(log_path) else []
lines = []


def say(s=""):
    print(s)
    lines.append(s)


def grep(rx, flags=re.I, limit=None):
    r = re.compile(rx, flags)
    hits = [l for l in log if r.search(l)]
    return hits[:limit] if limit else hits


say(f"## vulkan-probe: {case}")
say("```")
# Timing (wall clock, 1 s resolution, from the run loop).
tp = os.path.join(out, "timing.txt")
if os.path.exists(tp):
    for l in open(tp).read().splitlines():
        say("timing: " + l)

# Mod list: which of the ids that matter were really loaded.
mods = {}
inblock = False
for l in log:
    if re.search(r"Loading \d+ mods:", l):
        inblock = True
        say(l.strip()[-120:])
        continue
    if inblock:
        m = re.search(r"[-\\]+\s+([A-Za-z0-9_.\-]+)\s+(\S+)\s*$", l)
        if m:
            mods.setdefault(m.group(1), m.group(2))
        elif l.strip() and not re.match(r"^\s*[|\\ -]", l):
            inblock = False
for mid in ("vulkanmod", "vulkairis", "vulkairis_sodium_compat", "vulkairis_sodium_provider", "sodium", "iris", "immediatelyfast", "originclient", "origin", "fabric-api",
            "modernfix", "lithium", "ferritecore", "modmenu", "jei"):
    if mid in mods:
        say(f"loaded: {mid} {mods[mid]}")
say("not loaded (of interest): " + ", ".join(m for m in ("vulkanmod", "vulkairis", "sodium", "iris", "immediatelyfast") if m not in mods))
for l in grep(r"disableModIds|disabled mod|Mod resolution failed|Incompatible mods? found|requires .* which is missing|breaks|is not a (fabric )?mod", limit=12):
    say("loader: " + l.strip()[:300])

# Vulkan device / renderer lines.
for l in grep(r"vulkan|lavapipe|llvmpipe|\bDevice\b|GL_RENDERER|Backend library|OpenGL", limit=14):
    say("render: " + l.strip()[:240])

# First fatal error.
fatal_rx = re.compile(r"Exception in thread|---- Minecraft Crash Report|/FATAL\]|NoClassDefFoundError|ClassNotFoundException|"
                      r"UnsatisfiedLinkError|IllegalStateException|NullPointerException|VK_ERROR|Mixin apply.*failed|"
                      r"Critical injection failure|InvalidInjectionException|MixinApplyError|Could not find|"
                      r"Mod resolution failed|Incompatible mod|Failed to create|RuntimeException", re.I)
first = next((i for i, l in enumerate(log) if fatal_rx.search(l)), None)
if first is None:
    say("first error: none matched")
else:
    say(f"first error (run.log line {first + 1}):")
    for l in log[first:first + 12]:
        say("  " + l.rstrip()[:260])

# Mixin apply failures, Origin's by name.
mix = grep(r"Mixin apply(ing)? .*failed|Critical injection failure|InvalidInjectionException|MixinApplyError|"
           r"InvalidMixinException|@Overwrite .*conflict|Redirector .*conflict|MixinTargetAlreadyLoaded")
say(f"mixin failures: {len(mix)} (Origin's: {sum(1 for l in mix if 'origin' in l.lower())})")
for l in mix[:15]:
    say("  " + l.strip()[:300])

# Origin markers.
for l in grep(r"\[origin-smoke\]|\[dev-shots\] captured|Stopping!|\[OriginBootLog\].*(title|total|done)|summary lead\+handoff", limit=30):
    say("origin: " + l.strip()[:260])

# PNGs: count, and real content (not black / not flat).
real = 0
pngs = sorted(glob.glob(os.path.join(out, "screenshots", "*.png")) + glob.glob(os.path.join(out, "x11", "*.png")))
say(f"PNGs: {len(pngs)} (game screenshots: {len(glob.glob(os.path.join(out, 'screenshots', '*.png')))}, X11 grabs: {len(glob.glob(os.path.join(out, 'x11', '*.png')))})")
for p in pngs:
    if Image is None:
        continue
    try:
        im = Image.open(p).convert("L")
        st = ImageStat.Stat(im)
        mean, std = st.mean[0], st.stddev[0]
        verdict = "BLACK" if mean < 8 and std < 4 else ("FLAT" if std < 3 else "real")
        if verdict == "real":
            real += 1
        say(f"  {os.path.relpath(p, out)} {im.size[0]}x{im.size[1]} mean={mean:.1f} std={std:.1f} {verdict}")
    except Exception as e:
        say(f"  {os.path.relpath(p, out)} unreadable: {e}")
title = bool(grep(r"\[origin-smoke\] title screen reached"))
say(f"VERDICT {case}: title={'yes' if title else 'NO'} real_pngs={real}/{len(pngs)}")
say("```")

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
sys.exit(0 if title and real > 0 else 1)
