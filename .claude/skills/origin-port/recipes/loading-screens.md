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
| G9 1.8.9 | Dev GuiConnecting is created via Unsafe and needs the GuiScreen fields copied. See legacy-forge-189.md |
| G7/G8 pre-1.20 | Gfx wrapper. 1.17.1: ensureCompiled() is skipped while the loading overlay is up. Its core shaders compile on demand with un-namespaced names because 1.17 ShaderInstance has no namespace handling |
| G1/G2 1.20.x | GenericDirtMessageScreen exists until 1.20.5 (GenericMessageScreen after). renderBackground arity: 1-arg on 1.20, 4-arg on 1.20.2+ |
| G1 1.21 / 1.21.1 | the source set; GenericMessageScreenMixin |
| G3 1.21.4 | no font text during the initial reload (ShaderManager era) |
| G4 1.21.6 / 1.21.8 | renderBackground runs BEFORE render: cancel the ProgressScreen and LevelLoadingScreen backdrop SEPARATELY. ReloadTaskTagMixin targets `prepareTasks` |
| G5 1.21.10 / 1.21.11 | no `menu.loadingLevel` key. A bare LevelLoadTracker reports no progress until `start(Stage, n)`. Don't read progress from a tracker the game didn't start. Mid-load shot: `start(LOAD_INITIAL_CHUNKS,100)` then `update(LOAD_INITIAL_CHUNKS,50,100)` gives a real 50%. **Opaque ConnectScreen on deferred GUI:** draw the picture at render HEAD (not cancelled) so vanilla's Cancel submits on top, keep the status-line WrapOperation, and cancel ConnectScreen's renderBackground in ScreenBackgroundMixin (it runs before render). **Realms:** `RealmsLongRunningMcoTaskScreen` (javap 1.21.10+1.21.11) has `private volatile Component title`, `protected repositionElements()V`, no render of its own; its parent `net.minecraft.realms.RealmsScreen` declares neither render nor renderBackground, so the mixin extends RealmsScreen and overrides `render` (draw picture, then only Button children; super.render on vanilla mode or a failed draw); seat Cancel at repositionElements TAIL. Dev shot: a dummy `LongRunningTask` (abstract getTitle(), aborted()) that only sleeps |
| G6 26.x | extract names (extractBackground / extractRenderState). `mc.gui.overlay()` on 26.2+, `mc.getOverlay()` on 26.1. 26.3: never build a pipeline while the overlay is up |

- **ConnectScreen status line (2026-10-25, rings-out port):** 1.16.5-1.21.11 call drawCenteredString(Font,Component,III) exactly once for the status - @WrapOperation/@Redirect it and draw the picture at render TAIL (keeps narration); 26.x calls `centeredText(Font,Component,III)V` once in extractRenderState - same pattern. 1.8.9 GuiConnecting.drawScreen (func_73863_a) is safe to HEAD-cancel (OptiFine doesn't replace it). Move Cancel: `setY` on 1.19.3+, the `y` field before; 1.20.2 has no setHeight.
- **Dock tooltip cards:** WidgetTooltipHolder.refreshTooltipForNextRenderPass HEAD-cancel on 1.21.1-26.x (no AbstractWidget.getTooltip on 1.21.6+/26.x: register the Tooltip when created); 1.19.3/1.19.4 AbstractWidget.updateTooltip (private, HEAD); 1.19.2 and older have no Tooltip API.
- **No refmap on 1.16.5-1.21.11 Loom builds:** mixin targets are remapped in-class to intermediary; check javap names, not a refmap. Pre-1.20 reuse one Gfx via `rebind` (PoseStack is new each frame).

## Proof
- Use `dev-shots -f set=handoff -f framelog=true`. It writes `[OriginFrameLog]` lines and GC for the loading → main menu handoff.
- Use `-f video=true` (loading.mp4) to check the visual sequence.
- Also run `set=world` for level loading and the receiving screen.
- Modules flagged `"inworld": true` (live-modules.json) also prove the flat-world load end to end: boot-smoke and the
  design set end with `inworld_hud_*` / `inworld_modmenu_*` shots and Save and Quit (verify-loop.md section 2).
- Use `boot-time` (workflow_dispatch, `module`, `base_ref`, `boots`) for boot-time regressions. It needs OriginBootLog, which only 1.21.1 has.
