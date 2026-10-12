#!/usr/bin/env python3
"""Decide which Origin Client mod modules a CI run must build.

Used by .github/workflows/build-check.yml (--all: build every live module) and
.github/workflows/launcher-release.yml (--release TAG: build only what changed
since the previous PUBLISHED release, reuse the rest from that release's zip).

Why this exists: a release used to compile all ~16 modules one after another
on a single runner (12-40 min). The workflows now build in parallel, one job
per module, and this script hands them the list. The live-module list itself
is src/mods/live-modules.json — the one place a version is added or removed.

Reuse rule (unchanged from the old in-YAML PowerShell, never skip on a guess):
a module is REUSED only if
  - a previous published release exists, AND
  - the pipeline/sync tooling is byte-identical to that release, AND
  - the module directory existed in that release and is byte-identical, AND
  - no src/mods/shared/ file it consumes changed (files listed in its
    overrides.txt are its own forks; .no-shared-sync modules consume none), AND
  - its jar is actually inside that release's OriginLauncher-win-x64.zip.
Anything else -> build fresh. Any error in the reuse path -> build everything.

The previous release is taken from GitHub Releases, not from git tags: a tag
whose release run failed (e.g. launcher-v1.0.33) has no zip to reuse from.

Outputs (written to $GITHUB_OUTPUT when set, else printed):
  build     JSON array of {"module", "covers", "java"} — the build matrix
  prev_tag  the previous published release tag ("" if none)
Reused jars land in <reuse-dir>/<module>/originclient-<module>-reused.jar,
the same <module>/<jar> layout the build jobs upload, so the release job
treats both identically. The "-reused" name still matches each module's
csproj glob (originclient-<module>-*.jar / originclient-*.jar).
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LIVE_MODULES = REPO / "src" / "mods" / "live-modules.json"
TAG_PREFIX = "launcher-v"
ZIP_ASSET = "OriginLauncher-win-x64.zip"

# A change to any of these since the previous release means "unaffected" may
# not mean what it used to -> rebuild every module.
PIPELINE_FILES = [
    ".github/workflows/launcher-release.yml",
    ".github/workflows/build-mods.yml",
    "tools/shared-sync/sync.py",
    "tools/release/plan.py",
]
SHARED_PREFIX = "src/mods/shared/src/"


def load_modules() -> list[dict]:
    return json.loads(LIVE_MODULES.read_text(encoding="utf-8"))["modules"]


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=REPO, check=True,
                          capture_output=True, text=True).stdout


def tag_version(tag: str) -> tuple[int, ...]:
    return tuple(int(p) for p in tag[len(TAG_PREFIX):].split("."))


def previous_release(current_tag: str) -> str:
    """Highest published launcher-v release strictly below current_tag.
    current_tag "next" (a rehearsal) means: the latest published release."""
    raw = subprocess.run(
        ["gh", "release", "list", "--limit", "200",
         "--json", "tagName,isDraft,isPrerelease"],
        cwd=REPO, check=True, capture_output=True, text=True).stdout
    tags = [r["tagName"] for r in json.loads(raw)
            if not r["isDraft"] and not r["isPrerelease"]
            and r["tagName"].startswith(TAG_PREFIX)]
    if current_tag == "next":
        older = tags
    else:
        current = tag_version(current_tag)
        older = [t for t in tags if tag_version(t) < current]
    return max(older, key=tag_version) if older else ""


def exists_at(ref: str, path: str) -> bool:
    return subprocess.run(["git", "cat-file", "-e", f"{ref}:{path}"],
                          cwd=REPO, capture_output=True).returncode == 0


def overrides_at(ref: str, module_rel: str) -> set[str]:
    """The module's overrides.txt as of `ref` (paths relative to its src/)."""
    if not exists_at(ref, f"{module_rel}/overrides.txt"):
        return set()
    lines = (ln.strip() for ln in git("show", f"{ref}:{module_rel}/overrides.txt").splitlines())
    return {ln.replace("\\", "/") for ln in lines if ln and not ln.startswith("#")}


def content_changes(base: str, head: str, path: str) -> list[str]:
    """Files under `path` whose CONTENT differs between base and head.
    A permission-only change (e.g. gradlew gaining its executable bit) keeps
    the same blob and changes no jar, so it is ignored."""
    out = []
    for line in git("diff", "--raw", "--no-renames", "--no-abbrev", base, head, "--", path).splitlines():
        meta, _, name = line.partition("\t")
        _, _, old_blob, new_blob, _ = meta.split()
        if old_blob != new_blob:
            out.append(name)
    return out


