# Recipe: loading overlay, level loading, progress / connect / message screens, reload

The 1.21.1 set is these mixins:
- LoadingOverlayMixin
- LoadingOverlayRevealMixin
- LevelLoadingScreenMixin
- ReceivingLevelScreenMixin
- ProgressScreenMixin
- GenericMessageScreenMixin
- ConnectScreenMixin
- ReloadTaskBudgetMixin, plus ReloadTaskTagMixin where it exists
- ScreenBackgroundMixin

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
| G5 1.21.10 / 1.21.11 | no `menu.loadingLevel` key. A bare LevelLoadTracker reports no progress until `start(Stage, n)`. Don't read progress from a tracker the game didn't start |
| G6 26.x | extract names (extractBackground / extractRenderState). `mc.gui.overlay()` on 26.2+, `mc.getOverlay()` on 26.1. 26.3: never build a pipeline while the overlay is up |

- **ConnectScreen status line (2026-10-25, rings-out port):** 1.16.5-1.21.11 call drawCenteredString(Font,Component,III) exactly once for the status - @WrapOperation/@Redirect it and draw the picture at render TAIL (keeps narration); 26.x calls `centeredText(Font,Component,III)V` once in extractRenderState - same pattern. 1.8.9 GuiConnecting.drawScreen (func_73863_a) is safe to HEAD-cancel (OptiFine doesn't replace it). Move Cancel: `setY` on 1.19.3+, the `y` field before; 1.20.2 has no setHeight.
- **Dock tooltip cards:** WidgetTooltipHolder.refreshTooltipForNextRenderPass HEAD-cancel on 1.21.1-26.x (no AbstractWidget.getTooltip on 1.21.6+/26.x: register the Tooltip when created); 1.19.3/1.19.4 AbstractWidget.updateTooltip (private, HEAD); 1.19.2 and older have no Tooltip API.
- **No refmap on 1.16.5-1.21.11 Loom builds:** mixin targets are remapped in-class to intermediary; check javap names, not a refmap. Pre-1.20 reuse one Gfx via `rebind` (PoseStack is new each frame).

## Proof
- Use `dev-shots -f set=handoff -f framelog=true`. It writes `[OriginFrameLog]` lines and GC for the loading → main menu handoff.
- Use `-f video=true` (loading.mp4) to check the visual sequence.
- Also run `set=world` for level loading and the receiving screen.
- Use `boot-time` (workflow_dispatch, `module`, `base_ref`, `boots`) for boot-time regressions. It needs OriginBootLog, which only 1.21.1 has.
