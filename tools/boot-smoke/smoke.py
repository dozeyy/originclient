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
    python tools/boot-smoke/smoke.py <module-dir> [--timeout SECONDS] [--world NAME] [--attempts N] [--run-java N]

--run-java N (CI): run the GAME on JDK N while Gradle stays on its own JVM (21). It adds
tools/boot-smoke/run-java.init.gradle, which pins runClient's javaLauncher to a JDK N
toolchain (found through setup-java's JAVA_HOME_N_X64), then PROVES it: once the client
is up, the Knot client process is located under /proc and its real java binary's
`-version` is printed and checked against N. A mismatch fails the run.

--inworld DIR (modules with "inworld": true in src/mods/live-modules.json): after the
title marker, keep the game running for the in-world step (OriginDevWorld.inWorldTick,
env ORIGIN_SMOKE_WORLD=DIR): a flat test world, the HUD and the Mod Menu shot into
DIR/screenshots, Save and Quit. Passes only on "[origin-smoke] in-world: PASS" with both
PNGs on disk; "[origin-smoke] in-world: FAIL <reason>", a crash or the extra time budget
running out fails the run with that reason.

Exit code 0 = reached the title screen with zero mixin-apply failures (and, with --inworld,
passed the in-world step).
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

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "ci"))
import perf_log   # tools/ci/perf_log.py: Sodium/Iris loaded + applied-mixin tally (shared with dev-shots)
import companion_mods   # tools/ci/companion_mods.py: the catalog's ImmediatelyFast + Mod Menu versions, and the loaded-list check

# Lines that mean a mixin (ours or a bundled mod's) failed to apply, the mod set
# was rejected, or the game threw during startup. Any one of these fails the run.
FAIL_PATTERNS = [
    re.compile(r"Mixin apply(ing)? .*failed", re.I),
    re.compile(r"Critical injection failure", re.I),
    re.compile(r"InvalidInjection", re.I),
    re.compile(r"was not found in|Could not find any target|Injection point .*could not", re.I),
    re.compile(r"Incompatible mods found", re.I),
    re.compile(r"A mod crashed on startup", re.I),
    re.compile(r"Exception in thread \"main\""),
    re.compile(r"Failed to start Minecraft", re.I),
]
# The ONE ready marker: Origin's own dev-only title-screen line (see module docstring).
READY = re.compile(r"\[origin-smoke\] title screen reached")
# The in-world step's verdict (--inworld): exactly one of these, from OriginDevWorld.inWorldEnd.
INWORLD = re.compile(r"\[origin-smoke\] in-world: (PASS|FAIL)\b ?(.*)")
# The two shots the step must leave in DIR/screenshots (prefixes; the size is the window's).
INWORLD_SHOTS = ("inworld_hud_", "inworld_modmenu_")
# Extra seconds after the title for world creation + the step (a fresh flat world on CI's software GL).
INWORLD_BUDGET = 420
# Known red herrings that look scary but prove nothing about our code
# (see MEMORY.md -> runclient-voxy-blocker, and the Iris shader-var warnings).
IGNORE = re.compile(r"twelvemonkeys|ImageInputStreamSpi|BIOME_PALE_GARDEN|endFlashIntensity", re.I)
# Gradle/HTTP download failures (Maven Central 429, Loom DownloadException, CDN
# timeouts): ONE list shared with tools/ci/gradle_prefetch.sh.
_NET_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ci", "network_errors.txt")
with open(_NET_FILE, encoding="utf-8") as _f:
    NETWORK = re.compile("|".join(l.strip() for l in _f if l.strip() and not l.lstrip().startswith("#")), re.I)
# A compile error is never a download flake, whatever else the log says.
COMPILE_ERROR = re.compile(r"Compilation failed|\.java:\d+: error:|^\s*error:", re.I)
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


def retryable(pre_start_lines):
    """True only when Gradle's own "What went wrong" block names a download/network
    failure and nothing failed to compile. pre_start_lines = output before the game JVM
    started (a failure after that is the game's, never a flake)."""
    if any(COMPILE_ERROR.search(l) for l in pre_start_lines):
        return False
    block, inside = [], False
    for l in pre_start_lines:
        if "What went wrong" in l:
            inside = True
        elif inside and l.lstrip().startswith("* Try"):
            inside = False
        elif inside:
            block.append(l)
    return any(NETWORK.search(l) for l in block)


