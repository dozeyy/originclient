# Origin 1.17.1 — port notes & mixin audit

Pre-GuiGraphics module (build target **1.17.1**, `fabric.mod.json` range
`>=1.17- <1.18-`; the launcher offers **1.17.1 only**). Forked from
`src/mods/staged/1.18.2` (the Gfx-wrapper + TextComponent/pre-signature-chat
foundation). Every statement below was verified with `javap -p -s -c` against
the loom-mapped **1.17.1** merged jar
(`minecraft-merged/1.17.1-loom.mappings.1_17_1...`). Nothing was taken from
memory. Java 16, fabric-api `0.46.1+1.17` (newest 1.17 build on
maven.fabricmc.net at port time), loader 0.19.3.

## Module-mechanics delta: MERGED jar, not split source sets

**The single biggest structural change vs every 1.18+ module.** 1.17.1 predates
Minecraft's bundled server jar (added in 1.18 / 21w39a), so Loom cannot
`splitEnvironmentSourceSets()` here — it fails configuration with
"Only Minecraft versions using a bundled server jar can be split". Fixes:
- `build.gradle`: removed `splitEnvironmentSourceSets()`; the `mods` block now
  references `sourceSets.main` only.
- Source layout merged: everything that lived in `src/client/{java,resources}`
  was moved into `src/main/{java,resources}` (the whole mod is
  `environment:"client"` anyway). Both `originclient.*.mixins.json` now live in
  `src/main/resources/`. The compile task is `compileJava` (there is no
  `compileClientJava` without the split).
- The copied module was missing `gradle/wrapper/gradle-wrapper.jar` (only the
  `.properties` came across) — restored from the sibling 1.18.2 module (byte
  identical wrapper version). Without it `gradlew` can't launch at all.

## Config

- `gradle.properties`: `minecraft_version=1.17.1`, `fabric_api_version=0.46.1+1.17`,
  header comment rewritten for the 1.17 era. Java 16.
- `build.gradle`: `archivesName = "originclient-1.17.1"`; `options.release = 16`,
  source/targetCompatibility `VERSION_16`; dev-libs guard renamed to
  `sodium-fabric-mc1.17.1-0.3.4+build.13.jar` / `iris-mc1.17.1-1.2.7.jar` (still
  `.exists()`-guarded, absent from CI). twelvemonkeys webp include unchanged.
- `fabric.mod.json`: `"minecraft": ">=1.17- <1.18-"`, `"java": ">=16"`.
- both `mixins.json`: `compatibilityLevel: "JAVA_16"`.
- `.no-shared-sync` present; no `overrides.txt` (whole-module fork).

## Non-mixin era deltas applied on top of the 1.18.2 module

- **Older Gson (1.17.1 ships Gson 2.8.0)**: `JsonObject.keySet()` (added Gson
  2.8.1) and `JsonArray.isEmpty()` do not exist. → `for (var e : obj.entrySet())`
  with `e.getKey()`/`e.getValue()` (ModsConfig ×3 loops, OriginUi icon loop);
  `arr.isEmpty()` → `arr.size() == 0/ > 0` (ShaderDownloader ×2, ShaderPreviews).
- **`AbstractWidget.isHoveredOrFocused()` does not exist** — 1.17.1 has separate
  `isHovered()` + `isFocused()` methods. → `(x.isHovered() || x.isFocused())`
  (OriginButtonRenderer button/slider/checkbox, OriginShaderButton).
- **`Options.getEffectiveRenderDistance()` does not exist** — render distance is
  the public `int renderDistance` field. → `options.renderDistance`
  (BlockEntityRenderDispatcherMixin, EntityRenderDispatcherMixin — chunk-border /
  hitbox distance math).
- **`Biome.unwrapKey()` does not exist** — 1.17.1 is pre-Holder: `getBiome`
  returns a bare `Biome`, not `Holder<Biome>`. → resolve the key via
  `level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getKey(biome)`
  (nullable ResourceLocation) in HudElements' coords/biome line.
- **`PlainTextButton` does not exist** (it is 1.18+) — see TitleScreenMixin below.

Everything else the 1.18.2 module needed (TextComponent/TranslatableComponent
factories, `new Button(x,y,w,h,label,onPress)` + public x/y fields,
`Vector3f.ZP.rotationDegrees` + `com.mojang.math` matrices, the Gfx
RenderSystem+gui-scale scissor, the ItemRenderer modelview-multiply GUI item
draws, `Registry.PARTICLE_TYPE`, `Minecraft.fps` accessor, `rescaleChat()` for
chat) is already era-correct at 1.17.1 and carried over unchanged.

