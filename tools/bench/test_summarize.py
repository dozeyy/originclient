#!/usr/bin/env python3
"""Tests for tools/bench/summarize.py -- a known CSV gives known numbers, a corrupt one fails loudly.

Run: python -m pytest tools/bench -q      (CI)
     python -m unittest discover tools/bench   (no pytest needed)
"""
import contextlib
import importlib.util
import io
import json
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def _load():
    spec = importlib.util.spec_from_file_location("bench_summarize", os.path.join(HERE, "summarize.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


S = _load()

MS = 1_000_000


def known_frames():
    """980 frames of 10 ms and 20 of 50 ms: every figure below is hand-computable.

    total 10.8 s -> avg 1000/10.8 = 92.59 fps; 2 % of frames are slow, so p99 = 50 ms;
    slowest 1 % (10 frames) are all 50 ms -> 1 % low = 20 fps; mean 10.8 ms,
    variance (980*0.8^2 + 20*39.2^2)/1000 = 31.36 -> stdev 5.6 ms.
    """
    return [10 * MS] * 490 + [50 * MS] * 20 + [10 * MS] * 490


def write_csv(path, frames, header="frame_ns"):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header + "\n")
        for x in frames:
            f.write("%d\n" % x)


def scene_entry(name="idle", frames=1000, **extra):
    e = {"scenario": name, "frames": frames, "valid": True, "gcCount": 3, "gcTimeMs": 42,
         "renderThreadAllocMbPerSec": 12.34, "heapAvgMb": 812.3}
    e.update(extra)
    return e


def write_run(run_dir, scenes, env=None):
    """scenes: {name: (frames list, extra bench.json fields)}"""
    os.makedirs(run_dir, exist_ok=True)
    entries = []
    for name, (frames, extra) in scenes.items():
        write_csv(os.path.join(run_dir, "frames-%s.csv" % name), frames)
        entries.append(scene_entry(name, len(frames), **extra))
    with open(os.path.join(run_dir, "bench.json"), "w", encoding="utf-8") as f:
        json.dump({"environment": env or {"gpu": "test"}, "scenarios": entries}, f)


class Tmp(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="bench-test-")
        self.addCleanup(shutil.rmtree, self.dir, True)

    def p(self, *parts):
        return os.path.join(self.dir, *parts)


class KnownNumbers(Tmp):
    def test_known_csv_gives_known_numbers(self):
        path = self.p("frames.csv")
        write_csv(path, known_frames())
        st = S.frame_stats(S.read_frames(path))
        self.assertEqual(st["frames"], 1000)
        self.assertEqual(st["seconds"], 10.8)
        self.assertEqual(st["avgFps"], 92.6)
        self.assertEqual(st["p50Ms"], 10.0)
        self.assertEqual(st["p95Ms"], 10.0)
        self.assertEqual(st["p99Ms"], 50.0)
        self.assertEqual(st["maxMs"], 50.0)
        self.assertEqual(st["low1Fps"], 20.0)
        self.assertEqual(st["low01Fps"], 20.0)
        self.assertEqual(st["stdevMs"], 5.6)
        self.assertEqual(st["hitches20ms"], 20)

    def test_mixed_slow_tail_pins_the_1pct_and_01pct_windows(self):
        """2000 frames: 1980 x 10 ms, 10 x 50 ms, 10 x 30 ms. By hand:
        1 % window = ceil(2000*0.01) = 20 frames = the 10 x 50 + 10 x 30 -> mean 40 ms -> 25.0 fps.
        0.1 % window = ceil(2000*0.001) = 2 frames = both 50 ms -> 20.0 fps.
        A doubled window (40 frames) would add 20 x 10 ms -> mean 25 ms -> 40 fps; a halved 1 % window
        (10 frames, all 50 ms) would give 20 fps, so 25.0 fails both mistakes. total 20.6 s -> avg 97.09 fps.
        p50 = p95 = p99 = 10 ms (the 99th percentile is rank 1980, still a 10 ms frame)."""
        path = self.p("tail.csv")
        write_csv(path, [10 * MS] * 900 + [50 * MS] * 10 + [10 * MS] * 540 + [30 * MS] * 10 + [10 * MS] * 540)
        st = S.frame_stats(S.read_frames(path))
        self.assertEqual(st["frames"], 2000)
        self.assertEqual(st["seconds"], 20.6)
        self.assertEqual(st["avgFps"], 97.1)
        self.assertEqual(st["low1Fps"], 25.0)
        self.assertEqual(st["low01Fps"], 20.0)
        self.assertEqual(st["p50Ms"], 10.0)
        self.assertEqual(st["p99Ms"], 10.0)
        self.assertEqual(st["maxMs"], 50.0)
        self.assertEqual(st["hitches20ms"], 20)

    def test_order_of_frames_does_not_change_the_numbers(self):
        a, b = self.p("a.csv"), self.p("b.csv")
        fr = known_frames()
        write_csv(a, fr)
        write_csv(b, list(reversed(fr)))
        self.assertEqual(S.frame_stats(S.read_frames(a)), S.frame_stats(S.read_frames(b)))

    def test_single_frame(self):
        path = self.p("one.csv")
        write_csv(path, [16_666_667])
        st = S.frame_stats(S.read_frames(path))
        self.assertEqual(st["frames"], 1)
        self.assertEqual(st["avgFps"], 60.0)
        self.assertEqual(st["low1Fps"], 60.0)
        self.assertEqual(st["stdevMs"], 0.0)

    def test_all_equal_frames_have_equal_lows(self):
        path = self.p("flat.csv")
        write_csv(path, [8 * MS] * 500)
        st = S.frame_stats(S.read_frames(path))
        self.assertEqual(st["avgFps"], 125.0)
        self.assertEqual(st["low1Fps"], 125.0)
        self.assertEqual(st["p99Ms"], 8.0)

    def test_extra_columns_are_ignored_but_header_is_used(self):
        path = self.p("wide.csv")
        with open(path, "w", encoding="utf-8") as f:
            f.write("hook_ns,frame_ns\n5,10000000\n6,10000000\n")
        self.assertEqual(S.read_frames(path), [10 * MS, 10 * MS])


class RunFolder(Tmp):
    def test_run_summary_combines_csv_and_game_counters(self):
        run = self.p("run-1")
        write_run(run, {"idle": (known_frames(), {"gcPauseTotalMs": 40.5, "gcPauseMaxMs": 18.2})})
        res = S.summarize_run(run)
        idle = res["scenes"]["idle"]
        self.assertEqual(idle["avgFps"], 92.6)
        self.assertEqual(idle["gcPauseTotalMs"], 40.5)
        self.assertEqual(idle["gcPauseMaxMs"], 18.2)
        self.assertEqual(idle["gcPauseSource"], "gc-notifications")
        self.assertEqual(idle["allocMbPerSec"], 12.34)
        self.assertEqual(res["environment"], {"gpu": "test"})

    def test_old_run_without_pause_data_reports_null_max_not_a_guess(self):
        run = self.p("run-1")
        write_run(run, {"idle": (known_frames(), {})})
        idle = S.summarize_run(run)["scenes"]["idle"]
        self.assertEqual(idle["gcPauseTotalMs"], 42.0)
        self.assertIsNone(idle["gcPauseMaxMs"])
        self.assertEqual(idle["gcPauseSource"], "collector-total-time")

    def test_alloc_minus_one_means_unknown(self):
        run = self.p("run-1")
        write_run(run, {"idle": (known_frames(), {"renderThreadAllocMbPerSec": -1})})
        self.assertIsNone(S.summarize_run(run)["scenes"]["idle"]["allocMbPerSec"])


class CorruptInput(Tmp):
    """Every kind of damage stops the summary with SummaryError (CLI exit 2); nothing is skipped."""

    def check_csv(self, text, needle):
        path = self.p("bad.csv")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        with self.assertRaises(S.SummaryError) as cm:
            S.read_frames(path)
        self.assertIn(needle, str(cm.exception))
        self.assertIn("bad.csv", str(cm.exception))

    def test_empty_file(self):
        self.check_csv("", "empty file")

    def test_header_only(self):
        self.check_csv("frame_ns\n", "no frames")

    def test_wrong_header(self):
        self.check_csv("time\n100\n", "frame_ns")

    def test_text_in_a_frame(self):
        self.check_csv("frame_ns\n100\nabc\n", "bad.csv:3")

    def test_float_is_not_a_frame(self):
        self.check_csv("frame_ns\n16.6\n", "not an integer")

    def test_negative_and_zero(self):
        self.check_csv("frame_ns\n100\n-5\n", "positive")
        self.check_csv("frame_ns\n0\n", "positive")

    def test_blank_line_in_the_middle(self):
        self.check_csv("frame_ns\n100\n\n100\n", "blank line")

    def test_row_with_extra_columns(self):
        self.check_csv("frame_ns\n100,200\n", "columns")

    def test_binary_garbage(self):
        path = self.p("bad.csv")
        with open(path, "wb") as f:
            f.write(b"\xff\xfe\x00\x80\x81")
        with self.assertRaises(S.SummaryError):
            S.read_frames(path)

    def test_missing_file(self):
        with self.assertRaises(S.SummaryError):
            S.read_frames(self.p("nope.csv"))

    def test_truncated_csv_is_caught_against_bench_json(self):
        run = self.p("run-1")
        write_run(run, {"idle": (known_frames(), {})})
        write_csv(os.path.join(run, "frames-idle.csv"), known_frames()[:900])
        with self.assertRaises(S.SummaryError) as cm:
            S.summarize_run(run)
        self.assertIn("900 frames", str(cm.exception))
        self.assertIn("1000", str(cm.exception))

    def test_listed_scene_without_csv(self):
        run = self.p("run-1")
        write_run(run, {"idle": (known_frames(), {})})
        os.remove(os.path.join(run, "frames-idle.csv"))
        with self.assertRaises(S.SummaryError):
            S.summarize_run(run)

    def test_no_bench_json_means_the_run_did_not_finish(self):
        run = self.p("run-1")
        os.makedirs(run)
        write_csv(os.path.join(run, "frames-idle.csv"), known_frames())
        with self.assertRaises(S.SummaryError) as cm:
            S.summarize_run(run)
        self.assertIn("bench.json", str(cm.exception))

    def test_cli_exits_2_and_says_why(self):
        run = self.p("run-1")
        write_run(run, {"idle": (known_frames(), {})})
        with open(os.path.join(run, "frames-idle.csv"), "a", encoding="utf-8") as f:
            f.write("oops\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = S.main(["run", run])
        self.assertEqual(code, 2)
        self.assertIn("FAILED", err.getvalue())
        self.assertIn("oops", err.getvalue())

    def test_cli_ok_run_exits_0_and_writes_json(self):
        run = self.p("run-1")
        write_run(run, {"idle": (known_frames(), {})})
        out = self.p("out", "summary.json")
        with contextlib.redirect_stdout(io.StringIO()):
            code = S.main(["run", run, "--out", out])
        self.assertEqual(code, 0)
        with open(out, encoding="utf-8") as f:
            doc = json.load(f)
        self.assertEqual(doc["label"], "CI, relative only")
        self.assertEqual(doc["scenes"]["idle"]["avgFps"], 92.6)


class Aggregate(Tmp):
    def build(self):
        # baseline: 10 ms frames (100 fps); fast: 8 ms frames (125 fps). Three runs each, slightly different.
        for variant, base_ms in (("optifine-only", 10), ("origin-default", 8)):
            for i, jitter in enumerate((0, 1, -1), start=1):
                ms = base_ms + jitter * 0.5 * (1 if variant == "optifine-only" else 0)
                write_run(self.p("root", variant, "run-%d" % i),
                          {"idle": ([int(ms * MS)] * 400, {"gcPauseTotalMs": 10 * i, "gcPauseMaxMs": 5.0})})

    def test_median_min_max_and_delta(self):
        self.build()
        agg = S.aggregate(self.p("root"), baseline="optifine-only")
        base = agg["variants"]["optifine-only"]["scenes"]["idle"]["avgFps"]
        # frame times 10, 10.5, 9.5 ms -> 100, 95.2, 105.3 fps
        self.assertEqual(base["median"], 100.0)
        self.assertEqual(base["min"], 95.2)
        self.assertEqual(base["max"], 105.3)
        new = agg["variants"]["origin-default"]["scenes"]["idle"]["avgFps"]
        self.assertEqual(new["median"], 125.0)
        self.assertEqual(agg["deltaPctVsBaseline"]["origin-default"]["idle"]["avgFps"], 25.0)
        self.assertEqual(agg["variants"]["origin-default"]["scenes"]["idle"]["gcPauseTotalMs"]["median"], 20.0)
        self.assertEqual(agg["label"], "CI, relative only")

    def test_markdown_carries_the_label_and_every_variant(self):
        self.build()
        md = S.to_markdown(S.aggregate(self.p("root"), baseline="optifine-only"))
        self.assertIn("CI, relative only", md)
        self.assertIn("## optifine-only", md)
        self.assertIn("## origin-default", md)
        self.assertIn("+25.0%", md)

    def test_invalid_scene_is_left_out_and_named(self):
        self.build()
        path = self.p("root", "origin-default", "run-2", "bench.json")
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        doc["scenarios"][0]["valid"] = False
        with open(path, "w", encoding="utf-8") as f:
            json.dump(doc, f)
        agg = S.aggregate(self.p("root"), baseline="optifine-only")
        v = agg["variants"]["origin-default"]
        self.assertEqual(v["scenes"]["idle"]["runs"], 2)
        self.assertEqual(v["leftOutInvalid"], ["run-2/idle"])

    def test_one_corrupt_run_fails_the_whole_aggregate(self):
        self.build()
        with open(self.p("root", "origin-default", "run-3", "frames-idle.csv"), "a", encoding="utf-8") as f:
            f.write("x\n")
        with self.assertRaises(S.SummaryError):
            S.aggregate(self.p("root"), baseline="optifine-only")

    def test_require_valid_fails_when_every_run_of_a_variant_is_invalid(self):
        self.build()
        for i in (1, 2, 3):
            path = self.p("root", "origin-default", "run-%d" % i, "bench.json")
            with open(path, encoding="utf-8") as f:
                doc = json.load(f)
            doc["scenarios"][0]["valid"] = False
            with open(path, "w", encoding="utf-8") as f:
                json.dump(doc, f)
        agg = S.aggregate(self.p("root"), baseline="optifine-only")
        with self.assertRaises(S.SummaryError) as c:
            S.require_valid(agg)
        self.assertIn("origin-default", str(c.exception))
        self.assertEqual(S.main(["aggregate", self.p("root"), "--baseline", "optifine-only", "--require-valid"]), 2)
        # without the flag the same data still summarizes (empty tables), so it is the flag that makes CI red
        self.assertEqual(S.main(["aggregate", self.p("root"), "--baseline", "optifine-only"]), 0)

    def test_require_valid_passes_on_good_data(self):
        self.build()
        S.require_valid(S.aggregate(self.p("root"), baseline="optifine-only"))
        self.assertEqual(S.main(["aggregate", self.p("root"), "--baseline", "optifine-only", "--require-valid"]), 0)

    def test_probe_without_hud_scene_still_counts_as_valid(self):
        # the OptiFine-only probe skips hud on purpose; an Origin variant without hud is still a failure
        for i in (1, 2):
            write_run(self.p("root", "optifine-only", "run-%d" % i), {"idle": ([10 * MS] * 400, {})},
                      env={"originActive": False})
            write_run(self.p("root", "origin-default", "run-%d" % i),
                      {"idle": ([10 * MS] * 400, {}), "hud": ([11 * MS] * 400, {})}, env={"originActive": True})
        S.require_valid(S.aggregate(self.p("root"), baseline="optifine-only"))
        write_run(self.p("root", "off-hud", "run-1"), {"idle": ([10 * MS] * 400, {})}, env={"originActive": True})
        with self.assertRaises(S.SummaryError) as c:
            S.require_valid(S.aggregate(self.p("root"), baseline="optifine-only"))
        self.assertIn("off-hud has no valid run of: hud", str(c.exception))

    def test_startup_time_is_spread_per_variant_and_printed(self):
        for i, ms in enumerate((20000, 22000, 21000), start=1):
            write_run(self.p("root", "origin-default", "run-%d" % i), {"idle": ([10 * MS] * 400, {})},
                      env={"startupMs": ms})
        write_run(self.p("root", "origin-default", "run-4"), {"idle": ([10 * MS] * 400, {})},
                  env={"startupMs": -1})   # title never seen: left out, not counted as 0
        agg = S.aggregate(self.p("root"))
        self.assertEqual(agg["variants"]["origin-default"]["startupS"],
                         {"median": 21.0, "min": 20.0, "max": 22.0, "n": 3})
        self.assertIn("Time to title screen (JVM start -> title, s): 21.0 (20.0-22.0)", S.to_markdown(agg))

    def _slow_variant(self, factor):
        """A third variant whose every frame is `factor` x the baseline's 10 ms (so avg FPS and 1 % low drop)."""
        for i in (1, 2, 3):
            write_run(self.p("root", "slow", "run-%d" % i), {"idle": ([int(10 * factor * MS)] * 400, {})})

    def test_max_regression_pct_fails_on_a_x13_frame_time(self):
        self.build()
        self._slow_variant(1.3)
        agg = S.aggregate(self.p("root"), baseline="optifine-only")
        # 13 ms frames = 76.9 fps against 100: -23.1 % on both avg FPS and 1 % low
        self.assertEqual(agg["deltaPctVsBaseline"]["slow"]["idle"]["avgFps"], -23.1)
        self.assertEqual(agg["deltaPctVsBaseline"]["slow"]["idle"]["low1Fps"], -23.1)
        with self.assertRaises(S.SummaryError) as c:
            S.check_regression(agg, 15)
        self.assertIn("slow idle avgFps -23.1%", str(c.exception))
        self.assertIn("slow idle low1Fps", str(c.exception))
        S.check_regression(agg, 25)      # a looser limit lets it through
        base = ["aggregate", self.p("root"), "--baseline", "optifine-only"]
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(S.main(base + ["--max-regression-pct", "15"]), 2)
            self.assertEqual(S.main(base + ["--max-regression-pct", "25"]), 0)
            self.assertEqual(S.main(base), 0)   # opt-in: without the flag nothing is gated
        # the summary is written before the gate trips, so a red run still leaves its numbers behind
        self.assertTrue(os.path.isfile(self.p("root", "summary.json")))

    def test_max_regression_pct_ignores_gains_and_is_not_fooled_by_a_better_variant(self):
        self.build()      # origin-default is +25 % over the baseline
        S.check_regression(S.aggregate(self.p("root"), baseline="optifine-only"), 0)

    def test_max_regression_pct_fails_when_the_baseline_lacks_a_scene(self):
        self.build()
        for i in (1, 2, 3):   # a variant measured a scene ("menu") the baseline never did, and it is 3x slower
            write_run(self.p("root", "origin-default", "run-%d" % i),
                      {"idle": ([8 * MS] * 400, {}), "menu": ([30 * MS] * 400, {})})
        agg = S.aggregate(self.p("root"), baseline="optifine-only")
        self.assertNotIn("menu", agg["deltaPctVsBaseline"]["origin-default"])   # the silent skip this guards
        with self.assertRaises(S.SummaryError) as c:
            S.check_regression(agg, 20)
        self.assertIn("origin-default menu avgFps", str(c.exception))
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(S.main(["aggregate", self.p("root"), "--baseline", "optifine-only",
                                     "--max-regression-pct", "20"]), 2)

    def test_max_regression_pct_skips_origin_only_scenes_the_inert_probe_never_runs(self):
        for i in (1, 2, 3):
            write_run(self.p("root", "optifine-only", "run-%d" % i), {"idle": ([10 * MS] * 400, {})},
                      env={"originActive": False})
            write_run(self.p("root", "origin-default", "run-%d" % i),
                      {"idle": ([10 * MS] * 400, {}), "hud": ([11 * MS] * 400, {}),
                       "hudedit": ([11 * MS] * 400, {}), "hudrobot": ([11 * MS] * 400, {})},
                      env={"originActive": True})
        S.check_regression(S.aggregate(self.p("root"), baseline="optifine-only"), 20)
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(S.main(["aggregate", self.p("root"), "--baseline", "optifine-only",
                                     "--max-regression-pct", "20"]), 0)
        # a scene the probe could have run ("menu") still fails the gate when the baseline lacks it
        for i in (1, 2, 3):
            write_run(self.p("root", "origin-default", "run-%d" % i),
                      {"idle": ([10 * MS] * 400, {}), "hud": ([11 * MS] * 400, {}), "menu": ([11 * MS] * 400, {})},
                      env={"originActive": True})
        with self.assertRaises(S.SummaryError) as c:
            S.check_regression(S.aggregate(self.p("root"), baseline="optifine-only"), 20)
        self.assertIn("origin-default menu avgFps", str(c.exception))
        self.assertNotIn("hud ", str(c.exception))

    def test_max_regression_pct_rejects_a_negative_limit(self):
        self.build()
        with self.assertRaises(S.SummaryError):
            S.check_regression(S.aggregate(self.p("root"), baseline="optifine-only"), -1)

    def test_unknown_baseline_fails(self):
        self.build()
        with self.assertRaises(S.SummaryError):
            S.aggregate(self.p("root"), baseline="nope")

    def test_empty_root_fails(self):
        os.makedirs(self.p("root"))
        with self.assertRaises(S.SummaryError):
            S.aggregate(self.p("root"))


if __name__ == "__main__":
    unittest.main()
