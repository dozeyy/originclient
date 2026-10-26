"""U6.4 Java/GC A/B: every variant against origin-default from the SAME workflow run and round (paired).

summarize.py compares medians per variant; across several CI runs (different runners) that mixes runner speed into
the delta. Here each variant's scene is divided by the origin-default launch of the same run and round, so runner
drift cancels, and the spread of those per-round deltas is the noise.

usage:  python tools/bench/ab189.py DIR [DIR ...]
        each DIR is one downloaded bench-189 artifact (<variant>/run-N/bench.json), e.g. gh run download <id>
Percent rows are signed so that + is better (higher FPS / 1% low, lower frame time / heap). GC rows are the
difference in milliseconds of pause per 45 s scene, + = less pause. "CI, relative only": llvmpipe, 4 vCPUs.
"""
import json
import os
import statistics
import sys

CONTROL = "origin-default"
METRICS = (("avgFps", 1, "%"), ("low1Fps", 1, "%"), ("p50Ms", -1, "%"), ("p99Ms", -1, "%"),
           ("gcPauseTotalMs", -1, "ms"), ("gcPauseMaxMs", -1, "ms"), ("heapAvgMb", -1, "%"))


def load(dirs):
    """{(artifact dir, variant, run-N): {scene: scenario dict}} for every valid scene."""
    data = {}
    for d in dirs:
        for var in sorted(os.listdir(d)):
            vp = os.path.join(d, var)
            if not os.path.isdir(vp):
                continue
            for rn in sorted(os.listdir(vp)):
                bj = os.path.join(vp, rn, "bench.json")
                if os.path.isfile(bj):
                    with open(bj, encoding="utf-8") as f:
                        doc = json.load(f)
                    data[(d, var, rn)] = {s["scenario"]: s for s in doc.get("scenarios", []) if s.get("valid")}
    return data


def paired(data):
    """{variant: {scene: {metric: [per-round signed delta]}}} against the same run + round's control."""
    out = {}
    for (d, var, rn), scenes in data.items():
        if var == CONTROL:
            continue
        ctl = data.get((d, CONTROL, rn))
        if not ctl:
            continue
        for scene, x in scenes.items():
            c = ctl.get(scene)
            if c is None:
                continue
            for m, sign, unit in METRICS:
                a, b = x.get(m), c.get(m)
                if a is None or b is None:
                    continue
                if unit == "ms":
                    v = sign * (a - b)
                elif b:
                    v = sign * 100.0 * (a - b) / b
                else:
                    continue
                out.setdefault(var, {}).setdefault(scene, {}).setdefault(m, []).append(v)
    return out


def report(deltas):
    lines = []
    for var in sorted(deltas):
        lines.append("## %s vs %s (paired; median [min..max]; + = better)" % (var, CONTROL))
        lines.append("| scene | n | " + " | ".join(m for m, _, _ in METRICS) + " |")
        lines.append("|---|---|" + "---|" * len(METRICS))
        for scene in sorted(deltas[var]):
            row = deltas[var][scene]
            n = max(len(v) for v in row.values())
            cells = []
            for m, _, unit in METRICS:
                v = row.get(m)
                cells.append("%+.1f%s [%+.1f..%+.1f]" % (statistics.median(v), unit, min(v), max(v)) if v else "-")
            lines.append("| %s | %d | %s |" % (scene, n, " | ".join(cells)))
        lines.append("")
    return "\n".join(lines)


def main(argv):
    if not argv:
        sys.stderr.write(__doc__)
        return 2
    print(report(paired(load(argv))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
