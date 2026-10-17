#!/usr/bin/env python3
"""Claude Lean usage report: where this project's Claude Code tokens went.

    python tools/lean/report.py                 # last 5 sessions
    python tools/lean/report.py --sessions 20
    python tools/lean/report.py --compare <session-id-A> <session-id-B>

Reads Claude Code's own transcripts (~/.claude/projects/<project>/*.jsonl and
each session's subagents/) - nothing is sent anywhere. Standard library only.

What the numbers mean (read before quoting them):
- "read" = cache-read input tokens: the conversation re-read on every call.
  In long sessions this is ~99% of all input. "write" = cache writes (new
  context entering the cache), "out" = output tokens.
- "index" = a cost-weighted proxy using Anthropic's published API multipliers
  for the model (Opus/Sonnet 5.5: read 0.05x, 1h write 2x, 5m write 1.25x,
  output 5x base input). Anthropic does NOT publish how plan usage limits
  weight these, so the index is a comparison tool, not your plan meter. The
  statusline's "5h %" is the only direct plan signal.
- Transcripts record streamed replies in chunks; per message the largest
  usage seen is kept. Subagent output counts are known to be undercounted.
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys

# Cache-read multiplier per model family (published API pricing, 2026-10).
READ_MULT = {"fable": 0.025, "mythos": 0.025, "opus-5": 0.05, "sonnet-5": 0.05}
WRITE_1H, WRITE_5M, OUT_MULT = 2.0, 1.25, 5.0


def read_mult(model):
    for k, v in READ_MULT.items():
        if k in (model or ""):
            return v
    return 0.1


def project_dir(cwd):
    base = os.path.join(os.path.expanduser("~"), ".claude", "projects")
    slug = re.sub(r"[^A-Za-z0-9]", "-", os.path.abspath(cwd))
    path = os.path.join(base, slug)
    if os.path.isdir(path):
        return path
    # Older/newer slug rules differ slightly; fall back to a case-insensitive match.
    for d in glob.glob(os.path.join(base, "*")):
        if os.path.basename(d).lower() == slug.lower():
            return d
    sys.exit("No Claude Code transcripts found for %s (looked in %s)" % (cwd, path))


def scan(files, subagent):
    best, ctx = {}, []
    models = {}
    for f in files:
        try:
            fh = open(f, encoding="utf-8")
        except OSError:
            continue
        with fh:
            for line in fh:
                if '"usage"' not in line:
                    continue
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                m = e.get("message")
                if e.get("type") != "assistant" or not isinstance(m, dict) or not m.get("usage"):
                    continue
                k = m.get("id") or id(m)
                u = m["usage"]
                if k not in best or (u.get("output_tokens") or 0) >= (best[k].get("output_tokens") or 0):
                    best[k] = u
                    models[k] = m.get("model")
    t = dict(calls=len(best), read=0, write=0, out=0, inp=0, index=0.0, ctx=[])
    for k, u in best.items():
        r, w = u.get("cache_read_input_tokens") or 0, u.get("cache_creation_input_tokens") or 0
        o, i = u.get("output_tokens") or 0, u.get("input_tokens") or 0
        t["read"] += r
        t["write"] += w
        t["out"] += o
        t["inp"] += i
        t["ctx"].append(r + w + i)
        t["index"] += r * read_mult(models.get(k)) + w * (WRITE_5M if subagent else WRITE_1H) + o * OUT_MULT + i
    return t


def session(pdir, sid):
    main = scan([os.path.join(pdir, sid + ".jsonl")], subagent=False)
    subs = scan(glob.glob(os.path.join(pdir, sid, "subagents", "*.jsonl")), subagent=True)
    return main, subs


def fmt(n):
    return "%.1fM" % (n / 1e6) if n >= 1e6 else "%dk" % round(n / 1e3)


def line(label, t):
    if not t["calls"]:
        return "  %-10s -" % label
    c = t["ctx"]
    return ("  %-10s calls %4d | read %7s write %6s out %6s | ctx median %5s peak %5s | index %7s"
            % (label, t["calls"], fmt(t["read"]), fmt(t["write"]), fmt(t["out"]),
               fmt(statistics.median(c)), fmt(max(c)), fmt(t["index"])))


def lean_stats(cwd):
    path = os.path.join(cwd, ".claude", "lean", "stats.jsonl")
    agg = {"trim": [0, 0, 0], "read_blocked": [0, 0], "compact": [0]}
    try:
        for l in open(path, encoding="utf-8"):
            e = json.loads(l)
            if e.get("event") == "trim":
                a = agg["trim"]
                a[0] += 1
                a[1] += e.get("before", 0)
                a[2] += e.get("after", 0)
            elif e.get("event") == "read_blocked":
                agg["read_blocked"][0] += 1
                agg["read_blocked"][1] += e.get("bytes", 0)
            elif e.get("event") == "compact":
                agg["compact"][0] += 1
    except (OSError, ValueError):
        return None
    return agg


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--sessions", type=int, default=5)
    ap.add_argument("--project", default=os.getcwd(), help="project root (default: current folder)")
    ap.add_argument("--compare", nargs=2, metavar=("A", "B"), help="two session ids (B is compared to A)")
    a = ap.parse_args()
    pdir = project_dir(a.project)

    if a.compare:
        res = [session(pdir, s) for s in a.compare]
        for sid, (m, s) in zip(a.compare, res):
            print(sid)
            print(line("main", m))
            print(line("subagents", s))
        tot = [m["index"] + s["index"] for m, s in res]
        if tot[0]:
            print("\nindex B vs A: %+.1f%%" % (100 * (tot[1] - tot[0]) / tot[0]))
        return

    files = sorted(glob.glob(os.path.join(pdir, "*.jsonl")), key=os.path.getmtime)[-a.sessions:]
    print("Claude Code usage for %s (newest last)\n" % a.project)
    grand = 0.0
    for f in files:
        sid = os.path.basename(f)[:-6]
        m, s = session(pdir, sid)
        grand += m["index"] + s["index"]
        print(sid)
        print(line("main", m))
        print(line("subagents", s))
        idx = m["index"] + s["index"]
        if idx:
            share = (m["read"] * read_mult("opus-5") + s["read"] * read_mult("opus-5")) / idx
            print("  -> re-reading context is ~%d%% of this session's index" % round(100 * share))
    st = lean_stats(a.project)
    if st:
        tr, rb, cp = st["trim"], st["read_blocked"], st["compact"]
        print("\nClaude Lean (this machine, all sessions): %d outputs trimmed (%s -> %s chars), "
              "%d unchanged re-reads skipped (%s), %d compactions with state card"
              % (tr[0], fmt(tr[1]), fmt(tr[2]), rb[0], fmt(rb[1]) + "B", cp[0]))


if __name__ == "__main__":
    main()
