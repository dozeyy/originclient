"""Where a boot's CPU time went, from a boot-time.yml JFR profile.

    jfr print --events jdk.ExecutionSample,jdk.NativeMethodSample,jdk.JVMInformation \
        --stack-depth 160 prof-b.jfr > prof.txt
    python3 jfr_hot.py prof.txt [--until-ms 17000] [--top 40] [--match REGEX]

Groups samples by thread (Render thread / reload workers / everything else),
keeps only samples taken before --until-ms of JVM uptime (the boot's
title-interactive mark), and prints for each group the methods with the most
INCLUSIVE samples (a method counted once per sample it appears anywhere in) and
the most SELF samples (top frame). Java samples are ~20 ms apart per running
thread (JFR "profile" settings), native ones ~20 ms: read the counts as relative
weight, not exact ms. --match keeps only frames whose method matches REGEX in
the inclusive list (e.g. 'com\\.origin|ModelManager').
"""
import argparse
import collections
import datetime
import re

TS = re.compile(r"startTime = (\d\d):(\d\d):(\d\d)\.(\d+)")
FRAME = re.compile(r"^\s+([\w$.<>/\[\]-]+\.[\w$<>-]+)\(")


def group_of(thread):
    if thread == "Render thread":
        return "render"
    if thread.startswith("Worker-") or "ResourceReload" in thread or thread.startswith("ForkJoinPool"):
        return "workers"
    return "other:" + re.sub(r"[-#]?\d+$", "", thread)


def parse(path):
    jvm_start = None
    events = []
    cur = None
    in_stack = False
    with open(path, errors="replace") as f:
        for line in f:
            s = line.strip()
            if s.startswith("jdk.JVMInformation"):
                cur = {"kind": "info"}
            elif s.startswith("jdk.ExecutionSample") or s.startswith("jdk.NativeMethodSample"):
                cur = {"kind": "sample", "stack": [], "thread": "?", "t": None}
            elif cur is None:
                continue
            elif cur["kind"] == "info" and s.startswith("jvmStartTime"):
                m = TS.search(s.replace("jvmStartTime", "startTime"))
                if m:
                    jvm_start = tuple(int(x) for x in m.groups()[:3]) + (int(m.group(4)[:3].ljust(3, "0")),)
            elif cur["kind"] == "sample":
                if s.startswith("startTime"):
                    m = TS.search(s)
                    if m:
                        cur["t"] = tuple(int(x) for x in m.groups()[:3]) + (int(m.group(4)[:3].ljust(3, "0")),)
                elif s.startswith("sampledThread"):
                    m = re.search(r'"(.*?)"', s)
                    cur["thread"] = m.group(1) if m else "?"
                elif s.startswith("stackTrace = ["):
                    in_stack = True
                elif in_stack and s == "]":
                    in_stack = False
                    events.append(cur)
                    cur = None
                elif in_stack:
                    m = FRAME.match(line)
                    if m:
                        cur["stack"].append(m.group(1))
    return jvm_start, events


def ms_of(t):
    h, m, s, ms = t
    return ((h * 60 + m) * 60 + s) * 1000 + ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--until-ms", type=int, default=0)
    ap.add_argument("--top", type=int, default=35)
    ap.add_argument("--match", default="")
    a = ap.parse_args()
    jvm_start, events = parse(a.file)
    start = ms_of(jvm_start) if jvm_start else None
    match = re.compile(a.match) if a.match else None
    incl = collections.defaultdict(collections.Counter)
    self_ = collections.defaultdict(collections.Counter)
    total = collections.Counter()
    for e in events:
        if not e["stack"]:
            continue
        if a.until_ms and start is not None and e["t"] is not None:
            up = ms_of(e["t"]) - start
            if up < 0:
                up += 24 * 3600 * 1000
            if up > a.until_ms:
                continue
        g = group_of(e["thread"])
        total[g] += 1
        self_[g][e["stack"][0]] += 1
        for fr in set(e["stack"]):
            if match is None or match.search(fr):
                incl[g][fr] += 1
    print("jvm start:", jvm_start, "samples:", sum(total.values()), "until-ms:", a.until_ms or "all")
    for g, n in total.most_common():
        if n < 20:
            continue
        print("\n=== %s: %d samples ===" % (g, n))
        print("-- inclusive --")
        for fr, c in incl[g].most_common(a.top):
            print("%6d %5.1f%%  %s" % (c, 100.0 * c / n, fr))
        print("-- self --")
        for fr, c in self_[g].most_common(12):
            print("%6d %5.1f%%  %s" % (c, 100.0 * c / n, fr))


if __name__ == "__main__":
    main()
