#!/usr/bin/env python3
"""Origin 1.8.9 benchmark summarizer: per-frame CSV -> summary JSON (+ markdown).

OriginBench (src/mods/versions/1.8.9 .../client/OriginBench.java) writes, per run:
    bench.json                 one entry per scene: frame count, GC and allocation counters, "valid"
    frames-<scene>.csv         header "frame_ns", then one integer frame time (nanoseconds) per line

This script re-derives every number that matters from the raw frames, so the report never has to
trust a pre-digested figure, and refuses to guess: a corrupt, truncated or empty CSV stops it with
exit code 2 and a message naming the file and line. Nothing here invents a value; a figure the game
did not record (for example the longest GC pause on an old run) is reported as null.

  run        one run folder      -> summary of each scene
  aggregate  <root>/<variant>/run-<n>/   -> median (min-max) across runs per variant and scene, and
                                    the relative change of every variant against the baseline

Definitions (same as OriginBench's own, so the two always agree):
  avgFps     frames / total frame time
  p50/p95/p99  nearest-rank percentile of the frame times
  1% low     FPS of the slowest 1% of frames = 1e9 / mean(slowest ceil(n*0.01) frame times); 0.1% likewise
  stdevMs    population standard deviation of the frame times
  gcPause*   GC pauses inside the measured window (game's GC notifications), allocMbPerSec = render
             thread allocation rate

Stdlib only. Run the tests with:  python -m pytest tools/bench   (or: python -m unittest discover tools/bench)
"""
import argparse
import csv
import json
import math
import os
import statistics
import sys

SCHEMA = 1
LABEL_CI = "CI, relative only"

# Metrics shown in the aggregate (name, lower-is-better). Order = table order.
METRICS = [
    ("avgFps", False),
    ("low1Fps", False),
    ("low01Fps", False),
    ("p50Ms", True),
    ("p99Ms", True),
    ("stdevMs", True),
    ("gcPauseTotalMs", True),
    ("gcPauseMaxMs", True),
    ("allocMbPerSec", True),
    ("heapAvgMb", True),
]


class SummaryError(Exception):
    """Input is missing, corrupt or inconsistent. The CLI turns this into exit code 2."""


# ----------------------------------------------------------------------------------------------
# CSV -> frame times
# ----------------------------------------------------------------------------------------------

def read_frames(path):
    """Frame times in ns from an OriginBench CSV. Raises SummaryError on anything off."""
    try:
        with open(path, newline="", encoding="utf-8") as f:
            reader = csv.reader(f)
            try:
                header = next(reader)
            except StopIteration:
                raise SummaryError("%s: empty file (no header)" % path)
            header = [h.strip() for h in header]
            if "frame_ns" not in header:
                raise SummaryError("%s: header must contain 'frame_ns', got %r" % (path, header))
            col = header.index("frame_ns")
            frames = []
            for row in reader:
                line = reader.line_num
                if not row or all(not c.strip() for c in row):
                    raise SummaryError("%s:%d: blank line inside the frame list" % (path, line))
                if len(row) != len(header):
                    raise SummaryError("%s:%d: %d columns, header has %d" % (path, line, len(row), len(header)))
                cell = row[col].strip()
                try:
                    v = int(cell)
                except ValueError:
                    raise SummaryError("%s:%d: frame_ns is not an integer: %r" % (path, line, cell))
                if v <= 0:
                    raise SummaryError("%s:%d: frame_ns must be positive, got %d" % (path, line, v))
                frames.append(v)
    except OSError as e:
        raise SummaryError("%s: cannot read (%s)" % (path, e))
    except UnicodeDecodeError as e:
        raise SummaryError("%s: not text (%s)" % (path, e))
    except csv.Error as e:
        raise SummaryError("%s: not valid CSV (%s)" % (path, e))
    if not frames:
        raise SummaryError("%s: header but no frames" % path)
    return frames


# ----------------------------------------------------------------------------------------------
# Statistics
# ----------------------------------------------------------------------------------------------

def percentile_ns(sorted_ns, p):
    """Nearest-rank percentile (matches OriginBench.pct)."""
    n = len(sorted_ns)
    idx = int(math.ceil(p / 100.0 * n)) - 1
    return sorted_ns[max(0, min(n - 1, idx))]


def low_fps(sorted_ns, fraction):
    """FPS of the slowest `fraction` of frames (matches OriginBench.lowFps)."""
    n = len(sorted_ns)
    k = max(1, int(math.ceil(n * fraction)))
    tail = sorted_ns[n - k:]
    return 1e9 / (sum(tail) / float(k))


def r(v, places):
    return None if v is None else round(v, places)


