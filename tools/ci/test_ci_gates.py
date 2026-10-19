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


perf_libs = _load("perf_libs", os.path.join(HERE, "perf_libs.py"))
perf_log = _load("perf_log", os.path.join(HERE, "perf_log.py"))

MODLIST = ("[main/INFO] (FabricLoader) Loading 9 mods:\n\t- fabricloader 0.15.11\n\t- sodium 0.4.10+build.27\n"
           "\t   \\-- nested 1.0\n\t- iris 1.6.4\n")
MIXING = ("[main/INFO] (mixin) Mixing IrisWatermarkMixin from originclient.client.mixins.json into net.coderbot.iris.gui.screen.ShaderPackScreen\n"
          "[main/INFO] (mixin) Mixing TitleScreenMixin from originclient.client.mixins.json into net.minecraft.client.gui.screens.TitleScreen\n")


class PerfLibs(unittest.TestCase):
    GRADLE = ('\t// modRuntimeOnly files("libs/sodium-commented-out.jar")\n'
              '\tif (file("libs/sodium-fabric-mc1.20-0.4.10+build.27.jar").exists()) {\n'
              '\t\tmodRuntimeOnly files("libs/sodium-fabric-mc1.20-0.4.10+build.27.jar")\n'
              '\t\tmodRuntimeOnly files("libs/iris-mc1.20-1.6.4.jar")\n\t}\n')

    def test_pins_come_from_build_gradle_not_comments(self):
        self.assertEqual(perf_libs.pins(self.GRADLE),
                         ["sodium-fabric-mc1.20-0.4.10+build.27.jar", "iris-mc1.20-1.6.4.jar"])

    def test_every_pre_121_module_has_pins_and_1_8_9_none(self):
        for mod in "1.16.5 1.17.1 1.18.2 1.19.2 1.19.3 1.19.4 1.20 1.20.2".split():
            pins = perf_libs.pins(_read(os.path.join(ROOT, "src", "mods", "versions", mod, "build.gradle")))
            self.assertEqual(sorted(perf_libs.slug_of(p) for p in pins), ["iris", "sodium"], mod)
        self.assertEqual(perf_libs.pins(_read(os.path.join(ROOT, "src", "mods", "versions", "1.8.9", "build.gradle"))), [])

    CATALOG_TEXT = ('        ["1.21"] = new("1.21", PerfStackTier.Full, new PerfMod("a", "https://cdn.modrinth.com/x", "sodium-fabric-0.5.11+mc1.21.jar"), '
                    'new PerfMod("b", "u", "indium-1.jar"), null, null, null, new PerfMod("c", "u", "iris-1.7.3+mc1.21.jar"));' + chr(10) +
                    '        ["1.20.6"] = new("1.20.6", PerfStackTier.Partial, null, null, null);' + chr(10))

    def test_catalog_is_the_source_of_truth_for_pins(self):
        cf = perf_libs.catalog_files(self.CATALOG_TEXT, "1.21")
        self.assertEqual(cf, ("sodium-fabric-0.5.11+mc1.21.jar", "iris-1.7.3+mc1.21.jar"))   # Indium is not picked up
        self.assertEqual(perf_libs.expected_pins(self.CATALOG_TEXT, "1.21", ["iris-1.7.3+mc1.21.jar", cf[0]]), list(cf))
        # The 1.21 bug: build.gradle pinned an Iris that needs Sodium 0.6.x next to Sodium 0.5.11.
        with self.assertRaises(perf_libs.PerfLibsError):
            perf_libs.expected_pins(self.CATALOG_TEXT, "1.21", [cf[0], "iris-fabric-1.8.8+mc1.21.1.jar"])
        with self.assertRaises(perf_libs.PerfLibsError):    # entry without Sodium + Iris (Partial tier)
            perf_libs.expected_pins(self.CATALOG_TEXT, "1.20.6", ["sodium-x.jar", "iris-x.jar"])
        with self.assertRaises(perf_libs.PerfLibsError):    # no entry at all
            perf_libs.expected_pins(self.CATALOG_TEXT, "9.9", ["sodium-x.jar", "iris-x.jar"])

    def test_every_live_module_pin_matches_what_ships(self):
        # Make CI test what players get: each live module with libs/ pins names exactly the catalog's jars.
        import json
        catalog = _read(perf_libs.CATALOG)
        live = [m["module"] for m in json.loads(_read(os.path.join(ROOT, "src", "mods", "live-modules.json")))["modules"]]
        checked = 0
        for mod in live:
            pins = perf_libs.pins(_read(os.path.join(ROOT, "src", "mods", "versions", mod, "build.gradle")))
            if not pins:
                continue
            perf_libs.expected_pins(catalog, mod, pins)   # raises with the diff on any mismatch
            checked += 1
        self.assertGreaterEqual(checked, 15)

    def test_hash_mismatch_fails(self):
        import hashlib
        data = b"jar bytes"
        perf_libs.verify(data, hashlib.sha512(data).hexdigest())
        with self.assertRaises(perf_libs.PerfLibsError):
            perf_libs.verify(b"tampered", hashlib.sha512(data).hexdigest())

    def test_fetch_one_rejects_a_bad_download_and_skips_an_unknown_pin(self):
        import hashlib
        fn = "iris-mc1.20-1.6.4.jar"
        listing = perf_libs.API + "/project/iris/version?loaders=" + perf_libs.urllib.parse.quote('["fabric"]')
        api = {
            listing: [{"id": "V1", "files": [{"filename": fn}]}],
            perf_libs.API + "/version/V1": {"files": [{"filename": fn, "url": "https://cdn.modrinth.com/data/x/f.jar",
                                                       "hashes": {"sha512": hashlib.sha512(b"good").hexdigest()}}]},
        }
        out = tempfile.mkdtemp()
        with self.assertRaises(perf_libs.PerfLibsError):
            perf_libs.fetch_one(fn, out, api.__getitem__, lambda u: b"evil")
        self.assertFalse(os.path.exists(os.path.join(out, fn)))
        self.assertTrue(perf_libs.fetch_one(fn, out, api.__getitem__, lambda u: b"good").startswith("ok"))
        self.assertTrue(os.path.exists(os.path.join(out, fn)))
        # Not on Modrinth under that name -> skipped, never guessed.
        api[listing] = []
        self.assertTrue(perf_libs.fetch_one(fn, out, api.__getitem__, lambda u: b"good").startswith("skipped"))

    def test_only_the_modrinth_cdn_is_a_download_host(self):
        import hashlib
        fn = "iris-mc1.20-1.6.4.jar"
        listing = perf_libs.API + "/project/iris/version?loaders=" + perf_libs.urllib.parse.quote('["fabric"]')
        for url in ("http://cdn.modrinth.com/f.jar", "https://evil.example/f.jar", "https://cdn.modrinth.com.evil.example/f.jar"):
            api = {listing: [{"id": "V1", "files": [{"filename": fn}]}],
                   perf_libs.API + "/version/V1": {"files": [{"filename": fn, "url": url,
                                                              "hashes": {"sha512": hashlib.sha512(b"good").hexdigest()}}]}}
            out = tempfile.mkdtemp()
            with self.assertRaises(perf_libs.PerfLibsError, msg=url):
                perf_libs.fetch_one(fn, out, api.__getitem__, lambda u: b"good")
            self.assertFalse(os.path.exists(os.path.join(out, fn)))

    def test_pin_names_cannot_carry_a_path(self):
        self.assertEqual(perf_libs.pins('modRuntimeOnly files("libs/sub/iris.jar")\n'), [])
        self.assertEqual(perf_libs.pins('modRuntimeOnly files("libs/a\\\\b.jar")\n'), [])
        with self.assertRaises(perf_libs.PerfLibsError):
            perf_libs.pins('modRuntimeOnly files("libs/iris..jar")\n')
        with self.assertRaises(perf_libs.PerfLibsError):
            perf_libs.fetch_one("../iris.jar", tempfile.mkdtemp(), lambda u: [], lambda u: b"")


