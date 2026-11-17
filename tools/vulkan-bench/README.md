# Vulkan vs Sodium bench (1.21.1)

Bench-only tooling from the 2026-11-17 run, kept for re-runs. It ships nothing: no jars and no shipped code.

- `mod/`: the `vk-bench` client mod (id `origin_vk_bench`). Env vars drive it: `BENCH_OUT`, `BENCH_PLAN`, `BENCH_WORLD`,
  `BENCH_PRIME`, `BENCH_WARM`, `BENCH_SAMPLE`. On the title screen it sets a 1920x1080 window and opens or creates the world
  (seed 20261117). It sets noon, clear weather and no spawns, then runs the plan:
  - `case NAME RD DX DY DZ YAW PITCH`: teleport, wait for terrain readiness, warm up, sample frame times, screenshot.
  - `field NS COUNT DX DZ`: a field of cogs facing south.
  - `fly NAME RD DX Y DZ PITCH SECONDS`: fly east at 1 block per tick.
  - `cmd ...`: one server command.

  It writes `<case>.frames` (ns per frame) and `bench.log`, then quits. Build it with the vulkairis vendor wrapper:
  `./gradlew.bat -p tools/vulkan-bench/mod build`.
- `run1.sh`: one runClient run (dev, `run/`).
- `runp.sh`: one prodClient run (`run-prod/`; needed for big mod lists like Cobblemon).
- Both scripts take `gl` or `vk`. `vk` adds `-PrendererJars=<VulkanMod, VulkaIris, flywheel-vulkan> -PdisableModIds=sodium,immediatelyfast`, the same switches the launcher uses.
- `batch.sh` and `batchw.sh`: reps with the stack order alternating each rep.
- `gpu.ps1`: 3D-engine GPU % per pid and total CPU, sampled once a second.
- `analyze.py runs [prefix]`: per run, then medians. It reports avg FPS, 1% low (mean of the worst 1% of frames) and p99 frame time.
- `gate.py <mods dir>`: which jars the launcher's Vulkan gate (`VulkanMode.BlockingIds`) would block.
- `errdiff.py <gl.log> <vk.log>`: warnings and errors that only appear on Vulkan.
- `shot.ps1`: PrintWindow capture of a game window the bench launched. It sends no input.
- `lum.py "<glob>" <base>`: mean brightness of shots (a black frame = a pack that did not render, so its FPS is invalid).

The scripts carry the scratch paths of the original run (`B=` / `M=` at the top); change those first.
Take the machine lock: `pwsh -NoProfile -File ~/.claude/scripts/verify-lock.ps1 -MaxMinutes N -- bash <script>`.
