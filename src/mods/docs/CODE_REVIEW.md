# Origin Client — code review (2026-07-08)

A self-review of the in-game UI mod after the loading-screen work, FPS pass,
and font/label reverts. No live test was possible in this environment (network
policy blocks the Gradle/Loom/Mojang fetch, so no build and no `javap`), so this
also flags exactly what to verify on first launch.

## Architecture (as it stands)

Everything is **restyle-in-place**: mixins cancel a vanilla widget/screen draw
at `renderWidget`/`render` HEAD and repaint the Origin look. No widgets are ever
added, removed, or swapped — positions, actions, and clicks are all vanilla.

- `render/OriginScreenRenderer` — all screen-level drawing (menu background:
  charcoal + rotating rings + grain; cursor spotlight; loading overlay; the new
  world-load scene). Textures load once via the classloader and degrade
  gracefully on failure.
- `gui/OriginButtonRenderer` — buttons, sliders, checkboxes (shared 9-slice
  shell + hover easing). Labels use the default Minecraft font.
- `theme/OriginTheme` — colour/spacing tokens + easing helpers.
- `mixin/*` — widget restyle (AbstractButton, AbstractSliderButton, Checkbox),
  menu background + cursor glow (Screen), title screen re-skin, loading overlay.
- `mixin/loading/*` — the world-load/connect screens (isolated config).

## Font / labels — settled

Default Minecraft font for **all** in-game text. The baked-Inter label ladder
and the Inter TTF `font/default.json` override were both built, shipped, and
then reverted at Will's direction. One text path now (`drawLabel` →
`font.drawString`), so buttons/sliders/menus/HUD are uniform by construction.
The only baked text is the "ORIGIN" wordmark (the brand mark — all-caps Inter
700 with a glow bloom, and it must render before the font loads). The old
"LOADING xx%" caption strip was removed: the loading screen has no percentage
text, and it was the last baked-Inter text that wasn't the logo.

## Loading screen — final layout (Will's spec)

`renderLoading` (startup resource load): near-black (#050505) + crisp orbital
rings + fine grain + the "ORIGIN" wordmark + a chunky progress bar well below
it. Specifics Will locked in:
- **All-caps "ORIGIN"**, Inter 700, letter-spacing = 0.45x the font's space
  advance ("half a space, a little less"; derived from the space glyph in
  `generate_wordmark.py`, not a magic em value).
- **Cap height 0.135h** (all-caps has no descender, so ink height == cap
  height); wordmark centered at 0.48h so the logo+bar group stays balanced.
- **Bar**: full-word-width, 0.012h thick, sitting 1.15x the cap-height below
  the wordmark centre (a clear gap — "farther down and bigger").
Every asset + layout number was verified in-sandbox (Pillow renders the scene
exactly as the Java does) before shipping.

## GL-state discipline (the recurring bug class)

Around `GuiGraphics`, **both** the shader colour and the blend state are shared
mutable GL state and must be treated as dirty before every draw:
- `RenderSystem.setShaderColor(...)` tints later `fill()`/`blit()` — always
  reset to opaque white between a tinted texture draw and a plain `fill()`.
- `guiGraphics.fill()` flushes through a render type whose teardown **disables
  blending**, so a following textured `blit()` ignores its alpha and draws
  fully opaque (this was the "white ring on every slider" bug). Re-enable blend
  + `defaultBlendFunc()` before any blit that can follow a `fill()`.
Both are now handled in `render`/`renderSlider`/`renderCheckbox`/`drawLabel`.

## Performance

- Grain tiled at 1:1 real pixels: tile bumped 128→256px, ~135→~40 blits/frame
  at 1080p on every Origin background. (512 was rejected — the extra blit
  savings are trivially fast but quadruple the PNG.)
- `ensureLoaded()` in both renderers now has a `volatile` fast-path so the
  per-frame (and per-widget-per-frame) already-loaded case is a plain field
  read, not a monitor acquire.
- Animations are wall-clock + dt-corrected (framerate-independent), so hover,
  halo trail, rings, and the indeterminate bar stay smooth at any FPS.
- HUD (`OriginHud`) is minimal text, on par with vanilla's debug lines.

## Fail-soft contract (multi-version safety)

Every Origin draw entry point catches `Throwable` and, on first failure, flips
a session-wide health switch: Origin rendering stops, widget mixins stop
cancelling the vanilla draw, and the standalone suppressions (title panorama,
menu-list strips, the vanilla-logo redirect) release — so vanilla visuals
genuinely return instead of a black screen or a crash. Both mixin configs are
`required:false` / `defaultRequire:0`, so renamed targets on other game
versions skip per-surface at load time too. Worst case on any version/loader
mismatch: the vanilla look. See `VERSIONS.md` for the full strategy.

## Correctness notes / assumptions to verify on first launch

1. **Loading screens** (`mixin/loading/`, `originclient.loading.mixins.json`,
   `required:false`): assumes `LevelLoadingScreen`, `ReceivingLevelScreen`,
   `ProgressScreen`, `ConnectScreen` each **override** `render(GuiGraphics,
   int,int,float)`. If one doesn't, its mixin is skipped silently (config is
   non-fatal) and that screen stays vanilla — no crash. Verify each shows the
   Origin scene: singleplayer world load, join a server, create a world,
   connect screen.
2. **Indeterminate bar**: intentionally not tied to real progress (reading each
   screen's progress field needs an unverifiable `@Shadow`). ProgressScreen's
   `getTitle()` may be empty, so "preparing world" may show just the bar — if
   Will wants the live stage text ("Building terrain"), that's a follow-up that
   needs a javap-confirmed shadow of the stage field.
3. **Cursor glow in-world**: now also draws on in-game menus (pause, inventory,
   containers) over the blurred backdrop, at `renderBackground` TAIL (behind the
   panel content). Confirm it's not distracting over container screens.
4. **`RenderSystem.defaultBlendFunc()`** and **`Screen.getTitle()`** are the
   only APIs used here without a javap confirmation this round; both are
   ultra-stable. A wrong mixin target fails loudly at build, not silently.

## Not done (out of scope this round)

- Sodium Video Settings restyle (its own widget classes — separate decision).
- Create-world nav tabs / text input fields (still vanilla-styled).
- Real progress on the loading bar (needs shadowed progress fields).
