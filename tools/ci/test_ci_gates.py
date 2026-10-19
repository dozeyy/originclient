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

    def test_loom_download_exception_is_network(self):
        # boot-smoke 39267525399 (1.18.2, 1.19.2, 1.19.3): prefetch called this "non-network".
        line = "> net.fabricmc.loom.util.download.DownloadException: Failed to download"
        self.assertTrue(smoke.NETWORK.search(line))

    def test_prefetch_reads_the_same_list(self):
        # gradle_prefetch.sh and smoke.py must share tools/ci/network_errors.txt, not copies.
        sh = _read(os.path.join(HERE, "gradle_prefetch.sh"))
        self.assertIn("network_errors.txt", sh)
        self.assertNotIn("Too Many Requests", sh)

    def test_retry_only_on_network_in_what_went_wrong(self):
        flake = ["> Task :downloadAssets FAILED\n", "FAILURE: Build failed with an exception.\n",
                 "* What went wrong:\n", "Execution failed for task ':downloadAssets'.\n",
                 "> net.fabricmc.loom.util.download.DownloadException: Failed to download\n",
                 "* Try:\n", "> Run with --stacktrace option to get the stack trace.\n"]
        self.assertTrue(smoke.retryable(flake))
        # A 429 retried and recovered earlier in the log, then javac fails: a compile error, not a flake.
        compile_fail = ["Could not GET 'https://x/y.pom'. Received status code 429 from server\n",
                        "/src/Foo.java:12: error: cannot find symbol\n",
                        "* What went wrong:\n", "Execution failed for task ':compileJava'.\n",
                        "> Compilation failed; see the compiler error output for details.\n", "* Try:\n"]
        self.assertFalse(smoke.retryable(compile_fail))
        # A network line outside the "What went wrong" block alone is not enough.
        noise = ["Read timed out (retrying)\n", "* What went wrong:\n",
                 "A problem occurred evaluating root project.\n", "* Try:\n"]
        self.assertFalse(smoke.retryable(noise))

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


class DevShotsFilm(unittest.TestCase):
    YML = os.path.join(ROOT, ".github", "workflows", "dev-shots.yml")

    def test_film_trigger_covers_lwjgl2(self):
        # 1.8.9 never prints "Backend library" -> no loading.mp4 (run 39267523866).
        yml = _read(self.YML)
        m = re.search(r'grep -qE "([^"]+)" run\.log', yml)
        self.assertIsNotNone(m, "film trigger grep not found")
        trig = re.compile(m.group(1))
        self.assertTrue(trig.search("[14:25:26] [main/INFO] (Minecraft) LWJGL Version: 2.9.4"))
        self.assertTrue(trig.search("[Render thread/INFO]: Backend library: LWJGL version 3.3.3+5"))

    def test_film_splash_theme_matches_launcher(self):
        # The filmed 1.8.9 splash must be the one players get.
        cs = _read(os.path.join(ROOT, "src", "OriginLauncher.App", "Core", "Loaders", "ForgeSplashTheme.cs"))
        block = cs[cs.index("string Content ="):]
        launcher = re.findall(r'"(\w+=[^"\\]+)\\n"', block[:block.index(";")])
        self.assertGreaterEqual(len(launcher), 5)
        m = re.search(r"printf '([^']*)' > \"\$rundir/config/splash\.properties\"", _read(self.YML))
        self.assertIsNotNone(m, "dev-shots no longer seeds splash.properties")
        self.assertEqual(m.group(1).split("\\n")[:-1], launcher)


