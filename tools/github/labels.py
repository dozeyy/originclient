#!/usr/bin/env python3
"""The repo's GitHub labels, and the rules that put them on pull requests.

One place for both, used by .github/workflows/labels.yml:
  labels.py sync        create/update every label below on GitHub
  labels.py pr NUMBER   add the area/version labels matching the files PR
                        NUMBER changes (only ever ADDS — hand-set labels stay)
  labels.py match PATH...   print the labels those paths would get (testing)

Version labels ("mc 1.21.4", "staged 26.2") are generated from
src/mods/live-modules.json and src/mods/staged/, so adding or pulling a
Minecraft version never means editing a label list by hand.

Kinds of label:
  type      what the change/issue is (bug, enhancement, ...) — set by hand or
            by the issue templates ("documentation" is also automatic)
  area      which part of Origin it touches — set automatically on PRs
  mc        which shipped Minecraft build(s) it touches — automatic on PRs
  status / priority — set by hand
"""
from __future__ import annotations

import fnmatch
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# name -> (colour, description, [glob patterns that auto-apply it on PRs])
# Globs use fnmatch: "*" also crosses "/" so "src/mods/*" means "anything under".
STATIC = {
    # type — the GitHub default names are kept so existing labels update in place
    "bug":           ("d73a4a", "Something is broken", []),
    "crash":         ("b60205", "The game or launcher crashes", []),
    "enhancement":   ("a2eeef", "New feature or improvement", []),
    "polish":        ("c5def5", "Look-and-feel polish, no new behaviour", []),
    "performance":   ("fbca04", "Faster, smoother or lighter", []),
    "documentation": ("0075ca", "Docs and project notes (*.md)", ["*.md"]),
    # area
    "area: launcher":         ("1d76db", "The Windows launcher (src/OriginLauncher.App)",
                               ["src/OriginLauncher.App/*"]),
    "area: installer":        ("1d76db", "Setup.exe installer (installer/)", ["installer/*"]),
    "area: in-game mod":      ("5319e7", "The Origin Client Fabric mod (src/mods)", ["src/mods/*"]),
    "area: shared core":      ("5319e7", "Mod code shared by every version (src/mods/shared) — reaches all of them",
                               ["src/mods/shared/*"]),
    "area: release pipeline": ("0e8a16", "Builds, releases and auto-update (.github, tools/release)",
                               [".github/*", "tools/release/*", "tools/shared-sync/*",
                                "tools/github/*", "docs/RELEASING.md"]),
    "area: website":          ("0e8a16", "originclient.org (website/)", ["website/*"]),
    "area: design":           ("f9d0c4", "Design files and mockups (design/)", ["design/*"]),
    "area: tools":            ("bfdadc", "Asset and catalog generators (tools/)",
                               ["tools/boot-smoke/*", "tools/buttons/*", "tools/font-atlas/*",
                                "tools/loading-screen/*", "tools/mod-menu/*", "tools/perf-catalog/*"]),
    # status / priority — by hand
    "status: needs testing at home": ("fef2c0", "Needs a real run on Will's Windows PC (runClient / launcher)", []),
    "status: blocked":               ("e99695", "Waiting on something outside this repo", []),
    "priority: high":                ("b60205", "Players are affected now", []),
}
MC_COLOUR = "8250df"
STAGED_COLOUR = "d4c5f9"


def definitions() -> dict[str, tuple[str, str, list[str]]]:
    defs = dict(STATIC)
    live = json.loads((REPO / "src/mods/live-modules.json").read_text(encoding="utf-8"))["modules"]
    for m in live:
        defs[f"mc {m['module']}"] = (
            MC_COLOUR, f"Minecraft {m['covers']} build (src/mods/versions/{m['module']})",
            [f"src/mods/versions/{m['module']}/*"])
    staged = REPO / "src/mods/staged"
    for d in sorted(p for p in staged.iterdir() if p.is_dir()) if staged.is_dir() else []:
        defs[f"staged {d.name}"] = (
            STAGED_COLOUR, f"Unreleased Minecraft {d.name} port (src/mods/staged/{d.name})",
            [f"src/mods/staged/{d.name}/*"])
    return defs


def match(paths: list[str]) -> list[str]:
    defs = definitions()
    return [name for name, (_, _, globs) in defs.items()
            if any(fnmatch.fnmatchcase(p, g) for p in paths for g in globs)]


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], cwd=REPO, check=True,
                          capture_output=True, text=True).stdout


def upsert(names: list[str] | None = None) -> None:
    for name, (colour, desc, _) in definitions().items():
        if names is None or name in names:
            gh("label", "create", name, "--color", colour, "--description", desc, "--force")
            print(f"label ok: {name}")


def label_pr(number: str) -> None:
    files = gh("api", "--paginate", f"repos/{{owner}}/{{repo}}/pulls/{number}/files",
               "--jq", ".[].filename").split()
    wanted = match(files)
    if not wanted:
        print("No matching labels.")
        return
    existing = set(gh("label", "list", "--limit", "500", "--json", "name",
                      "--jq", ".[].name").splitlines())
    upsert([n for n in wanted if n not in existing])  # a PR can never fail on a missing label
    gh("pr", "edit", number, "--add-label", ",".join(wanted))
    print("Labelled:", ", ".join(wanted))


def main() -> int:
    cmd, rest = (sys.argv[1], sys.argv[2:]) if len(sys.argv) > 1 else ("", [])
    if cmd == "sync":
        upsert()
    elif cmd == "pr" and len(rest) == 1:
        label_pr(rest[0])
    elif cmd == "match":
        print("\n".join(match(rest)))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
