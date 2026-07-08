# Origin Client across Minecraft versions & loaders

The product promise: **whatever version/config the player picks in Origin
Launcher, the game boots and the Origin menus work — flawlessly or not at all,
never broken.** That is delivered in two layers:

1. **The right build for the right version** — installed by the launcher
   (per-version builds, below).
2. **A fail-soft runtime** — if anything is mismatched anyway, every Origin
   surface degrades to vanilla instead of crashing (contract, below).

## Loader reality (the simple truths)

- Origin Client is a **Fabric** mod. A Fabric jar cannot load under Forge, and
  nothing can load under bare vanilla — there is no injection point without a
  loader.
- Therefore **the launcher guarantees the loader**: every Origin install is
  Fabric (installed quietly and automatically) + the Origin build matching the
  chosen Minecraft version. This is the Lunar/Feather model. When the player
  picks "vanilla" in the launcher, they get Fabric + Origin only — invisible
  to them except that the menus look like Origin.
- **OptiFine is not a supported pairing.** It conflicts with the bundled
  Sodium, and both of its draws are covered by the bundled stack: performance
  by Sodium/Lithium/FerriteCore (already included), shaders by Iris (candidate
  to bundle later). The launcher must never install OptiFine alongside the
  Origin jar.
- **Forge/NeoForge** support would be a separate port (Architectury-style
  shared core or a parallel module), a Phase 2+ decision — it is not a build
  flag on this codebase.

## Per-version builds

Minecraft renames and reshapes its GUI internals between versions, and mixins
bind to exact class/method names — so "supports every version" means **one
Origin build per supported Minecraft version**, and the launcher installs the
matching one. A single jar spanning versions is not possible at this layer.

- **Currently verified target: 1.21.1** (`fabric.mod.json` pins `~1.21.1`).
  Every mixin target in this repo was javap-confirmed against the mapped
  1.21.1 jar.
- Known breakpoints to re-verify **with javap, per version** before porting
  (these are from memory and must not be trusted until verified):
  - `GuiGraphics.blit` overloads were reworked in **1.21.2** (render-pipeline
    parameters) — breaks every textured draw in the renderers at runtime.
  - `GuiGraphics` itself only exists since **1.20**; before that, drawing is
    `PoseStack`-based — a substantially bigger port.
  - `Screen.renderBackground`'s signature changed around **1.20.2**.
  - Screen/widget class names used by the mixins (`LevelLoadingScreen`,
    `ReceivingLevelScreen`, `ProgressScreen`, `ConnectScreen`,
    `AbstractSliderButton.value`, `LogoRenderer`/`SplashRenderer` call shapes
    in `TitleScreen.render`) look stable across 1.20–1.21.x but each needs
    per-version confirmation.
- Recommended tooling when build/network access exists: **Stonecutter**
  (per-version source sets; ~95% of this code is shared) or per-version
  branches. The codebase is already split the right way for it:
  version-agnostic (theme tokens, baked assets, layout math, `tools/`
  generators) vs version-touching (the two renderers' Minecraft calls + the
  mixins).

## The fail-soft contract (implemented)

- **Load time**: both mixin configs are `required: false` with
  `defaultRequire: 0` — a missing or renamed target skips that one surface
  silently; the game always boots.
- **Runtime**: every Origin draw entry point catches `Throwable` (including
  `NoSuchMethodError`-class linkage failures from API drift). First failure
  flips a session-wide switch: all Origin rendering stops, and every
  suppression of vanilla drawing (title panorama, menu-list strips, the
  vanilla logo redirect) is gated on that health flag — so the *vanilla*
  visuals genuinely come back rather than leaving a black screen. Widget
  mixins only cancel the vanilla draw when the Origin draw reports success.
- Net effect, per surface: **worst case is the vanilla look; there is no
  crash case.** This is what makes an unexpected version/loader combination
  safe while its dedicated build is still being ported.

## Menus "fitting" each version automatically

Origin restyles **in place** and never defines menu contents. Whatever
screens and widgets a given Minecraft version has, they keep their own
layout, options, and behavior — Origin only repaints them. An option that
doesn't exist in some version simply isn't there; one that's new appears
already styled (if it uses the standard widget classes) or vanilla (if not).
No per-version menu curation is required — that requirement is satisfied by
the architecture itself.
