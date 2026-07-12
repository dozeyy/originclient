# Origin

> Project-specific config. Global rules, identity, model + skill routing live in
> `~/.claude/` and load automatically — this file is ONLY what's unique to this
> project. Keep it lean (it loads every session). Long logs go in `./MEMORY.md`.

## Type
application

## What it is
- **Origin Launcher** — premium Windows desktop Minecraft launcher (C# / .NET 8,
  WPF). Account management, version handling, performance tuning, launch.
- **Origin Client** — the in-game Fabric mod it installs: restyled title screen,
  loading screens, mod menu, HUD, and a curated set of QoL mods. Java, Fabric,
  Mojmap, Gradle + Loom. Lives under `src/OriginClient.Mod*/`, one module per
  Minecraft API family.

## The mandate (non-negotiable)
1. **Fabric only.** No Forge, no NeoForge, no OptiFine. The launcher always
   installs Fabric + the matching Origin build quietly (Lunar/Feather model).
2. **Every supported version gets the FULL Origin experience.** Identical look
   across versions — the title screen, every loading/progress screen, the mod
   menu, and the HUD must all match the Origin design on every supported version.
   Vanilla menus are NOT an acceptable shipped state; fail-soft-to-vanilla exists
   only as a crash-safety net, never the intended result.
3. **Every supported version has shader integration.** Iris + Sodium work on
   every version Origin offers. A version doesn't ship until its shaders work.
4. **Never broken.** Whatever version the player picks, the game boots and the
   Origin surfaces work — or degrade silently to vanilla, never crash.

## Supported versions
1.20, 1.20.1, 1.20.4, 1.21, 1.21.1, 1.21.11 — all Fabric. Each needs the full
Origin UI + shaders (per the mandate). Reaching that is a **per-version build**:
Minecraft renames GUI/render internals between versions and mixins bind to exact
signatures, so there's one Origin jar per API family, and the launcher installs
the matching one. ~95% of the mod is shared (theme, assets, layout, tools/); only
the two renderers' MC calls + the mixin layer are version-touching.

**Verification bar:** compiling clean only proves mixin *targets exist*. `@Inject`
descriptor and `@Shadow` mismatches only surface at mixin **apply** time — always
smoke-test a new/ported build with `./gradlew runClient` (offline dev account,
independent of launcher auth) and confirm zero `Mixin apply ... failed` lines
before calling a version done.

## Stack
- Launcher: C# / .NET 8, WPF. CmlLib.Core (Mojang manifest, downloads, Fabric
  install, launch args). MSAL for MSA→Xbox→XSTS→Minecraft auth. Windows DPAPI for
  token-at-rest. System.Text.Json for config.
- Mod: Java + Fabric (loader + API) + Loom + official Mojang mappings. 1.21.x on
  Java 21, 1.20.x on Java 17. Shaders via Iris; perf via Sodium/Lithium/
  FerriteCore. 1.21.1 jar bundles its perf stack jar-in-jar; other versions get
  the perf stack installed standalone from `PerformanceModCatalog` alongside the
  Origin jar (`VersionManager.OriginBuilds` records which model per version).

## Brand
Origin mark = 3 tilted stroke-only rings sharing one center (0°/60°/120°, atom/
orbital), soft monochrome glow. Deskify-derived monochrome (dark default, one
tonal accent `#E0E0E0`, no hue even in the glow). Minimal center-focused launcher:
big centered Play button, version dropdown above, chromeless window, floating
corner controls. In-game menus match this exactly.

## Constraints
- Launcher cold start <3s, 60fps+ UI, no jank switching tabs/versions.
- Accounts encrypted at rest + device-bound; no plaintext tokens on disk.
- Instances isolated per version under `%LocalAppData%/OriginLauncher/instances/`.
- Newest launch action cancels any in-flight one.

## Current state (2026-07-12)
- Launcher shipping (v1.0.19, auto-update). Auth chain: MSA→Xbox→XSTS confirmed;
  Minecraft `login_with_xbox` returns 403 (leading theory: new-app-registration
  propagation) — retry sign-in after time; Azure config verified correct.
- 1.21.1 Origin mod: full, shipping.
- 1.20 Origin mod (`src/OriginClient.Mod120`, covers 1.20 + 1.20.1): full,
  shipping (runClient-verified, zero mixin-apply failures).
- 1.20.4 Origin mod (`src/OriginClient.Mod1204`): full, shipping — ported from
  Mod120 (1.20.2-era API: 4-arg renderBackground/mouseScrolled, gui sprites,
  PlayerSkin, panorama drawn inline in TitleScreen.render), every mixin javap-
  verified against the mapped 1.20.4 jar, runClient-verified clean with the
  catalog's Sodium 0.5.8 + Iris 1.7.2. Wired into `OriginBuilds`, csproj
  bundling, and the release workflow.
- 26.2 mod port + Voxy support (1.21.1): merged to `main` (PR #15) and shipped in
  launcher v1.0.19. 26.2's own Origin build stays STAGED in `OriginBuilds` (render
  layer mid-port to retained-mode GUI); 26.2 is offered as Fabric+shaders today.
- 1.21 Origin mod (`src/OriginClient.Mod121`): byte-identical source to 1.21.1
  (shared pre-1.21.2 blit API), standalone install model, its own 1.21-mapped
  build. Wired LIVE (OriginBuilds + csproj + real CI build steps — 1.21 is a real,
  Full-shader version the runner can compile). runClient at home is the remaining
  confidence check; a runtime miss fail-softs to vanilla.
- 1.21.2–1.21.11 Origin mod (`src/OriginClient.Mod12111`): one build covers the
  whole post-1.21.2-blit-rework family (source byte-identical across the range;
  fabric.mod.json `>=1.21.2- <1.22`; OriginBuilds maps every version string to the
  one jar, Mod120-style). Only the 1.21.2 `blit` rework differs from 1.21.1, so
  look/feel are identical. STAGED per-version: each flips live once its shaders are
  Full in the catalog AND the jar is runClient-verified. Only 1.21.11 is Full
  today; 1.21.3–1.21.10 are shader-blocked (Partial), 1.21.2 absent. Guide:
  `src/OriginClient.Mod12111/PORT-12111.md`.
- Remaining to satisfy the mandate: fill the 1.21.3–1.21.10 shader catalog from
  Modrinth (at home), then the at-home javap+runClient verification that flips
  those (and 1.21.11, 26.2) live.

## Roadmap
- [x] **1.20 / 1.20.1** — runtime mixin fixes done, UI + shaders verified in-game.
- [x] **1.20.4** — per-version build (renderBackground 4-arg era), UI + shaders.
- [x] **1.21** — per-version build (`src/OriginClient.Mod121`), byte-identical
  source to 1.21.1 (shared pre-1.21.2 blit API), standalone install model. Wired
  LIVE + real CI build (1.21 is a real, Full-shader version); runClient at home.
- [~] **1.21.2 – 1.21.11** — one build (`src/OriginClient.Mod12111`) covers the
  whole post-1.21.2-blit-rework family (blit reworked at 1.21.2; look/feel =
  1.21.1). Staged per-version: each flips live once its shaders are Full in the
  catalog AND the jar is runClient-verified. Only 1.21.11 is Full today; 1.21.3–
  1.21.10 are shader-blocked, 1.21.2 absent (PORT-12111.md).
- [ ] Crash system: Origin debug screen, log-cause detection, disable-mods-&-retry.
- [ ] Light theme (Deskify inverse tokens).
