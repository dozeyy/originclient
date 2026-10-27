#!/usr/bin/env python3
"""Write the player-facing notes for an Origin Launcher release.

Every GitHub release used to say only "Automated build of <sha>". This turns
the commits since the previous published release into a readable changelog:

  ## What's new / ## Fixes   (from commit titles; dev/CI/test/docs work is left out)
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
import difflib
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


# --- what never reaches the player -----------------------------------------
# A commit is left out of the notes when its TITLE says it is dev/CI/test/docs
# work, or when every file it touched is outside the shipped product. The
# notes are the player's changelog; the full commit list is one click away
# (the compare link in the footer).
NOISE_TITLE = re.compile(
    r"(?i)(^merge\b|^revert\b|\bdev[- ]?only\b|\bdev[- ]tool\b|\bci[- ]?only\b|\btest[- ]?only\b"
    r"|\blauncher[- ]shots?\b|\bdev[- ]?shots?\b|\bframe[- ]log\b|\brender-shots\b"
    r"|\bboot-smoke\b|\bcheck_consistency\b|\bscale-checks\b"
    r"|^review\b|\breview fix(?:es)?\s*:|\breview round \d|\bfollow-?ups?\b|\bpolish from\b|\binterface skeleton\b"
    r"|\bseams?\b|\.xaml\b|\bper the\b|\bGradle\b|\bJDK\b|\bfixtures?\b|\bwiring\b|\balloc\b|\bflush on throw\b)")
# Code identifiers are only recognised by shapes no product/feature name has:
# a dotted or underscored name ("SettingsDelta.AddProfiles", "check_consistency"),
# a call "name()", or a C# interface name ("IImportCloseHost"). Plain CamelCase
# is NOT a signal: Y2K, ModMenu, FreeLook, macOS and iPhone are player words.
CODE_WORD = re.compile(r"\b(?:[A-Za-z]+\.[A-Za-z]\w*|\w+_\w+|\w+\(\)|I[A-Z][a-z]+[A-Z]\w*)")
# Files that never ship to a player. A commit touching only these is dropped.
# website/ and services/ are deliberately in the list: the site and the store
# service deploy on their own (deploy-website.yml, the Worker), not with the
# launcher, so they are not part of this release's changes.
NOISE_PATH = re.compile(
    r"^(\.github/|\.claude/|docs/|tools/|website/|services/)|(^|/)[^/]*[Tt]ests?/|[^/]*\.Tests/|\.md$|(^|/)(LICENSE|\.gitignore)$")
# Leading "1.21.1:", "1.20-26.3:", "1.21.6, 1.21.8:", "26.3:" scope.
VERSION_LABEL = re.compile(r"^(?:\d+(?:\.\d+)+(?:\s*[-,]\s*|\s+and\s+)?)+:\s+")
# Trailing "(1.17.1, 1.16.5)", "(8f5c7ff3 port)", "(WIP pending review)".
TRAIL_PAREN = re.compile(r"\s*\((?:[\d.,\s/-]+|[0-9a-f]{7,8}\b[^)]*|WIP\b[^)]*)\)\s*$")
SCOPE_LABELS = {"launcher", "setup", "installer"}


def is_noise_title(subject: str) -> bool:
    if NOISE_TITLE.search(subject):
        return True
    return bool(CODE_WORD.search(subject))


def touches_only_noise(files: list[str]) -> bool:
    return bool(files) and all(NOISE_PATH.search(f) for f in files)


def tidy(text: str) -> str:
    """One commit title -> a plain sentence: no WIP/hash/version tails, capitalised."""
    prev = None
    while prev != text:
        prev, text = text, TRAIL_PAREN.sub("", text).strip()
    text = text.rstrip(".").strip()
    return text[:1].upper() + text[1:]


def classify(subject: str) -> tuple[str, str]:
    """(section, cleaned title) for one commit title.
    Sections: new | fixes | skip (never shown)."""
    subject = tidy(subject.strip())  # drop "(WIP pending review)" etc. before judging the words
    if is_noise_title(subject):
        return "skip", subject
    m = PREFIXED.match(subject)
    if not m:
        return ("fixes" if STARTS_WITH_FIX.match(subject) else "new"), tidy(subject)
    label, rest = m.group("label").strip(), m.group("rest")
    kind = label.lower()
    if kind in CHECKPOINT or kind in BEHIND_THE_SCENES or kind.split()[0] in BEHIND_THE_SCENES:
        return "skip", subject
    section = "new"
    if kind in FIX_WORDS or STARTS_WITH_FIX.match(rest) or STARTS_WITH_FIX.match(label) \
            or re.search(r"(?i)\bfix$", label):
        section = "fixes"
    if kind in FIX_WORDS or kind in NEW_WORDS or kind in SCOPE_LABELS:
        return section, tidy(rest)
    # A version/feature scope ("1.21.11:", "Tab Editor:") tells the player what the
    # line is about; it is kept here and collapsed by dedupe when many versions share a line.
    return section, tidy(subject)


def _core(text: str) -> str:
    """Comparison key: the sentence without its version scope, lower-cased."""
    return re.sub(r"[^a-z0-9 ]+", " ", VERSION_LABEL.sub("", text).lower()).strip()


def dedupe(lines: list[str]) -> list[str]:
    """Collapse the same change listed once per version ("1.19.2: X", "1.19.3: X"...)
    into a single line without a version scope. Near-identical titles (>= 0.8
    similar) merge too. First-seen order is kept."""
    groups: list[list[str]] = []
    for line in lines:
        key = _core(line)
        for g in groups:
            gk = _core(g[0])
            if key == gk or difflib.SequenceMatcher(None, key, gk).ratio() >= 0.8:
                g.append(line)
                break
        else:
            groups.append([line])
    return [tidy(g[0] if len(g) == 1 else VERSION_LABEL.sub("", g[0])) for g in groups]


def drop_reverts(records: list[tuple[str, list[str]]]) -> list[tuple[str, list[str]]]:
    """A 'Revert "X"' and the commit titled X cancel out; neither is shown."""
    reverted = {m.group(1) for s, _ in records if (m := re.match(r'^Revert "(.+)"$', s))}
    return [(s, f) for s, f in records if s not in reverted and not s.startswith("Revert ")]


def player_lines(records: list[tuple[str, list[str]]]) -> dict[str, list[str]]:
    """{'new': [...], 'fixes': [...]} - the player-facing lines for (subject, files) commits."""
    sections: dict[str, list[str]] = {"new": [], "fixes": []}
    for subject, files in drop_reverts(records):
        if touches_only_noise(files):
            continue
        section, text = classify(subject)
        if section in sections and text:
            sections[section].append(text)
    sections["new"] = dedupe(sections["new"])
    sections["fixes"] = dedupe(sections["fixes"])
    fixes = {_core(t) for t in sections["fixes"]}  # a fix is not also "new"
    sections["new"] = [t for t in sections["new"] if _core(t) not in fixes]
    return sections


def commit_records(prev: str, head: str) -> list[tuple[str, list[str]]]:
    """(title, files touched) for every non-merge commit in prev..head."""
    raw = plan.git("log", "--no-merges", "--format=\x1e%s", "--name-only", f"{prev}..{head}")
    records = []
    for chunk in raw.split("\x1e")[1:]:
        lines = [l for l in chunk.splitlines() if l.strip()]
        if lines:
            records.append((lines[0], lines[1:]))
    return records


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


def _git_error_text(e: Exception) -> str:
    """One line for an error: git's own stderr when it is a failed git call."""
    if isinstance(e, subprocess.CalledProcessError):
        err = e.stderr
        if isinstance(err, bytes):
            err = err.decode("utf-8", "replace")
        err = (err or "").strip()
        return err.splitlines()[0] if err else f"exit {e.returncode} from {' '.join(map(str, e.cmd))}"
    return f"{type(e).__name__}: {e}"


