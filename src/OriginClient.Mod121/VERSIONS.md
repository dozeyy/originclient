# Origin Client across Minecraft versions & loaders

> **CURRENT STATE (2026-07-10) — read this first; it supersedes the history below.**
> Origin is **Fabric only**. The classics (1.8.9, 1.12.2) and the whole
> Forge/Legacy-Fabric/OptiFine path were **removed** — that experiment didn't
> hold up in-game and pulled away from this codebase. The launcher's supported
> set is now **1.20 and up** (currently 1.20, 1.20.1, 1.20.4, 1.21, 1.21.1,
> 1.21.11), all on official Fabric.
>
> The Origin **mod** ships per API family: `OriginClient.Mod` (**1.21.1**,
> `~1.21.1`, bundles its perf stack jar-in-jar), `OriginClient.Mod120`
> (**1.20 + 1.20.1**, `>=1.20- <1.20.2`, Origin-only jar + standalone catalog
> stack), and `OriginClient.Mod1204` (**1.20.4**, `>=1.20.3- <1.20.5`, same
> model as 1.20). All three are runClient-verified with zero mixin-apply
> failures and full shader integration (Iris + Sodium from the catalog pins).
> Remaining per-version builds: **1.21** (reuse/adapt 1.21.1) and **1.21.11**
> (blit reworked at 1.21.2). Porting method that works: copy the nearest API
> family module, adjust only version-forced deltas, javap-verify every mixin
> descriptor against the mapped jar, then runClient-verify. Stonecutter (one
> codebase → per-version jars) remains the long-term intent; a first attempt
> hit a Loom split-sourceset JiJ-nesting blocker and was reverted.

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

## Pre-Fabric versions (older than 1.14)

Official Fabric only exists for Minecraft 1.14+, but the product promise (and
the website copy) is "beta to latest." Three real routes, in order of
preference:

1. **Community loader ports (the plan).** The Fabric loader has been ported
   backwards by community projects that keep the same mixin system and expose
   Fabric-style metadata servers the launcher can query the same way it
   queries Fabric's:
   - **Legacy Fabric** — Minecraft 1.3.2 → 1.13.2 (covers the two legacy
     versions that matter commercially: **1.8.9** and **1.12.2**);
     meta server at `meta.legacyfabric.net`. **DECIDED (Will, 2026-07-08):
     this is the route. The supported version set is exactly `1.8.9`,
     `1.12.2`, `1.16.5`, and `1.17 → newest`** — the launcher's picker shows
     only this set (`VersionManager.PinnedVersions` + the 1.17 floor).
     1.8.9/1.12.2 launch via Legacy Fabric; 1.16.5 and 1.17+ via official
     Fabric.
   - **Babric** (Beta 1.7.3) / **Ornithe** (broad historical coverage) — the
     beta-era route. **Out of scope**: the supported set stops at 1.8.9.
   Because the launcher owns the install, the player never sees any of this:
   pick a version, and the launcher installs the right loader flavor + the
   Origin build for that era, exactly as it installs Fabric for 1.21.x.
2. **Java agent** (`-javaagent:` flag in the launcher-built JVM args) —
   loader-independent class transformation that works on any version. The
   universal fallback, but it re-implements what mixin gives us for free, so
   it's only for a version no loader port reaches.
3. **Local jar patching at install time** — the pre-loader-era approach.
   Legally this must be a patch applied on the player's machine (binary
   diffs), never a redistributed modified Mojang jar. Highest maintenance;
   last resort only.

Port tiers for the mod code itself (the design — assets, tokens, layout math —
carries over 100%; only the drawing/mixin layer is per-era):

| Tier | Versions | Loader | GUI era (port size) |
|------|----------|--------|---------------------|
| A | 1.20 → latest | Fabric | `GuiGraphics` — current code, per-version builds |
| B | 1.16.5 → 1.19.4 | Fabric | `Screen` + `PoseStack` draws — moderate port |
| C | 1.8.9, 1.12.2 | Legacy Fabric | `GuiScreen`, fixed-function GL — bigger port, simpler drawing |
| D | Beta 1.7.3 | Babric/Ornithe | **out of scope** — supported set stops at 1.8.9 |

Rollout is demand-driven: v1 ships Tier A (1.21.1). Next targets by player
population are 1.8.9 and 1.12.2 (Tier C via Legacy Fabric). Until a tier is
ported, the launcher still launches that version **plain vanilla** (CmlLib
handles old manifests fine) — the "never broken" promise holds everywhere;
Origin menus arrive per tier.

### Launcher wiring: implemented (2026-07-08)

The launcher now installs Legacy Fabric end-to-end for 1.8.9 and 1.12.2 (the
two legacy versions in the supported set):

- `Core/Loaders/LegacyFabricInstaller.cs` — resolves the newest stable loader
  from `meta.legacyfabric.net/v2/versions/loader/{game}`, fetches the standard
  launcher profile from `.../{loader}/profile/json`, writes it into the
  instance's `versions/` folder, and hands the id to CmlLib to install/launch
  (identical mechanism to CmlLib's own modern-Fabric flow). A marker file in
  the instance root makes relaunches resolve offline.
- `VersionManager` routes `LoaderKind.Fabric` + pre-1.14 to it, and installs
  **Legacy Fabric API** (Modrinth `legacy-fabric-api`) into `mods/` the way
  modern versions get Fabric API. No Origin Client jar and no perf catalog on
  legacy versions — neither targets them yet, so **Origin menus stay vanilla
  there until the Tier C mod port**.
- `HomePage` now shows the Fabric toggle for legacy versions (recommended),
  with the existing Forge(+OptiFine) option still one click away.

**Verify at home** (none of this could be exercised from the sandbox — the
proxy blocks the meta servers):
1. `https://meta.legacyfabric.net/v2/versions/game` lists `1.8.9` and
   `1.12.2` (and `/v2/versions/loader/1.8.9` is non-empty).
2. Launcher → the picker shows exactly 1.8.9, 1.12.2, 1.16.5, 1.17+ → pick
   1.8.9 (then 1.12.2) → Fabric toggle appears and is the recommendation →
   Play: watch "Installing Fabric loader..." then a normal vanilla-looking
   game whose F3 brand string says fabric.
3. Old-version Java: 1.8.9 wants Java 8 — confirm CmlLib's bundled-runtime
   selection handles it (launch plain Vanilla 1.8.9 first to isolate this
   from the loader change).
4. A dropped-in legacy-fabric mod jar loads from the instance's `mods/`.

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
