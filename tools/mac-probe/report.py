#!/usr/bin/env python3
"""Mac boot probe: turn one version's logs into the report lines. It only reads logs and decides nothing
about blame: it shows the furthest milestone, the mixin failure count and the first error candidates,
and whoever reads the run compares them with the GL control (glprobe lines) to split runner from Mac.

    report.py <label> <folder> [<folder> ...]     every *.log / *.txt under the folders is read
"""
import os
import re
import sys

# In boot order. The furthest one hit is "how far it got".
MILESTONES = [
    ("game JVM started", r"Loading \d+ mods|Forge Mod Loader version|Loading tweak class"),
    ("mixin subsystem up", r"SpongePowered MIXIN|Compatibility level set to|Initializing Mixins|Mixin subsystem"),
    ("mods accepted by the loader", r"Mod list|Loading \d+ mods|Forge Mod Loader has identified|Searching .* for mods"),
    ("LWJGL / window code reached", r"Backend library: LWJGL|LWJGL Version|Setting user:"),
    ("GL context reported", r"\[OptiFine\] OpenGL|OpenGL: |GL_RENDERER|OpenGL Version|Created: \d+x\d+"),
    ("first resource reload", r"Reloading ResourceManager"),
    ("Origin title screen reached", r"\[origin-smoke\] title screen reached"),
    ("in-world step passed", r"\[origin-smoke\] in-world: PASS|BENCH.*(done|finished|wrote)"),
]
MIXIN_FAIL = re.compile(r"Mixin apply(ing)? .*failed|Critical injection failure|InvalidInjection|MixinApplyError|MixinTransformerError", re.I)
ERROR = re.compile(r"\bFATAL\b|/ERROR\]|\[ERROR\]|Exception\b|\bError:|UnsatisfiedLinkError|NoClassDefFoundError|SIGSEGV|SIGBUS|A fatal error|"
                   r"GLFW error|No context|failed to|OpenGL.*(not|unsupported|requires)|Incompatible mods", re.I)
# Prints that look bad and prove nothing here (same list spirit as smoke.py's IGNORE).
NOISE = re.compile(r"twelvemonkeys|ImageInputStreamSpi|BIOME_PALE_GARDEN|endFlashIntensity|Gradle|deprecat|ErrorProne|errorMessage|"
                   r"ErrorHandler|onError|\.java:\d+\)$|^\s+at ", re.I)


def logs(folders):
    for folder in folders:
        for dp, _, files in os.walk(folder):
            for n in sorted(files):
                if n.endswith((".log", ".txt")) and "probe" not in n:
                    yield os.path.join(dp, n)


def main():
    label, folders = sys.argv[1], sys.argv[2:]
    lines = [f"### {label}"]
    paths = list(logs(folders))
    # A set: the same line is in the boot's stdout and in latest.log.
    hit, fails, first = {}, set(), []
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f, 1):
                for name, pat in MILESTONES:
                    if name not in hit and re.search(pat, line):
                        hit[name] = f"{os.path.basename(path)}:{i}"
                if MIXIN_FAIL.search(line):
                    fails.add(line.strip())
                if len(first) < 25 and ERROR.search(line) and not NOISE.search(line):
                    first.append(f"{os.path.basename(path)}:{i}: {line.strip()[:220]}")
    furthest = next((n for n, _ in reversed(MILESTONES) if n in hit), "none (the game never logged a milestone)")
    lines += [f"- logs read: {', '.join(os.path.basename(p) for p in paths) or 'NONE'}",
              f"- furthest point: **{furthest}**",
              f"- milestones: " + "; ".join(f"{n} ({hit[n]})" for n, _ in MILESTONES if n in hit),
              f"- mixin apply failure lines: **{len(fails)}**",
              "- first error candidates (read in order, the first REAL one decides):"]
    lines += [f"  - `{x}`" for x in first] or ["  - none"]
    text = "\n".join(lines) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
