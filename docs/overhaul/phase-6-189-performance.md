# Phase 6 - Minecraft 1.8.9 performance

Covers ORIGIN_PROGRESS P6-01 to P6-12, B-04, B-07, B-12, B-13. Standing rules: `docs/overhaul/README.md` (S1 real-install testing,
S12 honesty: **never quote CI FPS as a player number**). Phase branch `claude/ovh-p6` (track C). Design basis: `audit/perf-189.md`
and `audit/architecture.md` section 6 (D1 presets).

## 1. Objectives (03's order: 6.1 -> 6.2 -> 6.3 -> 6.4)
1. **6.1 Benchmark mode first**: repeatable scenarios measuring average FPS, 1 % and 0.1 % lows, frame-time median / p99 / stdev,
   memory (heap, allocation rate, GC pauses), Origin's own per-frame cost, startup time; input latency where a real display allows it.
2. **6.2 Presets** Competitive / Balanced / Quality: applied on click, previewed, undoable, tested.
3. **6.3 Targeted optimisation** of measured bottlenecks: HUD rendering, particles, cosmetics, blur, unnecessary rendering, memory
   allocation, background work. Visual correctness and multiplayer kept.
4. **6.4 Real GPU**: run the benchmark on Will's PC (04: allowed) and also ship one command he can run himself that writes a readable
   report. Real numbers only; CI numbers are labelled relative and never presented as gameplay FPS.

