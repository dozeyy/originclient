# Origin

> Project-specific config. Global rules, identity, model + skill routing live in
> `~/.claude/` and load automatically — this file is ONLY what's unique to this
> project. Keep it lean (it loads every session). Long logs go in `./MEMORY.md`.

## Working agreement (Will)

1. **Before making changes**: ask whatever clarifying questions are needed.
   Don't guess at intent on anything with more than one reasonable reading.
2. **After making changes**: give a short plain-English summary — what changed
   and exactly what Will should click/test. No jargon; Will doesn't touch the
   code, so write the summary for a player, not a programmer.
3. Keep the code optimized for **Claude readability** — Will never edits it.
   Comments explain constraints and reasons, one obvious way to do each thing.

## Type
application

## What it is
- **Origin Launcher** — premium Windows desktop Minecraft launcher (C# / .NET 8,
  WPF). Account management, version handling, performance tuning, launch.
  Lives in `src/OriginLauncher.App/`.
- **Origin Client** — the in-game Fabric mod it installs: restyled title screen,
  loading screens, mod menu, HUD, and a curated set of QoL mods. Java, Fabric,
  Mojmap, Gradle + Loom. Lives under `src/mods/` — see layout below.

## The mandate (non-negotiable)
1. **No loader choice, ever** (the Lunar/Feather model): the launcher always
   installs the right stack for the picked version quietly. **Fabric** for
   every version Fabric supports (all of 1.20+); **Forge + OptiFine** ONLY for
   the two legacy versions 1.8.9 and 1.12.2, which predate Fabric entirely —
   added 2026-07-14 on Will's explicit direction (amending the previous
   "Fabric only" rule; OptiFine is the only shader layer that exists there).
   No NeoForge. No loader selector anywhere in the UI or the code.
2. **Every supported version gets the FULL Origin experience.** Identical look
   across versions — title screen, every loading/progress screen, mod menu, and
   HUD must match the Origin design on every supported version. Vanilla menus
   are NOT an acceptable shipped state; fail-soft-to-vanilla exists only as a
   crash-safety net, never the intended result.
3. **Every supported version has shader integration.** Iris + Sodium on the
   Fabric versions, OptiFine shaders on the legacy two. A version doesn't
   ship until its shaders work.
4. **Never broken.** Whatever version the player picks, the game boots and the
   Origin surfaces work — or degrade silently to vanilla, never crash. Boot
   crashes surface the CrashReportWindow naming the culprit mod when evidence
   allows (Core/Launch/CrashAnalyzer.cs), with an Origin-only retry.
5. **Per-version best-fit, one consistent look.** Every version is built for its
   OWN layout and API era — there is NO one-size-fits-all implementation. If a
   mechanism that works on one version does not work on another, do NOT force it:
   swap in the best — fastest, most reliable, stable — replacement that works on
   THAT version. The method under the hood may differ per version; the player-
   facing look and feel must stay consistent across every version (mandate 2 —
   consistent, not identical internals). Prefer each version's own stable stack
   over dragging a newer/older mechanism onto it and breaking things. Example
   (2026-07-21): the mod list inside Video Settings uses Sodium's Config API on
   the versions already on stable Sodium 0.8; 1.21.1 ships stable Sodium 0.6.13
   (no Config API, and its 0.8 is a shader-breaking alpha), so it gets the same
   feature via a Sodium 0.6.13 GUI mixin instead — same look, different method.

## Repo layout (the boundaries that matter)
```
src/OriginLauncher.App/   the WPF launcher
src/mods/
  shared/                 version-independent mod core — ONE copy, synced out
  versions/<mc>/          SHIPPED per-API-family builds (CI builds these)
  staged/<mc>/            WIP builds — never built by CI, can't reach players
  VERSIONS.md             THE registry: live/staged status, sharing rules, and
                          the 3 coupling points to flip a staged version live
tools/shared-sync/sync.py propagates shared/ into every module; --check is the
                          CI drift gate (build-check runs it on every push)
docs/RELEASING.md         how shipping works (tag flow)
```
- **Shared fix** → edit `src/mods/shared/`, run `python tools/shared-sync/sync.py`.
- **Version fix** → edit that module only; if the file exists in shared/, list
  it in the module's `overrides.txt` (marks the deliberate fork).
- Staged work NEVER lives next to shipped work — `staged/` is the boundary.

