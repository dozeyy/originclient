#!/usr/bin/env python3
"""Fail CI when facts that must live in two places drift apart.

Each pair below exists in two files for a real reason (different languages or
runtimes), so instead of pretending there is one copy, this checks they agree:

1. Playable versions — src/mods/live-modules.json "versions" (read by the
   installer's default-version picker, .NET Framework, no access to launcher
   code) must equal the launcher's VersionManager.OriginBuilds entries, jar by jar.
2. The installer -> launcher hand-off file name — src/OriginSetup/Engine/
   Handoff.cs and the launcher's Core/Install/SetupChoices.cs.
3. Theme tokens — the installer owns its Workbench token and type files
   (src/OriginSetup/Theme/Tokens.xaml, Typography.xaml), so they are not copies of the launcher's. Both sets are
   read at run time and every shared key (Color.*, Size.Control*, Radius.*, Font.*, FontSize.*, LineHeight.*, Space.*,
   plus the PAIRED_KEYS role names) must agree, except the keys listed in KNOWN_DIVERGENCE (each with a reason; an
   entry that starts to agree must be deleted). The face (Inter) and Body/Caption sizes are always required.
4. The release feed — the installer downloads the launcher from the same
   GitHub release asset the launcher self-updates from: owner, repo, tag
   prefix and zip name in src/OriginSetup/Engine/ReleaseFeed.cs must match
   the launcher's Core/Updates/UpdateService.cs.
5. The version picker — every version in the launcher's Core/Versions/
   VersionCatalog.cs grid must be in VersionManager.OriginBuilds and vice
   versa (both C#, but hand-authored separately). A version without a build
   must never be shown (no greyed "Coming Soon" entries — Will, 2026-10-12),
   and a shipped version must never be missing from the picker.

6. The self-update hash — the launcher's UpdateService looks for the zip's
   AssetName + ".sha256" release asset and the release workflow must publish
   exactly that (both in the `gh release create` list and the rehearsal artifact).

Run from anywhere: python3 tools/release/check_consistency.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LAUNCHER = REPO / "src" / "OriginLauncher.App"
SETUP = REPO / "src" / "OriginSetup"


def origin_builds() -> dict[str, set[str]]:
    """VersionManager.OriginBuilds as {jar module: {Minecraft versions}}, commented-out entries skipped."""
    source = (LAUNCHER / "Core/Versions/VersionManager.cs").read_text(encoding="utf-8")
    builds: dict[str, set[str]] = {}
    for line in source.splitlines():
        if line.strip().startswith("//"):
            continue
        m = re.search(r'\["([^"]+)"\]\s*=\s*new\("originclient-([^"]+)\.jar"', line)
        if m:
            builds.setdefault(m.group(2), set()).add(m.group(1))
    return builds


def check_versions() -> list[str]:
    live = json.loads((REPO / "src/mods/live-modules.json").read_text(encoding="utf-8"))["modules"]
    builds = origin_builds()

    problems = []
    listed = {m["module"]: set(m.get("versions", [])) for m in live}
    for module in sorted(set(builds) | set(listed)):
        a, b = listed.get(module), builds.get(module)
        if a != b:
            problems.append(f"versions for module {module}: live-modules.json says {sorted(a or [])}, "
                            f"VersionManager.OriginBuilds says {sorted(b or [])}")
    return problems


def check_handoff() -> list[str]:
    pattern = r'FileName\s*=\s*"([^"]+)"'
    a = re.search(pattern, (SETUP / "Engine/Handoff.cs").read_text(encoding="utf-8"))
    b = re.search(pattern, (LAUNCHER / "Core/Install/SetupChoices.cs").read_text(encoding="utf-8"))
    if not a or not b or a.group(1) != b.group(1):
        return [f"hand-off file name: installer {a and a.group(1)!r} vs launcher {b and b.group(1)!r}"]
    return []


# ---- Theme token parity (typography + the tokens the two apps share) ----
# The installer keeps its own Theme/Tokens.xaml and Typography.xaml (it is a separate .NET Framework app), so the
# files cannot be one copy. Instead both sides are READ and every key defined in both is compared. Nothing is
# hard-coded here except the list of keys that are allowed to differ and why: a key that differs and is NOT listed
# fails the build, and a listed key that has started to agree fails too ("remove it"), so the list cannot rot.
# When the launcher palette moves to the Workbench set (Phase 3, unit U3.0) its Color.* entries start to agree and
# the Color. exception below must be deleted.
THEME_FAMILIES = ("Color.", "Size.Control", "Radius.", "Font.", "FontSize.", "LineHeight.", "Space.")

# prefix -> why the two files may differ today. A prefix is "in use" while at least one common key under it differs.
KNOWN_DIVERGENCE = {
    "Color.": "launcher palette is still the old monochrome set until U3.0 moves it to Workbench; the installer is already Workbench",
    "Size.Control": "installer controls are 28/32/36 (Sm/Md/Lg) in a 640 x 440 window, the launcher's 32/40/48 (Sm/Control/Lg) fill a 1080p window",
    "FontSize.Display": "installer hero title is 32, the launcher's Play-screen display is 40",
    "Space.": "installer spacing is a 4-based ladder with 12/20 steps (Space.3 = 12), the launcher's is 4/8/16/24/32/48",
}


# (launcher key, installer key): the launcher names corner radii by role, the installer by size.
PAIRED_KEYS = [
    ("Radius.Control", "Radius.Md"),   # buttons, fields, dropdowns
    ("Radius.Chip", "Radius.Xs"),      # chips and checkbox corners
    ("Radius.Bar", "Radius.Track"),    # progress and slider rails
]


def _theme_values(paths: list[Path]) -> dict[str, str]:
    """Every `x:Key` in the given XAML files -> its value (element text, Color= attribute, or a Style's setters),
    whitespace-collapsed, with the assembly name in font paths normalised so the two apps can match."""
    out: dict[str, str] = {}
    for path in paths:
        text = re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8"), flags=re.S)
        for m in re.finditer(r'<([\w:.]+)\s+([^<>]*?\bx:Key="([^"]+)"[^<>]*?)(?:/>|>(.*?)</\1>)', text, flags=re.S):
            attrs, key, body = m.group(2), m.group(3), m.group(4)
            if body is None or not body.strip():
                color = re.search(r'\bColor="([^"]*)"', attrs)
                body = color.group(1) if color else re.sub(r'x:Key="[^"]*"', "", attrs)
            value = re.sub(r"\s+", " ", body).strip()
            out[key] = re.sub(r"/[A-Za-z.]+;component/", "/<assembly>;component/", value)
    return out


def check_theme_parity() -> list[str]:
    launcher = _theme_values([LAUNCHER / "Theme" / n for n in ("Colors.xaml", "Spacing.xaml", "Typography.xaml")])
    setup = _theme_values([SETUP / "Theme" / n for n in ("Tokens.xaml", "Typography.xaml")])
    problems: list[str] = []
    common = sorted(k for k in set(launcher) & set(setup) if k.startswith(THEME_FAMILIES))
    if len(common) < 10:  # the parser lost the files: refuse to pass on an empty comparison
        return [f"theme parity found only {len(common)} shared keys; the XAML layout changed, update check_theme_parity"]

    for key in ("Font.Primary", "FontSize.Body", "FontSize.Caption"):
        if key not in common:
            problems.append(f"{key} must be defined in both launcher and installer theme files")
    if "#Inter" not in launcher.get("Font.Primary", "") or "#Inter" not in setup.get("Font.Primary", ""):
        problems.append(f"Font.Primary must be Inter in both (launcher {launcher.get('Font.Primary')!r}, installer {setup.get('Font.Primary')!r})")

    differing = [k for k in common if launcher[k] != setup[k]]
    for key in differing:
        if not any(key.startswith(prefix) for prefix in KNOWN_DIVERGENCE):
            problems.append(f"{key}: launcher {launcher[key]!r} vs installer {setup[key]!r} "
                            f"(the two apps share this token; make them agree, or list it in KNOWN_DIVERGENCE with the reason)")
    for prefix, why in KNOWN_DIVERGENCE.items():
        under = [k for k in common if k.startswith(prefix)]
        if under and not any(k in differing for k in under):
            problems.append(f"KNOWN_DIVERGENCE {prefix!r} ({why}) now agrees in every shared key; delete the entry")

    # Same job under a different name: launcher key -> installer key. The names are listed, the values are read.
    for lk, sk in PAIRED_KEYS:
        if lk not in launcher or sk not in setup:
            problems.append(f"PAIRED_KEYS {lk} <-> {sk}: one of them no longer exists, update the pair")
        elif launcher[lk] != setup[sk]:
            problems.append(f"{lk} (launcher {launcher[lk]!r}) vs {sk} (installer {setup[sk]!r}) must agree")
    return problems


def check_release_feed() -> list[str]:
    def consts(path: Path) -> dict[str, str]:
        return dict(re.findall(r'const string (Owner|Repo|TagPrefix|AssetName)\s*=\s*"([^"]*)"',
                               path.read_text(encoding="utf-8")))
    a = consts(SETUP / "Engine/ReleaseFeed.cs")
    b = consts(LAUNCHER / "Core/Updates/UpdateService.cs")
    names = ("Owner", "Repo", "TagPrefix", "AssetName")
    if any(n not in a or n not in b for n in names):
        return [f"release feed constants not found (installer {sorted(a)}, launcher {sorted(b)})"]
    return [f"release feed {n}: installer {a[n]!r} vs launcher {b[n]!r}" for n in names if a[n] != b[n]]


def check_update_hash() -> list[str]:
    svc = (LAUNCHER / "Core/Updates/UpdateService.cs").read_text(encoding="utf-8")
    wf = (REPO / ".github/workflows/launcher-release.yml").read_text(encoding="utf-8")
    name = re.search(r'const string AssetName\s*=\s*"([^"]*)"', svc)
    if not name or not re.search(r'const string HashAssetName\s*=\s*AssetName\s*\+\s*"\.sha256"', svc):
        return ["UpdateService no longer derives HashAssetName as AssetName + \".sha256\" (update check_update_hash)"]
    hash_name = name.group(1) + ".sha256"
    n = len(re.findall(r"^\s+" + re.escape(hash_name) + r"\s*$", wf, re.M))
    return [] if n >= 2 else [f"launcher-release.yml must list {hash_name} in the release and the rehearsal artifact (found {n}x)"]


def check_picker() -> list[str]:
    # Each card is Group("key", "title", "tagline", "description", "ver", "ver", ...):
    # the versions are every string literal after the first four.
    source = (LAUNCHER / "Core/Versions/VersionCatalog.cs").read_text(encoding="utf-8-sig")
    code = "\n".join(l for l in source.splitlines() if not l.strip().startswith("//"))
    body = code[code.index("Build() =>"):]
    picker: list[str] = []
    for call in re.findall(r'\bGroup\((.*?)\),\s*$', body, flags=re.S | re.M):
        picker += re.findall(r'"((?:[^"\\]|\\.)*)"', call)[4:]
    built = set().union(*origin_builds().values())
    if not picker:
        return ["version picker: no Group(...) versions found in VersionCatalog.cs (did its shape change?)"]
    problems = [f"version picker lists {v} twice" for v in sorted({v for v in picker if picker.count(v) > 1})]
    problems += [f"version picker shows {v}, which has no VersionManager.OriginBuilds entry "
                 f"(an unplayable version must not appear in the picker)" for v in picker if v not in built]
    problems += [f"VersionManager.OriginBuilds ships {v}, but VersionCatalog's picker does not list it"
                 for v in sorted(built - set(picker))]
    return problems


def main() -> int:
    problems = (check_versions() + check_handoff() + check_theme_parity() + check_release_feed()
                + check_update_hash() + check_picker())
    for p in problems:
        print(f"::error::{p}")
    if not problems:
        print("OK: version list, version picker, hand-off file name, theme tokens, release feed and update hash agree "
              "across launcher and installer.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
