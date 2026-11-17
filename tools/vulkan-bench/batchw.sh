#!/usr/bin/env bash
# Full-list scenario (Will's list minus the Vulkan blockers/crashers), production client. usage: batchw.sh <repFrom> <repTo>
# Cases: W = spawn view, no machines; WC = same world, 320 Create cogs on screen. Stacks alternate order per rep.
set -u
B="C:/Users/Will/AppData/Local/Temp/claude/b5f9c392485e1e3c/614156c5-ad9a-4dc0-92ee-0a152d2e44b7/scratchpad/bench"
unset BENCH_PRIME BENCH_WARM BENCH_SAMPLE
PLAN="case W 12 0 30 0 0 12;field create 320 0 0;case WC 12 0 6 -9 0 0"
for rep in $(seq $1 $2); do
  if [ $((rep % 2)) = 1 ]; then order="gl vk"; else order="vk gl"; fi
  for c in "none|-" "makeup|$B/packs/MakeUpCreate.zip" "eclipse|$B/packs/Eclipse.zip"; do
    IFS='|' read -r pk pf <<< "$c"
    for st in $order; do
      # Eclipse does not load on Vulkan (falls back to no shaders): one Vulkan run proves it, no reps.
      [ "$pk" = eclipse ] && [ "$st" = vk ] && [ "$rep" != 1 ] && continue
      bash "$B/runp.sh" "W_${st}_${pk}_r${rep}" "$st" "$pf" "$B/x-wv4" "$PLAN" "$B/world-w-pristine"
    done
  done
done
