#!/usr/bin/env bash
# Full-list runs: production client (run-prod/), Will's mod list (minus Voxy + originclient.jar) + vk-bench.
# usage: runp.sh <runName> <gl|vk> <pack.zip|-> <extraDir> <plan> <worldSrc|->   (env BENCH_PRIME/WARM/SAMPLE pass through)
set -u
B="C:/Users/Will/AppData/Local/Temp/claude/b5f9c392485e1e3c/614156c5-ad9a-4dc0-92ee-0a152d2e44b7/scratchpad/bench"
M="C:/Users/Will/Documents/Minecraft Mods/Origin Client/.claude/worktrees/agent-a371c2ec01aa23f4c/src/mods/versions/1.21.1"
R="$M/run-prod"
name=$1; mode=$2; pack=$3; extra=$4; plan=$5; world=$6
out="$B/runs/$name"; [ -e "$out" ] && { echo "run $name exists; pick a new name"; exit 3; }
mkdir -p "$out" "$R/config" "$R/shaderpacks" "$R/saves"
cp -f "$B/options.txt" "$R/options.txt"
export BENCH_WORLD="b_$name"
[ -e "$R/saves/$BENCH_WORLD" ] && { echo "world $BENCH_WORLD exists"; exit 3; }
[ "$world" != "-" ] && cp -r "$world" "$R/saves/$BENCH_WORLD"
if [ "$pack" != "-" ]; then
  cp -f "$pack" "$R/shaderpacks/"; [ -f "$pack.txt" ] && cp -f "$pack.txt" "$R/shaderpacks/"
  printf 'enableShaders=true\nmaxShadowRenderDistance=32\nshaderPack=%s\n' "$(basename "$pack")" > "$R/config/iris.properties"
else printf 'enableShaders=false\n' > "$R/config/iris.properties"; fi
gargs="-PextraMods=$extra -PbootLog -PbootJvmArgs=-Xms8G,-Xmx8G"
[ "$mode" = vk ] && gargs="$gargs -PrendererJars=$B/rj -PdisableModIds=sodium,immediatelyfast"
export BENCH_OUT="$out" BENCH_PLAN="$plan"
rm -f "$out/gpu.stop"
powershell -NoProfile -ExecutionPolicy Bypass -File "$B/gpu.ps1" -Out "$out/gpu.txt" -Stop "$out/gpu.stop" &
gp=$!
cd "$M"
echo "$(date +%T) run $name: $mode pack=$pack extra=$(basename $extra)"
t0=$(date +%s)
timeout 1800 ./gradlew.bat prodClient $gargs > "$out/game.log" 2>&1
rc=$?
touch "$out/gpu.stop"; wait $gp 2>/dev/null
cp -f "$R/logs/latest.log" "$out/latest.log" 2>/dev/null
echo "$(date +%T) exit $rc in $(( $(date +%s) - t0 )) s; done=$([ -f "$out/bench.done" ] && echo yes || echo NO); $(grep -c 'sample end' "$out/bench.log" 2>/dev/null) samples"