def changed_since(base: str, modules: list[str], head: str = "HEAD",
                  check_pipeline: bool = True) -> dict[str, str]:
    """module -> why its jar differs from `base` ("" = unchanged, reusable).
    Reads every rule file at `head`, so it works for past releases too
    (tools/release/notes.py uses it to list the versions each release touched)."""
    if check_pipeline and git("diff", "--name-only", base, head, "--", *PIPELINE_FILES).split():
        return {m: "build pipeline changed" for m in modules}

    changed = content_changes(base, head, "src/mods")
    shared = [f[len(SHARED_PREFIX):] for f in changed if f.startswith(SHARED_PREFIX)]

    out = {}
    for name in modules:
        rel = f"src/mods/versions/{name}"
        if not exists_at(base, rel):
            out[name] = "new since last release"
        elif any(f.startswith(rel + "/") for f in changed):
            out[name] = "its own files changed"
        elif (shared and not exists_at(head, f"{rel}/.no-shared-sync")
              and any(f not in overrides_at(head, rel) for f in shared)):
            out[name] = "shared core change reaches it"
        else:
            out[name] = ""
    return out


def fetch_reused(prev_tag: str, names: list[str], reuse_dir: Path) -> list[str]:
    """Copy each module's jar out of the previous release zip. Returns the
    names that could NOT be reused (missing from the zip) -> must be built."""
    missing = []
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["gh", "release", "download", prev_tag, "-p", ZIP_ASSET,
                        "-D", tmp, "--clobber"], cwd=REPO, check=True)
        with zipfile.ZipFile(Path(tmp) / ZIP_ASSET) as z:
            entries = {n.replace("\\", "/"): n for n in z.namelist()}
            for name in names:
                entry = entries.get(f"Bundled/OriginClient/originclient-{name}.jar")
                if entry is None:
                    missing.append(name)
                    continue
                dest = reuse_dir / name / f"originclient-{name}-reused.jar"
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(z.read(entry))
    return missing


def emit(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")
    else:
        print(f"{key}={value}")


def summary(lines: list[str]) -> None:
    text = "\n".join(lines) + "\n"
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text)
    print(text)


def main() -> int:
    ap = argparse.ArgumentParser()
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--all", action="store_true", help="build every live module")
    mode.add_argument("--release", metavar="TAG",
                      help='plan the release for TAG ("next" = rehearse the next release)')
    ap.add_argument("--reuse-dir", type=Path, help="where reused jars are written (--release)")
    ap.add_argument("--base", help="diff against this ref instead of asking GitHub "
                                   "(local testing; skips the jar download)")
    ap.add_argument("--head", default="HEAD", help="local testing: plan as of this ref")
    args = ap.parse_args()
    if args.release and args.base is None and args.reuse_dir is None:
        ap.error("--release needs --reuse-dir")

    modules = load_modules()
    reasons = {m["module"]: "full build" for m in modules}
    prev = ""

    if args.release:
        try:
            prev = args.base if args.base is not None else previous_release(args.release)
            if prev:
                reasons = changed_since(prev, [m["module"] for m in modules], args.head)
                reusable = [n for n, r in reasons.items() if not r]
                if reusable and args.base is None:
                    for name in fetch_reused(prev, reusable, args.reuse_dir):
                        reasons[name] = f"jar missing from {prev}"
            else:
                reasons = {n: "first release" for n in reasons}
        except Exception as e:  # never ship on a guess: fall back to building all
            print(f"::warning::Release planning failed ({e}); building every module.")
            if args.reuse_dir:  # drop half-fetched jars so no module ships twice
                shutil.rmtree(args.reuse_dir, ignore_errors=True)
            reasons = {m["module"]: "planning failed, full build" for m in modules}

    # "java" = the JDK the module's Gradle build runs on (26.x needs 25; every 1.x
    # module cross-compiles from 21 via options.release).
    build = [{"module": m["module"], "covers": m["covers"], "java": m.get("java", "21")}
             for m in modules if reasons[m["module"]]]
    emit("build", json.dumps(build, separators=(",", ":")))
    emit("prev_tag", prev)

    rows = ["| Minecraft | Module | Action |", "|---|---|---|"]
    for m in modules:
        r = reasons[m["module"]]
        rows.append(f"| {m['covers']} | `{m['module']}` | "
                    + (f"build ({r})" if r else f"reuse jar from {prev}") + " |")
    summary([f"### Mod build plan ({len(build)} to build, "
             f"{len(modules) - len(build)} reused)", ""] + rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
