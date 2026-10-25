#!/usr/bin/env python3
"""Token gate for the launcher: no hard-coded colour and no literal font size outside the token files.

Rule (docs/overhaul/DESIGN-SYSTEM.md section 12, phase-3 U3.0): every colour and every font size in the launcher
comes from a token in src/OriginLauncher.App/Theme/ (Colors.xaml, Typography.xaml, Spacing.xaml). This script lists
every bypass:

  * a hex colour literal ("#RGB", "#RRGGBB", "#AARRGGBB") in a .xaml or .cs file outside the token files
    (comments are ignored), and
  * a literal number used as a font size (FontSize="13" in XAML, FontSize = 13 / FontSize: 13 in C#).

Allowed: the token files themselves, UI/Windows/CrashFallback.cs (the last-resort window that must not depend on any
resource dictionary, so its values are literal on purpose) and UI/RenderShots.cs (demo icon colours in the shot harness).

Usage:  python tools/design/check_tokens.py            list bypasses, exit 1 if there is any (the build-check gate)
        python tools/design/check_tokens.py --report   list them, always exit 0
        python tools/design/check_tokens.py --root DIR scan DIR instead of the repo's launcher project (tests)
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = REPO / "src" / "OriginLauncher.App"

# Token sources (relative to the scanned root). Hex and font-size literals are their job.
TOKEN_FILES = {
    "Theme/Colors.xaml",
    "Theme/Typography.xaml",
    "Theme/Spacing.xaml",
}
# Intentional literals (see the module doc).
ALLOWED_FILES = {
    "UI/Windows/CrashFallback.cs",
    # Screenshot harness only (the dev-shots / launcher-shots build): colours of fake demo mod icons, never shipped UI.
    "UI/RenderShots.cs",
}
SKIP_DIRS = {"obj", "bin", ".vs", "Assets"}

HEX = re.compile(r"(?<![A-Za-z0-9_&])#(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3,4})(?![0-9A-Za-z_])")
XAML_FONTSIZE = re.compile(r"""\bFontSize\s*=\s*["']\s*-?\d""")
CS_FONTSIZE = re.compile(r"\bFontSize\s*(?:=|:)\s*-?\d")
# A colour built from literal channels. Re-tinting a token (Color.FromArgb(0x26, hue.R, hue.G, hue.B)) is fine; the
# rule is about writing a colour down, so only literal RGB channels count.
CS_COLOR_CALL = re.compile(r"\bColor\.FromRgb\s*\(\s*(?:0x|\d)|\bColor\.FromArgb\s*\(\s*[^,()]+,\s*(?:0x|\d)")
CS_COLOR_CTOR = re.compile(r"\bnew\s+Color\s*\{")


def strip_xaml_comments(text: str) -> str:
    """Blank out <!-- ... --> blocks but keep every newline so line numbers stay right."""
    return re.sub(r"<!--.*?-->", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.S)


def strip_cs_comments(text: str) -> str:
    """Blank out // and /* */ comments (string-aware enough for hex scanning) keeping newlines."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if two == "//":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
        elif two == "/*":
            while i < n and text[i:i + 2] != "*/":
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
        elif c == '"':
            # string literal: keep it (hex literals live in strings), honour escapes and verbatim @"" strings
            verbatim = i > 0 and text[i - 1] == "@"
            out.append(c)
            i += 1
            while i < n:
                ch = text[i]
                out.append(ch)
                i += 1
                if verbatim:
                    if ch == '"':
                        if i < n and text[i] == '"':
                            out.append('"')
                            i += 1
                            continue
                        break
                else:
                    if ch == "\\" and i < n:
                        out.append(text[i])
                        i += 1
                    elif ch == '"':
                        break
        else:
            out.append(c)
            i += 1
    return "".join(out)


def scan_file(path: Path, rel: str) -> list[tuple[int, str, str]]:
    raw = path.read_text(encoding="utf-8-sig", errors="replace")
    is_xaml = path.suffix == ".xaml"
    text = strip_xaml_comments(raw) if is_xaml else strip_cs_comments(raw)
    hits: list[tuple[int, str, str]] = []
    for no, line in enumerate(text.splitlines(), 1):
        for m in HEX.finditer(line):
            hits.append((no, "hex colour " + m.group(0), line.strip()))
        if is_xaml:
            if XAML_FONTSIZE.search(line):
                hits.append((no, "literal FontSize", line.strip()))
        else:
            if CS_FONTSIZE.search(line):
                hits.append((no, "literal FontSize", line.strip()))
            if CS_COLOR_CALL.search(line) or CS_COLOR_CTOR.search(line):
                hits.append((no, "colour built in code", line.strip()))
    return hits


def scan(root: Path) -> list[tuple[str, int, str, str]]:
    found: list[tuple[str, int, str, str]] = []
    for path in sorted(root.rglob("*")):
        if path.suffix not in (".xaml", ".cs") or not path.is_file():
            continue
        parts = path.relative_to(root).parts
        if any(p in SKIP_DIRS for p in parts):
            continue
        rel = "/".join(parts)
        if rel in TOKEN_FILES or rel in ALLOWED_FILES:
            continue
        for no, what, src in scan_file(path, rel):
            found.append((rel, no, what, src))
    return found


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--report", action="store_true", help="list bypasses but always exit 0")
    ap.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="directory to scan (default: the launcher project)")
    args = ap.parse_args(argv)
    if not args.root.is_dir():
        print(f"check_tokens: {args.root} is not a directory", file=sys.stderr)
        return 2
    found = scan(args.root)
    for rel, no, what, src in found:
        print(f"{rel}:{no}: {what}: {src[:110]}")
    print(f"check_tokens: {len(found)} bypass(es) in {args.root.name}")
    if found and not args.report:
        print("check_tokens: FAIL - move the value into Theme/Colors.xaml, Typography.xaml or Spacing.xaml and reference the token")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
