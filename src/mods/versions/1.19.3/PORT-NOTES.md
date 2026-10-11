# Origin 1.19.3 — port notes & mixin audit

Dedicated 1.19.3 module (1.19.3 ONLY; 1.19.4 has its own jar). Template:
`src/mods/staged/1.19.4`. 1.19.3 is the hybrid era: it shares 1.19.4's
JOML/Axis + `Button.builder` + `GuiComponent.enableScissor` APIs, but keeps
the OLDER `renderButton`-era widgets, has no LogoRenderer, and keeps the dirt
background. Every statement below was verified with `javap -p -s/-c` against
the loom-mapped **1.19.3 merged jar**
(`minecraft-merged-1.19.3-loom.mappings...jar`). Nothing was taken from
memory. The bar was ZERO degradation vs the 1.19.4 experience — all four
known 1.19.3 floor degradations from the 1.19.4 module's audit are fixed
(see "The four fixes" below).

## The four fixes (the 1.19.4 floor-audit degradations)

1. **Widgets rendered vanilla** → fixed. On 1.19.3 the widget render method
   is `AbstractWidget.renderButton(PoseStack;IIF)V`; AbstractButton and
   AbstractSliderButton declare NO override (javap-verified), so the 1.19.4
   module's two `renderWidget` mixins had nothing to bind to. Replaced with
   ONE `AbstractWidgetMixin` on `AbstractWidget.renderButton` that
   type-dispatches (slider → `renderSlider` via the new
   `AbstractSliderButtonAccessor` for the protected `value:D`; button →
   `render`). Checkbox declares its own `renderButton` override → its mixin
   simply retargeted `renderWidget` → `renderButton`. Coverage is
   structurally identical to 1.19.4: subclasses with their own renderButton
   (ImageButton, PlainTextButton, EditBox, LockIconButton,
   StateSwitchingButton — javap-enumerated) bypass and stay vanilla, exactly
   the set that bypassed renderWidget there; Button/CycleButton (no
   override) are styled.
2. **Vanilla "Minecraft" logo showed** → fixed. No LogoRenderer in 1.19.3
   (arrived 1.19.4). render() draws the wordmark via the inherited
   `TitleScreen.blitOutlineBlack(IILjava/util/function/BiConsumer;)V` — two
   call sites (minceraft-easter-egg / normal), if/else so exactly one runs
   per frame; one @Redirect matches both and draws the Origin wordmark. The
   "Java Edition" strip is the only 9-arg
   `blit(PoseStack;IIFFIIII)V` in render() — suppressed via a same-frame
   latch (`originclient$wordmarkDrawn`) so a wordmark failure brings back
   BOTH vanilla pieces, never half a logo. The wordmark draws on a fresh
   identity PoseStack — bytecode-verified equivalent: GameRenderer.render
   passes `new PoseStack()` into Screen.renderWithTooltip on 1.19.3.
3. **Dirt background on GenericDirtMessageScreen** → fixed.
   `renderDirtBackground` is `(I)V` here (no PoseStack) — hook retargeted.
   `renderBackground` hooks retargeted to the
   `renderBackground(PoseStack;I)V` funnel (the 1-arg overload delegates
   into it with 0, bytecode-verified), with explicit descriptors so the
   two-overload name can't ambiguity-match. GenericDirtMessageScreen calls
   `renderDirtBackground(I)` directly (bytecode-verified) → covered.
4. **Armor-HUD item icons latched off** → fixed. 1.19.3's ItemRenderer has
   no PoseStack GUI overloads; `Gfx.renderItem/renderItemDecorations` now
   call `renderAndDecorateItem(ItemStack,II)V` /
   `renderGuiItemDecorations(Font,ItemStack,II)V` (javap-verified) with the
   wrapped pose multiplied onto `RenderSystem.getModelViewStack()`
   (`mulPoseMatrix(org.joml.Matrix4f)`, exists on 1.19.3) around the call —
   so HUD-editor scale/position apply — then popped + re-applied. The
   fail-soft latch stays.

