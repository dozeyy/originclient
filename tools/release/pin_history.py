"""ManagedPins.History.cs: every jar Origin has ever pinned for a mods folder, from the launcher's git history.

Why: the launcher decides whether a jar in a mods folder is Origin's or the player's by its bytes (ModManager.OwnerOf).
A pin that has since left the catalog still marks a file an older launcher installed; if its SHA-512 were unknown, an
old leftover of Origin's would count as the player's own copy and win over Origin's newer one (shaders silently off).
Pins added before 2026-10-20 carried only a URL, so the SHA-512 of every URL is asked from Modrinth's API.

  python tools/release/pin_history.py           regenerate (needs full git history + Modrinth)
  python tools/release/pin_history.py --check   fail when a Modrinth URL in the git history is missing from the file
                                                (needs full git history; no network)
"""
import json
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/OriginLauncher.App/Core/Loaders/ManagedPins.History.cs"
# The version segment is a Modrinth version id on newer pins, but older CDN URLs carry the version NUMBER
# (".../versions/0.2.1/krypton-0.2.1.jar", ".../versions/1.0.7%2Bmc1.18.2/..."): match both.
URL_RE = re.compile(r"https://cdn\.modrinth\.com/data/([A-Za-z0-9]+)/versions/([^/\s\"'<>]+)/([^\"'\s)<>/]+?\.jar)")
VERSION_ID_RE = re.compile(r"^[A-Za-z0-9]{8}$")
SHA_RE = re.compile(r"(?<![0-9A-Fa-f])[0-9A-Fa-f]{128}(?![0-9A-Fa-f])")


def history_text() -> str:
    if subprocess.run(["git", "rev-parse", "--is-shallow-repository"], cwd=ROOT, capture_output=True,
                      text=True).stdout.strip() == "true":
        # A shallow clone can still hold HEAD's whole history (another ref is the shallow one): check the root.
        roots = subprocess.run(["git", "rev-list", "--max-parents=0", "HEAD"], cwd=ROOT, capture_output=True,
                               text=True).stdout.split()
        shallow = (ROOT / subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=ROOT, capture_output=True,
                                         text=True).stdout.strip() / "shallow")
        cut = set(shallow.read_text().split()) if shallow.exists() else set()
        if any(r in cut for r in roots):
            sys.exit("pin_history: HEAD's history is cut short (shallow clone); fetch the full history first")
    return subprocess.run(["git", "log", "-p", "HEAD", "--", "src/OriginLauncher.App"], cwd=ROOT,
                          capture_output=True, text=True, encoding="utf-8", errors="replace", check=True).stdout


def urls_in(text: str) -> dict:
    return {m.group(0): (m.group(1), m.group(2), urllib.parse.unquote(m.group(3))) for m in URL_RE.finditer(text)}


def modrinth(path: str):
    req = urllib.request.Request("https://api.modrinth.com" + path, headers={"User-Agent": "OriginLauncher-dev/pin-history"})
    with urllib.request.urlopen(req, timeout=60) as reply:
        return json.load(reply)


def check() -> int:
    current = OUT.read_text(encoding="utf-8")
    missing = sorted(u for u in urls_in(history_text()) if f'"{u}"' not in current)
    for u in missing:
        print("MISSING", u)
    print(f"pin_history: {len(missing)} pinned URL(s) from the git history missing from {OUT.name}")
    return 1 if missing else 0


def generate() -> int:
    text = history_text()
    urls = urls_in(text)
    shas = {s.lower() for s in SHA_RE.findall(text)}
    if OUT.exists():  # append-only: never drop a SHA-512 that is already there
        shas |= set(re.findall(r"[0-9a-f]{128}", OUT.read_text(encoding="utf-8")))
        for u, entry in urls_in(OUT.read_text(encoding="utf-8")).items():
            urls.setdefault(u, entry)
    ids = sorted({v for _, v, _ in urls.values() if VERSION_ID_RE.match(v)})
    files_of = {}
    for i in range(0, len(ids), 80):
        for v in modrinth("/v2/versions?ids=" + urllib.parse.quote(json.dumps(ids[i:i + 80]))):
            files_of[v["id"]] = v["files"]
    project_files = {}  # project id -> every file of every version (for URLs that carry a version number)

    def files_for(project: str, vid: str) -> list:
        if vid in files_of:
            return files_of[vid]
        if project not in project_files:
            try:
                project_files[project] = [f for v in modrinth(f"/v2/project/{project}/version") for f in v["files"]]
            except OSError:
                project_files[project] = []
        return project_files[project]

    unresolved = []
    for url, (project, vid, name) in sorted(urls.items()):
        files = files_for(project, vid)
        hit = next((f for f in files if urllib.parse.unquote(f["url"]) == urllib.parse.unquote(url)), None) \
            or next((f for f in files if f["filename"] == name), None)
        if hit is None:
            unresolved.append(url)
        else:
            shas.add(hit["hashes"]["sha512"].lower())

    lines = [
        "namespace OriginLauncher.App.Core.Loaders;",
        "",
        "// Every jar Origin has EVER pinned for a mods folder (perf catalog, Fabric API, Simple Voice Chat, vetted",
        "// alternates), from the launcher's whole git history: each Modrinth URL ever pinned, and the SHA-512 of every one",
        "// (asked from Modrinth's API, so pins from before pins carried hashes are covered). A pin that left the catalog",
        "// still marks a file an older Origin installed: such a leftover is Origin's to set aside, never mistaken for the",
        "// player's own copy (ModManager.OwnerOf). Append-only: ManagedPinsTests fails when a current pin's URL or SHA-512",
        "// is missing here. Generated by tools/release/pin_history.py (its --check proves every URL in history is here).",
        "public static partial class ManagedPins",
        "{",
        "    private static readonly string[] EverPinnedUrls =",
        "    {",
    ]
    lines += [f'        "{u}",' for u in sorted(urls)]
    lines += ["    };", "", "    private static readonly string[] EverPinnedSha512 =", "    {"]
    lines += [f'        "{s}",' for s in sorted(shas)]
    lines += ["    };", "}", ""]
    OUT.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"pin_history: {len(urls)} URLs, {len(shas)} SHA-512s, {len(unresolved)} unresolved")
    for u in unresolved:
        print("UNRESOLVED", u)
    return 1 if unresolved else 0


if __name__ == "__main__":
    sys.exit(check() if "--check" in sys.argv[1:] else generate())
