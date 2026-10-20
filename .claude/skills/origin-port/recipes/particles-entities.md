# Recipe: particles, entity rendering, item physics, weather, sky, time, lighting

## Rules that hold everywhere
- **Never hide a particle by returning null from `createParticle`.** `FireworkParticles$Starter` dereferences the result,
  so any firework crashes. The fix shipped on 1.21.11 only, and the bug is latent everywhere else. Fix it per module when you touch particles.
  The 1.21 transplant keeps its own FireworkStarterMixin.
- `Particle.scale` exists in every era.
- Pre-1.19 particle colours are `int[]`.

## Per-era facts

| Era | Facts (proven unless marked) |
|---|---|
| pre-1.20 | `renderSky` arity is 6 / 4 / 2 depending on version (javap per module). 1.16.5: Entity fields `yRot`, `inventory`, `abilities`; no `getMinBuildHeight` (0) |
| 1.21.2+ | weather `addWeatherPass`, sky `addSkyPass`, `FramerateLimitTracker` (render-pipeline era) |
| 1.21.10 / 1.21.11 | `ClientLevel.addDestroyBlockEffect` / `addBreakingBlockEffect`. 3 mixins on 1.21.11 targeted methods that no longer exist and failed SILENTLY: EntityNametagMixin, BlockEntityRenderDispatcherMixin, ParticleEngineMixin. Run the loom-cache audit |
| 26.1 | beds are block entities (`BedRenderer.extractRenderState`): Colored Beds works. `EntityType.PLAYER`. Fullbright: `LightmapRenderStateExtractor.extract` TAIL (LightTexture gone; `util.LightCoordsUtil.FULL_BRIGHT`) |
| 26.2 | beds are NOT block entities (no Colored Beds hook). `EntityTypes.PLAYER`; `RenderTypes.entityCutoutNoCull` → `entityCutout`. Time of day: `ClientClockManager.getTotalTicks(Holder<WorldClock>)` (Time Changer hooks here); `Level.getOverworldClockTime()` = real time. Glint: `RenderType.prepare()` returns PreparedRenderType; swap at RETURN |
| 26.3 | `ClientClockManager.getTotalTicks` gone → hook `ClientClockInstance.totalTicks()`. `addBreakingBlockEffects` also plays the hit sound → gate `addBreakingParticles`. `EntityRenderDispatcher.shouldRender(+float)`. Glint drawn INSIDE the item/armour pass (`RenderTypes.itemCutoutGlint(tex)`): recognise it by the glint texture in the list. Weather "Clear" empties WeatherRenderState after `WeatherEffectRenderer.extractRenderState`. `PoseStack.mulPose` → `rotate(...)` |

## Proof
- `dev-shots set=world`. Spawn fireworks once whenever a particle hook changes, as a crash check.
- Whether a mixin applied: mixin export or a one-time log line. These targets fail silently.
- Deep notes: PORTING-ERA-NOTES `### Findings from the feature port` (Item Physics, FPS Coach, Color Saturation on 1.16.5).
