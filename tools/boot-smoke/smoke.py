#!/usr/bin/env python3
"""Boot-smoke one Origin Client version: launch runClient, watch the log, and
fail if any mixin didn't apply.

Compiling a module only proves the mixin TARGETS exist. @Inject descriptor and
@Shadow mismatches surface only when the mixin is APPLIED at class load, which
needs a real boot. This script does that boot and turns "zero mixin-apply
failures" into a pass/fail gate -- the same check Will does by hand for every
version, automated so CI (or a local run) catches a regression in shared/ code
before a player does.

It works anywhere runClient works: locally on a machine with a GPU, or in CI
under Xvfb + a software GL (see .github/workflows/boot-smoke.yml). It does NOT
render anything itself -- it only reads Minecraft's own log.

Usage:
    python tools/boot-smoke/smoke.py <module-dir> [--timeout SECONDS] [--world NAME]

Exit code 0 = booted far enough with zero mixin-apply failures. 1 = a failure
signature appeared, or it never reached a running state before the timeout.
"""
import argparse
import os
import re
import signal
import subprocess
import sys
import time

# Lines that mean a mixin (ours or a bundled mod's) failed to apply, the mod set
# was rejected, or the game threw during startup. Any one of these fails the run.
FAIL_PATTERNS = [
    re.compile(r"Mixin apply(ing)? .*failed", re.I),
    re.compile(r"Critical injection failure", re.I),
    re.compile(r"was not found in|Could not find any target|Injection point .*could not", re.I),
    re.compile(r"Incompatible mods found", re.I),
    re.compile(r"A mod crashed on startup", re.I),
    re.compile(r"Exception in thread \"main\""),
    re.compile(r"Failed to start Minecraft", re.I),
]
# Lines that mean the client got far enough to prove the boot path (main menu or
# in-world). Reaching any = the render pipeline and mixins loaded successfully.
READY_PATTERNS = [
    re.compile(r"main menu to in-game", re.I),      # ModernFix: reached a world
    re.compile(r"Backend library: (LWJGL|OpenGL)", re.I),  # window + GL up
    re.compile(r"Sodium Renderer .* Loaded", re.I),
    re.compile(r"Starting JEI took", re.I),          # JEI runtime up (1.21.1)
    re.compile(r"Stopping worker threads", re.I),
]
# Known red herrings that look scary but prove nothing about our code
# (see MEMORY.md -> runclient-voxy-blocker, and the Iris shader-var warnings).
IGNORE = re.compile(r"twelvemonkeys|ImageInputStreamSpi|BIOME_PALE_GARDEN|endFlashIntensity", re.I)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module", help="path to the version module (e.g. src/mods/versions/1.21.1)")
    ap.add_argument("--timeout", type=int, default=300, help="hard cap in seconds")
    ap.add_argument("--world", default="", help="quickplay singleplayer world name (blank = boot to menu)")
    args = ap.parse_args()

    module = os.path.abspath(args.module)
    if not os.path.isdir(module):
        print(f"boot-smoke: module dir not found: {module}", file=sys.stderr)
        return 2

    gradlew = os.path.join(module, "gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [gradlew, "runClient", "--no-daemon"]
    if args.world:
        cmd.append(f"-Pquickplay={args.world}")

    print(f"boot-smoke: {os.path.basename(module)} -> {' '.join(cmd)}", flush=True)
    # New process group so we can kill the whole gradle+java tree on the way out.
    popen_kw = dict(cwd=module, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    bufsize=1, universal_newlines=True)
    if os.name == "nt":
        popen_kw["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        popen_kw["preexec_fn"] = os.setsid
    proc = subprocess.Popen(cmd, **popen_kw)

    start = time.monotonic()
    ready = False
    verdict = None  # (ok, reason)
    try:
        for line in proc.stdout:
            sys.stdout.write(line)
            if time.monotonic() - start > args.timeout:
                verdict = (False, f"timeout after {args.timeout}s without a ready marker")
                break
            if IGNORE.search(line):
                continue
            if any(p.search(line) for p in FAIL_PATTERNS):
                verdict = (False, f"failure signature: {line.strip()[:200]}")
                break
            if not ready and any(p.search(line) for p in READY_PATTERNS):
                ready = True
                verdict = (True, f"ready: {line.strip()[:120]}")
                break
        else:
            # stream ended (process exited) with no verdict
            if verdict is None:
                verdict = (ready, "process exited")
    finally:
        _kill_tree(proc)

    ok, reason = verdict if verdict else (False, "no output")
    print(f"\nboot-smoke: {os.path.basename(module)}: {'PASS' if ok else 'FAIL'} -- {reason}", flush=True)
    return 0 if ok else 1


def _kill_tree(proc):
    if proc.poll() is not None:
        return
    try:
        if os.name == "nt":
            proc.send_signal(signal.CTRL_BREAK_EVENT)
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


if __name__ == "__main__":
    sys.exit(main())
