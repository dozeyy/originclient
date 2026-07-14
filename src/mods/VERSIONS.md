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

## Live versions (in `versions/`)

| Module | Covers | fabric.mod.json range | Install model | Java |
|--------|--------|----------------------|---------------|------|
| `1.21.1` | 1.21.1 | `~1.21.1` | **Bundles perf stack jar-in-jar** (Sodium/Indium/Iris/Lithium/…) | 21 |
| `1.20` | 1.20, 1.20.1 | `>=1.20- <1.20.2` | Origin jar + standalone catalog stack | 17 (via JDK 21) |
| `1.20.4` | 1.20.3, 1.20.4 | `>=1.20.3- <1.20.5` | standalone stack | 17 (via JDK 21) |
| `1.21` | 1.21 | `>=1.21- <1.21.1` | standalone stack (source byte-identical to 1.21.1 — shared pre-1.21.2 blit API) | 21 |
| `1.21.5` | 1.21.5 | `>=1.21.3- <1.21.6` | standalone stack (blit-rework + HitboxRenderState era) | 21 |
| `1.21.8` | 1.21.8 | `>=1.21.6- <1.21.9` | standalone stack (Matrix3x2fStack + no-setShaderColor era) | 21 |
| `1.21.10` | 1.21.10 | `>=1.21.10- <1.21.11` | standalone stack (split from 1.21.11: the 1.21.11 mapping-rename wave made one jar impossible) | 21 |
| `1.21.11` | 1.21.11 | `>=1.21.10- <1.22` | standalone stack (render-pipeline + world-event-v2 era) | 21 |

All are boot-verified with zero mixin-apply failures and full shader
integration (Iris + Sodium from the catalog pins). Each module's
overrides.txt lists exactly what it forks from `shared/` for its API era.

### Why 1.21.2–1.21.9 aren't here yet (the hard truth about the 1.21.x line)

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
**Shipped (Will picked the popular versions):** 1.21.5, 1.21.8, 1.21.10,
1.21.11. **Not yet built:** 1.21.2, 1.21.3, 1.21.4, 1.21.6, 1.21.7, 1.21.9 —
each stays out of `OriginBuilds`, so the picker greys it "Coming Soon"
(shipping a vanilla-menu version would violate mandate #2). The 1.21.5 and
1.21.8 modules are the templates for the sub-families around them (1.21.5 →
1.21.3/1.21.4; 1.21.8 → 1.21.6/1.21.7; the 1.21.11 module → 1.21.9 once its
Fabric-API `.world` path is reverted).

**Gone LIVE 2026-07-14 (launcher-v1.0.23):** `1.20.2`, `1.21.4` (covers 1.21.2/3/4),
`1.21.6` (covers 1.21.6/7), and the `1.21.10` split — all boot-swept clean, in-world
verified on 1.21.11 by Will (outline, overlay, chunk borders, particles, motion blur,
zoom, lock icon). Remaining known gaps: motion blur inert on <=1.21.5 (no persistent
post-target support), and hitboxes/nametags/tile-entity-culling absent on
1.21.10/1.21.11 (deferred-render port pending).

## Staged versions (in `staged/`)

| Module | Covers | Status | Blocking / next step |
|--------|--------|--------|----------------------|
| `26.2` | 26.2 | does NOT compile | render layer mid-port to the retained-mode GUI (most source parked in `disabled262/`). Java 25. The 1.21.11 module's port solved many of the same API moves — start there. `staged/26.2/PORT-262.md` |

The three 1.20.2/1.21.4/1.21.6 modules above are wired into the launcher
(`VersionManager.OriginBuilds` + the csproj bundle, both pointing at their
`staged/` jars) and offered in the picker for boot-testing. **1.21.9 was pulled
entirely** (removed from `VersionCatalog` — it was the hard input-event-boundary +
fabric-API-gap hybrid; not worth carrying). Its analysis lives in memory if ever
revisited.

**Not attempted — 1.16.5, 1.17.x, 1.18.x, 1.19.x:** these are pre-`GuiGraphics`
(it only exists since 1.20). 28 of a module's 69 files + 11 `shared/` files draw
through `GuiGraphics` (~138 call-sites); pre-1.20 uses `PoseStack` + static
`GuiComponent` draws instead. That's a second rendering backend — a large separate
project, not a gap-port.

## Flipping a staged version live — the 3 coupling points

Promote in ONE commit, after javap-verifying every mixin descriptor against
the mapped jar and a clean `./gradlew runClient` (zero `Mixin apply ... failed`
lines):

1. **`src/OriginLauncher.App/Core/Versions/VersionManager.cs`** — uncomment
   the version's `OriginBuilds` entry (that's what makes the launcher
   install the jar).
2. **`.github/workflows/launcher-release.yml` AND `build-check.yml`** —
   uncomment the module's gradle build step (and `git mv` the module from
   `staged/` to `versions/`; update the step's `working-directory`).
3. **`src/OriginLauncher.App/OriginLauncher.App.csproj`** — the `<Content>`
   bundle entry already exists for every module and is conditional on the
   jar existing; verify its path points at `versions/<ver>` after the move.
   Also add the jar name to the release workflow's
   "Assert bundled mod jars present" list.

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
