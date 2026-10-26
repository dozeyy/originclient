#!/usr/bin/env python3
"""Tests for tools/bench/ab189.py: a variant is compared with the control of the SAME run and round only."""
import importlib.util
import json
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("bench_ab189", os.path.join(HERE, "ab189.py"))
A = importlib.util.module_from_spec(spec)
spec.loader.exec_module(A)


class Paired(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)

    def put(self, run, var, rn, fps, pause=0, valid=True):
        d = os.path.join(self.root, run, var, "run-%d" % rn)
        os.makedirs(d)
        scen = {"scenario": "idle", "valid": valid, "avgFps": fps, "low1Fps": fps / 2, "p50Ms": 1000.0 / fps,
                "p99Ms": 2000.0 / fps, "gcPauseTotalMs": pause, "gcPauseMaxMs": pause, "heapAvgMb": 500.0}
        with open(os.path.join(d, "bench.json"), "w", encoding="utf-8") as f:
            json.dump({"scenarios": [scen]}, f)

    def test_runner_speed_cancels(self):
        # run A is a fast runner, run B a slow one; the variant is 10% faster on both
        self.put("A", "origin-default", 1, 40.0, pause=20)
        self.put("A", "jvm-x", 1, 44.0, pause=5)
        self.put("B", "origin-default", 1, 20.0, pause=20)
        self.put("B", "jvm-x", 1, 22.0, pause=35)
        d = A.paired(A.load([os.path.join(self.root, "A"), os.path.join(self.root, "B")]))
        row = d["jvm-x"]["idle"]
        self.assertEqual([round(v, 6) for v in row["avgFps"]], [10.0, 10.0])
        self.assertEqual(sorted(row["gcPauseTotalMs"]), [-15, 15])      # + = less pause than the control
        self.assertIn("| idle | 2 | +10.0% [+10.0..+10.0]", A.report(d))

    def put_startup(self, var, rn, ms):
        d = os.path.join(self.root, "A", var, "run-%d" % rn)
        os.makedirs(d)
        with open(os.path.join(d, "bench.json"), "w", encoding="utf-8") as f:
            json.dump({"environment": {"startupMs": ms}, "scenarios": []}, f)

    def test_startup_is_paired_like_a_scene(self):
        # optifine-only boots in 20 s, origin-default in 25 s: Origin costs 25% more time to title (- = worse)
        self.put_startup("origin-default", 1, 25000)
        self.put_startup("optifine-only", 1, 20000)
        self.put_startup("off-hud", 1, -1)          # title never seen: no row, never a fake 0
        d = A.paired(A.load([os.path.join(self.root, "A")]))
        self.assertEqual(d["optifine-only"]["startup"]["startupMs"], [20.0])
        self.assertNotIn("off-hud", d)
        self.assertIn("| startup | 1 |", A.report(d))

    def test_no_control_in_that_round_means_no_pair(self):
        self.put("A", "origin-default", 1, 40.0)
        self.put("A", "jvm-x", 2, 44.0)
        self.put("A", "jvm-y", 1, 30.0, valid=False)
        self.assertEqual(A.paired(A.load([os.path.join(self.root, "A")])), {})


if __name__ == "__main__":
    unittest.main()
