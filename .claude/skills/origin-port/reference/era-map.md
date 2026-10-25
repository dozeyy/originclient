# Era map: every live module

These sources win over this file when they disagree:
- `src/mods/live-modules.json`: which modules ship, plus each module's Java.
- `PerformanceModCatalog.Data.cs`: the Sodium and Iris versions that ship.
- Each module's `gradle.properties`: the build pins.

Snapshot taken 2026-10-20 from origin/main @ a77356180. Re-read live-modules.json before trusting the module list.

## Common to every Fabric module
- Mappings:
  - `loom.officialMojangMappings()` (Mojmap) on 1.16.5–1.21.11.
  - 26.x: no mappings line at all (unobfuscated). Plugin `net.fabricmc.fabric-loom`, no refmap.
- `loom_version=1.17-SNAPSHOT` in the sampled modules (1.16.5, 1.21.1, 26.2).
- The source-set layout differs by era:
  - 1.17.1 and 1.16.5 have no `splitEnvironmentSourceSets`; client code is in `src/main`. It fails below 1.18 (proven).
  - Every other module uses `src/client`.
- `fabric.mod.json` depends key: `fabric` (not `fabric-api`) below 1.18.

## Module table

| Group | Module | Covers | Java | Render / GUI backend | Sodium · Iris shipped | JEI (Fabric) | Donor (proven order) |
|---|---|---|---|---|---|---|---|
| G9 | 1.8.9 | 1.8.9 | 8 | Forge 11.15.1.2318, MCP stable_22, gg.essential.loom 1.3.12, Gradle 8.7, Mixin 0.7.11 shadowed in; fixed-function GL, foundation `gui.Pose` over the GL matrix stack; LWJGL 2 | none: OptiFine HD_U_M5 | none | 1.16.5 (type map in PORT-189.md) |
| G8 | 1.16.5 | 1.16.5 | 8 | Gfx wrapper over PoseStack; FIXED FUNCTION (no core shaders, alpha test GREATER 0.1, color4f, fillLayer) | 0.2.0 · 1.4.5 | none exists | ported 1.17.1 tree + 3-way merge, then java8.py |
| G8 | 1.17.1 | 1.17.1 | 16 | Gfx; core shaders, no CoreShaderRegistrationCallback; Gson 2.8.0 | 0.3.4 · 1.2.7 | none exists | ported 1.18.2 tree + asserted deltas |
| G7 | 1.18.2 | 1.18.2 | 17 | Gfx; pre-JOML `com.mojang.math.Matrix4f`; `BufferBuilder.end()` void + `BufferUploader.end(bb)`; widget x/y public fields | 0.4.1 · 1.6.11 | 10 (`common.*` pkgs) | 1.19.4 |
| G7 | 1.19.2 | 1.19.2 | 17 | Gfx; pre-JOML; renderButton; no enableScissor | 0.4.4 · 1.6.11 | 11 | 1.19.4 |
| G7 | 1.19.3 | 1.19.3 | 17 | Gfx; JOML + Axis + Button.builder from here; GridWidget layouts; renderButton | 0.4.9 · 1.5.2 | 12 | 1.19.4 |
| G7 | 1.19.4 | 1.19.4 | 17 | Gfx; renderWidget, isHovered(), LogoRenderer, debugQuads, enableScissor | 0.4.10 · 1.6.11 | 13 | 1.21.1 (Gfx regen) |
| G2 | 1.20 | 1.20–1.20.1 | 17 | GuiGraphics immediate; Tesselator `bb.vertex().endVertex()` + `BufferUploader.drawWithShader(bb.end())`; no blur API; icons.png; 3-arg mouseScrolled | 1.20: 0.4.10 · 1.6.4; 1.20.1: 0.5.13 · 1.7.6 | 14.0.0.11 | 1.21.1 + 1.20.1 deltas |
| G1 | 1.20.2 | 1.20.2 | 17 | GuiGraphics; sprite atlas + blitSprite from here; SpriteIconButton; ImageButton title icons; no setHeight | 0.5.5 · 1.6.14 | 16.0.0.2 | 1.21.1 near-verbatim |
| G1 | 1.20.4 | 1.20.4 (live-modules says 1.20.4) | 17 | GuiGraphics; setHeight, ScoreHolder, renderWidget on lists from 1.20.3 | 0.5.8 · 1.7.2 | 17.3.1.5 | 1.21.1 near-verbatim |
| G1 | 1.21 | 1.21 | 21 | same API era as 1.21.1 | 0.5.11 · 1.7.3 | 19.27 | wholesale copy of 1.21.1 (keep own fabric.mod.json + FireworkStarterMixin) |
| — | **1.21.1** | 1.21.1 | 21 | **design truth**; GuiGraphics immediate | 0.6.13 · 1.8.8 | 19.27 | — |
| G3 | 1.21.4 | 1.21.2–1.21.4 | 21 | blit rework + RenderPipeline; GUI batched (never setShaderColor, pass per-blit ARGB); getFov returns Float | 1.21.2: 0.6.1 · 1.8.0; 1.21.3: 0.6.8 · 1.8.1; 1.21.4: 0.6.13 · 1.8.8 | none exists | 1.21.5 template + 1.21 HitboxMixin |
| G4 | 1.21.6 | 1.21.6–1.21.7 | 21 | deferred GuiRenderState; Matrix3x2fStack; no setShaderColor; strata, blur once per frame | 0.7.3 · 1.9.6 | none exists | 1.21.8 (byte-identical tree) |
| G4 | 1.21.8 | 1.21.8 | 21 | as 1.21.6 | 0.7.3 · 1.9.6 | none exists | 1.21.6 / 1.21.1 |
| G5 | 1.21.10 | 1.21.10 | 21 | deferred GUI + world extract/submit; MouseButtonEvent/KeyEvent + typed KeyMapping.Category (1.21.9+); Fabric `.world` WorldRenderEvents | 0.7.3 · 1.9.7 | 26.3.0.31 | 1.21.11 minus the rename wave (backmap) |
| G5 | 1.21.11 | 1.21.11 | 21 | 1.21.10 + rename wave (Identifier, rendertype.RenderTypes, renderContents, getSamplerCache), gizmos, LineWidth vertex element, FINAL AbstractButton.renderWidget | 0.8.12 · 1.10.7 | 27.17.0.50 | 1.21.10 |
| G6 | 26.1 | 26.1/26.1.1/26.1.2 (one jar, built vs 26.1.2) | 25 | GuiGraphicsExtractor, extract*; `Gui` is still the HUD; `mc.screen`, `mc.setScreen` | 26.1.2: 0.9.2 · 1.11.4; 26.1/26.1.1: 0.8.9 · 1.10.9 | 29.37.0.100 (pinned: 29.40+ nests mezz_config = 26.1.2 only) | 26.2 |
| G6 | 26.2 | 26.2 | 25 | extract GUI; `Hud` class (`mc.gui.hud`), `mc.gui.screen()` / `mc.gui.setScreen`; COLLECT_SUBMITS world overlays | 0.9.2 · 1.11.4 | 30.42 (split drawForScreenBackground/Foreground) | 1.21.11 + PORTING-ERA-NOTES "26.x era" |
| G6 | 26.3 | 26.3 | 25 | 26.2 + SDL3 input (no GLFW), `com.mojang.renderpearl.api.*`, separate-shader-objects GLSL | 0.9.2 · 1.11.7 | 31.14.0.72 | 26.2 |

