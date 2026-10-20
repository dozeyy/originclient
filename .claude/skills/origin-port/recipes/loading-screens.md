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

## Proof
- Use `dev-shots -f set=handoff -f framelog=true`. It writes `[OriginFrameLog]` lines and GC for the loading → main menu handoff.
- Use `-f video=true` (loading.mp4) to check the visual sequence.
- Also run `set=world` for level loading and the receiving screen.
- Use `boot-time` (workflow_dispatch, `module`, `base_ref`, `boots`) for boot-time regressions. It needs OriginBootLog, which only 1.21.1 has.
