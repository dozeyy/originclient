# Origin Launcher back-end audit (read-only) - origin/main de6b3eb6a, 2026-10-25

All paths are inside the detached worktree of origin/main (WT):
C:\Users\Will\AppData\Local\Temp\claude\C--Users-Will-Documents-Minecraft-Mods-Origin-Client\f0dcc439-1e1c-4429-aca1-1bb3e25913eb\scratchpad\audit-wt
Short form below: APP = src/OriginLauncher.App, TESTS = src/OriginLauncher.Tests.
"likely" = inferred, not proven by running. Nothing in the main repo was changed; no window was launched.

SUPERSEDES the earlier pass against the stale local main (480 commits behind). That pass wrongly said "Import: not implemented",
"no compat check", "crash window has only two actions", "Java: no logic", "perf mods unhashed". All of those are wrong on origin/main.

## 0. Tests
- `dotnet test src/OriginLauncher.Tests` in WT: Passed 1434, Failed 0, Skipped 0 (1 m 31 s, exit 0). Log: scratchpad/audit/dotnet-test-wt.log.
  (The 5 failures from the stale-main pass were the repo-root lookup failing when output was relocated; they pass in a real checkout.)
- Not run: OriginSetup.Tests, OriginLauncher.UiTests (CI runs them: build-check.yml:87,93,132).

