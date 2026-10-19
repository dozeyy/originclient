#!/usr/bin/env python3
"""Boot-smoke one Origin Client version: launch runClient, watch the log, and
fail if any mixin didn't apply or the title screen was never reached.

Compiling a module only proves the mixin TARGETS exist. @Inject descriptor and
@Shadow mismatches surface only when the mixin is APPLIED at class load, which
needs a real boot. This script does that boot and turns "reached the title
screen with zero mixin-apply failures" into a pass/fail gate -- the same check
Will does by hand for every version, automated so CI (or a local run) catches a
regression in shared/ code before a player does.

READY = the line every live module's OriginClientMod prints once, the first
tick the title screen is up with no loading overlay, when ORIGIN_SMOKE is set:
    [origin-smoke] title screen reached
(It used to be "Backend library: LWJGL", i.e. only the GL window being up --
a boot that wedged on the loading screen still passed.)

It works anywhere runClient works: locally on a machine with a GPU, or in CI
under Xvfb + a software GL (see .github/workflows/boot-smoke.yml). It does NOT
render anything itself -- it only reads Minecraft's own log.

Network flakes: a run that dies on a dependency/asset download (Maven 429,
timeouts) before the game is up is retried (bounded, with backoff) instead of
being reported as an Origin failure or, worse, a pass.

Usage:
    python tools/boot-smoke/smoke.py <module-dir> [--timeout SECONDS] [--world NAME] [--attempts N]

Exit code 0 = reached the title screen with zero mixin-apply failures.
1 = a failure signature appeared, or it never reached the title before the timeout.
"""
import argparse
import os
import re
import signal
import subprocess
import sys
import threading
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
# The ONE ready marker: Origin's own dev-only title-screen line (see module docstring).
READY = re.compile(r"\[origin-smoke\] title screen reached")
# Known red herrings that look scary but prove nothing about our code
# (see MEMORY.md -> runclient-voxy-blocker, and the Iris shader-var warnings).
IGNORE = re.compile(r"twelvemonkeys|ImageInputStreamSpi|BIOME_PALE_GARDEN|endFlashIntensity", re.I)
# Gradle/HTTP download failures (Maven Central 429, Mojang asset CDN timeouts). Only
# these make a failed run retryable; deliberately NOT "Could not resolve" alone,
# which is also what a wrong version pin prints.
NETWORK = re.compile(
    r"\b429\b|Too Many Requests|Could not (GET|HEAD|download)|Connection reset|Connect(ion)? timed out"
    r"|Read timed out|SocketTimeoutException|UnknownHostException|SSLHandshakeException"
    r"|Remote host terminated|Received status code 5\d\d|Premature end of Content-Length",
    re.I)
# Once one of these shows up the game JVM is running: a later failure is the game's,
# never a download flake, so it is not retried.
GAME_STARTED = re.compile(r"Loading Minecraft|Backend library|Setting user:|\[origin-smoke\]|Forge Mod Loader version", re.I)


def classify(line):
    """'ignore' | 'fail' | 'ready' | None for one log line."""
    if IGNORE.search(line):
        return "ignore"
    if any(p.search(line) for p in FAIL_PATTERNS):
        return "fail"
    if READY.search(line):
        return "ready"
    return None


def run_once(module, cmd, timeout):
    """One runClient. Returns (ok, reason, retryable)."""
    env = dict(os.environ, ORIGIN_SMOKE="1")
    popen_kw = dict(cwd=module, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    bufsize=1, universal_newlines=True, env=env, errors="replace")
    # New process group so we can kill the whole gradle+java tree on the way out.
    if os.name == "nt":
        popen_kw["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        popen_kw["preexec_fn"] = os.setsid
    proc = subprocess.Popen(cmd, **popen_kw)
    # A real deadline: a wedged game that prints nothing must still be killed (the
    # old per-line check only fired when a line arrived).
    timed_out = threading.Event()

    def _expire():
        timed_out.set()
        _kill_tree(proc)
    timer = threading.Timer(timeout, _expire)
    timer.start()

    start = time.monotonic()
    verdict = None
    network = started = False
    try:
        for line in proc.stdout:
            sys.stdout.write(line)
            if not started and GAME_STARTED.search(line):
                started = True
            if not started and NETWORK.search(line):
                network = True
            kind = classify(line)
            if kind == "fail":
                verdict = (False, f"failure signature: {line.strip()[:200]}", False)
                break
            if kind == "ready":
                verdict = (True, f"title screen reached after {time.monotonic() - start:.0f}s; "
                                 f"mixin-apply failures: 0", False)
                break
    finally:
        timer.cancel()
        _kill_tree(proc)
    if verdict:
        return verdict
    if timed_out.is_set():
        return (False, f"timeout after {timeout}s without reaching the title screen", network and not started)
    return (False, "process exited before the title screen", network and not started)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module", help="path to the version module (e.g. src/mods/versions/1.21.1)")
    ap.add_argument("--timeout", type=int, default=600, help="hard cap per attempt in seconds")
    ap.add_argument("--world", default="", help="quickplay singleplayer world name (blank = boot to menu)")
    ap.add_argument("--attempts", type=int, default=3, help="max runs when a download flake kills the run")
    args = ap.parse_args()

    module = os.path.abspath(args.module)
    if not os.path.isdir(module):
        print(f"boot-smoke: module dir not found: {module}", file=sys.stderr)
        return 2
    name = os.path.basename(module)

    # A FRESH game dir (every CI run) opens vanilla's one-time accessibility
    # onboarding screen (1.19.4+) instead of the title, so the title marker never
    # comes -- every 1.19.4+ module timed out on the first run of this gate while
    # 1.8.9-1.19.3 passed. Seed the option a returning player already has; an
    # existing run/options.txt (a local dev setup) is left alone. OriginDevShots
    # skips the same screen for dev-shots.
    run_dir = os.path.join(module, "run")
    options = os.path.join(run_dir, "options.txt")
    if not os.path.exists(options):
        os.makedirs(run_dir, exist_ok=True)
        with open(options, "w", encoding="ascii", newline="\n") as f:
            f.write("onboardAccessibility:false\npauseOnLostFocus:false\n")
        print(f"boot-smoke: seeded {options} (onboardAccessibility:false)", flush=True)

    gradlew = os.path.join(module, "gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [gradlew, "runClient", "--no-daemon"]
    if args.world:
        cmd.append(f"-Pquickplay={args.world}")

    ok, reason = False, "not run"
    for attempt in range(1, max(1, args.attempts) + 1):
        print(f"boot-smoke: {name} attempt {attempt} -> {' '.join(cmd)}", flush=True)
        ok, reason, retryable = run_once(module, cmd, args.timeout)
        if ok or not retryable or attempt == args.attempts:
            if retryable and not ok:
                reason += f" (network/download failure on all {attempt} attempts -- not an Origin bug; re-run)"
            break
        delay = 30 * (3 ** (attempt - 1))
        print(f"boot-smoke: {name}: download failure before the game started; retrying in {delay}s", flush=True)
        time.sleep(delay)

    verdict = f"boot-smoke: {name}: {'PASS' if ok else 'FAIL'} -- {reason}"
    print("\n" + verdict, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"- {verdict}\n")
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
