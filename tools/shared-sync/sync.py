#!/usr/bin/env python3
"""Propagate src/mods/shared/ into every mod module.

The Origin Client mod is built once per Minecraft API family
(src/mods/versions/* shipped, src/mods/staged/* WIP). Each module is a fully
standalone Gradle build, but ~75% of its source is the version-independent
core (theme, gui layout, hud, mods menu, config). That core lives ONCE in
src/mods/shared/src/... and is copied verbatim into each module by this
script. Modules stay isolated: nothing changes in any module until this
script is deliberately run.

Rules:
- A shared fix  -> edit src/mods/shared/..., run `python tools/shared-sync/sync.py`,
  then build/verify each affected module.
- A version fix -> edit that module only. If the file exists in shared/, add
  its path to the module's overrides.txt (that marks the deliberate fork and
  sync will never touch it again).
- sync only ever touches module files that exist in shared/. Version-specific
  files (most mixins, OriginScreenRenderer, fabric.mod.json, ...) are simply
  not in shared/.

Usage:
  python tools/shared-sync/sync.py            # copy shared/ into all modules
  python tools/shared-sync/sync.py --check    # verify no drift (CI gate);
                                              # exit 1 and print a diff summary
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MODS = REPO / "src" / "mods"
SHARED = MODS / "shared" / "src"


def module_dirs() -> list[Path]:
    out = []
    for parent in ("versions", "staged"):
        base = MODS / parent
        if base.is_dir():
            out.extend(sorted(p for p in base.iterdir() if (p / "src").is_dir()))
    return out


def load_overrides(module: Path) -> set[str]:
    f = module / "overrides.txt"
    if not f.is_file():
        return set()
    lines = (ln.strip() for ln in f.read_text(encoding="utf-8").splitlines())
    return {ln.replace("\\", "/") for ln in lines if ln and not ln.startswith("#")}


def shared_files() -> list[str]:
    return sorted(
        str(p.relative_to(SHARED)).replace("\\", "/")
        for p in SHARED.rglob("*")
        if p.is_file()
    )


def main() -> int:
    check = "--check" in sys.argv[1:]
    rels = shared_files()
    if not rels:
        print(f"ERROR: no files under {SHARED}", file=sys.stderr)
        return 1

    drift: list[str] = []
    copied = 0
    for module in module_dirs():
        overrides = load_overrides(module)
        unknown = overrides - set(rels)
        if unknown:
            # An override for a file shared/ doesn't have is stale - flag it
            # so overrides.txt can't rot silently.
            drift.extend(f"{module.name}: stale override (not in shared/): {u}" for u in sorted(unknown))
        for rel in rels:
            if rel in overrides:
                continue
            src = SHARED / rel
            dst = module / "src" / rel
            same = dst.is_file() and dst.read_bytes() == src.read_bytes()
            if same:
                continue
            if check:
                state = "differs" if dst.is_file() else "missing"
                drift.append(f"{module.parent.name}/{module.name}: {rel} ({state})")
            else:
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
                copied += 1

    if check:
        if drift:
            print(f"DRIFT: {len(drift)} file(s) out of sync with src/mods/shared/ "
                  f"(fix: run sync.py, or add deliberate forks to overrides.txt):")
            for d in drift:
                print("  " + d)
            return 1
        print(f"OK: {len(rels)} shared files in sync across {len(module_dirs())} modules.")
        return 0

    if drift:
        for d in drift:
            print("WARNING: " + d)
    print(f"Synced {len(rels)} shared files into {len(module_dirs())} modules ({copied} updated).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
