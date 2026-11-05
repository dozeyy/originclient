"""Test the default HUD layout (hud/HudLayout): every piece in its own spot.

    python tools/hud-layout-tests/run.py

1. Every live module carries hud/HudLayout.java byte-identical to shared/ (the pre-1.20 modules
   and 1.8.9 are .no-shared-sync, so sync.py --check doesn't see theirs).
2. Every live module takes its HUD defaults from the table: no literal `new HudPos(<numbers>)`
   outside HudPos/HudLayout, and the ids it passes to HudPos.ofDefault are collected for step 3.
3. HudLayoutTests (pure Java, compiled with HudLayout and ToastBand): every collected id has a spot and a
   planned size; no two pieces overlap each other, the chat or the hotbar at 1366x768 / 1920x1080
   (auto GUI scale) and bigger GUI sizes; a player's own spot is kept and an untouched old default
   moves to the new one; the Potion HUD steps below toasts (ToastBand, JG-9).
CI: build-check.
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
REL = "com/origin/client/client/hud/HudLayout.java"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", REL)
LITERAL = re.compile(r"new (?:com\.origin\.client\.client\.hud\.)?HudPos\(\s*-?[\d.]+\s*,")
OF_DEFAULT = re.compile(r'HudPos\.ofDefault\("(\w+)"\)')


def main():
    live = [m["module"] for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]]
    want = open(SHARED, "rb").read()
    bad = 0
    ids = set()
    for mod in live:
        base = os.path.join(ROOT, "src/mods/versions", mod)
        copies = glob.glob(os.path.join(base, "src/*/java", REL))
        if len(copies) != 1 or open(copies[0], "rb").read() != want:
            print(f"FAIL {mod}: hud/HudLayout.java missing or differs from shared")
            bad += 1
        found = set()
        for f in glob.glob(os.path.join(base, "src/*/java/**/*.java"), recursive=True):
            if os.path.basename(f) in ("HudPos.java", "HudLayout.java"):
                continue
            text = open(f, encoding="utf-8").read()
            found.update(OF_DEFAULT.findall(text))
            for i, line in enumerate(text.splitlines(), 1):
                if LITERAL.search(line):
                    print(f"FAIL {mod}: {os.path.relpath(f, base)}:{i} has a literal HUD default; use HudPos.ofDefault(id)")
                    bad += 1
        if not found:
            print(f"FAIL {mod}: no HudPos.ofDefault calls found")
            bad += 1
        ids |= found
    print(f"HudLayout copies + defaults: {len(live)} live modules, {len(ids)} piece ids")

    out = tempfile.mkdtemp(prefix="hudlayout-")
    # ToastBand (the potions step below toasts): every live module but 1.8.9 carries the shared copy
    band_rel = REL.replace("HudLayout.java", "ToastBand.java")
    band = os.path.join(ROOT, "src/mods/shared/src/client/java", band_rel)
    band_want = open(band, "rb").read()
    for mod in live:
        if mod == "1.8.9":
            continue   # one achievement pop-up at a time: the slot HudLayout reserves is enough
        copies = glob.glob(os.path.join(ROOT, "src/mods/versions", mod, "src/*/java", band_rel))
        if len(copies) != 1 or open(copies[0], "rb").read() != band_want:
            print(f"FAIL {mod}: hud/ToastBand.java missing or differs from shared")
            bad += 1
    if subprocess.run(["javac", "--release", "8", "-d", out, SHARED, band, os.path.join(HERE, "HudLayoutTests.java")]).returncode:
        sys.exit("compile failed")
    bad += subprocess.run(["java", "-cp", out, "HudLayoutTests"] + sorted(ids)).returncode
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
