# Recipe: Sodium / Iris internals, shadow pass, culling, Video Settings, post effects

Third-party internals change with the **Sodium/Iris version**, not with the Minecraft era. Group by the
stack that ships. Read it from `PerformanceModCatalog.Data.cs`; dev runs load the same jars via
`tools/ci/perf_libs.py`. Every mixin into Sodium/Iris is `remap = false`.

## The stack each module ships (catalog, 2026-10-20)

| Stack generation | Modules (Sodium · Iris) | Package names seen in module code |
|---|---|---|
| S1 old | 1.16.5 (0.2.0 · 1.4.5), 1.17.1 (0.3.4 · 1.2.7) | Sodium `me.jellysquid.mods.sodium` (likely; modules probe both), Iris `net.coderbot.iris` 1.16.5 (Sodium 0.2.0) and 1.17.1 (Sodium 0.3.4) differ; re-derive hooks for each. |
| S2 | 1.18.2 (0.4.1 · 1.6.11), 1.19.2 (0.4.4 · 1.6.11), 1.19.3 (0.4.9 · 1.5.2), 1.19.4 (0.4.10 · 1.6.11), 1.20 (0.4.10 · 1.6.4; 1.20.1 gets 0.5.13 · 1.7.6) | Iris `net.coderbot.iris` (`shaderpack.PackShadowDirectives`) |
| S3 | 1.20.2 (0.5.5 · 1.6.14), 1.20.4 (0.5.8 · 1.7.2), 1.21 (0.5.11 · 1.7.3) | Iris `net.irisshaders.iris` from 1.7 (1.20.4 module); Sodium `net.caffeinemc.mods.sodium` |
| S4 = source | **1.21.1 (0.6.13 · 1.8.8)**, 1.21.4 (1.21.4: 0.6.13 · 1.8.8; 1.21.2: 0.6.1 · 1.8.0; 1.21.3: 0.6.8 · 1.8.1) | `net.irisshaders.iris.shaderpack.properties.PackShadowDirectives` |
| S5 | 1.21.6 / 1.21.8 / 1.21.10 (0.7.3 · 1.9.6–1.9.7) | as S4 (re-derive) |
| S6 | 1.21.11 (0.8.12 · 1.10.7), 26.1 / 26.1.1 (0.8.9 · 1.10.9) | as S4 (re-derive) |
| S7 | 26.1.2 / 26.2 (0.9.2 · 1.11.4), 26.3 (0.9.2 · 1.11.7) | as S4 (re-derive). Sodium 0.9.x ships its own Fabric Rendering API (no Indium) |
| none | 1.8.9 | OptiFine HD_U_M5 (shader layer = OptiFine; nothing here applies) |

Memory once recorded 1.21.8 / 1.21.10 / 1.21.11 as Sodium 0.8.13 + Iris 1.10.4. The catalog says otherwise
(above), and the catalog is what ships. One module can span stacks: 1.20 (1.20 vs 1.20.1), 1.21.4 (three Sodiums)
and 26.1 (two). A reflected member must be checked on every stack the module ships with.

## Already on main (don't re-port)
- `IrisShadowDirectivesMixin` is on every Fabric module.
  - Its target is `net.coderbot...PackShadowDirectives` on S1/S2 and `net.irisshaders...properties.PackShadowDirectives` on S4+.
  - Proof: `dev-shots -f set=world -f shaderpack=true`.
- `IrisBridge` (reflection to the Iris API) is in every module. `IrisWatermarkMixin` and `IrisListDecorationsMixin` are on some.
- Terrain renderer: `render/terrain/OriginRender` plus `SodiumChunkRendererMixin`, `SodiumDefaultChunkRendererAccessor` and
  `SodiumShaderChunkRendererAccessor` are on 1.21, 1.21.1 and 1.21.4 only.

