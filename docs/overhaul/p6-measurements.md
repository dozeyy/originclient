# Phase 6 U6.3 step 1 - where Origin's own frame time goes on 1.8.9

Branch `claude/p6-measure` (cut from main 332def388). This step only measures. Each pick below
becomes its own implementation unit, on its own branch, re-measured with the same bench.

## Method and caveats (read before the numbers)

- **Bench:** the `bench-189` workflow (Xvfb + Mesa llvmpipe, OptiFine M5). Each round alternates the
  variants `optifine-only` (the probe: Origin loaded but inert) and `origin-default` (Origin as
  shipped). Each scene is run 3 times, and every number below is the median of the runs. The one
  exception is the per-HUD-element sections, which have n=2.
- **The numbers are relative, not player FPS.** llvmpipe rasterises on the CPU, so anything that
  draws is inflated. Read the A/B deltas as "Origin costs X relative to OptiFine alone on this box".
- **BenchProf** (`BenchProf.java`) wraps every Origin event handler (36 of them) plus named sections
  such as mixins, layers and HUD elements. For each one it records ns/frame, bytes/frame (thread
  allocation counter), calls/frame and the maximum single call. The timer cost (40-50 ns/call) is
  subtracted.
  - It is off unless `ORIGIN_BENCH_PROF=1` is set: `BenchProf.ON` is a `static final` from
    `OriginBench.profiling()`, and every call site is guarded by it.
  - Its own cost while on is 14-69 us/frame.
- **CPU timing of a GPU pass is meaningless on llvmpipe.** The Menu Blur section reads about 35 ms
  because it absorbs the frame's GPU sync. Blur and cosmetics are therefore judged by the A/B frame
  delta, not by their section time.
- **Maximum single-call figures are preemption noise** on the shared runner (some reach 500 us). They
  are upper bounds, not hitches.
- **Microbench:** the pure, warmed CPU cost per call, measured in the same JVM. It is the most
  reliable small number here.
- **Bench fixes made here** (all bench-only):
  - (a) Limit Unfocused FPS is off during CI. This is the 30 FPS cap below; it also made
    `FrameWatch.idle()` skip every HUD element and the cosmetic sparkles.
  - (b) After the flythrough, the camera returns to the static spot. Before this, later scenes
    rendered from the flight's end point, which invalidated the first players/cosmetics run.
  - (c) New scenes: `players` (16 fake players), `cosmetics` (the same 16 wearing cosmetics) and
    `pause` (the Esc menu).
  - (d) A `size` input on the workflow.

## Runs

