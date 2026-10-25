# Origin 1.8.9 performance audit + benchmark plan (read-only)

Audited code: **origin/main @ de6b3eb6a** (detached worktree `scratchpad/audit-wt`). The local `main`
checkout is stale (480 commits behind), so findings first made there were re-checked on origin/main.
Every hot spot below is marked **likely** because it comes from reading the code. Nothing was run, launched
or measured. No numbers in this file are measurements.

---------------------------------------------------------------------------------------------------
## 1. Current 1.8.9 stack (origin/main)

| Layer | What ships | Where |
|---|---|---|
| Loader | Forge 11.15.1.2318, LaunchWrapper, Mixin 0.7.11 shadowed into the Origin jar, 6 mixin configs, all `defaultRequire 0` | `src/mods/versions/1.8.9/build.gradle:22-45,70-77`, `src/OriginLauncher.App/Core/Loaders/LegacyForgeInstaller.cs:26-30` |
| Renderer/shaders | OptiFine **HD_U_M5**, from a SHA-pinned mirror chain | `Core/Loaders/OptiFineInstaller.cs:35-41` |
| Extra perf mods | TexFix 4.0 and BetterFps 1.2.0 (CurseForge CDN pins) | `Core/Loaders/LegacyStackInstaller.cs:28-31` |
| Java | **Eclipse Temurin 8u504-b01 JRE x64** (pinned, SHA-256-verified). It falls back silently to Mojang `jre-legacy`. | `Core/Runtime/TemurinJava8.cs:22-27`, `Core/Versions/VersionManager.cs:733-738` (commit c4d67dfb5, 2026-10-20) |
| Mojang fallback | `jre-legacy` = **8u51** on windows-x64 and x86 (cacerts refreshed 2025-10-06), **8u202** on linux | Mojang java-runtime manifest `all.json`, fetched today |
| Heap | Auto: min(half of RAM, **4 GB**), or 6 GB when the instance is "heavy" (25+ mods, any shader pack, or 256 MB+ of resource packs). `-Xms == -Xmx`. Manual is used as-is; above 6 GB it shows a warning only. | `Core/RamAdvice.cs:18-33`, `Core/Launch/HeapPlan.cs`, `Core/Launch/LaunchHeap.cs` |
| GC flags | Aikar's G1 set, resized for legacy heaps up to 6 GB: G1NewSize 25 / Max 35 %, 4 M regions, **MaxGCPauseMillis=200**, IHOP 15, MaxTenuringThreshold=1, DisableExplicitGC, no AlwaysPreTouch | `Core/Launch/JvmArgPresets.cs` (FlagsFor, LegacySmallHeapFlags) |
| OptiFine settings | **None seeded.** The launcher only copies or imports `optionsof.txt`, so a fresh player runs OptiFine's defaults. | `Core/GameFolders/AccountSeed.cs:18-21`, `Core/Import/SettingsMigrator.cs:710` |

Notes:
- The JVM preset comment explains the legacy resize. A real 2026-10-20 log at 12 GB showed a young generation of 3.6 GB or more.
- I found no A/B comparison of Temurin 8u504 against Mojang 8u51 in MEMORY.md or the commit. **The runtime switch is unmeasured.**
- CI runs 1.8.9 **without OptiFine, TexFix or BetterFps** (`PORT-189.md:77`; dev-shots and boot-smoke boot the dev game). CI's JDK 8 is foojay-provisioned, so it is not the Temurin 8u504 Windows JRE either.
  Any CI number therefore comes from a different stack than players run, until a production-like 1.8.9 instance exists in CI.
- `boot-time.yml` and `tools/boot-time` have no 1.8.9 path (no mention of it). `LaunchTimingHarness.cs:97` keys off Fabric's "Loading N mods" line, which Forge never prints.

