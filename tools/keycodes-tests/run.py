"""Test Origin's one cross-version key format (util/KeyCodes).

    python tools/keycodes-tests/run.py

originclient-mods.json travels between every version through Settings Sync, so its
keybind values must mean the same key everywhere: GLFW key codes, mouse button b as
-100 + b, -1 unbound. KeyCodes.java is pure Java, so it compiles here with any JDK.
Checks the shared (GLFW) copy, 26.3's SDL3 copy (every mapped key round-trips), and
that the pre-1.20 modules (outside shared sync) carry the shared copy byte-identical.
CI: build-check.
"""
import filecmp
import glob
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
REL = "com/origin/client/client/util/KeyCodes.java"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", REL)
SDL = os.path.join(ROOT, "src/mods/versions/26.3/src/client/java", REL)
PRE_120 = ["1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.19.4"]


def run(src, arg):
    out = tempfile.mkdtemp(prefix="keycodes-")
    if subprocess.run(["javac", "-d", out, src, os.path.join(HERE, "KeyCodesTests.java")]).returncode:
        sys.exit("compile failed: " + src)
    return subprocess.run(["java", "-cp", out, "KeyCodesTests", arg]).returncode


def main():
    bad = 0
    for m in PRE_120:
        copy = glob.glob(os.path.join(ROOT, "src/mods/versions", m, "src/*/java", REL))
        if not copy or not filecmp.cmp(copy[0], SHARED, shallow=False):
            print("FAIL", m, "does not carry the shared util/KeyCodes.java")
            bad += 1
    bad += run(SHARED, "glfw")
    bad += run(SDL, "sdl")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
