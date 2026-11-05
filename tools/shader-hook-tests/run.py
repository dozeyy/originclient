"""Test the shader fixes from bug run 11 (2026-11-05).

    python tools/shader-hook-tests/run.py

1. Every live Fabric module carries the same shaders/IrisScreenHook.java (the pre-1.20
   modules are .no-shared-sync and the rest fork IrisBridge, so nothing else checks it).
2. Source gates, every live module:
   - Iris's Shader Packs screen goes through IrisScreenHook.attach (SHADER-1/10).
   - Shader Performance Mode reloads Iris from ONE place, IrisBridge's tick watch, so
     the reset arrow, Profiles and Settings Sync can't miss it (SHADER-3): the mod menu
     and FPS Coach no longer reload by hand.
   - Remove switches the selected pack off before deleting it (SHADER-2); a finished
     download rebuilds Iris's open screen (SHADER-4); 1.8.9 saves OptiFine's choice (SHADER-5).
3. ShaderHookTests (Java, stubs for Minecraft/Fabric/Iris): IrisScreenHook against Iris 1.8's
   screen shape and an older Iris; 1.8.9's off-thread browser warning (SHADER-8).
CI: build-check.
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "com/origin/client/client"


def module_file(mod, rel):
    hits = glob.glob(os.path.join(ROOT, "src/mods/versions", mod, "src/*/java", PKG, rel))
    return hits[0] if len(hits) == 1 else None


def read(path):
    return open(path, encoding="utf-8").read() if path else ""


def check_sources():
    live = [m["module"] for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]]
    fabric = [m for m in live if m != "1.8.9"]
    bad = []
    hook = read(module_file("1.21.1", "shaders/IrisScreenHook.java"))
    if not hook:
        bad.append("1.21.1: shaders/IrisScreenHook.java missing")
    for mod in fabric:
        if read(module_file(mod, "shaders/IrisScreenHook.java")) != hook:
            bad.append(f"{mod}: IrisScreenHook.java missing or differs from 1.21.1's")
        main = read(module_file(mod, "OriginClientMod.java"))
        if "IrisScreenHook.attach(" not in main:
            bad.append(f"{mod}: Iris's shader screen doesn't go through IrisScreenHook")
        if "IrisBridge.watchShaderPerformanceMode()" not in main:
            bad.append(f"{mod}: Shader Performance Mode watch not registered")
        if "reloadIfPackActive" in read(module_file(mod, "gui/OriginModMenuScreen.java")):
            bad.append(f"{mod}: mod menu reloads Iris by hand (IrisBridge's watch owns it)")
        if "reloadIfInUse" in read(module_file(mod, "mods/FpsCoachTips.java")):
            bad.append(f"{mod}: FPS Coach reloads Iris by hand (IrisBridge's watch owns it)")
        dl = read(module_file(mod, "shaders/ShaderDownloader.java"))
        if "IrisBridge.refreshIrisScreen()" not in dl:
            bad.append(f"{mod}: a finished download doesn't rebuild Iris's open screen")
    # In-world menus never blur (DECISIONS 2026-11-04): Iris's screen overrides Screen's blur,
    # so modules with vanilla's menu blur (1.20.5+) zero Iris's radius through the option.
    for mod in fabric:
        mixins = glob.glob(os.path.join(ROOT, "src/mods/versions", mod, "src/*/resources/originclient.client.mixins.json"))
        if "BlurredBackground" in read(module_file(mod, "mixin/MenuBlurMixin.java")):
            if '"IrisBlurOptionMixin"' not in read(mixins[0]) or not module_file(mod, "mixin/IrisBlurOptionMixin.java"):
                bad.append(f"{mod}: Iris's Shader Packs screen can still blur the world (IrisBlurOptionMixin)")
    for mod in live:
        dl = read(module_file(mod, "shaders/ShaderDownloader.java"))
        remove = dl[dl.find("public static void remove("):]
        remove = remove[:remove.find("\n\t}\n")]
        if not re.search(r"IrisBridge\.selectedPack\(\)\)\) \{\s*IrisBridge\.apply\(null\);", remove):
            bad.append(f"{mod}: Remove doesn't switch the selected pack off first")
        if "removeNotice = \"Couldn't remove" not in remove:
            bad.append(f"{mod}: a failed Remove isn't reported")
    bridge189 = read(module_file("1.8.9", "shaders/IrisBridge.java"))
    apply189 = bridge189[bridge189.find("public static boolean apply("):]
    if "storeConfig" not in apply189[:apply189.find("\n\t}\n")]:
        bad.append("1.8.9: IrisBridge.apply doesn't save OptiFine's choice (storeConfig)")
    print(f"sources: {len(fabric)} Fabric modules + 1.8.9, {'all gates hold' if not bad else 'PROBLEMS'}")
    for b in bad:
        print("  " + b)
    return not bad


def run_java():
    out = tempfile.mkdtemp(prefix="shader-hook-tests-")
    srcs = glob.glob(os.path.join(HERE, "stubs", "**", "*.java"), recursive=True)
    srcs += [module_file("1.21.1", "shaders/IrisScreenHook.java"),
             module_file("1.8.9", "shaders/ShaderCompat.java"),
             os.path.join(HERE, "ShaderHookTests.java")]
    if subprocess.run(["javac", "--release", "8", "-nowarn", "-d", out] + srcs).returncode:
        return False
    ok = True
    for mode in ("iris", "oldiris", "compat"):
        ok &= subprocess.run(["java", "-cp", out, "ShaderHookTests", mode]).returncode == 0
    return ok


if __name__ == "__main__":
    ok = check_sources()
    ok &= run_java()
    print("shader hook tests: " + ("PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)