def render(head: str, prev: str) -> str:
    """Notes for the release at git ref `head` (a tag, or a sha in a rehearsal)."""
    if not prev:  # the first release: its "changes" are the whole project
        return ("First public release of Origin Launcher.\n\n"
                "Download `OriginLauncher-Setup.exe` below and run it.\n")
    records = commit_records(prev, head)
    sections = player_lines(records)

    out = []
    for key, heading in (("new", "What's new"), ("fixes", "Fixes")):
        if sections[key]:
            out += [f"## {heading}", *(f"- {t}" for t in sections[key]), ""]
    if not sections["new"] and not sections["fixes"]:
        out += ["Fixes and stability improvements." if records
                else "Re-release of the previous build with no code changes.", ""]

    try:
        updated = versions_updated(prev, head)
    except (subprocess.CalledProcessError, ValueError, KeyError, TypeError, OSError) as e:
        updated = None  # the version list is a nicety; never let it block a release
        # ...but say so: a silently missing section is invisible in CI logs otherwise.
        print(f"notes.py: warning: version list skipped: {_git_error_text(e)}", file=sys.stderr)
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
    try:
        body = render(head, args.prev)
    except subprocess.CalledProcessError as e:
        print(f"notes.py: git failed: {_git_error_text(e)}", file=sys.stderr)
        return 1
    if args.out:
        args.out.write_text(body, encoding="utf-8")
    else:
        sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