def frame_stats(frames):
    srt = sorted(frames)
    n = len(srt)
    total = sum(srt)
    ms = [x / 1e6 for x in frames]
    return {
        "frames": n,
        "seconds": r(total / 1e9, 3),
        "avgFps": r(n / (total / 1e9), 1),
        "low1Fps": r(low_fps(srt, 0.01), 1),
        "low01Fps": r(low_fps(srt, 0.001), 1),
        "p50Ms": r(percentile_ns(srt, 50) / 1e6, 3),
        "p95Ms": r(percentile_ns(srt, 95) / 1e6, 3),
        "p99Ms": r(percentile_ns(srt, 99) / 1e6, 3),
        "maxMs": r(srt[-1] / 1e6, 3),
        "stdevMs": r(statistics.pstdev(ms), 3),
        "hitches20ms": sum(1 for x in frames if x >= 20_000_000),
    }


# ----------------------------------------------------------------------------------------------
# One run folder
# ----------------------------------------------------------------------------------------------

def _load_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise SummaryError("%s: cannot read as JSON (%s)" % (path, e))


def summarize_run(run_dir):
    """{'environment': {...}, 'scenes': {name: {...}}} for one run folder (bench.json + frames-*.csv)."""
    bench_path = os.path.join(run_dir, "bench.json")
    if not os.path.isfile(bench_path):
        raise SummaryError("%s: no bench.json (the run did not finish)" % run_dir)
    bench = _load_json(bench_path)
    scenes_in = bench.get("scenarios")
    if not isinstance(scenes_in, list) or not scenes_in:
        raise SummaryError("%s: bench.json has no scenarios" % bench_path)

    scenes = {}
    for entry in scenes_in:
        name = entry.get("scenario")
        if not name:
            raise SummaryError("%s: a scenario entry has no name" % bench_path)
        csv_path = os.path.join(run_dir, "frames-%s.csv" % name)
        if not os.path.isfile(csv_path):
            raise SummaryError("%s: missing (bench.json lists scene %r)" % (csv_path, name))
        frames = read_frames(csv_path)
        expected = entry.get("frames")
        if expected is not None and int(expected) != len(frames):
            raise SummaryError("%s: %d frames in the CSV but bench.json recorded %s (truncated or edited)"
                               % (csv_path, len(frames), expected))
        s = frame_stats(frames)
        s["valid"] = bool(entry.get("valid", True))
        # GC: pauses seen by the game's GC notifications when recorded; else the collectors' own
        # total time (all of a stop-the-world collector's time is pause) and no maximum.
        if entry.get("gcPauseTotalMs") is not None:
            s["gcPauseTotalMs"] = r(float(entry["gcPauseTotalMs"]), 1)
            s["gcPauseMaxMs"] = r(float(entry["gcPauseMaxMs"]), 1) if entry.get("gcPauseMaxMs") is not None else None
            s["gcPauseSource"] = "gc-notifications"
        elif entry.get("gcTimeMs") is not None:
            s["gcPauseTotalMs"] = r(float(entry["gcTimeMs"]), 1)
            s["gcPauseMaxMs"] = None
            s["gcPauseSource"] = "collector-total-time"
        else:
            s["gcPauseTotalMs"] = None
            s["gcPauseMaxMs"] = None
            s["gcPauseSource"] = None
        s["gcCount"] = entry.get("gcCount")
        alloc = entry.get("renderThreadAllocMbPerSec")
        s["allocMbPerSec"] = None if alloc is None or alloc < 0 else r(float(alloc), 2)
        s["heapAvgMb"] = entry.get("heapAvgMb")
        scenes[name] = s
    return {"environment": bench.get("environment", {}), "scenes": scenes}


# ----------------------------------------------------------------------------------------------
# Across runs and variants
# ----------------------------------------------------------------------------------------------

def _spread(values):
    vals = [v for v in values if v is not None]
    if not vals:
        return None
    return {"median": r(statistics.median(vals), 3), "min": r(min(vals), 3), "max": r(max(vals), 3), "n": len(vals)}