class PerfLogGate(unittest.TestCase):
    def test_mixin_failures_fail_the_job(self):
        for bad in ("Mixin apply for mod originclient failed originclient.client.mixins.json:XMixin",
                    "org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: x",
                    "Critical injection failure: @Inject annotation on foo"):
            ok, _ = perf_log.gate(MODLIST + bad + "\n", "m")
            self.assertFalse(ok, bad)

    def test_loaded_gate_needs_both_mods(self):
        ok, _ = perf_log.gate("Loading 2 mods:\n\t- fabricloader 0.15\n", "m", require_loaded=True)
        self.assertFalse(ok)
        ok, _ = perf_log.gate("Loading 3 mods:\n\t- sodium 0.4.10\n", "m", require_loaded=True)
        self.assertFalse(ok)
        ok, lines = perf_log.gate(MODLIST + MIXING, "m", require_loaded=True)
        self.assertTrue(ok)
        self.assertIn("sodium=0.4.10+build.27", "\n".join(lines))

    def test_counts_only_origin_mixins_on_sodium_iris(self):
        self.assertEqual(perf_log.tally(MIXING), ["IrisWatermarkMixin -> ShaderPackScreen"])
        ok, lines = perf_log.gate(MODLIST + MIXING, "m")
        self.assertIn("mixins applied: 1", "\n".join(lines))

    def test_declared_mixins_are_listed_against_applied(self):
        names = perf_log.declared(os.path.join(ROOT, "src", "mods", "versions", "1.20"))
        self.assertIn("IrisWatermarkMixin", names)
        self.assertIn("IrisShadowDirectivesMixin", names)
        ok, lines = perf_log.gate(MODLIST + MIXING, "m", declared_names=names)
        self.assertIn("NOT applied", "\n".join(lines))
        self.assertIn("IrisShadowDirectivesMixin", "\n".join(lines))

    def test_zero_applied_with_mods_loaded_warns_but_passes(self):
        ok, lines = perf_log.gate(MODLIST, "m", require_loaded=True)
        self.assertTrue(ok)
        self.assertIn("::warning", "\n".join(lines))

    def test_missing_or_empty_log_fails_the_gate(self):
        import sys
        d = tempfile.mkdtemp()
        empty = os.path.join(d, "empty.log")
        open(empty, "w").close()
        blank = os.path.join(d, "blank.log")
        with open(blank, "w") as fh:
            fh.write("\n  \n")
        good = os.path.join(d, "good.log")
        with open(good, "w") as fh:
            fh.write(MODLIST + MIXING)
        old = sys.argv
        try:
            for path, want in ((os.path.join(d, "missing.log"), 1), (empty, 1), (blank, 1), (good, 0)):
                sys.argv = ["perf_log.py", path, "--require-loaded"]
                self.assertEqual(perf_log.main(), want, path)
        finally:
            sys.argv = old

    def test_smoke_and_workflows_wire_the_gate(self):
        self.assertEqual(smoke.classify("InvalidInjectionException: @Inject target"), "fail")
        ds = _read(os.path.join(ROOT, ".github", "workflows", "dev-shots.yml"))
        bs = _read(os.path.join(ROOT, ".github", "workflows", "boot-smoke.yml"))
        for wf in (ds, bs):
            self.assertIn("tools/ci/perf_libs.py", wf)
        self.assertIn("tools/ci/perf_log.py", ds)
        self.assertIn("--perf", bs)
        # the fetch runs before Gradle's prefetch (Loom checksums libs/ at configuration time)
        self.assertLess(ds.index("python3 tools/ci/perf_libs.py"), ds.index("bash tools/ci/gradle_prefetch.sh"))
        self.assertLess(bs.index("python3 tools/ci/perf_libs.py"), bs.index("bash tools/ci/gradle_prefetch.sh"))

    def test_perf_set_is_in_every_harness_with_sodium_pins(self):
        import glob
        for mod in "1.16.5 1.17.1 1.18.2 1.19.2 1.19.3 1.19.4 1.20 1.20.2 1.21.1".split():
            pat = os.path.join(ROOT, "src", "mods", "versions", mod, "src", "*", "java", "com", "origin", "client", "client", "OriginDevShots.java")
            t = _read(glob.glob(pat)[0])
            self.assertIn('"perf".equals(System.getenv("ORIGIN_SHOTS_SET"))', t, mod)
            self.assertIn("PERF_SODIUM", t, mod)
            # the design set must not pick the perf kinds up (they throw without the jars)
            self.assertIn('!k.name().startsWith("PERF_")', t, mod)


if __name__ == "__main__":
    unittest.main(verbosity=1)