def inworld_verdict(line, shots_dir):
    """(ok, reason) for an in-world verdict line, or None when the line is not one. A PASS still
    fails when either shot is missing from shots_dir/screenshots."""
    m = INWORLD.search(line)
    if not m:
        return None
    if m.group(1) == "FAIL":
        return False, f"in-world step FAILED: {m.group(2).strip()[:200] or 'no reason given'}"
    folder = os.path.join(shots_dir, "screenshots")
    have = sorted(f for f in os.listdir(folder) if f.endswith(".png")) if os.path.isdir(folder) else []
    missing = [p for p in INWORLD_SHOTS if not any(f.startswith(p) for f in have)]
    if missing:
        return False, f"in-world step said PASS but {', '.join(p + '*.png' for p in missing)} missing from {folder}"
    return True, "in-world PASS (" + ", ".join(f for f in have if f.startswith(INWORLD_SHOTS)) + ")"


def run_once(module, cmd, timeout, run_java=0, perf=None, companions=None, expect_perf=None, inworld=""):
    """One runClient. Returns (ok, reason, retryable). companions = {mod id: catalog version} that
    must appear in the loaded mod list (--companions), else the run fails. inworld = shots dir for the
    in-world step (blank = stop at the title)."""
    env = dict(os.environ, ORIGIN_SMOKE="1")
    if inworld:
        env["ORIGIN_SMOKE_WORLD"] = inworld
    if perf is not None:
        # Mixin logs "Mixing X from Y into Z" at INFO only with this flag (perf_log.tally counts them).
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " -Dmixin.debug.verbose=true").strip()
    seen = []
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
    started = False
    pre_start = []
    title = None   # with --inworld: the title verdict, kept while the in-world step runs
    try:
        for line in proc.stdout:
            sys.stdout.write(line)
            if title is not None and IGNORE.search(line) is None:
                iv = inworld_verdict(line, inworld)
                if iv is not None:
                    verdict = (iv[0], title + "; " + iv[1], False)
                    if not iv[0]:
                        print("::error title=in-world step failed::" + iv[1], flush=True)
                    break
            if perf is not None or companions:
                seen.append(line)
            if not started and GAME_STARTED.search(line):
                started = True
            if not started:
                pre_start.append(line)
            kind = classify(line)
            if kind == "fail":
                verdict = (False, f"failure signature: {line.strip()[:200]}", False)
                break
            if kind == "ready":
                verdict = (True, f"title screen reached after {time.monotonic() - start:.0f}s; "
                                 f"mixin-apply failures: 0", False)
                if run_java:
                    ok, reason = _check_game_jvm(run_java, (True, verdict[1]))
                    verdict = (ok, reason, False)
                if companions and verdict[0]:
                    problems = companion_mods.verify_loaded("".join(seen), companions)
                    shown = ", ".join(f"{k} {v}" for k, v in sorted(companions.items()))
                    if problems:
                        print("::error title=companions not loaded::" + "; ".join(problems), flush=True)
                        verdict = (False, "companion mods not loaded: " + "; ".join(problems), False)
                    else:
                        print(f"boot-smoke: companions loaded at the catalog versions: {shown}", flush=True)
                        verdict = (True, verdict[1] + f"; companions loaded: {shown}", False)
                if perf is not None and verdict[0]:
                    # A player-profile row may require Iris NOT to load (covers.py --player with no Iris): then only the
                    # expectation below decides what must be in the mod list.
                    require = perf and not (expect_perf and "absent" in expect_perf.values())
                    pok, plines = perf_log.gate("".join(seen), os.path.basename(module), require)
                    print(chr(10).join(plines), flush=True)
                    if not pok:
                        verdict = (False, "Sodium/Iris gate failed (see perf-gate lines above)", False)
                    else:
                        verdict = (True, verdict[1] + f"; Origin Sodium/Iris mixins applied: {len(perf_log.tally(''.join(seen)))}", False)
                if expect_perf and verdict[0]:
                    # covers.py --pair: the swapped-in pair must be what loaded (not a nested or shipped copy).
                    text = "".join(seen)
                    have = perf_log.loaded(text)
                    wrong = [f"{k} loaded {have.get(k, 'NOT LOADED')}, expected {v}" for k, v in sorted(expect_perf.items())
                             if v != "absent" and have.get(k) != v]
                    # "absent" (covers.py --player, no player Iris): Fabric's mod list still draws a disabled nested
                    # mod under its parent ("|-- iris ..."), so the proof is that it is not a top-level entry AND that
                    # none of its own mixin configs applied (run 40286066973: 0 lines from mixins.iris*.json).
                    for k, v in sorted(expect_perf.items()):
                        if v != "absent":
                            continue
                        top = re.search(r"^[ \t]*- %s " % re.escape(k), text, re.M)
                        own = re.search(r"Mixing \S+ from (?!originclient)\S*\b%s\b\S*\.json into" % re.escape(k), text)
                        if top or own:
                            wrong.append(f"{k} loaded ({'top-level' if top else 'its mixins applied'}), expected absent")
                    if wrong:
                        print("::error title=pair not loaded::" + "; ".join(wrong), flush=True)
                        verdict = (False, "swapped Sodium/Iris pair not loaded: " + "; ".join(wrong), False)
                    else:
                        shown = ", ".join(f"{k} {v}" for k, v in sorted(expect_perf.items()))
                        print(f"boot-smoke: pair loaded: {shown}", flush=True)
                        verdict = (True, verdict[1] + f"; pair loaded: {shown}", False)
                if inworld and verdict[0]:
                    # Keep the game: the in-world step starts now, on a fresh deadline of its own.
                    title, verdict = verdict[1], None
                    timer.cancel()
                    timer = threading.Timer(INWORLD_BUDGET, _expire)
                    timer.start()
                    print(f"boot-smoke: title reached; in-world step running (budget {INWORLD_BUDGET}s)", flush=True)
                    continue
                break
    finally:
        timer.cancel()
        _kill_tree(proc)
    if verdict:
        return verdict
    flake = not started and retryable(pre_start)
    if title is not None:
        why = f"no verdict within {INWORLD_BUDGET}s" if timed_out.is_set() else "the game exited first"
        print("::error title=in-world step failed::" + why, flush=True)
        return (False, f"{title}; in-world step FAILED: {why} (grep '[origin-smoke] in-world' in the log)", False)
    if timed_out.is_set():
        return (False, f"timeout after {timeout}s without reaching the title screen", flake)
    return (False, "process exited before the title screen", flake)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module", help="path to the version module (e.g. src/mods/versions/1.21.1)")
    ap.add_argument("--timeout", type=int, default=600, help="hard cap per attempt in seconds")
    ap.add_argument("--world", default="", help="quickplay singleplayer world name (blank = boot to menu)")
    ap.add_argument("--attempts", type=int, default=3, help="max runs when a download flake kills the run")
    ap.add_argument("--run-java", type=int, default=0,
                    help="boot the game JVM on this Java major (Gradle stays on its own JDK) and verify it")
    ap.add_argument("--perf", action="store_true",
                    help="CI: Sodium + Iris jars were fetched into libs/ (tools/ci/perf_libs.py); require both in the "
                         "mod list and print how many Origin Sodium/Iris mixins applied")
    ap.add_argument("--companions", action="store_true",
                    help="CI: tools/ci/companion_mods.py put the catalog's ImmediatelyFast + Mod Menu jars in run/extra-mods "
                         "(loaded via -PextraMods): FAIL unless the loaded mod list names each at its catalog version")
    ap.add_argument("--expect-perf", default="",
                    help="CI (covers.py --pair): a file of 'sodium=<ver>' / 'iris=<ver>' lines; FAIL unless the loaded mod "
                         "list names exactly those versions")
    ap.add_argument("--gradle-arg", action="append", default=[],
                    help="extra argument for the gradle runClient call (repeatable), e.g. --stacktrace")
    ap.add_argument("--inworld", default="",
                    help="after the title: flat world, HUD + Mod Menu shots into DIR/screenshots, Save and Quit (must PASS)")
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
    cmd += args.gradle_arg
    if args.world:
        cmd.append(f"-Pquickplay={args.world}")
    if args.run_java:
        # setup-java exports JAVA_HOME_<major>_X64 (or _ARM64) for each JDK it installs.
        home = next((os.environ[k] for k in (f"JAVA_HOME_{args.run_java}_X64", f"JAVA_HOME_{args.run_java}_ARM64")
                     if os.environ.get(k)), "")
        if not home:
            print(f"boot-smoke: --run-java {args.run_java} needs JAVA_HOME_{args.run_java}_X64 (setup-java) in the environment",
                  file=sys.stderr)
            return 2
        cmd += ["--init-script", os.path.join(HERE, "run-java.init.gradle"),
                f"-DoriginRunJava={args.run_java}",
                f"-Porg.gradle.java.installations.paths={home}"]

    companions = None
    if args.companions:
        with open(companion_mods.CATALOG, encoding="utf-8") as f:
            companions = companion_mods.expected_versions(f.read(), name)
        if not companions:
            print(f"boot-smoke: --companions: the launcher catalog bundles no ImmediatelyFast / Mod Menu for {name}", file=sys.stderr)
            return 1

    expect_perf = None
    if args.expect_perf:
        with open(args.expect_perf, encoding="ascii") as f:
            expect_perf = dict(l.strip().split("=", 1) for l in f if "=" in l)
        if set(expect_perf) != {"sodium", "iris"} or not args.perf:
            print("boot-smoke: --expect-perf needs --perf and both sodium= and iris= lines", file=sys.stderr)
            return 2

    ok, reason = False, "not run"
    for attempt in range(1, max(1, args.attempts) + 1):
        print(f"boot-smoke: {name} attempt {attempt} -> {' '.join(cmd)}", flush=True)
        ok, reason, retryable = run_once(module, cmd, args.timeout, args.run_java, True if args.perf else None, companions,
                                         expect_perf, os.path.abspath(args.inworld) if args.inworld else "")
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