## 2. Original requests
03 "PHASE 6" (6.1-6.4). 01 section 15 (1.8.9 extreme performance priority). 00 "I run one command". 04 Performance section
("Benchmark before optimizing ... No stability sacrificed for FPS claims. Real GPU: use Will's hardware where access works; else one
command."). Backlog: B-04 shader lag, B-07 missing performance options, B-12 Esc pixelation, B-13 Lunar-level performance.

## 3. Inspect first
- `audit/perf-189.md` section 1 (stack: Forge 11.15.1.2318, Mixin 0.7.11, OptiFine HD_U_M5 `OptiFineInstaller.cs:35-41`, TexFix +
  BetterFps `LegacyStackInstaller.cs:28-31`, Temurin 8u504 `TemurinJava8.cs:22-27`, heap `RamAdvice.cs:18-33`, GC `JvmArgPresets.cs`,
  no OptiFine settings seeded `AccountSeed.cs:18-21`; CI runs 1.8.9 without OptiFine), the per-frame hook table, section 2 (ranked hot
  spots), 3 (existing perf work + branches `claude/shader-speed`, `release/shader-speed`), 4 (PvP client research), 5 (candidates A-K),
  6 (benchmark plan: `OriginBench`, 7 scenarios, CSV + summary JSON, CI can / cannot), 7 (preset knobs and sketch).
- `audit/architecture.md` section 6 (D1: launcher writes `options.txt` + `optionsof.txt` via `PresetOptions` / `PresetWriter`, undo file
  `<account game dir>/origin-preset-undo.json`, refuse while that account's 1.8.9 game runs, "Custom" label).
- 1.8.9 module: `src/mods/versions/1.8.9` (`HudHooks`, `HudElements`, `PerfSettings`, `FrameStats`, `FpsCoach`, `CosmeticEffects`,
  `MenuBlur`, particle mixin `EntityFX.renderParticle`). Notes: `legacy-forge-port-recipe` (auto-memory).
- `tools/boot-time/summarize.py` (summary pattern), `LaunchTimingHarness.cs:97` (Fabric-only marker; Phase 3 U3.3 adds a Forge one).

## 4. Agent units
| Unit | Agent | Scope | Order |
|---|---|---|---|
| U6.0 E3 OptiFine keys | verifier | `javap` on the cached M5 jar's GameSettings `of*` fields + a CI-dumped fresh `optionsof.txt` | first |
| U6.1a Production-like CI stack | fabric-mod-dev | a 1.8.9 `prodClient` path (Forge universal + OptiFine M5 + TexFix + BetterFps + built Origin jar + Temurin 8) in a CI workflow | parallel with U6.0 |
| U6.1b OriginBench | perf-profiler (design) then fabric-mod-dev | `OriginBench` beside `OriginDevWorld`, env-gated, 7 scenarios, CSV + summary JSON, `tools/bench/summarize.py` | after U6.1a |
| U6.1c Bench gate audit | verify-auditor | proves the bench and summarizer fail on a planted regression and on a corrupt CSV | after U6.1b |
| U6.2 Presets | desktop-app-dev + ui-builder | `PresetOptions.For(Competitive|Balanced|Quality, "1.8.9")`, `PresetWriter`, undo, running-game guard, Settings UI | after U6.0; UI after Phase 3 U3.0 |
| U6.3 Optimisations | perf-profiler picks from measurements, fabric-mod-dev implements | candidates B, C, D, E, F, G, K + B-04/B-12 | after U6.1b baseline; one optimisation per unit branch |
| U6.4 Java + GC A/B | perf-profiler | candidates H, I, J | after U6.1b |
| U6.5 Real GPU | perf-profiler | `tools/bench/bench-189.ps1` + a real run on Will's PC | after U6.3 |
Each optimisation is its own worktree and branch so a failing one is dropped alone.

## 5. Changes to make
**U6.1.** Build the harness exactly as `audit/perf-189.md` section 6 describes (fixed seed world, fixed window, VSync off, scripted camera,
15 s warm-up + 45 s measure, 3-5 alternating runs per variant; per-frame CSV of frame ns, Origin hook ns, render-thread allocated bytes;
per-second GC/heap/entities/chunk updates; summary JSON with avg FPS, median/p99 frame time, 1 % and 0.1 % lows, stdev, GC pause total
and max, allocation MB/s). Variants: OptiFine-only baseline (no Origin jar), Origin default, Origin + each preset, Java A/B, GC A/B,
BetterFps on/off. Add a 1.8.9 path to `boot-time.yml` with `[OriginBoot]` FML marks. Bench code is dev-only and env-gated: it never runs
in a player's game.

**U6.2.** D1 as written. Preset contents start from the `audit/perf-189.md` section 7 sketch, then are tuned by U6.3's measurements;
only keys U6.0 verified are written (a test fails on any unverified key). Origin's own toggles (Menu Blur, cosmetic sparkles, Entity / TE
Distance, Particle Changer) are included because 03 6.3 names HUD, particles, cosmetics and blur (orchestrator decision). Inventory blur:
off in Competitive, on in Balanced and Quality (orchestrator decision). The preset button is an explicit, previewed, undoable bundle,
the FPS Coach "Try it" precedent, recorded as the deliberate exception to "one control, one job" (orchestrator decision). Fast Render
interplay with MenuBlur / ColorGrade / MotionBlur is render-checked before Competitive turns it on.

**U6.3.** For each candidate, in order of measured cost (not the audit's guess): B HUD batching (one Tessellator batch for fills/panels/
graph; HUD caching only if it does not break live elements), C font width cache + batched glyphs (reimplemented, no copied code), D
particles (snapshot settings once per frame), E entity occlusion culling (async, off with shaders), F cosmetics (no per-frame
allocation, capped emitters), G blur (capture once on open; fixes B-12 Esc pixelation if the capture is at full resolution), K Patcher-
style Competitive knobs. B-04 shader lag: measure with a shader pack variant, fix what is Origin's. B-07: compare Origin's 1.8.9 options
with Lunar/Patcher's public option lists and add the missing ones that measure as a win. Each change: before/after bench on the same
runner, alternated; kept only if it improves the target metric without worsening 1 % lows elsewhere; visual check via dev-shots
(`set=world`) against `p0-baseline`; multiplayer check on a local dedicated 1.8.9 server (join, PvP hit registration, nametags, tab list).

**U6.4.** H, I, J as A/B runs; change a default only on a clear, repeated win; record numbers (CI relative only).

**U6.5.** `tools/bench/bench-189.ps1`: one command for Will, in PowerShell, from the repo root:
```powershell
cd "C:\Users\Will\Documents\Minecraft Mods\Origin Client"
powershell -NoProfile -ExecutionPolicy Bypass -File tools\bench\bench-189.ps1
```
It builds or downloads nothing paid, creates an isolated instance under `artifacts\bench-189\` (never his launcher data, worlds or
options), runs the scenarios for Origin default and the three presets (3 runs each), and writes `artifacts\bench-189\report.md`: a plain
table (avg FPS, 1 % low, frame-time consistency, memory) per preset with the date, GPU name, driver, CPU, RAM, and a note on what the
numbers mean. A `-DryRun` mode runs against a fixture CSV set (unit-tested). Agents also run it on Will's PC themselves (04) when his PC
is reachable and no game of his is running; results go into ORIGIN_PROGRESS with the machine details. Low-end claims stay estimates
unless a low-end machine is tested.

## 6. Must NOT change
- Visual correctness (dev-shots compare), multiplayer behaviour, server-side fairness (no client advantage beyond what Lunar/Feather ship).
- The 1.8.9 stack pins (Forge, OptiFine M5 mirror chain, Temurin 8) unless U6.4 proves a change and Phase 7's gate passes it.
- Will's real worlds, options and accounts (the bench uses its own instance). The Mod Menu look (S4).

## 7. Dependencies and parallel tracks
Track C, starts after Phase 0. U6.2's UI after Phase 3 U3.0. Phase 6 code in `versions/1.8.9` merges before Phase 4's W5 (1.8.9 port)
starts; W5 rebases on it. Phase reports stay in order (Phase 6's after Phase 5's).

## 8. Verification
- `.\gradlew.bat build` in `versions/1.8.9`; boot-smoke 1.8.9 green on the production-like stack; zero mixin failures.
- Bench: summary JSON for every variant in CI (labelled "CI, relative only"); `verify-auditor` report from U6.1c.
- Presets: unit tests (bundle contents only verified keys, apply then undo restores every key exactly, refusal while running, "Custom"
  label); launcher-shots of the preset UI.
- Each optimisation: before/after table in the unit report, dev-shots compare, local-server multiplayer check log.
- Real GPU: `report.md` from Will's PC (agent-run or Will-run), with machine details; no number appears anywhere without its source.
- code-reviewer + verifier per unit (S2).

## 9. Completion criteria
1. Bench mode, production-like CI stack and summarizer in place and proven able to fail.
2. Three presets applied, undone and tested; key names verified.
3. Each kept optimisation has a measured win and passes visual + multiplayer checks; dropped ones are listed with their numbers.
4. `bench-189.ps1` works (dry-run test green) and at least one real-GPU report exists, or the report says plainly that no real run
   happened and why.
5. **The judge passes Phase 6 (`docs/overhaul/JUDGE.md`: 95/100, no auto-reject; back-end weights for bench/optimisation parts, full
   weights for the preset UI).**
6. Phase branch merged to `main` and pushed; ORIGIN_PROGRESS, MEMORY updated; short report. No release here (S8).

## 10. Needs Will
Nothing blocks the phase. "Needs testing": how the presets feel on real servers; a low-end laptop run if he has one. "Needs my decision"
(non-blocking): which 1.8.9 options he found missing (B-07), if U6.3's comparison did not cover them. Orchestrator decisions above
(preset exception, Origin toggles in presets, inventory blur default) are recorded, Will can override.
