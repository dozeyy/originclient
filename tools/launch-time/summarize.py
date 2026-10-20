"""Summarize launch-time.yml runs: the launcher side of a Play press, per phase.

    python summarize.py <dir>

<dir> holds <variant>-<round>-<n>.json from `OriginLauncher.App.exe --launch-timing`
(LaunchTimingHarness): n = 0 is a cold launch (fresh launcher folder, everything
downloads), n >= 1 a warm one (same folder, new launcher process). Prints, per
variant and kind, the median time since Play of every mark, the median length of
each phase (gap from the previous mark), and the HTTP requests a launch makes.
Never fails the run.
"""
import glob
import json
import os
import re
import statistics
import sys
from urllib.parse import urlsplit

ORDER = ["options", "game-dir", "stamp-full", "stamp-fast", "loader", "fabric-api", "mods",
         "version", "files-first-progress", "files", "process-built", "prepared", "settings-sync",
         "process-start", "game-first-line", "game-fabric-start", "game-mods-listed"]
WHAT = {
    "options": "settings + JVM args",
    "game-dir": "account game folder",
    "stamp-full": "install stamp read (full verify)",
    "stamp-fast": "install stamp read (fast path)",
    "loader": "Fabric loader resolve/install",
    "fabric-api": "Fabric API check/download",
    "mods": "Origin jar + mods folder sweeps",
    "version": "CmlLib version lookup (manifest)",
    "files-first-progress": "CmlLib file lists (Java/asset manifests)",
    "files": "CmlLib check + download files",
    "process-built": "natives + command line",
    "prepared": "(end of provisioning)",
    "settings-sync": "Settings Sync",
    "process-start": "java process start",
    "game-first-line": "JVM to first log line",
    "game-fabric-start": "to Fabric Loader start",
    "game-mods-listed": "Fabric mod discovery",
}


def load(folder):
    runs = {}
    for path in sorted(glob.glob(os.path.join(folder, "*.json"))):
        m = re.fullmatch(r"(\w+)-(\d+)-(\d+)\.json", os.path.basename(path))
        if not m:
            continue
        try:
            with open(path, encoding="utf-8-sig") as f:
                data = json.load(f)
        except (OSError, ValueError):
            continue
        kind = "cold" if m.group(3) == "0" else "warm"
        runs.setdefault((m.group(1), kind), []).append((os.path.basename(path), data))
    return runs


def med(xs):
    return int(statistics.median(xs)) if xs else None


def fmt(x):
    return "-" if x is None else f"{x:,}"


def short(url):
    u = urlsplit(url)
    path = re.sub(r"/[0-9a-f]{40}/", "/<sha1>/", u.path)
    if "/objects/" in path or path.startswith("/maven") or path.endswith(".jar"):
        path = re.sub(r"[^/]+$", "*", path)
    return u.netloc + path


def java_of(path):
    """The Mojang runtime a java path sits in (CmlLib: runtime/<os>/<component>/...), else the path."""
    parts = re.split(r"[\\/]", path)
    if "runtime" in parts:
        i = parts.index("runtime")
        return "/".join(parts[i + 1:i + 3]) or path
    return path


def offline(folder):
    """The offline=1 launches (<variant>-1-offfast/offfull.json): did they get to the game?"""
    rows = []
    for path in sorted(glob.glob(os.path.join(folder, "*-off*.json"))):
        try:
            with open(path, encoding="utf-8-sig") as f:
                d = json.load(f)
        except (OSError, ValueError):
            continue
        start = next((m["ms"] for m in d.get("marks", []) if m["name"] == "process-start"), None)
        reqs = d.get("requests", [])
        dead = sum(1 for r in reqs if not r.get("status"))
        err = (d.get("error") or "").split("\n")[0][:140].replace("|", "/")
        ok = start is not None and not err
        rows.append((os.path.basename(path), "launched" if ok else "FAILED", fmt(start),
                     f"{len(reqs)} ({dead} no answer)", err))
    if rows:
        print("\n### Offline launches (no network)\n")
        print("| run | result | Play -> java start | requests tried | error |")
        print("|---|---|---:|---|---|")
        for r in rows:
            print("| " + " | ".join(r) + " |")


