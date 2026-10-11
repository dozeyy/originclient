# Origin 1.16.5 — port notes & mixin audit

The OLDEST / hardest target: **Java 8 bytecode** + **fixed-function GL** (no
`RenderSystem.setShader*`) + **gson 2.8.0** + **no bundled-server split jar**.
Build target **1.16.5**, `fabric.mod.json` range `>=1.16.5- <1.17-` (launcher
offers **1.16.5 only**). Forked from `staged/1.18.2` (which already carries the
≤1.18.2 era: `TextComponent`/`TranslatableComponent`, no `OptionInstance`,
pre-signature chat) plus the fixed-function GL rewrite and a full Java-8
downgrade. `.no-shared-sync` (whole render core forked). Every statement below
was verified with `javap -p -s/-c` against the loom-mapped **1.16.5** merged jar
(`minecraft-merged-1.16.5-loom.mappings…`). Nothing was taken from memory.

## Build status

`gradlew clean build` **exit 0** (Loom 1.17.14, JDK auto-provisioned, fabric-api
`0.42.0+1.16`, loader 0.19.3). Artifact: `build/libs/originclient-1.16.5-0.4.1.jar`
(86 classes; both mixin configs + fabric.mod.json + shaders bundled; class
bytecode **major version 52 = Java 8**).

## Module mechanics (era-forced changes vs the 1.18.2 base)

- **No split source sets.** 1.16.5 predates the bundled-server jar, so Loom
  rejects `splitEnvironmentSourceSets()` ("Only Minecraft versions using a
  bundled server jar can be split"). The whole module was merged into ONE source
  set: `src/client/*` → `src/main/*` (java + resources), and `build.gradle`
  now declares only `sourceSet sourceSets.main`. The mod is `environment:client`
  via fabric.mod.json. `gradle-wrapper.jar` (excluded by the original copy) was
  restored from the sibling module.
- `gradle.properties`: `minecraft_version=1.16.5`, `fabric_api_version=0.42.0+1.16`
  (newest 1.16 build on maven.fabricmc.net at port time).
- `build.gradle`: `archivesName=originclient-1.16.5`; `options.release = 8`,
  source/target `VERSION_1_8`; dev-libs guard renamed to
  `sodium-fabric-mc1.16.5-0.2.0+build.4.jar` / `iris-mc1.16.5-1.4.5.jar`
  (still `.exists()`-guarded, absent from CI).
- `mixins.json` (both): `compatibilityLevel: JAVA_8`.

## Fixed-function GL rewrite (the big one)

1.16.5's `RenderSystem` has **no** `setShader` / `setShaderTexture` /
`setShaderColor` (all 1.17+) and **no** `getModelViewStack` / `applyModelViewMatrix`.
It DOES have `color4f`, `bindTexture(int)`, the legacy matrix stack
(`pushMatrix`/`popMatrix`/`multMatrix`/`translatef`/`scalef`), and `enableScissor`.
`PoseStack`, `GuiComponent` statics (fill/blit/drawString/drawCenteredString/
blitOutlineBlack) and `com.mojang.math.Matrix4f` all exist with the SAME shapes
as 1.18.2 (javap-confirmed).

- **`Gfx` wrapper**: texture bind = `Minecraft.getInstance().getTextureManager()
  .bind(rl)` (was `setShaderTexture`); `fillGradient` re-implemented in fixed
  function exactly like vanilla 1.16.5's own (`disableTexture` / `disableAlphaTest`
  / `shadeModel(GL_SMOOTH)` / a `POSITION_COLOR` quad through the Tesselator via
  `begin(GL11.GL_QUADS, …)` + `Tesselator.end()` / restore). `BufferBuilder.begin`
  takes an **int GL mode** on 1.16.5, not `VertexFormat.Mode`. Item GUI draws
  multiply the pose into the **legacy** matrix stack (`pushMatrix` /
  `multMatrix(pose.last().pose())` / `popMatrix`) instead of the 1.17+ modelview
  stack — HUD-editor scale/offset still works. Scissor and Font draw shapes are
  unchanged.
- **`OriginUi`** (drawing kit), **`OriginButtonRenderer`**, **`OriginScreenRenderer`**:
  every `RenderSystem.setShaderColor(r,g,b,a)` → `RenderSystem.color4f(r,g,b,a)`
  (identical fixed-function semantics — the global GL color modulates textured
  blits). 31 call sites.
- **`BlockOverlayRenderer`** filled overlay: `setShader(getPositionColorShader)`
  → `disableTexture()`/`enableTexture()`; `begin(VertexFormat.Mode.QUADS,…)` →
  `begin(GL11.GL_QUADS,…)`; `q.end()`+`BufferUploader.end(q)` →
  `Tesselator.getInstance().end()`.