class DevShotsWorkflow(unittest.TestCase):
    YML = DevShotsFilm.YML

    def _plan(self, modules, msg="", event="push"):
        """Runs the plan job's module picker exactly as CI does; returns (exit code, stdout, stderr)."""
        import subprocess, sys
        yml = _read(self.YML)
        m = re.search(r"python3 - <<'EOF' >> \"\$GITHUB_OUTPUT\"\n(.*?)\n\s*EOF\n", yml, re.S)
        self.assertIsNotNone(m, "plan's module picker not found")
        lines = m.group(1).splitlines()
        indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
        script = "\n".join(l[indent:] for l in lines)
        env = dict(os.environ, INPUT=modules, MSG=msg, EVENT=event)
        p = subprocess.run([sys.executable, "-c", script], cwd=ROOT, env=env, capture_output=True, text=True)
        return p.returncode, p.stdout, p.stderr

    def test_unknown_module_fails_instead_of_shooting_another(self):
        # [shots:1.21.l1] used to shoot 1.21.1 and go green.
        code, out, err = self._plan("1.21.l1")
        self.assertNotEqual(code, 0, out)
        self.assertIn("::error::", err)
        self.assertNotIn("modules=", out)
        code, _, _ = self._plan("1.21.1,1.21.l1")
        self.assertNotEqual(code, 0)

    def test_prose_example_in_body_does_not_fail(self):
        # Run 39289921229: a body documenting "([shots:1.21.l1])" failed the plan.
        msg = "ci: tidy\n\nThe gate rejects typos (like [shots:1.21.l1]) now.\nSee [shots:bogus] too.\n"
        code, out, err = self._plan("", msg)
        self.assertEqual(code, 0, err)
        self.assertIn("modules=[]", out)
        self.assertNotIn('"module"', out)

    def test_midline_mention_plans_nothing_on_push(self):
        # The if: guard passes on any '[shots' substring; with no end-of-line tag the
        # plan must be empty (shots/publish skip), never the 1.21.1 fallback.
        code, out, err = self._plan("", "ci: x\n\nwe discuss the [shots] tag mid-line here\n")
        self.assertEqual(code, 0, err)
        self.assertEqual(out.strip(), "modules=[]")
        self.assertIn("no end-of-line shots tag; nothing to shoot", err)
        # A bare end-of-line tag still means 1.21.1; hand-started runs keep the default.
        _, out, _ = self._plan("", "ci: y [shots]")
        self.assertIn('"module": "1.21.1"', out)
        _, out, _ = self._plan("", "", event="workflow_dispatch")
        self.assertIn('"module": "1.21.1"', out)

    def test_shots_job_skips_on_empty_plan(self):
        yml = _read(self.YML)
        self.assertRegex(yml, r"shots:\r?\n    needs: plan\r?\n(?:    #[^\n]*\n)*    if: needs\.plan\.outputs\.modules != '\[\]'")

    def test_typo_in_real_tag_still_fails(self):
        code, out, err = self._plan("", "1.21.1: fix thing [shots:1.21.l1]\n\nbody")
        self.assertNotEqual(code, 0, out)
        self.assertIn("::error::", err)
        # A mid-line tag WITH a list, then a bare end-of-line tag: only the
        # end-of-line rule gives 1.21.1 (an anywhere-rule would pick 1.8.9).
        msg = "ci: x\n\nprose [shots:1.8.9] here\n\nreal tag below [shots]\n"
        code, out, err = self._plan("", msg)
        self.assertEqual(code, 0, err)
        self.assertIn('"module": "1.21.1"', out)
        self.assertNotIn('"module": "1.8.9"', out)

    def test_known_modules_are_planned(self):
        code, out, err = self._plan("1.21.1, 1.8.9")
        self.assertEqual(code, 0, err)
        self.assertIn('"module": "1.21.1"', out)
        self.assertIn('"module": "1.8.9"', out)

    def test_only_publish_can_write(self):
        # The shots job runs the repo's Gradle build; it must hold a read-only token.
        yml = _read(self.YML)
        top = re.search(r"^permissions:\n((?:  .*\n)+)", yml, re.M)
        self.assertIsNotNone(top, "no workflow-level permissions block")
        self.assertNotIn("write", top.group(1))
        jobs = re.split(r"^  (?=[\w-]+:\n)", yml.split("\njobs:\n", 1)[1], flags=re.M)
        writers = [j.split(":", 1)[0] for j in jobs if re.search(r"^\s+contents:\s*write", j, re.M)]
        self.assertEqual(writers, ["publish"])

    def test_every_publish_attempt_starts_from_the_fresh_tip(self):
        # Run 39273133310 lost the push race 5 times: it cloned once and retried the stale commit.
        yml = _read(self.YML)
        loop = re.search(r"for attempt in [\d ]+; do\n(.*?)could not publish", yml, re.S)
        self.assertIsNotNone(loop, "publish retry loop not found")
        body = loop.group(1)
        self.assertIn("git clone", body)
        self.assertLess(body.index("git clone"), body.index("git push"))


class CheckShotsNames(unittest.TestCase):
    def test_captured_shot_missing_fails_even_when_the_count_matches(self):
        d = tempfile.mkdtemp()
        for n in ("title_1920x1080_s0.png", "stray.png"):
            open(os.path.join(d, n), "wb").close()
        log = ("[dev-shots] captured title_1920x1080_s0.png\n[dev-shots] captured mods_1920x1080_s0.png\n"
               "[dev-shots] done — 2 shots in /tmp/shots\n")
        ok, msg = shots.check(d, log)
        self.assertFalse(ok)
        self.assertIn("mods_1920x1080_s0.png", msg)


class LiveModules(unittest.TestCase):
    def test_dropping_a_live_module_fails_consistency(self):
        # Removing a module from live-modules.json while the launcher still ships it must go red.
        import json, shutil
        cc = _load("check_consistency", os.path.join(ROOT, "tools", "release", "check_consistency.py"))
        self.assertEqual(cc.check_versions(), [])
        data = json.loads(_read(os.path.join(ROOT, "src", "mods", "live-modules.json")))
        victim = data["modules"][-1]["module"]
        data["modules"] = data["modules"][:-1]
        tmp = tempfile.mkdtemp()
        try:
            os.makedirs(os.path.join(tmp, "src", "mods"))
            with open(os.path.join(tmp, "src", "mods", "live-modules.json"), "w", encoding="utf-8") as f:
                json.dump(data, f)
            from pathlib import Path
            cc.REPO = Path(tmp)
            problems = cc.check_versions()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        self.assertTrue(any(victim in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main(verbosity=1)
