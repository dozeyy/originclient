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
1. **Fabric only.** No Forge, no NeoForge, no OptiFine. The launcher always
   installs Fabric + the matching Origin build quietly (Lunar/Feather model).
   There is no loader choice anywhere in the UI or the code.
2. **Every supported version gets the FULL Origin experience.** Identical look
   across versions — title screen, every loading/progress screen, mod menu, and
   HUD must match the Origin design on every supported version. Vanilla menus
   are NOT an acceptable shipped state; fail-soft-to-vanilla exists only as a
   crash-safety net, never the intended result.
3. **Every supported version has shader integration.** Iris + Sodium work on
   every version Origin offers. A version doesn't ship until its shaders work.
4. **Never broken.** Whatever version the player picks, the game boots and the
   Origin surfaces work — or degrade silently to vanilla, never crash. Boot
   crashes surface the CrashReportWindow naming the culprit mod when evidence
   allows (Core/Launch/CrashAnalyzer.cs), with an Origin-only retry.

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
1.20, 1.20.1, 1.20.4, 1.21, 1.21.1 live; 1.21.2–1.21.11 staged (one build),
26.2 staged (mid-port). All Fabric. Per-version status, install models, and
the promotion checklist live in `src/mods/VERSIONS.md` — keep that file the
single source of truth, not this one.

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
  on Java 21, 1.20.x on Java 17 bytecode, 26.2 on Java 25. Shaders via Iris;
  perf via Sodium/Lithium/FerriteCore. 1.21.1 bundles its perf stack
  jar-in-jar; other versions get the stack standalone from
  `PerformanceModCatalog` (`VersionManager.OriginBuilds` records which model).

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

## Current state (2026-07-13)
- Launcher shipping via tag flow (latest launcher-v1.0.21+; auto-update).
  Auth chain: MSA→Xbox→XSTS confirmed; Minecraft `login_with_xbox` returns 403
  (leading theory: new-app-registration propagation) — `OfflineTestMode` in
  Settings→Developer is the test path until resolved.
- Live Origin builds: 1.21.1 (bundled stack), 1.20/1.20.1, 1.20.4, 1.21 —
  runClient-verified except 1.21 (at-home confidence check pending).
- Fabric-only cleanup landed 2026-07-12: loader selector, Forge/OptiFine,
  Voxy, and PerformanceMode all removed end to end.
- Crash system v1: `CrashAnalyzer` + `CrashReportWindow` (boot crashes name
  the culprit mod; Origin-only retry). In-game debug screen still open.
- Remaining to satisfy the mandate: fill the 1.21.3–1.21.10 shader catalog
  from Modrinth (at home), then at-home javap+runClient verification flips
  those (and 1.21.11, 26.2) live per `src/mods/VERSIONS.md`.

## Roadmap
- [x] 1.20 / 1.20.1, 1.20.4, 1.21.1 — full Origin experience, verified.
- [x] 1.21 — wired live; runClient at home is the remaining confidence check.
- [~] 1.21.2 – 1.21.11 — one staged build; flips live per-version behind the
  shader catalog + runClient gate (`src/mods/staged/1.21.11/PORT-12111.md`).
- [~] 26.2 — staged, render layer mid-port (`src/mods/staged/26.2/PORT-262.md`).
- [x] Crash system v1 — boot-crash blame + Origin-only retry.
- [ ] Crash system v2 — in-game Origin debug screen, log-cause detection.
- [ ] Light theme (Deskify inverse tokens).
