#!/usr/bin/env python3
"""Crash plant: break a REAL Minecraft 1.21.1 boot on purpose, one known defect at a time, and keep what the
stopped game left as a crash-corpus case the launcher's diagnosis is then checked against (CI only:
.github/workflows/crash-plant.yml). This is how Crash Report V3 proves it reads real loader/JVM output, not just
the hand-written fixtures in src/OriginLauncher.Tests/CrashCorpus.

Each case: plant jars into <module>/run/mods (or a JVM option), run `gradlew runClient` under Xvfb with
ORIGIN_SMOKE=1, stop at the Origin title-screen marker (= it booted) or when the game exits, then write
<out>/<case>/ with launch.log (the game's own output), latest.log, crash-*.txt, hs_err_pid*.log when present,
exit.txt, mods.txt and expected.json in the corpus format (CrashCorpusTests.LoadEvidence).

Usage: python3 tools/crash-plant/plant.py <module-dir> <out-dir> [--timeout SECONDS] [--only CASE[,CASE]]
Never run it on Will's PC (it opens Minecraft): CI only.
"""
import argparse
import glob
import io
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import zipfile

READY = re.compile(r"\[origin-smoke\] title screen reached")
# ModernFix logs this once loading is over. CI run 40249284621: the duplicate-id boot printed it but the Origin
# marker never reached stdout (Gradle held the game's output until the kill), so either line counts as booted,
# and latest.log on disk is watched as well as stdout.
GAME_TOOK = re.compile(r"Game took [0-9.]+ seconds to start")
FATAL = re.compile(r"Minecraft has crashed|Uncaught exception in thread|Incompatible mods found|"
                   r"Error: Could not create the Java Virtual Machine|OutOfMemoryError|Mixin apply .* failed")
GRADLE_EXIT = re.compile(r"finished with non-zero exit value (-?\d+)")
TASK_START = re.compile(r"^> Task :runClient\b")
GRADLE_TAIL = re.compile(r"^(FAILURE: Build failed|BUILD FAILED|BUILD SUCCESSFUL|\* What went wrong:|> Task :)")

ORIGIN_MODS = ["originclient originclient.jar origin", "fabric-api fabric-api.jar origin"]


def mod_json(mod_id, version="1.0.0", depends=None, mixins=None):
    data = {"schemaVersion": 1, "id": mod_id, "version": version, "name": "Plant " + mod_id, "environment": "*"}
    if depends:
        data["depends"] = depends
    if mixins:
        data["mixins"] = mixins
    return json.dumps(data, indent=2)


def write_jar(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)


MIXIN_SRC = """package plant.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Targets a method Minecraft does not have: Mixin must refuse to apply it (require 1 = a hard failure).
@Mixin(targets = "net.minecraft.client.Minecraft")
public class PlantMixin {
    @Inject(method = "plantNoSuchMethod", at = @At("HEAD"), require = 1)
    private void plant$missing(CallbackInfo ci) {}
}
"""


