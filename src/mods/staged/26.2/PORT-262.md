# Origin Client — 26.2 port status & guide

26.2 is the **unobfuscated** MC era (Java 25). The toolchain is solved; the render
layer is being re-architected onto 26.2's **retained-mode** GUI. This file is the
working guide for finishing that port.

## Toolchain (DONE — see build.gradle / gradle.properties / settings.gradle)
- Java 25 required. Loom checks the JVM running Gradle → run with `JAVA_HOME`
  pointing at a JDK 25 (local dev: `C:\Users\Will\.jdks\jdk-25.0.3+9`). CI: add 25
  via setup-java and set `JAVA_HOME=${{ env.JAVA_HOME_25_X64 }}` on the 26.2 step.
- Plugin id `net.fabricmc.fabric-loom` (Loom 1.17.x), **no `mappings` line**
  (unobfuscated — Mojang ships no client_mappings), plain `implementation`,
  `options.release = 25`. Loom downloads + remaps MC 26.2 and compiles against it.
- Build: `JAVA_HOME=<jdk25> ./gradlew build` (or compileClientJava). First run
  downloads MC 26.2. Reference jar for javap: `~/.gradle/caches/fabric-loom/26.2/
  minecraft-client.jar`.

## Class renames (mojmap → 26.2 official). Most names are KEPT.
| old (1.21.1) | 26.2 |
|---|---|
| `net.minecraft.client.gui.GuiGraphics` | `net.minecraft.client.gui.GuiGraphicsExtractor` |
| `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` |
| `net.minecraft.client.renderer.MultiBufferSource` | `net.minecraft.client.renderer.SubmitNodeCollector` |
| `net.minecraft.client.renderer.LightTexture` | `net.minecraft.client.renderer.Lightmap` |
KEPT: `Minecraft`, `Component`, `Screen`, `Font`, `PoseStack`(world), `NativeImage`,
`DynamicTexture`, `Axis`. `Identifier.fromNamespaceAndPath(ns,path)` is unchanged
in shape.

## Draw-call mapping (on `GuiGraphicsExtractor g`)
- `g.fill(x1,y1,x2,y2,argb)` — UNCHANGED.
- `g.drawString(font,s,x,y,argb,shadow)` → `g.text(font,s,x,y,argb,shadow)`
  (String / Component / FormattedCharSequence overloads; returns void).
- Tint: there is **no `RenderSystem.setShaderColor`** — fold the tint into a blit
  `color` arg. Remove `RenderSystem.enableBlend/defaultBlendFunc/disableBlend`
  (blend is per-`RenderPipeline`). Import
  `net.minecraft.client.renderer.RenderPipelines` and use `RenderPipelines.GUI_TEXTURED`.
- blit — old GuiGraphics had two arg orders; both fold into the region-scaling
  overload (import above):
  `g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, (float)u, (float)v, srcW, srcH, dstW, dstH, texW, texH, argb)`
  - full texture (old `blit(id,x,y,u,v,w,h,tw,th)`): srcW/H = dstW/H = w/h.
  - region-scaled 9-slice (old `blit(id,x,y,dstW,dstH,u,v,srcW,srcH,tw,th)`): as-is.
  - untinted → argb = `0xFFFFFFFF`; alpha-only → `(alpha255<<24)|0x00FFFFFF`.
- `pose()` now returns `org.joml.Matrix3x2fStack` (2D):
  `pushPose/popPose`→`pushMatrix/popMatrix`; `translate(x,y,z)`→`translate((float)x,(float)y)`;
  `scale(sx,sy,1f)`→`scale(sx,sy)`; `mulPose(Axis.ZP.rotationDegrees(d))`→
  `rotate((float)Math.toRadians(d))`.

## Texture registration
- `new DynamicTexture(image)` → `new DynamicTexture(() -> name, image)` (Supplier<String>).
- `texture.setFilter(true,false)` — **removed** (no setFilter on DynamicTexture in
  26.2). Dropped for now; if rings/wordmark look blocky in runClient, set filtering
  on the GpuTexture (revisit).
- `mc.getTextureManager().register(id, texture)` — UNCHANGED.