## Shader-speed features (1.21.1, origin/claude/shader-speed; not yet ported)
There are four features: Limit Far, Smart Terrain, Off-Map Shadows and Half-Rate Shadows.
- Files on that branch, under `src/mods/versions/1.21.1/src/client/java/com/origin/client/client/`:
  - `render/`: HalfRateShadows, OffMapShadows, ShadowCasterLimit, SmartTerrainUpdates, IrisState, terrain/TerrainCuller, terrain/TerrainShaders.
  - `mixin/`: IrisPipelineHalfRateMixin, IrisShadowRendererHalfRateMixin, IrisShadowDirectivesMixin,
    LevelRendererCullFrustumMixin, ShadowCasterBlockEntityMixin, ShadowCasterEntityMixin, SodiumChunkRendererMixin,
    SodiumGraphReuseMixin, SodiumOcclusionStampMixin, SodiumViewportShadowMapMixin, SodiumWorldRendererReuseMixin,
    and the Sodium*Accessor set (ChunkRenderList, DefaultChunkRenderer, RenderSectionManager, ShaderChunkRenderer, SortedRenderLists).
- Targets on 1.21.1:
  - `OcclusionCuller`
  - `SodiumWorldRenderer`
  - `Viewport.isBoxVisible` (via @ModifyReturnValue)
  - RenderSectionManager `scheduleRebuild` / `scheduleSort` INVOKE `RenderSection.setPendingUpdate`
  - Iris `ShadowRenderer.renderShadows` HEAD/RETURN and `createShadowModelView` RETURN
  - `IrisRenderingPipeline`
  - LevelRenderer `prepareCullFrustum` HEAD
- **Facts proven on Iris 1.8.8 + Sodium 0.6.13** (1.21.1). Each must be re-proven on every other stack:
  - The Iris shadow pass runs at renderSky, BEFORE the main setupRender.
  - OcclusionCuller has 3 `setLastVisibleFrame` call sites.
  - `Viewport.isBoxVisible(IIIFFF)Z` is called only by OcclusionCuller.
  - Iris never culls the shadow pass to the shadow map's x/y edges. Off-Map Shadows adds that cull.
  - `beginLevelRendering` reads `shadowRenderTargets` at field-read ordinal 0.
  - `customImages` is a superset of `clearImages`.
  - Read Iris private members through **cached reflection**, not @Shadow. A rename then degrades to "feature off" instead
    of failing the mixin.
- **Porting rule**: for each target stack, javap the real Sodium and Iris jars (from `libs/` after perf_libs, or Modrinth),
  then redo the call census for each target above before writing a hook. Sodium 0.4 → 0.5 → 0.6 → 0.7 → 0.8 → 0.9
  restructured the chunk renderer more than once (likely; not yet diffed). Never copy 0.6.13 descriptors.

## Video Settings mod rail (bundled mods as tabs inside Sodium's Video Settings)
- Sodium 0.8+ has a Config API; use it where the module ships 0.8+ (1.21.11, 26.x).
- On Sodium <0.8 the rail is a `SodiumOptionsGUI` GUI mixin (1.21.1 on 0.6.13).
  - The 1.21.1 0.8 build is an alpha that breaks Iris.
  - Which mechanism 1.21.6–1.21.10 (Sodium 0.7.3) use is NOT checked here. Grep the module.
- Module code probes both `me.jellysquid...SodiumOptionsGUI` and `net.caffeinemc...SodiumOptionsGUI`.
- This can only be proven by opening Video Settings in dev shots (`set=perf`, `perf_mods=true`).

## Post effects and colour grade
- **Color Saturation**: a hand-rolled, state-isolated fullscreen grade. PostChain corrupted the vanilla sky.
  - 1.17.1–1.20.4 had a black-world / white-vignette bug. See PORTING-ERA-NOTES "Color Saturation black world".
  - 1.16.5 has no core shaders, so the card was removed.
- **1.21.11**: fullscreen grade via copyTextureToTexture, with a V flip and the real bounds(). PostChain uniforms are baked, so motion blur ships 3 variants.
- **26.x**:
  - Extract runs before the world draw. Take framebuffer snapshots inside `GuiElementRenderState.buildVertices`.
  - Derive pipelines from vanilla ones (`OriginShaders.derive`).
  - 26.3: `GameRenderer.update()` rebuilds requested post effects every frame. Motion Blur appends its chain there.
