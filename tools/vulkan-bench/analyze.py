"""Bench analysis: per run + per case stats, then medians over reps.
usage: python analyze.py <runs-dir> [prefix-filter]
Run dir names: <group>_<stack>_<pack>_r<N>  (e.g. P_gl_none_r1)."""
import os, re, sys, statistics as st

root = sys.argv[1]
flt = sys.argv[2] if len(sys.argv) > 2 else ""


def stats(ft):
    ft = [x for x in ft if x > 0]
    n = len(ft)
    tot = sum(ft) / 1e9
    s = sorted(ft)
    worst = s[-max(1, n // 100):]
    p99 = s[min(n - 1, int(n * 0.99))] / 1e6
    return {"n": n, "sec": tot, "avg": n / tot, "low1": 1e9 / (sum(worst) / len(worst)), "p99": p99,
            "max": s[-1] / 1e6, "over16": sum(1 for x in ft if x > 16.7e6), "over50": sum(1 for x in ft if x > 50e6)}


rows = []
for d in sorted(os.listdir(root)):
    if not d.startswith(flt) or not re.search(r"_r\d+$", d):
        continue
    p = os.path.join(root, d)
    log = os.path.join(p, "bench.log")
    if not os.path.exists(log):
        continue
    lines = open(log, encoding="utf-8", errors="replace").read().splitlines()
    pid = None
    cases = {}
    for ln in lines:
        m = re.search(r"armed: pid=(\d+)", ln)
        if m:
            pid = m.group(1)
        m = re.search(r"case (\S+) terrain ready: readyMs=(\d+)", ln)
        if m:
            cases.setdefault(m.group(1), {})["ready"] = int(m.group(2))
        m = re.search(r"case (\S+) terrain NOT ready", ln)
        if m:
            cases.setdefault(m.group(1), {})["ready"] = None
        m = re.search(r"case (\S+) sample start epochMs=(\d+) focused=(\S+)", ln)
        if m:
            c = cases.setdefault(m.group(1), {})
            c["t0"] = int(m.group(2)); c["focus0"] = m.group(3)
        m = re.search(r"case (\S+) sample end epochMs=(\d+).* focused=(\S+)", ln)
        if m:
            c = cases.setdefault(m.group(1), {})
            c["t1"] = int(m.group(2)); c["focus1"] = m.group(3)
    boot = None
    gl = os.path.join(p, "game.log")
    if os.path.exists(gl):
        for ln in open(gl, encoding="utf-8", errors="replace"):
            m = re.search(r"\[OriginBoot\] mark title-screen-set (\d+)", ln)
            if m:
                boot = int(m.group(1))
    gpu = []
    gp = os.path.join(p, "gpu.txt")
    if os.path.exists(gp):
        for ln in open(gp):
            parts = ln.split()
            if len(parts) < 2:
                continue
            kv = dict(x.split("=") for x in parts[2:] if "=" in x)
            gpu.append((int(parts[0]), float(parts[1]), kv))
    for name, c in cases.items():
        f = os.path.join(p, name + ".frames")
        if not os.path.exists(f) or "t0" not in c:
            continue
        ft = [int(x) for x in open(f).read().split()]
        s = stats(ft)
        win = [g for g in gpu if c["t0"] + 1500 <= g[0] <= c.get("t1", 0)]
        game = [float(g[2].get(pid, 0)) for g in win]
        others = [sum(float(v) for k, v in g[2].items() if k != pid) for g in win]
        cpu = [g[1] for g in win]
        s.update({"run": d, "case": name, "ready": c.get("ready"), "boot": boot,
                  "gpu": st.mean(game) if game else None, "gpuOther": st.mean(others) if others else None,
                  "cpu": st.mean(cpu) if cpu else None, "focus": c.get("focus0", "?") + "/" + c.get("focus1", "?")})
        rows.append(s)


def f(v, fmt="{:.0f}"):
    return "-" if v is None else fmt.format(v)


print("## per run")
print("run | case | frames | avg FPS | 1% low | p99 ms | max ms | >16.7ms | ready ms | boot ms | game GPU% | other GPU% | CPU% | focus")
for r in rows:
    print(f"{r['run']} | {r['case']} | {r['n']} | {r['avg']:.0f} | {r['low1']:.0f} | {r['p99']:.2f} | {r['max']:.1f} | {r['over16']} | {f(r['ready'])} | {f(r['boot'])} | {f(r['gpu'])} | {f(r['gpuOther'])} | {f(r['cpu'])} | {r['focus']}")

print("\n## medians over reps")
print("config | case | reps | avg FPS (each) | median avg | median 1% low | median p99 ms | median ready ms | median boot ms | game GPU% | CPU%")
groups = {}
for r in rows:
    cfg = re.sub(r"_r\d+$", "", r["run"])
    groups.setdefault((cfg, r["case"]), []).append(r)


def med(rs, k):
    v = [r[k] for r in rs if r[k] is not None]
    return st.median(v) if v else None


for (cfg, case), rs in sorted(groups.items(), key=lambda x: (x[0][1], x[0][0])):
    each = "/".join(f"{r['avg']:.0f}" for r in rs)
    print(f"{cfg} | {case} | {len(rs)} | {each} | {f(med(rs,'avg'))} | {f(med(rs,'low1'))} | {f(med(rs,'p99'), '{:.2f}')} | {f(med(rs,'ready'))} | {f(med(rs,'boot'))} | {f(med(rs,'gpu'))} | {f(med(rs,'cpu'))}")
