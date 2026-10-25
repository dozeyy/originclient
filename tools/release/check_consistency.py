#!/usr/bin/env python3
"""Fail CI when facts that must live in two places drift apart.

Each pair below exists in two files for a real reason (different languages or
runtimes), so instead of pretending there is one copy, this checks they agree:

1. Playable versions — src/mods/live-modules.json "versions" (read by the
   installer's default-version picker, .NET Framework, no access to launcher
   code) must equal the launcher's VersionManager.OriginBuilds entries, jar by jar.
2. The installer -> launcher hand-off file name — src/OriginSetup/Engine/
   Handoff.cs and the launcher's Core/Install/SetupChoices.cs.
3. Typography — src/OriginSetup/Theme/Typography.xaml is the launcher's
   Theme/Typography.xaml with only the font resource path changed (a WPF
   resource path names its assembly).
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


def check_typography() -> list[str]:
    def body(path: Path) -> list[str]:
        lines = path.read_text(encoding="utf-8").splitlines()
        return [re.sub(r"/[A-Za-z.]+;component/", "/<assembly>;component/", l)
                for l in lines if not l.strip().startswith("<!--") and "MIRROR" not in l]
    a, b = body(LAUNCHER / "Theme/Typography.xaml"), body(SETUP / "Theme/Typography.xaml")
    if a != b:
        return ["src/OriginSetup/Theme/Typography.xaml differs from the launcher's Theme/Typography.xaml "
                "(beyond the font path) — copy the launcher's file over and change only the assembly name"]
    return []


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
    problems = (check_versions() + check_handoff() + check_typography() + check_release_feed()
                + check_update_hash() + check_picker())
    for p in problems:
        print(f"::error::{p}")
    if not problems:
        print("OK: version list, version picker, hand-off file name, typography, release feed and update hash agree "
              "across launcher and installer.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