## 1. Import
Status: Implemented, extensively unit-tested (Import has ~40 test files, TESTS/Import/*); never run in a real launcher window
(MEMORY.md 2026-10-19 "Not verified: no real import has run in a real launcher window").
Two separate paths exist:
A) Launcher import (the real feature), APP/Core/Import (engine, pure, roots injected) + APP/Core/Import/Launcher (glue) +
   APP/UI/Controls/Import* (drawer/page). Scan -> plan -> apply, ImportEngine.cs (DefaultSources :148-155, ScanAsync, PlanAsync :197-209,
   ApplyAsync). 
   - Sources read: Official launcher launcher_profiles.json (token-stream, only name/type/lastVersionId/lastUsed/gameDir; OfficialLauncherSource.cs),
     Prism + MultiMC (instance.cfg INI, mmc-pack.json; PrismSource.cs), Modrinth App (profile folders only; app.db never opened; ModrinthSource.cs),
     CurseForge app (minecraftinstance.json; CurseForgeSource.cs), PvP clients Lunar / Feather / Badlion / LabyMod (PvpClientSource.cs, Lunar layout
     confirmed on Will's PC read-only), and "Browse..." any folder (FolderSource.cs). Not covered: ATLauncher, GDLauncher, standalone modpack zips
     (.mrpack / CurseForge zip), Technic. Quilt jars are recognised and refused (ModSetResolver.cs:131-148).
   - Version/loader are the launcher's statement cross-checked with latest.log, options.txt DataVersion and mod consensus (Detection/SetupResolver.cs,
     EvidenceJudge.cs, FolderEvidence.cs); "certain" only if all agree, else the player picks.
   - What it copies (categories, ImportCategories.cs:19-29): mods, settings (options.txt, mod/video configs, OptiFine), servers.dat, resource packs,
     shader packs, worlds, screenshots; plus Mojang game files for the target version (GameFiles/GameFileContributor.cs: SHA-1 verified, copy not
     hardlink) and Distant Horizons / Voxy caches only on proven world/server identity (Caches/CacheContributor.cs).
   - Mods: landed in the version's ONE shared mods folder, never a profile (ImportEngine.cs:317-380; SharedModsLedger.cs). Resolver
     (Mods/ModSetResolver.cs:22-33, :46-120): not-a-Fabric-mod / 1.8.9 Forge rule -> listed not copied; Origin already includes it (by mod id, BuiltInModSet.cs)
     -> not copied; wrong MC version -> not copied; duplicate ids -> keep highest, list rest; KnownIncompatible (optifabric, optifine, DH <= 2.3.2 with Sodium >= 0.8;
     KnownIncompatible.cs:11-21) and unmet depends / breaks -> copied DISABLED with the reason; disabled in source -> arrives disabled.
     "Play with my own mods" is never flipped; the plan says when it is off (ImportEngine.cs:380-384).
   - Validation/safety: CredentialGuard denylist on every source read (CredentialGuard.cs, SourceFs.cs); links never followed; source never written; free-space
     check +10% (ImportEngine.cs Finish ~:411-424); refuses sources inside Origin's own folders and running games (BuildPlan :217-232); metadata reader caps
     (ModMetadataReader.cs:52-60, zip-quine budget).
   - Duplicates/overwrite: nothing is overwritten in place; existing file is moved to backup and journaled "replaced" (ImportTransaction.cs:7-17). Identical
     packs land once, same-name different packs become "<name> (imported)" (DestinationLedger.cs). A mod Origin's folder already has stays as is, except two
     swap cases (SharedModsLedger.cs:17-23).
   - Undo/crash safety: journal before every change; ImportRecovery rolls back an interrupted import at next start (ImportRecovery.cs:5-9); one apply per target
     folder at a time (ImportTargetLocks.cs); update and import exclude each other (UpdateRunState, ImportRunState).
   - Memory: ImportedSetupsJson in LauncherSettings.cs (a string so a bad value cannot reset all settings), keyed setup+account+target versions, so unchanged
     setups are not offered again; first-run prompt scan at most weekly (ImportPrompt.cs).
   - Bulk "bring everything" with one transaction per run (Bulk/BulkEngine.cs 687 lines, BulkComposer, ImportManifest).
B) Mods-page add: ModManager.Import (APP/Core/Mods/ModManager.cs:153-210), unchanged from the stale-main pass. Takes loose .jar files only, ".jar" suffix check,
   temp + rename, SILENTLY overwrites a same-name file, deletes a .disabled twin, refuses Origin-provisioned names in a profile (:177). It does NOT call
   ModSetResolver or ModMetadataReader: no mod-id/version/MC/loader check, no duplicate-by-id detection. So the strong import logic does not protect the
   everyday "drop a jar" path. Status: Partial.
claude/import-flow: see section 9.

## 2. Profiles
Status: Implemented; in-game pass pending (CLAUDE.md "Current state"); pure logic tested (TESTS/ModProfileTests.cs).
- Model: APP/Core/Profiles/ModProfile.cs: Id, Name, Version, ServerName/ServerAddress, plus Tint and Icon since 2026-10-19 (emblem only). Still no loader, Java,
  JVM args, RAM, config, notes. List in settings.json (LauncherSettings.ModProfiles, SelectedProfileId), written only by SettingsStore.Update.
- Storage: instances/<ver>/profiles/<id>/mods (OriginPaths.ProfileModsDir). Resolved at launch by ProfileRules.ForLaunch / Active (ProfileRules.cs) and
  LaunchModFolder.LaunchSet + Sync into <account>/mods-profile (LaunchModFolder.cs): Origin-provisioned jars come ONLY from the shared per-version folder,
  the profile contributes only non-Origin jars, loose jars in the shared folder are excluded. Fabric is pointed at it via -Dfabric.modsFolder.
- Different versions of Origin-managed mods per profile (Sodium, Iris, Fabric API, Lithium, ...)? NO (confirmed from code, unchanged). Managed names are
  dropped from profile sets and refused on import (ModManager.cs:177); the shared folder keeps one jar per family and the catalog pins one build per MC version
  (PerformanceModCatalog.Data.cs). IsManaged is filename-shape based (ModManager.cs:264-290, now also EntityCulling via IsCatalogOnlyPerfJar, and bare
  "immediatelyfast-").
- A profile is a mod SET, not an instance: worlds, options, servers, configs, resource/shader packs live in the per-account game folder shared by all
  profiles of that account+version (GameFolders). Imports deliberately create NO profile (ImportEngine.cs:317, commit bdcd7dc5a).
- Mods landed by an import are in the shared folder, therefore visible to every profile only if "loose shared jars" were included; they are NOT (LaunchSet
  excludes loose shared jars), so imported mods load only in the no-profile case (likely, from LaunchModFolder.cs:28-43; verify with Will).
  This is an important design tension for "import + profiles".

## 3. Compatibility checks
Status: Implemented for import only; NOT in the launch path.
- Reader: ModMetadataReader.cs (305 lines): fabric.mod.json, quilt.mod.json, META-INF/mods.toml, neoforge.mods.toml, mcmod.info; id, version, name, depends,
  breaks, provides, jar-in-jar nested mods, MC range; never throws; size/depth budget.
- Version ranges: VersionPredicate.cs (242 lines) + FabricVersion compare. Built-ins: BuiltInModSet.Build reads the bundled originclient jar's nested jars + the
  perf catalog (BuiltInModSet.cs:1-60), flags "incomplete" instead of guessing.
- Consumers: ImportEngine.PlanMods, FolderEvidence.cs:279 (version inference), SharedModsLedger.cs:56. Grep for ModSetResolver|ModMetadataReader|
  KnownIncompatible outside Core/Import returns nothing: no pre-launch scan, no Mods-page warnings, no check when a jar is added by ModManager.Import.
- Java: only `JavaFor(mcVersion)` (ImportEngine.cs:~432-438: 8/16/17/21/25) is used to test a mod's "java" dependency. Jars' own Java requirements are not read.
  Fabric-only: Forge/NeoForge jars are labelled "wrong loader"; 1.8.9 only checks mcmod.info mcversion.
- Gap for "one-click repair": no function maps a ModDecision (CopiedDisabled, reason) to an action on an already-installed mod; no fix suggestions (find the
  dependency, update, remove duplicate); no live-folder scan API. ModSetResolver.Resolve is pure and takes SourceMod[], so it can be reused on the live folders.

## 4. Crash diagnosis
Status: Implemented (rebuilt since the stale-main pass); classifier + bundle tested, mod-blame analyzer not directly tested.
- Files: APP/Core/Launch/CrashAnalyzer.cs (mod blame), CrashClassifier.cs (226, pure), CrashDiagnoser.cs (44, file reads), CrashModels.cs,
  CrashReportBundle.cs (114, secret scrub), UI/Windows/CrashReportWindow.xaml.cs, CrashFallback.cs (windows survive theme failure), UI/Pages/HomePage.xaml.cs
  :1002-1050 (boot watch), :1172-1245 (ShowCrashReportAsync, ApplyCrashActionAsync), :1404-1420 (post-boot exits).
- Signals: launch stdout/stderr log tail (512 KB, last 600 lines), newest crash-reports/crash-*.txt and hs_err_pid*.log (only when < 5 min old),
  exit code (native codes 0xC0000005/0xC0000409/0xC0000135/0xC0000374), configured RAM and PC RAM. Not read: logs/latest.log, GPU/driver names, Java version.
- Rules (CrashClassifier.cs:28-60, :64-78): order = Out of memory -> Java won't start (UnsupportedClassVersionError, Could not create the JVM, Unrecognized VM
  option) -> damaged files (corrupt jar/zip) -> graphics driver (GLFW/WGL/driver dll names) -> mod blame from CrashAnalyzer -> native crash -> unknown.
  Adding a cause = one row. Each result has ONE main action: RaiseMemory (MemoryBump +2 GB, capped by RamAdvice), DisableMod, OriginOnly, Relaunch (forces
  full verification), OpenLogs. CrashAnalyzer rules are as before (Fabric incompatible mods, duplicate id, Forge FML errors, mixin apply, "Suspected Mods",
  single stack-package match; no numeric confidence, empty culprits = unknown).
- Retry safety: CrashRetryGuard allows ONE automatic fix per account per crash chain for 30 min (CrashClassifier.cs:190-226); a spent action turns into "open logs".
  Newest launch action wins (HomePage.xaml.cs:1216).
- Intentional closes: exit 0 or StoppedByPlayer never show the window; a non-zero exit AFTER the game was up only shows with a fresh crash file or a recognised
  cause (CrashGate :168-176), so Task Manager kills no longer pop a window (post-boot). A boot-time non-zero exit still always shows and invalidates the install
  stamp (HomePage.xaml.cs:1040) - a kill during boot is still a likely false positive.
- Copy report: CrashReportBundle scrubs tokens, emails, Windows user name, launch args (tested: TESTS/CrashReportBundleTests.cs).
- Weak points: (1) DisableMod calls ModManager.SetEnabled(game.Version, jar, false) with NO profile id (HomePage.xaml.cs:1228-1231). If the blamed jar lives in a
  profile folder, SetEnabled fails ("no longer in the mods folder", ModManager.cs:118-135) and the fallback SaveExternalMods(false) turns off ALL own mods
  persistently (likely). (2) OriginOnly/fallback flips PlayWithExternalMods for good, never restored. (3) A jar a running game of another account holds open can't
  be renamed (IOException -> same fallback). (4) Likely: IsExternal is "name not managed" (CrashAnalyzer.cs ~:156-170, not re-read), so a user-dropped mod with a managed-looking filename is blamed as built-in.
  (5) Multi-mod Rule 1 results (victim + required mod) can't be disabled one at a time. (6) hs_err and GPU info are only pattern-matched, no hardware facts.
- Tests: TESTS/CrashClassifierTests.cs, CrashReportBundleTests.cs (classifier is linked). CrashAnalyzer / CrashDiagnoser are linked in the csproj but no test references
  them (grep returns 0). UI fallback: src/OriginLauncher.UiTests/CrashWindowFallbackTests.cs.

## 5. Java runtime
Status: Partial. Modern versions delegated to CmlLib/Mojang; 1.8.9 pinned Temurin; no user override or diagnostics.
- Modern: MLaunchOption.JavaPath is never set; CmlLib 4.0.6 picks Mojang's runtime component named by the version JSON and downloads it under the version's
  shared root runtime/ (likely 8/16/17/21/25 by era; VersionManager comments; not run here).
- 1.8.9 (Forge): LegacyJava.ResolveAsync (APP/Core/Runtime/TemurinJava8.cs:176-190) ensures a pinned Temurin 8u504 JRE under OriginPaths.Runtimes: download to temp,
  SHA-256 pinned (TemurinJava8.cs:19-24), unzip to temp, atomic rename, ".origin-verified" marker, one try per session, null = keep Mojang's jre-legacy
  (VersionManager.cs:~731-740). 5-minute cap, no resume.
- Memory: Auto by default (LauncherSettings.RamManual; RamPolicy.cs; HeapPlan.cs; RamAdvice.AutoMb: legacy 4 GB / 6 GB heavy, modern up to 8 GB); manual value
  untouched. -Xms == -Xmx. LaunchProfileBuilder.cs:9-61.
- GC: JvmArgPresets.AikarsFlags (G1, no AlwaysPreTouch), LegacySmallHeapFlags for legacy heaps <= 6 GB (4 MB regions, 25/35%), per-version boot args (1.21.1:
  -Dmodernfix.config.mixin.perf.dynamic_resources=true; JvmArgPresets.cs:60-78). One flag set for all Java levels 8-25, no per-Java gating (likely fine).
- User override: Settings -> JVM arguments per MC version (JvmArgsByVersion; "Replace Origin's tuned defaults"), warnings in JvmArgLine.cs (-Xmx/-Xms, second GC)
  but no block. JvmArgLine is not linked into the tests csproj. No Java path picker, no per-profile args, no "which Java am I on" display (CrashReportBundle.JavaVersionOf
  only parses a crash log).
- Crash side: JVM start failures are now classified (JAVA row) but the action is only "Open logs folder"; no automatic fix such as dropping custom args.

## 6. Downloads and integrity
Status: Mixed; better than the stale-main pass, still uneven.
- Mojang files via CmlLib: size always, SHA-1 on full pass (VersionManager.cs BuildLauncher ~:800+). InstallVerification: stamp trusted for 7 days max
  (InstallVerification.cs MaxAge), invalidated on any boot crash. Fast path reads version JSONs from disk (LocalFirstVersionLoader.cs), offline launch fallback
  (OfflineLaunch.cs), MojangMetaCache.cs. No launcher-side retry layer (CmlLib's own retry unknown).
- OptiFine: pinned SHA-1 per build, cache + installed copy healed by hash, mirror chain (optifine.net scrape, bmclapi2, archive.org for 1.12.2), non-fatal
  (OptiFineInstaller.cs:30-53, 72-80, 129-133). No per-source retry, no size check.
- Perf stack: now SHA-512 pinned per mod (PerfMod.cs:15-27, enforced by PerformanceModCatalogTests); PerfModInstaller promotes a download only if it matches
  (PerfModInstaller.cs:34-39) and throws InvalidDataException otherwise - a Modrinth outage or a bad hash FAILS the launch (no try/catch around InstallAsync, likely).
- Fabric API and Simple Voice Chat: still live-queried newest builds, no hash check (FabricApiInstaller.cs, SimpleVoiceChatInstaller.cs; grep for sha/hash finds none).
  Legacy Forge universal jar and the 1.8.9 perf mods (CurseForge CDN): no hash (LegacyForgeInstaller.cs, LegacyStackInstaller.cs).
- Temurin Java 8: SHA-256 + size pin (TemurinJava8.cs).
- Launcher self-update: UpdateService.cs:128-150, 195-217 still downloads the zip with no hash, no signature, no compare of received vs Content-Length, no retry;
  a truncated zip fails at ExtractToDirectory; then a robocopy script overwrites the install (security-relevant, risk floor). Installer (OriginSetup/Engine/Download.cs)
  is stronger (resume, 4 attempts, SHA-256).
- Config sanitiser SanitizeCorruptConfigs (VersionManager.cs:916) is a precedent for a small "repair" pass.

## 7. Test infrastructure
- TESTS = net8.0 xunit, linked-file csproj (Compile Include list, ~57 entries; Core/Import/** is linked wholesale). New pure code is silently untested unless added.
  Import kit: TESTS/Import/ImportTestKit.cs, TypicalSource.cs, BulkFixture.cs, EngineBKit.cs (temp-folder fakes; the engine takes every root as a parameter).
- Linked with direct tests: Import (all), Profiles, GameFolders, SharedMods, Settings Sync, CrashClassifier, CrashReportBundle, HeapPlan/RamPolicy (LegacyJavaHeapTests),
  PerformanceModCatalog/Preset, LaunchPrewarm, OfflineLaunch, PerfMod hashes, Temurin provisioning (via test download delegate).
- Linked without direct tests: CrashAnalyzer, CrashDiagnoser. Not linked (untestable here): ModManager is linked but only lightly referenced; VersionManager,
  InstallVerification, UpdateService, JvmArgLine, LocalFirstVersionLoader, OptiFine installer logic beyond hash helpers, all WPF.
- UI: src/OriginLauncher.UiTests (headless WPF, net8.0-windows): CrashWindowFallbackTests, HomeSceneRegression. CI: build-check.yml runs shared-sync check, check_consistency.py,
  both launcher test projects, setup tests, UiTests; launcher-shots.yml renders UI including the Import drawer tour; boot-smoke.yml / boot-time.yml / launch-time.yml / dev-shots.yml
  run the real mods headless in CI.
- Gap noted in MEMORY 2026-10 ("launcher's settings->launch path has no test").

## 8. Biggest risks for import + one-click compatibility repair + crash recovery
1. Imports and profiles don't meet: imports always land in the shared folder with no profile; profiles exclude loose shared jars (LaunchModFolder.cs:28-43). If Will wants
   "import into a profile", the engine's destination model (shared ledger, ImportTransaction) and the profile launch model both need design work first (likely).
2. The compat engine exists but only for import; the everyday add-a-jar path (ModManager.Import) and the launch path bypass it. Repair needs a live-folder scan, an action
   model (disable, remove duplicate, update, add missing dependency) and a place to show results. Nothing maps a reason string to an action today.
3. Shared mods folder is global per version: any repair that renames/deletes can hit a jar a running game of another account holds (Windows locks). All writes must stay
   add / delete / rename-swap (SharedMods.cs rule); failures must degrade, not escalate to "disable everything".
4. Crash DisableMod ignores profiles and falls back to a persistent global switch (HomePage.xaml.cs:1228-1236; PlayWithExternalMods never restored).
5. Name-shape heuristics decide "managed" (ModManager.cs:264-352): user jars with prefixes like modmenu-/indium-/krypton-/immediatelyfast- are treated as Origin's, hidden on
   the Mods page and de-duplicated by the family heal. Imported mods that match are dropped by the built-in check (by id) but the heuristic can still hide/delete user files.
6. Fabric API / Simple Voice Chat / Forge / legacy mods are downloaded unhashed; the perf installer now hard-fails the launch on a hash mismatch. A repair that re-downloads
   inherits both behaviors; the self-update has no integrity check at all.
7. Real-world proof is missing: no import has run in a real window; profiles awaiting Will's pass; crash window actions (RaiseMemory, DisableMod) only unit-tested at the classifier level.
8. CrashAnalyzer's regexes (duplicate id needs quoted id, Forge FML tables, mixin) have no fixtures from real logs in the repo; unknown-cause rate is unmeasured.
9. Java: no user choice or visibility; Temurin 8 only for 1.8.9; a mod needing a newer Java than Mojang's runtime can't be fixed from the launcher.
10. SettingsStore.Load resets all settings when it cannot parse the file (LauncherSettings.cs ImportedSetupsJson comment); any new persisted repair/crash state should be a string
    or its own file so one bad value can't wipe settings.

## 9. Branch claude/import-flow
- `git log origin/main..claude/import-flow --oneline` lists 4 commits (32e667b7a, 07f199914, 006ee07f3, bad736db3, branch tip dated 2026-10-19).
- All four are ALREADY on origin/main under different hashes (identical `git patch-id --stable`): 63e9d357f, 5589c76f8, 76ceb0a85, b6ac3d510; merged by 68e9a63ff
  "Merge claude/import-flow: new import drawer - pick the launcher, then the versions and what to bring". The branch merely wasn't rewritten after that.
- `git diff --stat origin/main...claude/import-flow`: 17 files, +2817/-443 (ImportCategories.cs, SetupInventory.cs, ImportFlowModel.cs, ImportPanel.xaml(.cs), ImportViewModels.cs,
  ImportShots.cs, launcher-shots.yml, ImportCategoryTests, ImportFlowModelTests, csproj). Content: per-version "what to bring" categories, a setup inventory (counts per kind, no file
  opened), the pure drawer flow model, the Lunar-style "pick launcher, then versions" drawer, and a CI check that fails when the drawer tour errors.
- A direct tip-to-tip diff is large (62 files, 7367 deletions) only because origin/main has moved on since (import grid page, import memory, manifest, shared-mods ledger).
  Nothing unique is left on the branch; it is stale and safe to delete after Will confirms (not done here).
