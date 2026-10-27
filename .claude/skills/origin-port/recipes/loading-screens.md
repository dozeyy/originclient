# Recipe: loading overlay, level loading, progress / connect / message screens, reload

The 1.21.1 set is these mixins:
- LoadingOverlayMixin
- LoadingOverlayRevealMixin
- LevelLoadingScreenMixin
- ReceivingLevelScreenMixin
- ProgressScreenMixin
- GenericMessageScreenMixin
- ConnectScreenMixin
- RealmsTaskScreenMixin (Realms' long-running task screen: "Connecting to the realm...", create, reset)
- ReloadTaskBudgetMixin, plus ReloadTaskTagMixin where it exists
- ScreenBackgroundMixin

## Parity spec: the one loading look (1.21.1 is the truth, Will 2026-10-27)
Every port must match this on every loading surface: startup overlay, in-game reload, world loading,
receiving/progress/message screens, Connecting to a server, Realms connecting. Code: `render/LoadingLook.java`,
`OriginScreenRenderer.renderLoading` / `renderLoadingScene` / `renderConnecting` / `connectCancelTop`.
- **Font: Minecraft's own font for every word.** Caption = `g.drawString(mc.font, text, x, y, colour, true)` (shadow on),
  centred on its ink width (`font.width(text) - 1`). No Inter / MSDF / SDF text on any loading screen. Only the startup
  overlay, where `mc.font` is not loaded yet, draws through `bootText` (reads `ascii.png` itself): same glyphs.
- **Backdrop: OPAQUE.** One flat `OriginTheme.CLEAN_BG` #0E1110 over the whole window. Never the world, the panorama,
  the dirt or a blur showing through. The only translucency is the startup overlay dissolving into the title.
- **Logo:** `textures/ui/origin_logo.png` itself (never a drawn ring), 72 design px, centred horizontally, its centre at
  0.44 of the window height on every loading screen. No wordmark.
- **Bar:** 24 design px under the logo, 200 design px wide, a 1 GUI px hairline track `CLEAN_BAR_TRACK` 0x14FFFFFF, a 2 GUI px fill `CLEAN_BAR_FILL`
  0xFF5FD09B with capsule (round) ends. Indeterminate screens (connect, Realms, message) sweep; others show real progress.
- **No stub at 0 %:** the fill is exactly its fraction. 0 % draws nothing (`pill()` returns at w <= 0), and there is no
  minimum width or nub. A sub-pixel tail is one partial-alpha column (only above 0.02 of a pixel).
- **Caption:** 12 design px under the bar, colour `TITLE_TEXT_MUTED` 0x85F1E9D2. The status / task title as given
  ("Connecting to the server...", "Loading terrain...").
- **Cancel (every loading screen that has one): the Workbench card** (`OriginButtonRenderer.setLoadingButton`), label in
  Minecraft's font in parchment `TITLE_TEXT` #F1E9D2 (never muted: it reads as disabled), `LOADING_CANCEL_W` 120 x 20 GUI px
  (narrower when the screen is), centred, top edge `connectCancelTop(screenH, 20)` = one picture step (the 24 design px
  logo-to-bar gap) under the caption, 8 GUI px clear of the bottom. Same card fill as the title buttons (the decided
  charcoal). 1.21.1 has two: ConnectScreen (init TAIL) and RealmsLongRunningMcoTaskScreen
  (`repositionElements()V` TAIL; it declares no render(), so the mixin adds the override and calls `super.render` when
  vanilla mode is on or the picture fails).
- **Left vanilla on purpose:** RealmsDownloadLatestWorldScreen and RealmsUploadScreen (transfer screens with live
  stats a player reads), GenericWaitingScreen (the chat-report sender). Not loading pictures; don't skin them.
- **Main Menu Style = Vanilla** keeps every one of these stock (`OriginStyle.vanillaMenusOn`).
- **Proof shots (design + clean sets):** `level_loading`, `level_loading_mid`, `connecting`, `connecting_realms`, and
  the startup overlay (`loading_boot_*`, clean/style sets).

## Rules that hold everywhere
- **Label caches must clear on reload or key on the language.** A cache keyed on identity goes stale after a resource reload
  or a language change. This was in the clean-look review addendum.
- **No font text and no Origin pipeline build during the initial reload.**
  - 1.21.2+: font text then wedges boot (proven on 1.21.4).
  - 26.3: pipelines built under the overlay fail "source not found" and latch off. Check `OriginShaders.sourcesPending()`.

## Per-era hooks

| Era | Facts (proven unless marked) |
|---|---|
| G9 1.8.9 | Dev GuiConnecting is created via Unsafe and needs the GuiScreen fields copied. LoadingLook.draw fills CLEAN_BG itself (callers drop their panorama backdrop); Cancel = setLoadingButton at GuiConnecting.initGui TAIL, drawn in the drawScreen HEAD-cancel's own button loop via titleControl. No Realms connecting screen exists. See legacy-forge-189.md |
| G7/G8 pre-1.20 | Gfx wrapper. 1.17.1: ensureCompiled() is skipped while the loading overlay is up. Its core shaders compile on demand with un-namespaced names because 1.17 ShaderInstance has no namespace handling |
| G1/G2 1.20.x | GenericDirtMessageScreen exists until 1.20.5 (GenericMessageScreen after). renderBackground arity: 1-arg on 1.20, 4-arg on 1.20.2+ |
| G1 1.21 / 1.21.1 | the source set; GenericMessageScreenMixin |
| G3 1.21.4 | no font text during the initial reload (ShaderManager era): the startup caption stays on `bootText`. bootGlyphs fades by `layerAlpha` itself (per-blit ARGB), so pass bootText the UNFADED caption colour or the dissolve fades it twice. `renderConnecting` is 2-arg (no cancelTop). RealmsLongRunningMcoTaskScreen has 1.21.1's exact shape (javap: `repositionElements()V` declared and called at init's end, `private volatile Component title`, no render()). In-world step: `new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures())`; createFreshLevel's dimensions function gets a `HolderLookup.Provider`: `lookupOrThrow(WORLD_PRESET).getOrThrow(FLAT)` (registryOrThrow / getHolderOrThrow are gone). setHeight/setWidth exist (the old 'no setHeight' comment in the 1.21.4 fork was copied from 1.20.2) |
| G4 1.21.6 / 1.21.8 | renderBackground runs BEFORE render: cancel the ProgressScreen and LevelLoadingScreen backdrop SEPARATELY. ReloadTaskTagMixin targets `prepareTasks` |
| G5 1.21.10 / 1.21.11 | no `menu.loadingLevel` key. A bare LevelLoadTracker reports no progress until `start(Stage, n)`. Don't read progress from a tracker the game didn't start. Mid-load shot: `start(LOAD_INITIAL_CHUNKS,100)` then `update(LOAD_INITIAL_CHUNKS,50,100)` gives a real 50%. **Opaque ConnectScreen on deferred GUI:** draw the picture at render HEAD (not cancelled) so vanilla's Cancel submits on top, keep the status-line WrapOperation, and cancel ConnectScreen's renderBackground in ScreenBackgroundMixin (it runs before render). **Realms:** `RealmsLongRunningMcoTaskScreen` (javap 1.21.10+1.21.11) has `private volatile Component title`, `protected repositionElements()V`, no render of its own; its parent `net.minecraft.realms.RealmsScreen` declares neither render nor renderBackground, so the mixin extends RealmsScreen and overrides `render` (draw picture, then only Button children; super.render on vanilla mode or a failed draw); seat Cancel at repositionElements TAIL. Dev shot: a dummy `LongRunningTask` (abstract getTitle(), aborted()) that only sleeps |
| G3 1.21.4 | no font text during the initial reload (ShaderManager era) |
| G4 1.21.6 / 1.21.8 | renderBackground runs BEFORE render: cancel the ProgressScreen and LevelLoadingScreen backdrop SEPARATELY. ReloadTaskTagMixin targets `prepareTasks`. One-look port (2026-10-27, javap 1.21.6 = 1.21.8): LevelLoadingScreen, ProgressScreen, ConnectScreen and RealmsLongRunningMcoTaskScreen (and RealmsScreen) declare NO renderBackground, so the mixin `extends Screen` (RealmsScreen) and ADDS the override: draw the picture there, set an `@Unique drew` flag, else `super.renderBackground`; render HEAD-cancels only when `drew`. ReceivingLevelScreen and GenericMessageScreen declare their own, so @Inject HEAD on it. ConnectScreen keeps vanilla's render (Cancel + `GameNarrator.saySystemNow` narration), status line dropped by WrapOperation while `drew`. Realms: render override draws only Buttons when `drew`. ProgressScreen fields header/stage/progress/stop unchanged from 1.21.1 (ProgressScreenAccessor copies over). OriginFrameLog has no partStart/part here: drop those timers from LoadingLook. bootGlyphs fades by layerAlpha itself: pass the boot caption colour unfaded |
| G5 1.21.10 / 1.21.11 | no `menu.loadingLevel` key. A bare LevelLoadTracker reports no progress until `start(Stage, n)`. Don't read progress from a tracker the game didn't start |
| G6 26.x | extract names (extractBackground / extractRenderState). `mc.gui.overlay()` on 26.2+, `mc.getOverlay()` on 26.1. 26.3: never build a pipeline while the overlay is up. Opaque picture + Cancel (2026-10-27): Screen draws extractBackground in its own stratum BEFORE extractRenderState, so a loading screen with widgets draws the picture from an ADDED `extractBackground` override (mixin `extends Screen`/`RealmsScreen`; ConnectScreen and RealmsLongRunningMcoTaskScreen declare none) and Cancel lands on top; never at extractRenderState TAIL (covers Cancel) |

