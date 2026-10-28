#!/usr/bin/env bash
# Thread dump of every game JVM in one process group, for a CI run that wedged (dev tool).
#
#   bash tools/ci/thread_dump.sh <process-group-id> [label]
#
# A wedged game prints nothing, so a deadline kill alone leaves no evidence of WHICH
# thread waits on WHAT (the 1.8.9 Save and Quit freeze, 2026-10-28: log stopped at
# "Holder lookups applied" and the job died 9 minutes later with nothing to read).
# Called by dev-shots.yml's wait loop (log stalled / deadline) and by
# tools/boot-smoke/smoke.py's deadline, right before they kill the tree. Generic for
# every module: Fabric (KnotClient / devlaunchinjector) and 1.8.9 Forge (launchwrapper).
#
# Gradle's own JVMs (wrapper, single-use daemon, workers) share the group; they are
# skipped (cmdline has org.gradle). For each game JVM: jstack from THAT JVM's own JDK
# (the game may run Java 8, 17, 21 or 25 -- a newer jstack can't always attach to an
# older VM), printed here and saved next to the shots when ORIGIN_SHOTS is set. If
# jstack is missing or fails, SIGQUIT: the VM then writes the dump to its own stdout,
# which is the run log (dev-shots run.log / smoke.py's echoed output).
pg="$1"
label="${2:-dump}"
[ -n "$pg" ] || { echo "thread_dump: no process group given"; exit 0; }
found=0
# The game JVM is NOT always in the group: Gradle's single-use daemon (--no-daemon with
# custom JVM args) detaches into its own session and forks the game from there (run
# 40618928636: "no game JVM in process group"). So: the group's JVMs plus every JVM on
# the machine running a Minecraft dev launch main (CI runners run one game at a time).
pids=$( { pgrep -g "$pg" java 2>/dev/null; pgrep -f 'devlaunchinjector|KnotClient|launchwrapper|GradleStart|net\.minecraft\.client\.main\.Main' 2>/dev/null; } | sort -un)
for g in $pids; do
  [ "$g" = "$$" ] && continue
  cmd=$(tr '\0' ' ' < "/proc/$g/cmdline" 2>/dev/null || true)
  case "$cmd" in *org.gradle*|*thread_dump*|"") continue ;; esac
  case "$(readlink -f "/proc/$g/exe" 2>/dev/null)" in */java) ;; *) continue ;; esac
  found=1
  out="${TMPDIR:-/tmp}/threaddump-$g-$(date +%s).txt"
  jbin=$(dirname "$(readlink -f "/proc/$g/exe" 2>/dev/null || echo /nonexistent/java)")
  echo "=== thread dump: $label, game pid $g ($(date -u +%H:%M:%S) UTC) ==="
  if [ -x "$jbin/jstack" ] && timeout 30 "$jbin/jstack" -l "$g" > "$out" 2>&1 && grep -q '^"' "$out"; then
    cat "$out"
  else
    echo "(jstack unavailable or failed at $jbin -- SIGQUIT; the dump is in the game's own output)"
    head -n 5 "$out" 2>/dev/null
    kill -3 "$g" 2>/dev/null || true
    sleep 3
  fi
  if [ -n "${ORIGIN_SHOTS:-}" ] && [ -s "$out" ]; then
    mkdir -p "$ORIGIN_SHOTS/screenshots"
    cp "$out" "$ORIGIN_SHOTS/screenshots/threaddump-$(echo "$label" | tr -c 'A-Za-z0-9\n' '-')-$g.txt" 2>/dev/null || true
  fi
  echo "=== end thread dump: game pid $g ==="
done
if [ "$found" != 1 ]; then
  echo "thread_dump: no game JVM found ($label); java processes:"
  ps -eo pid,pgid,sid,args 2>/dev/null | grep -i '[j]ava' | cut -c 1-300
fi
exit 0
