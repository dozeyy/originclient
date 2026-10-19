#!/usr/bin/env bash
# The boot-time.yml boot loop: a warm-up boot per variant, then BOOTS measured
# boots per variant in alternating order, then (PROFILE=true) one JFR-profiled
# boot per variant, then the summary.
#   run-all.sh <workspace> <out-dir>
# Variant b = <workspace>/b (this ref), a = <workspace>/a (base ref) if present.
# Env: MODULE, BOOTS, PROFILE, JVM_A/JVM_B and MFX_A/MFX_B (per-variant extra JVM
# args / ModernFix options, comma-separated), DEVJVM=true to drop the launcher's
# own JVM flags.
set -u
ws="$1"; out="$2"
mkdir -p "$out"
boot="$ws/b/tools/boot-time/boot.sh"
variants="b"
[ -d "$ws/a" ] && variants="a b"
# The launcher's own flags (src/OriginLauncher.App/Core/Launch/JvmArgPresets.cs and
# LaunchProfileBuilder's -Xms == -Xmx), so the boots behave like a player's launch.
prod="-Xms4G,-Xmx4G,-XX:+UseG1GC,-XX:+ParallelRefProcEnabled,-XX:MaxGCPauseMillis=200,-XX:+UnlockExperimentalVMOptions,-XX:+DisableExplicitGC,-XX:G1NewSizePercent=30,-XX:G1MaxNewSizePercent=40,-XX:G1HeapRegionSize=8M,-XX:G1ReservePercent=20,-XX:G1HeapWastePercent=5,-XX:G1MixedGCCountTarget=4,-XX:InitiatingHeapOccupancyPercent=15,-XX:G1MixedGCLiveThresholdPercent=90,-XX:G1RSetUpdatingPauseTimePercent=5,-XX:SurvivorRatio=32,-XX:+PerfDisableSharedMem,-XX:MaxTenuringThreshold=1"
[ "${DEVJVM:-false}" = "true" ] && prod=""

one() { # one <variant> <log> [gradle args...]
  local v="$1" log="$2"; shift 2
  local x m
  if [ "$v" = "a" ]; then x="${JVM_A:-}"; m="${MFX_A:-}"; else x="${JVM_B:-}"; m="${MFX_B:-}"; fi
  local j="$prod"
  [ -n "$x" ] && j="${j:+$j,}$x"
  BOOT_JVM="$j" BOOT_MFX="$m" bash "$boot" "$ws/$v" "$MODULE" "$log" "$@"
}

nproc; free -m | head -2
for v in $variants; do
  echo "::group::warm-up $v (build + first boot)"
  one "$v" "$out/warm$v-0.log"
  echo "::endgroup::"
done
for i in $(seq 1 "$BOOTS"); do
  order="$variants"
  # Alternate the order each round so neither variant always boots second.
  [ $((i % 2)) -eq 0 ] && [ "$variants" = "a b" ] && order="b a"
  for v in $order; do
    echo "boot $v-$i"
    one "$v" "$out/$v-$i.log"
  done
done
if [ "${PROFILE:-false}" = "true" ]; then
  for v in $variants; do
    echo "profiled boot $v"
    one "$v" "$out/prof$v-1.log" -PreloadProfile "-PbootJfr=$out/prof-$v.jfr"
    echo "--- $v reload listeners ---"
    grep -E "took approximately|Resource reload finished" "$out/prof$v-1.log" | sed -E 's/^.*\]: //' || true
  done
fi
echo "--- mixin failures (should be none) ---"
grep -iE "Mixin apply(ing)? .*failed|Critical injection failure" "$out"/*.log || echo "none"
python3 "$ws/b/tools/boot-time/summarize.py" "$out" | tee "$out/summary.md"