## Screens & mixins — the retained-mode shift (BIGGEST remaining work)
`Screen` has **no `render(...)`** — only `extractRenderState(GuiGraphicsExtractor,
int mouseX, int mouseY, float partialTick)`. So:
- Screen subclasses (OriginModMenuScreen, HudEditorScreen, OriginColorPicker):
  override `extractRenderState` instead of `render`; draw via the extractor.
- Every mixin that `@Inject`s into a `render(GuiGraphics,...)` must retarget
  `extractRenderState(GuiGraphicsExtractor,...)` (method name + descriptor). The
  entity/particle/world mixins moved to the submit-node / render-state extractor
  model (`SubmitNodeCollector`, `*RenderStateExtractor`, `Lightmap`) — verify each
  target with javap against the 26.2 jar before wiring the inject point.

## Mixin retarget map (verified via javap on the 26.2 jar)
The immediate-mode `render*`/`renderBackground` hook points are gone; retarget to
the extraction methods (param `GuiGraphics`→`GuiGraphicsExtractor`):
- Widgets — `AbstractButton.renderWidget` → `AbstractButton.extractWidgetRenderState`
  (**protected final** — mixin @Inject still works on final). `AbstractSliderButton`
  has its own public `extractWidgetRenderState`.
- **Checkbox has NO own extract method** (inherits AbstractButton's final one) — the
  old `CheckboxMixin` (targeted `Checkbox.renderWidget`) needs a rethink: either
  detect Checkbox instances inside the AbstractButton hook, or find Checkbox's new
  draw path. Not a mechanical rename.
- Screen backdrop — `Screen.renderBackground` / `renderMenuBackgroundTexture` are
  GONE. Entry is `Screen.extractRenderState(GuiGraphicsExtractor,int,int,float)`
  (inject HEAD, draw backdrop first). No separate list-texture method to cancel —
  the list backdrop handling changed; re-derive.
- Title — `TitleScreen` has `extractBackground(...)` (backdrop) AND
  `extractRenderState(...)` (overlays: wordmark/chip/glow). Split the old
  TitleScreenMixin hooks across these two.
- HUD — check `net.minecraft.client.gui.Gui`/`Hud` for the new per-element extract
  methods. Entity/particle/world mixins → submit-node model (`SubmitNodeCollector`,
  `*RenderStateExtractor`, `Lightmap`); javap each target before wiring.

## Core-first strategy (in progress)
The module is all-or-nothing to compile, so we drive to a bootable CORE first:
port the title/menu/loading/widget surfaces, and DISABLE the peripheral mixins
(they're `required:false` → fail-soft to vanilla). Disabled mixins were **moved
out of the source set** to `disabled262/` (javac still compiles anything under
`src/`, so removing from the JSON is not enough) and stripped from the two
`*.mixins.json`. Restore + port them from `disabled262/` incrementally later.

## More 26.2 API surprises found
- `KeyMapping` category is now a `KeyMapping.Category` record, not a String:
  `KeyMapping.Category.register(Identifier.fromNamespaceAndPath("originclient","main"))`.
- `ReceivingLevelScreen` was **removed** → its mixin is disabled (dropped).
- `LevelLoadingScreen`/`ProgressScreen`/`ConnectScreen` each declare their own
  `extractRenderState` (good mixin targets).

## Fabric API RENAMES in 26.2 (NOT a classpath issue — corrected)
The "package does not exist" errors were Fabric API **renames** in 0.146.2+26.2,
confirmed by cracking open Lunar's bundled 26.2 fabric-api jars (its 26.2 profile
`~/.lunarclient/profiles/26/mods/fabric-26.2-snapshot-4/dependencies/`). (The
misleading part: an OLDER `fabric-key-binding-api-v1` jar sat in the gradle cache
from another version — ignore it; the resolved 0.146.2+26.2 modules are the truth.)
| old (1.21.1 fabric-api) | 26.2 fabric-api |
|---|---|
| `client.keybinding.v1.KeyBindingHelper` | `client.keymapping.v1.KeyMappingHelper` |
| `KeyBindingHelper.registerKeyBinding(km)` | `KeyMappingHelper.registerKeyMapping(km)` |
| `client.rendering.v1.WorldRenderEvents` | `client.rendering.v1.level.LevelRenderEvents` (+ `LevelExtractionEvents`) |
| `client.rendering.v1.WorldRenderContext` | `client.rendering.v1.level.LevelRenderContext` (+ `LevelExtractionContext`) |
| (no "HUD layer" API existed) | `client.rendering.v1.hud.HudElementRegistry` / `HudElement` / `VanillaHudElements` |
`client.screen.v1.ScreenEvents` / `Screens` — UNCHANGED.
DONE: `OriginKeyBindings` (KeyMappingHelper.registerKeyMapping). TODO: the world-
render events shape changed (Level*Context is not a drop-in for WorldRenderContext),
so `mods/BlockOverlayRenderer`/`ChunkBorderRenderer` + the `OriginClientMod`
WorldRenderEvents.LAST/BLOCK_OUTLINE registrations need real rework against
LevelRenderEvents (peripheral — can be stubbed for the first bootable core).
**HUD win:** re-do the disabled `GuiHudMixin` as a `HudElementRegistry` registration
(the proper 26.2 "draw after everything" HUD layer) instead of a Gui.render mixin —
check `HudElement`'s callback for the GuiGraphicsExtractor it hands you.

## Also disabled (peripheral, non-mechanical API changes)
- `PauseScreenMixin` — `Button.onPress` now takes `InputWithModifiers` and the
  `ConfirmScreen` ctor changed; smart-disconnect is a nice-to-have, deferred.

## Vanilla API changes found during the screen/HUD port (all applied)
- **Mouse events** (Screen overrides): `mouseClicked(double,double,int)` →
  `mouseClicked(MouseButtonEvent, boolean)`; `mouseReleased(double,double,int)` →
  `mouseReleased(MouseButtonEvent)`; `mouseDragged(double,double,int,double,double)`
  → `mouseDragged(MouseButtonEvent, double, double)`. Unpack via `event.x()/y()/
  button()`. `mouseScrolled(double,double,double,double)` UNCHANGED.
- `Minecraft.setScreen(...)` → **`setScreenAndShow(...)`**.
- **`Minecraft.screen` field REMOVED** (no field/getter named `screen`; screen
  mgmt was restructured). Origin now tracks it itself: `OriginScreenState.current`
  fed by `MinecraftScreenTrackerMixin` (@Inject setScreenAndShow HEAD). All
  `client.screen`/`mc.screen` reads were swapped to `OriginScreenState.current`.
- **`MobEffects` constants renamed to Yarn-style**: `MOVEMENT_SPEED`→`SPEED`,
  `DAMAGE_BOOST`→`STRENGTH` (check others as they surface: `MOVEMENT_SLOWDOWN`→
  `SLOWNESS`, `DIG_SPEED`→`HASTE`, `DIG_SLOWDOWN`→`MINING_FATIGUE`, ...).
- `pose()` is `Matrix3x2fStack`: `translate(x,y,z)`→`translate((float)x,(float)y)`,
  `scale(sx,sy,1f)`→`scale(sx,sy)`.

## World-render mods = DEEP rework (stubbed for the first boot)
`mods/ChunkBorderRenderer` + `BlockOverlayRenderer` (and the entrypoint's
`WorldRenderEvents.LAST`/`BLOCK_OUTLINE` registrations) need a real rewrite onto
26.2's new pipeline, not a rename. Findings:
- `WorldRenderContext` → `rendering.v1.level.LevelRenderContext`:
  `consumers()`→`bufferSource()`, `matrixStack()`→`poseStack()`, and it has NO
  `camera()` (get it from `Minecraft.getInstance().gameRenderer.mainCamera()`).
- Events: `WorldRenderEvents.LAST`→`LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`
  (callback gives `LevelRenderContext`); `WorldRenderEvents.BLOCK_OUTLINE`→
  `LevelRenderEvents.BEFORE_BLOCK_OUTLINE` — but its 2nd arg is now a
  `BlockOutlineRenderState` (extraction model), NOT the old BlockOutlineContext
  (blockPos()/blockState()/entity()/cameraX()); re-derive block data from it.
- **`RenderType` moved to `net.minecraft.client.renderer.rendertype.RenderType`
  AND `RenderType.lines()` is GONE**; `net.minecraft.client.renderer.MultiBufferSource`
  is gone too. Debug-line drawing moved to the new submit/gizmo pipeline (see
  `LevelRenderEvents.BEFORE_GIZMOS` / `SubmitNodeCollector`). VertexConsumer
  `addVertex(pose,…)`/`setColor`/`setNormal(pose,…)` DO survive.
- `ClientLevel.getMinBuildHeight()/getMaxBuildHeight()` → `getMinY()/getMaxY()`.
Decision: these peripheral overlays are STUBBED (moved to disabled262/, entrypoint
registrations commented) so the core boots; port them onto the gizmo/submit
pipeline as a focused follow-up.

## File status
DONE — ported (the 3 renderers are compiler-verified clean; the rest apply the
verified patterns, full-module compile pending):
- `client/gui/OriginButtonRenderer.java`, `client/gui/OriginUi.java`,
  `client/render/OriginScreenRenderer.java` (account chip uses the Origin mark —
  26.2 removed PlayerFaceRenderer.draw / SkinManager.getInsecureSkin)
- `client/OriginKeyBindings.java` (KeyMapping.Category)
- mixins: `AbstractButtonMixin`, `AbstractSliderButtonMixin`
  (renderWidget→extractWidgetRenderState); `loading/LevelLoadingScreenMixin`,
  `loading/ProgressScreenMixin`, `loading/ConnectScreenMixin` (render→extractRenderState)
DONE — core KEEP mixins (all 6 client + 3 loading ported to the extraction model):
  `TitleScreenMixin` (extractBackground HEAD-cancel for the backdrop; @Redirect
  LogoRenderer/SplashRenderer.extractRenderState + the version text() in
  extractRenderState), `ScreenBackgroundMixin` (extractBackground +
  extractMenuBackgroundTexture), `PauseScreenMixin` (createPauseMenu→init;
  KeyMapping-free), `LoadingOverlayMixin` (Overlay.extractRenderState TAIL),
  `AbstractButton`/`AbstractSliderButton` (extractWidgetRenderState),
  `loading/{LevelLoadingScreen,ProgressScreen,ConnectScreen}` (extractRenderState).
  `GuiHudMixin` DISABLED — the HUD is a separate re-arch:
  `Gui.extractRenderState(DeltaTracker, boolean, boolean)` has NO GuiGraphicsExtractor
  param, so the old "draw HUD at Gui.render RETURN" lever is gone; needs a new
  draw hook (a 26.2 Fabric HUD layer API or a different extractor-carrying method).
TODO — Screen subclasses → override `extractRenderState`:
  `OriginModMenuScreen`, `HudEditorScreen`, `OriginColorPicker`.
TODO — non-mixin version-touching (referenced by the entrypoint, must compile):
  `hud/HudElements` (+ `OriginHud`), `mods/BlockOverlayRenderer`,
  `mods/ChunkBorderRenderer`, `mods/MotionBlur`, `shaders/OriginShaderButton`,
  `shaders/ShaderBrowserScreen`; and fix any changed vanilla calls in
  `OriginClientMod` (e.g. `setRenderHitBoxes`, options accessors).
TODO — `disabled262/` mixins (~23): port + restore to JSON one/two at a time,
  each javap-verified, re-running runClient between batches. `CheckboxMixin` needs
  a rethink (Checkbox has no own extract method — inherits AbstractButton's final).
  Entity/particle/world ones → SubmitNodeCollector / Lightmap submit-node model.

## Verify (the ship gate)
`JAVA_HOME=<jdk25> ./gradlew runClient` → confirm zero `Mixin apply ... failed`
lines; then check title screen / mod menu / HUD / loading screens render in Origin
style and Iris+Sodium shaders load. Only then re-enable `OriginBuilds["26.2"]`, the
csproj entry, and the CI 26.2 build steps (all currently staged out).
