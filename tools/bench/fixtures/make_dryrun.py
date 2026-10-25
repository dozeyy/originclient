#!/usr/bin/env python3
"""Writes tools/bench/fixtures/dryrun/: SYNTHETIC benchmark runs for `bench-189.ps1 -DryRun` and its test.

Nothing here was measured. Each run is 300 frames of `base` ms with ten 4x spikes, so every figure the
report prints is hand-computable (run 2 is the median run): total = 290*b + 10*4b = 330b ms,
avg FPS = 300 / (330b/1000) = 909.09 / b.

Re-run:  python tools/bench/fixtures/make_dryrun.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "dryrun")
MS = 1_000_000

# variant -> scene -> base frame ms of the middle run (runs 1 and 3 are +-0.5 ms)
BASE = {
    "optifine-only": {"idle": 9.0, "flythrough": 15.0},
    "origin-default": {"idle": 10.0, "flythrough": 16.0},
    "competitive": {"idle": 8.0, "flythrough": 12.0},
}
CPU = {1: (3, 12, 9), 2: (2, 11, 9), 3: (4, 13, 9)}      # before %, during % total, game %


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    write(os.path.join(OUT, "machine.json"), json.dumps({
        "gpu": "Fixture GPU 9000 (synthetic)", "gpuDriver": "1.2.3.4", "monitor": "1920x1080",
        "cpu": "Fixture CPU 8-Core", "cpuCores": "8 cores / 16 threads", "ramGb": 32,
        "os": "Windows 11 (fixture)", "powerPlan": "Balanced (fixture)",
        "java": "Temurin 8u504 (fixture)", "build": "originclient-1.8.9-fixture"}, indent=2) + "\n")
    for variant, scenes in BASE.items():
        for run in (1, 2, 3):
            d = os.path.join(OUT, variant, "run-%d" % run)
            entries = []
            for scene, b in scenes.items():
                base = b + (run - 2) * 0.5
                frames = []
                for i in range(300):
                    frames.append(int(base * MS * (4 if i % 30 == 29 else 1)))
                write(os.path.join(d, "frames-%s.csv" % scene), "frame_ns\n" + "".join("%d\n" % x for x in frames))
                entries.append({"scenario": scene, "frames": 300, "valid": True, "gcCount": 2 + run,
                                "gcTimeMs": 30 + run, "gcPauseCount": 2 + run, "gcPauseTotalMs": 30 + run,
                                "gcPauseMaxMs": 20 + run, "renderThreadAllocMbPerSec": round(40 + base, 2),
                                "heapAvgMb": 800 + 10 * run})
            write(os.path.join(d, "bench.json"), json.dumps({"environment": {
                "gpu": "Fixture GPU 9000 (synthetic)", "optifine": True, "resolution": "1920x1080",
                "variant": variant}, "scenarios": entries}, indent=2) + "\n")
            before, during, game = CPU[run]
            write(os.path.join(d, "meta.json"), json.dumps({
                "cpuBeforePct": before, "cpuDuringPct": during, "gameCpuPct": game,
                "otherCpuPct": during - game, "samples": 12, "status": "ok"}, indent=2) + "\n")


if __name__ == "__main__":
    main()