### Origin's per-frame and per-tick hooks on 1.8.9
| Hook | Work |
|---|---|
| `RenderTickEvent` START | HudHooks: FrameStats ring, PerfSettings.refresh, FrameWatch. WorldHooks: zoom ease, HitColor/Glint sync. SocialHooks: newFrame. ServerHooks: Bedwars hearts reset. |
| `RenderTickEvent` END | FpsCoach.frame, FpsCoachToast, SocialToast.renderAll |
| `RenderGameOverlayEvent.Pre/Post` | ColorGrade (ALL), crosshair, boss bar, tab list, saturation, locator bar, **HudElements.renderAll** (every enabled element), party markers, waypoint HUD |
| `RenderWorldLastEvent` | chunk borders, waypoints, WorldEdit CUI, Social/Party world pass, **CosmeticEffects**, Team View |
| Per-entity / per-particle mixins | RenderManager.shouldRender (Entity Distance), renderTileEntity (TE distance), renderLivingLabel (nametags), **EntityFX.renderParticle (particle scale)**, LayerCape (cape physics), glint, hit colour, item physics, TNT countdown |
| `ClientTickEvent` END | Stopwatch, Ping, CombatStats, Minimap keys, PortalCalc, MotionBlur, Snaplook, WorldEditCui, Social.tick, ThemeManager, FpsCoach.tick, menu hooks |

---------------------------------------------------------------------------------------------------
## 2. Ranked hot spots (likely, unmeasured)

Ranked by expected share of frame time for a PvP player on a low-end PC. #1 is not Origin code, but it is
the biggest lever Origin controls.

1. **OptiFine and vanilla defaults (no seeded perf settings).**
   - A fresh 1.8.9 install runs OptiFine's defaults: Fast Render off, Smooth FPS, Render Regions, animations, Dynamic Lights and others.
   - Origin writes no `optionsof.txt`, and vanilla `options.txt` is copied, not tuned.
   - Lunar's own FPS page tells 1.8.9 players to change exactly these: Fast Render on, Chunk Updates 1, Smooth FPS off, Fast Math on, and more ([Lunar support](https://support.lunarclient.com/solutions/how-to-fix-fps-issues-in-lunar-client)).
   - Likely the largest single win, and zero code risk inside the renderer.

2. **HUD draw-call count, in immediate-mode style.**
   - `gui/Gfx.java:230-262`: every `fill` is its own Tessellator begin and draw, plus about 8 GlStateManager calls.
   - `gui/OriginUi.java:71-82,287-316`: each rounded panel is about 3 band fills, plus 4 AA corners with one fill per alpha run per row. That is roughly 10-20 draws per panel at radius 1-2.
   - `hud/HudElements.java:220-233`: **the FPS frame graph draws one fill per column, at least 90 (GRAPH_MIN_W) separate draws per frame** when "Show Graph" is on.
   - Text goes through vanilla `FontRenderer`. 1.8.9's `renderDefaultChar` is one glBegin/glEnd per glyph. *Likely* also under OptiFine M5; not checked against the M5 jar.
   - Each element is measured, then rendered: `HudElements.java:1061-1081` runs `measure()` (an `int[]` allocation plus string widths) and then `render()`.
   - `HudElements.java:129-131` builds a `new HudPos` on every `pos()` call.
   - All of this is driver and CPU overhead that grows with FPS. It hurts most at the 200-400 fps PvP players run.

3. **Per-particle config lookups.**
   - `mixin/world/ParticleScaleMixin.java` calls `ParticleFilter.globalScale()` for **every particle, every frame**, even with Particle Changer off.
   - `mods/ParticleFilter.java:86-92` calls `Mods.on`, which goes to `raw()`: ensureLoaded, then migrateOnce, then `HashMap.computeIfAbsent`, then a Gson `get` (`mods/Mods.java:1492-1496`).
   - Crit-heavy fights put thousands of particles on screen.
   - With the mod on, the spawn path `ParticleFilter.hidden` (`:102-150`) builds 5-8 strings per spawn (`"p_" + path + ...`).
   - `PerfSettings` (`mods/PerfSettings.java`) already fixed this exact pattern for entity distance and text shadow. Particles were left out.

