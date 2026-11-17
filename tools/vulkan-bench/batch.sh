#!/usr/bin/env bash
# usage: batch.sh <group> <reps-from> <reps-to>    groups: P (plain world: A, B, E), C (server stack), D (Create factory)
# Interleaves the stacks (gl, vk, gl, vk ...) so drift over time hits both alike.
set -u
B="C:/Users/Will/AppData/Local/Temp/claude/b5f9c392485e1e3c/614156c5-ad9a-4dc0-92ee-0a152d2e44b7/scratchpad/bench"
unset BENCH_PRIME BENCH_WARM BENCH_SAMPLE
MK="$B/packs/MakeUp-UltraFast.zip"; BSL="$B/packs/BSL_v10.1.3.zip"
PLAN_A="case A12 12 0 30 0 0 12;case A32 32 0 30 0 0 12"
PLAN_E="$PLAN_A;${FLY_STEP:-fly FLY 12 1200 140 0 25 30}"
PLAN_C="field trainworks 160 0 0;case C 12 0 4 -9 0 12"
PLAN_D="field create 320 0 0;case D320 12 0 6 -9 0 0;field create 1280 0 0;case D1280 12 0 6 -9 0 0"
g=$1
for rep in $(seq $2 $3); do
  case $g in
    P) cfgs=("none|-|$PLAN_E" "makeup|$MK|$PLAN_A" "bsl|$BSL|$PLAN_A"); extra="$B/x-plain" ;;
    C) cfgs=("none|-|$PLAN_C" "makeup|$MK|$PLAN_C"); extra="$B/x-full" ;;
    D) cfgs=("none|-|$PLAN_D" "makeup|$MK|$PLAN_D"); extra="$B/x-create" ;;
  esac
  for c in "${cfgs[@]}"; do
    IFS='|' read -r pk pf plan <<< "$c"
    # alternate which stack goes first each rep
    if [ $((rep % 2)) = 1 ]; then order="gl vk"; else order="vk gl"; fi
    for st in $order; do
      bash "$B/run1.sh" "${g}_${st}_${pk}_r${rep}" "$st" "$pf" "$extra" "$plan"
    done
  done
done