def main(folder):
    runs = load(folder)
    if not runs:
        print("No launch-time results found.")
        return
    keys = sorted(runs)
    print("## Launch time (launcher side), median ms since Play\n")
    print("CI's Windows runner, not a player's PC: compare variants as a relative change.\n")
    for v, kind in keys:
        rs = runs[(v, kind)]
        errors = [n for n, d in rs if d.get("error")]
        print(f"### {v} {kind}: {len(rs)} launches" + (f", {len(errors)} FAILED ({', '.join(errors)})" if errors else ""))
        print()
        print("| mark | what it covers | at (median) | phase (median) | min..max phase |")
        print("|---|---|---:|---:|---:|")
        series = []
        for _, d in rs:
            marks = {}
            for mk in d.get("marks", []):
                marks.setdefault(mk["name"], mk["ms"])
            series.append(marks)
        names = [n for n in ORDER if any(n in s for s in series)]
        extra = sorted({n for s in series for n in s if n not in ORDER and n != "game-stop"},
                       key=lambda n: med([s[n] for s in series if n in s]) or 0)
        for name in names + extra:
            at = [s[name] for s in series if name in s]
            gaps = []
            for s in series:
                if name not in s:
                    continue
                prev = [s[p] for p in names + extra if p in s and s[p] <= s[name] and p != name]
                gaps.append(s[name] - (max(prev) if prev else 0))
            lo, hi = (min(gaps), max(gaps)) if gaps else (None, None)
            print(f"| {name} | {WHAT.get(name, 'game boot mark')} | {fmt(med(at))} | {fmt(med(gaps))} | {fmt(lo)}..{fmt(hi)} |")
        print()
        # Requests: what a launch of this kind fetches, grouped.
        groups = {}
        for _, d in rs:
            seen = {}
            for r in d.get("requests", []):
                k = short(r["url"])
                c = seen.setdefault(k, [0, 0])
                c[0] += 1
                c[1] += r["ms"]
            for k, (n, ms) in seen.items():
                g = groups.setdefault(k, ([], []))
                g[0].append(n)
                g[1].append(ms)
        total = [len(d.get("requests", [])) for _, d in rs]
        print(f"HTTP requests per launch: median {fmt(med(total))}\n")
        ups = [d["startupMs"] for _, d in rs if isinstance(d.get("startupMs"), (int, float))]
        if ups:
            print(f"Launcher process start -> harness (startup): median {fmt(med(ups))} ms\n")
        javas = sorted({java_of(d["javaPath"]) for _, d in rs if d.get("javaPath")})
        if javas:
            print(f"Game ran on: {', '.join(javas)}\n")
        if groups:
            print("| request | per launch | summed ms (median) |")
            print("|---|---:|---:|")
            for k, (ns, mss) in sorted(groups.items(), key=lambda kv: -(med(kv[1][1]) or 0))[:25]:
                print(f"| {k} | {fmt(med(ns))} | {fmt(med(mss))} |")
            print()
    # The headline: Play -> java process start, per variant.
    print("### Play -> java process start (median ms)\n")
    print("| variant | cold | warm |")
    print("|---|---:|---:|")
    for v in sorted({k[0] for k in keys}):
        row = []
        for kind in ("cold", "warm"):
            xs = [m["ms"] for _, d in runs.get((v, kind), []) for m in d.get("marks", []) if m["name"] == "process-start"]
            row.append(fmt(med(xs)))
        print(f"| {v} | {row[0]} | {row[1]} |")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
        offline(sys.argv[1])
    except Exception as e:  # a summary bug must never fail the measurement run
        print(f"summary failed: {e}")
