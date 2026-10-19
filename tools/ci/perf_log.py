#!/usr/bin/env python3
"""run.log gate for dev-shots / boot-smoke: mixin failures, Sodium + Iris really loaded, applied-mixin count.

Three things, all from the game's own log:
  1. FAIL on any `Mixin apply ... failed`, `InvalidInjection*` or `Critical injection failure` line.
     (dev-shots used to only grep for the first and the last and print "none".)
  2. With --require-loaded: FAIL unless Fabric's mod list names BOTH sodium and iris
     (`Loading N mods:` then `- sodium 0.4.10+build.27`). Without them the @Pseudo Sodium/Iris
     mixins have no target and require=0 hides it.
  3. Print how many Origin mixins were applied to Sodium / Iris classes, from Mixin's own
     `Mixing <mixin> from <config> into <target>` lines (run with -Dmixin.debug.verbose=true,
     which promotes them from DEBUG to INFO -- dev-shots.yml / smoke.py --perf set it). A wrong
     @Pseudo target shows up as a count lower than the module's Sodium/Iris mixin classes.
     Zero while Sodium/Iris are loaded is a warning, not a failure.

Usage: perf_log.py <run.log> [--module NAME] [--require-loaded]     exit 0 ok, 1 gate failed.
Also imported by tools/boot-smoke/smoke.py (tally()).
"""
import argparse
import os
import re
import sys

FAILURE = re.compile(r"Mixin apply(ing)? .*failed|InvalidInjection|Critical injection failure", re.I)
# Fabric Loader's mod list: "\t- sodium 0.4.10+build.27" (jar-in-jar children are "\-- ..." and never match).
LOADED = re.compile(r"^\W*- (sodium|iris) (\S+)", re.M)
MIXING = re.compile(r"Mixing (\S+) from (\S+) into (\S+)")
# A target is a Sodium/Iris class (any era's package root), or the mixin is named after them.
PERF_TARGET = re.compile(r"sodium|jellysquid|caffeinemc|iris|coderbot|irisshaders", re.I)
PERF_NAME = re.compile(r"(^|\.)(Sodium|Iris)\w*", re.I)
ORIGIN_CFG = re.compile(r"origin", re.I)


def failures(text):
    return [l.strip()[:220] for l in text.splitlines() if FAILURE.search(l)]


def loaded(text):
    """{'sodium': version, 'iris': version} for the ones the mod list names."""
    return {m.group(1).lower(): m.group(2) for m in LOADED.finditer(text)}


def tally(text):
    """Origin mixins applied to Sodium/Iris classes: sorted unique 'Mixin -> target' strings."""
    out = set()
    for m in MIXING.finditer(text):
        name, cfg, target = m.groups()
        if ORIGIN_CFG.search(cfg) and (PERF_TARGET.search(target) or PERF_NAME.search(name)):
            out.add(f"{name.rsplit('.', 1)[-1]} -> {target.rsplit('.', 1)[-1]}")
    return sorted(out)


SRC_PKG = re.compile(r"me\.jellysquid|net\.caffeinemc|net\.coderbot|net\.irisshaders")


def declared(module_dir):
    """Names of the module's own @Mixin classes that aim at Sodium/Iris (named Sodium*/Iris*, or source naming one of
    their package roots). Static, so it also lists the ones whose target class this run never loaded."""
    out = set()
    for root, _, files in os.walk(os.path.join(module_dir, "src")):
        if f"{os.sep}mixin" not in root:
            continue
        for fn in files:
            if not fn.endswith(".java"):
                continue
            with open(os.path.join(root, fn), encoding="utf-8", errors="replace") as f:
                src = f.read()
            if "@Mixin(" in src and (re.match(r"(Sodium|Iris)", fn) or SRC_PKG.search(src)):
                out.add(fn[:-5])
    return sorted(out)


def gate(text, module="", require_loaded=False, declared_names=None):
    """Returns (ok, [lines to print])."""
    lines, ok = [], True
    bad = failures(text)
    if bad:
        ok = False
        lines.append(f"::error title=mixin failure {module}::{len(bad)} mixin failure line(s); first: {bad[0]}")
        lines += [f"  {b}" for b in bad[:10]]
    have = loaded(text)
    lines.append(f"perf-gate: {module}: mods loaded: sodium={have.get('sodium', 'NOT LOADED')} iris={have.get('iris', 'NOT LOADED')}")
    if require_loaded and not ({"sodium", "iris"} <= set(have)):
        ok = False
        missing = sorted({"sodium", "iris"} - set(have))
        lines.append(f"::error title=perf mods {module}::{', '.join(missing)} not in the mod list -- the libs/ jars were not on the run classpath")
    applied = tally(text)
    lines.append(f"perf-gate: {module}: Origin Sodium/Iris mixins applied: {len(applied)}")
    lines += [f"  {a}" for a in applied]
    if declared_names:
        got = {a.split(" -> ")[0].rsplit(".", 1)[-1] for a in applied}
        missing = [d for d in declared_names if d not in got]
        lines.append(f"perf-gate: {module}: declared Sodium/Iris mixins: {len(declared_names)}, applied: {len(declared_names) - len(missing)}"
                     + (f", NOT applied (target class not loaded in this run, or a wrong target): {', '.join(missing)}" if missing else ""))
    if not applied and have:
        lines.append(f"::warning title=perf mixins {module}::Sodium/Iris loaded but no Origin mixin was applied to them "
                     f"(or -Dmixin.debug.verbose=true was not set: no 'Mixing ... into' lines)")
    return ok, lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_log")
    ap.add_argument("--module", default="")
    ap.add_argument("--require-loaded", action="store_true")
    ap.add_argument("--module-dir", default="", help="the module folder: also list its declared Sodium/Iris mixins vs applied")
    args = ap.parse_args()
    try:
        with open(args.run_log, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError as e:
        # A missing log must FAIL: an empty text has no failure lines, so the gate would pass on nothing.
        print(f"::error title=perf log::cannot read {args.run_log}: {e}")
        return 1
    if not text.strip():
        print(f"::error title=perf log::{args.run_log} is empty -- the game never logged, nothing was gated")
        return 1
    name = args.module or os.path.basename(os.path.dirname(os.path.abspath(args.run_log)))
    ok, lines = gate(text, name, args.require_loaded, declared(args.module_dir) if args.module_dir else None)
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("\n".join(l for l in lines if l.startswith("perf-gate")) + "\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