def build_mixin_jar(path, work):
    jars = sorted(glob.glob(os.path.expanduser("~/.gradle/caches/**/sponge-mixin-*.jar"), recursive=True))
    jars = [j for j in jars if not j.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars:
        raise SystemExit("crash-plant: no sponge-mixin jar in the Gradle cache (run gradle_prefetch.sh first)")
    src = os.path.join(work, "src", "plant", "mixin")
    os.makedirs(src, exist_ok=True)
    with open(os.path.join(src, "PlantMixin.java"), "w") as f:
        f.write(MIXIN_SRC)
    classes = os.path.join(work, "classes")
    subprocess.run(["javac", "--release", "21", "-proc:none", "-cp", jars[-1], "-d", classes,
                    os.path.join(src, "PlantMixin.java")], check=True)
    with open(os.path.join(classes, "plant", "mixin", "PlantMixin.class"), "rb") as f:
        cls = f.read()
    config = json.dumps({"required": True, "minVersion": "0.8", "package": "plant.mixin",
                         "compatibilityLevel": "JAVA_21", "client": ["PlantMixin"],
                         "injectors": {"defaultRequire": 1}}, indent=2)
    write_jar(path, {"fabric.mod.json": mod_json("plantmixin", mixins=["plantmixin.mixins.json"]),
                     "plantmixin.mixins.json": config, "plant/mixin/PlantMixin.class": cls})


def exp(crash, rule=None, cause=None, confidence=None, culprits=(), fix="None", jars=None):
    e = {"crash": crash}
    if rule:
        e.update(rule=rule, cause=cause, confidence=confidence)
    e["culprits"] = list(culprits)
    e["fix"] = fix
    if jars is not None:
        e["fixJars"] = jars
    return e


def case_defs():
    """name -> (plant(mods_dir, work) -> mods.txt lines, extra JVM args, ramMb, expected)."""
    def missing_dep(mods, work):
        write_jar(os.path.join(mods, "plant-missing-dep-1.0.0.jar"),
                  {"fabric.mod.json": mod_json("plantmissing", depends={"plantneededlib": "*"})})
        return ["plantmissing plant-missing-dep-1.0.0.jar external"]

    def wrong_mc(mods, work):
        write_jar(os.path.join(mods, "plant-wrong-mc-1.0.0.jar"),
                  {"fabric.mod.json": mod_json("plantwrongmc", depends={"minecraft": "1.20.1"})})
        return ["plantwrongmc plant-wrong-mc-1.0.0.jar external"]

    def duplicate(mods, work):
        write_jar(os.path.join(mods, "plant-dup-1.0.0.jar"), {"fabric.mod.json": mod_json("plantdup", "1.0.0")})
        write_jar(os.path.join(mods, "plant-dup-1.0.1.jar"), {"fabric.mod.json": mod_json("plantdup", "1.0.1")})
        return ["plantdup plant-dup-1.0.0.jar external", "plantdup plant-dup-1.0.1.jar external"]

    def mixin(mods, work):
        build_mixin_jar(os.path.join(mods, "plant-mixin-1.0.0.jar"), work)
        return ["plantmixin plant-mixin-1.0.0.jar external"]

    def truncated(mods, work):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("fabric.mod.json", mod_json("planttrunc"))
            z.writestr("filler.txt", "x" * 4096)
        with open(os.path.join(mods, "plant-truncated-1.0.0.jar"), "wb") as f:
            f.write(buf.getvalue()[: len(buf.getvalue()) // 2])  # cut mid-file: no central directory
        return ["- plant-truncated-1.0.0.jar external"]

    def nothing(mods, work):
        return []

    return {
        # Fabric 0.19.3 names the mod and the missing dependency in its "Incompatible mods found!" block.
        "plant-missing-dep": (missing_dep, [], 4096, exp(True, "fabric-dependency", "ModProblem", "confirmed",
                              ["plantmissing"], "DisableMods", ["plant-missing-dep-1.0.0.jar"])),
        "plant-wrong-mc": (wrong_mc, [], 4096, exp(True, "fabric-dependency", "ModProblem", "confirmed",
                           ["plantwrongmc"], "DisableMods", ["plant-wrong-mc-1.0.0.jar"])),
        # Fabric 0.19.3 has no duplicate-id error: two jars with one id resolve to the newer one ("newer version
        # active") and the game boots. Not a crash, so the window must stay shut.
        "plant-duplicate-id": (duplicate, [], 4096, exp(False)),
        "plant-mixin-target": (mixin, [], 4096, exp(True, "mixin-apply", "ModProblem", "strong",
                               ["plantmixin"], "DisableMods", ["plant-mixin-1.0.0.jar"])),
        "plant-oom-64m": (nothing, ["-Xmx64m"], 2048, exp(True, "out-of-memory", "OutOfMemory", "confirmed",
                          [], "RaiseMemory")),
        "plant-bad-jvm-option": (nothing, ["-XX:+OriginNoSuchOption"], 4096, exp(True, "jvm-start", "JavaProblem",
                                 "confirmed", [], "None")),
        "plant-truncated-jar": (truncated, [], 4096, exp(True, "damaged-file", "DamagedFiles", "confirmed",
                                ["plant-truncated-1.0.0.jar"], "DisableMods", ["plant-truncated-1.0.0.jar"])),
    }


def kill_tree(proc):
    if proc.poll() is not None:
        return
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def run_case(module, name, plant, jvm_args, ram_mb, expected, out_root, timeout):
    run = os.path.join(module, "run")
    mods = os.path.join(run, "mods")
    os.makedirs(mods, exist_ok=True)
    for old in glob.glob(os.path.join(mods, "plant-*.jar")):
        os.remove(old)
    for old in glob.glob(os.path.join(run, "crash-reports", "*")) + glob.glob(os.path.join(run, "hs_err_pid*.log")) \
            + glob.glob(os.path.join(run, "logs", "latest.log")):
        os.remove(old)
    work = os.path.join(out_root, "_work", name)
    os.makedirs(work, exist_ok=True)
    mod_lines = plant(mods, work)

    args = ["-Dfabric.noGui=true"] + jvm_args
    cmd = [os.path.join(".", "gradlew"), "runClient", "--no-daemon", "-PbootJvmArgs=" + ",".join(args)]
    env = dict(os.environ, ORIGIN_SMOKE="1")
    proc = subprocess.Popen(cmd, cwd=module, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1,
                            universal_newlines=True, errors="replace", env=env, preexec_fn=os.setsid)
    timer = threading.Timer(timeout, lambda: kill_tree(proc))
    timer.start()
    lines, game, in_game, start = [], [], False, time.monotonic()
    booted = threading.Event()
    done = threading.Event()

    def booted_text(text):
        return bool(READY.search(text) or GAME_TOOK.search(text)) and not FATAL.search(text)

    def watch_log():
        # latest.log is written by the game itself, so it shows the boot even while Gradle buffers stdout.
        path = os.path.join(run, "logs", "latest.log")
        while not done.wait(3):
            try:
                with open(path, errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            if booted_text(text):
                booted.set()
                kill_tree(proc)
                return

    watcher = threading.Thread(target=watch_log, daemon=True)
    watcher.start()
    try:
        for line in proc.stdout:
            sys.stdout.write(line)
            lines.append(line)
            if TASK_START.match(line):
                in_game = True
                continue
            if in_game and GRADLE_TAIL.match(line):
                in_game = False
            if in_game:
                game.append(line)
            if READY.search(line) or GAME_TOOK.search(line):
                if not FATAL.search("".join(game)):
                    booted.set()
                    break
    finally:
        done.set()
        timer.cancel()
        kill_tree(proc)
        proc.wait()
        watcher.join(5)
    booted = booted.is_set()
    elapsed = time.monotonic() - start
    gradle_exit = GRADLE_EXIT.search("".join(lines))
    timed_out = not booted and elapsed >= timeout - 1
    # The game's own exit code (Gradle reports it); a boot that reached the title screen is closed by us, which the
    # launcher would see as the player quitting (exit 0).
    exit_code = 0 if booted else int(gradle_exit.group(1)) if gradle_exit else (0 if proc.returncode == 0 else 1)

    case = os.path.join(out_root, name)
    os.makedirs(case, exist_ok=True)
    with open(os.path.join(case, "launch.log"), "w") as f:
        f.write("".join(game))
    for src in glob.glob(os.path.join(run, "logs", "latest.log")) + glob.glob(os.path.join(run, "crash-reports", "crash-*.txt")) \
            + glob.glob(os.path.join(run, "hs_err_pid*.log")):
        shutil.copy(src, os.path.join(case, os.path.basename(src)))
    with open(os.path.join(case, "exit.txt"), "w") as f:
        f.write(f"exit={exit_code}\nwhileStarting={'false' if booted else 'true'}\nramMb={ram_mb}\ntotalMb=16384\n")
    with open(os.path.join(case, "mods.txt"), "w") as f:
        f.write("\n".join(mod_lines + ORIGIN_MODS) + "\n")
    with open(os.path.join(case, "expected.json"), "w") as f:
        json.dump(expected, f, indent=2)
    for old in glob.glob(os.path.join(mods, "plant-*.jar")):
        os.remove(old)
    state = "booted (clean close)" if booted else ("TIMED OUT" if timed_out else f"exited {exit_code}")
    print(f"::notice title=crash-plant {name}::{state} after {elapsed:.0f}s; {len(game)} game lines", flush=True)
    return state


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module")
    ap.add_argument("out")
    ap.add_argument("--timeout", type=int, default=480)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    module, out = os.path.abspath(a.module), os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)
    only = {x.strip() for x in a.only.split(",") if x.strip()}
    states = {}
    for name, (plant, jvm, ram, expected) in case_defs().items():
        if only and name not in only:
            continue
        print(f"\n===== crash-plant: {name} =====", flush=True)
        states[name] = run_case(module, name, plant, jvm, ram, expected, out, a.timeout)
    shutil.rmtree(os.path.join(out, "_work"), ignore_errors=True)
    print("\ncrash-plant runs:")
    for k, v in states.items():
        print(f"  {k}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
