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

## Held items and first-person hand (1.7 Animations, 2026-11-02, proven in CI on every live module)

| Era | Facts |
|---|---|
| all | Hook `ItemInHandRenderer.renderArmWithItem` (first person), `ItemInHandLayer` right after `ArmedModel.translateToHand` (third person), `getArmPose` (player renderer). The swing-while-using visual is `LivingEntity.swing(hand, false)`: `LocalPlayer` overrides only `swing(hand)`, so no packet. A raised shield = `isUsingItem` + BLOCK anim: leave that path to vanilla |
| 1.8.9 | sword blocking, no hit dip and instant sneak are native; only the third-person block turn is added |
| 1.16.5 | no `Mth.PI` (local constant), no `isScoping`, 3-arg `getModel`, no `PlayerItemInHandLayer`; Java 8 (no `Set.of`) |
| pre-1.19.3 | `Vector3f.YP.rotationDegrees`; TransformType until 1.19.4 (ItemDisplayContext from 1.19.4); `getUseDuration()` has no entity arg before 1.20.5 |
| pre-1.21 | armour tint is three floats: apply a red flash per channel |
| 1.21.2+ | render-state renderers: carry per-player data on a duck in `client.ext` (NEVER the mixin package: IllegalClassLoadError), written at `extractRenderState` TAIL, non-null only for `mc.player` |
| 1.21.4 | flat item = reused `ItemStackRenderState` probe, `isGui3d` false |
| 1.21.5+ | `SwordItem` gone: `ItemTags.SWORDS`. `isGui3d` gone: `!usesBlockLight` alone is too wide (3D resource-pack swords with `gui_light: front` match), add layer bounding-box depth <= 1/16 or a bake-time generated flag. 1.21.6 `appendModelIdentityElement` adds to a list `clear()` never empties: a reused probe must override it |
| 1.21.11 | `submitArmWithItem` gains an ItemStack arg; the attack dip reads `getItemSwapScale` (1.21.10: `getAttackStrengthScale`) |
| 26.x | local player = `AvatarRenderState.id == mc.player.getId()` (no duck needed); 26.2+ screen = `mc.gui.screen()` (26.1: `mc.screen`); 26.1 first person still `renderArmWithItem` |
| 26.3 | first person split: draw `FirstPersonHandsAndItemsRenderer.submitArmWithItem(PlayerRenderState, FirstPersonHandsAndItemsRenderState, ...)`, tick `FirstPersonHandsAndItems.tick(LocalPlayer)`; `swing(hand, SwingAnimation, boolean)` returns boolean; PoseStack `rotateDegrees(Axis, float)` |

Proof: a HOOKS table + `missingHooks()` reflection check that FAILS the dev-world in-world step (mixins are silent when dead), plus in-world shots with a scripted keyUse.

## Proof
- `dev-shots set=world`. Spawn fireworks once whenever a particle hook changes, as a crash check.
- Whether a mixin applied: mixin export or a one-time log line. These targets fail silently.
- Deep notes: PORTING-ERA-NOTES `### Findings from the feature port` (Item Physics, FPS Coach, Color Saturation on 1.16.5).
