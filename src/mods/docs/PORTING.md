# Origin Client — Multi-Version Readiness & Execution Plan

Goal: **the Origin Client works on every supported version**, with the correct
per-version shaders/perf mods, the *same* auto-loaded settings, the *same* look
and feel, one-click easy, and multiplayer-safe (unbannable). This doc is the
execution plan for that "massive addition." It builds on `VERSIONS.md` (the
strategy) — read that first.

## Locked decisions (2026-07-10)

- **Build system: Stonecutter.** One repo, per-version source sets, ~95% shared
  code, only the version-touching layer forks. One place to fix a bug for all
  versions.
- **Version set: shader-capable only.** We only ship versions that actually work
  end-to-end (Sodium + Iris exist for them). Brand-new Minecraft releases the
  perf mods haven't caught up to are hidden until they do. Implemented already:
  the launcher's picker is now gated on `PerformanceModCatalog.HasShaderStack`.
- **Anti-ban posture: Lunar-tier.** Keep every current feature — they're all
  client-side QoL that Lunar/Feather also ship. No reach/aimbot/xray/auto-click
  anywhere. (One caveat below: Fly Boost.)

## Current state (audited 2026-07-10)

- **Mod builds for 1.21.1 only.** 50 of 61 client files reference Minecraft
  classes, so most of the code needs per-version verification — mixins bind to
  exact class/method names Mojang renames between versions.
- **Config + feel are already version-agnostic.** The settings registry
  (`mods/Mods.java`, `mods/ModsConfig.java`), theme tokens, baked assets, and
  layout math don't depend on Minecraft internals. So "same settings, same feel
  everywhere" is essentially free from the architecture — as long as those files
  stay in the *shared* source set (see Workstream 3).
- **🚨 Most modern versions currently have NO shaders.** The launcher's
  `PerformanceModCatalog.Data.cs` (auto-generated 2026-07-06) marks these
  `Partial` = no Sodium, no Iris: `1.21.3–1.21.11, 1.20.2, 1.20.5, 1.20.6, 1.17,
  26.x`. Only these have the full Sodium+Iris stack: `1.16.5, 1.17.1, 1.18.1,
  1.18.2, 1.19–1.19.4, 1.20, 1.20.1, 1.20.4, 1.21, 1.21.1`. Filling this is the
  single most direct "correct shaders per version" fix (Workstream 2).
- **Fail-soft contract is in place** — any unported/ mismatched version launches
  vanilla-looking, never broken.

## Workstreams

