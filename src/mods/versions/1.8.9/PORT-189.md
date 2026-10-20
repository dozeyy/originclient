# Porting Origin Client to 1.8.9 (Forge) — the brief

Will's decision (2026-10-13): 1.8.9 comes back LIVE in the picker with the FULL
current Origin experience (everything in launcher-v1.0.48: the OriginDeck mod
menu + Right Shift screen, every Origin window, title/loading/pause screens,
widget skin, the Lunar-parity mod set, cosmetics, Friends/party, Portal
Calculator, FPS Coach, ...). Stack: **Forge 11.15.1.2318 + OptiFine HD_U_M5**
(the only way 1.8.9 gets shaders; the player never picks a loader — the
launcher installs it quietly). One port, all at once.

**Reference = the 1.16.5 module** (`src/mods/versions/1.16.5`): it is already
Java 8 and fixed-function GL, and it carries every current feature. Port it
file by file into this module, keeping the SAME package layout
(`com.origin.client.client.*`) and the SAME public class/method names and
shapes, with Minecraft types swapped per the map below. That shared shape is
the contract between the porting agents: code in one package calls another
package's 1.16.5 API and it must compile against the 1.8.9 port of it.
When 1.16.5 and 1.21.1 differ in behaviour, 1.21.1 is the design truth
(`src/mods/versions/1.21.1`); 1.16.5 is the closest-era implementation.
Era notes for every port so far: `src/mods/docs/PORTING-ERA-NOTES.md`.

The OLD 1.8.9 module (pre-port, Forge-event only, ~4.8k lines) is at commit
`a2fb8a1` — `git show a2fb8a1:src/mods/versions/1.8.9/src/main/java/com/origin/client/<path>`.
It compiled and boot-tested on 1.8.9, so its API usage is VERIFIED 1.8.9 MCP —
use it as a name reference (render/OriginLoadingRenderer.java replaced
`mc.loadingScreen`; util/Gl.java; gui/OriginUi.java; hud/HudElements.java).

## Toolchain (already set up — don't change build.gradle without asking)
- Forge 1.8.9-11.15.1.2318, MCP `stable_22` names, gg.essential.loom 1.3.12,
  Gradle 8.7, **Java 8 language level** (records, `var`, switch expressions,
  text blocks, `List.of`, `String.isBlank`, `InputStream.readAllBytes`,
  `Files.readString`, `Stream.toList` are all unavailable — 1.16.5 is already clean).
- **Mixin 0.7.11** (shadowed into the jar, loaded by `MixinTweaker`). Six
  configs, one per area, all `required: false` + `defaultRequire: 0`:
  `src/main/resources/originclient.<area>.mixins.json`, package
  `com.origin.client.client.mixin.<area>` (areas: core, menus, hud, world,
  social, server). Add your mixin class names to YOUR area's `"client"` list.
  Mixin 0.7.11 limits: no MixinExtras (@WrapOperation/@Local/@ModifyExpressionValue
  don't exist), @Accessor/@Invoker DO exist, @Inject/@Redirect/@ModifyArg/
  @ModifyVariable/@ModifyConstant/@Shadow work. Never @Overwrite.
- **Runtime Java is Mojang's `jre-legacy` (Java 8, possibly as old as 8u51).**
  Its CA store lacks Let's Encrypt / Google Trust Services roots → plain
  HttpsURLConnection to Cloudflare-hosted Origin services or modern APIs can
  fail with PKIX errors. ALL HTTPS goes through the foundation's
  `com.origin.client.client.net.OriginHttp` (bundled trust roots + TLS 1.2).
- Libraries on 1.8.9's classpath: LWJGL **2.9.4** (org.lwjgl.input.Keyboard /
  Mouse, org.lwjgl.opengl.Display, GL11–GL30; NO GLFW, NO stb), Guava 17,
  **Gson 2.2.4** (NO JsonObject.keySet()/size(), NO JsonArray.isEmpty()/remove(),
  NO JsonElement.deepCopy(), NO JsonParser.parseString — use
  `new JsonParser().parse(s)`, `entrySet()`, `JsonArray.size()`), commons-io 2.4,
  commons-lang3 3.3.2, Netty 4.0, log4j 2.0-beta9, authlib 1.5.21.
