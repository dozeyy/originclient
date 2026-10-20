# Recipe: Sodium / Iris internals, shadow pass, culling, Video Settings, post effects

Third-party internals change with the **Sodium/Iris version**, not with the Minecraft era. Group by the
stack that ships. Read it from `PerformanceModCatalog.Data.cs`; dev runs load the same jars via
`tools/ci/perf_libs.py`. Every mixin into Sodium/Iris is `remap = false`.

## The stack each module ships (catalog, 2026-10-20)

| Stack generation | Modules (Sodium · Iris) | Package names seen in module code |
|---|---|---|
| S1 old | 1.16.5 (0.2.0 · 1.4.5), 1.17.1 (0.3.4 · 1.2.7) | Sodium `me.jellysquid.mods.sodium` (likely; modules probe both), Iris `net.coderbot.iris` |
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
