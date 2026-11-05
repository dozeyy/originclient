"""Test Borderless Fullscreen's window maths (BorderlessFit) in every module that ships it.

    python tools/borderless-tests/run.py

BorderlessFit is pure Java 8 (no Minecraft, no GLFW), so it compiles and runs here
with any JDK 8+. Every copy (shared/ and each module's own) must be byte-identical
to the shared one -- the pre-1.20 modules are .no-shared-sync, so sync.py --check
doesn't see theirs -- and every live GLFW/SDL module (all but 1.8.9, whose LWJGL 2
borderless works differently) must have one. Also WindowMemory (the window's size and
maximised state between launches), in every live module. CI: build-check.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
REL = "com/origin/client/client/BorderlessFit.java"
MEM_REL = "com/origin/client/client/WindowMemory.java"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", REL)
NOT_SHIPPED_ON = {"1.8.9"}


def main():
    want = open(SHARED, "rb").read()
    copies = sorted(glob.glob(os.path.join(ROOT, "src/mods/versions/*/src/*/java", REL)))
    bad = [c for c in copies if open(c, "rb").read() != want]
    print(f"BorderlessFit copies: {len(copies)} (all identical to shared: {'yes' if not bad else 'NO'})")
    for c in bad:
        print("  differs:", os.path.relpath(c, ROOT))
    live = [m["module"] for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]]
    have = {os.path.relpath(c, ROOT).replace(os.sep, "/").split("/")[3] for c in copies}
    missing = [m for m in live if m not in have and m not in NOT_SHIPPED_ON]
    for m in missing:
        print("  missing in live module:", m)
    out = tempfile.mkdtemp(prefix="borderless-")
    # --release 8: the 1.16.5 module compiles this file as Java 8.
    r = subprocess.run(["javac", "--release", "8", "-d", out, SHARED, os.path.join(HERE, "BorderlessFitTests.java")])
    if r.returncode:
        sys.exit("compile failed")
    rc = subprocess.run(["java", "-cp", out, "BorderlessFitTests"]).returncode

    # WindowMemory (the window's size + maximised state between launches, JG-10): every live
    # module carries the shared copy, 1.8.9 included (its LWJGL 2 glue remembers the size).
    mem = os.path.join(ROOT, "src/mods/shared/src/client/java", MEM_REL)
    mem_want = open(mem, "rb").read()
    for m in live:
        copies = glob.glob(os.path.join(ROOT, "src/mods/versions", m, "src/*/java", MEM_REL))
        if len(copies) != 1 or open(copies[0], "rb").read() != mem_want:
            print(f"  WindowMemory missing or differs from shared in live module: {m}")
            missing.append(m)
    r = subprocess.run(["javac", "--release", "8", "-d", out, mem, os.path.join(HERE, "WindowMemoryTests.java")])
    if r.returncode:
        sys.exit("compile failed (WindowMemory)")
    rc |= subprocess.run(["java", "-cp", out, "WindowMemoryTests"]).returncode
    sys.exit(1 if bad or missing or rc else 0)


if __name__ == "__main__":
    main()
