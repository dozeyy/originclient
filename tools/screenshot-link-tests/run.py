"""Test the chat screenshot buttons' guard (ScreenshotLinks) in every live module.

    python tools/screenshot-link-tests/run.py

ScreenshotLinks is pure Java (no Minecraft), Java 8 level, so it compiles and runs
here with any JDK 8+. Every live module must carry a copy byte-identical to the
shared one (the pre-1.20 modules and 1.8.9 are .no-shared-sync, so sync.py --check
doesn't see theirs), and every module's ScreenshotChat must act only through it
(no click handler reading a path or action out of the chat text, which a server
controls). Bug sweep 2, F-1. CI: build-check.
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "com/origin/client/client/mods"
SHARED = os.path.join(ROOT, "src/mods/shared/src/client/java", PKG, "ScreenshotLinks.java")


def main():
    want = open(SHARED, "rb").read()
    live = [m["module"] for m in json.load(open(os.path.join(ROOT, "src/mods/live-modules.json")))["modules"]]
    bad = []
    for m in live:
        links = glob.glob(os.path.join(ROOT, "src/mods/versions", m, "src/*/java", PKG, "ScreenshotLinks.java"))
        chat = glob.glob(os.path.join(ROOT, "src/mods/versions", m, "src/*/java", PKG, "ScreenshotChat.java"))
        if not links:
            bad.append(f"{m}: no ScreenshotLinks.java")
        elif open(links[0], "rb").read() != want:
            bad.append(f"{m}: ScreenshotLinks.java differs from shared")
        if not chat:
            bad.append(f"{m}: no ScreenshotChat.java")
            continue
        src = open(chat[0], encoding="utf-8").read()
        if "ScreenshotLinks.resolve(" not in src or "ScreenshotLinks.register(" not in src:
            bad.append(f"{m}: ScreenshotChat does not go through ScreenshotLinks")
        # The old trust-the-insertion handler: matching the prefix, then reading the click event.
        if re.search(r"ACTION_PREFIX|getClickEvent\(\)\.getValue\(\)|getChatClickEvent\(\)\.getValue\(\)|pathOf\(", src):
            bad.append(f"{m}: ScreenshotChat still reads its target from the chat text")
    print(f"ScreenshotLinks in {len(live)} live modules: {'all wired' if not bad else 'PROBLEMS'}")
    for b in bad:
        print("  " + b)
    out = tempfile.mkdtemp(prefix="shotlinks-")
    r = subprocess.run(["javac", "-d", out, SHARED, os.path.join(HERE, "ScreenshotLinksTests.java")])
    if r.returncode:
        sys.exit("compile failed")
    rc = subprocess.run(["java", "-cp", out, "ScreenshotLinksTests"]).returncode
    sys.exit(1 if bad or rc else 0)


if __name__ == "__main__":
    main()
