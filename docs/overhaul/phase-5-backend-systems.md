# Phase 5 - Back-end systems (compatibility, per-profile versions, repair, crash V3, guided import)

Covers ORIGIN_PROGRESS P5-01 to P5-14, B-24, B-35, B-40. Standing rules: `docs/overhaul/README.md` (S2 risk floor: downloads,
hashes, the component store and anything that writes user data get an **opus** code-reviewer with the security checklist; S9 data).
Phase branch `claude/ovh-p5` (track B). Design basis: `audit/architecture.md` (picks A1, B1, C2; D1 is Phase 6) and
`audit/launcher-systems.md`.

## 1. Objectives (03's internal order: 5.1 -> 5.2 -> 5.3 -> 5.4 -> 5.5)
1. **5.1** One compatibility checker (`Core/Compat`) used by profile management, launch preparation, imported modpacks, dependency
   management and crash recovery. No caller keeps its own compatibility logic.
2. **5.2** Per-profile versions of Origin-managed components, and (04 section 6) **one user-facing profile with several compatible
   configurations behind it**: MC-version mod sets, loader deps, vetted Sodium/Iris combos, per-configuration Origin components,
   picked automatically at Play, switched safely, incompatible dependencies isolated (separate internal folders or linked configs where
   one mods folder can't hold them). Hidden from players. Never force incompatible mods into one runtime.
3. **5.3** Fix Compatibility: one click; preserves the original profile and imported data; creates a repaired copy where appropriate;
   explains plainly when there is no safe fix.
4. **5.4** Crash Report V3: automatic diagnosis and recovery; confirmed causes kept apart from guesses; safe auto-repairs only.
5. **5.5** Guided import: choose one source launcher -> Import Everything or Choose What -> confirm and import -> verify the result.
   Never claims unsupported data transferred. Importing creates Origin profiles (decided, 04).

## 2. Original requests
03 "PHASE 5" (5.1-5.5, "use the shared compatibility checker rather than duplicating"). 01 sections 11 (import redesign), 12 (one-click
modpack repair), 13 (crash diagnosis and recovery), 18 (cross-version architecture). 04 section 6 (Compatibility) verbatim intent:
"pick profile -> Play -> Origin picks the configuration; if repair needed, one-click fix. Preserve original profiles and imported data.
Never promise compatibility that doesn't exist." Decided (04): importing creates Origin profiles (reverses `bdcd7dc5a`); Fix
Compatibility may query Modrinth with mod ids and hashes only (no logs, accounts, tokens or private paths).

## 3. Inspect first
- `audit/architecture.md` sections 0 (riskiest assumptions + experiments E1, E2), 3 (A1 component store + `ComponentPins`), 4 (B1
  `Core/Compat` interfaces: `ModSnapshot`, `Platform`, `ModInfoCache`, `CompatEngine.Check`, `RepairPlanner.Plan`,
  `RepairExecutor.Apply`), 5 (C2 `CrashFacts`, rules, confidence levels, corpus + CI defect planting), 8 (sub-phase table), 9 (data,
  paths, perf budget, security), 10 (proof split), 11 (risks).
- `audit/launcher-systems.md` sections 1 (import engine: `ImportEngine.cs` :148-155, :197-209, :317-380; `ModSetResolver.cs:22-33,
  46-120`; `KnownIncompatible.cs:11-21`; `ModManager.cs:153-210` silent overwrite, no checks), 2 (profiles), 3 (compat checks today),
  4 (crash: `CrashAnalyzer`, `CrashDiagnoser`, DisableMod global fallback), 6 (downloads, `PerfModInstaller` hash path), 7 (tests).
- Phase 0 merged `ImportedModFit.cs`, `ClientCoreMods.cs` and the CrashAnalyzer fixture: build on them.
- `bdcd7dc5a` (2026-10-19 "imports never make a profile"): read what it removed before reversing it.

## 4. Agent units
| Unit | Agent | Scope | Order |
|---|---|---|---|
| U5.0 Experiments E1, E2 | verifier (dispatches boot-smoke) | E1: 1.21.11 boot-smoke with Sodium swapped to the previous minor + `-Dmixin.debug.export=true`; E2: 1.21.1 top-level vs nested Sodium, read the loader's mod list | first; results decide 5.2's Sodium/Iris matrix |
| U5.1 Core/Compat | architect (interfaces, if anything in section 4 of the audit needs changing) then desktop-app-dev | move `ModMetadataReader`, `VersionPredicate`, `BuiltInModSet`, `KnownIncompatible`, `ImportedModFit` into `Core/Compat/`; `ModInfoCache`; `CompatEngine.Check`; wire every caller | after Phase 0 (parallel with U5.0) |
| U5.2a Integrity | desktop-app-dev, **opus reviewer** | Fabric API (+ Simple Voice Chat) into the catalog with SHA-512 pins; legacy Forge + 1.8.9 stack pinned; hash-based "managed" with filename fallback (`ModManager.cs:264-290`) | after U5.1 |
| U5.2b Component store + configurations | desktop-app-dev, **opus reviewer** | `OriginPaths.ComponentStore`, `ComponentPins`, `LaunchSet` overload, per-profile launch folder `mods-profile-<id>`, `ConfigurationSelector`, per-configuration config overlay | after U5.2a + U5.0 |
| U5.3 Fix Compatibility | desktop-app-dev (planner/executor) + ui-builder (prompt) | `RepairPlanner`, `RepairExecutor` (only writer, journaled), pre-launch check with "Launch anyway", `ModManager.Import` check, Modrinth lookup | after U5.2b; UI after Phase 3 U3.0 |
| U5.4 Crash V3 | crash-investigator (rules + corpus) then desktop-app-dev + ui-builder | `CrashFacts`, ordered rules, confidence, 5-section window, corpus, CI defect-planting job, privacy scrub test | rules/corpus parallel with U5.2; auto-repair after U5.3 |
| U5.5 Guided import | desktop-app-dev + ui-builder | 4-step flow over `ImportEngine`; profile per imported setup; final report | after U5.1 (+ U5.2b for profiles); UI after Phase 3 U3.0 |
| U5.6 Regression | verifier | whole phase | last |
Parallel writers: U5.4's rules/corpus and U5.2 work in separate worktrees (disjoint folders `Core/Crash` vs `Core/Compat`, `Core/Mods`).

## 5. Changes to make
**U5.1.** Exactly the B1 shape in `audit/architecture.md` section 4. Pure parts unit-tested; the move links `Core/Compat/**` wholesale in
the test csproj; **test count must not drop** (Phase 4's count is the floor). Callers: import planning, `ModManager.Import` (stop the
silent overwrite: same id -> ask; wrong MC/loader -> refuse with the reason), pre-launch (before `LaunchModFolder.Sync`), crash repair.
Cached verdict keyed by the launch-set fingerprint; measure cold and warm `Check` on a 150-jar set and record the numbers.

**U5.2.** A1 as written (immutable write-once store `<Root>/components/<family>/<sha512-12>/<file>`; pins as one string field written only
through `SettingsStore.Update`; shared `mods/` never touched by profiles or repairs; "No profile" unchanged). Plus 04 section 6:
- A **configuration** = (MC version, loader + version, Origin build, Sodium/Iris pair, Fabric API, other managed components). The
  catalog lists vetted configurations per module; each enters the list only after a green boot-smoke + mixin-apply check against that
  module's Origin build (data-only after the first). U5.0 decides whether any Sodium/Iris alternates are viable; if E1 fails, the matrix
  is Fabric API only on that module and the doc says so (never promised).
- `ConfigurationSelector.Pick(profile mods, platform)` runs at Play: the newest vetted configuration whose `CompatEngine.Check` has no
  blocking issue; ties prefer the one already materialised (no copy). No UI to choose it (hidden); the launch log names the pick.
- Isolation: each configuration materialises into its own launch folder; Sodium/Iris config files get a per-configuration overlay
  (copied in before launch, copied back after exit) so a newer schema never rewrites an older one's config. Switching while a game of that
  profile runs is refused.

**U5.3.** `RepairPlanner` / `RepairExecutor` per audit section 4. Steps: create a repaired copy of the profile (original untouched),
pin a component, add a trusted dependency (Origin catalog, or a Modrinth version whose published SHA-512 matches the download; the
query sends mod ids and hashes only: the opus reviewer checks the request body), exclude a jar with a reason. Unfixable issues get plain
"why there is no fix" text. Pre-launch: blocking issues show the Fix Compatibility prompt with "Launch anyway"; non-blocking launch.

**U5.4.** C2 per audit section 5: crash vs normal close first; facts; ordered rules; confidence confirmed / strong / possible / unknown;
auto-fix only at confirmed or strong, through the U5.3 executor; remove the DisableMod / PlayWithExternalMods global fallback; RaiseMemory
stays (reversible). Corpus `src/OriginLauncher.Tests/CrashCorpus/<case>/` with `expected.json`; every fixture scrubbed; a test fails on
user names, tokens, emails or private paths. CI job plants each defect (duplicate id, missing dep, wrong-MC jar, missing mixin target,
`-Xmx64m` OOM, bad JVM option, truncated jar) via boot-smoke and checks the diagnosis. Window: What happened / Likely cause (with the
confidence words) / Recommended fix / Fix button (only when safe) / Technical details. Re-uses the Phase 3 crash-window skin.

**U5.5.** Steps per 03 5.5 over the existing engine (sources, categories, credential guard, journal, recovery all kept). Reverse
`bdcd7dc5a`: each imported setup becomes an Origin profile (its mods in `profiles/<id>/mods`, run through `CompatEngine.Check`; a setup
that cannot run gets a repaired copy offer via U5.3). Step 4 "verify" re-reads what landed and shows succeeded / skipped / unsupported /
failed with reasons. Unsupported data (accounts, app databases, Quilt jars, ATLauncher/GDLauncher/Technic, modpack zips) is named as
not imported, never implied. Settings porting (B-24) checked against a real Lunar 1.8 and a Prism 1.20 setup.

## 6. Must NOT change
- The shared `instances/<v>/mods/` rule (add / delete / rename-swap only); "No profile" launches exactly as today; one game folder per
  account; newest launch action per account cancels in-flight work; settings writes only via `SettingsStore.Update`; new state is a string
  field or its own file, never a typed object whose parse failure resets settings; every new folder is an `OriginPaths` member.
- Import safety: credential guard, links never followed, source never written, journal + recovery, free-space check.
- No uploads except the Modrinth lookup (ids + hashes); crash data stays local; "Copy report" keeps the scrub.
- Startup: zero added startup work; pre-launch adds only stats when the launch set is unchanged.
- The cosmetics service and its deployment (S9).

## 7. Dependencies and parallel tracks
Track B. Starts after Phase 0 (needs U0.2's `ImportedModFit` and U0.3's verified updater: no new downloader before the self-update hash
check is on main). 5.1 -> 5.2 -> 5.3 in order; 5.4's rules and corpus run alongside 5.2; 5.4's auto-repair after 5.3; 5.5 after 5.1 (and
5.2b for profiles). UI units (5.3 prompt, 5.4 window, 5.5 screens) start after Phase 3 U3.0 merges. If Phase 3 U3.3 (Play installs)
and U5.2b both touch the launch path, U3.3 merges first and Phase 5 rebases. Phase reports stay in order: Phase 5's report goes out
after Phase 4's.

## 8. Verification
- `dotnet test` all three projects; count never drops; new tests: CompatEngine (each issue kind positive + negative), RepairPlanner
  (original untouched, repaired copy correct), executor journal + crash-recovery, store write-once + hash re-fetch, `ComponentPins` parse
  safety, `ConfigurationSelector` (picks, ties, refusal while running), config overlay round-trip, crash corpus Theory + scrub test +
  unknown-rate print, import report (unsupported items listed), Modrinth request body contains only ids/hashes (recorded request).
- Fabric Loader differential test: for each planted mod set, the engine's verdict equals what a headless boot reports (boot-smoke job).
- Each vetted configuration: boot-smoke + mixin-apply grep, recorded with run id.
- CI defect-planting crash job green; each planted defect diagnosed with the expected cause and confidence.
- launcher-shots of the Fix Compatibility prompt, crash window (each confidence level), import steps 1-4 and report.
- Real install (S1): a real import from Will's own launchers (Lunar, Prism, any present) into his installed Origin after the release,
  read-only on the sources, backup of his Origin data first; never deletes or changes accounts.
- code-reviewer (opus for 5.2, 5.3 downloads, 5.4 privacy) + verifier per unit (S2).

## 9. Completion criteria
1. One `CompatEngine` serves all five callers (grep shows no other compatibility logic outside `Core/Compat`).
2. A profile with two vetted configurations launches the right one automatically; incompatible deps never share a runtime; the Sodium/Iris
   matrix is exactly what E1/E2 and boot-smoke proved, documented.
3. Fix Compatibility repairs a broken imported profile into a working copy with one click; the original is byte-identical afterwards.
4. Crash V3 diagnoses every corpus and planted case as expected; auto-fix only at confirmed/strong; no global DisableMod fallback.
5. Guided import: four steps, profiles created, honest report.
6. **The judge passes Phase 5 (`docs/overhaul/JUDGE.md`, back-end weights: 95/100, every critical requirement passing, no auto-reject).**
7. Phase branch merged to `main` and pushed; ORIGIN_PROGRESS, MEMORY updated; short report. No release here (S8).

## 10. Needs Will
Nothing blocks the phase. Decided (04): imports create profiles; Modrinth lookup with ids and hashes only. Decided by the orchestrator
(Will can override): picks A1, B1, C2 from the architecture audit. "Needs testing": a real import of his own setups and a real crash
recovery on his PC; whether the Fix Compatibility and "no fix" wording reads clearly.