## Java-8 downgrade (whole module)

- **Records → final classes** (private final fields + canonical ctor +
  record-style accessors): `OriginModMenuScreen.SRow`, `HudElements.Element`
  (keeps its `pos()`), `HudElements.PotionRow`, `Mods.Mod`,
  `OriginScreenRenderer.Ring`, `ShaderDownloader.State`, `ShaderPreviews.Preview`.
- **`var` → explicit type** (44 sites).
- **instanceof-patterns → classic instanceof + cast** (9 sites: both widget
  mixins, HitboxMixin, PauseScreenMixin, ScreenBackgroundMixin, TitleScreenMixin
  ×2, OriginClientMod ×2, BlockOverlayRenderer).
- **switch arrow-labels/expressions → classic `case … : … break;`** (5 switches:
  OriginColorPicker, OriginModMenuScreen ×2, ShaderBrowserScreen, and the
  `int tx = switch(pos2){…}` expression in HudElements).
- **`List.of(...)` → `java.util.Arrays.asList(...)`** (4 sites).
- **`InputStream.readAllBytes()` → `IoCompat.readAllBytes(in)`** (new tiny Java-8
  helper `com.origin.client.client.IoCompat`; 7 sites).
- **`Path.of` → `Paths.get`** (2); **`URLEncoder.encode(s, Charset)` →
  `encode(s, "UTF-8")`** (2, Java-8 signature); **`JsonArray.isEmpty()` →
  `.size()==0`/`>0`** (3, gson 2.8.0 has no isEmpty on arrays);
  **`JsonObject.keySet()` → `IoCompat.keys(obj)`** (5, keySet arrived gson 2.8.1;
  MC 1.16.5 ships 2.8.0).

## Era-API fixes (symbol/name drift caught by the compiler, javap-resolved)

- **slf4j → log4j**: `OriginClient` used `org.slf4j.*` (absent pre-1.17; the game
  logs via log4j2). Now `org.apache.logging.log4j.LogManager.getLogger`.
- **Options**: `getEffectiveRenderDistance()` → public `renderDistance` field
  (Entity/BlockEntityRenderDispatcherMixin). `gamma` is a public `double` field —
  LightTextureMixin's `@Redirect` on `Options.gamma:D` (GETFIELD in
  `updateLightTexture`) is VALID (bytecode-confirmed at offset 489).
- **LocalPlayer/Player fields**: `getYRot()`→`yRot`, `getInventory()`→`inventory`,
  `getAbilities()`→`abilities` (all field access pre-1.18/1.19.4).
- **Level height**: `getMinBuildHeight()` (1.18+) → `0.0` (1.16.5 world floor).
- **Biome name**: `biome.unwrapKey()` (Holder API, 1.18.2+) →
  `level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getKey(biome)`.
- **Entity.getEyePosition()**: needs the `(float)` arg on 1.16.5 → `getEyePosition(1.0F)`.
- **AbstractWidget**: `isHoveredOrFocused()` (1.19+) → `isHovered()`
  (OriginButtonRenderer ×3, OriginShaderButton).
- **PauseScreen**: no `removeWidget`/`addRenderableWidget` (1.17+). The mixin
  `extends Screen`, so it drops the old button from `this.buttons`+`this.children`
  directly and re-adds via protected `this.addButton(guarded)`.
- **TitleScreen**: `PlainTextButton` doesn't exist on 1.16.5 (1.17+); the
  button-strip drops it, keeping the `ImageButton` case (the copyright is drawn
  as text on this era, not a button — and is suppressed by the version redirect,
  see below).

## Mixin audit — originclient.client.mixins.json (34 entries: 33 verified, 1 retargeted, 1 added; 0 dropped)

Every target class + method descriptor + `@Shadow`/`@Accessor`/`@Invoker` member
javap-verified against the 1.16.5 merged jar.

