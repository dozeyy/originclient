#!/usr/bin/env python3
"""Tests for the CI gates themselves -- proof each one CAN fail (build-check runs this).

  check_shots.py : the dev-shots PNG-count gate
  smoke.py       : boot-smoke's line classifier, ready marker and retry decision

Run: python tools/ci/test_ci_gates.py
"""
import importlib.util
import os
import re
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


shots = _load("check_shots", os.path.join(HERE, "check_shots.py"))
smoke = _load("smoke", os.path.join(ROOT, "tools", "boot-smoke", "smoke.py"))

DONE3 = "[12:00:01] [Render thread/INFO] (originclient) [dev-shots] done — 3 shots in /tmp/shots\n"


class CheckShots(unittest.TestCase):
    def _dir(self, n):
        d = tempfile.mkdtemp()
        for i in range(n):
            open(os.path.join(d, f"title_1920x1080_s{i}.png"), "wb").close()
        open(os.path.join(d, "_sheet.jpg"), "wb").close()   # never counted
        return d

    def test_zero_pngs_after_maven_429_fails(self):
        # The v1.0.57 false green: Gradle died on a 429, no game, no shots.
        log = "> Could not GET 'https://repo.maven.apache.org/x.pom'. Received status code 429\nBUILD FAILED\n"
        ok, msg = shots.check(self._dir(0), log)
        self.assertFalse(ok)
        self.assertIn("429", msg)

    def test_missing_done_line_fails_even_with_pngs(self):
        ok, _ = shots.check(self._dir(3), "[dev-shots] captured title_1920x1080_s0.png\n")
        self.assertFalse(ok)

    def test_fewer_pngs_than_planned_fails(self):
        ok, msg = shots.check(self._dir(2), "[dev-shots] step 1 failed\n" + DONE3)
        self.assertFalse(ok)
        self.assertIn("2/3", msg)
        self.assertIn("[1]", msg)

    def test_all_planned_pngs_passes(self):
        ok, msg = shots.check(self._dir(3), DONE3)
        self.assertTrue(ok, msg)
        self.assertIn("3/3", msg)

    def test_mojibake_dash_still_parses(self):
        # A non-UTF-8 console (1.8.9's Forge log) may mangle the em dash.
        ok, _ = shots.check(self._dir(3), DONE3.replace("—", "â€”"))
        self.assertTrue(ok)

    def test_world_set_needs_at_least_one_png(self):
        log = "[dev-shots] done — world set in /tmp/shots\n"
        self.assertFalse(shots.check(self._dir(0), log)[0])
        self.assertTrue(shots.check(self._dir(1), log)[0])

    def test_done_line_matches_every_harness(self):
        # Every module's harness must still print the line this gate parses.
        versions = os.path.join(ROOT, "src", "mods", "versions")
        import json
        live = [m["module"] for m in json.loads(_read(os.path.join(ROOT, "src", "mods", "live-modules.json")))["modules"]]
        for v in live:
            found = False
            for dirpath, _, files in os.walk(os.path.join(versions, v, "src")):
                for f in files:
                    if f in ("OriginDevShots.java", "OriginDevWorld.java"):
                        if "[dev-shots] done" in _read(os.path.join(dirpath, f)):
                            found = True
            self.assertTrue(found, f"{v}: no '[dev-shots] done' line in its harness")


class Smoke(unittest.TestCase):
    def test_ready_is_the_title_marker_only(self):
        self.assertEqual(smoke.classify("[Render thread/INFO]: [origin-smoke] title screen reached"), "ready")
        # GL up is NOT ready any more (a boot wedged on the loading screen passed).
        self.assertIsNone(smoke.classify("[Render thread/INFO]: Backend library: LWJGL version 3.3.3"))

    def test_mixin_failures_fail(self):
        self.assertEqual(smoke.classify(
            "Mixin apply for mod originclient failed originclient.client.mixins.json:TitleScreenMixin"), "fail")
        self.assertEqual(smoke.classify("Critical injection failure: @Inject annotation on foo"), "fail")

    def test_red_herrings_ignored(self):
        self.assertEqual(smoke.classify("twelvemonkeys ImageInputStreamSpi was not found in x"), "ignore")

    def test_network_signatures(self):
        self.assertTrue(smoke.NETWORK.search("Received status code 429 from server: Too Many Requests"))
        self.assertTrue(smoke.NETWORK.search("Execution failed for task ':downloadAssets'. > Read timed out"))
        # A wrong pin is not a network flake: never retried.
        self.assertIsNone(smoke.NETWORK.search("Could not resolve net.fabricmc:fabric-loader:9.9.9."))

    def test_every_live_module_prints_the_marker(self):
        import json
        live = [m["module"] for m in json.loads(_read(os.path.join(ROOT, "src", "mods", "live-modules.json")))["modules"]]
        for v in live:
            hits = []
            for dirpath, _, files in os.walk(os.path.join(ROOT, "src", "mods", "versions", v, "src")):
                if "OriginClientMod.java" in files:
                    text = _read(os.path.join(dirpath, "OriginClientMod.java"))
                    hits.append(bool(re.search(r'"\[origin-smoke\] title screen reached"', text)))
            self.assertIn(True, hits, f"{v}: OriginClientMod never prints the boot-smoke title marker")


if __name__ == "__main__":
    unittest.main(verbosity=1)
