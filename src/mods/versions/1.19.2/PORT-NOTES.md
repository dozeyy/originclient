# Origin 1.19.2 — port notes & mixin audit

Pre-1.19.3-era module (build target **1.19.2**, `fabric.mod.json` range
`>=1.19- <1.19.3`; the launcher should offer **1.19.2 only**). Template:
`src/mods/staged/1.19.4` (the Gfx-wrapper foundation port). Every statement
below was verified with `javap -p -s -c` against the loom-mapped jars —
**1.19.2** (clientonly + common) for the build target, and **1.19** +
**1.19.1** (merged, via a scratch loom probe) for the declared range floor.
Nothing was taken from memory.

## Era deltas applied on top of the 1.19.4 module

- **Widget paint is `renderButton`, declared ONLY on AbstractWidget.**
  Neither AbstractButton nor AbstractSliderButton declares an override
  (bytecode: plain `Button.renderButton` is just `super` + tooltip;
  CycleButton has none; sliders draw via the base method + a `renderBg`
  hook). The button/slider mixins therefore both target
  `AbstractWidget.renderButton` and scope themselves with `instanceof`;
  slider `value` is bridged by the new **AbstractSliderButtonAccessor**
  (can't @Shadow a subclass field from an AbstractWidget mixin). Checkbox
  declares its own `renderButton` (no super call) — hooked directly.
- **Button API**: `Button.builder`/`.bounds`/`getX()`/`getY()` are 1.19.3+ —
  replaced with the `(x,y,w,h,label,onPress)` ctor and the public `x`/`y`
  fields (PauseScreenMixin, OriginShaderButton, OriginButtonRenderer).
  `AbstractWidget.isHovered()` doesn't exist either: restyle highlight uses
  `isHoveredOrFocused()` (what vanilla's own 1.19.2 widget draw brightens
  on); the cursor-glow hover-bloom checks use `isMouseOver(mx,my)` (pure
  hover, cursor already at hand at those sites).
- **Pre-JOML**: `com.mojang.math.Axis` is 1.19.3+ →
  `Vector3f.ZP.rotationDegrees(deg)` returning the era `Quaternion`
  (OriginScreenRenderer.drawRings, OriginUi.mark). `renderSky`'s matrix param
  and `Gfx.fillGradient`'s `pose.last().pose()` are `com.mojang.math.Matrix4f`
  (import swap only).
- **Scissor**: `GuiComponent.enableScissor(IIII)` exists on 1.19.2 and 1.19.1
  but **NOT on 1.19**, and the range floor is 1.19 — so `Gfx` converts the
  GUI rectangle to window pixels itself (vanilla's exact gui-scale math) and
  calls `RenderSystem.enableScissor`, present on every 1.19.x. One code path,
  no floor-only NoSuchMethodError.
- **GUI item draws**: no PoseStack overloads on ItemRenderer — `Gfx.renderItem/
  renderItemDecorations` multiply the wrapped pose into
  `RenderSystem.getModelViewStack()` around `renderAndDecorateItem` /
  `renderGuiItemDecorations` (all javap-verified), so the **armor HUD icons
  WORK on 1.19.2** including HUD-editor scale/offset (this is the shipped jar;
  1.19.4's "latched off" degradation was floor-only there). The one-flip
  fail-soft latch is kept around the calls.
- **`Minecraft.getFps()` is 1.19.4+** — the FPS HUD reads the private static
  `Minecraft.fps` through the new **MinecraftFpsAccessor** (@Accessor).
- **`RenderType.debugQuads()` is 1.19.4+ and 1.19.2 has no translucent
  POSITION_COLOR quad type at all** (full factory list javap'd; only
  `lightning()`, which blends additively). BlockOverlayRenderer's filled
  overlay now draws immediate-mode through the Tesselator with the
  position-color shader, blend on / cull off / depth-write off — the same
  approach this era's own `DebugRenderer.renderFilledBox` uses. Block Overlay
  + side-only fill WORK.
- **`BuiltInRegistries` is 1.19.3+** → `net.minecraft.core.Registry
  .PARTICLE_TYPE` (ParticleEngineMixin per-type controls).
- **`MobEffectInstance.isInfiniteDuration()` is 1.19.4+** and effects can't be
  infinite anywhere in 1.19–1.19.2 → the 1.19.4 latch helper reduced to a
  constant `false` (seam kept so potion-row code stays one shape).
- **`TextureAtlasSprite.atlasLocation()` doesn't exist** →
  `sprite.atlas().location()` in Gfx's sprite blit.

## Mixin audit — originclient.client.mixins.json (33 entries: 30 kept/retargeted, 4 dropped, 2 added)

| Mixin | Verdict |
|---|---|
| GameRendererMixin | OK — `getFov(Camera;FZ)D` double; unchanged. |
| GuiHudMixin | OK — `Gui.render(PoseStack;F)V`. |
| MouseHandlerMixin | OK — `onPress(JIII)V`, `onScroll(JDD)V`, `turnPlayer()V`, `@Shadow accumulatedDX/DY:D`, `isMouseGrabbed()Z`. |
| ClientLevelTimeMixin | OK — adds `dayTime()` override; `LevelAccessor.dayTime()J` default exists on 1.19.2 (and 1.19). |
| CameraMixin | OK — `setup(BlockGetter;Entity;ZZF)V`, exactly 3 `setRotation(FF)V` invokes. |
| LoadingOverlayMixin | OK — `render(PoseStack;IIF)V`; `@Shadow currentProgress:F`. |
| TitleScreenMixin | **RETARGETED (wordmark lever changed)** — no LogoRenderer class this era. The logo is TWO `blitOutlineBlack(IILjava/util/function/BiConsumer;)V` calls (normal + "Minceraft" easter-egg branch; one executes per frame) — ONE @Redirect wraps both and draws the Origin wordmark, using Mixin arg-capture to reach render()'s PoseStack (blitOutlineBlack carries none). New second redirect suppresses the 8-arg "Java Edition" badge blit (`(PoseStack;IIFFIIII)V`, single occurrence). Panorama (`PanoramaRenderer.render(FF)V`), 10-arg PANORAMA_OVERLAY blit, splash `drawCenteredString(...String;III)V`, version `drawString(...String;III)V` redirects port as-is — each re-verified the only call of its shape in 1.19.2's render() bytecode. init-TAIL ImageButton/PlainTextButton strip unchanged (both classes exist). |
| AbstractButtonMixin | **RETARGETED** — `@Mixin(AbstractWidget)`, `renderButton(PoseStack;IIF)V` HEAD, `instanceof AbstractButton` scope (see era deltas). |
| ScreenBackgroundMixin | Retargeted — explicit descriptors: `renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V` (2-arg overload exists too, bare name would be ambiguous) and `renderDirtBackground(I)V` (no PoseStack; int scroll offset). |
| AbstractSelectionListMixin | OK — `render(PoseStack;IIF)V`; `@Shadow renderBackground/renderTopAndBottom:Z`. |
| TabButtonMixin | **DROPPED** — class is 1.19.4+. |
| TabNavigationBarMixin | **DROPPED** — class is 1.19.4+. |
| ExperimentsScreenMixin | **DROPPED** — class is 1.19.4+. |
| CreateWorldScreenMixin | **DROPPED** — 1.19.2's `CreateWorldScreen.render()` has NO blit at all (bytecode: only drawString/drawCenteredString); the footer-separator suppression has nothing to suppress. |
| AbstractSliderButtonMixin | **RETARGETED** — `@Mixin(AbstractWidget)`, `renderButton` HEAD, `instanceof AbstractSliderButton`; value via accessor. |
| AbstractSliderButtonAccessor | **ADDED** — `@Accessor("value") D` on AbstractSliderButton. |
| CheckboxMixin | Retargeted descriptor — `Checkbox.renderButton(PoseStack;IIF)V` (declared, no super call); `selected()Z`. |
| LevelRendererMixin | Retargeted — `renderSky(PoseStack;Lcom/mojang/math/Matrix4f;F;Camera;Z;Runnable)V` (JOML→mojang.math import swap); `renderSnowAndRain(LightTexture;FDDD)V` unchanged. |
| LightTextureMixin | OK — `updateLightTexture(F)V`; `OptionInstance.get()` ordinal 1 is still gamma (bytecode: exactly 2 gets — 0=darknessEffectScale, 1=gamma; same order on 1.19). |
| ParticleEngineMixin | OK (body edit only) — `createParticle(ParticleOptions;DDDDDD)`, `destroy`, `crack`; registry access via `Registry.PARTICLE_TYPE`. |
| SingleQuadParticleMixin | OK — `getQuadSize(F)F`. |
| LevelTimeMixin | OK — `Level.getDayTime()J`. |
| HitboxMixin | OK — `renderHitbox(PoseStack;VertexConsumer;Entity;F)V` private static. |
| EntityNametagMixin | OK — `shouldShowName(Entity)Z`, `renderNameTag(Entity;Component;PoseStack;MultiBufferSource;I)V`. |
| ChatTimestampMixin | OK unchanged — `addMessage(Component;MessageSignature;GuiMessageTag)V` exists on **1.19.2 AND 1.19.1**; `@Shadow allMessages`, `refreshTrimmedMessage()V` both present. (1.19.0 differs — see floor audit.) |
| GuiEffectsMixin | OK — `renderEffects(PoseStack)V`. |
| GuiScoreboardMixin | OK — `displayScoreboardSidebar(PoseStack;Objective)V` private. |
| GameRendererAccessor | OK — `loadEffect(ResourceLocation)V` private (also on 1.19). |
| PostChainAccessor | OK — `passes:List<PostPass>`, `PostPass.getEffect()`, `EffectInstance.safeGetUniform`. |
| MinecraftFramerateMixin | OK — `getFramerateLimit()I`. |
| MinecraftFpsAccessor | **ADDED** — static `@Accessor("fps") I` (getFps() is 1.19.4+). |
| ToastComponentMixin | OK — `addToast(Toast)V`. |
| EntityRenderDispatcherMixin | OK — `shouldRender(Entity;Frustum;DDD)Z`. |
| BlockEntityRenderDispatcherMixin | OK — `render(BlockEntity;F;PoseStack;MultiBufferSource)V`. |
| PauseScreenMixin | OK (body edit) — `createPauseMenu()V`; guard button rebuilt with the era Button ctor + x/y fields. |
| IrisShadowDirectivesMixin | OK unchanged — pre-1.20 Iris keeps `net.coderbot.iris.shaderpack.PackShadowDirectives`. |
| IrisWatermarkMixin | OK unchanged — `net.coderbot.iris.gui.screen.ShaderPackScreen`, @Pseudo/require 0. |

## Mixin audit — originclient.loading.mixins.json (all 4 kept, unchanged)

LevelLoadingScreenMixin / ReceivingLevelScreenMixin / ProgressScreenMixin /
ConnectScreenMixin — `render(PoseStack;IIF)V` verified on 1.19.2 for all four.

## Feature deltas vs the 1.19.4 module

- **Armor HUD item icons WORK** (modelview multiplication; latched-off on
  1.19.4 only because its floor was 1.19.3 — here the era-correct call is the
  primary path).
- **Block Overlay fill WORKS** via immediate-mode quads (debugQuads absent).
- Tab bar / Experiments screen restyles gone WITH the vanilla features (the
  screens/classes don't exist on 1.19.2) — nothing lost vs vanilla.
- CreateWorld footer-separator suppression dropped (vanilla draws none).
- Everything else 1:1 with the 1.19.4 module: title scene + cursor glow +
  account chip, loading/progress/connect scenes, mod menu, HUD + editor,
  every mod (zoom, fullbright, hitboxes, chunk borders, block overlay/outline,
  CPS/FPS/keystrokes, motion blur, freelook, weather/sky/time, particles,
  nametags, scoreboard, toasts, chat timestamps/stack-spam), shader browser +
  downloader + Iris bridge (`net.coderbot.*`), crash fail-soft.

## Range-floor audit (declared `>=1.19- <1.19.3`; javap'd 1.19 + 1.19.1 merged jars)

**1.19.1 — everything binds identically to 1.19.2**: 3-arg addMessage +
refreshTrimmedMessage, GuiComponent scissor (unused now anyway),
PlayerFaceRenderer, all widget/screen/renderer descriptors. Full experience.

**1.19.0 — no crash path; two silent degradations**:
- ChatTimestampMixin drops whole (no 3-arg `addMessage`, no
  `refreshTrimmedMessage` — `required:false` tolerates the failed @Shadow):
  chat timestamps/stack-spam quietly absent.
- Account chip head falls back to the Origin ring mark (`PlayerFaceRenderer`
  class is 1.19.1+; the call site already has its own inner catch → chip-only
  degradation, health latch untouched).
- Verified PRESENT on 1.19 (so no other floor risk): AbstractWidget
  renderButton + x/y + isHoveredOrFocused/isMouseOver, Button ctor,
  Checkbox.renderButton/selected, Screen renderBackground(PoseStack)/
  renderDirtBackground(I), TitleScreen render() invoke shapes (identical
  counts: 2 blitOutlineBlack, one 8-arg + one 10-arg blit, 1 panorama render,
  1 splash drawCenteredString, 1 version drawString), Minecraft.fps +
  getFramerateLimit, ItemRenderer no-pose draws, renderSky/renderSnowAndRain
  descriptors, Gui.render(PoseStack;F), gamma ordinal (2 gets, same order),
  Vector3f.ZP, PoseStack.mulPoseMatrix, RenderSystem.enableScissor,
  Registry.PARTICLE_TYPE, getMobEffectTextures, TextureAtlasSprite.atlas(),
  GameRenderer.loadEffect.

1.19 and 1.19.1 are tolerated by the range, not offered by the launcher.

## What the downstream ports (1.18.2 / 1.17.1 / 1.16.5) must know

1. **Component factories**: `Component.literal/translatable/empty` are 1.19+.
   ≤1.18.2 → `new TextComponent(str)` / `new TranslatableComponent(key)`;
   `Component.empty()` → `TextComponent.EMPTY` or `new TextComponent("")`.
   Sweep the WHOLE module (PauseScreenMixin, ChatTimestampMixin, mod menu,
   HUD, shader browser all build Components).
2. **Chat**: ≤1.18.2 ChatComponent has no MessageSignature/GuiMessageTag era
   at all — `addMessage(Component)` public + `addMessage(Component,int)`
   style privates (1.18.2 javap needed). Retarget the @ModifyVariable to the
   era's terminal overload; `refreshTrimmedMessage` may be
   `rescaleChat`/absent — re-derive both @Shadows or drop the mixin.
3. **Scissor**: keep this module's RenderSystem + gui-scale implementation —
   it's already era-portable back to 1.16 (GuiComponent statics never existed
   there either).
4. **This module's widget targeting (AbstractWidget.renderButton +
   instanceof + value accessor) should port as-is to 1.18.2/1.17.1**; javap
   Checkbox/Button per era to confirm the no-super-call assumption. 1.16.5:
   AbstractWidget still has renderButton(PoseStack? NO — 1.16 render methods
   take (PoseStack) already but javap — and Checkbox may differ; re-verify
   everything, plus Java 8 rules (no `var`, no instanceof patterns, no
   List.of in hot paths, options.release = 8 — instanceof-pattern syntax used
   in the two widget mixins MUST be rewritten for the 1.16.5 module).
5. **OptionInstance is 1.19+**: ≤1.18.2 Options.gamma is a plain field/
   `Option` object — LightTextureMixin's OptionInstance.get redirect and any
   fullbright gamma writes need the era's accessor (javap Options +
   updateLightTexture bytecode).
6. **GuiMessage**: 1.19.0 already shows it generic (`GuiMessage<Component>`);
   ≤1.18.2 the @Shadow type in any chat mixin must match the era.
7. **Item draws**: the modelview-multiplication pattern here is the correct
   one for every pre-1.19.4 version — copy it. Registry statics stay on
   `net.minecraft.core.Registry` all the way down.
8. **TitleScreen**: the blitOutlineBlack wordmark lever should hold for
   1.18.2/1.17.1/1.16.5 (same logo drawing style) but re-count every redirect
   shape per era; the splash/version/overlay call shapes WILL drift.
9. **RenderSystem**: 1.17.1 keeps setShader*; **1.16.5 has none of it** —
   Gfx must bind via TextureManager.bind + RenderSystem.color4f, and the
   BlockOverlayRenderer immediate-mode fill must lose setShader (fixed
   function) — see the spec's 1.16.5 section.
10. **Fabric API**: pick the era build from maven metadata (1.18.2 ≈
    0.77.0+1.18.2, 1.17.1 ≈ 0.46.1+1.17, 1.16.5 ≈ 0.42.0+1.16); ScreenEvents
    pass PoseStack everywhere pre-1.20; confirm WorldRenderEvents.BLOCK_OUTLINE
    exists on the 1.16/1.17 fabric-api builds before porting
    BlockOverlayRenderer's event registration.

## Build status

`gradlew build` exit 0 (Loom 1.17-SNAPSHOT, JDK 17, fabric-api 0.77.0+1.19.2,
loader 0.19.3). Artifact: `build/libs/originclient-1.19.2-0.4.1.jar`
(83 classes; both mixin configs bundled). Not runClient-tested (orchestrator
boot-sweeps staged modules later).

## 2026-10 — ported onto the 1.21.1 design (redesign v6)

Reproducible from the pristine module with the scratch script `port_1192.py`
(same method and feature set as the 1.19.4 module's 2026-10 section). 1.19.2
deltas, each from this module's own code:
- Pre-JOML: com.mojang.math Matrix4f/Vector4f and Vector3f.ZP rotations (rings,
  logo); the waypoint screen projection uses load/multiply/transform; nametag
  redirect descriptor uses Lcom/mojang/math/Matrix4f;.
- Widgets: the module's own AbstractButtonMixin / AbstractSliderButtonMixin
  (@Mixin(AbstractWidget) renderButton + instanceof) and Checkbox.renderButton;
  public x / y fields instead of getX/setX (skin, title layout, foreign-widget
  avoidance, shader button); `new Button(...)` instead of Button.builder;
  isHoveredOrFocused() in the skin, isMouseOver(mx, my) for the spotlight.
- Registry.ITEM / Registry.PARTICLE_TYPE; FPS via MinecraftFpsAccessor.
- Title wordmark: the module's own blitOutlineBlack (arg-capture) + edition
  badge redirects. Account chip + tab faces: PlayerFaceRenderer (1.19.1+).
- Tab list: no getPlayerInfos() -> OriginTabList.vanillaPlayerList rebuilds
  vanilla's order (non-spectators, team, name ignoring case; 80 rows).
- BlockOverlayRenderer's hairline outline draws into FillQuads.
