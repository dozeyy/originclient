#!/usr/bin/env python3
"""Gate for the Linux launcher's boot test (OriginLauncher --boot-test, linux-launcher.yml).

Reads the game console the boot test saved and applies boot-smoke's own rules
(tools/boot-smoke/smoke.py classify(): every mixin/injection/crash failure signature, its
ignore list, the "[origin-smoke] title screen reached" ready line), so a launch through the
Linux launcher is judged exactly like a boot-smoke run of the same version. Also prints
tools/ci/perf_log.py's Sodium/Iris summary for Fabric versions (information only: how the
launcher installs the shader stack differs per version).

Usage: check_boot.py <game log> <minecraft version>     exit 0 = pass, 1 = fail
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "boot-smoke"))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "ci"))
import smoke      # noqa: E402  the boot-smoke rules
import perf_log   # noqa: E402  Sodium/Iris loaded + Origin mixins applied to them


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    path, version = sys.argv[1:3]
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()
    fails = [l.strip()[:240] for l in lines if smoke.classify(l) == "fail"]
    ready = any(smoke.classify(l) == "ready" for l in lines)
    forge = any("Forge Mod Loader version" in l for l in lines)
    fabric = any("Loading Minecraft" in l and "Fabric Loader" in l for l in lines)
    print(f"check_boot {version}: {len(lines)} log lines; loader: "
          f"{'Forge' if forge else 'Fabric' if fabric else 'NOT SEEN'}; title reached: {ready}; failure lines: {len(fails)}")
    if fabric:
        _, perf = perf_log.gate("\n".join(lines), module=version)
        for p in perf:
            print("  " + p.replace("::error", "note").replace("::warning", "note"))
    ok = True
    if not ready:
        print(f"::error title=boot {version}::the title screen was never reached (no '[origin-smoke] title screen reached')")
        ok = False
    if not (forge or fabric):
        print(f"::error title=boot {version}::neither Fabric nor Forge started: the launcher did not install the loader")
        ok = False
    if fails:
        print(f"::error title=boot {version}::{len(fails)} failure line(s); first: {fails[0]}")
        for l in fails[:15]:
            print("  " + l)
        ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
