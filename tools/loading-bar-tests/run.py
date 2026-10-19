"""Test the loading screen's bar fill (LoadBar) in every module that ships it.

    python tools/loading-bar-tests/run.py

LoadBar is pure Java (no Minecraft), so it compiles and runs here with any JDK
8+. Every copy (shared/ and each module's own) must be byte-identical to the
shared one -- the pre-1.20 modules are .no-shared-sync, so sync.py --check
doesn't see theirs -- and the tests run once against it. CI: build-check.
"""
import glob
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
REL = "com/origin/client/client/render/LoadBar.java"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", REL)


def main():
    want = open(SHARED, "rb").read()
    copies = sorted(glob.glob(os.path.join(ROOT, "src/mods/versions/*/src/*/java", REL)))
    bad = [c for c in copies if open(c, "rb").read() != want]
    print(f"LoadBar copies: {len(copies)} (all identical to shared: {'yes' if not bad else 'NO'})")
    for c in bad:
        print("  differs:", os.path.relpath(c, ROOT))
    # Every live module draws its world-loading bar through it (and every Fabric
    # one its startup bar too), 1.8.9 included.
    import json
    live = [m["module"] for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]]
    have = {os.path.relpath(c, ROOT).replace(os.sep, "/").split("/")[3] for c in copies}
    missing = [m for m in live if m not in have]
    for m in missing:
        print("  missing in live module:", m)
    bad = bad + missing
    out = tempfile.mkdtemp(prefix="loadbar-")
    r = subprocess.run(["javac", "-d", out, SHARED, os.path.join(HERE, "LoadBarTests.java")])
    if r.returncode:
        sys.exit("compile failed")
    rc = subprocess.run(["java", "-cp", out, "LoadBarTests"]).returncode
    sys.exit(1 if bad or rc else 0)


if __name__ == "__main__":
    main()
