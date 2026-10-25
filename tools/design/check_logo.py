#!/usr/bin/env python3
"""Logo gate: the Origin mark is the official image file and is never drawn again.

Rule (docs/overhaul/DESIGN-SYSTEM.md section 1.5, Will 2026-10-25): the only logo files are
textures/ui/origin_logo.png (the same art as Assets/origin.ico) and Assets/Brand/wordmark.png. Nothing may redraw the
mark in XAML, SVG, CSS, JavaScript, Java or Python. This script fails the build when a hand-drawn Origin mark comes
back anywhere in the launcher, the installer, the website or the mods:

  1. three tilted rings: a rotation list/array containing 0, 60 and 120 degrees, or rotations of 60 and 120 degrees in
     a file that also draws ellipses/rings (the "atom" construction of the old mark);
  2. a TorusGeometry (the three.js ring);
  3. a ring-mark file by name (OriginMark*, OrbitMark*, ring-0..3.png, rings.json);
  4. a logo-like image (name contains logo or mark) whose bytes are not one of the official files.

Usage:  python tools/design/check_logo.py                 scan the repo, exit 1 on any hit (the build-check gate)
        python tools/design/check_logo.py --root DIR      scan DIR instead of the repo (tests, planted-defect proofs)
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# md5 of the official files. A new official logo is added HERE (one place) together with its file.
OFFICIAL_MD5 = {
    "470b7e45e5ec999b1da3244e6923acf3",  # textures/ui/origin_logo.png (and the launcher's linked copy)
    "562729b44dde2b5d583c85d1618f18c8",  # Assets/Brand/wordmark.png
    # The website's resized copies of the official logo (website/assets/origin-logo-<px>.png; commit 60220251b).
    "82cfc84b0a02bc73ff337c7f510abc9b",  # 32 px
    "5ca08d0975f7d0b2a0b74f9973ede328",  # 64 px
    "3a0086eed8ab9eecf5da237ba109153d",  # 128 px
}

SKIP_DIRS = {".git", "node_modules", "obj", "bin", "build", ".gradle", ".claude", "graphify-out", "artifacts", "docs",
             "design", "__pycache__", ".vs", "run"}
# "design" is skipped in two places only (see skipped()): tools/design (this detector and its tests spell the patterns
# out on purpose) and the top-level design/ folder (historical logo proposals and mock-ups, never shipped).

# Third-party minified libraries under a vendor/ folder (three.js defines TorusGeometry for everybody).
VENDOR_MIN = re.compile(r"(?:^|/)vendor/[^/]+\.min\.js$")

# The in-game "living rings" background field (launch experience v2, Will 2026-10-18) is a texture set, not the logo:
# OriginScreenRenderer paints ring-0..3.png as a drifting backdrop. These exact paths are the only place those names
# are allowed; retiring the field is a separate in-game unit.
INGAME_RING_FIELD = re.compile(r"^src/mods/(?:shared|versions/[^/]+|staged/[^/]+)/.*/textures/ui/(?:ring-[0-3]\.png|rings\.json)$")

CODE_EXT = {".xaml", ".cs", ".java", ".kt", ".js", ".mjs", ".ts", ".html", ".htm", ".css", ".svg", ".py", ".json", ".glsl"}
IMAGE_EXT = {".png", ".ico", ".svg", ".jpg", ".jpeg", ".webp"}

RING_NAMES = re.compile(r"(?i)^(originmark|orbitmark|origin[-_]?mark|orbit[-_]?mark)|^ring-?[0-3]\.png$|^rings\.json$")
LOGO_LIKE = re.compile(r"(?i)(^|[-_ .])(logo|mark)([-_ .]|$)")
ANGLES_LIST = re.compile(r"\b0(?:\.0+)?[fFdD]?\s*,\s*60(?:\.0+)?[fFdD]?\s*,\s*120(?:\.0+)?[fFdD]?\b")
ROT_60 = re.compile(r"(?i)(rotat\w*|angle)\W{0,12}\b60(?:\.0+)?[fFdD]?\b|rotate\(\s*60\b")
ROT_120 = re.compile(r"(?i)(rotat\w*|angle)\W{0,12}\b120(?:\.0+)?[fFdD]?\b|rotate\(\s*120\b")
ELLIPSE = re.compile(r"(?i)\bEllipse(?:Geometry)?\b|<ellipse\b|ctx\.ellipse|\.ellipse\(|\bring\b|\borbit")
TORUS = re.compile(r"\bTorusGeometry\b|\bTorusKnot\w*\b")


def skipped(parts: tuple[str, ...]) -> bool:
    for i, p in enumerate(parts[:-1]):
        if p in SKIP_DIRS:
            if p == "design" and not (i == 0 or parts[i - 1] == "tools"):
                continue  # only top-level design/ and tools/design are skipped; any other design folder is scanned
            return True
    return False


def scan(root: Path) -> list[tuple[str, str]]:
    hits: list[tuple[str, str]] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel_parts = path.relative_to(root).parts
        if skipped(rel_parts):
            continue
        rel = "/".join(rel_parts)
        name = path.name
        ext = path.suffix.lower()

        if INGAME_RING_FIELD.match(rel) or VENDOR_MIN.search(rel):
            continue
        if RING_NAMES.search(name):
            hits.append((rel, "ring-mark file name"))
            continue

        if ext in IMAGE_EXT and ext != ".svg" and LOGO_LIKE.search(path.stem):
            md5 = hashlib.md5(path.read_bytes()).hexdigest()
            if md5 not in OFFICIAL_MD5:
                hits.append((rel, f"logo-like image that is not an official file (md5 {md5})"))
            continue

        if ext not in CODE_EXT:
            continue
        try:
            text = path.read_text(encoding="utf-8-sig", errors="replace")
        except OSError:
            continue
        if len(text) > 2_000_000:
            continue
        if TORUS.search(text):
            hits.append((rel, "TorusGeometry (a drawn ring)"))
            continue
        if ANGLES_LIST.search(text):
            hits.append((rel, "rotations 0 / 60 / 120 degrees (three tilted rings)"))
            continue
        if ROT_60.search(text) and ROT_120.search(text) and ELLIPSE.search(text):
            hits.append((rel, "ellipses rotated 60 and 120 degrees (three tilted rings)"))
    return hits


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=REPO, help="directory to scan (default: the repo)")
    args = ap.parse_args(argv)
    if not args.root.is_dir():
        print(f"check_logo: {args.root} is not a directory", file=sys.stderr)
        return 2
    hits = scan(args.root)
    for rel, why in hits:
        print(f"{rel}: {why}")
    print(f"check_logo: {len(hits)} hand-drawn mark hit(s)")
    if hits:
        print("check_logo: FAIL - use the official logo image (Brand.Logo / origin_logo.png), never redraw the mark")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
