"""Test the in-game guards from bug run 3 (2026-11-03).

    python tools/ingame-guard-tests/run.py

1. Every live module carries the pure helpers byte-identical to shared/ (the pre-1.20
   and 1.8.9 modules are .no-shared-sync, so sync.py --check doesn't see theirs):
   worldedit/CuiNumbers (a server can't freeze the game over WorldEdit CUI),
   ConfigFiles (a damaged config is kept as .corrupt), mods/HypixelDetect,
   mods/HypixelLocation (waypoints per Hypixel game and map).
2. IngameGuardTests: those helpers + WaypointScope's per-version key (Portal Calc's).
3. ModsConfigTests: the shared ModsConfig itself, one JVM per scenario (damaged file,
   wrong-shape entries, profiles keep the Hypixel API key). Needs a Gson jar: found on
   the machine (Gradle/Maven ship one) or fetched from Maven Central (checked against its .sha1).
CI: build-check.
"""
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = "src/mods/shared/src/client/java/"
HELPERS = [
    "com/origin/client/client/worldedit/CuiNumbers.java",
    "com/origin/client/client/ConfigFiles.java",
    "com/origin/client/client/mods/HypixelDetect.java",
    "com/origin/client/client/mods/HypixelLocation.java",
]
GSON_URL = "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"


def check_copies():
    live = [m["module"] for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]]
    bad = []
    for rel in HELPERS:
        want = open(os.path.join(ROOT, JAVA, rel), "rb").read()
        for mod in live:
            copies = glob.glob(os.path.join(ROOT, "src/mods/versions", mod, "src/*/java", rel))
            if len(copies) != 1:
                bad.append(f"{mod}: {rel} missing")
            elif open(copies[0], "rb").read() != want:
                bad.append(f"{mod}: {rel} differs from shared")
    print(f"helper copies: {len(HELPERS)} files x {len(live)} live modules, "
          f"{'all identical to shared' if not bad else 'PROBLEMS'}")
    for b in bad:
        print("  " + b)
    return not bad


def find_gson():
    if os.environ.get("GSON_JAR"):
        return os.environ["GSON_JAR"]
    # Maven 3.9 and Gradle carry Gson in their lib/ (GitHub's Ubuntu runners have Maven).
    for pattern in ("/opt/*/lib/gson-2.*.jar", "/usr/share/*/lib/gson-2.*.jar", "/usr/local/*/lib/gson-2.*.jar"):
        hits = sorted(glob.glob(pattern, recursive=True))
        # JsonParser.parseString / JsonObject.keySet need a modern Gson (2.8.6+).
        hits = [h for h in hits if not os.path.basename(h).startswith(("gson-2.2", "gson-2.3", "gson-2.4", "gson-2.5", "gson-2.6", "gson-2.7"))]
        if hits:
            return hits[-1]
    dest = os.path.join(tempfile.mkdtemp(prefix="gson-"), "gson.jar")
    data = urllib.request.urlopen(GSON_URL, timeout=60).read()
    want = urllib.request.urlopen(GSON_URL + ".sha1", timeout=60).read().decode().split()[0]
    if hashlib.sha1(data).hexdigest() != want:
        sys.exit("downloaded Gson failed its .sha1 check")
    open(dest, "wb").write(data)
    return dest


def main():
    ok = check_copies()
    out = tempfile.mkdtemp(prefix="guards-")
    srcs = [os.path.join(ROOT, JAVA, r) for r in HELPERS] + [
        os.path.join(ROOT, JAVA, "com/origin/client/client/waypoints/WaypointScope.java"),
        os.path.join(HERE, "IngameGuardTests.java")]
    # --release 8: the same helper files compile into the Java 8 modules (1.16.5, 1.8.9).
    if subprocess.run(["javac", "--release", "8", "-d", out] + srcs).returncode:
        sys.exit("compile failed (pure helpers)")
    ok &= subprocess.run(["java", "-cp", out, "IngameGuardTests"]).returncode == 0

    gson = find_gson()
    print("Gson for ModsConfigTests:", gson)
    mc_out = tempfile.mkdtemp(prefix="modsconfig-")
    stubs = glob.glob(os.path.join(HERE, "stubs", "**", "*.java"), recursive=True)
    srcs = stubs + [os.path.join(ROOT, JAVA, "com/origin/client/client/mods/ModsConfig.java"),
                    os.path.join(ROOT, JAVA, "com/origin/client/client/ConfigFiles.java"),
                    os.path.join(HERE, "modsconfig/com/origin/client/client/mods/ModsConfigTests.java")]
    if subprocess.run(["javac", "-cp", gson, "-d", mc_out] + srcs).returncode:
        sys.exit("compile failed (ModsConfig)")
    seed = os.path.join(mc_out, "assets", "originclient")
    os.makedirs(seed)
    with open(os.path.join(seed, "default-mods-config.json"), "w") as f:
        f.write('{"mods":{"zoom":{"enabled":true,"fov":30}},"hud":{}}')
    for scenario in ("corrupt", "wrongshape", "profile"):
        cfg = tempfile.mkdtemp(prefix=f"cfg-{scenario}-")
        print(f"-- ModsConfig: {scenario}")
        rc = subprocess.run(["java", f"-Dtest.configDir={cfg}", "-cp", mc_out + os.pathsep + gson,
                             "com.origin.client.client.mods.ModsConfigTests", scenario]).returncode
        ok &= rc == 0
        shutil.rmtree(cfg, ignore_errors=True)
    print("ALL GUARD TESTS PASS" if ok else "GUARD TESTS FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
