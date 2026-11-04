"""Test the pure-logic parts of the 2026-11-04 QoL fixes (util/KeyTaps, util/WindowTitles,
util/OnceSteps,
util/HotkeyChord).

    python tools/qol-tests/run.py

The shared copies (src/mods/shared) compile here with any JDK, next to QolTests.java.
The modules outside shared sync (pre-1.20 and 1.8.9, `.no-shared-sync`) carry their own
Java 8 copies; each is compiled with --release 8 and runs the same tests, so a fork can't
drift from the shared behaviour unnoticed. CI: build-check.
"""
import glob
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
SHARED_UTIL = os.path.join(ROOT, "src/mods/shared/src/client/java/com/origin/client/client/util")


def run(util_dir, label, release=None):
    out = tempfile.mkdtemp(prefix="qol-tests-")
    srcs = [os.path.join(util_dir, n) for n in ("KeyTaps.java", "WindowTitles.java", "OnceSteps.java", "HotkeyChord.java")]
    srcs.append(os.path.join(HERE, "QolTests.java"))
    cmd = ["javac", "-d", out] + (["--release", release] if release else []) + srcs
    if subprocess.run(cmd).returncode:
        print(f"qol-tests: {label}: compile failed")
        return False
    print(f"qol-tests: {label}")
    return subprocess.run(["java", "-cp", out, "QolTests"]).returncode == 0


def main():
    ok = run(SHARED_UTIL, "shared")
    for marker in sorted(glob.glob(os.path.join(ROOT, "src/mods/versions/*/.no-shared-sync"))):
        module = os.path.dirname(marker)
        for src in ("src/client", "src/main"):
            util = os.path.join(module, src, "java/com/origin/client/client/util")
            if os.path.isfile(os.path.join(util, "WindowTitles.java")):
                ok = run(util, os.path.basename(module), "8") and ok
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
