"""Test the pure-logic parts of the 2026-11-04 QoL fixes (shared util/KeyTaps, util/WindowTitles).

    python tools/qol-tests/run.py

Both are plain Java in src/mods/shared, so they compile here with any JDK, next to
QolTests.java. CI: build-check.
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
UTIL = os.path.join(ROOT, "src/mods/shared/src/client/java/com/origin/client/client/util")


def main():
    out = tempfile.mkdtemp(prefix="qol-tests-")
    srcs = [os.path.join(UTIL, "KeyTaps.java"), os.path.join(UTIL, "WindowTitles.java"),
            os.path.join(HERE, "QolTests.java")]
    if subprocess.run(["javac", "-d", out] + srcs).returncode:
        sys.exit("compile failed")
    sys.exit(1 if subprocess.run(["java", "-cp", out, "QolTests"]).returncode else 0)


if __name__ == "__main__":
    main()