- **ConnectScreen status line (2026-10-25, rings-out port):** 1.16.5-1.21.11 call drawCenteredString(Font,Component,III) exactly once for the status - @WrapOperation/@Redirect it and draw the picture at render TAIL (keeps narration); 26.x calls `centeredText(Font,Component,III)V` once in extractRenderState - same pattern. 1.8.9 GuiConnecting.drawScreen (func_73863_a) is safe to HEAD-cancel (OptiFine doesn't replace it). Move Cancel: `setY` on 1.19.3+, the `y` field before; 1.20.2 has no setHeight.
- **26.x Realms task screen (javap 26.1.2/26.2/26.3, identical):** `RealmsLongRunningMcoTaskScreen extends RealmsScreen`; `init()` builds a LinearLayout (realmsLogo ImageWidget, `LoadingDotsWidget(Font, title)`, Cancel `Button`) and ends in `repositionElements()V` (protected, declared) - hook its TAIL to register/seat Cancel; `private volatile Component title` (setTitle from the task thread) is the caption. Declares no extractBackground/extractRenderState: add both overrides, and while the picture draws, extract only the Button children (logo + dots are replaced by the picture). Dev-shots: an anonymous `LongRunningTask` (public no-arg ctor, `getTitle()`, `aborted()`) that sleeps; screen ctor `(Screen, LongRunningTask...)`.
- **26.x in-world step facts (javap):** `LevelSettings(String, GameType, LevelSettings$DifficultySettings(Difficulty, hardcore, locked), allowCommands, WorldDataConfiguration)` - no GameRules arg (moved to `net.minecraft.world.level.gamerules`); flat preset `ra.lookupOrThrow(WORLD_PRESET).getOrThrow(FLAT).value().createWorldDimensions()`; Save and Quit = `mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)` (PauseScreen's own call: level disconnect, saving screen, title).
- **Dock tooltip cards:** WidgetTooltipHolder.refreshTooltipForNextRenderPass HEAD-cancel on 1.21.1-26.x (no AbstractWidget.getTooltip on 1.21.6+/26.x: register the Tooltip when created); 1.19.3/1.19.4 AbstractWidget.updateTooltip (private, HEAD); 1.19.2 and older have no Tooltip API.
- **No refmap on 1.16.5-1.21.11 Loom builds:** mixin targets are remapped in-class to intermediary; check javap names, not a refmap. Pre-1.20 reuse one Gfx via `rebind` (PoseStack is new each frame).
- **Realms connecting (2026-10-27, G1/G2):** RealmsLongRunningMcoTaskScreen on 1.20.2-1.21.1 has `repositionElements()V`,
  no render() of its own and `private volatile Component title`: 1.21.1's mixin ports as-is (extends RealmsScreen, adds render).
  1.20 is the OLD screen: declares `render(GuiGraphics,IIF)V`, `init()V` builds `cancelOrBackButton` (width/2-106, row(12), 212 x 20),
  `errorMessage` field, and error(Component) swaps in a fresh Back button at vanilla's spot. There: init TAIL re-seats the button,
  render HEAD cancels unless errorMessage != null. LongRunningTask: `getTitle()` abstract on 1.20.2+; 1.20 has none (a task calls
  `setTitle`). Cancel buttons are built 20 high, so the 120 x 20 seat needs only setWidth (none of these call setHeight).
- **In-world step per era (OriginDevWorld):** 1.21 `WorldOpenFlows.openWorld(String, Runnable)` + 5-arg createFreshLevel (with a
  Screen); 1.20.4 `checkForBackupAndLoad(String, Runnable)` + 5-arg; 1.20.2 / 1.20 `loadLevel(Screen, String)` + 4-arg
  createFreshLevel (no Screen). Leaving the world: `Minecraft.disconnect(Screen)` on 1.20.2+, `clearLevel(Screen)` on 1.20.
  GenericDirtMessageScreen on 1.20.x.

## Proof
- Use `dev-shots -f set=handoff -f framelog=true`. It writes `[OriginFrameLog]` lines and GC for the loading → main menu handoff.
- Use `-f video=true` (loading.mp4) to check the visual sequence.
- Also run `set=world` for level loading and the receiving screen.
- Modules flagged `"inworld": true` (live-modules.json) also prove the flat-world load end to end: boot-smoke and the
  design set end with `inworld_hud_*` / `inworld_modmenu_*` shots and Save and Quit (verify-loop.md section 2).
  Porting OriginDevWorld.inWorldTick to 1.21.6/1.21.8 (javap): `new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures())`;
  `createFreshLevel`'s function takes a `HolderLookup.Provider` (`lookupOrThrow(WORLD_PRESET).getOrThrow(FLAT)`, not
  registryOrThrow/getHolderOrThrow); Save and Quit = `PauseScreen.disconnectFromWorld(mc, ClientLevel.DEFAULT_QUIT_MESSAGE)`.
- Use `boot-time` (workflow_dispatch, `module`, `base_ref`, `boots`) for boot-time regressions. It needs OriginBootLog, which only 1.21.1 has.