Pulled (modules kept, out of the picker and CI): 1.21.5, 1.12.2
1.21.9: pulled entirely, no module. VERSIONS.md's line that
"1.21.11 covers 1.21.9" is wrong per memory, and 1.21.9 is pulled. VERSIONS.md's live table also
lacks rows for 1.20.2, 1.21.4 and 1.21.6. live-modules.json is the authority.

## Era cliffs (the boundaries every recipe keys off)

- **Pre-1.19 / 1.19.3 / 1.19.4** (the Gfx group): widget, matrix and buffer APIs shift at each step.
  Rows are in PORTING-ERA-NOTES "Pre-1.20 Gfx era".
  - `mc.getFps()` exists from 1.19.3. Use MinecraftFpsAccessor on ≤1.19.2.
  - PlayerFaceRenderer exists from 1.19.1. ≤1.18.2 blits face (8,8) and hat (40,8) by hand.
  - TextComponent and no OptionInstance below 1.19; Options fields are plain.
  - `new Matrix4f()` before JOML is all zeros.
  - A buffer begun and never drawn shifts later draws (pre-1.19).
- **1.20.2**: blitSprite("widget/button") and the sprite atlas. Before it: widgets.png 9-slice and icons.png.
  - PlayerSkin and `Minecraft.getGameProfile()` + `SkinManager.getInsecureSkin` compile on 1.20.2+ (build-check 132).
  - renderBackground is 1-arg on 1.20 and 4-arg on 1.20.2+.
