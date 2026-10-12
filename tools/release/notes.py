#!/usr/bin/env python3
"""Write the player-facing notes for an Origin Launcher release.

Every GitHub release used to say only "Automated build of <sha>". This turns
the commits since the previous published release into a readable changelog:

  ## What's new / ## Fixes / ## Behind the scenes   (from commit titles)
  ## Minecraft versions updated                      (which mod builds changed)
  install/update footer + full compare link

Used two ways:
  notes.py --tag launcher-v1.0.40 --prev launcher-v1.0.39 --out notes.md
      launcher-release.yml, for the release being published (a rehearsal
      passes --head <sha> instead of --tag: notes for "what would ship").
  notes.py --backfill [--dry-run]
      relabel-releases.yml (manual): rewrites the title + notes of EVERY
      published release with the same format, so old releases read the same.

The launcher's updater only reads tag_name and asset names from a release,
never its title or body, so rewriting notes can't affect installed launchers.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import plan  # noqa: E402  (same folder; shares the module-change rules)

REPO_SLUG = os.environ.get("GITHUB_REPOSITORY", "dozeyy/originclient")

# Commit-title prefixes that are about the repo, not the player's game.
BEHIND_THE_SCENES = {
    "ci", "docs", "doc", "chore", "build", "refactor", "test", "tests", "style",
    "memory", "claude.md", "release", "website", "tools", "tooling",
}
# Mid-work checkpoints ("WIP: ...") — the finished commit that follows says
# what shipped, so these are left out of the notes entirely.
CHECKPOINT = {"wip"}
FIX_WORDS = {"fix", "fixes", "hotfix", "bugfix"}
NEW_WORDS = {"feat", "feature", "perf"}
# "<label>: <text>" where label is a short prefix — a conventional type
# ("fix", "feat(ui)!"), a version ("1.21.11"), or a feature ("Tab Editor").
PREFIXED = re.compile(r"^(?P<label>[^:]{1,40}?)(?:\([^)]*\))?!?:\s+(?P<rest>.+)$")
STARTS_WITH_FIX = re.compile(r"(?i)^fix(es|ed)?\b")


def title_for(tag: str) -> str:
    return f"Origin Launcher {tag[len(plan.TAG_PREFIX):]}"


def classify(subject: str) -> tuple[str, str]:
    """(section, cleaned title) for one commit title."""
    m = PREFIXED.match(subject)
    if not m:
        return ("fixes" if STARTS_WITH_FIX.match(subject) else "new"), subject
    label, rest = m.group("label").strip(), m.group("rest")
    kind = label.lower()
    if kind in CHECKPOINT:
        return "skip", subject
    if kind in BEHIND_THE_SCENES or kind.split()[0] in BEHIND_THE_SCENES:
        return "behind", subject
    if kind in FIX_WORDS:
        return "fixes", rest[:1].upper() + rest[1:]
    if kind in NEW_WORDS:
        return "new", rest[:1].upper() + rest[1:]
    # A scope label such as "1.21.11:" or "Tab Editor:" — keep it, it tells
    # the player which version/feature the line is about.
    if STARTS_WITH_FIX.match(rest) or STARTS_WITH_FIX.match(label) or re.search(r"(?i)\bfix$", label):
        return "fixes", subject
    return "new", subject


def commit_titles(prev: str, head: str) -> list[str]:
    return [s for s in plan.git("log", "--no-merges", "--format=%s", f"{prev}..{head}").splitlines() if s.strip()]


def shipped_modules(tag: str) -> list[str] | None:
    """Mod modules that release `tag` actually built and bundled.
    From live-modules.json when the tag has it; older tags listed them as
    working-directory lines in launcher-release.yml. None = can't tell
    (the earliest releases predate src/mods/versions/)."""
    if plan.exists_at(tag, "src/mods/live-modules.json"):
        return [m["module"] for m in json.loads(plan.git("show", f"{tag}:src/mods/live-modules.json"))["modules"]]
    wf = ".github/workflows/launcher-release.yml"
    if not plan.exists_at(tag, "src/mods/versions") or not plan.exists_at(tag, wf):
        return None
    found = re.findall(r"(?m)^\s*working-directory:\s*src/mods/versions/(\S+)\s*$", plan.git("show", f"{tag}:{wf}"))
    return list(dict.fromkeys(found)) or None


def versions_updated(prev: str, tag: str) -> list[str] | None:
    """Human labels of the shipped mod builds whose code changed in this release."""
    shipped = shipped_modules(tag)
    if shipped is None:
        return None
    covers = {m["module"]: m["covers"] for m in plan.load_modules()}
    reasons = plan.changed_since(prev, shipped, tag, check_pipeline=False)
    labels = [covers.get(name, name) for name, why in reasons.items() if why]
    return sorted(labels, key=lambda s: tuple(int(p) for p in re.findall(r"\d+", s)[:3]), reverse=True)


def render(head: str, prev: str) -> str:
    """Notes for the release at git ref `head` (a tag, or a sha in a rehearsal)."""
    if not prev:  # the first release: its "changes" are the whole project
        return ("First public release of Origin Launcher.\n\n"
                "Download `OriginLauncher-Setup.exe` below and run it.\n")
    sections = {"new": [], "fixes": [], "behind": [], "skip": []}
    for subject in commit_titles(prev, head):
        section, text = classify(subject)
        sections[section].append(text)

    out = []
    for key, heading in (("new", "What's new"), ("fixes", "Fixes"), ("behind", "Behind the scenes")):
        if sections[key]:
            out += [f"## {heading}", *(f"- {t}" for t in sections[key]), ""]
    if not any(sections[k] for k in ("new", "fixes", "behind")):
        out += ["Re-release of the previous build with no code changes.", ""]

    updated = versions_updated(prev, head)
    if updated is not None:
        out += ["## Minecraft versions updated"]
        out += ([", ".join(updated)] if updated else
                ["None — the in-game Origin Client is unchanged in this release."])
        out += [""]

    out += [
        "---",
        "**New to Origin?** Download `OriginLauncher-Setup.exe` below and run it.",
        "**Already installed?** Your launcher updates itself — click the download "
        "badge in the corner when it appears.",
        "",
    ]
    out.append(f"Full list of changes: https://github.com/{REPO_SLUG}/compare/{prev}...{head}")
    return "\n".join(out) + "\n"


def published_releases() -> list[str]:
    raw = subprocess.run(["gh", "release", "list", "--limit", "200",
                          "--json", "tagName,isDraft,isPrerelease"],
                         cwd=plan.REPO, check=True, capture_output=True, text=True).stdout
    tags = [r["tagName"] for r in json.loads(raw)
            if not r["isDraft"] and not r["isPrerelease"]
            and r["tagName"].startswith(plan.TAG_PREFIX)]
    return sorted(tags, key=plan.tag_version)


def backfill(dry_run: bool) -> int:
    tags = published_releases()
    for i, tag in enumerate(tags):
        prev = tags[i - 1] if i else ""
        body = render(tag, prev)
        if dry_run:
            print(f"===== {title_for(tag)} ({tag}, since {prev or 'start'}) =====\n{body}")
            continue
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
            f.write(body)
        subprocess.run(["gh", "release", "edit", tag, "--title", title_for(tag),
                        "--notes-file", f.name], cwd=plan.REPO, check=True)
        os.unlink(f.name)
        print(f"Relabelled {tag}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", help="release being published")
    ap.add_argument("--head", help="describe this ref instead of a tag (rehearsal)")
    ap.add_argument("--prev", default="", help="previous published release tag")
    ap.add_argument("--out", type=Path, help="write notes here (default: stdout)")
    ap.add_argument("--backfill", action="store_true", help="rewrite every published release")
    ap.add_argument("--dry-run", action="store_true", help="with --backfill: print, don't edit")
    args = ap.parse_args()

    if args.backfill:
        return backfill(args.dry_run)
    head = args.head or args.tag
    if not head:
        ap.error("--tag or --head is required (or use --backfill)")
    body = render(head, args.prev)
    if args.out:
        args.out.write_text(body, encoding="utf-8")
    else:
        sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
