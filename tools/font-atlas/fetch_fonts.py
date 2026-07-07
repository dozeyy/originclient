#!/usr/bin/env python3
"""Fetch Inter's static (non-variable) TTF weights used by Origin Client's
in-game font atlas.

Origin Client's font provider (Minecraft 1.21.1, LWJGL FreeType-backed) has
already failed twice on a *variable* font (Bahnschrift's `fvar` table) — see
MEMORY.md 2026-07-06/07. Google Fonts' own distribution of Inter is a single
variable file; this script instead pulls the static per-weight builds that
`@fontsource/inter` publishes to npm (WOFF2), converts each to a plain TTF via
fontTools, and asserts no `fvar` table survived the trip.

Usage: python3 fetch_fonts.py
Requires: npm (for `npm pack`), fontTools, brotli (`pip install fonttools brotli`)
Output: fonts/Inter-{400,500,600,700,800}.ttf + fonts/OFL-LICENSE.txt
"""
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont

WEIGHTS = [400, 500, 600, 700, 800]
PACKAGE = "@fontsource/inter"
HERE = Path(__file__).resolve().parent
FONTS_DIR = HERE / "fonts"


def main() -> None:
    FONTS_DIR.mkdir(exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        subprocess.run(["npm", "pack", PACKAGE], cwd=tmp_path, check=True, capture_output=True)
        tarballs = list(tmp_path.glob("fontsource-inter-*.tgz"))
        if not tarballs:
            print("npm pack did not produce a tarball", file=sys.stderr)
            sys.exit(1)
        with tarfile.open(tarballs[0]) as tf:
            tf.extractall(tmp_path)

        package_dir = tmp_path / "package"
        license_src = package_dir / "LICENSE"
        (FONTS_DIR / "OFL-LICENSE.txt").write_bytes(license_src.read_bytes())

        for weight in WEIGHTS:
            src = package_dir / "files" / f"inter-latin-{weight}-normal.woff2"
            font = TTFont(str(src))

            if "fvar" in font:
                print(f"weight {weight}: source is a variable font (fvar present) — aborting", file=sys.stderr)
                sys.exit(1)

            cmap = font.getBestCmap()
            missing = [chr(c) for c in range(0x20, 0x7F) if c not in cmap]
            if missing:
                print(f"weight {weight}: missing printable ASCII glyphs: {missing}", file=sys.stderr)
                sys.exit(1)

            out = FONTS_DIR / f"Inter-{weight}.ttf"
            font.flavor = None  # strip the WOFF2 wrapper, write plain sfnt/TTF
            font.save(str(out))
            print(f"weight {weight}: OK, no fvar, full ASCII coverage -> {out}")


if __name__ == "__main__":
    main()
