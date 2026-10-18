"""Compile + run the Resource Pack Hub's Minecraft-free unit tests.

    python tools/packhub-tests/run.py [--gson path/to/gson.jar]

Compiles the pure classes from the 1.21.1 module's packs/ package (everything
that imports no net.minecraft / com.mojang / fabric class) with the tests, using
only a JDK (17+) and a Gson jar (found in the Gradle cache when not given).
"""
import argparse
import glob
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = os.path.join(ROOT, "src/mods/versions/1.21.1/src/client/java/com/origin/client/client/packs")
# Pure classes outside packs/ that ride along (the FPS module's frame ring).
EXTRA = [os.path.join(ROOT, "src/mods/versions/1.21.1/src/client/java/com/origin/client/client/hud/FrameStats.java")]
HERE = os.path.dirname(os.path.abspath(__file__))
GAME_IMPORTS = ("import net.minecraft", "import com.mojang", "import net.fabricmc", "import org.lwjgl")


def find_gson():
    pats = [os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar"),
            "/opt/*/lib/gson-*.jar"]
    for p in pats:
        hits = sorted(glob.glob(p))
        if hits:
            return hits[-1]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gson")
    a = ap.parse_args()
    gson = a.gson or find_gson()
    if not gson:
        sys.exit("No gson jar found; pass --gson")
    pure = []
    for f in sorted(glob.glob(os.path.join(PKG, "*.java"))):
        src = open(f, encoding="utf-8").read()
        if not any(i in src for i in GAME_IMPORTS) and "OriginClient" not in src:
            pure.append(f)
    pure += EXTRA
    tests = glob.glob(os.path.join(HERE, "**/*.java"), recursive=True)
    out = tempfile.mkdtemp(prefix="packhub-")
    cmd = ["javac", "-Xlint:all", "-Xlint:-serial", "-d", out, "-cp", gson] + pure + tests
    r = subprocess.run(cmd)
    if r.returncode:
        sys.exit("compile failed")
    print("compiled", len(pure), "hub classes:", ", ".join(os.path.basename(p)[:-5] for p in pure))
    r = subprocess.run(["java", "-cp", out + os.pathsep + gson, "PackHubTests"])
    sys.exit(r.returncode)


if __name__ == "__main__":
    main()