4. **Menu Blur behind inventories, default ON.**
   - `mods/Mods.java:802-805` defaults Inventories and Pause to true.
   - `mods/MenuBlur.java:89-120` runs a full-frame downsample/upsample pyramid every frame, 3 levels at strength 4, while a container is open. The world keeps rendering behind it.
   - PvP refills happen with the inventory open, so this is a GPU fill-rate cost on integrated GPUs. Lunar's own guidance lowers this kind of effect; its exact blur behaviour is unknown.

5. **Cosmetics per frame, scaling with Origin wearers in view.**
   - `cosmetics/CosmeticEffects.java:~153` loops over all `world.playerEntities` every frame.
   - `cosmetics/ModelCosmetic.java:~305-360`: the `Mat` helper allocates a `float[16]` per translate/rotate per bone per frame.
   - `cosmetics/CapePhysics.java:156-158` allocates 3 `float[N]` per cape per frame.
   - Cheap with few wearers. It becomes GC pressure in a lobby full of Origin players, a future state.

6. **Nametags in lobbies.**
   - `mixin/world/NametagLabelMixin.java` still makes 9 `Mods.*` lookups per tag per frame.
   - The `getFormattedText` allocation is already lazy (commit fc88c86ca). At 100+ players this is lookups, not allocation.

7. **Menus (not in-game).**
   - `render/MenuPanorama.java` draws vanilla's panorama: 64 cube draws plus 7 blur passes into a 256 px texture, every frame.
   - The menu FPS cap is raised from vanilla's 30 to **120** (`Mods.java:101`, `MinecraftFramerateMixin`).
   - Film grain adds about 40 blits at 1080p (`render/OriginScreenRenderer.java:880-900`).
   - There is a `glGetInteger(GL_FRAMEBUFFER_BINDING)` per frame (`MenuPanorama.java:60`).
   - This matters for laptop heat and noise on the title screen, not for in-game FPS.

Unaudited: the network and IO paths (DiscordPresence, HypixelApi, SocialClient) were not checked for render-thread blocking. The OriginTtfFont atlas build and startup cost were not checked either.

---------------------------------------------------------------------------------------------------
## 3. Existing perf work (origin/main)

**Settings > Performance** (`mods/Mods.java:98-107`):
- Limit Unfocused FPS. When on, `FrameWatch.idle()` also skips Origin's HUD and sparkles.
- Max Unfocused FPS.
- Max Main Menu FPS (default 120).
- Entity Distance % and Tile Entity Distance %.
- Pack Icon Downscale (`PackIconMixin`, on by default).
- Disable Text Shadow (`FontShadowMixin`, off by default).

**Already in place:**
- `PerfSettings`: a once-per-frame snapshot of the hot settings.
- Coordinates lines rebuilt at most 20 times a second.
- A lazy nametag name-line.
- A cached GUI scale.

**Measurement tooling already present:**
- `hud/FrameStats.java`: a 512-frame ring with no allocation, 1% low, and spike classification. A spike is checked against a stop-the-world GC overlap through the MXBeans.
- The FPS HUD frame graph.
- **FPS Coach** (`mods/FpsCoach.java`): measures 4 s before a change and 8 s after it, in the same spot, then offers Keep or Undo.
- Particle Changer (Reduced/Off modes).
- CI already has the pieces a bench mode can reuse:
  - `OriginDevWorld` (`ORIGIN_SHOTS_SET=world`) creates and drives a world headlessly.
  - `boot-time.yml` gives an alternating A/B pattern with JFR.

**Paused worktree** `.claude/worktrees/agent-af2df8266a0d6b8aa`:
- `git status --short`: clean. `git diff --stat`: empty.
- HEAD is fc88c86ca "1.8.9 perf: cached GUI scale, coords at 20 Hz, lazy nametag name-line, Pack Icon Downscale, Disable Text Shadow".
- It is **0 ahead / 45 behind origin/main**, so it is already merged. Nothing is left to resume there.