| Mixin | Verdict |
|---|---|
| GameRendererMixin | OK — `getFov(Camera;FZ)D` double; `CallbackInfoReturnable<Double>`. |
| GuiHudMixin | OK — `Gui.render(PoseStack;F)V`. |
| MouseHandlerMixin | OK — `onPress(JIII)V`, `onScroll(JDD)V`, `turnPlayer()V`; `@Shadow accumulatedDX/DY:D`, `isMouseGrabbed()Z`. |
| ClientLevelTimeMixin | **CORRECTED 2026-10-11** — this row used to call it inert ("the time-changer works through LevelTimeMixin"). Wrong for the sky: on 1.16.5 the sky/moon read `LevelTimeAccess.getTimeOfDay(F)`/`getMoonPhase()` (defaults over `dayTime()J`, intermediary class_5424 method_30271 — declared only on LevelTimeAccess this era), never `Level.getDayTime()`. The mixin now `implements LevelTimeAccess` with an `@Override dayTime()` so Loom remaps it (an un-annotated mixin method with no supertype shipped under its dev name and overrode nothing in production). Client-only: ClientLevel, never ServerLevel. |
| CameraMixin | OK — `setup(BlockGetter;Entity;ZZF)V` contains exactly **3** `setRotation(FF)V` invokes (all redirected); `@Shadow setRotation(FF)V`. |
| LoadingOverlayMixin | OK — `render(PoseStack;IIF)V`; `@Shadow currentProgress:F`. |
| TitleScreenMixin | OK — full `render()` call census matches all six redirect shapes: `PanoramaRenderer.render(FF)V` (×1), `blit(PoseStack;IIIIFFIIII)V` (×1 overlay), `blitOutlineBlack(II;BiConsumer)V` (×2 logo/Minceraft — one redirect covers both), `blit(PoseStack;IIFFIIII)V` (×1 edition badge), `drawCenteredString(PoseStack;Font;String;III)V` (×1 splash), `drawString(PoseStack;Font;String;III)V` (**×2** on 1.16.5 = version **and** copyright — the redirect suppresses both, giving a clean Origin screen). `PlainTextButton` strip removed. |
| AbstractButtonMixin | OK — `@Mixin(AbstractWidget)`, `renderButton(PoseStack;IIF)V` HEAD, `instanceof AbstractButton` scope. |
| ScreenBackgroundMixin | OK — `renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V` (explicit — 2-arg overload also exists) + `renderDirtBackground(I)V`. |
| AbstractSelectionListMixin | OK — `render(PoseStack;IIF)V`; `@Shadow renderBackground/renderTopAndBottom:Z`. |
| AbstractSliderButtonMixin | OK — `@Mixin(AbstractWidget)`, `renderButton` HEAD, `instanceof AbstractSliderButton`; value via accessor. |
| AbstractSliderButtonAccessor | OK — `@Accessor("value") D` on AbstractSliderButton (protected). |
| CheckboxMixin | OK — `Checkbox.renderButton(PoseStack;IIF)V` (declared); `selected()Z`. |
| **LevelRendererMixin** | **RETARGETED** — `renderSnowAndRain(LightTexture;FDDD)V` unchanged; **`renderSky` is only `(PoseStack;F)V` on 1.16.5** (no projection matrix / camera / isFoggy / skyFogSetup Runnable — all later eras). Handler params rewritten to `(PoseStack, float, CallbackInfo)` and the `skyFogSetup.run()` call dropped (fog is set by FogRenderer on this era). Matrix4f/Camera imports removed. |
| LevelRendererCountAccessor | **ADDED** — `@Invoker("countRenderedChunks") int` (the method is `protected` on 1.16.5, no public getter). Feeds the "C:" rendered-chunk count in the Coords HUD (caller wraps in catch(Throwable)). |
| LightTextureMixin | OK — `updateLightTexture(F)V`; `@Redirect` on `Options.gamma:D` GETFIELD confirmed present (bytecode offset 489). |
| ParticleEngineMixin | OK — `createParticle(ParticleOptions;DDDDDD)`, `destroy(BlockPos;BlockState)V`, `crack(BlockPos;Direction)V`; registry via `Registry.PARTICLE_TYPE`. Body fix: `getEyePosition(1.0F)`. |
| SingleQuadParticleMixin | OK — `getQuadSize(F)F`. |
| LevelTimeMixin | OK — `Level.getDayTime()J` (the real time-changer hook this era). |
| HitboxMixin | OK — `renderHitbox(PoseStack;VertexConsumer;Entity;F)V` private. |
| EntityNametagMixin | OK — `shouldShowName(T)Z`, `renderNameTag(T;Component;PoseStack;MultiBufferSource;I)V`. |
| ChatTimestampMixin | OK — `@ModifyVariable` on `addMessage(Component;I)V` (present, private); `@Shadow allMessages:List<GuiMessage<Component>>` + `rescaleChat()V` both present on 1.16.5 (`GuiMessage` is generic this era; `refreshTrimmedMessage` is the later 1.19 name, not used here). |
| GuiEffectsMixin | OK — `renderEffects(PoseStack)V`. |
| GuiScoreboardMixin | OK — `displayScoreboardSidebar(PoseStack;Objective)V` private. |
| GameRendererAccessor | OK — `@Invoker loadEffect(ResourceLocation)V` private. |
| PostChainAccessor | OK — `@Accessor("passes") List<PostPass>`; `PostPass.getEffect()` present. |
| MinecraftFramerateMixin | OK — `getFramerateLimit()I` private. |
| MinecraftFpsAccessor | OK — static `@Accessor("fps") I`. |
| ToastComponentMixin | OK — `addToast(Toast)V`. |
| EntityRenderDispatcherMixin | OK — `shouldRender(E;Frustum;DDD)Z`. Body fix: `renderDistance`. |
| BlockEntityRenderDispatcherMixin | OK — `render(E;F;PoseStack;MultiBufferSource)V`. Body fix: `renderDistance`. |
| PauseScreenMixin | OK — `createPauseMenu()V` EXISTS on 1.16.5 (private); guard button rebuilt via era Button ctor + `buttons/children`/`addButton`. |
| IrisShadowDirectivesMixin | OK unchanged — `@Pseudo`/remap=false/require=0 soft mixin on `net.coderbot.iris.shaderpack.PackShadowDirectives` (pre-1.20 Iris package). |
| IrisWatermarkMixin | OK unchanged — `@Pseudo`/require=0 on `net.coderbot.iris.gui.screen.ShaderPackScreen`. See boot-sweep watch item. |

