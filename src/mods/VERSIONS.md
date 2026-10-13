# Origin Client — version registry & how this tree works

The in-game mod ships **one build per Minecraft API family** (Minecraft
renames GUI/render internals between versions and mixins bind to exact
signatures, so one jar can't span families). This file is the single
source of truth for what's live, what's staged, and how code is shared.

## The tree

```
src/mods/
  shared/     the version-independent core (79 files today: theme, gui
              layout, hud, mods menu, config, and the few mixins that are
              byte-identical everywhere). Lives ONCE here; copied verbatim
              into every module by tools/shared-sync/sync.py.
  versions/   SHIPPED modules — built by CI, bundled into the launcher.
  staged/     WIP modules — never built by CI, never shipped. Work here
              can't affect players.
```

Every module under `versions/` and `staged/` is a **fully standalone Gradle
build** (own gradlew, build.gradle, complete src/). Nothing in any module
changes unless you change it or deliberately run the sync.

### The sharing rule

- **Shared fix or feature** → edit `shared/src/...`, run
  `python tools/shared-sync/sync.py`, rebuild affected modules.
- **Version-specific fix** → edit that module only. If the file also exists
  in `shared/`, add its path to the module's `overrides.txt` — that marks
  the deliberate fork and sync never touches it again.
- CI (`build-check.yml`) runs `sync.py --check` on every push: any silent
  drift between `shared/` and a module fails the build.
- `live-modules.json` (next to this file) is the machine-readable list of
  shipped modules that every CI workflow reads; this file is the human one.

## Live versions (in `versions/`)

**2026-10-11:** every module below now carries the 1.21.1 design and mod set (see
MEMORY.md and `docs/PORTING-ERA-NOTES.md`). JEI ships only where a Fabric JEI exists
(not 1.21.2-1.21.8, 1.17.1, 1.16.5); Color Saturation is absent on 1.16.5 (no shaders).

| Module | Covers | fabric.mod.json range | Install model | Java |
|--------|--------|----------------------|---------------|------|
| `26.3` | 26.3 | `~26.3` | standalone stack (Sodium 0.9.2 + Iris 1.11.7, Lithium 0.26.2 …) + JEI 31.12 jar-in-jar. Derived from `26.2` on 2026-10-13. 26.3 swapped GLFW for **SDL3** (window/input; `InputConstants.KEY_*`, `SDLVideo`) and moved the render API to **`com.mojang.renderpearl.api`** (pipelines, textures, GpuFormat) — see `docs/PORTING-ERA-NOTES.md` "26.3". No 26.3.x patch releases yet (2026-10-13); add them to this jar's range only after an API diff | 25 |
| `26.2` | 26.2 | `~26.2` | standalone stack (Sodium 0.9.2 + Iris 1.11.4 …) + JEI jar-in-jar. **26.x era**: unobfuscated (no mappings), extract/submit GUI — ported from 1.21.11 on 2026-10-13, see `docs/PORTING-ERA-NOTES.md` "26.x era" | 25 (CI runs Gradle on JDK 25: `"java": "25"` in live-modules.json) |
| `26.1` | 26.1, 26.1.1, 26.1.2 | `>=26.1- <26.2-` | standalone stack (26.1.2: Sodium 0.9.2 + Iris 1.11.4; 26.1 / 26.1.1: Sodium 0.8.9 + Iris 1.10.9 — Iris 1.11 needs Sodium 0.9, whose only build here requires 26.1.2) + JEI 29.37 jar-in-jar (newer JEI nests mezz_config, which demands 26.1.2 exactly and would not boot on 26.1 / 26.1.1). **One jar for all three**: version-scout's API diff found no class or public signature change across 26.1 → 26.1.2. Derived from `26.2` on 2026-10-13 (a middle era: extract GUI like 26.2, but `Gui` is still the HUD, `mc.screen`, immediate block outline, `RenderType.draw`, beds are block entities) — `docs/PORTING-ERA-NOTES.md` "26.1.x" | 25 |
| `1.21.1` | 1.21.1 | `~1.21.1` | **Bundles perf stack jar-in-jar** (Sodium/Indium/Iris/Lithium/…) | 21 |
| `1.20` | 1.20, 1.20.1 | `>=1.20- <1.20.2` | Origin jar + standalone catalog stack | 17 (via JDK 21) |
| `1.20.4` | 1.20.3, 1.20.4 | `>=1.20.3- <1.20.5` | standalone stack | 17 (via JDK 21) |
| `1.21` | 1.21 | `>=1.21- <1.21.1` | standalone stack (source byte-identical to 1.21.1 — shared pre-1.21.2 blit API — except Store cosmetics, 1.21.1-only for now: docs/STORE.md) | 21 |
| `1.21.8` | 1.21.8 | `>=1.21.6- <1.21.9` | standalone stack (Matrix3x2fStack + no-setShaderColor era) | 21 |
| `1.21.10` | 1.21.10 | `>=1.21.10- <1.21.11` | standalone stack (split from 1.21.11: the 1.21.11 mapping-rename wave made one jar impossible) | 21 |
| `1.21.11` | 1.21.11 | `>=1.21.10- <1.22` | standalone stack (render-pipeline + world-event-v2 era) | 21 |
| `1.19.4` | 1.19.4 | `>=1.19.4- <1.20-` | standalone stack (**pre-GuiGraphics**: PoseStack via the `Gfx` wrapper) | 17 |
| `1.19.3` | 1.19.3 | `>=1.19.3- <1.19.4-` | standalone stack (hybrid era — its own jar, see below) | 17 |
| `1.19.2` | 1.19–1.19.2 | `>=1.19- <1.19.3` | standalone stack (pre-JOML) | 17 |
| `1.18.2` | 1.18–1.18.2 | `>=1.18- <1.19-` | standalone stack (TextComponent era, no OptionInstance) | 17 |
| `1.17.1` | 1.17–1.17.1 | `>=1.17- <1.18-` | standalone stack (merged source set — no bundled server jar) | 16 |
| `1.16.5` | 1.16.5 | `>=1.16.5- <1.17-` | standalone stack (**fixed-function GL**, no `setShader*`) | 8 |

All are boot-verified with zero mixin-apply failures and full shader
integration (Iris + Sodium from the catalog pins). Each module's
overrides.txt lists exactly what it forks from `shared/` for its API era.

### Why the 1.21.x line is several builds (the hard truth about it)

"1.21.2–1.21.11 is one build like 1.21.1" turned out to be false. Across
that range Minecraft rewrote its render/GUI/input system in **stages**, and
each stage introduces a genuinely-new-at-that-version class the compiled jar
references — so a single jar `NoClassDefFoundError`s on the versions below
its build target (proven by a per-version boot sweep through the real
launcher). The verified runtime boundaries:

| Boundary at | What appears |
|---|---|
| 1.21.2 | `GuiGraphics.blit` render-pipeline rework |
| 1.21.5 | hitboxes extracted into `HitboxRenderState` |
| 1.21.6 | GUI transforms → `Matrix3x2fStack`; `setShaderColor` removed |
| 1.21.9 | new input-event API (`MouseButtonEvent`) + typed `KeyMapping.Category` |
| 1.21.10 | Fabric API moved `WorldRenderEvents` into the `.world` subpackage |

So each 1.21.x sub-family needs its **own** Origin build (a real port with
its own mixin-descriptor work + boot verification), not a config flip.
**Shipped:** 1.21.2/1.21.3/1.21.4 (the `1.21.4` jar), 1.21.6/1.21.7 (the `1.21.6`
jar), 1.21.8, 1.21.10, 1.21.11. **PULLED (not in the picker at all):** 1.21.5 and
1.21.9.
The 1.21.4 and 1.21.8 modules are the templates for the sub-families around
them; the 1.21.11 module covers 1.21.9 once its Fabric-API `.world` path is
reverted. The 1.21.5 module is still on disk and remains the reference for the
blit-rework + HitboxRenderState era even though the version is not shipped.

**Gone LIVE 2026-07-14 (launcher-v1.0.23):** `1.20.2`, `1.21.4` (covers 1.21.2/3/4),
`1.21.6` (covers 1.21.6/7), and the `1.21.10` split — all boot-swept clean, in-world
verified on 1.21.11 by Will (outline, overlay, chunk borders, particles, motion blur,
zoom, lock icon). Remaining known gaps: motion blur inert on <=1.21.5 (no persistent
post-target support). On 1.21.11 hitboxes (gizmo system), nametags, and
tile-entity culling are now ported and wired (2026-07-26) — the earlier
"deferred-render port pending" note is resolved. **1.21.11 motion-blur caveat
(era limitation, accepted per mandate 5):** its reworked `PostPass` bakes each
uniform into an immutable, non-mappable `GpuBuffer` at load and exposes no
per-frame setter, so the 0..10 slider maps onto 3 baked strength variants
(`motion_blur_1/2/3.json`) instead of 1.21.1's single chain with a live,
time-derived `Amount` uniform. Result on 1.21.11: the slider is stepped and each
step's trail length is framerate-dependent. True parity would require recreating
the blur pass's UBO every frame (per-frame GPU-buffer churn racing the private
`addToFrame` map) — not worth the stability risk; the baked variants are the
best-fit stable method for this render era.