**Branches `claude/shader-speed` (5900de317) and `release/shader-speed` (3eec248d6):**
- Both are 0 ahead of origin/main, so fully merged.
- Their content is Iris half-rate shadows (1.21.1 and 26.x). That does not apply to 1.8.9 or OptiFine.

**Open item** (memory `paused-playtest-backlog-2026-10-20`): Will's "1.8.9 massive performance improvement, research how Lunar gets it" is still open. This audit is that research step.

---------------------------------------------------------------------------------------------------
## 4. Research: how the PvP clients get 1.8.9 performance

Sources gathered by a researcher sub-agent plus my own fetches. "Unknown" means no source was found.

**Lunar**
- Its support FPS guide covers mostly OptiFine settings plus its own toggles:
  - HUD Caching, which needs Fast Render **off** (the page contradicts its Fast Render on advice).
  - Turbo Nametags (1.8 only).
  - Particle Physics off.
  - Entities and Tile Entities at Lowest.
  - Lowered Max Unfocused and Main Menu FPS.
  - RAM: 3 GB on 8 GB PCs, 4 GB on 16 GB, 6 GB at 32 GB or more. It warns that too much RAM makes GC pauses longer. ([support.lunarclient.com](https://support.lunarclient.com/solutions/how-to-fix-fps-issues-in-lunar-client))
- Lunar ships an **Azul Zulu** runtime in `.lunarclient\jre` ([Lunar support: integrated graphics](https://support.lunarclient.com/support/solutions/articles/60000714758-lunar-client-using-integrated-graphics)).
- A community page says "Lunar Client requires a Java version of 16 or above" ([Java-Optimisations-MC](https://github.com/Youded-byte/Java-Optimisations-MC)). That is not official.
- **Lunar is not Forge-based**, so its loader and newer-Java approach does not transfer to Forge 11.15.1.2318.
- Renderer internals: unknown.

**Badlion**
- OptiFine plus its own "Betterframes"; no detail published ([badlion.net/faq](https://www.badlion.net/faq)).

**Feather**
- Parts were published as "FeatherOpt" (caching, BlockPos pooling).
- These are ported by MicroOptimizations, which is LGPL-2.1, marked "(Broken)" and "causes issues" ([modrinth](https://modrinth.com/mod/microoptimizations)).

**Patcher / PolyPatcher**
- Licence: CC BY-NC-SA 4.0. **Reimplement the ideas; never copy the code.**
- Perf features ([Patcher README](https://github.com/Sk1erLLC/Patcher), [modrinth](https://modrinth.com/mod/patcher)):
  - Entity culling with a check interval, auto-off with OptiFine shaders.
  - Back-face culling.
  - Hiding armor stands, item frames, skulls and similar.
  - Per-type entity render distance.
  - A particle cap and static particle colours.
  - 64 px pack icons (Origin already has this).
  - Batched model rendering.
  - An **optimized font renderer** with a font/string-width cache.
  - A chunk update limiter.
  - A lower animation tick rate.
  - Experimental HUD caching.
  - Faster world swapping.

**EntityCulling**
- The 1.8.9 port is listed as outdated or unsupported. Mainline uses the tr7zw Protective License, so do not reuse it.

**FoamFix / BetterFps / MemoryFix**
- No authoritative 1.8.9 evidence was found beyond a hosting-vendor blurb for FoamFix 0.6.3.

**Sodium-lineage ports**
- Celeritas covers 1.7.10 and 1.12.2 only ([gitea](https://gitea.com/Jerry-main/Celeritas)). Nothing exists for 1.8.9, so OptiFine stays the renderer.

### Java runtime question
- **1.8.9 with Forge needs Java 8.**
  - LaunchWrapper casts the system class loader to `URLClassLoader`, which fails on Java 9 and later.
  - Mojang closed that as "won't support versions later than Java 8" ([Mojang/LegacyLauncher#20](https://github.com/Mojang/LegacyLauncher/issues/20)).
  - The only maintained Java 17+ path is lwjgl3ify (GTNH), and it targets **1.7.10** with a replaced bootstrap and LWJGL3 ([github](https://github.com/GTNewHorizons/lwjgl3ify)). No 1.8.9 equivalent was found.
  - Mixin 0.7.11 on Java 9 and later is unproven.
- **Benchmarks:** no controlled Java 8 vs 17 numbers exist for 1.8.9. JVM-vendor guides call the Java 8 builds "basically identical" across vendors ([brucethemoose guide](https://github.com/brucethemoose/Minecraft-Performance-Flags-Benchmarks)), with no numbers. No Zulu 8 vs Temurin 8 data was found.
- **GC flags:**
  - The same guide reports Aikar's flags causing "long stutters on some clients".
  - It suggests G1, `-Xms` equal to `-Xmx`, and `MaxGCPauseMillis` around 37 for clients.
  - ZGC does not exist on Java 8. Shenandoah exists only in Red Hat's OpenJDK 8 builds.
- **Recommendation:** stay on Java 8, which Temurin 8u504 already gives.
  - Do **not** pursue Java 17 for Forge 1.8.9. It would be a loader rewrite with no evidence of an FPS gain.
  - Prove the Temurin switch with an A/B against Mojang 8u51 on Will's PC.
  - Separately A/B the GC pause target. 200 ms is lenient for 3-7 ms frames; try about 30-50 ms and compare young-GC pause max and 1% lows.
  - Treat Zulu 8 as optional. Without evidence it is a coin flip with Temurin.

---------------------------------------------------------------------------------------------------
## 5. Candidate optimisations

Each expected effect is an **estimate**. Each one gets measured with the section 6 harness and is reverted if it does not move the number.

| # | Change | Expected effect (estimate) | Risk / notes |
|---|---|---|---|
| A | **Competitive/Balanced/Quality presets** that write OptiFine and vanilla settings, plus Origin toggles (section 7) | Largest, especially on low-end. It is what Lunar's own guidance does. | The `optionsof.txt` key names must first be verified from the M5 jar (`javap` on OptiFine's GameSettings `of*` fields, or dump a generated file in CI). Only `ofRenderRegions` is sourced. Fast Render can break Origin's framebuffer effects (MenuBlur falls back, ColorGrade and MotionBlur untested), so it needs a render check. |
| B | **HUD batching:** one `POSITION_COLOR` Tessellator batch per frame for all fills, panels and the FPS graph; text flushed after. Optionally HUD caching (render the HUD to an FBO at 20-60 Hz, blit every frame), Lunar/Patcher style. | Cuts HUD draws from roughly 100-300 to a handful. Matters when driver- or CPU-bound at high FPS. | Caching conflicts with Fast Render (Lunar notes this). Live elements (keystrokes, CPS) need a dirty flag. |
| C | **Font:** a string-width cache, plus a batched glyph renderer replacing per-glyph glBegin, as in Patcher's idea | Text-heavy HUDs, chat and nametags | Check OptiFine M5's FontRenderer patch (HD fonts, custom colours). Reimplement only (licence). |
| D | **Particles:** snapshot `globalScale` and the Particle Changer flags once per frame in PerfSettings; precompute per-type config keys | Removes thousands of map lookups per frame in crit fights | Trivial and low risk |
| E | **Entity occlusion culling:** an async ray or AABB visibility test, auto-off with OptiFine shaders | Large in dense lobbies and Bed Wars bases | Real engineering. Cannot copy EntityCulling or Patcher. |
| F | **Cosmetics:** reuse matrix and cape arrays (no per-frame allocation); cap emitters | GC pressure as wearer count grows | Low risk |
| G | Inventory blur default **off** in Competitive (or capture once on open) | Faster refills on integrated GPUs | Design call for Will |
| H | **GC:** A/B `MaxGCPauseMillis` (200 vs ~40) and the young-gen size, from `-Xloggc` + `-XX:+PrintGCApplicationStoppedTime` | 1% lows, stutter | Measure first |
| I | **Java:** A/B Temurin 8u504 vs Mojang 8u51 | Unknown | Already shipped unmeasured |
| J | **BetterFps 1.2.0:** A/B with and without it (overlaps OptiFine Fast Math; its compatibility is unverified) | Unknown; could be negative | Measure |
| K | Patcher-style knobs for Competitive: hide armor stands and item frames, per-type entity distance, animation tick limit, chunk-update cap | Situational | Each is a small mixin |

---------------------------------------------------------------------------------------------------
## 6. Benchmark plan

### Harness: `ORIGIN_SHOTS_SET=bench` (or `-Dorigin.bench=<scenario>`) in the 1.8.9 module
This would be a new `OriginBench` beside `OriginDevWorld`, dev-only and env-gated. No code is written yet.
- **World:** created through `launchIntegratedServer` with a fixed seed and type.
  - Gamerules doDaylightCycle and doWeatherCycle off, time 6000, peaceful except in the entity scenario.
  - Window fixed at 1920x1080 on Will's PC (1280x720 in CI). VSync off, max FPS unlimited (260). GUI scale fixed.
  - Render distance per scenario. FOV and position fixed, camera driven by script (no OS input).
- **Scenarios**, each with a warm-up of 15 s, then a 45 s measurement, then 3-5 runs alternating variants:
  1. `idle`: stand still on fixed terrain, RD 8.
  2. `spin`: a 360-degree yaw sweep every 10 s, RD 8 (frustum, chunk visibility).
  3. `entities`: 200 named armor stands, 100 NoAI zombies, and 30 client-side `EntityOtherPlayerMP` with skins, capes, Origin dev cosmetics and nametags (lobby proxy).
  4. `particles`: `/particle crit` and `magicCrit` bursts of a fixed count every tick (PvP proxy).
  5. `chunks`: teleport along a line at a fixed speed through ungenerated terrain for 30 s. Frame spikes plus chunk-update counts.
  6. `highrd`: the idle scene at RD 16.
  7. `pvp-ui`: open and close the inventory every 2 s, HUD with every element on vs off (blur and HUD cost).
- **Per frame, to CSV** (`$ORIGIN_SHOTS/bench/<scenario>-<variant>-<run>.csv`):
  - Frame ns (RenderTickEvent START delta, the FrameStats source).
  - Accumulated ns in Origin's hooks: HUD, world-last, overlay, the particle/nametag mixins. Gated, nanoTime pairs only in bench mode.
  - Render-thread allocated bytes (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`; HotSpot 8 has it).
  - Once a second: GC count, time and max pause per collector (MXBeans), heap used, entities rendered, chunk updates.
- **Summary JSON:** avg FPS, median and p99 frame time, **1% low** (FPS of the slowest 1% of frames), 0.1% low, frame-time stdev, GC pause total and max, allocation MB/s.
  A summarizer like `tools/boot-time/summarize.py` would report the median across runs with min and max, and the relative delta between variants.
- **Startup:** FML marks (JVM start, then "Forge Mod Loader has successfully loaded", then the first title draw) printed as `[OriginBoot]` lines. Add a 1.8.9 path to `boot-time.yml`.
- **Variants** via env/system properties: no Origin jar (OptiFine baseline), Origin default, Origin plus Competitive, Java A/B, GC A/B, BetterFps on/off.

### What headless CI (Linux, Xvfb + Mesa llvmpipe, 4 vCPU) can and cannot tell us
- **Can (relative A/B on one runner, alternated):**
  - Origin's own CPU cost per frame (hook ns).
  - Allocation rate and GC pauses (with `-Xloggc`, or JFR on Temurin 8u262 and later).
  - Client tick cost, startup time, heap after world load.
  - Regressions, as a relative delta.
- **Cannot:**
  - GPU-bound FPS: llvmpipe rasterizes on the CPU and steals the same cores.
  - Real AMD/Intel driver per-draw overhead.
  - Shader-pack cost, present and VSync behaviour, input latency, absolute FPS or 1% lows of any player's PC.
  - Never quote CI FPS as a player number.
- **Stack gap to close first:** CI must run a **production-like 1.8.9 instance**: Forge 2318 universal, OptiFine M5, TexFix, BetterFps, the built Origin jar and Temurin 8.
  - Today's dev runs have no OptiFine, so they measure the wrong renderer.
  - This needs a small Python launcher that resolves the Forge version JSON, or a 1.8.9 `prodClient` task.
  - The same job can dump a fresh `optionsof.txt` to verify the OptiFine keys for the presets.

### On Will's PC (RX 9070 XT): opening any window needs Will's explicit OK
- Same harness and scenarios at 1920x1080, plus **PresentMon** for present-to-present frame times as an independent check of the in-mod CSV.
- 3 runs per variant, background apps closed, GPU driver version, OptiFine settings and power plan recorded.
- This is the only source of GPU-bound truth and of real 1% lows.
- Low-end gap: an RX 9070 XT is not a low-end PC. To prove "low-end" claims, ask Will whether a laptop with integrated graphics is available.
  Until then, low-end effects are estimates. CPU-affinity throttling on the desktop is a weak proxy and must be labelled as such.

---------------------------------------------------------------------------------------------------
## 7. Preset feasibility (Competitive / Balanced / Quality)

**Available knobs**
- **Vanilla `options.txt`**, which the launcher's `OptionsTxtFormat` already reads and writes:
  - renderDistance, fancyGraphics, ao, particles (0/1/2), maxFps, enableVsync, useVbo, mipmapLevels, clouds, entityShadows, fboEnable, bobView.
- **OptiFine M5 (`optionsof.txt`, or GameSettings `of*` fields via reflection in-game):**
  - Fast Render, Fast Math, Chunk Updates, Dynamic Updates, Smooth FPS, Render Regions, Lazy Chunk Loading, Dynamic Lights.
  - Animations group, Fog, Sky/Stars/Sun-Moon, Clouds, Trees, Connected/Natural Textures, Better Grass, AA and AF, Show Capes.
  - Key names are unverified except `ofRenderRegions`.
- **Origin:**
  - Entity and Tile Entity Distance, Disable Text Shadow, Menu Blur (Inventories and Pause), Cosmetic Effects, Cape Physics.
  - Particle Changer mode (Reduced/Off), FPS graph, Motion Blur, Color Saturation, Item Physics, Minimap, Max Unfocused and Main Menu FPS.

**Sketch (for Will to approve):**

| Preset | Settings |
|---|---|
| Competitive | Fast graphics, smooth lighting off, RD 6-8, particles Decreased (crits stay), clouds/sky/stars off, entity shadows off, Fast Render + Fast Math on, Chunk Updates 1, Smooth FPS off, Lazy Chunk Loading on, Dynamic Lights off, animations mostly off, Menu Blur off, cosmetic sparkles off, Entity Distance 75 %, TE Distance 50 % |
| Balanced | Lunar-like middle: Fast graphics, smooth lighting Min, RD 10, blur on for pause only |
| Quality | Fancy graphics, smooth lighting Max, RD 12-16, all effects on |

**Feasible:** yes, from the launcher (write both files before launch) or from an in-game Settings row (reflection into OptiFine's fields, guarded by `isOptiFineLoaded`).

**Blockers / decisions:**
1. Origin's rule "One control, one job: no UI handler may mutate a setting other than its own" conflicts with a preset button. FPS Coach's "Try it" is the precedent for a measured, undoable bundle. Will must decide.
2. The OptiFine key names need verifying on the real M5 jar.
3. Fast Render interplay with Origin's framebuffer effects needs a render check.
