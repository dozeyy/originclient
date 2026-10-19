"""Summarize boot-time.yml logs: per-variant medians of every OriginBootLog mark.

    python3 summarize.py <dir>

<dir> holds <variant>-<n>.log files (variant "a" = base ref, "b" = this ref;
"warm" boots are skipped). Each log's "[OriginBoot] summary name=ms ..." line is
the boot's timeline (ms of JVM uptime). Prints a markdown table: median, min and
max per mark and variant, the median gap between consecutive marks, and the
change in time-to-interactive-title between a and b. Never fails the run.
"""
import glob
import os
import re
import statistics
import sys

KEY = "title-interactive"


def timelines(folder):
    out = {}
    for path in sorted(glob.glob(os.path.join(folder, "*.log"))):
        name = os.path.basename(path)[:-4]
        variant = name.rsplit("-", 1)[0]
        if variant.startswith("warm") or variant.startswith("prof"):
            continue
        line = None
        with open(path, errors="replace") as f:
            for raw in f:
                if "[OriginBoot] summary" in raw:
                    line = raw
        if line is None:
            out.setdefault(variant, []).append((name, None))
            continue
        marks = dict((k, int(v)) for k, v in re.findall(r"(\S+?)=(\d+)", line.split("summary", 1)[1]))
        out.setdefault(variant, []).append((name, marks))
    return out


def fmt(xs):
    if not xs:
        return "-"
    return "%d (%d-%d)" % (statistics.median(xs), min(xs), max(xs))


def main():
    folder = sys.argv[1]
    data = timelines(folder)
    if not data:
        print("no boot logs found")
        return
    variants = sorted(data)
    order = []
    for v in variants:
        for _, m in data[v]:
            if m:
                for k in m:
                    if k not in order:
                        order.append(k)
    print("## Boot timeline (ms of JVM uptime: median (min-max))\n")
    for v in variants:
        ok = [m for _, m in data[v] if m]
        failed = [n for n, m in data[v] if not m]
        print("- variant %s: %d boots with a timeline%s" % (v, len(ok), (", no timeline: " + ", ".join(failed)) if failed else ""))
    print("\n| mark | " + " | ".join(variants) + " |")
    print("|---|" + "---|" * len(variants))
    for k in order:
        row = [fmt([m[k] for _, m in data[v] if m and k in m]) for v in variants]
        print("| %s | %s |" % (k, " | ".join(row)))
    print("\n## Gaps between consecutive marks (ms, median)\n")
    print("| from -> to | " + " | ".join(variants) + " |")
    print("|---|" + "---|" * len(variants))
    for i in range(1, len(order)):
        a, b = order[i - 1], order[i]
        row = []
        for v in variants:
            xs = [m[b] - m[a] for _, m in data[v] if m and a in m and b in m]
            row.append("%d" % statistics.median(xs) if xs else "-")
        print("| %s -> %s | %s |" % (a, b, " | ".join(row)))
    if "a" in data and "b" in data:
        a = [m[KEY] for _, m in data["a"] if m and KEY in m]
        b = [m[KEY] for _, m in data["b"] if m and KEY in m]
        if a and b:
            ma, mb = statistics.median(a), statistics.median(b)
            print("\n**time to interactive title: a %d ms -> b %d ms (%+.1f%%)**" % (ma, mb, (mb - ma) / ma * 100.0))
            print("\nper boot a: %s\n\nper boot b: %s" % (a, b))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # a summary must never fail the run
        print("summary failed: %r" % (e,))