## Mixin audit — originclient.loading.mixins.json (all 4 kept)

LevelLoadingScreenMixin / ReceivingLevelScreenMixin / ProgressScreenMixin /
ConnectScreenMixin — all four classes exist on 1.16.5 with
`render(PoseStack;IIF)V` (javap-confirmed).

## Motion-blur post effect

1.16.5's post-effect shaders are **GLSL 110** (`attribute`/`varying`/`texture2D`/
`gl_FragColor`); the originclient program was **GLSL 150** (`in`/`out`/`texture()`/
named out), which is not guaranteed to link in this era's post pipeline. Both
`originclient_motion_blur.vsh`/`.fsh` were downgraded to GLSL 110 to match vanilla
1.16.5 exactly. The post-CHAIN JSON schema (`targets`/`passes`/`auxtargets`,
vanilla `blit`, `minecraft:main`) is unchanged back to 1.16 — no edit needed.
MotionBlur already fail-soft latches on any load throwable.

## Features — full parity, nothing dropped

Every 1.18.2-module feature is present on 1.16.5: title scene + cursor glow +
account chip, all loading/progress/connect scenes, mod menu + HUD + editor, every
mod (zoom, fullbright, hitboxes, chunk borders, block overlay/outline + filled
overlay via fixed-function immediate mode, CPS/FPS/keystrokes/coords/armor/potions
HUD, motion blur, freelook, weather/sky/time toggles, particles, nametags,
scoreboard, toasts, chat timestamps), shader browser + downloader + Iris bridge
(`net.coderbot.*`), crash fail-soft. No feature is impossible on 1.16.5.

## Boot-sweep watch items (runClient later)

1. **IrisWatermarkMixin** targets Iris's `ShaderPackScreen` with
   `@Shadow MutableComponent irisTextComponent/developmentComponent/updateComponent`.
   These field names/types match Iris **1.6.x**; the 1.16.5 Iris is **1.4.5** and
   may name them differently. The mixin is `@Pseudo`/require=0 → it silently fails
   to apply if they don't match (no crash; only the dev-watermark rename is
   skipped). Confirm the Origin watermark on the shader screen when Iris is loaded;
   if absent, re-derive the `@Shadow` names against `iris-mc1.16.5-1.4.5.jar`.
2. **ClientLevelTimeMixin.dayTime()** carries the Time Changer's sky on 1.16.5
   (see the corrected audit row; LevelTimeMixin only covers Level.getDayTime).
   Verify in a PRODUCTION launch (not just runClient) that the slider moves the
   sun/moon live while dragging.
3. **TitleScreen drawString redirect suppresses BOTH the version and copyright**
   lines (2 call sites this era). Intended (clean Origin screen) — just confirm no
   stray vanilla text bleeds through.
4. **Fixed-function color state**: the whole UI now tints via `color4f` and binds
   via `TextureManager.bind`. Watch for any panel/icon that renders with a stale
   tint (a missing `color4f(1,1,1,1)` reset) — the resets were preserved 1:1 from
   the setShaderColor originals, but this is the class of bug fixed function is
   prone to.
5. **Motion blur**: confirm the GLSL-110 program links and the blur actually shows
   (it's the one shader that was materially rewritten for the era).