def _java_major(version_output):
    """'openjdk version "17.0.13" ...' -> 17; '"1.8.0_492"' -> 8."""
    m = re.search(r'version "(\d+)(?:\.(\d+))?', version_output)
    if not m:
        return None
    major = int(m.group(1))
    return int(m.group(2)) if major == 1 and m.group(2) else major


def _check_game_jvm(expected, verdict):
    """Find the running Minecraft JVM (the process whose command line names Fabric's
    KnotClient) via /proc, print its real java binary and `-version`, and require the
    expected major. Linux only (CI); anywhere else a --run-java run cannot be proven."""
    if not os.path.isdir("/proc"):
        return (False, "--run-java cannot verify the game JVM without /proc (Linux only)")
    exe = None
    for pid in filter(str.isdigit, os.listdir("/proc")):
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                cmdline = f.read()
            # Loom wraps the game in `sh -c "... java ... KnotClient"` (xvfb-run); skip the
            # shell, the real JVM is the process whose binary is named java.
            real = os.path.realpath(f"/proc/{pid}/exe")
            # Loom's launcher names devlaunchinjector on the command line, Knot is its main
            # class: accept either (dev-shots.yml's pgrep does the same).
            if (b"devlaunchinjector" in cmdline or b"KnotClient" in cmdline)                     and os.path.basename(real).startswith("java"):
                exe = real
                break
        except OSError:
            continue
    if not exe:
        return (False, "game ready, but no KnotClient JVM found under /proc to verify its Java version")
    out = subprocess.run([exe, "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         universal_newlines=True).stdout
    print(f"boot-smoke: GAME JVM binary: {exe}", flush=True)
    for ln in out.strip().splitlines():
        print(f"boot-smoke: GAME JVM -version: {ln}", flush=True)
    major = _java_major(out)
    if major != expected:
        return (False, f"game JVM is Java {major}, expected {expected} ({exe})")
    ok, reason = verdict
    return (ok, f"{reason} [game JVM = Java {major}: {exe}]")


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