## Legacy versions (1.8.9 / 1.12.2, Forge) — PULLED 2026-08-06

**Both are removed from the supported lineup** (Will's call). They are gone from
`VersionCatalog`, so their grid cards no longer appear at all — the picker is
Fabric-only now — and neither workflow builds them nor does the launcher bundle
their jars or the Forge version JSONs.

The modules (`versions/1.8.9`, `versions/1.12.2`) and the launcher-side install
path (`LegacyForgeInstaller` / `OptiFineInstaller` / `LegacyStackInstaller`, plus
the LEGACY BRANCH in `VersionManager.Prepare`) are still in the repo but are now
**unreachable code** — no supported version uses Forge. Kept rather than deleted
so re-adding either version is a catalog + CI change rather than a rewrite; if
they are not coming back, that subsystem is a clean deletion.

Their original toolchain, for reference if revived:

| Module | Loader | Toolchain | Java | Install model |
|--------|--------|-----------|------|---------------|
| `1.8.9` | Forge 11.15.1.2318 | gg.essential.loom 1.3.12, Gradle 8.7, MCP stable_22 | 8 (foojay auto-provisioned) | Origin jar + OptiFine HD_U_M5 + TexFix/BetterFps |
| `1.12.2` | Forge 14.23.5.2860 runtime / 2847 dev | gg.essential.loom 1.3.12, Gradle 8.7, MCP stable_39-1.12 | 8 (foojay auto-provisioned) | Origin jar + OptiFine HD_U_G5 + FoamFix/Phosphor/VanillaFix/TexFix/BetterFps |

These two versions predate Fabric entirely — Forge + OptiFine was the only
loader/shader stack that exists there (the call Lunar/Feather made too). OptiFine
was never redistributed: it downloaded at install time (official site first,
mirrors after) with every source SHA-1-pinned.

**These modules do NOT consume `shared/`.** The shared core is Mojmap/modern-
Fabric source that cannot compile against MCP-era APIs, so each legacy module
is a from-scratch re-implementation of the Origin surfaces (theme constants,
ring scene, wordmark reveal, mod menu, HUD + editor, fail-soft contract —
ported by hand from the shared design; the baked PNG art is byte-identical).
Each carries a `.no-shared-sync` marker, which `sync.py` (and its `--check`
CI gate) honors as "skip this module entirely."

**No mixins on legacy.** Every surface hangs off Forge events (GuiOpenEvent /
DrawScreenEvent / RenderGameOverlayEvent), so the mixin-apply failure class
doesn't exist here; the fail-soft ladder is the same volatile-latch pattern
(one Throwable → vanilla for the session). Generic menus (options, world
select) are restyled by vanilla-texture override (options_background,
widgets.png) rather than a scene takeover — full scenes cover the title
screen and every loading/connecting/working screen.

## Staged versions (in `staged/`)

| Module | Covers | fabric.mod.json range | Java | Status | Blocking / next step |
|--------|--------|----------------------|------|--------|----------------------|
| — | | | | | (none — 26.3, 26.2 and 26.1 – 26.1.2 all went live 2026-10-13) |

**1.21.9 was pulled entirely** (removed from `VersionCatalog` — it was the hard
input-event-boundary + fabric-API-gap hybrid; not worth carrying). Its analysis
lives in memory if ever revisited.

## The version picker shows only shipped versions — no "Coming Soon"

**Will, 2026-10-12:** versions Origin doesn't have will never ship, so the
launcher's picker no longer lists them greyed out. `VersionCatalog` lists
exactly the `OriginBuilds` versions — nothing else — and
`tools/release/check_consistency.py` fails CI if the two ever disagree. Removed
that day: the whole 26.x card (26.1, 26.1.1, 26.1.2, 26.2), 1.20.6, 1.20.5,
1.20.3, 1.19.1, 1.19, 1.18.1, 1.17, and 1.16–1.16.4 (the 26 card's art went
with it; re-adding a version means re-adding it to its card). The 26 card came
back on 2026-10-13 listing 26.2 (its art restored from history); 26.1.2, 26.1.1
and 26.1 joined it the same day (one jar), and 26.3 (its own jar) on top. A player whose
saved version is no longer listed starts on the newest version (1.21.11) — the
launcher already drops any saved version the picker doesn't offer.

### The pre-1.20 PoseStack backend (1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4)

**LIVE 2026-07-15** (launcher-v1.0.24) — Will's order: "1.16.5 and every version
above it, skipping the unfinished 1.21.x gaps, Lunar-parity"; all six
boot-verified by him through the real launcher. These are pre-`GuiGraphics` (it only exists
since 1.20): the whole render core draws through `PoseStack` + static `GuiComponent`
methods instead. Rather than fork ~138 call-sites six times, every module routes all
drawing through **one `Gfx` wrapper** (`client/gui/Gfx.java`) that exposes the exact
GuiGraphics shapes the code already called (`fill`/`blit`/`drawString`/`enableScissor`/
`renderItem`/`pose()`) over a `PoseStack` — so the conversion was a type swap plus
`new Gfx(poseStack)` at each vanilla boundary, and every era difference lives inside
`Gfx`. Each module is a standalone build carrying `.no-shared-sync` (like the legacy
Forge pair — this is a second rendering backend `shared/` can't compile), so
`sync.py` skips them.

Verified boundaries that forced per-module work (all javap-confirmed against each
mapped jar; full detail in each module's `PORT-NOTES.md`):

| Boundary | What changes going older |
|---|---|
| 1.19.4 | last version with `LogoRenderer`, `AbstractWidget.isHovered`, `RenderType.debugQuads`, `MobEffectInstance.isInfiniteDuration`, hitbox extraction; TabNavigationBar/TabButton/ExperimentsScreen were *introduced here* |
| 1.19.3 | widgets go `renderWidget`→`renderButton` (base `AbstractWidget` only); no `LogoRenderer` (wordmark via `blitOutlineBlack`); dirt background returns — its own jar so 1.19.3 isn't shipped degraded |
| 1.19.2 | pre-JOML (`Axis`→`Vector3f.ZP`+Quaternion); `Button.builder`→ctor+public x/y; `enableScissor` gone at the 1.19 floor → re-implemented in `Gfx` |
| 1.18.2 | `Component.literal/translatable`→`TextComponent/TranslatableComponent`; no `OptionInstance` (Options are direct fields); pre-signature chat; `renderSky` gains Camera/isFoggy *at* 1.18.2 |
| 1.17.1 | **no bundled server jar → `splitEnvironmentSourceSets()` fails**: merged `src/client`→`src/main`; Java 16; `renderSky` is the 4-arg era; older Gson (no `keySet`) |
| 1.16.5 | **Java 8** (records/var/patterns/switch-expr/Java-9 libs all rewritten) **+ fixed-function GL** (no `RenderSystem.setShader*`: `Gfx` binds via `TextureManager.bind` + tints via `color4f`, items via the legacy matrix stack); motion-blur GLSL 150→110 |

Perf/shader stack for all six is Full in `PerformanceModCatalog` (era-paired
Sodium↔Iris pins, e.g. Iris `mc1.16.5-1.4.5` with Sodium `0.2.0`). **1.18.1 is
deliberately NOT offered** — its only Sodium is an alpha and its only Iris a
pre-release; "never broken" outranks coverage.

## Flipping a staged version live — the 4 coupling points

Promote in ONE commit, after javap-verifying every mixin descriptor against
the mapped jar and a clean `./gradlew runClient` (zero `Mixin apply ... failed`
lines):

1. **`src/OriginLauncher.App/Core/Versions/VersionManager.cs`** — uncomment
   the version's `OriginBuilds` entry (that's what makes the launcher
   install the jar).
2. **`src/mods/live-modules.json`** — `git mv` the module from `staged/` to
   `versions/` and add `{ "module": ..., "covers": ..., "versions": [...] }`
   ("versions" = the exact OriginBuilds keys for that jar — CI's
   check_consistency.py fails if they differ; the installer's default-version
   picker reads them). That one line is
   what makes build-check AND the release build it (in parallel, via
   `.github/workflows/build-mods.yml`), what the release's "every live jar is
   bundled" assert checks, and what creates its `mc <module>` GitHub label.
   Its Java level comes from the module's own `options.release`; CI runs
   Gradle on JDK 21 unless the entry says `"java": "25"` (every 26.x module:
   plan.py, build-mods.yml and dev-shots.yml read it; boot-smoke.yml lists it).
3. **`src/OriginLauncher.App/OriginLauncher.App.csproj`** — the `<Content>`
   bundle entry already exists for every module (a glob over its
   `build/libs`); verify its path points at `versions/<ver>` after the move.
4. **`src/OriginLauncher.App/Core/Versions/VersionCatalog.cs`** — add the
   version to its card (or a new card + `Assets/Versions/<key>.png`). The
   picker lists only what's here; check_consistency.py fails CI if it differs
   from `OriginBuilds`.

Pulling a version is the same four places in reverse — it leaves the picker
entirely (never greyed).

## Porting method that works

Copy the nearest API family module, adjust only version-forced deltas,
javap-verify every mixin descriptor against the mapped jar, then
runClient-verify. Compiling clean only proves mixin *targets exist* —
`@Inject` descriptor and `@Shadow` mismatches only surface at mixin apply
time.

Known API breakpoints: `GuiGraphics.blit` reworked in 1.21.2;
`Screen.renderBackground` gained args around 1.20.2; `GuiGraphics` only
exists since 1.20 (before that it's `PoseStack` draws — a much bigger port).

## The fail-soft contract (why "never broken" holds)

- Both mixin configs are `required: false` with `defaultRequire: 0` — a
  missing/renamed target skips that one surface silently; the game boots.
- Every Origin draw entry point catches `Throwable`. First failure flips a
  session-wide health switch: all Origin rendering stops and every
  suppression of vanilla drawing is gated on that flag, so vanilla visuals
  genuinely come back. Worst case is the vanilla look; there is no crash
  case. (This is the safety net, never the intended shipped state — see the
  mandate in the root CLAUDE.md.)

## Menus fit each version automatically

Origin restyles **in place** and never defines menu contents. Whatever
screens/widgets a version has keep their own layout and behavior — Origin
only repaints them. No per-version menu curation is needed.
