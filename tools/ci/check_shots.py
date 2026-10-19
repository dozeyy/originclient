#!/usr/bin/env python3
"""dev-shots gate: fail a module's job when it captured fewer PNGs than its set planned.

Why: a dev-shots job once went green with ZERO screenshots -- Gradle died on a
Maven 429 before the game started, and nothing checked the output (the v1.0.57
sweep, shots-1.21.11/run.log). A gate that can't fail is worse than none.

The in-mod harness (every module's OriginDevShots / OriginDevWorld) prints exactly
one line when it finishes:
    [dev-shots] done — <N> shots in <dir>     (screen sets: N = the planned matrix)
    [dev-shots] done — world set in <dir>     (world set: count varies, >= 1)
This script needs that line AND at least that many PNGs in the folder. No line =
the harness never finished (the game didn't boot, wedged, or was killed).

Usage: check_shots.py <screenshots-dir> <run.log> [--module NAME]
Exit 0 = every planned shot is on disk. 1 = not.
"""
import argparse
import os
import re
import sys

DONE = re.compile(r"\[dev-shots\] done \S* (?:(\d+) shots|world set)")
STEP_FAILED = re.compile(r"\[dev-shots\] step (\d+) failed")
# What usually explains a run that never finished (shown in the failure message).
HINTS = re.compile(r"\b429\b|Too Many Requests|Could not (GET|HEAD|download)|What went wrong|"
                   r"Mixin apply(ing)? .*failed|Exception in thread \"main\"|BUILD FAILED|timed out", re.I)


def check(shots_dir, log_text):
    """Returns (ok, message)."""
    pngs = sorted(f for f in os.listdir(shots_dir) if f.lower().endswith(".png")) if os.path.isdir(shots_dir) else []
    done = None
    for m in DONE.finditer(log_text):
        done = m
    if done is None:
        hints = [l.strip()[:200] for l in log_text.splitlines() if HINTS.search(l)][:6]
        msg = (f"{len(pngs)} PNGs, and the harness never printed '[dev-shots] done' -- the game "
               f"didn't boot, wedged, or was killed before finishing.")
        if hints:
            msg += " First clues in run.log:\n  " + "\n  ".join(hints)
        return False, msg
    expected = int(done.group(1)) if done.group(1) else 1
    failed = sorted({int(s) for s in STEP_FAILED.findall(log_text)})
    if len(pngs) < expected or not pngs:
        msg = f"{len(pngs)}/{expected} PNGs -- {expected - len(pngs)} planned shot(s) missing."
        if failed:
            msg += f" Harness steps that threw: {failed} (grep '[dev-shots] step' in run.log)."
        return False, msg
    label = f"{len(pngs)}/{expected}" if done.group(1) else f"{len(pngs)} (world set)"
    return True, f"{label} PNGs"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("shots_dir")
    ap.add_argument("run_log")
    ap.add_argument("--module", default="")
    args = ap.parse_args()
    try:
        with open(args.run_log, encoding="utf-8", errors="replace") as f:
            log_text = f.read()
    except OSError:
        log_text = ""
    ok, msg = check(args.shots_dir, log_text)
    name = args.module or os.path.basename(os.path.dirname(os.path.abspath(args.run_log)))
    line = f"dev-shots: {name}: {'PASS' if ok else 'FAIL'} -- {msg}"
    print(line if ok else f"::error title=dev-shots {name}::{msg.splitlines()[0]}\n{line}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"- {line.splitlines()[0]}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