## Other deltas vs the 1.19.4 module

- **Deleted mixins** (classes don't exist on 1.19.3 — javap-confirmed
  absent): TabNavigationBarMixin, TabButtonMixin, ExperimentsScreenMixin
  (all 1.19.4-new, as the 1.19.4 port proved). **CreateWorldScreenMixin
  also deleted**: 1.19.3's CreateWorldScreen is the pre-tab UI and its
  render() contains NO blit at all (bytecode census) — there is no footer
  separator to suppress, nothing lost.
- **isHovered()**: `AbstractWidget.isHovered()` is 1.19.4+ (1.19.3 has only
  the protected field). New `OriginButtonRenderer.hovered(AbstractWidget)`
  recomputes hover via the public `isMouseOver(DD)Z` with the live cursor
  mapped to GUI space (the exact xpos*guiWidth/screenWidth mapping vanilla
  uses). All five call sites (button/slider/checkbox hover-ease, title +
  screen cursor-glow bloom, mod-menu hover in OriginClientMod) rewired.
- **RenderType.debugQuads()** is 1.19.4+ and `RenderType.create` is
  package-private on 1.19.3 → BlockOverlayRenderer's fill type is now a
  private `FillQuads extends RenderType` built on the PUBLIC RenderType
  ctor, reusing vanilla's own protected shards (POSITION_COLOR_SHADER +
  TRANSLUCENT_TRANSPARENCY + NO_CULL — the exact three 1.19.4's
  debug_quads composes, bytecode-verified, buffer 131072, no crumbling/sort
  — the 5-arg create defaults). setup/clear delegate to the shards
  themselves, so GL state is vanilla's.
- **MobEffectInstance.isInfiniteDuration()** is 1.19.4+ → the latched
  helper is now a constant `false` (era-correct: 1.19.3 effects can't be
  infinite; call sites unchanged).
- **OriginShaderButton** overrides `renderButton` (was `renderWidget`).
- **Gfx**: scissor comment corrected — `GuiComponent.enableScissor(IIII)V`
  DOES exist on 1.19.3 (javap), direct delegate kept. Item methods per fix
  4. Everything else identical.
- `gradle.properties` — minecraft 1.19.3, fabric_api `0.76.1+1.19.3`
  (newest 1.19.3 build on maven.fabricmc.net at port time), loader 0.19.3.