def aggregate(root, label=LABEL_CI, baseline=None):
    """root/<variant>/run-<n>/ -> variants -> scenes -> metric spreads, plus deltas vs the baseline."""
    if not os.path.isdir(root):
        raise SummaryError("%s: not a folder" % root)
    variants = {}
    for vname in sorted(os.listdir(root)):
        vdir = os.path.join(root, vname)
        if not os.path.isdir(vdir):
            continue
        run_dirs = sorted(d for d in os.listdir(vdir)
                          if d.startswith("run-") and os.path.isdir(os.path.join(vdir, d)))
        if not run_dirs:
            continue
        runs, per_scene, left_out, startups = [], {}, [], []
        env = {}
        for rd in run_dirs:
            res = summarize_run(os.path.join(vdir, rd))   # raises on a corrupt run: never skipped quietly
            env = env or res["environment"]
            runs.append(rd)
            ms = res["environment"].get("startupMs")
            if isinstance(ms, (int, float)) and ms > 0:   # -1 / missing = title never seen, or an older jar
                startups.append(ms / 1000.0)
            for sc, s in res["scenes"].items():
                if not s["valid"]:
                    left_out.append("%s/%s" % (rd, sc))
                    continue
                per_scene.setdefault(sc, []).append(s)
        scenes = {}
        for sc, rows in per_scene.items():
            m = {"runs": len(rows)}
            for name, _ in METRICS:
                m[name] = _spread([row.get(name) for row in rows])
            scenes[sc] = m
        variants[vname] = {"runs": runs, "leftOutInvalid": left_out, "environment": env, "scenes": scenes,
                           "startupS": _spread(startups)}   # JVM start -> title screen, seconds (6.1 startup)
    if not variants:
        raise SummaryError("%s: no <variant>/run-<n> folders found" % root)

    if baseline is None:
        baseline = next(iter(variants))
    if baseline not in variants:
        raise SummaryError("baseline variant %r not found (have: %s)" % (baseline, ", ".join(variants)))
    deltas = {}
    for vname, v in variants.items():
        if vname == baseline:
            continue
        d = {}
        for sc, m in v["scenes"].items():
            base = variants[baseline]["scenes"].get(sc)
            if not base:
                continue
            row = {}
            for name, _ in METRICS:
                a, b = (base.get(name) or {}).get("median"), (m.get(name) or {}).get("median")
                if a is None or b is None or a == 0:
                    row[name] = None
                else:
                    row[name] = r((b - a) / a * 100.0, 1)   # percent change of the median
            d[sc] = row
        deltas[vname] = d
    return {"schema": SCHEMA, "label": label, "baseline": baseline, "variants": variants,
            "deltaPctVsBaseline": deltas}


def require_valid(agg):
    """A bench that produced files but no usable measurement must not pass as green: every variant needs at
    least one counted run of every scene any variant ran (invalid runs are named in the error)."""
    expected = set()
    for v in agg["variants"].values():
        expected.update(v["scenes"])
        expected.update(x.split("/", 1)[1] for x in v["leftOutInvalid"])
    problems = []
    for name, v in agg["variants"].items():
        want = set(expected)
        if v["environment"].get("originActive") is False:
            want.discard("hud")   # the OptiFine-only probe skips hud on purpose (no Origin HUD to draw)
        missing = sorted(want - set(v["scenes"]))
        if missing:
            problems.append("%s has no valid run of: %s (left out as invalid: %s)"
                            % (name, ", ".join(missing), ", ".join(v["leftOutInvalid"]) or "none"))
    if problems:
        raise SummaryError("no usable measurement: " + "; ".join(problems))


REGRESSION_METRICS = ("avgFps", "low1Fps")   # the two numbers a player feels; higher is better for both


def check_regression(agg, max_pct):
    """Opt-in gate: raise if any non-baseline variant's median avg FPS or 1% low is worse than the baseline's by
    more than max_pct percent, in any scene. Not a default: a CI runner is noisy, so the caller picks the N."""
    if max_pct < 0:
        raise SummaryError("--max-regression-pct must be >= 0, got %s" % max_pct)
    bad, uncompared = [], []
    for vname, v in agg["variants"].items():
        if vname == agg["baseline"]:
            continue
        row_by_scene = agg["deltaPctVsBaseline"].get(vname, {})
        for sc in v["scenes"]:
            # A scene the baseline never measured has no delta; skipping it would let a regression pass unseen.
            for name in REGRESSION_METRICS:
                d = (row_by_scene.get(sc) or {}).get(name)
                if d is None:
                    uncompared.append("%s %s %s" % (vname, sc, name))
                elif d < -max_pct:
                    bad.append("%s %s %s %+.1f%% vs %s" % (vname, sc, name, d, agg["baseline"]))
    if uncompared:
        raise SummaryError("cannot check regression, no baseline figure for: %s (baseline %s)"
                           % ("; ".join(uncompared), agg["baseline"]))
    if bad:
        raise SummaryError("regression beyond %g%%: %s" % (max_pct, "; ".join(bad)))


def _cell(spread, places=1):
    if not spread:
        return "-"
    f = "%." + str(places) + "f"
    return "%s (%s-%s)" % (f % spread["median"], f % spread["min"], f % spread["max"])