| Run id | Scenes | Use |
|---|---|---|
| 40189356614 | hud | **invalid**: no HUD was drawn (FrameWatch idle under Xvfb). Led to fix (a) |
| 40190206267 | idle, particles, menu, pause, flythrough (+players/cosmetics, invalid: camera) | main table, blur, particles, B-12 screenshot |
| 40190284101 | idle, hud | first HUD-on numbers |
| 40191213373 | idle, hud | per-HUD-element sections |
| 40192448235 | idle, flythrough, players, cosmetics | cosmetics, nametags, cape physics |
| 40191592587 / 40191594991 | idle at 427x240, fix (a) reverted / applied | 30 FPS cap proof |
| 40190037572 | presets (coordinator's run) | the cap report that started the check |

The summary gate shows red on idle+hud runs because the probe has no hud scene, and on the
cap-check run because of the regression. Those failures are expected; the data in the artifacts is
complete.

## The 30 FPS cap (cause + fix)

- **Cause:** Origin's own Limit Unfocused FPS. It is on by default, with Max Unfocused FPS 30
  (`Mods.java:99-100`). It is applied by `MinecraftFramerateMixin.getLimitFramerate`
  (`mixin/world/MinecraftFramerateMixin.java:21-35`). Xvfb never gives the window focus, so every
  non-probe variant was capped at 30, including the preset variants.
- **Proof** (427x240 so that llvmpipe can exceed 30, idle, 2 runs each):
  - With the fix reverted (40191592587): origin **30.0 fps, p50 33.4 ms, pinned**; probe 39.6.
  - With the fix applied (40191594991): origin **38.75** vs probe 40.35.
- **Fix:** `OriginBench.saveAndApplySettings` turns `limitUnfocusedFps` off when
  `IGNORE_FOCUS && !PROBE`, and `restoreSettings` puts it back. This follows the same CI-only switch
  as `ORIGIN_BENCH_IGNORE_FOCUS`; players never get this path. The probe variant is untouched,
  because Origin is inert there.
- **What I saw in the preset run:** at 1280x720, run 40190037572 showed 21.8-24.1 fps, under the
  cap. The cap was still latent for any variant that would have gone above 30.

## Per-hook cost (Origin as shipped, default mods)

These are medians. ns/frame are CPU on llvmpipe, so they are upper bounds.

| Hook / section | Scene (run) | ns/frame | bytes/frame | calls/frame |
|---|---|---|---|---|
| All Origin handlers, total | idle (40190206267) | 357,454 | 3,860 | - |
| HudHooks.onOverlayPost | idle (40190206267) | 217,916 | 2,787 | 8 |
| - hud element directionhud (compass, default on) | idle (40191213373) | 169,400 | 2,163 | 1 |
| OriginClientMod.onClientTick | idle | 26,680 | 514 | 2.25 |
| SocialHooks.onRenderWorldLast | idle | 18,591 | 112 | 1 |
| ServerHooks.onClientTick | idle | 17,880 | 117 | 2.25 |
| ServerHooks.onWorldLast | idle | 10,879 | 24 | 1 |
| every other handler | idle | <10,000 each | <120 | - |
| mixin RenderManager.shouldRender (Entity Distance) | idle | 359 | 0 | 14 |
| **HudHooks.onOverlayPost, all HUD elements on** | hud (40191213373) | **2,086,263** | **233,660** | 8 |
| - minimap | hud | 642,776 | 181,341 | 1 |
| - fps (+ frame graph) | hud | 320,268 | 15,204 | 1 |
| - keystrokes | hud | 252,336 | 9,256 | 1 |
| - itemcounter | hud | 158,265 | 3,604 | 1 |
| - directionhud | hud | 119,064 | 1,768 | 1 |
| - clock / packdisplay / portalcalc / serveraddress / coords | hud | 77k / 64k / 60k / 59k / 53k | <=4,035 | 1 |
| - the rest (reach, memory, stopwatch, ping, cps, combo, daycounter, ...) | hud | <41k each | <=6,041 | 1 |
| **layer CosmeticLayer.doRenderLayer** (hats/masks/backs) | cosmetics (40192448235) | **20,426,211** (GPU-inflated) | **261,376** | 16 |
| mixin LayerCape.doRenderLayer HEAD (cape physics) | cosmetics | 297,492 | 54,272 | 16 |
| SocialHooks.onRenderWorldLast (cosmetic sparkles) | cosmetics | 186,712 | 800 | 1 |
| mixin Render.renderLivingLabel (nametags) | players / cosmetics | 12,678 / 20,639 | 0 | 16 |
| cape-physics HEAD, no cosmetics worn | players | 12,476 | 0 | 16 |

The idle-scene rows are for run 40190206267; the directionhud idle row is run 40191213373.

Frame A/B (p50 ms, origin vs probe; alloc MB/s on the render thread):

| Scene (run) | origin p50 | probe p50 | delta | alloc origin / probe |
|---|---|---|---|---|
| idle (40190206267) | 54.2 | 52.7 | +1.5 (noise; 40191213373 shows 53.7 vs 53.9) | 2.52 / 2.18 |
| hud, all elements (40191213373) | 55.4 | idle 53.7 | +1.7 vs idle | 6.27 / 2.40 |
| particles (40190206267) | 66.1 | 65.4 | +0.7 (noise) | 47.0 / 46.7 |
| menu (40190206267) | **61.2** | 55.7 | **+5.5** | 2.55 / 3.14 |
| pause (40190206267) | **59.0** | 53.4 | **+5.7** | 1.58 / 1.63 |
| flythrough (40192448235) | 53.8 | 53.5 | +0.3 (noise) | 3.69 / 3.57 |
| players, 16 (40192448235) | 47.5 | 46.9 | +0.6 (noise) | 7.11 / 7.04 |
| **cosmetics, 16 wearers** (40192448235) | **59.0** | 46.9 | **+12.1** | **11.84 / 7.06** |

Particles (40190206267): 2,336 particle renders and 553 spawns per frame.
Microbench, per call (3 runs): `ParticleFilter.globalScale` 23-29 ns, `hidden` 24-29 ns,
`Mods.on` 21-28 ns.

## Ranked picks

The ranking is cost x reach. The expected win is never more than what was measured. Real-GPU
numbers do not exist yet: every pick is re-measured with this bench before and after, and Will
judges the feel.

### 1. Cosmetics: allocation-free pose math and less per-frame vertex work (candidate F)

- **Measured:** +12.1 ms p50 for 16 wearers (llvmpipe), 261 KB/frame from CosmeticLayer plus 54 KB/frame
  from cape physics, and render-thread allocation 11.84 vs 7.06 MB/s.
- **Expected win:** about 315 KB/frame of garbage goes to roughly zero, which ends the GC pressure in
  lobbies. The CPU part of the 12.1 ms delta shrinks too. How much of the delta is CPU and how much is
  llvmpipe raster cannot be split here.
- **Risk:**
  - Visuals: none if the matrices match bit for bit; the bench `cosmetics` screenshots must match.
  - Multiplayer: none, because this is client-side rendering only.
- **Files:**
  - `cosmetics/ModelCosmetic.java`
    - `:232` `new Mat()` on every pass
    - `:287` `new Mat()`, `:281` `pathScratch`
    - `:371-373` `push()` clones `float[16]`
    - `:390/396/401/406` a new `float[16]` on every translate/rotate
    - `:411` a new `float[16]` on every mul
    - `:309-330` drawBone
    - `:336-353` emit, which transforms every vertex on the CPU every frame
    - `:157-224` render, up to 3 passes, so up to 3 Tessellator draws per cosmetic
  - `cosmetics/CapePhysics.java`: `:156-158` three `new float[N]` per call and `:188` `new Mat()`
    per call.
- **Design:**
  1. `Mat` multiplies in place into a reused `float[16]` scratch. translate/rotate write their factor
     into a second scratch. The stack becomes a fixed `float[MAX_DEPTH][16]` with an index, so `push`
     copies with `System.arraycopy` and allocates nothing.
  2. Each ModelCosmetic owns one reused `Mat` (render thread only). CapePhysics keeps static
     `relP/relR/cumR` and pose scratch.
  3. Emit cache: inside one frame, wearers with the same model, frame, fit and motion inputs reuse the
     posed vertex array. That needs a key check; skip it if the hit rate is low.
  4. Re-run `cosmetics` and confirm the allocation delta goes to about 0 and the p50 delta drops.

### 2. Menu Blur: blur once while the world is frozen (candidate G)

- **Measured:** menu +5.5 ms and pause +5.7 ms p50 (llvmpipe fill-bound; the section time is not
  usable).
- **Expected win:** up to the measured 5.5-5.7 ms (llvmpipe) on frozen-world screens. On a real GPU
  the win is smaller, but it is per frame for as long as any in-world menu is open.
- **Risk:**
  - Visuals: in singleplayer pause the world is frozen, so a cached blur looks identical.
  - Multiplayer: the world keeps moving, so the per-frame blur must stay. Only the reflection fix
    applies there.
- **Files:**
  - `mods/MenuBlur.java:89-135` `blur()`: rebuilds a pyramid of `1 + log2(radius)` levels every
    frame.
  - `:61-70` `handles()`.
  - `render/OriginScreenRenderer.java:561` ensurePyramid, `:599` drawFullscreenTexture.
  - `shaders/IrisBridge.java:91-112`: `staticField` calls getDeclaredField + setAccessible on every
    call, from `handles()` every frame.
- **Design:**
  1. When `mc.isGamePaused()` (singleplayer, any pausing screen), keep `PYRAMID[0]` after the first
     full blur and re-composite only that texture. Invalidate it on resize, screen change, strength
     change or unpause.
  2. Otherwise keep today's path.
  3. Cache the `Field` in IrisBridge (resolve it once, keep it in a static field).
  4. Re-run `menu,pause`.

### 3. HUD draw batching: one Tessellator draw for runs of fills (candidates B + C)

- **Measured:**
  - Default HUD (compass only): 0.17 ms and 2.2 KB/frame.
  - All elements on: onOverlayPost 2.09 ms and 234 KB/frame, with 159 Origin Tessellator draws and
    34 text draws per frame.
  - fps + graph 0.32 ms, keystrokes 0.25 ms, itemcounter 0.16 ms.
- **Expected win:** most of the draw-call share of those numbers, which is at most 0.17 ms by default
  and at most about 1.4 ms with everything on except the minimap (see pick 4).
- **Risk:**
  - Visuals: draw order must stay exact. Flushing before any text or texture draw keeps it exact.
  - Multiplayer: none.
- **Files:**
  - `gui/Gfx.java:230` fillF, which does begin/draw plus GL state changes for every fill.
  - `hud/Readouts.java:623` renderDirection: about 9 separate fills plus 5 texts.
  - `hud/HudElements.java`: `:220` frameGraph (one fill per bar, 90 or more), `:235` cpsText (builds
    strings every frame), `:1070` renderAll / `:1085` measure (text built twice per frame). These are
    main's line numbers.
  - `gui/OriginUi.java:73` panel.
- **Design:**
  1. Add `Gfx.batchBegin()` / `batchEnd()`. While batching, fillF appends quads to one open
     `POSITION_COLOR` buffer; state is set once.
  2. Any text or texture draw, or `batchEnd`, flushes first, so overlap order is unchanged.
  3. `renderAll` wraps each element in a batch. frameGraph emits its bars into one batch.
  4. Element strings that change at most 4 times a second (fps, cps, clock) are cached together with
     their width, so measure and render share them.

### 4. Minimap: stop allocating per scanned block

- **Measured:** 0.64 ms and **181 KB/frame**, by far the largest HUD allocator.
- **Expected win:** most of the 181 KB/frame; the 0.64 ms falls by the scan's share. The split
  between scan and draw is not measured, but the scan is the likely source (see Files).
- **Risk:**
  - Visuals: none (same colours).
  - Multiplayer: none (it reads the client world).
- **Files:**
  - `minimap/MinimapData.java`: `:244-314` update (6 scans, 1 ms budget per frame, `:67-68`),
    `:72` refresh near 1 s / far 6 s re-scans every loaded chunk, `:359` scan, `:446` surfaceColumn.
  - `new BlockPos` per column and per y step at `:413/451/456/468/490`.
- **Design:**
  1. Use one `BlockPos.MutableBlockPos` per scan, passed down to surfaceColumn.
  2. Re-measure. If the scan still dominates, skip re-scanning a chunk whose top-block hash is
     unchanged, or lengthen the far refresh.
  3. A section around `update` vs `drawMap` confirms the split first.

### 5. Per-frame flag snapshots: particles and `Mods.on` (candidate D)

- **Measured:** about 2,900 filter calls/frame at 23-29 ns, so about 65-80 us/frame in heavy
  particles. Frame A/B is within noise (14.6 vs 14.8 fps; alloc 47.0 vs 46.7 MB/s).
- **Expected win:** at most about 0.08 ms/frame in particle-heavy scenes. This pick is low priority:
  do it only alongside another change to these files.
- **Risk:**
  - Visuals: none.
  - Multiplayer: none.
- **Files:**
  - `mods/ParticleFilter.java:86` globalScale and `:102` hidden.
  - Callers `mixin/world/ParticleScaleMixin.java:34` and `ParticleSpawnMixin.java:39`.
  - `mods/Mods.java:1311` `on()`: a JSON lookup plus byId on every call.
- **Design:**
  1. ParticleFilter caches its scale and its hidden-id bitset once per frame (compare a frame
     counter), so each call becomes a field read.
  2. `Mods.on` gets a per-mod boolean cache that `Mods.set` and config reload clear.

## Dropped

- **Tick handlers:** about 58 us per tick across all of them. They run per tick (20/s), not per
  frame, so at real frame rates they are well under 0.1 ms per 50 ms. The large maximum values are
  preemption.
- **RenderWorldLast handlers with features off:** about 27 us/frame, low value.
- **Entity Distance mixin (`shouldRender`):** 0.36 us/frame, nothing to gain.
- **Nametag mixin:** 13-21 us for 16 players, nothing to gain. Players without cosmetics: A/B
  47.5 vs 46.9 ms is noise.
- **Particle allocation:** there is no A/B allocation difference (47.0 vs 46.7 MB/s). The allocation
  is vanilla/OptiFine's, not Origin's.
- **E (entity occlusion) and K (Patcher-style knobs):** these are new optimisations of vanilla, not
  Origin's own cost. No bench scene measures them, and llvmpipe would mostly measure software raster.
  They are left for a real-GPU test (PresentMon on Will's PC, with his go), not ranked here.

## B-12 Esc menu pixelation

The pause screenshot at 1280x720 (40190206267, origin run 1, zoomed crop) shows a smooth blur with no
pixel grid. The 2x-step upsample from 268e8174f looks effective at that size.

Not proven: Will's resolution and GUI scale. Blur is also skipped when shaders or Fast Render are on.
**Will to check:** open Esc in a world at his usual resolution and GUI scale and say whether the
background is still blocky.

## B-04 shader lag: is any of it Origin's?

The bench has no shader-pack variant, and adding one would not answer the question: an OptiFine
shader pack on llvmpipe is software-rendered deferred lighting, and its cost would swamp any Origin
number.

What the data does say:

- Menu Blur steps aside whenever shaders are active (`MenuBlur.handles` -> `IrisBridge.shadersActive`).
- Origin's per-frame CPU cost with default mods is about 0.3-0.36 ms of handlers. That cost does not
  depend on shaders.
- The cosmetics cost (pick 1) is the only Origin cost that grows with the scene, and it would also
  hit shader frames (cosmetic geometry likely also goes through the shader shadow pass; unverified).

So the shader lag is OptiFine plus the pack on his GPU, plus Origin's fixed ~0.3 ms and any
cosmetics in view. Proving that needs a real-GPU A/B on Will's PC (probe vs origin with the same
pack).
