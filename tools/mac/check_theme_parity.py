"""Theme parity: does the Mac launcher carry every design token and style the Windows launcher has?

The Windows launcher's tokens live in src/OriginLauncher.App/Theme/*.xaml (WPF); the Mac launcher's twins live in
src/OriginLauncher.Mac/Theme/*.axaml (Avalonia) under the SAME keys, so a page ported from one to the other can use
the same names. This lists, per Windows theme file:
  - keys with no Mac twin (a style or token the Mac port has not reached yet),
  - plain values that differ (colours, sizes, spacings, radii, durations), after normalising the two dialects
    (WPF Duration vs Avalonia TimeSpan, #AARRGGBB case, spaces in thicknesses).
Keys the Mac has on purpose and Windows does not (macOS-only layout) are listed in MAC_ONLY.

Informational: always exits 0. CI (the Mac launcher workflow) prints it into the job summary.
Usage: python tools/mac/check_theme_parity.py [--markdown]
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
WIN_THEME = ROOT / "src" / "OriginLauncher.App" / "Theme"
MAC_THEME = ROOT / "src" / "OriginLauncher.Mac" / "Theme"

# On the Mac only: the bar's insets around macOS's traffic lights, and the ghost button's resting fill (Avalonia's
# templates need an explicit transparent brush where WPF falls back to null).
MAC_ONLY = {"Brush.GhostRest", "Margin.TopBarBrand", "Margin.TopBarActions",
            # Avalonia Fluent's own ComboBox-popup keys, re-pointed at Origin tokens inside Input.ComboBox
            # (a /template/ style can't reach the popup); WPF styles the popup in its template instead.
            "ComboBoxDropDownBackground", "ComboBoxDropDownBorderBrush", "ComboBoxDropdownBorderPadding",
            "ComboBoxDropdownBorderThickness", "OverlayCornerRadius"}

# Defined in code on the Mac instead of in a theme file (App.axaml.cs loads the two logo files as resources).
MAC_IN_CODE = {"Brand.Logo", "Brand.Wordmark"}

KEYED = re.compile(r'<(?P<tag>[A-Za-z:]+)\s+x:Key="(?P<key>[^"]+)"(?P<attrs>[^>]*?)(?:/>|>(?P<body>[^<]*)<)', re.S)
# Value-carrying tags whose content can be compared directly.
PLAIN = {"Color", "system:Double", "x:Double", "sys:Double", "Thickness", "CornerRadius", "Duration",
         "sys:TimeSpan", "x:TimeSpan"}  # FontFamily is left out: the font URIs differ per platform by design


def keys(folder: pathlib.Path, pattern: str) -> dict[str, dict[str, str]]:
    """file stem -> {key: comparable value ('' when not a plain value)}"""
    out: dict[str, dict[str, str]] = {}
    for f in sorted(folder.glob(pattern)):
        text = re.sub(r"<!--.*?-->", "", f.read_text(encoding="utf-8"), flags=re.S)
        found: dict[str, str] = {}
        for m in KEYED.finditer(text):
            if m.group("key").startswith("{"):
                continue  # an implicit style ({x:Type ToolTip}): matched by type, not by name
            found[m.group("key")] = value(m.group("tag"), m.group("attrs") or "", m.group("body") or "")
        out[f.stem] = found
    return out


def value(tag: str, attrs: str, body: str) -> str:
    if tag == "SolidColorBrush":
        color = re.search(r'Color="([^"]+)"', attrs)
        opacity = re.search(r'Opacity="([^"]+)"', attrs)
        return norm((color.group(1) if color else "") + (f"@{opacity.group(1)}" if opacity else ""))
    if tag in PLAIN:
        return norm(body)
    return ""


def norm(v: str) -> str:
    v = re.sub(r"\s+", "", v).lower()
    # Durations / TimeSpans: 0:0:0.12, 00:00:00.120 -> seconds.
    m = re.fullmatch(r"(\d+):(\d+):(\d+(?:\.\d+)?)", v)
    if m:
        h, mi, s = m.groups()
        return f"{int(h) * 3600 + int(mi) * 60 + float(s):g}s"
    # Thickness / CornerRadius written as one number or two numbers expand to four.
    parts = v.split(",")
    if all(re.fullmatch(r"-?\d+(\.\d+)?", p) for p in parts) and len(parts) in (2, 4) and len(parts) > 1:
        if len(parts) == 2:
            parts = [parts[0], parts[1], parts[0], parts[1]]
        return ",".join(f"{float(p):g}" for p in parts)
    if re.fullmatch(r"-?\d+(\.\d+)?", v):
        return f"{float(v):g}"
    return v


def main() -> int:
    markdown = "--markdown" in sys.argv
    win = keys(WIN_THEME, "*.xaml")
    mac = keys(MAC_THEME, "*.axaml")
    mac_all: dict[str, str] = {}
    for d in mac.values():
        mac_all.update(d)
    win_all = {k for d in win.values() for k in d}

    total = sum(len(d) for d in win.values())
    missing_total = 0
    lines: list[str] = []
    for stem, found in win.items():
        missing = [k for k in found if k not in mac_all and k not in MAC_IN_CODE]
        differ = [(k, v, mac_all[k]) for k, v in found.items() if k in mac_all and v and mac_all[k] and v != mac_all[k]]
        missing_total += len(missing)
        status = "complete" if not missing and not differ else f"{len(missing)} missing, {len(differ)} differ"
        lines.append(f"{'### ' if markdown else ''}{stem}.xaml: {len(found)} keys, {status}")
        if missing:
            lines.append(("- missing: " if markdown else "  missing: ") + ", ".join(missing))
        for k, a, b in differ:
            lines.append(("- " if markdown else "  ") + f"differs: {k} Windows={a} Mac={b}")
    extra = sorted(k for k in mac_all if k not in win_all and k not in MAC_ONLY)
    if extra:
        lines.append(("- " if markdown else "") + "Mac keys with no Windows twin (not on the Mac-only list): " + ", ".join(extra))
    head = f"Theme parity: {total - missing_total} of {total} Windows theme keys have a Mac twin."
    print(("## " if markdown else "") + head)
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
