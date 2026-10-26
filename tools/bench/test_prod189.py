#!/usr/bin/env python3
"""Tests for tools/bench/prod189.py: everything that does not need the network or a game.

Run: python -m pytest tools/bench -q      (CI)
     python -m unittest discover tools/bench   (no pytest needed)
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
import shutil
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))


def _load():
    spec = importlib.util.spec_from_file_location("bench_prod189", os.path.join(HERE, "prod189.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


P = _load()

REAL_MANIFEST = ("Manifest-Version: 1.0\r\nTweakClass: org.spongepowered.asm.launch.MixinTweaker\r\n"
                 "TweakOrder: 0\r\nMixinConfigs: originclient.core.mixins.json,originclient.menus.mixins.js\r\n"
                 " on,originclient.hud.mixins.json\r\nForceLoadAsMod: true\r\n"
                 "FMLCorePluginContainsFMLMod: true\r\n\r\n")


def make_jar(path, manifest=REAL_MANIFEST, extra=None):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", manifest)
        z.writestr("mcmod.info", '[{"modid":"originclient"}]')
        z.writestr("com/origin/client/OriginClient.class", b"\xca\xfe\xba\xbe")
        z.writestr("META-INF/ORIGIN.SF", "signature file that must not survive")
        for n, d in (extra or {}).items():
            z.writestr(n, d)


class Tmp(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="prod189-test-")
        self.addCleanup(shutil.rmtree, self.dir, True)

    def p(self, *parts):
        return os.path.join(self.dir, *parts)


class Naming(unittest.TestCase):
    def test_maven_path(self):
        self.assertEqual(P.maven_path("org.ow2.asm:asm-all:5.0.3"), "org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar")
        self.assertEqual(P.maven_path("org.lwjgl.lwjgl:lwjgl-platform:2.9.4", "natives-linux"),
                         "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-linux.jar")
        self.assertEqual(P.maven_path("a.b:c:1:x"), "a/b/c/1/c-1-x.jar")

    def test_bad_coordinate_is_an_error(self):
        with self.assertRaises(P.ProvisionError):
            P.maven_path("nonsense")

    def test_lib_key_ignores_version(self):
        self.assertEqual(P.lib_key("org.ow2.asm:asm-all:5.0.3"), P.lib_key("org.ow2.asm:asm-all:9.0"))
        self.assertNotEqual(P.lib_key("a:b:1"), P.lib_key("a:b:1", "natives-linux"))

    def test_os_name(self):
        self.assertEqual(P.os_name("win32"), "windows")
        self.assertEqual(P.os_name("linux"), "linux")
        self.assertEqual(P.os_name("darwin"), "osx")


class Rules(unittest.TestCase):
    def test_no_rules_allows(self):
        self.assertTrue(P.rules_allow(None, "linux"))
        self.assertTrue(P.rules_allow([], "windows"))

    def test_osx_only_library_is_skipped_elsewhere(self):
        rules = [{"action": "allow"}, {"action": "disallow", "os": {"name": "osx"}}]
        self.assertTrue(P.rules_allow(rules, "linux"))
        self.assertFalse(P.rules_allow(rules, "osx"))

    def test_allow_only_for_one_os(self):
        rules = [{"action": "allow", "os": {"name": "windows"}}]
        self.assertTrue(P.rules_allow(rules, "windows"))
        self.assertFalse(P.rules_allow(rules, "linux"))


class Merge(unittest.TestCase):
    def test_child_wins_and_order_is_child_first(self):
        child = [{"name": "net.minecraft:launchwrapper:1.12"}, {"name": "org.ow2.asm:asm-all:5.0.3"}]
        parent = [{"name": "org.ow2.asm:asm-all:4.1"}, {"name": "com.google.guava:guava:17.0"}]
        out = [l["name"] for l in P.merge_libraries(child, parent)]
        self.assertEqual(out, ["net.minecraft:launchwrapper:1.12", "org.ow2.asm:asm-all:5.0.3",
                               "com.google.guava:guava:17.0"])


class Args(unittest.TestCase):
    def test_fill(self):
        self.assertEqual(P.fill_args("--a ${x} --flag --b ${y}", {"x": 1, "y": "two"}),
                         ["--a", "1", "--flag", "--b", "two"])

    def test_missing_value_is_an_error_not_an_empty_string(self):
        with self.assertRaises(P.ProvisionError):
            P.fill_args("--a ${nope}", {})

    def test_real_forge_json_args_are_all_answerable(self):
        forge = P.load_forge_json()
        prov = {"assets": "A", "assetIndex": "1.8", "natives": "N", "classpath": ["x.jar"],
                "mainClass": forge["mainClass"], "argsTemplate": forge["minecraftArguments"]}
        cmd = P.game_command("java", prov, "G", "-Xmx1G -XX:+UseG1GC", 1280, 720)
        self.assertIn("net.minecraftforge.fml.common.launcher.FMLTweaker", cmd)
        self.assertEqual(cmd[cmd.index("--gameDir") + 1], "G")
        self.assertEqual(cmd[cmd.index("--width") + 1], "1280")
        self.assertIn("-Xmx1G", cmd)
        self.assertEqual(forge["mainClass"], "net.minecraft.launchwrapper.Launch")
        self.assertEqual(cmd[cmd.index("-cp") + 2], "net.minecraft.launchwrapper.Launch")


class ProbeJar(Tmp):
    def test_tweaker_entries_are_gone_and_everything_else_stays(self):
        src, dst = self.p("o.jar"), self.p("probe.jar")
        make_jar(src)
        P.probe_jar(src, dst)
        with zipfile.ZipFile(dst) as z:
            mf = z.read("META-INF/MANIFEST.MF").decode()
            names = z.namelist()
            self.assertEqual(z.read("com/origin/client/OriginClient.class"), b"\xca\xfe\xba\xbe")
        for key in ("TweakClass", "TweakOrder", "MixinConfigs", "ForceLoadAsMod", "FMLCorePluginContainsFMLMod"):
            self.assertNotIn(key, mf)
        self.assertNotIn("MixinTweaker", mf)
        self.assertNotIn("originclient.hud.mixins.json", mf)    # the folded continuation line went with its key
        self.assertTrue(mf.startswith("Manifest-Version: 1.0"))
        self.assertIn("mcmod.info", names)
        self.assertNotIn("META-INF/ORIGIN.SF", names)

    def test_original_jar_is_untouched(self):
        src = self.p("o.jar")
        make_jar(src)
        before = P.sha256_file(src)
        P.probe_jar(src, self.p("probe.jar"))
        self.assertEqual(P.sha256_file(src), before)

    def test_jar_without_manifest_is_an_error(self):
        src = self.p("o.jar")
        with zipfile.ZipFile(src, "w") as z:
            z.writestr("x.class", b"")
        with self.assertRaises(P.ProvisionError):
            P.probe_jar(src, self.p("p.jar"))

    def test_find_origin_jar(self):
        os.makedirs(self.p("libs"))
        for n in ("originclient-1.8.9-0.4.2.jar", "originclient-1.8.9-0.4.2-sources.jar", "readme.txt"):
            open(self.p("libs", n), "w").close()
        self.assertTrue(P.find_origin_jar(self.p("libs")).endswith("originclient-1.8.9-0.4.2.jar"))
        open(self.p("libs", "originclient-1.8.9-0.5.0.jar"), "w").close()
        with self.assertRaises(P.ProvisionError):
            P.find_origin_jar(self.p("libs"))


class Fetch(Tmp):
    def test_sha1_gate_falls_through_to_the_next_source(self):
        good = b"the real optifine"
        sha = hashlib.sha1(good).hexdigest()
        served = {"https://a/x": b"tampered", "https://b/x": good}

        def get(url, **kw):
            if url not in served:
                raise P.ProvisionError("404 " + url)
            return served[url]
        dest = self.p("out", "x.jar")
        used = P.fetch_verified(["https://nowhere/x", "https://a/x", "https://b/x"], dest, sha, get)
        self.assertEqual(used, "https://b/x")
        with open(dest, "rb") as f:
            self.assertEqual(f.read(), good)

    def test_no_source_matches_is_an_error_and_writes_nothing(self):
        def get(url, **kw):
            return b"wrong"
        dest = self.p("x.jar")
        with self.assertRaises(P.ProvisionError) as cm:
            P.fetch_verified(["https://a/x"], dest, "0" * 40, get)
        self.assertIn("SHA-1 mismatch", str(cm.exception))
        self.assertFalse(os.path.exists(dest))

    def test_ensure_file_skips_the_network_when_the_hash_matches(self):
        dest = self.p("x.jar")
        with open(dest, "wb") as f:
            f.write(b"cached")

        def get(url, **kw):
            raise AssertionError("must not download")
        P.ensure_file(dest, ["https://a/x"], hashlib.sha1(b"cached").hexdigest(), get)

    def test_optifine_pin_matches_the_launcher(self):
        """The SHA-1 here must be the one OptiFineInstaller.cs pins; a drifted pin would test a different jar."""
        cs = os.path.join(HERE, "..", "..", "src", "OriginLauncher.App", "Core", "Loaders", "OptiFineInstaller.cs")
        with open(cs, encoding="utf-8") as f:
            text = f.read()
        self.assertIn(P.OPTIFINE_SHA1, text)
        self.assertIn(P.OPTIFINE_FILE, text)

    def test_stack_pins_match_the_launcher(self):
        cs = os.path.join(HERE, "..", "..", "src", "OriginLauncher.App", "Core", "Loaders", "LegacyStackInstaller.cs")
        with open(cs, encoding="utf-8") as f:
            text = f.read()
        for name, url in P.STACK_MODS:
            self.assertIn(url, text)

    def test_every_downloaded_jar_has_a_sha256_pin(self):
        self.assertEqual(sorted(P.STACK_SHA256), sorted(n for n, _ in P.STACK_MODS))
        for h in list(P.STACK_SHA256.values()) + [P.FORGE_UNIVERSAL_SHA256]:
            self.assertRegex(h, r"^[0-9a-f]{64}$")

    def test_a_stack_mod_with_the_wrong_sha256_is_refused_and_a_cached_one_is_replaced(self):
        good = b"the real jar"
        sha = hashlib.sha256(good).hexdigest()
        dest = self.p("mods", "TexFix.jar")
        os.makedirs(os.path.dirname(dest))
        with self.assertRaises(P.ProvisionError) as c:
            P.ensure_file(dest, ["https://cdn/x"], get=lambda url, **kw: b"swapped", sha256=sha)
        self.assertIn("SHA-256 mismatch", str(c.exception))
        self.assertFalse(os.path.exists(dest))
        with open(dest, "wb") as f:
            f.write(b"tampered earlier download")
        P.ensure_file(dest, ["https://cdn/x"], get=lambda url, **kw: good, sha256=sha)
        with open(dest, "rb") as f:
            self.assertEqual(f.read(), good)

    def test_temurin_pin_matches_the_launcher(self):
        cs = os.path.join(HERE, "..", "..", "src", "OriginLauncher.App", "Core", "Runtime", "TemurinJava8.cs")
        with open(cs, encoding="utf-8") as f:
            text = f.read()
        self.assertIn(P.TEMURIN8_WIN["sha256"], text)
        self.assertIn(P.TEMURIN8_WIN["url"], text)

    def test_optifine_official_link_comes_first_then_mirrors(self):
        page = b"<a href='downloadx?f=OptiFine_1.8.9_HD_U_M5.jar&x=0a1b2c3d'>Download</a>"
        urls = P.optifine_urls(lambda u, **kw: page)
        self.assertEqual(urls[0], "https://optifine.net/downloadx?f=OptiFine_1.8.9_HD_U_M5.jar&x=0a1b2c3d")
        self.assertEqual(urls[1:], P.OPTIFINE_MIRRORS)

    def test_optifine_page_down_leaves_the_mirrors(self):
        def get(u, **kw):
            raise P.ProvisionError("503")
        self.assertEqual(P.optifine_urls(get), P.OPTIFINE_MIRRORS)


class LibraryJobs(unittest.TestCase):
    def test_forge_universal_and_plain_libs(self):
        libs = [
            {"name": "net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9", "url": "https://maven.minecraftforge.net/"},
            {"name": "lzma:lzma:0.0.1"},
            {"name": "com.mojang:authlib:1.5.21",
             "downloads": {"artifact": {"path": "com/mojang/authlib/1.5.21/a.jar", "url": "https://l/a.jar", "sha1": "ab"}}},
            {"name": "ca.weblite:java-objc-bridge:1.0.0", "rules": [{"action": "allow", "os": {"name": "osx"}}],
             "downloads": {"artifact": {"path": "p", "url": "u", "sha1": "s"}}},
            {"name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.4",
             "natives": {"linux": "natives-linux", "windows": "natives-windows"},
             "extract": {"exclude": ["META-INF/"]},
             "downloads": {"classifiers": {"natives-linux": {"path": "n/linux.jar", "url": "https://l/n.jar", "sha1": "cd"}}}},
        ]
        jobs, natives = P.library_jobs(libs, "linux")
        rels = [j[0] for j in jobs]
        self.assertEqual(rels[0], "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9.jar")
        self.assertEqual(jobs[0][1], [P.FORGE_UNIVERSAL_URL])
        self.assertIn("lzma/lzma/0.0.1/lzma-0.0.1.jar", rels)
        self.assertIn("com/mojang/authlib/1.5.21/a.jar", rels)
        self.assertNotIn("p", rels)                         # the osx-only library
        self.assertEqual(len(natives), 1)
        self.assertEqual(natives[0][0], "n/linux.jar")
        self.assertEqual(natives[0][3], ["META-INF/"])


FORGE_FAKE = hashlib.sha256(b"forge-universal").hexdigest()   # the fake internet's Forge bytes


class FakeNet(Tmp):
    """A whole provision against an in-memory 'internet': proves the wiring, the cache and the asset gate."""

    def build_world(self):
        client = b"client-jar-bytes"
        native_zip = io.BytesIO()
        with zipfile.ZipFile(native_zip, "w") as z:
            z.writestr("liblwjgl64.so", b"so")
            z.writestr("META-INF/junk", b"x")
        nat = native_zip.getvalue()
        asset = b"sound"
        ah = hashlib.sha1(asset).hexdigest()
        index = json.dumps({"objects": {"a.ogg": {"hash": ah, "size": len(asset)}}}).encode()
        vanilla = {
            "id": "1.8.9", "assetIndex": {"id": "1.8", "url": "https://idx/1.8.json", "sha1": hashlib.sha1(index).hexdigest()},
            "downloads": {"client": {"url": "https://c/client.jar", "sha1": hashlib.sha1(client).hexdigest()}},
            "libraries": [
                {"name": "com.mojang:authlib:1.5.21", "downloads": {"artifact": {
                    "path": "com/mojang/authlib/1.5.21/authlib-1.5.21.jar", "url": "https://l/authlib.jar",
                    "sha1": hashlib.sha1(b"authlib").hexdigest()}}},
                {"name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.4", "natives": {"linux": "natives-linux"},
                 "extract": {"exclude": ["META-INF/"]}, "downloads": {"classifiers": {"natives-linux": {
                     "path": "org/lwjgl/n.jar", "url": "https://l/n.jar", "sha1": hashlib.sha1(nat).hexdigest()}}}},
            ]}
        vj = json.dumps(vanilla).encode()
        manifest = json.dumps({"versions": [{"id": "1.8.9", "url": "https://m/1.8.9.json",
                                             "sha1": hashlib.sha1(vj).hexdigest()}]}).encode()
        forge = P.load_forge_json()
        served = {P.MANIFEST_URL: manifest, "https://m/1.8.9.json": vj, "https://idx/1.8.json": index,
                  "https://c/client.jar": client, "https://l/authlib.jar": b"authlib", "https://l/n.jar": nat,
                  P.ASSET_URL + ah[:2] + "/" + ah: asset, P.FORGE_UNIVERSAL_URL: b"forge-universal"}
        self.requests = []

        def get(url, **kw):
            self.requests.append(url)
            if url in served:
                return served[url]
            if url.endswith(".jar"):                   # every other Forge library
                return b"lib:" + url.encode()
            raise P.ProvisionError("404 " + url)
        return get, forge

    def test_provision_then_second_call_downloads_nothing(self):
        get, forge = self.build_world()
        cache = self.p("cache")
        prov = P.provision_cache(cache, "linux", get, FORGE_FAKE)
        self.assertTrue(prov["classpath"][-1].endswith("1.8.9.jar"))
        self.assertTrue(any(c.endswith("forge-1.8.9-11.15.1.2318-1.8.9.jar") for c in prov["classpath"]))
        self.assertTrue(os.path.isfile(os.path.join(prov["natives"], "liblwjgl64.so")))
        self.assertFalse(os.path.exists(os.path.join(prov["natives"], "META-INF", "junk")))
        self.assertEqual(prov["mainClass"], "net.minecraft.launchwrapper.Launch")
        self.assertEqual(prov["assetIndex"], "1.8")
        self.assertGreater(len(self.requests), 5)

        def no_net(url, **kw):
            raise AssertionError("second provision must not touch the network: " + url)
        prov2 = P.provision_cache(cache, "linux", no_net, FORGE_FAKE)
        self.assertEqual(prov2["classpath"], prov["classpath"])

    def test_a_corrupt_asset_stops_the_provision(self):
        get, _ = self.build_world()

        def bad(url, **kw):
            if url.startswith(P.ASSET_URL):
                return b"corrupt"
            return get(url, **kw)
        with self.assertRaises(P.ProvisionError):
            P.provision_cache(self.p("cache"), "linux", bad, FORGE_FAKE)

    def test_a_tampered_forge_universal_jar_is_refused(self):
        get, _ = self.build_world()

        def evil(url, **kw):
            return b"not-forge" if url == P.FORGE_UNIVERSAL_URL else get(url, **kw)
        with self.assertRaises(P.ProvisionError) as c:
            P.provision_cache(self.p("cache"), "linux", evil, FORGE_FAKE)
        self.assertIn("SHA-256 mismatch", str(c.exception))
        # and the real pin is what runs by default: the fake bytes do not match it
        with self.assertRaises(P.ProvisionError):
            P.provision_cache(self.p("cache2"), "linux", get)


class GameDir(Tmp):
    def setup_jars(self):
        origin, opti, tex = self.p("o.jar"), self.p("optifine.src"), self.p("TexFix.jar")
        make_jar(origin)
        for f, d in ((opti, b"opti"), (tex, b"tex")):
            with open(f, "wb") as fh:
                fh.write(d)
        return origin, opti, tex

    def test_origin_default_ships_the_jar_as_built(self):
        origin, opti, tex = self.setup_jars()
        rec = P.prepare_game_dir(self.p("g1"), "origin-default", origin, opti, [tex])
        files = sorted(os.listdir(self.p("g1", "mods")))
        self.assertEqual(files, ["TexFix.jar", "o.jar", "optifine.jar"])
        self.assertEqual(P.sha256_file(self.p("g1", "mods", "o.jar")), P.sha256_file(origin))
        self.assertEqual(rec["variant"], "origin-default")
        self.assertEqual(len(rec["mods"]), 3)

    def test_optifine_only_has_the_stack_and_an_inert_origin_jar(self):
        origin, opti, tex = self.setup_jars()
        rec = P.prepare_game_dir(self.p("g2"), "optifine-only", origin, opti, [tex])
        self.assertEqual(sorted(os.listdir(self.p("g2", "mods"))), ["TexFix.jar", "o.jar", "optifine.jar"])
        with zipfile.ZipFile(self.p("g2", "mods", "o.jar")) as z:
            self.assertNotIn("TweakClass", z.read("META-INF/MANIFEST.MF").decode())
        self.assertNotEqual(P.sha256_file(self.p("g2", "mods", "o.jar")), P.sha256_file(origin))
        self.assertFalse(os.path.exists(self.p("g2", "_probe.jar")))
        self.assertEqual(rec["variant"], "optifine-only")

    def test_unknown_variant(self):
        origin, opti, tex = self.setup_jars()
        with self.assertRaises(P.ProvisionError):
            P.prepare_game_dir(self.p("g3"), "nope", origin, opti, [tex])

    def test_bench_env_switches(self):
        a = argparse.Namespace(scenes="idle", warmup=15, duration=45, width=1280, height=720, shots=False,
                               save_options=True)
        e1 = P.bench_env(a, "optifine-only", "/o", "b")
        self.assertEqual(e1["ORIGIN_BENCH_PROBE"], "1")
        self.assertEqual(e1["ORIGIN_BENCH_SIZE"], "1280x720")
        self.assertEqual(e1["ORIGIN_BENCH_SAVE_OPTIONS"], "1")
        e2 = P.bench_env(a, "origin-default", "/o", "b")
        self.assertEqual(e2["ORIGIN_BENCH_PROBE"], "0")
        self.assertEqual(e2["ORIGIN_BENCH_VARIANT"], "origin-default")


class Presets(Tmp):
    def _w(self, name, text):
        path = self.p(name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def _game(self):
        game = self.p("game")
        os.makedirs(game)
        return game

    def test_known_keys_are_written(self):
        keys = self._w("keys.txt", "# verified\nofFastRender\nofChunkUpdates\n")
        preset = self._w("p.json", json.dumps({"options": {"maxFps": "260"}, "optionsof": {"ofFastRender": "true"}}))
        game = self._game()
        P.apply_preset(game, preset, keys)
        with open(os.path.join(game, "optionsof.txt")) as f:
            self.assertEqual(f.read(), "ofFastRender:true\n")
        with open(os.path.join(game, "options.txt")) as f:
            self.assertEqual(f.read(), "maxFps:260\n")

    def test_an_unverified_optifine_key_is_refused(self):
        keys = self._w("keys.txt", "ofFastRender\n")
        preset = self._w("p.json", json.dumps({"optionsof": {"ofMadeUp": "1"}}))
        game = self._game()
        with self.assertRaises(P.ProvisionError) as c:
            P.apply_preset(game, preset, keys)
        self.assertIn("ofMadeUp", str(c.exception))
        self.assertFalse(os.path.exists(os.path.join(game, "optionsof.txt")))

    def test_missing_key_list_refuses_optifine_keys(self):
        preset = self._w("p.json", json.dumps({"optionsof": {"ofFastRender": "true"}}))
        with self.assertRaises(P.ProvisionError):
            P.apply_preset(self._game(), preset, self.p("nope.txt"))

    def test_the_committed_key_list_is_the_verified_72(self):
        keys = P.load_key_list()
        self.assertEqual(len(keys), 72)
        self.assertTrue(all(k.startswith("of") or k == "key_of.key.zoom" for k in keys))
        self.assertIn("ofFastRender", keys)
        with open(P.KEYS_FILE, encoding="utf-8") as f:
            names = [ln.split(":")[0] for ln in f if ln.strip() and not ln.startswith("#")]
        self.assertEqual(len(names), len(set(names)), "duplicate key in the list")

    def test_an_optifine_key_under_options_is_refused(self):
        """of* / key_of.* in "options" would skip the verified-list check, so it is refused outright."""
        keys = self._w("keys.txt", "ofFastRender\n")
        for stray in ("ofFastRender", "ofMadeUp", "ofRenderRegions", "key_of.key.zoom"):
            preset = self._w("p.json", json.dumps({"options": {"maxFps": "260", stray: "1"}}))
            game = self._game()
            with self.assertRaises(P.ProvisionError, msg=stray) as c:
                P.apply_preset(game, preset, keys)
            self.assertIn(stray, str(c.exception))
            self.assertFalse(os.path.exists(os.path.join(game, "options.txt")), "nothing is written on refusal")
            shutil.rmtree(game)

    def test_vanilla_keys_that_merely_contain_of_are_not_refused(self):
        preset = self._w("p.json", json.dumps({"options": {"fov": "70.0", "forceUnicodeFont": "false",
                                                           "key_key.forward": "17"}}))
        P.apply_preset(self._game(), preset, self.p("nope.txt"))

    def test_every_committed_preset_validates(self):
        """Phase 6.2 puts the 1.8.9 presets in tools/bench/presets-189/*.json (the same shape apply_preset reads).
        Every one must pass the real key list here, so a typo'd OptiFine key fails CI, not a player's launch.
        Skipped until that folder has presets; if 6.2 keeps them elsewhere, change presets_dir below."""
        presets_dir = os.path.join(HERE, "presets-189")
        files = sorted(f for f in (os.listdir(presets_dir) if os.path.isdir(presets_dir) else [])
                       if f.endswith(".json"))
        if not files:
            self.skipTest("no presets-189/*.json yet (arrives with Phase 6.2)")
        for name in files:
            with self.subTest(preset=name):
                game = self.p("game-" + name)
                os.makedirs(game)
                P.apply_preset(game, os.path.join(presets_dir, name))

    def test_malformed_preset_is_refused(self):
        preset = self._w("p.json", "[1,2]")
        with self.assertRaises(P.ProvisionError):
            P.apply_preset(self._game(), preset, self.p("nope.txt"))


class RoundTrip(Tmp):
    def _w(self, name, text):
        path = self.p(name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def _saved(self, options="", optionsof=""):
        d = self.p("saved")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "options.txt"), "w", encoding="latin-1", newline="") as f:
            f.write(options)
        with open(os.path.join(d, "optionsof.txt"), "w", encoding="latin-1", newline="") as f:
            f.write(optionsof)
        return d

    def _preset(self):
        return self._w("p.json", json.dumps({"options": {"renderDistance": "8", "ao": "2", "fancyGraphics": "false"},
                                              "optionsof": {"ofVignette": "1", "ofFastMath": "true", "ofClouds": "3"}}))

    def test_values_that_survive_are_clean_even_when_reformatted(self):
        saved = self._saved("renderDistance:8.0\r\nao:true\r\nfancyGraphics:False\r\nother:1\r\n",
                            "ofVignette:1\nofFastMath:true\nofClouds:3\n")
        self.assertEqual(P.compare_roundtrip(self._preset(), saved), [])

    def test_a_clamped_value_is_reported_written_to_saved(self):
        saved = self._saved("renderDistance:8\nao:2\nfancyGraphics:false\n", "ofVignette:2\nofFastMath:true\nofClouds:3\n")
        self.assertEqual(P.compare_roundtrip(self._preset(), saved), [("optionsof.txt", "ofVignette", "1", "2")])

    def test_a_missing_key_or_file_is_a_difference(self):
        saved = self._saved("renderDistance:8\nao:2\n", "ofVignette:1\nofClouds:3\n")
        got = P.compare_roundtrip(self._preset(), saved)
        self.assertIn(("options.txt", "fancyGraphics", "false", None), got)
        self.assertIn(("optionsof.txt", "ofFastMath", "true", None), got)
        empty = self.p("nothing")
        os.makedirs(empty)
        self.assertEqual(len(P.compare_roundtrip(self._preset(), empty)), 6)

    def test_the_command_exits_nonzero_on_a_difference(self):
        ok = self._saved("renderDistance:8\nao:2\nfancyGraphics:false\n", "ofVignette:1\nofFastMath:true\nofClouds:3\n")
        self.assertEqual(P.main(["check-roundtrip", "--preset", self._preset(), "--saved", ok]), 0)
        bad = self._saved("renderDistance:8\nao:2\nfancyGraphics:false\n", "ofVignette:0\nofFastMath:true\nofClouds:3\n")
        self.assertEqual(P.main(["check-roundtrip", "--preset", self._preset(), "--saved", bad]), 1)


class ShaderPack(Tmp):
    """B-04 shader variant: the bench's own pack is copied in, selected, and a run where it did not load fails."""
    PACK = os.path.join(HERE, "shaderpack-189", "origin-bench")

    def test_pack_is_copied_and_selected(self):
        game = self.p("game")
        os.makedirs(game)
        self.assertEqual(P.install_shaderpack(game, self.PACK), "origin-bench")
        self.assertTrue(os.path.isfile(os.path.join(game, "shaderpacks", "origin-bench", "shaders", "shadow.vsh")))
        with open(os.path.join(game, "optionsshaders.txt"), encoding="ascii") as f:
            self.assertEqual(f.read(), "shaderPack=origin-bench\n")

    def test_a_folder_without_a_final_pass_is_refused(self):
        os.makedirs(self.p("notapack", "shaders"))
        with self.assertRaises(P.ProvisionError):
            P.install_shaderpack(self.p(), self.p("notapack"))

    def test_shaders_were_on_reads_the_game_flag(self):
        for active, want in ((True, True), (False, False), (None, False)):
            path = self.p("bench-%s.json" % active)
            env = {} if active is None else {"shaderPackActive": active}
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"environment": env}, f)
            self.assertEqual(P.shaders_were_on(path), want)
        self.assertFalse(P.shaders_were_on(self.p("missing.json")))


class JvmAb(unittest.TestCase):
    """U6.4: each named JVM set is the shipped one with ONE change, so its delta belongs to that change."""

    def test_one_change_each(self):
        base = P.LAUNCHER_JVM.split()
        for name, want_gone, want_new in (
                ("g1-pause40", {"-XX:MaxGCPauseMillis=200"}, {"-XX:MaxGCPauseMillis=40"}),
                ("g1-young15", {"-XX:G1NewSizePercent=25", "-XX:G1MaxNewSizePercent=35"},
                 {"-XX:G1NewSizePercent=15", "-XX:G1MaxNewSizePercent=25"}),
                ("heap2g", {"-Xms4096M", "-Xmx4096M"}, {"-Xms2048M", "-Xmx2048M"}),
                ("heap6g", {"-Xms4096M", "-Xmx4096M"}, {"-Xms6144M", "-Xmx6144M"})):
            flags = P.JVM_AB[name].split()
            self.assertEqual(set(base) - set(flags), want_gone, name)
            self.assertEqual(set(flags) - set(base), want_new, name)

    def test_other_collectors_carry_no_g1_flag(self):
        for name, gc in (("parallel", "-XX:+UseParallelGC"), ("cms", "-XX:+UseConcMarkSweepGC")):
            flags = P.JVM_AB[name].split()
            self.assertIn(gc, flags)
            self.assertNotIn("-XX:+UseG1GC", flags)
            self.assertFalse([f for f in flags if f.startswith("-XX:G1")], name)
            self.assertIn("-Xmx4096M", flags)

    def test_cli_variant_overrides_jvm(self):
        a = P.build_parser().parse_args(["run", "--origin-jar", "x", "--out", "o", "--jvm-variant", "cms", "--no-betterfps"])
        self.assertEqual((a.jvm_variant, a.no_betterfps), ("cms", True))


class JarKeys(Tmp):
    """E3: saved-key literals come out of the jar's String constants, never from field names."""

    @staticmethod
    def _class():
        def utf8(s):
            b = s.encode("utf-8")
            return b"\x01" + len(b).to_bytes(2, "big") + b
        pool = [utf8("ofFogType:"), b"\x08\x00\x01",          # 1, 2: String "ofFogType:"
                utf8("ofFieldOnly:"),                          # 3: a Utf8 no String points at (a name, not a literal)
                b"\x05" + (7).to_bytes(8, "big"),              # 4-5: a long takes two slots
                utf8("ofAfterLong:"), b"\x08\x00\x06",         # 6, 7
                utf8("ofNoColon"), b"\x08\x00\x08"]            # 8, 9: a load-side literal, not a save one
        return b"\xca\xfe\xba\xbe\x00\x00\x00\x34" + (10).to_bytes(2, "big") + b"".join(pool) + b"\x00" * 8

    def _jar(self):
        path = self.p("optifine.jar")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("avh.class", self._class())
            z.writestr("patch/other.xdelta", b"\x00\x01ofSky:\x02")
            z.writestr("notes/", b"")
        return path

    def _keys(self, *names):
        path = self.p("keys.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write("# comment\n" + "".join("%s:x\n" % n for n in names) + "key_of.key.zoom:46\n")
        return path

    def test_reads_string_constants_only(self):
        found = P.optifine_jar_saved_keys(self._jar())
        self.assertEqual(sorted(found), ["ofAfterLong", "ofFogType", "ofSky"])
        self.assertEqual(found["ofFogType"], ["avh.class"])
        self.assertEqual(found["ofSky"], ["patch/other.xdelta"])

    def test_cli_passes_when_every_listed_key_is_in_the_jar(self):
        argv = ["jar-keys", "--jar", self._jar(), "--sha1", ""]
        self.assertEqual(P.main(argv + ["--keys", self._keys("ofFogType", "ofSky")]), 0)

    def test_cli_fails_on_a_listed_key_the_jar_never_writes(self):
        argv = ["jar-keys", "--jar", self._jar(), "--sha1", ""]
        self.assertEqual(P.main(argv + ["--keys", self._keys("ofFogType", "ofFieldOnly")]), 1)

    def test_gdiff_patch_is_rebuilt_against_vanilla(self):
        # OptiFine M5 ships patch/avh.class.xdelta (GDIFF). A DATA command boundary can split a literal in the raw
        # bytes (CI run 40245200772: ofPotionParticles, ofRandomEntities), so the class is rebuilt from vanilla.
        target = self._class()
        vanilla = target[:12] + b"vanilla-only"
        cut = target.index(b"ofAfterLong:") + 4                     # split "ofAf" | "terLong:"
        patch = (b"\xd1\xff\xd1\xff\x04" + b"\xf9" + (0).to_bytes(2, "big") + bytes([12])   # COPY 12 from 0
                 + bytes([cut - 12]) + target[12:cut]                                          # DATA
                 + b"\xf7" + (len(target) - cut).to_bytes(2, "big") + target[cut:] + b"\x00")
        self.assertEqual(P.gdiff_apply(vanilla, patch), target)
        self.assertEqual(P.gdiff_apply(vanilla, patch[:-1]), target)      # OptiFine's patches carry no EOF byte
        of_jar, van_jar, bad_jar = self.p("of.jar"), self.p("vanilla.jar"), self.p("bad.jar")
        with zipfile.ZipFile(of_jar, "w") as z:
            z.writestr("patch/avh.class.xdelta", patch)
            z.writestr("patch/avh.class.md5", hashlib.md5(target).hexdigest())
        with zipfile.ZipFile(bad_jar, "w") as z:
            z.writestr("patch/avh.class.xdelta", patch)
            z.writestr("patch/avh.class.md5", "0" * 32)
        with zipfile.ZipFile(van_jar, "w") as z:
            z.writestr("avh.class", vanilla)
        self.assertEqual(P.optifine_patched_classes(bad_jar, van_jar), {})  # md5 mismatch: not counted
        self.assertNotIn("ofAfterLong", P.optifine_jar_saved_keys(of_jar))          # the raw scan misses it
        rebuilt = P.optifine_patched_classes(of_jar, van_jar)
        found = P.optifine_jar_saved_keys(of_jar, rebuilt)
        self.assertEqual(sorted(found), ["ofAfterLong", "ofFogType"])
        out = self.p("classes")
        argv = ["jar-keys", "--jar", of_jar, "--sha1", "", "--vanilla", van_jar, "--write-classes", out]
        self.assertEqual(P.main(argv + ["--keys", self._keys("ofFogType", "ofAfterLong")]), 0)
        self.assertTrue(os.path.isfile(os.path.join(out, "avh.class")))

    def test_gdiff_rejects_other_formats(self):
        with self.assertRaises(ValueError):
            P.gdiff_apply(b"", b"\xd6\xc3\xc4\x00\x00")       # VCDIFF (xdelta3) magic

    def test_cli_refuses_another_jar(self):
        self.assertEqual(P.main(["jar-keys", "--jar", self._jar(), "--keys", self._keys("ofFogType")]), 2)


if __name__ == "__main__":
    unittest.main()
