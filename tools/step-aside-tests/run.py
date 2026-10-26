"""Test Origin's step-aside logic (StepAside: a player's own mod replaces an Origin mod).

    python tools/step-aside-tests/run.py

StepAside.java is pure Java (no Minecraft, no Fabric, no Gson), so it compiles and
runs here with any JDK 17+. The tests run against the SHARED copy; sync.py --check
already proves every synced module carries it byte-identical. Also lists which
modules are wired (their Mods.java reads StepAside), for the porting units.
CI: build-check.
"""
import glob
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
REL = "com/origin/client/client/mods/StepAside.java"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", REL)


def main():
    wired = []
    for mods in sorted(glob.glob(os.path.join(ROOT, "src/mods/versions/*/src/client/java/com/origin/client/client/mods/Mods.java"))):
        if "StepAside." in open(mods, encoding="utf-8").read():
            wired.append(os.path.relpath(mods, ROOT).replace(os.sep, "/").split("/")[3])
    print("modules wired to StepAside:", ", ".join(wired) or "none")
    if "1.21.1" not in wired:
        sys.exit("1.21.1 (the design truth) is not wired to StepAside")
    out = tempfile.mkdtemp(prefix="stepaside-")
    r = subprocess.run(["javac", "-d", out, SHARED, os.path.join(HERE, "StepAsideTests.java")])
    if r.returncode:
        sys.exit("compile failed")
    sys.exit(subprocess.run(["java", "-cp", out, "StepAsideTests"]).returncode)


if __name__ == "__main__":
    main()