- Text: smooth Origin text is the foundation's AWT-rasterised Inter
  (`gui/OriginTtfFont`, same role as 1.16.5's stb version); vanilla text is
  `FontRenderer` (`mc.fontRendererObj`).

## Hooks: Forge event first, Mixin second, accessors over reflection
1. **Forge event** when Forge fires one at the right moment (GuiOpenEvent,
   GuiScreenEvent.InitGuiEvent/DrawScreenEvent/ActionPerformedEvent/
   MouseInputEvent/KeyboardInputEvent, RenderGameOverlayEvent.Pre/Post per
   ElementType, RenderWorldLastEvent, DrawBlockHighlightEvent,
   RenderLivingEvent.Specials (nametags), RenderPlayerEvent,
   EntityViewRenderEvent (FOVModifier, CameraSetup, FogColors...), FOVUpdateEvent,
   ClientChatReceivedEvent, PlaySoundEvent, MouseEvent, TickEvent
   (ClientTick/RenderTick), InputEvent.KeyInputEvent, FMLNetworkEvent
   connect/disconnect, TextureStitchEvent). Subscribe in YOUR area's hooks class
   (`com.origin.client.client.hooks.<Area>Hooks`, see Ownership) — the
   foundation registers each hooks class on both buses
   (`MinecraftForge.EVENT_BUS` + `FMLCommonHandler.instance().bus()`; in 1.8.9
   TickEvent/InputEvent/FMLNetworkEvent fire on the FML bus).
2. **Mixin** when no event exists. **OptiFine is on every player's game** and
   REPLACES whole vanilla classes (EntityRenderer, RenderGlobal, ItemRenderer,
   RendererLivingEntity, RenderItem, FontRenderer, GuiVideoSettings, GameSettings,
   TextureManager, ModelRenderer, parts of Minecraft...). Its classes keep
   vanilla method names/descriptors but not vanilla method BODIES, so:
   HEAD / RETURN / TAIL injections are robust; @Redirect / INVOKE / FIELD /
   ordinal-based injection points inside those classes may silently miss with
   OptiFine (dev shots run WITHOUT OptiFine, so they won't show it). Prefer
   HEAD/RETURN (+cancel) in OptiFine-replaced classes and say in a comment
   when a hook is INVOKE-based in one of them. @Shadow only fields/methods that
   are vanilla API (OptiFine keeps those).
3. **Private members**: prefer a Mixin `@Accessor` / `@Invoker` interface in
   your area's mixin package — the refmap translates its MCP name to SRG at
   build time, so it is correct in production with no SRG bookkeeping. Cast
   the instance to the accessor (`((GuiIngameAccessor) mc.ingameGUI).origin$getDisplayedTitle()`)
   and guard with `instanceof` so a failed accessor = feature off, not a
   ClassCastException. Forge's own classes (GuiIngameForge, net.minecraftforge.*)
   are not obfuscated: mixins/accessors into Forge-added members need
   `remap = false`.
4. **Reflection** (last resort, e.g. classes from other mods like OptiFine): ALWAYS through
   `com.origin.client.client.util.Reflect` with BOTH names — the MCP name
   (dev/runClient) and the SRG name (players' production Forge runs SRG-named):
   `Reflect.field(GuiIngame.class, "displayedTitle", "field_175201_x")`.
   An unknown SRG name is a bug for players even though dev works — get it
   from the ref-189 `srg` lookup (below) or list it in your report as
   "SRG NEEDED: Class.member". Fail soft: a missing member = feature off.
Mixin injection targets use MCP names only (the refmap maps them to SRG).

Every hook body is fail-soft: catch Throwable around Origin work in event
handlers and mixin callbacks; a broken feature = vanilla behaviour, never a
crash (mandate 4). Same volatile-latch pattern the other versions use.

## Type / name map (1.16.5 Mojmap → 1.8.9 MCP)
| 1.16.5 | 1.8.9 |
|---|---|
| `Minecraft.getInstance()` | `Minecraft.getMinecraft()` |
| `mc.player` / `mc.level` / `mc.screen` / `mc.font` / `mc.options` | `mc.thePlayer` / `mc.theWorld` / `mc.currentScreen` / `mc.fontRendererObj` / `mc.gameSettings` |
| `mc.setScreen(s)` | `mc.displayGuiScreen(s)` |
| `mc.getWindow().getGuiScaledWidth()` etc. | `new ScaledResolution(mc)` `.getScaledWidth()/getScaledHeight()/getScaleFactor()`; window px = `mc.displayWidth/displayHeight` |
| `Screen` / `Button` / `AbstractWidget` / `EditBox` | `GuiScreen` / `GuiButton` / `GuiButton` / `GuiTextField` |
| `Screen.init()` / `render(PoseStack,mx,my,pt)` / `mouseClicked(mx,my,btn)` → bool / `keyPressed` / `charTyped` / `mouseScrolled` / `onClose` / `removed` | `initGui()` / `drawScreen(mx,my,pt)` / `mouseClicked(mx,my,btn)` void throws IOException / `keyTyped(char,int)` throws IOException / — / `handleMouseInput()` + `Mouse.getEventDWheel()` / — / `onGuiClosed()`; plus `mouseReleased(mx,my,state)`, `mouseClickMove(mx,my,btn,timeSinceClick)`, `updateScreen()`, `doesGuiPauseGame()` |
| `Font` / `font.width(s)` / `font.lineHeight` / `font.draw` / `drawShadow` | `FontRenderer` / `getStringWidth(s)` / `FONT_HEIGHT` / `drawString(s,x,y,c)` / `drawStringWithShadow(s,x,y,c)` |
| `Component` / `TextComponent` / `TranslatableComponent` / `Style` / `ChatFormatting` / `FormattedCharSequence` | `IChatComponent` / `ChatComponentText` / `ChatComponentTranslation` / `ChatStyle` / `EnumChatFormatting` / `String` |
| `PoseStack` | the foundation's `com.origin.client.client.gui.Pose` — a thin face over the fixed-function GL matrix stack (1.8.9 has no matrix objects): `pushPose()`, `popPose()`, `translate(double,double,double)`, `scale(float,float,float)`, `mulPose(Pose.Axis.ZP.rotationDegrees(deg))` (also `XP`/`YP`, `.rotation(radians)`). `Gfx.pose()` returns it; `new Pose()` works anywhere (world rendering too). There is NO `last().pose()` matrix: vertices are emitted with `wr.pos(x,y,z)` and the current GL matrix applies, so drop the matrix argument of 1.16.5's `vertex(matrix, x, y, z)` |
| `RenderSystem` | `GlStateManager` (+ `GL11` for what it lacks: scissor, line width, `glColorMask`...) |
| `Tesselator` / `BufferBuilder` / `DefaultVertexFormat` / `.vertex(m,x,y,z).color(..).endVertex()` | `Tessellator.getInstance()` / `WorldRenderer` (`tess.getWorldRenderer()`) / `DefaultVertexFormats` / `wr.begin(GL11.GL_QUADS, POSITION_COLOR); wr.pos(x,y,z).tex(u,v).color(r,g,b,a).endVertex(); tess.draw();` (vertex element order must match the format) |
| `RenderTarget` / `PostChain` | `Framebuffer` / `ShaderGroup` |
| `KeyMapping` / `InputConstants` / GLFW key codes | `KeyBinding` / `org.lwjgl.input.Keyboard` + `Mouse` / LWJGL2 key codes — see Keys below |
| `LocalPlayer` / `Player` / `LivingEntity` / `ItemEntity` / `PrimedTnt` | `EntityPlayerSP` / `EntityPlayer` / `EntityLivingBase` / `EntityItem` / `EntityTNTPrimed` |
| `ClientLevel` / `Level` | `WorldClient` / `World` |
| `Vec3` (`x/y/z`) / `AABB` / `HitResult` / `BlockHitResult` | `Vec3` (`xCoord/yCoord/zCoord`) / `AxisAlignedBB` / `MovingObjectPosition` (`typeOfHit`, `getBlockPos()`, `entityHit`) |
| `entity.getX()/getY()/getZ()`, `xo/yo/zo`, `yRot/xRot`, `getYRot()` | `posX/posY/posZ`, `prevPosX...`, `rotationYaw/rotationPitch` |
| `PlayerInfo` / `ClientPacketListener` / `getOnlinePlayers()` | `NetworkPlayerInfo` / `NetHandlerPlayClient` / `getPlayerInfoMap()` |
| `ItemRenderer.renderGuiItem` | `mc.getRenderItem().renderItemAndEffectIntoGUI(stack,x,y)` + `renderItemOverlays(font,stack,x,y)` (inside `RenderHelper.enableGUIStandardItemLighting()`) |
| `Gui` (HUD) / `BossHealthOverlay` / `PlayerTabOverlay` / `ChatComponent` | `GuiIngame`/`GuiIngameForge` / `BossStatus` (static) / `GuiPlayerTabOverlay` / `GuiNewChat` |
| `SoundEngine`/`SoundManager` / `SoundInstance` | `SoundManager`/`SoundHandler` / `ISound` |
| `Util.getMillis()` | `Minecraft.getSystemTime()` |
| `I18n.get` | `net.minecraft.client.resources.I18n.format` |

Things that don't exist on 1.8.9 (decide per feature, mirror what older
modules did for missing APIs, and note it in your report): no item cooldowns
(Cooldowns HUD → 1.8.9 has no cooldown items; hide the card on 1.8.9), no
offhand, no elytra, no wind charges, no shields/totems, no sweep attack, no
toasts system (advancement toasts → 1.8.9 achievement pop-up `GuiAchievement`),
no boss-bar list (one `BossStatus`), no bed block-entity (beds are plain
blocks), no sub-titles toggle, no attack indicator, no `Options` for
"particles minimal" names (1.8.9 has `particleSetting` int), no narrator,
no accessibility button, no realms button needed.

## Keys — config stays GLFW so Settings Sync works across versions
Every other version stores key codes in `originclient-*.json` as GLFW codes
(and mouse buttons as their InputConstants form). The 1.8.9 port keeps that
FILE FORMAT identical and translates at the edge: the foundation's
`com.origin.client.client.util.KeyCodes` maps GLFW ↔ LWJGL2
(`glfwToLwjgl(int)`, `lwjglToGlfw(int)`; mouse buttons follow vanilla
1.8.9's convention: `-100 + button`). `Mods.keyCode(...)` returns the LWJGL2
code; `Mods.set(..., keyCode)` from UI receives LWJGL2 and stores GLFW.

## Ownership (each agent edits ONLY its own files; never another area's)
Everything lives under `src/main/java/com/origin/client/client/` unless noted.
1.16.5 path names are the guide; new 1.8.9-only helpers go in your area.
- **foundation**: `OriginClient` (@Mod, `com/origin/client/OriginClient.java`),
  `OriginClientMod` (event hub + the per-tick feature hub from 1.16.5:
  zoom/freelook/sprint/sneak/chat/hitboxes/fullbright/time/fly-boost/death
  waypoints state), `OriginKeyBindings`, `OriginConfig`, `OriginFeatures`,
  `OriginFreelookState`, `OriginHud`, `GeneralSettings`, `IoCompat`,
  `OriginDevShots`, `OriginDevWorld`, `theme/*`, `gui/Gfx`, `gui/Pose`,
  `gui/OriginText`, `gui/OriginTtfFont`, `gui/OriginUi`, `gui/OriginDeck`,
  `gui/ModIcons`, `gui/OriginWindow`, `gui/OriginWidgetOwnership`,
  `mods/ModOption`, `mods/ModsConfig`, `mods/ModsConfigAccess`, `mods/Mods`,
  `mods/Profiles`, `hud/HudPos`, `render/FillQuads`, `render/ThickLine`,
  `render/OriginRenderTypes`, `util/*`, `net/*`, `hooks/OriginHooks` (registry),
  `mixin/core/*`, all of `src/main/resources/` except mixin configs of other
  areas (lang, textures, fonts, shaders, default config, mcmod.info).
- **menus**: `render/OriginScreenRenderer`, `render/TitleLayout`, the Origin
  title screen (1.8.9: replace `GuiMainMenu` via GuiOpenEvent with an Origin
  `GuiScreen` — best fit, no mixin), every loading/progress screen
  (GuiConnecting, GuiDownloadTerrain, GuiScreenWorking, the world-load
  `LoadingScreenRenderer` — the old module replaced `mc.loadingScreen`),
  pause screen (Smart Disconnect + the Cosmetics/Mods/Friends buttons the
  other versions add), out-of-world backdrop behind every menu, vanilla widget
  skin (buttons, sliders, lists, text fields), `mods/MenuBlur`,
  `gui/OriginButtonRenderer`, `gui/OriginForeignWidgets`, `shaders/*`
  (OptiFine edition: "Download Shaders" button in OptiFine's shader screen
  `net.optifine.gui.GuiShaders`/`shadersmod.client.GuiShaders` — detect by class
  name, no compile dep; `ShaderDownloader` writes to `shaderpacks/`, the folder
  OptiFine reads), "Show Achievements" (GuiAchievement), `hooks/MenuHooks`,
  `mixin/menus/*`.
- **modmenu**: `gui/OriginModMenuScreen`, `hud/HudEditorScreen` (the Right
  Shift screen + HUD editor drag mode), `gui/OriginColorPicker`,
  `gui/OriginMultiSelect`, `gui/OriginItemGridScreen`,
  `gui/OriginItemSizeScreen`, `gui/ItemCounterPickerScreen`, `hooks/ModMenuHooks`
  (Right Shift opening).
- **hud**: `hud/*` except HudEditorScreen/HudPos, `minimap/*`,
  `render/OriginCrosshair`, `mods/ClickStats`, `mods/CombatStats`,
  `mods/PingSampler`, `mods/Stopwatch`, `mods/ItemCounter`,
  `mods/SaturationOverlay`, `mods/PortalCalc`, `mods/PortalMemory`,
  `hooks/HudHooks`, `mixin/hud/*`. (Scoreboard, boss bar, titles, tab list,
  potion HUD, crosshair, Origin HUD top-most layer.)
- **world**: `mods/BlockOverlayRenderer`, `ChunkBorderRenderer`, `ItemPhysics`,
  `ItemSizes`, `TntCountdown`, `GlintColorizer`, `HitColor`, `ShinyPots`,
  `ParticleFilter`, `MotionBlur`, `WeatherOverride`, `Snaplook`,
  `SoundChanger`, `render/ColorGrade` (Color Saturation), `waypoints/*`,
  `worldedit/*`, and the render-side hooks of zoom (FOV), freelook (camera),
  hurt cam, full bright, time changer, entity/tile-entity distance, hitboxes,
  nametags (size/hide/LevelHead/party colour), FPS caps — `hooks/WorldHooks`,
  `mixin/world/*`.
- **social**: `cosmetics/*`, `gui/CosmeticsScreen`, `gui/FriendsScreen`,
  `social/*`, `hooks/SocialHooks`, `mixin/social/*` (capes/halos/crown layers,
  menu player preview, party chat `/originpc`, toasts, Party HUD, LAN-only
  markers/pings).
- **server**: `mods/AutoGG`, `AutoTextHotkey`, `ChatEvents`, `DiscordPresence`,
  `FpsCoach`, `FpsCoachTips`, `gui/FpsCoachScreen`, `gui/FpsCoachToast`,
  `Hypixel`, `HypixelApi`, `HypixelBedwars`, `hud/BedwarsHud` (yes, server owns
  this one HUD file), `NickHider`, `Quickplay`, `gui/QuickplayScreen`,
  `ScreenshotChat`, `ShotClipboard`, `ServerMods`, `TeamView`,
  `hooks/ServerHooks`, `mixin/server/*` (chat receive/timestamps, nick hider,
  screenshot key, Bed Wars sound/gui).
Cross-area calls: call the other area's 1.16.5 API as-is (its port keeps the
name and shape). If you need something that has no 1.16.5 equivalent from
another area, don't create it in their package — put a minimal helper in
yours and mention it in your report.

## Looking up 1.8.9 names (ref-189)
CI job logs hold javap output (MCP-named, private members included) for the
classes each area needs, one log per topic. Fetch with the GitHub MCP tool
`get_job_logs` (owner `dozeyy`, repo `originclient`, `job_id` from the table
the orchestrator gives you, `return_content: true`, `tail_lines` large enough —
the logs are a few thousand lines). Topics: gui, render, entity, net, forge,
srg. If a class/member you need isn't in them, write it in your report under
"LOOKUPS NEEDED" (`class X` / `member y`) — the orchestrator runs batches.
Don't guess an SRG name.

## Rules of the road
- Don't git commit/push — the orchestrator does. Don't edit other areas' files,
  build.gradle, workflows, or anything outside `src/mods/versions/1.8.9`.
- Comments: explain constraints and reasons (why this hook, why this era
  method), match the 1.16.5 file's comment density. One obvious way to do
  each thing. Every 1.8.9-specific decision gets a one-line "1.8.9:" comment.
- Before finishing, run the syntax/language-level gate on your files:
  `javac --release 8 -proc:none -d /tmp/x <your files>` and confirm there are
  NO parse errors and no Java-9+ API uses (cannot-find-symbol for Minecraft
  /Forge/Origin classes is expected without the classpath — ignore those).
- Report: files written, per-feature 1.8.9 mechanism (event / mixin / reflect),
  features dropped or stood in for and why, LOOKUPS NEEDED, SRG NEEDED, and any
  API you were unsure of.

## Foundation API (as built) — read this before calling foundation classes
Everything below matches the 1.16.5 class of the same name unless listed.
Package root `com.origin.client.client` unless noted.

**Entry / hub**
- `com.origin.client.OriginClient`: `LOGGER`, `MOD_ID` as 1.16.5, plus the
  FabricLoader stand-ins `OriginClient.configDir()` / `gameDir()` (both `Path`),
  `isModLoaded(String)` (Forge modid) and `isOptiFineLoaded()`.
- `OriginClientMod`: no `onInitializeClient` (Forge calls `init()`); keeps
  `FEATURES`, `timeOverride`, `zoomToggled/zoomActive/zoomScrollFactor`,
  `nametagsHidden/playerNametagsHidden`, `tabListVisible`, `previewTimeSlider`,
  `sprintKeyHeldByToggle(KeyBinding)`; adds `sneakKeyHeldByToggle(KeyBinding)`
  (toggle sneak = the vanilla sneak key reads held, mixin/core/KeyBindingMixin).
  `isRawKeyDown(int)` takes an **LWJGL2** code (what `Mods.keyCode` returns;
  mouse = -100+button; -1 = unbound -> false).
  **The hub does NOT tick or init other areas** (1.16.5's hub called
  ServerMods.register, Snaplook.tick, Stopwatch.tick, MinimapHud.tick,
  WorldEditCui.init, Social.init, CosmeticsService.start, PortalCalc.init,
  FpsCoach.register, AutoGG/ScreenshotChat, the WorldRenderEvents.LAST pass,
  block outline, potion-in-inventory, out-of-world backdrop, Iris shader
  button ...). Each area does its own from its `hooks/<Area>Hooks` (constructor
  for init, `TickEvent.ClientTickEvent` END for ticks, `RenderWorldLastEvent`
  for world passes). The hub keeps only its own list from the brief, plus the
  waypoint keybinds (open `WaypointScreen()` / `WaypointScreen(true)`, toggle
  "waypoints") and the death-waypoint edge (`Waypoints.onDeath(x,y,z,dim)`,
  `Waypoints.currentDim()`), as 1.16.5.
- `OriginKeyBindings`: same field names, type `KeyBinding` (`consumeClick()` ->
  `isPressed()`, `isDown()` -> `isKeyDown()`). `openModMenu` is consumed by
  **ModMenuHooks** (the hub does not open the Right Shift screen on 1.8.9).
- `hooks/OriginHooks.registerAll()` (called by the hub) loads `MenuHooks,
  ModMenuHooks, HudHooks, WorldHooks, SocialHooks, ServerHooks` by name, public
  no-arg constructor, registers on both buses. A missing class is skipped.

**Drawing**
- `gui/Pose` = PoseStack stand-in (GL matrix stack): `pushPose/popPose/
  translate(d,d,d)/scale(f,f,f)/mulPose(Pose.Axis.ZP.rotationDegrees(deg))`
  (+ `XP/YP/XN/YN/ZN`, `.rotation(rad)`), `popAll()`, `depth()`. A Pose ignores
  pops it never pushed. No `last()`.
- `gui/Gfx`: every 1.16.5 method, `Font` -> `FontRenderer`, `Component` ->
  `IChatComponent`, `PoseStack` -> `Pose`. Constructors `new Gfx()` (fresh Pose)
  and `new Gfx(Pose)`. Extras: `fillF(float..)`, `drawStringF(font,s,float x,
  float y,color,shadow)`, `drawString/drawStringWithBackdrop/renderTooltip`
  String overloads, `drawTooltip(font, List<String>, mx, my)`,
  `blitBound(x,y,w,h,u0,v0,u1,v1)` (bound texture, float geometry).
  Statics (use these instead of Window/RenderSystem):
  `Gfx.guiScale()` (int), `windowWidth()/windowHeight()` (framebuffer px),
  `defaultBlend()` (enableBlend + defaultBlendFunc), `color(argb)` (color4f),
  `resetColor()` (white + GlStateManager colour cache resync — call after any
  POSITION_COLOR draw of your own), `softAlpha()/defaultAlpha()` (alpha test at
  0 / back to vanilla's 0.1), `bind(ResourceLocation)`,
  `scissorWindow(x,y,w,h)` (window px, bottom-left), `readImage("/assets/...")`
  (-> BufferedImage, NativeImage.read stand-in), `registerTexture(id, img,
  linear[, mipmap])` (-> DynamicTexture; NativeImage+DynamicTexture+register).
- `gui/OriginScreen` (NEW, 1.8.9 only): the base for Origin screens. A
  `GuiScreen` that dispatches to the 1.16.5 handler shapes: `init()`,
  `render(Gfx, mx, my, pt)` (default = vanilla buttonList), `tick()`,
  `removed()`, `onClose()` (default `displayGuiScreen(null)`),
  `shouldCloseOnEsc()`, `isPauseScreen()`, and boolean `mouseClicked(double,
  double,int)`, `mouseReleased(..)`, `mouseDragged(mx,my,btn,dx,dy)`,
  `mouseScrolled(mx,my,notches)` (+ = scroll up), `keyPressed(key, 0, mods)`,
  `charTyped(char, mods)`. Mouse coords are double GUI px; **key codes are
  LWJGL2** (`Keyboard.KEY_*`); mods are GLFW-style bits (`MOD_SHIFT 1,
  MOD_CONTROL 2, MOD_ALT 4`); `hasShiftDown()/hasControlDown()/hasAltDown()`.
  Fields `title` (IChatComponent), `minecraft`, `font` mirror Screen's.
  The 1.8.9 overrides (initGui/drawScreen/mouseClicked(int..)/keyTyped/...)
  are `final` — override the modern ones. A modern handler returning false
  falls through to vanilla (buttonList, Esc -> onClose). Any throw closes the
  screen and latches the class: `OriginScreen.isBroken(SomeScreen.class)` —
  check it before replacing a vanilla screen with an Origin one.
- `gui/OriginWindow extends OriginScreen`: constructors `(IChatComponent,
  GuiScreen returnTo)` and `(String, GuiScreen)`; `render(Gfx,...)` instead of
  `render(PoseStack,...)`; otherwise 1.16.5's API (heading/subheading/
  headingIcon/drawBody/drawHeaderActions/renderOverlays/button/buttonW/card/
  label/field/scrollbar/dX/dY/back/beginClose, Style enum). Esc compares
  `Keyboard.KEY_ESCAPE`.
- `gui/OriginDeck`, `gui/OriginUi`, `gui/ModIcons`, `gui/OriginText`,
  `gui/OriginTtfFont`, `gui/OriginWidgetOwnership`, `theme/OriginTheme`: 1.16.5
  API unchanged (Font -> FontRenderer in OriginText). OriginTtfFont rasterises
  Inter with java.awt. Icons/glow/logo are mipmapped (thin strokes survive).
  OriginWidgetOwnership also owns `net.minecraftforge.*` widgets.

**Mods / config**
- `mods/Mods`: same ids/keys/defaults (config byte-compatible). Keybind
  defaults are LWJGL2; `Mods.keyCode()` returns LWJGL2; `Mods.set(mod, key,
  int)` on a KEYBIND row takes LWJGL2 and stores GLFW. A KEYBIND row's
  `ModOption.defKey` is LWJGL2 (so `keyCode(..) != o.defKey` still works).
  New: `Mods.unavailable(id)` (cards hidden on 1.8.9: `cooldowns`; `on()` is
  false for them), `Mods.LEGACY_PARTICLE_IDS` (1.8.9 EnumParticleTypes name ->
  Origin particle id; the Particle Changer only lists those ids on 1.8.9).
  Settings rows removed on 1.8.9: General "rawMouseInput", Performance
  "shaderPerformanceMode" (Iris-only) — reads return the stored value/false.
- `mods/ModsConfig`, `ModsConfigAccess`, `ModOption`, `Profiles`, `hud/HudPos`:
  1.16.5 API unchanged.
- `IoCompat`: 1.16.5's `readAllBytes`, `keys` + gson 2.2.4 gaps: `size(JsonObject)`,
  `parse(String)`, `deepCopy(JsonElement)`, `isEmpty(JsonArray)`,
  `without(JsonArray, index)`. NOTE gson 2.2.4: `gson.fromJson(x,
  JsonObject.class)` is NOT safe (no JsonObject adapter before 2.3) — use
  `new JsonParser().parse(x).getAsJsonObject()`; `JsonArray.add(String/Number/
  Boolean)` don't exist (wrap in `new JsonPrimitive(..)`).

**Utilities (new, 1.8.9 only)**
- `util/Reflect`: `field(Class, String... names)`, `method(Class, Class<?>[]
  params, String... names)`, `constructor(Class, Class<?>...)`, `type(String...
  classNames)` — cached, superclass walk, null on failure; `get(Field, target,
  fallback)`, `get(target, fallback, names...)`, `getInt/getFloat/getBoolean(Field,
  target, fallback)`, `set(Field, target, value)` -> boolean, `call(Method,
  target, fallback, args...)`, `run(Method, target, args...)` -> boolean,
  `create(Constructor, args...)`. Pass MCP name first, then SRG.
- `util/KeyCodes`: `glfwToLwjgl(int)`, `lwjglToGlfw(int)`, `isMouse(int)`,
  `isDown(int lwjgl)` (keys + mouse, never throws), `name(int lwjgl)`
  (GameSettings.getKeyDisplayString), `UNBOUND = -1`, `MOUSE_OFFSET = -100`.
  File format: GLFW keyboard codes; mouse button b stored as b.
- `net/OriginHttp`: `open(String|URL)` -> HttpURLConnection with Origin's trust
  store (JDK roots + bundled ISRG/GTS/DigiCert/Baltimore) over TLS 1.2 — swap
  `(HttpURLConnection) new URL(u).openConnection()` -> `OriginHttp.open(u)`;
  `get(url, timeoutMs, userAgent)` -> body or null; `readBody(InputStream)`;
  `socketFactory()`.

**World-render helpers** (render/, immediate, under the current GL matrix)
- `render/OriginRenderTypes`: `begin()` -> `WorldRenderer` (translucent
  depth-tested no-depth-write quads, POSITION_COLOR), `beginLines()` ->
  `WorldRenderer` (GL_LINES, POSITION_COLOR), `draw()` flushes both NOW with
  their state. Emit `wr.pos(x,y,z).color(r,g,b,a).endVertex()`. Two private
  Tessellators, so quads and lines can interleave. Call from
  `RenderWorldLastEvent` with camera-relative coords (subtract
  `RenderManager.viewerPosX/Y/Z`).
- `render/FillQuads`: `FillQuads.begin()` / `FillQuads.draw()` (translucent,
  no-cull quads, depth test as current) — 1.16.5's RenderType INSTANCE has no
  1.8.9 meaning.
- `render/ThickLine`: `edge(WorldRenderer q, Pose pose, ax..cz, t, r,g,b,a)`
  and `vertical(WorldRenderer q, Pose pose, x, z, y0, y1, halfWidth, r,g,b,a)`
  — `pose` is accepted and ignored (the GL matrix applies); overloads without
  it exist too.

**Hub extras / resources (Phase B, as built)**
- `OriginClientMod.chatBackground(int vanillaColor)` + `keyHeldByToggle(KeyBinding)`
  are what `mixin/core/GuiNewChatMixin` (Chat mod Background Opacity — 1.8.9 has
  no text-background option) and `mixin/core/KeyBindingMixin` (toggle sprint +
  sneak) call. Chat Scale rides `gameSettings.chatScale`; `chatOpacity` is never
  touched. FPS caps (maxMainMenuFps / limitUnfocusedFps) are NOT in the hub —
  world owns them per the Ownership list (`Minecraft.getLimitFramerate()` RETURN).
- The hub's tick is `TickEvent.ClientTickEvent` END; dev shots tick from it.
  On-exit flush = JVM shutdown hook (Forge 1.8.9 has no client-stopping event).
- `OriginDevShots` (same shot names as 1.16.5) builds other areas' screens by
  class name with 1.16.5's constructor shapes: `OriginModMenuScreen()` /
  `(GuiScreen, String modId)` + `.preview(boolean list, boolean settings)`,
  `CosmeticsScreen(GuiScreen)`, `FpsCoachScreen(GuiScreen)`, `WaypointScreen()`,
  `OriginItemSizeScreen()`, `ItemCounterPickerScreen()`, `QuickplayScreen()`,
  `ShaderBrowserScreen(GuiScreen)`, `FriendsScreen(GuiScreen).select(Object)`
  with `FriendsScreen.PARTY` / `SocialDemo.KESTREL`, `HudEditorScreen(boolean)`,
  static `SocialDemo.clear()` / `install(boolean)`. TITLE = `new GuiMainMenu()`
  through `displayGuiScreen` (menus swaps it on GuiOpenEvent); the harness
  treats `GuiMainMenu` or any class whose simple name contains "Title" as the
  title. World set: `launchIntegratedServer` + `new GuiIngameMenu()`.
- Resources: assets/originclient = 1.16.5's (textures incl. cosmetics, Inter
  TTFs, icon, default-mods-config.json; the MSDF `textures/font` atlases and the
  `font/*.json` ttf providers are left out — no use on 1.8.9);
  `lang/en_US.lang` (1.8.9 key=value); `certs/*.pem` (OriginHttp);
  assets/minecraft/shaders = 1.16.5's post + program files (1.8.9 ShaderGroup
  reads the same JSON shape; GLSL 110). The OLD vanilla overrides
  `assets/minecraft/textures/gui/widgets.png` + `options_background.png` are
  still there — **menus decides** whether its widget skin needs them.

## Status
- 2026-10-13: every area written (foundation, menus, modmenu, hud, world,
  social, server — ~43k lines, 191 files). Next: CI compile to green, then
  dev shots against 1.21.1's, then Will's in-game pass (the bar for "done":
  `runClient` with zero `Mixin apply ... failed` lines, and the real launcher
  with OptiFine installed).

- 2026-10-20: shader packs on 1.8.9 are an ALLOWLIST (Will: "only add shaders in
  1.8.9 that work with optifine"). `shaders/ShaderCompat.java` holds one pinned
  Modrinth file per pack (url + SHA-256, rule and rejected packs documented in
  its header); the Download Shaders screen lists only those and the downloader
  refuses a hash mismatch. A selected pack built for newer Minecraft (known
  modern name, or a core program above GLSL 130) gets a one-line warning in
  that screen and once in chat; files are never touched. Check:
  `tools/check-shader-allowlist.ps1` (pure Java, no MC classpath).