### 1. Build system → Stonecutter (the enabler)
Convert the single-version Gradle build to Stonecutter so one shared codebase
produces a jar per version. The codebase is already split the right way
(version-agnostic vs version-touching). Steps to run **when build/network access
exists** (can't be done or tested from the sandbox):
- Add the Stonecutter plugin to `settings.gradle` + `build.gradle`; declare the
  target MC versions (the shader-capable set above).
- Move version-touching code (`render/OriginScreenRenderer` Minecraft calls, all
  `mixin/**`) behind Stonecutter comments/`stonecutter` conditionals; keep the
  shared layer untouched.
- Per-version `gradle.properties`-equivalents for the bundled Sodium/Iris/Lithium
  versions (pull from the same numbers as the launcher catalog).
- CI/build produces `originclient-<mcver>.jar`; the launcher installs the match.

### 2. Fill the shader/perf catalog (do at home — Modrinth blocked here)
Regenerate `PerformanceModCatalog.Data.cs` from the live Modrinth API so *every*
supported version has Sodium + Indium + Lithium + FerriteCore + Iris. The picker
gate (Workstream 0, done) means a version only appears once this data exists, so
this is what actually turns shaders on per version. Keep the existing pinning
discipline (exact versions, Indium/Sodium 0.6.x pairing note in gradle.properties).

### 3. Formalize shared vs version-touching split
So Stonecutter forks stay tiny and settings/feel stay identical everywhere:
- **Shared (never forks):** `mods/**` (registry + config), `theme/**`, baked
  assets, `hud/HudPos`/layout math, `tools/**`.
- **Versioned (forks per MC):** the two renderers' Minecraft draw calls, every
  `mixin/**`, keybind/GLFW glue only where APIs moved.
Guard: the settings registry must never import a version-specific Minecraft type,
so a config written on 1.21.1 loads byte-identically on 1.20.1, etc.

### 4. Per-version verification checklist (javap each target before shipping)
Known breakpoints from `VERSIONS.md`, to confirm per version:
- `GuiGraphics.blit` overloads reworked in **1.21.2** — breaks every textured draw.
- `GuiGraphics` only exists since **1.20**; older = `PoseStack` (bigger port).
- `Screen.renderBackground` signature changed ~**1.20.2**.
- Widget/screen class names: `LevelLoadingScreen`, `ReceivingLevelScreen`,
  `ProgressScreen`, `ConnectScreen`, `AbstractSliderButton.value`,
  `LogoRenderer`/`SplashRenderer` call shapes in `TitleScreen.render`.
- **Iris internals** for the shader-perf hooks: `IrisBridge` reflection targets
  and `IrisShadowDirectivesMixin` (`PackShadowDirectives.getResolution/getDistance`)
  must be re-verified per Iris version — Iris renames internals across releases
  too. (Both are already fail-soft, so a miss = feature off, never a crash.)

### 5. Anti-ban / multiplayer-safety (Lunar-tier — keep everything)
Every feature is client-side. Classification:
- **Universally safe (Lunar/Feather ship these):** Zoom, Freelook, all HUD
  readouts (FPS/CPS/coords/armor/potion/keystrokes/server address), Toggle
  sprint/sneak, Chat tweaks, Motion blur, Nametag tweaks, Scoreboard styling,
  Particle changer, Block outline, Chunk borders, Hitboxes (vanilla F3+B).
- **Client-render-only, server never told:** Weather changer + Time changer —
  confirmed they only touch `client.level` / a render mixin, not the server.
- **Rare strict-server flag (kept, per Lunar-tier decision):** Fullbright (gamma),
  Freelook. Standard on Lunar; a handful of hardcore anticheats flag them.
- **⚠️ Fly Boost — action needed:** currently *defined but not implemented*
  (no code reads it → does nothing → zero ban risk today). When built, it MUST
  be gated strictly to creative/spectator flight (`player.getAbilities().flying`
  + `isCreative()/isSpectator()`). A survival speed multiplier would be a genuine
  ban risk. Alternative: remove the dead option.

### 6. Config portability & "same feel"
Already guaranteed by architecture (Workstream 3). Verify per version: a feature
whose Minecraft target doesn't exist on a version simply fails soft (option
hidden/no-op), settings JSON unchanged. Menus restyle in place, so each version's
own screens/options are kept and only repainted — no per-version menu curation.

### 7. Rollout sequence
Extend outward from the verified 1.21.1, cheapest-port-first:
1. **Tier A modern** (1.20 → newest shader-capable): current `GuiGraphics` code,
   per-version builds. Least work, biggest population.
2. **Tier B** (1.16.5 → 1.19.4): `GuiGraphics` present but some signature drift.
3. **Tier C classics** (1.8.9, 1.12.2 via Legacy Fabric): `GuiScreen` /
   fixed-function GL era — bigger, simpler-drawing port. (No Iris on these
   versions ever — they ship the menus/QoL without shaders.)

## Do-at-home checklist (blocked in the sandbox)
- Regenerate `PerformanceModCatalog.Data.cs` from Modrinth (Workstream 2).
- Stand up Stonecutter and confirm a clean `originclient-1.21.1.jar` still builds
  identically before adding any second version.
- javap each new version's mixin targets (Workstream 4).

## Open question for Will
The two classics (1.8.9 / 1.12.2) have **no Sodium/Iris ever** (impossible on
those versions) but big PvP demand. They're currently **kept** (Legacy Fabric,
menus + QoL, no shaders). Confirm keep — or drop them too if "must have shaders"
is absolute.