## Supported versions
1.20, 1.20.1, 1.20.2, 1.20.4, 1.21, 1.21.1, 1.21.2/3/4, 1.21.5, 1.21.6/7,
1.21.8, 1.21.10, 1.21.11 are LIVE (Fabric), **plus the pre-1.20 set 1.16.5,
1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 (LIVE 2026-07-15, launcher-v1.0.24)**
and **the legacy pair 1.8.9 and 1.12.2 (Forge + OptiFine), LIVE 2026-07-14** —
each a from-scratch Forge-event port of the Origin surfaces (no mixins,
`.no-shared-sync`, boot- and shader-verified through the real launcher
pipeline). 26.2 staged. **Pre-1.20 is a SECOND rendering backend**, not a
gap-port: `GuiGraphics` only exists since 1.20, so those six modules draw via
`PoseStack` through a per-module `Gfx` wrapper that mirrors the GuiGraphics
method shapes (1.16.5 additionally is Java 8 + fixed-function GL). They are
`.no-shared-sync` for that reason. 1.18.1 is deliberately NOT offered (its
only Sodium is an alpha, its only Iris a pre-release). The 1.21.x line is NOT
one build — Minecraft rewrote its render/GUI/input system in stages, so each
sub-family is its own port. Still greyed in the picker: 1.21.9 (pulled). Per-
version status, API boundaries, install models, and the promotion checklist
live in `src/mods/VERSIONS.md` — keep that file the single source of truth, not this
one.

**Verification bar:** compiling clean only proves mixin *targets exist*.
`@Inject` descriptor and `@Shadow` mismatches only surface at mixin **apply**
time — always smoke-test a new/ported build with `./gradlew runClient`
(offline dev account) and confirm zero `Mixin apply ... failed` lines before
calling a version done.

## Releasing (main → tag)
`main` always reflects at least the latest release. To ship, from a green main:
`git tag launcher-v1.0.N && git push origin launcher-v1.0.N`. CI builds every
live mod jar + the launcher, asserts the bundled jars are actually present,
and publishes setup.exe + the self-update zip. Updates are mandatory for
players. Full rules: `docs/RELEASING.md`.

## Stack
- Launcher: C# / .NET 8, WPF. CmlLib.Core (Mojang manifest, downloads, Fabric
  install, launch args). MSAL for MSA→Xbox→XSTS→Minecraft auth. Windows DPAPI
  for token-at-rest. System.Text.Json for config. Settings writes go through
  `SettingsStore.Update(mutate)` ONLY — never write a whole cached snapshot.
- Mod: Java + Fabric (loader + API) + Loom + official Mojang mappings. 1.21.x
  on Java 21, 1.20.x/1.18–1.19.x on Java 17 bytecode, 1.17.1 on 16, 1.16.5 on
  8, 26.2 on Java 25. Shaders via Iris; perf via Sodium/Lithium/FerriteCore.
  1.21.1 bundles its perf stack jar-in-jar; other versions get the stack
  standalone from `PerformanceModCatalog` (`VersionManager.OriginBuilds`
  records which model).
- Pre-1.20 mod (1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4): still
  Fabric + Loom, but a SECOND render backend — no `GuiGraphics` before 1.20, so
  all drawing goes through each module's `client/gui/Gfx.java` (a PoseStack +
  `GuiComponent`-statics shim mirroring the GuiGraphics method shapes). Hence
  `.no-shared-sync` on all six. Era cliffs: `fabric-api` as a depends key does
  NOT resolve below 1.18 (Fabric API's mod id is `fabric` there — use that);
  Loom's `splitEnvironmentSourceSets()` fails below 1.18 (no bundled server
  jar → merged source set); 1.16.5 has no `RenderSystem.setShader*` at all
  (fixed-function: `TextureManager.bind` + `color4f`).
- Legacy mod (1.8.9 / 1.12.2): Java 8, Forge, MCP mappings via
  gg.essential.loom (Gradle 8.7, foojay-provisioned JDK 8), NO mixins — all
  Forge events. Launcher side: `LegacyForgeInstaller` (bundled version JSON +
  universal jar), `OptiFineInstaller` (download-at-install, SHA-1-pinned,
  official-site-then-mirrors, never redistributed), `LegacyStackInstaller`
  (era perf mods + FML splash theme). Shaders via OptiFine.

## Brand
Origin mark = 3 tilted stroke-only rings sharing one center (0°/60°/120°, atom/
orbital), soft monochrome glow. Deskify-derived monochrome (dark default, one
tonal accent `#E0E0E0`, no hue even in the glow). Minimal center-focused
launcher: big centered Play button, version dropdown above, chromeless window,
floating corner controls. In-game menus match this exactly.

## Constraints
- Launcher cold start <3s, 60fps+ UI, no jank switching tabs/versions.
- Accounts encrypted at rest + device-bound; no plaintext tokens on disk.
- App installs per-user to `%LocalAppData%\Programs\OriginLauncher`; ALL user
  data lives under `%LocalAppData%\OriginLauncher` (`Core/OriginPaths.cs` is
  the single path authority — never write user data anywhere else).
- Instances isolated per version under `%LocalAppData%/OriginLauncher/instances/`.
- Newest launch action cancels any in-flight one.
- One control, one job: no UI handler may mutate a setting other than its own.