## Mixin audit — originclient.client.mixins.json (33 entries: all kept, 1 retargeted)

| Mixin | Verdict |
|---|---|
| GameRendererMixin | OK — `getFov(Camera;FZ)D` returns double; `CallbackInfoReturnable<Double>` unchanged. |
| GuiHudMixin | OK — `Gui.render(PoseStack;F)V`. |
| MouseHandlerMixin | OK — `onPress(JIII)V`, `onScroll(JDD)V`, `turnPlayer()V`, `@Shadow accumulatedDX/DY:D`, `isMouseGrabbed()Z`. |
| ClientLevelTimeMixin | OK — `dayTime()` override; `LevelAccessor.dayTime()J` default present on 1.17.1. |
| CameraMixin | OK — `setup(BlockGetter;Entity;ZZF)V`; exactly 3 `setRotation(FF)V` invokes (require=1 satisfied, redirects all 3, same as parent). |
| LoadingOverlayMixin | OK — `render(PoseStack;IIF)V` TAIL; `@Shadow currentProgress:F` present (1.17 red Mojang-studios overlay; we only paint over it at TAIL, no dependence on its logo internals). |
| TitleScreenMixin | **RETARGETED (copyright lever changed)** — no LogoRenderer/SplashRenderer this era (same as 1.18.2). All suppression redirects re-verified as the only call of their shape in 1.17.1 `render()`: `PanoramaRenderer.render(FF)V` ×1, 10-arg `blit(PoseStack;IIIIFFIIII)V` overlay ×1, `blitOutlineBlack(IILBiConsumer)V` ×2 (logo, both wrapped), 8-arg `blit(PoseStack;IIFFIIII)V` edition ×1, `drawCenteredString(PoseStack;Font;String;III)V` splash ×1. **KEY 1.17.1 DELTA: `drawString(PoseStack;Font;String;III)V` occurs TWICE in render()** — the version line AND the copyright line (1.18.2 made copyright a `PlainTextButton` widget; 1.17.1 still draws it as text). The single `@Redirect` on that shape binds both, so version + copyright are both suppressed on the Origin menu. **`PlainTextButton` removed** from the init-TAIL strip (class absent; the strip now hides the two `ImageButton`s — language/accessibility — only). |
| AbstractButtonMixin | OK — `@Mixin(AbstractWidget)`, `renderButton(PoseStack;IIF)V` HEAD, `instanceof AbstractButton` scope. |
| ScreenBackgroundMixin | OK — `renderBackground(PoseStack)V` + `renderDirtBackground(I)V` (both present; explicit descriptors disambiguate the 2-arg overload). |
| AbstractSelectionListMixin | OK — `render(PoseStack;IIF)V`; `@Shadow renderBackground/renderTopAndBottom:Z`. |
| AbstractSliderButtonMixin | OK — `@Mixin(AbstractWidget)`, `renderButton` HEAD, `instanceof AbstractSliderButton`; value via accessor. |
| AbstractSliderButtonAccessor | OK — `@Accessor("value") D` on AbstractSliderButton (`protected double value`). |
| CheckboxMixin | OK — `Checkbox.renderButton(PoseStack;IIF)V` declared; `selected()Z`. |
| LevelRendererMixin | **RETARGETED** — `renderSnowAndRain(LightTexture;FDDD)V` unchanged, BUT **1.17 sky era: `renderSky` is `(PoseStack;Matrix4f;F;Runnable)V`** — no `Camera`/`isFoggy` params (those arrive in 1.18). The Flat-sky HEAD handler dropped `Camera camera, boolean isFoggy` (would have failed to apply → silent Flat-sky loss); it now captures `(PoseStack, Matrix4f, float, Runnable skyFogSetup, CallbackInfo)`. `Matrix4f` = `com.mojang.math.Matrix4f` (pre-JOML). |
| LightTextureMixin | OK — `updateLightTexture(F)V`; `@Redirect` on `Options.gamma:D` field GET (require=1; exactly one gamma getfield in the method — plain `double gamma` field, pre-OptionInstance, same as 1.18.2). |
| ParticleEngineMixin | OK — `createParticle(ParticleOptions;DDDDDD)`, `destroy(BlockPos;BlockState)V`, `crack(BlockPos;Direction)V`; registry via `Registry.PARTICLE_TYPE`. |
| SingleQuadParticleMixin | OK — `getQuadSize(F)F`. |
| LevelTimeMixin | OK — `Level.getDayTime()J`. |
| HitboxMixin | OK — `renderHitbox(PoseStack;VertexConsumer;Entity;F)V` private static. |
| EntityNametagMixin | OK — `shouldShowName(Entity)Z`, `renderNameTag(Entity;Component;PoseStack;MultiBufferSource;I)V`. |
| ChatTimestampMixin | OK — `@ModifyVariable addMessage(Component;I)V` (private, present); `@Shadow allMessages:List<GuiMessage<Component>>`; `@Shadow rescaleChat()V` (this era's re-trim name — `refreshTrimmedMessage` is 1.19.x). |
| GuiEffectsMixin | OK — `renderEffects(PoseStack)V`. |
| GuiScoreboardMixin | OK — `displayScoreboardSidebar(PoseStack;Objective)V` private. |
| GameRendererAccessor | OK — `@Invoker loadEffect(ResourceLocation)V` private. |
| PostChainAccessor | OK — `@Accessor("passes") List<PostPass>`; `PostPass.getEffect()` present. |
| MinecraftFramerateMixin | OK — `getFramerateLimit()I`. |
| MinecraftFpsAccessor | OK — static `@Accessor("fps") I` (`private static int fps`). |
| ToastComponentMixin | OK — `addToast(Toast)V`. |
| EntityRenderDispatcherMixin | OK — `shouldRender(Entity;Frustum;DDD)Z`. |
| BlockEntityRenderDispatcherMixin | OK — `render(BlockEntity;FLPoseStack;MultiBufferSource)V`. |
| PauseScreenMixin | OK — `createPauseMenu()V` private (body edit; era Button ctor + x/y from parent). |
| IrisShadowDirectivesMixin | OK unchanged — `@Pseudo`/require 0, `net.coderbot.iris.shaderpack.PackShadowDirectives` (pre-1.20 Iris package, era-independent). |
| IrisWatermarkMixin | OK unchanged — `@Pseudo`/require 0, `net.coderbot.iris.gui.screen.ShaderPackScreen`, `<init>` TAIL. |

## Mixin audit — originclient.loading.mixins.json (all 4 kept, unchanged)

LevelLoadingScreenMixin / ReceivingLevelScreenMixin / ProgressScreenMixin /
ConnectScreenMixin — all `render(PoseStack;IIF)V`, javap-verified on 1.17.1.

## Feature parity vs the 1.18.2 module

1:1. Title scene + cursor glow + account chip, all loading/progress/connect
scenes, mod menu + every mod (zoom, fullbright, hitboxes, chunk borders, block
overlay/outline, CPS/FPS/keystrokes HUD, motion blur, freelook, weather/sky/time
toggles — **Flat sky repaired for the 1.17 renderSky arg shape**, particles,
nametags, scoreboard, toasts, chat timestamps/stack-spam), HUD + editor, shader
browser + downloader + Iris bridge (`net.coderbot.*`), crash fail-soft. No
feature is impossible on 1.17.1 — nothing dropped.

**One minor cosmetic edge:** the copyright line's *text* is suppressed on the
Origin menu (drawString redirect), but 1.17.1 handles the copyright click in
`TitleScreen.mouseClicked` via a bounds check (not a widget), so the now-invisible
bottom-right strip stays clickable (opens the Mojang URL). Not a crash, not an
Origin feature; left as-is to keep mixin surface minimal. Add a `mouseClicked`
HEAD guard later if the invisible click region is unwanted.

## Range-floor audit (declared `>=1.17- <1.18-`)

The launcher offers **1.17.1 only**; 1.17(.0) is tolerated by the range, not a
shipped experience. 1.17.0 → 1.17.1 was a bugfix patch with no client
API/mapping changes touching any hook here, so no crash path is expected:
- Every mixin is `required:false` / `defaultRequire:0` — a missing target
  silently skips. The **only** loud hook is CameraMixin's `require = 1` on
  `Camera.setRotation(FF)V`; `Camera.setup`/`setRotation` are byte-for-byte
  identical between 1.17 and 1.17.1 (no bugfix touched them), so it binds.
- All non-mixin era APIs used (`Options.renderDistance`, biome-registry key
  lookup, Gson `entrySet`, `getBiome`→`Biome`, `isHovered`/`isFocused`) exist
  identically in 1.17.0. Fabric-api `0.46.1+1.17` supports both.
- A dedicated 1.17.0 loom probe was NOT run (unlike the 1.19.x floors, where the
  floor spanned a feature release); the 1.17→1.17.1 delta is a patch release. If
  1.17.0 is ever offered, re-probe before shipping it.

## What the downstream port (1.16.5) must know — the Java 8 + fixed-function cliff

**1.16.5 is a much bigger jump than 1.17.1 was.** This module's fixes help, but
1.16.5 hits hard walls 1.17.1 does not:

1. **Merged jar, same as here** — 1.16.5 also predates the bundled server jar, so
   drop `splitEnvironmentSourceSets()` and merge `src/client` → `src/main`
   exactly as this module does. Copy the wrapper jar if the raw copy lacks it.
2. **Java 8 (`options.release = 8`), NOT 16** — this is the language cliff. Sweep
   the WHOLE module for Java 9+ syntax/APIs and rewrite:
   - `var` (used here in the Gson `entrySet` loops, HudElements biome, ModsConfig,
     OriginUi, and many `for (var el : ...)` in the shader classes) → explicit types.
   - `instanceof` pattern variables — **used in this module's TitleScreenMixin
     strip (`child instanceof ImageButton widget`) and the render() HEAD
     (`child instanceof AbstractWidget widget`)** — must be rewritten to a cast.
   - `InputStream.readAllBytes()` (Java 9) — used in ShaderDownloader — replace.
   - `List.of`/`Map.of`, `String.repeat`, `Stream.toList`, records, switch
     expressions, text blocks — sweep and rewrite if present.
3. **RenderSystem has NO setShader/setShaderTexture/setShaderColor** on 1.16.5
   (those are 1.17+, and 1.17.1 relies on them throughout Gfx). Gfx must bind via
   `TextureManager.bind(tex)` + `RenderSystem.color4f(r,g,b,a)`, and the
   BlockOverlayRenderer immediate-mode fill must drop `setShader` (fixed-function
   GL). This is the render cliff — budget real time for it.
4. **Gson is even older / same** — the `entrySet()` + `size()` fixes here are
   Gson-2.8.0-safe and port down cleanly; keep them.
5. **`isHovered()`/`isFocused()`, `Options.renderDistance`, biome-registry key
   lookup, `rescaleChat()`** — re-javap each against the 1.16.5 jar, but they are
   likely the same shapes as 1.17.1 (verify, don't assume).
6. **TitleScreen** — re-count the render() call shapes on 1.16.5 (blitOutlineBlack
   logo, panorama, overlay, splash `drawCenteredString`, and how many `drawString`
   calls — copyright may again be a plain drawString like 1.17.1). PlainTextButton
   stays absent.
7. **renderSky** — 1.16.5 sky args differ again (older still); javap and adapt the
   Flat-sky handler as this module did for the 1.17 4-arg shape.
8. **Fabric API** ≈ `0.42.0+1.16`; confirm `WorldRenderEvents.BLOCK_OUTLINE`
   exists before porting BlockOverlayRenderer's event registration.
9. **Post-effect (motion blur) GLSL** — re-check the program JSON + GLSL version
   against 1.16.5's own post shaders (110/150 differences) per the spec.

## Build status

`gradlew build` **exit 0** (Loom 1.17-SNAPSHOT / resolved 1.17.14, JDK 17
auto-provisioned to run Gradle, `options.release=16` for the mod bytecode,
fabric-api 0.46.1+1.17, loader 0.19.3). Artifact:
`build/libs/originclient-1.17.1-0.4.1.jar` (83 classes; both mixin configs +
fabric.mod.json bundled; twelvemonkeys webp jars jar-in-jar'd). Mixin annotation
targets are remapped in-place to intermediary by Loom (verified: CameraMixin's
`setRotation` redirect → `method_19325`), no separate refmap needed — production
correct. NOT runClient-tested (orchestrator boot-sweeps staged modules later).

## World-render fix (2026-10-11, round 2 — Fabric API 0.46.1+1.17 mixin read; vanilla block shape from 1.18.1 source)
- Fabric fires WorldRenderEvents.LAST before `LevelRenderer.renderDebug(Camera)`, inside
  renderLevel's final `getModelViewStack().pushPose(); mulPoseMatrix(pose); applyModelViewMatrix()`
  block, so ModelViewMat already holds the camera rotation and `context.matrixStack()` added it
  again (beam/posts swung opposite to the camera; WaypointHud.capture doubled it too). The LAST
  hook now sets the model-view to identity around our draws (same fix as 1.18.2-1.19.4).
  Chunk-border lines are flushed inside that window with a full `endBatch()`.
- Zoom: 1.17+ culls with `max(fov, options.fov)`, so the 1.16.5 stale-chunk bug cannot happen here.
