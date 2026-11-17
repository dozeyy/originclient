#!/usr/bin/env bash
# Full list minus oblivion-magic too (it crashes Vulkan once a pack is active): MakeUp (Create) on both stacks.
B="C:/Users/Will/AppData/Local/Temp/claude/b5f9c392485e1e3c/614156c5-ad9a-4dc0-92ee-0a152d2e44b7/scratchpad/bench"
unset BENCH_PRIME BENCH_WARM BENCH_SAMPLE
PLAN="case W 12 0 30 0 0 12;field create 320 0 0;case WC 12 0 6 -9 0 0"
for rep in 1 2 3; do
  if [ $((rep % 2)) = 1 ]; then order="gl vk"; else order="vk gl"; fi
  for st in $order; do bash "$B/runp.sh" "M_${st}_makeup_r${rep}" "$st" "$B/packs/MakeUpCreate.zip" "$B/x-wv5" "$PLAN" "$B/world-w-pristine"; done
done
