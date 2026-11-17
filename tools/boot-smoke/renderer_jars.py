#!/usr/bin/env python3
"""The jars a 1.21.1 Vulkan boot loads beside Origin's own, put in one folder for the module's
-PrendererJars=<dir> (boot-smoke.yml / dev-shots.yml, input `renderer: vulkan`).

Same set the launcher puts in instances/1.21.1/renderers/vulkan (Core/Renderers/VulkanRendererFiles.cs):
  - VulkanMod at the launcher's pin. URL and SHA-512 are read from ManagedPins.Data.cs (VulkanModPin), so CI
    and players can never run different files; a hash mismatch fails the step.
  - Origin's bundled Vulkan jars: vulkairis-origin-*.jar and flywheel-vulkan-*.jar (VulkanMode.BundledPatterns),
    built from src/mods/vendor/<name>/ (each has its own gradlew) unless a build/libs jar is already there.
Either bundled jar missing fails the step: without them the boot can only crash (Iris needs Sodium, which a
Vulkan launch disables), and a red row for that reason would say nothing about Vulkan.

Usage: python3 tools/boot-smoke/renderer_jars.py vulkan --out <dir>
"""
import argparse
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PINS = os.path.join(ROOT, "src", "OriginLauncher.App", "Core", "Loaders", "ManagedPins.Data.cs")
VENDOR = os.path.join(ROOT, "src", "mods", "vendor")
BUNDLED_PATTERNS = ["vulkairis-origin-*.jar", "flywheel-vulkan-*.jar"]  # = VulkanMode.BundledPatterns


def vulkanmod_pin():
    text = open(PINS, encoding="utf-8").read()
    m = re.search(r'VulkanModPin\s*=\s*new\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([0-9a-f]{128})"\)', text)
    if not m:
        sys.exit("renderer_jars: VulkanModPin not found in " + PINS)
    return m.group(2), m.group(3), m.group(4)


def download(url, dest, sha512):
    for attempt in range(1, 5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "origin-ci-boot-smoke"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            got = hashlib.sha512(data).hexdigest()
            if got != sha512:
                sys.exit(f"renderer_jars: {os.path.basename(dest)} SHA-512 {got} != pinned {sha512}")
            with open(dest + ".download", "wb") as f:
                f.write(data)
            os.replace(dest + ".download", dest)
            return
        except OSError as e:
            print(f"renderer_jars: download attempt {attempt} failed: {e}", file=sys.stderr)
            time.sleep(attempt * 10)
    sys.exit("renderer_jars: could not download " + url)


def built_jar(pattern):
    hits = [p for p in glob.glob(os.path.join(VENDOR, "*", "build", "libs", pattern))
            if not p.endswith(("-sources.jar", "-dev.jar"))]
    return sorted(hits, key=os.path.basename)[-1] if hits else None


def build_vendor_modules():
    if not os.path.isdir(VENDOR):
        return
    for d in sorted(os.listdir(VENDOR)):
        mod = os.path.join(VENDOR, d)
        if not os.path.isfile(os.path.join(mod, "gradlew")):
            continue
        print(f"renderer_jars: building src/mods/vendor/{d}", flush=True)
        subprocess.run(["sh", "./gradlew", "build", "-x", "test", "--no-daemon",
                        "-Dorg.gradle.internal.repository.max.retries=6"], cwd=mod, check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("renderer", choices=["vulkan"])
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    url, name, sha = vulkanmod_pin()
    download(url, os.path.join(args.out, name), sha)
    print(f"renderer_jars: {name} (SHA-512 matches the launcher pin)")

    if not all(built_jar(p) for p in BUNDLED_PATTERNS):
        build_vendor_modules()
    missing = [p for p in BUNDLED_PATTERNS if not built_jar(p)]
    if missing:
        sys.exit("renderer_jars: no " + ", ".join(missing) + " under src/mods/vendor/*/build/libs: "
                 "the Vulkan boot needs Origin's Vulkan jars (the launcher hides the switch without them too)")
    for p in BUNDLED_PATTERNS:
        jar = built_jar(p)
        shutil.copy2(jar, os.path.join(args.out, os.path.basename(jar)))
        print(f"renderer_jars: {os.path.basename(jar)}")


if __name__ == "__main__":
    main()
