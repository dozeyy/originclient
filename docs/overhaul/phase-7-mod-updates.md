# Phase 7 - Mod dependency updates, then the overhaul close and the release

Covers ORIGIN_PROGRESS P7-01 to P7-08, B-31, F-01, F-02. Standing rules: `docs/overhaul/README.md` (S2: dependency pins get an
**opus** code-reviewer with the security checklist; S8 release; S12). Phase branch `claude/ovh-p7` (track D).

## 1. Objectives
1. Audit every third-party mod dependency on every supported version: current version, latest compatible stable, breaking changes.
2. Update only what passes the 8-step gate below; hold or reject the rest with the reason. Never blindly install the newest.
3. Boot-test every affected configuration in CI; where CI cannot boot a configuration, say so and get suitable verification before
   shipping (04: Will's PC is allowed).
4. Close the overhaul: final ORIGIN_PROGRESS pass, the one launcher release (`launcher-v1.0.66`+), the final report, recommendations.

## 2. Original requests
03 "PHASE 7" (steps 1-8, "Do not ship an update that fails required checks", "If CI cannot provide a suitable Minecraft boot test,
report that limitation"). 01 section 14 (third-party mod updates), 22 (additional recommendations: F-02). 04 Release ("Yes, if it all
passes") and Reports (final overnight report contents).

## 3. Inspect first
- Per-module pins: each `src/mods/versions/<mc>/gradle.properties` and `build.gradle` (`modImplementation`, `include` nested jars;
  1.21.1 nests Sodium, Indium, Lithium, FerriteCore, ImmediatelyFast, ModernFix, Iris, JEI, ModMenu at `build.gradle:157-178`), and the
  1.8.9 stack in the launcher (`Core/Loaders/OptiFineInstaller.cs`, `LegacyStackInstaller.cs`).
- Launcher-installed mods: `Core/Loaders/PerformanceModCatalog.Data.cs` (31 entries, SHA pins; tests `PerformanceModCatalogTests.cs`);
  after Phase 5 also the vetted configurations and Fabric API pins (`Core/Compat`, component store).
- `src/mods/live-modules.json` (`covers`: one module serves several versions, e.g. 26.1 covers 26.1-26.1.2), `src/mods/VERSIONS.md`.
- Never booted in CI (B-31): 1.21.2, 1.21.3, 1.21.7, 1.20.1, 26.1.1 (covered versions; `boot-smoke.yml` boots each module on its
  primary version only). Notes (auto-memory): `jei-coverage-complete`, `video-settings-mod-rail` (1.21.1 stays on Sodium 0.6.13: 0.8
  breaks Iris there), `versions-md-porting-corrections`.
- Modrinth API: `https://api.modrinth.com/v2/project/<slug>/version` filtered by game version and loader (pin the version id).

## 4. Agent units
| Unit | Agent | Scope | Order |
|---|---|---|---|
| U7.1 Dependency audit | researcher | read-only; writes `docs/overhaul/p7-dependency-audit.md` | any time after Phase 0 (track D) |
| U7.2 Boot coverage | fabric-mod-dev (CI) | `boot-smoke.yml` gains a `covers` mode that boots a module on each covered version | after U7.1 |
| U7.3 Updates | fabric-mod-dev per module (parallel worktrees, one module each) + desktop-app-dev for catalog pins | approved audit rows | per module, after Phase 4 merged that module's wave |
| U7.4 Regression | verifier | everything touched | after U7.3 |
| U7.5 Overhaul close | chore-runner (ORIGIN_PROGRESS, MEMORY, merge), release-packager (release), orchestrator (report) | repo | after every phase's judge passed |

## 5. Changes to make
**U7.1.** One table per module: dependency, where pinned, current version (+ Modrinth version id), latest stable for that MC + loader,
changelog breaking changes, Origin mixins that touch it (grep `net.caffeinemc`, `net.irisshaders`, `jellysquid`, `mezz.jei`, ...),
recommendation (update / hold / reject) with reason. Never "latest" by date: filter by loader and game version. Alphas and betas are
rejected unless the current pin already is one (1.21.1 Sodium stays 0.6.13 unless Iris on that MC supports 0.8 stable). The orchestrator
approves rows under 04 ("dependency resolution" is in the may-decide list) and records them in Decisions.

**U7.2.** `boot-smoke.yml` `covers=true` input: for each module, also boots every version in its `versions` list. If a version cannot boot
in CI (missing assets, Java mismatch), the workflow prints the limitation and the report lists it; a real-install boot on Will's PC (S1)
is then the required check before that version ships.

**U7.3.** 03's 8 steps per update: identify, find, review, update (Modrinth version id pinned; SHA-512 in the launcher catalog),
`.\gradlew.bat build`, boot-smoke (module + covers), verify startup and mod initialisation (zero `Mixin apply .* failed`, Origin self-check
lines, the dependency's own init line), and **hold** on any failure (revert that row; the held list goes to ORIGIN_PROGRESS). dev-shots
`set=design` on the module after Sodium / Iris / JEI / ModMenu updates (menus, the video-settings mod rail).

**U7.5 Overhaul close.**
1. Every ORIGIN_PROGRESS row has a final status with evidence; "Done but untested" rows say which test is missing.
2. **Release** (S8): only when every phase's judge passed, build-check green on `main`, boot-smoke all modules green, launch-time green.
   `release-packager` checks `docs/RELEASING.md` and the commit titles that become release notes (player wording), then
   `git fetch origin --tags` / `git tag launcher-v1.0.<N> origin/main` / `git push origin launcher-v1.0.<N>`; watches
   `launcher-release.yml` (at most every 3 minutes) until published; confirms the assets (setup.exe, the zip, its `.sha256`). If the
   tag push fails, the report gives Will the PowerShell lines.
3. After the release: the real-install passes promised by earlier phases (launcher look and Play, real import, menus on real GPU) run on
   Will's PC under S1 and are recorded.
4. Final report to Will (04 Reports): completed phases, judge scores, requests done, verified features, remaining issues, blocked items,
   real benchmark results (real ones only), what he must test, decisions he can override. Plain English.
5. **F-02 Additional recommendations** (01 section 22): a short list in ORIGIN_PROGRESS, each with why and rough size; not built.

## 6. Must NOT change
Loader / MC / mapping pins (dependency versions only); 1.8.9's Forge + OptiFine M5 chain unless Phase 6 proved a change; the Mod Menu
look; anything a failed update touched (reverted).

## 7. Dependencies and parallel tracks
Track D. U7.1 any time after Phase 0. U7.3 per module after Phase 4's wave for that module merged, and after Phase 5's catalog changes
for launcher pins. U7.5 after everything else.

## 8. Verification
- Audit table complete for every live module (verifier spot-checks five rows against Modrinth).
- Per update: build, boot-smoke (module + covers) run ids, dev-shots where required; held list with reasons.
- `verify-auditor`: the `covers` boot fails when a covered version is broken on purpose (scratch branch, never merged).
- Release: workflow run id, release URL, asset list, `.sha256` matches the zip (verifier recomputes).
- code-reviewer (opus for pins and the release) + verifier per unit (S2).

## 9. Completion criteria
1. Every dependency audited; every update shipped with passing checks or held with a reason.
2. Covered versions booted in CI, or listed with the limitation and a real-install boot result.
3. **The judge passes Phase 7 (`docs/overhaul/JUDGE.md`, back-end weights).**
4. Phase branch merged to `main` and pushed; one release `launcher-v1.0.66`+ published (or Will's tag lines given); ORIGIN_PROGRESS and
   MEMORY updated; final report sent.

## 10. Needs Will
Nothing blocks the phase. If the tag push fails from the agent environment: the PowerShell lines in S8. "Needs testing" in the final
report: everything marked for his eyes across phases.
