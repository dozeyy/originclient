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
        prov = P.provision_cache(cache, "linux", get)
        self.assertTrue(prov["classpath"][-1].endswith("1.8.9.jar"))
        self.assertTrue(any(c.endswith("forge-1.8.9-11.15.1.2318-1.8.9.jar") for c in prov["classpath"]))
        self.assertTrue(os.path.isfile(os.path.join(prov["natives"], "liblwjgl64.so")))
        self.assertFalse(os.path.exists(os.path.join(prov["natives"], "META-INF", "junk")))
        self.assertEqual(prov["mainClass"], "net.minecraft.launchwrapper.Launch")
        self.assertEqual(prov["assetIndex"], "1.8")
        self.assertGreater(len(self.requests), 5)

        def no_net(url, **kw):
            raise AssertionError("second provision must not touch the network: " + url)
        prov2 = P.provision_cache(cache, "linux", no_net)
        self.assertEqual(prov2["classpath"], prov["classpath"])

    def test_a_corrupt_asset_stops_the_provision(self):
        get, _ = self.build_world()

        def bad(url, **kw):
            if url.startswith(P.ASSET_URL):
                return b"corrupt"
            return get(url, **kw)
        with self.assertRaises(P.ProvisionError):
            P.provision_cache(self.p("cache"), "linux", bad)


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

    def test_malformed_preset_is_refused(self):
        preset = self._w("p.json", "[1,2]")
        with self.assertRaises(P.ProvisionError):
            P.apply_preset(self._game(), preset, self.p("nope.txt"))


if __name__ == "__main__":
    unittest.main()
