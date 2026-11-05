"""Test Origin's one cross-version key format (util/KeyCodes).

    python tools/keycodes-tests/run.py

originclient-mods.json travels between every version through Settings Sync, so its
keybind values must mean the same key everywhere: GLFW key codes, mouse button b as
-100 + b, -1 unbound. KeyCodes.java is pure Java, so it compiles here with any JDK.
Checks the shared (GLFW) copy, 26.3's SDL3 copy (every mapped key round-trips), and
that the pre-1.20 modules (outside shared sync) carry the shared copy byte-identical.
1.8.9's LWJGL 2 copy is checked against stand-ins for LWJGL / Minecraft (legacy189/), as
Windows with a US, AZERTY, QWERTZ and UK layout and as Linux: on Windows LWJGL 2 numbers a
key by its character, the file by its place (bug run 10, INPUT-1).
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
LEGACY = os.path.join(ROOT, "src/mods/versions/1.8.9/src/main/java", REL)
PRE_120 = ["1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.19.4"]


def run(src, arg):
    out = tempfile.mkdtemp(prefix="keycodes-")
    if subprocess.run(["javac", "-d", out, src, os.path.join(HERE, "KeyCodesTests.java")]).returncode:
        sys.exit("compile failed: " + src)
    return subprocess.run(["java", "-cp", out, "KeyCodesTests", arg]).returncode


def run_legacy():
    out = tempfile.mkdtemp(prefix="keycodes-189-")
    stubs = sorted(glob.glob(os.path.join(HERE, "legacy189", "**", "*.java"), recursive=True))
    if subprocess.run(["javac", "-d", out, LEGACY] + stubs).returncode:
        sys.exit("compile failed: " + LEGACY)
    bad = 0
    for layout in ["us", "azerty", "qwertz", "uk"]:
        bad += subprocess.run(["java", "-Dos.name=Windows 10", "-Dkeycodes.layout=" + layout,
                               "-cp", out, "Legacy189Tests", layout]).returncode
    bad += subprocess.run(["java", "-Dos.name=Linux", "-cp", out, "Legacy189Tests", "linux"]).returncode
    return bad


def main():
    bad = 0
    for m in PRE_120:
        copy = glob.glob(os.path.join(ROOT, "src/mods/versions", m, "src/*/java", REL))
        if not copy or not filecmp.cmp(copy[0], SHARED, shallow=False):
            print("FAIL", m, "does not carry the shared util/KeyCodes.java")
            bad += 1
    bad += run(SHARED, "glfw")
    bad += run(SDL, "sdl")
    bad += run_legacy()
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