## Current state (2026-07-21)
- **Latest release: `launcher-v1.0.29`** (mandatory auto-update; tag flow).
- **Since v1.0.24 (through v1.0.29):** JEI bundled + toggleable on all 11 Fabric
  versions that have a Fabric JEI; ThickLine hitboxes + real-item ModIcons
  propagated to every live version; Weather-changer rewrite + own-nametag fix
  everywhere; batched-gui tint fix (TV-static backgrounds) on 1.21.4/1.21.5;
  Add-Microsoft-Account WebView2-dispose fix; CI Gradle cross-release caching;
  **1.21.1 menu buttons reskinned to the Origin look (v1.0.29).**
- **Video-Settings mod rail (in progress, per mandate 5):** list the bundled mods
  as tabs inside Video Settings. It's Sodium's Config API — present only on stable
  Sodium 0.8 (the 1.21.8/1.21.10/1.21.11 line), so those versions will use it
  natively; **1.21.1 stays on stable Sodium 0.6.13** (its 0.8 is a shader-breaking
  alpha) and gets the same look via a Sodium 0.6.13 GUI mixin. Not yet built. Full
  findings in MEMORY.md → video-settings-mod-rail.
- **1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.19.3 / 1.19.4 went LIVE 2026-07-15**
  (`launcher-v1.0.24`) — Will's order: 1.16.5 and everything above it, skipping
  the unfinished 1.21.x gaps, Lunar-parity coverage. The pre-`GuiGraphics`
  PoseStack backend (one `Gfx` wrapper per module); all six boot-verified by
  Will. 1.19.3 got its OWN jar (hybrid era: 1.19.4 APIs but renderButton
  widgets/no LogoRenderer/dirt bg — the 1.19.4 jar degrades silently there).
  Boot testing found what a 100%-green mixin audit could not: Fabric API's mod
  id is `fabric` (not `fabric-api`) below 1.18 → loader refused to start; and
  1.16.5's `TitleScreen.render()` opens with a full-screen WHITE fill that
  vanilla hides behind the panorama → suppressing the panorama exposed it and
  buried the Origin backdrop. Both fixed; full detail in MEMORY.md.
- **1.8.9 + 1.12.2 went LIVE 2026-07-14** (Will's direct order, amending the
  Fabric-only mandate): full Origin experience re-implemented from scratch on
  legacy Forge (byte-identical art assets, ported theme/scene math, Forge
  events instead of mixins), installed silently as Forge + OptiFine + era
  perf stack. Both boot- and shader-verified end to end through the real
  launcher pipeline (Sildur's Vibrant loaded in-world on both).
- Launcher shipping via tag flow (latest launcher-v1.0.29; auto-update).
  Auth chain: MSA→Xbox→XSTS confirmed; Minecraft `login_with_xbox` returns 403
  (leading theory: new-app-registration propagation) — `OfflineTestMode` in
  Settings→Developer is the test path until resolved.
- Live Origin builds: 1.21.1 (bundled stack), 1.20/1.20.1, 1.20.4, 1.21 —
  runClient-verified except 1.21 (at-home confidence check pending).
- Fabric-only cleanup landed 2026-07-12: loader selector, Forge/OptiFine,
  Voxy, and PerformanceMode all removed end to end.
- Crash system v1: `CrashAnalyzer` + `CrashReportWindow` (boot crashes name
  the culprit mod; Origin-only retry). In-game debug screen still open.
- 1.21.5, 1.21.8, 1.21.10, 1.21.11 went LIVE 2026-07-13, each a separate
  sub-family port of the staged 1.21.x rewrite (render-pipeline era rename
  table in MEMORY.md), all four boot-verified clean through the real launcher.
  The "one build for the whole 1.21.2–1.21.11 family" assumption was wrong;
  1.21.2/3/4/6/7/9 still need their own ports (VERSIONS.md has the boundaries).

## Roadmap
- [x] 1.20 / 1.20.1, 1.20.4, 1.21.1 — full Origin experience, verified.
- [x] 1.21 — wired live; runClient at home is the remaining confidence check.
- [x] 1.21.5, 1.21.8, 1.21.10, 1.21.11 — live, each its own sub-family port,
  boot-verified (`src/mods/versions/{1.21.5,1.21.8,1.21.11}`).
- [x] 1.8.9 + 1.12.2 — legacy pair live (Forge + OptiFine, own from-scratch
  modules), boot- and shader-verified 2026-07-14.
- [x] 1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 — the pre-1.20 PoseStack
  backend, live + boot-verified 2026-07-15 (`launcher-v1.0.24`).
- [ ] 1.21.9 — pulled (input-event boundary + fabric-API gap); analysis in memory.
- [ ] 1.18.1 — blocked on a stable Sodium/Iris pair (alpha/pre-release only today).
- [~] 26.2 — staged, render layer mid-port (`src/mods/staged/26.2/PORT-262.md`).
- [x] Crash system v1 — boot-crash blame + Origin-only retry.
- [ ] Crash system v2 — in-game Origin debug screen, log-cause detection.
- [ ] Light theme (Deskify inverse tokens).
