"""Test gui/GlyphCoverage (LOC-1: letters Origin's Inter text lacks fall back to Minecraft's font).

    python tools/glyph-coverage-tests/run.py

GlyphCoverage is pure Java 8. Every live module must carry it byte-identical to shared/ (the
pre-1.20 modules and 1.8.9 are .no-shared-sync, so sync.py --check doesn't see theirs), and
every module's OriginDeck must ask covers() on both its measure and its draw path (a string
measured in one font and drawn in the other would overlap). The shared copy compiles with
--release 8 and runs GlyphCoverageTests against the REAL Inter MSDF atlas (its glyph list is
handed over as a UTF-8 file). CI: build-check.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
REL = "com/origin/client/client/gui/GlyphCoverage.java"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", REL)
ATLAS = os.path.join(ROOT, "src/mods/versions/1.21.1/src/client/resources/assets/originclient/textures/font/inter_msdf.json")


def main():
    want = open(SHARED, "rb").read()
    bad = 0
    for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]:
        mod = os.path.join(ROOT, "src/mods/versions", m["module"])
        copies = glob.glob(os.path.join(mod, "src/*/java", REL))
        if len(copies) != 1 or open(copies[0], "rb").read() != want:
            print(f"FAIL {m['module']}: gui/GlyphCoverage.java missing or differs from shared")
            bad += 1
        deck = glob.glob(os.path.join(mod, "src/*/java/com/origin/client/client/gui/OriginDeck.java"))
        text = open(deck[0], encoding="utf-8").read() if deck else ""
        if text.count("Font.covers(str)") != 2:
            print(f"FAIL {m['module']}: OriginDeck must check covers(str) when measuring AND drawing")
            bad += 1
    glyphs = "".join(k for k in json.load(open(ATLAS, encoding="utf-8"))["glyphs"] if len(k) == 1)
    out = tempfile.mkdtemp(prefix="glyphs-")
    atlas_file = os.path.join(out, "atlas.txt")
    with open(atlas_file, "w", encoding="utf-8") as f:
        f.write(glyphs)
    if subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", out, SHARED,
                       os.path.join(HERE, "GlyphCoverageTests.java")]).returncode:
        sys.exit("compile failed")
    bad += subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", out, "GlyphCoverageTests", atlas_file]).returncode
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