- **Iris owns post-processing**: `GameRenderer.postProcessor` has one owner. Skip the effect (don't crash) while a pack is active.

## Proof
- `dev-shots -f set=world -f shaderpack=true -f perf_mods=true` proves the hooks apply with the real stack and a shadow pack.
- FPS gain needs Will's real hardware. Treat CI frame times as relative only.

## 1.21.4 port (proven 2026-10-20, commit cdf5f864)
- On 1.21.4 Iris runs the shadow pass right after `collectVisibleEntities`, not at `renderSky`; the main entity cull runs first against the previous frame's stamps.
- Iris 1.8.8+mc1.21.4 adds `MixinRenderSection`, which keeps shadow-pass stamps in a separate field; the frame counter is still shared, so stamp replay is still needed.
- Sodium 0.6.13 on 1.21.4: `update`, `createTerrainRenderList` and `setupTerrain` take `FogParameters`; `RenderSystem.getShaderFogEnd` is gone (use `RenderSystem.getShaderFog().end()`).
- `Entity.getBoundingBoxForCulling` moved to `EntityRenderer` (protected); read it through an `@Invoker` plus `@Local` capture of the renderer.
- Iris is not on the 1.21.4 compile classpath by default; add `modCompileOnly maven.modrinth:iris:<version id>`.
- The 1.21.4 module also serves MC 1.21.2/1.21.3 (Sodium 0.6.1/0.6.8, Iris 1.8.0/1.8.1); every Sodium/Iris hook must fail soft there.

## Version gates on third-party hooks (compat/CompatGates, 2026-10-26)
Every Origin mixin into Sodium, Iris or JEI applies only on the builds it was proven on (DECISIONS 2026-10-26).
- **Where:** the shared `compat/CompatGates` (+ `CompatMixinPlugin` on every client mixin config) reads the module's own
  `originclient.compat-gates.txt`. `group` = mods + Fabric version ranges + the mixins it gates; `feature` = the groups
  a feature needs (`/` = alternatives). Ranges = the shipped build, up to the end of its minor line (`~0.8.13`), plus the
  launcher's vetted pairs; join alternatives with ` or ` (never `||`: it splits the `|` fields).
- **Out of range:** the group's mixins never apply, the feature reads off (`CompatGates.on("<option key>")` in
  `SodiumIrisBuilds` and in each feature's `broken` flag), one log line `Origin compat gate: <feature> is off (<mod>
  <version> is outside <range>)`, and the Mod Menu says "Off: needs a supported Sodium" (option tooltip via
  `CompatNotes`, card/page notes via StepAside's note slot). The rest of Origin runs.
- **Adding a hook:** put the new mixin in a group of that module's data file. `tools/compat-gates/check.py` (build-check)
  fails on an ungated third-party mixin, stale data, an unknown `CompatGates.on` id, or a pre-1.20 copy of compat/
  that drifted (pre-1.20 modules are no-sync: copy compat/*.java by hand).
- **Shipped pair = unchanged behaviour:** every module's shipped Sodium/Iris/JEI is inside its own ranges.

| Module | Gated mixins | Groups: mod range (mixins) | Features: groups |
|---|---|---|---|
| 26.3 | 10 | `iris` iris ~1.11.7 (4); `sodium` sodium ~0.9.2 (4); `jei` jei >=31.17.0.93 <32.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium |
| 26.2 | 10 | `iris` iris ~1.11.4 (4); `sodium` sodium ~0.9.2 (4); `jei` jei >=30.48.0.242 <31.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium |
| 26.1 | 15 | `iris` iris ~1.10.9 or ~1.11.4 (4); `sodium` sodium ~0.8.9 or ~0.9.2 (3); `sodium08` sodium ~0.8.9 (4); `sodium09` sodium ~0.9.2 (1); `smartTerrainPair` sodium 0.8.9; iris 1.10.9 (1); `jei` jei >=29.37.0.100 <30.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium sodium08 / iris sodium sodium09, smartTerrainUpdates = iris sodium sodium08 smartTerrainPair |
| 1.21.1 | 12 | `iris` iris ~1.8.1 (3); `sodium` sodium ~0.8.13 or ~0.6.5 (7); `smartTerrainPair` sodium 0.8.13; iris 1.8.14-beta.1 (0); `jei` jei >=19.51.0.418 <20.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair |
| 1.21 | 17 | `iris` iris ~1.8.8 (3); `sodium` sodium ~0.6.13 (12); `smartTerrainPair` sodium 0.6.13; iris 1.8.8 (0); `jei` jei >=19.39.0.372 <20.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair, originRender = sodium |
| 1.21.4 | 14 | `iris` iris ~1.8.0 (3); `sodium` sodium ~0.6.0 (10); `smartTerrainPair` sodium 0.6.13; iris 1.8.8 (1) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair, originRender = sodium |
| 1.21.6 | 10 | `iris` iris ~1.9.6 (3); `sodium` sodium ~0.7.3 (7); `smartTerrainPair` sodium 0.7.3; iris 1.9.6 (0) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair |
| 1.21.8 | 10 | `iris` iris ~1.9.6 (3); `sodium` sodium ~0.7.3 (7); `smartTerrainPair` sodium 0.7.3; iris 1.9.6 (0) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair |
| 1.21.10 | 12 | `iris` iris ~1.9.7 (3); `sodium` sodium ~0.7.3 (7); `smartTerrainPair` sodium 0.7.3; iris 1.9.7 (0); `jei` jei >=26.3.0.31 <27.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair |
| 1.21.11 | 12 | `iris` iris ~1.10.0 (3); `sodium` sodium ~0.8.0 (7); `smartTerrain812` sodium 0.8.12; iris 1.10.7 (0); `smartTerrain814` sodium 0.8.14; iris 1.10.8 (0); `jei` jei >=27.54.0.112 <28.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrain812 / iris sodium smartTerrain814 |
| 1.20 | 20 | `iris` iris ~1.6.4 or ~1.7.6 (2); `sodium` sodium 0.4.10 or 0.5.13 (0); `sodium05` sodium 0.5.13 (10); `sodium04` sodium 0.4.10 (1); `iris17` iris 1.7.6 (2); `iris16` iris 1.6.4 (3); `jei` jei >=14.0.0.11 <15.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium sodium05 iris17 / iris sodium sodium04 iris16, offMapShadows = iris sodium sodium05 iris17 / iris sodium sodium04 iris16, smartTerrainUpdates = iris sodium sodium05 iris17 |
| 1.20.2 | 17 | `iris` iris ~1.6.14 (6); `sodium` sodium ~0.5.5 (9); `smartTerrainPair` sodium 0.5.5; iris 1.6.14 (0); `jei` jei >=16.0.0.2 <17.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair |
| 1.20.4 | 16 | `iris` iris ~1.7.2 (5); `sodium` sodium ~0.5.8 (9); `smartTerrainPair` sodium 0.5.8; iris 1.7.2 (0); `jei` jei >=17.3.1.5 <18.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium smartTerrainPair |
| 1.19.4 | 11 | `iris` iris ~1.6.11 (4); `sodium` sodium ~0.4.10 (5); `jei` jei >=13.1.0.19 <14.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium |
| 1.19.3 | 11 | `iris` iris ~1.5.2 (4); `sodium` sodium ~0.4.9 (5); `jei` jei >=12.4.0.22 <13.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium |
| 1.19.2 | 11 | `iris` iris ~1.6.11 (4); `sodium` sodium ~0.4.4 (5); `jei` jei >=11.68.0.1086 <12.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium |
| 1.18.2 | 11 | `iris` iris ~1.6.11 (4); `sodium` sodium ~0.4.1 (5); `jei` jei >=10.2.1.1002 <11.0.0- (2) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium |
| 1.17.1 | 9 | `iris` iris ~1.2.7 (4); `sodium` sodium ~0.3.4 (5) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium |
| 1.16.5 | 8 | `iris` iris ~1.4.5 (4); `sodium` sodium ~0.2.0 (4) | shaderPerformanceMode = iris, halfRateShadows = iris sodium, offMapShadows = iris sodium, smartTerrainUpdates = iris sodium |

1.8.9 (Forge) has two mixins, both into Forge's own fixed classes; no third-party gate is needed there.