def to_markdown(agg):
    lines = ["# 1.8.9 benchmark summary (%s)" % agg["label"], "",
             "Each cell: median of the counted runs (min-max). Baseline: `%s`. "
             "Percent columns are the change of the median against it; for FPS higher is better, "
             "for the ms / GC / allocation columns lower is better." % agg["baseline"], ""]
    for vname, v in agg["variants"].items():
        lines.append("## %s  (%d runs)" % (vname, len(v["runs"])))
        if v["leftOutInvalid"]:
            lines.append("Left out as invalid (window unfocused / camera off / truncated): " + ", ".join(v["leftOutInvalid"]))
        lines.append("")
        if v.get("startupS"):
            lines.append("Time to title screen (JVM start -> title, s): %s" % _cell(v["startupS"], 1))
            lines.append("")
        lines.append("| Scene | Runs | Avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | stdev ms | GC pause total ms | GC pause max ms | Alloc MB/s |")
        lines.append("|---|---|---|---|---|---|---|---|---|---|---|")
        for sc, m in v["scenes"].items():
            lines.append("| %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                sc, m["runs"], _cell(m["avgFps"], 0), _cell(m["low1Fps"], 0), _cell(m["low01Fps"], 0),
                _cell(m["p50Ms"], 2), _cell(m["p99Ms"], 2), _cell(m["stdevMs"], 2),
                _cell(m["gcPauseTotalMs"], 0), _cell(m["gcPauseMaxMs"], 0), _cell(m["allocMbPerSec"], 1)))
        lines.append("")
        d = agg["deltaPctVsBaseline"].get(vname)
        if d:
            lines.append("Change vs `%s`:" % agg["baseline"])
            lines.append("")
            lines.append("| Scene | Avg FPS | 1% low | p99 ms | stdev ms | GC pause total | Alloc MB/s |")
            lines.append("|---|---|---|---|---|---|---|")

            def pct(x):
                return "-" if x is None else "%+.1f%%" % x
            for sc, row in d.items():
                lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (
                    sc, pct(row["avgFps"]), pct(row["low1Fps"]), pct(row["p99Ms"]),
                    pct(row["stdevMs"]), pct(row["gcPauseTotalMs"]), pct(row["allocMbPerSec"])))
            lines.append("")
    if agg["label"] == LABEL_CI:
        lines.append("CI runs on shared CPU cores with a software renderer: compare variants with each other, "
                     "never quote these FPS as what a player's PC does.")
    return "\n".join(lines) + "\n"


# ----------------------------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------------------------

def _write(path, text):
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p_run = sub.add_parser("run", help="summarize one run folder")
    p_run.add_argument("run_dir")
    p_run.add_argument("--out", help="write summary JSON here (default: stdout)")
    p_run.add_argument("--label", default=LABEL_CI)
    p_agg = sub.add_parser("aggregate", help="<root>/<variant>/run-<n>/ -> medians + deltas")
    p_agg.add_argument("root")
    p_agg.add_argument("--out", help="summary JSON path (default: <root>/summary.json)")
    p_agg.add_argument("--md", help="markdown path (default: <root>/summary.md)")
    p_agg.add_argument("--label", default=LABEL_CI)
    p_agg.add_argument("--require-valid", action="store_true",
                       help="exit 2 if any variant has no counted run of a scene (all runs invalid)")
    p_agg.add_argument("--baseline", help="variant to compare the others with (default: first alphabetically)")
    p_agg.add_argument("--max-regression-pct", type=float, default=None, metavar="N",
                       help="opt-in: exit 2 if any variant's avg FPS or 1%% low is worse than the baseline's by "
                            "more than N percent (the summary files are still written first)")
    args = ap.parse_args(argv)
    try:
        if args.cmd == "run":
            res = summarize_run(args.run_dir)
            doc = {"schema": SCHEMA, "label": args.label, "source": args.run_dir, **res}
            text = json.dumps(doc, indent=2) + "\n"
            if args.out:
                _write(args.out, text)
            else:
                sys.stdout.write(text)
        else:
            agg = aggregate(args.root, label=args.label, baseline=args.baseline)
            if args.require_valid:
                require_valid(agg)
            _write(args.out or os.path.join(args.root, "summary.json"), json.dumps(agg, indent=2) + "\n")
            md = to_markdown(agg)
            _write(args.md or os.path.join(args.root, "summary.md"), md)
            sys.stdout.write(md)
            if args.max_regression_pct is not None:
                check_regression(agg, args.max_regression_pct)   # after the write: the numbers stay in the artifact
    except SummaryError as e:
        sys.stderr.write("summarize: FAILED: %s\n" % e)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
