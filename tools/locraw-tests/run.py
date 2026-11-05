"""Test util/HypixelLocraw (Quickplay's Play Again knows the Hypixel game you're in, JG-11).

    python tools/locraw-tests/run.py

HypixelLocraw is pure Java 8. Every live module must carry it byte-identical to shared/ (the
pre-1.20 modules and 1.8.9 are .no-shared-sync, so sync.py --check doesn't see theirs); the
shared copy compiles with --release 8 (1.16.5 and 1.8.9 build it as Java 8) and runs
LocrawTests. CI: build-check.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
REL = "com/origin/client/client/util/HypixelLocraw.java"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", REL)


def main():
    want = open(SHARED, "rb").read()
    bad = 0
    for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]:
        copies = glob.glob(os.path.join(ROOT, "src/mods/versions", m["module"], "src/*/java", REL))
        if len(copies) != 1 or open(copies[0], "rb").read() != want:
            print(f"FAIL {m['module']}: util/HypixelLocraw.java missing or differs from shared")
            bad += 1
    out = tempfile.mkdtemp(prefix="locraw-")
    if subprocess.run(["javac", "--release", "8", "-d", out, SHARED, os.path.join(HERE, "LocrawTests.java")]).returncode:
        sys.exit("compile failed")
    bad += subprocess.run(["java", "-cp", out, "LocrawTests"]).returncode
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
