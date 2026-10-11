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


def check_versions() -> list[str]:
    live = json.loads((REPO / "src/mods/live-modules.json").read_text(encoding="utf-8"))["modules"]
    source = (LAUNCHER / "Core/Versions/VersionManager.cs").read_text(encoding="utf-8")
    builds: dict[str, set[str]] = {}
    for line in source.splitlines():
        if line.strip().startswith("//"):
            continue
        m = re.search(r'\["([^"]+)"\]\s*=\s*new\("originclient-([^"]+)\.jar"', line)
        if m:
            builds.setdefault(m.group(2), set()).add(m.group(1))

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


def main() -> int:
    problems = check_versions() + check_handoff() + check_typography() + check_release_feed()
    for p in problems:
        print(f"::error::{p}")
    if not problems:
        print("OK: version list, hand-off file name, typography and release feed agree across launcher and installer.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