- **1.20.3**: setHeight, ScoreHolder, list renderWidget.
  - GenericDirtMessageScreen until 1.20.5; renderNameTag has 5 params before 1.21.
- **1.21.2**: blit rework, batched GUI (tint per blit), RenderPipeline, getFov returns Float.
  - addWeatherPass / addSkyPass / FramerateLimitTracker.
  - Never draw font text during the initial reload (boot wedge, proven on 1.21.4).
- **1.21.5**: HitboxRenderState (renderHitboxes(state)).
- **1.21.6**: deferred GUI. Matrix3x2fStack; setShaderColor removed.
  - About 130 draw calls per AA panel; bake masks (195→724 FPS on the mod list).
  - renderBackground runs BEFORE render.
  - Blur once per frame via `nextStratum()`. Guard `GuiRenderState.firstStratumAfterBlur != Integer.MAX_VALUE`, because "Can only blur once" throws before any state change.
- **1.21.9**: MouseButtonEvent / KeyEvent / CharacterEvent, typed KeyMapping.Category.
- **1.21.10**: world extract/submit; HitboxFeatureRenderer.
  - ClientLevel.addDestroyBlockEffect / addBreakingBlockEffect.
  - Block-outline interactionShape is null.
  - No `menu.loadingLevel`. A bare LevelLoadTracker has no progress until `start(Stage, n)`.
- **1.21.11**: the rename wave.
  - Gizmos (EntityHitboxDebugRenderer.emitGizmos, loaded lazily); setLineWidth required.
  - AbstractButton.renderWidget is final (use the whitelist).
  - No projection matrix (rebuild it from camera + FOV).
  - Duck interfaces go OUTSIDE the mixin package (`client.ext`) or IllegalClassLoadError.
- **26.1**: GuiGraphicsExtractor; extract runs BEFORE the world is drawn (framebuffer reads see last frame).
  - Pipelines are derived from vanilla ones (`OriginShaders.derive`).
  - World overlays are submits on COLLECT_SUBMITS.
  - Item stacks can't be built before a world loads.
- **26.2**: Hud split from Gui; `mc.gui.screen()`; TeamColor; RenderType.prepare().
- **26.3**: SDL3, renderpearl api, explicit-location GLSL.
  - Pipelines can't be built under the loading overlay (`OriginShaders.sourcesPending()`).
  - CI needs `SDL_VIDEO_FORCE_EGL=1`.

## Where the deep notes are (grep the heading; line numbers drift)
| Era | `src/mods/docs/PORTING-ERA-NOTES.md` heading |
|---|---|
| process, merge method | `## Process` |
| 1.20 / 1.20.2 / 1.20.4 | `## GuiGraphics era, pre-1.21`, `### 1.20.2 / 1.20.4` |
| 1.21.4 / 1.21.6 / 1.21.8 / 1.21.10 / 1.21.11 | `## 1.21.x RenderPipeline era` |
| 1.19.x → 1.16.5 | `## Pre-1.20 Gfx era` |
| fix rounds per era | `## Fix-round port (2026-10-12) — <modules>` |
| social per era | `## Social port (2026-10-12) — <modules>` |
| 26.x | `## 26.x era`, `## 26.1.x`, `## 26.3` |
| 1.8.9 | `src/mods/versions/1.8.9/PORT-189.md` (toolchain, hooks, type map, keys, ref-189) |
