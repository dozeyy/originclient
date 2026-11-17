#!/usr/bin/env bash
# usage: run1.sh <runName> <gl|vk> <pack.zip|-> <extraDir> <plan>   (env BENCH_PRIME/BENCH_WARM/BENCH_SAMPLE pass through)
set -u
B="C:/Users/Will/AppData/Local/Temp/claude/b5f9c392485e1e3c/614156c5-ad9a-4dc0-92ee-0a152d2e44b7/scratchpad/bench"
M="C:/Users/Will/Documents/Minecraft Mods/Origin Client/.claude/worktrees/agent-a371c2ec01aa23f4c/src/mods/versions/1.21.1"
name=$1; mode=$2; pack=$3; extra=$4; plan=$5
out="$B/runs/$name"; [ -e "$out" ] && { echo "run $name exists; pick a new name"; exit 3; }
mkdir -p "$out" "$M/run/config" "$M/run/shaderpacks" "$M/run/saves"
cp -f "$B/options.txt" "$M/run/options.txt"
# every run opens its own fresh copy of the primed world (no deletes: unique names)
export BENCH_WORLD="b_$name"
[ -e "$M/run/saves/$BENCH_WORLD" ] && { echo "world $BENCH_WORLD exists"; exit 3; }
cp -r "$B/world-pristine" "$M/run/saves/$BENCH_WORLD"
if [ "$pack" != "-" ]; then cp -f "$pack" "$M/run/shaderpacks/"; printf 'enableShaders=true\nshaderPack=%s\n' "$(basename "$pack")" > "$M/run/config/iris.properties"
else printf 'enableShaders=false\n' > "$M/run/config/iris.properties"; fi
gargs="-PextraMods=$extra -PbootLog"
[ "$mode" = vk ] && gargs="$gargs -PrendererJars=$B/rj -PdisableModIds=sodium,immediatelyfast"
export BENCH_OUT="$out" BENCH_PLAN="$plan"
rm -f "$out/gpu.stop"
powershell -NoProfile -ExecutionPolicy Bypass -File "$B/gpu.ps1" -Out "$out/gpu.txt" -Stop "$out/gpu.stop" &
gp=$!
cd "$M"
echo "$(date +%T) run $name: $mode pack=$pack extra=$(basename $extra)"
t0=$(date +%s)
timeout 1500 ./gradlew.bat runClient $gargs > "$out/game.log" 2>&1
rc=$?
touch "$out/gpu.stop"; wait $gp 2>/dev/null
echo "$(date +%T) exit $rc in $(( $(date +%s) - t0 )) s; done=$([ -f "$out/bench.done" ] && echo yes || echo NO); $(grep -c 'sample end' "$out/bench.log" 2>/dev/null) samples"