- `build.gradle` — archivesName `originclient-1.19.3`; dev-libs guard
  renamed to `sodium-fabric-mc1.19.3-0.4.9+build.23.jar` /
  `iris-mc1.19.3-1.5.2.jar` (still `.exists()`-guarded, absent from CI).
  Java release 17 kept. (Note: the jcpp/glsl-transformer/antlr dev-runtime
  pins were matched to Iris 1.6.x; if runClient shader testing ever needs
  Iris 1.5.2's exact nested versions, re-check its META-INF/jars.)
- `fabric.mod.json` — `"minecraft": ">=1.19.3- <1.19.4-"` (1.19.3 ONLY).
- `.no-shared-sync` kept (whole-module fork; no overrides.txt).

## Mixin audit — originclient.client.mixins.json (31 entries)

| Mixin | Verdict |
|---|---|
| GameRendererMixin | OK — `getFov(Camera;FZ)D` returns double; `CallbackInfoReturnable<Double>` kept. |
| GuiHudMixin | OK — `Gui.render(PoseStack;F)V`. |
| MouseHandlerMixin | OK — `onPress(JIII)V`, `onScroll(JDD)V`, `turnPlayer()V`, `@Shadow accumulatedDX/DY:D`, `isMouseGrabbed()Z`. |
| ClientLevelTimeMixin | OK — `dayTime()` override; `LevelAccessor.dayTime()J` default exists. |
| CameraMixin | OK — `setup(BlockGetter;Entity;ZZF)V` contains exactly 3 `setRotation(FF)V` invokes (bytecode count = 3; require=1 satisfied). |
| LoadingOverlayMixin | OK — `render(PoseStack;IIF)V`; `@Shadow currentProgress:F`. |
| TitleScreenMixin | **REWORKED** — LogoRenderer redirect DELETED (class absent); replaced by `blitOutlineBlack(IILjava/util/function/BiConsumer;)V` redirect (2 call sites, if/else) + 9-arg edition-strip `blit(PoseStack;IIFFIIII)V` redirect with same-frame latch. Re-verified the other four suppressions are each still the ONLY call of their shape in 1.19.3's render() (bytecode census: 1× `PanoramaRenderer.render(FF)V`, 1× 10-arg `blit(PoseStack;IIIIFFIIII)V`, 1× `drawCenteredString(...String;III)V` splash, 1× `drawString(...String;III)V` version). HEAD background + init-TAIL button strip unchanged (ImageButton/PlainTextButton exist on 1.19.3). |
| AbstractWidgetMixin | **NEW** (replaces AbstractButtonMixin + AbstractSliderButtonMixin) — `AbstractWidget.renderButton(PoseStack;IIF)V` declared public on AbstractWidget; type-dispatch to button/slider restyle. |
| AbstractSliderButtonAccessor | **NEW** — `@Accessor("value")` for declared field `value:D` on AbstractSliderButton. |
| ScreenBackgroundMixin | Retargeted — explicit `renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;I)V` (HEAD cancel + TAIL glow) and `renderDirtBackground(I)V` (HEAD cancel). |
| AbstractSelectionListMixin | OK — `render(PoseStack;IIF)V`; `@Shadow renderBackground/renderTopAndBottom:Z`. |
| CheckboxMixin | Retargeted descriptor — `renderButton(PoseStack;IIF)V` (declared on Checkbox in 1.19.3); `selected()Z`. |
| LevelRendererMixin | OK — `renderSnowAndRain(LightTexture;FDDD)V`, `renderSky(PoseStack;Lorg/joml/Matrix4f;F;Camera;Z;Runnable)V` (JOML in 1.19.3+). |
| LightTextureMixin | OK — `updateLightTexture(F)V`; `OptionInstance.get()` ordinal 1 is gamma (ordinal 0 = darknessEffectScale; bytecode-verified). |
| ParticleEngineMixin | OK — `createParticle(ParticleOptions;DDDDDD)`, `destroy(BlockPos;BlockState)V`, `crack(BlockPos;Direction)V`. |
| SingleQuadParticleMixin | OK — `getQuadSize(F)F`. |
| LevelTimeMixin | OK — `Level.getDayTime()J`. |
| HitboxMixin | OK — `renderHitbox(PoseStack;VertexConsumer;Entity;F)V`. |
| EntityNametagMixin | OK — `shouldShowName(Entity)Z`, `renderNameTag(Entity;Component;PoseStack;MultiBufferSource;I)V`. |
| ChatTimestampMixin | OK unchanged — `addMessage(Component;MessageSignature;GuiMessageTag)V` exists on 1.19.3; `@Shadow allMessages`, `refreshTrimmedMessage()V`. |
| GuiEffectsMixin | OK — `renderEffects(PoseStack)V`. |
| GuiScoreboardMixin | OK — `displayScoreboardSidebar(PoseStack;Objective)V`. |
| GameRendererAccessor | OK — `loadEffect(ResourceLocation)V`. |
| PostChainAccessor | OK — `passes:List` field; `PostPass.getEffect()` + `EffectInstance.safeGetUniform(String)` verified. |
| MinecraftFramerateMixin | OK — `getFramerateLimit()I`. |
| ToastComponentMixin | OK — `addToast(Toast)V`. |
| EntityRenderDispatcherMixin | OK — `shouldRender(Entity;Frustum;DDD)Z`. |
| BlockEntityRenderDispatcherMixin | OK — `render(BlockEntity;F;PoseStack;MultiBufferSource)V`. |
| PauseScreenMixin | OK — `createPauseMenu()V`; `Button.builder`/`Builder.bounds(IIII)`/`getX()I`/`getY()I` all exist (builder arrived 1.19.3). |
| IrisShadowDirectivesMixin | OK unchanged — pre-1.20 Iris (1.5.x/1.6.x) keeps `net.coderbot.iris.shaderpack.PackShadowDirectives`; @Pseudo/require 0. |
| IrisWatermarkMixin | OK unchanged — `net.coderbot.iris.gui.screen.ShaderPackScreen`, `<init>` TAIL, @Pseudo/require 0. |
| ~~TabButtonMixin~~ | DROPPED — TabButton doesn't exist on 1.19.3 (introduced 1.19.4); no tab UI to restyle. |
| ~~TabNavigationBarMixin~~ | DROPPED — class absent on 1.19.3. |
| ~~ExperimentsScreenMixin~~ | DROPPED — class absent on 1.19.3. |
| ~~CreateWorldScreenMixin~~ | DROPPED — 1.19.3's pre-tab CreateWorldScreen.render() has no blit (no footer separator exists). |

## Mixin audit — originclient.loading.mixins.json (all 4 kept)

| Mixin | Verdict |
|---|---|
| LevelLoadingScreenMixin | OK — `render(PoseStack;IIF)V`. |
| ReceivingLevelScreenMixin | OK — `render(PoseStack;IIF)V`. |
| ProgressScreenMixin | OK — `render(PoseStack;IIF)V`. |
| ConnectScreenMixin | OK — `render(PoseStack;IIF)V`. |

## Feature parity

1:1 with the 1.19.4 module, which is 1:1 with versions/1.20: title scene +
cursor glow + account chip + Origin wordmark, all loading/progress/connect
scenes, Origin backdrop behind every out-of-world menu INCLUDING
GenericDirtMessageScreen, mod menu, HUD + editor, every mod (armor HUD
icons included), shader browser/downloader/Iris bridge, crash fail-soft.
No feature dropped; the only non-feature difference is that 1.19.3 has no
tabbed CreateWorld/Experiments UI to restyle (vanilla never had it there).

## Build status

`gradlew build` exit 0 (Loom 1.17.14, Gradle wrapper, JDK 17). Artifact:
`build/libs/originclient-1.19.3-0.4.1.jar`. Not runClient-tested
(orchestrator boot-sweeps staged modules later).

## 2026-10 — ported onto the 1.21.1 design (redesign v6)

Reproducible from the pristine module with the scratch script `port_1193.py`
(same method and feature set as the 1.19.4 module's 2026-10 section). 1.19.3
deltas on top of the 1.19.4 port, each from this module's own code:
- Widgets: AbstractWidgetMixin (renderButton + instanceof) keeps restyling buttons
  and sliders; 1.21.1's AbstractButton/AbstractSliderButton/TabButton mixins are
  not used (renderWidget and TabButton are 1.19.4+). Checkbox + ImageButton +
  OriginShaderButton hook renderButton.
- Hover: `OriginButtonRenderer.hovered(widget)` (no isHovered() before 1.19.4) in
  the skin, the title spotlight and the menu backdrop.
- Title wordmark: the module's own blitOutlineBlack redirect + same-frame latch
  for the "Java Edition" strip (no LogoRenderer).
- BlockOverlayRenderer's new "mod off = hairline outline" draws into FillQuads
  (no RenderType.debugQuads before 1.19.4).
- Font.drawInBatch(boolean seeThrough) for Nametag styling; blockPosition() for
  the biome lookups; no infinite effects.
- ScreenInvoker declares only the erased bound (GuiEventListener & NarratableEntry).
- PlayerTabOverlayAccessor invokes getPlayerInfos() (reasoned, not javap'd here):
  if it is absent the accessor fails to apply and vanilla's tab list stays.
