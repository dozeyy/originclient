# Component configurations per module (Phase 5.2b)

What Play may pick for a mod profile (`Core/Components/ConfigurationCatalog.cs`). The rule: a pair is listed
only when the Sodium/Iris spike (`docs/overhaul/spike-sodium-iris.md`, 2026-10-25) booted Origin's unmodified jar
on it with zero mixin-apply failures. Sodium and Iris always move as a pair. A module with no proven alternate gets
its shipped set and nothing else; Play never offers more.

| Module | Shipped set (default) | Alternates, newest first | Proof | Notes |
|---|---|---|---|---|
| 1.21.1 | Sodium 0.6.13 + Iris 1.8.8, nested in originclient-1.21.1.jar | Sodium 0.6.5 + Iris 1.8.1 (top-level jars) | CI: boot-smoke 40252040490 job 121345919262; spike: dev 13/13 mixins applied, prod top-level-over-nested 13/13, sigcheck 46/46 | Smart Terrain off (exact-pair gate). 0.6.0 + 1.8.0 left out: loses `OcclusionCuller.addNearbySections` (45/46). |
| 1.21.11 | Sodium 0.8.14 + Iris 1.10.8 (catalog jars) | Sodium 0.8.4 + Iris 1.10.5; Sodium 0.8.0 + Iris 1.10.0 | CI: boot-smoke 40252040490 jobs 121345919180 (0.8.4) and 121345919166 (0.8.0); spike: dev 10/10 mixins applied, sigcheck 32/32 | Smart Terrain off. 0.8.15-beta.1 not offered (beta). |
| every other live module | its shipped set | none | - | Not tested in the spike: nothing to offer. |

## What the proof is, and is not

- CI proof (2026-10-25): boot-smoke run 40252040490, input `pairs` (branch `claude/p5-pair-proof`:
  `tools/boot-smoke/covers.py --pair`, `smoke.py --expect-perf`). Each pair booted production-style: the module's
  BUILT jar + that version's catalog mods with the catalog Sodium + Iris replaced by the pair (the launcher's
  LaunchSwap rule) + Fabric API. All three reached the title screen with zero `Mixin apply ... failed` lines and the
  mod list named exactly the pair (on 1.21.1 the top-level pair won over the one nested in Origin's jar). Boot covers
  the title screen only: 3 Origin Sodium/Iris mixins applied there; the rest apply in a world.
- Before that, the spike's local runs (`run-dev.sh` / `run-prod.sh`) and the static signature check
  (`docs/overhaul/spike-tools/sigcheck.py`).
- Before adding a pair: add its pins to `ManagedPins.Data.cs` (Modrinth SHA-512), boot it with boot-smoke
  `pairs=<mc>:<pairId>` (zero `Mixin apply ... failed`, pair loaded), then its row here and in the catalog.

## How Play uses it

1. Only a launch with a mod profile, on a module above that has alternates, goes through the selector; every other
   launch is checked and started exactly as before.
2. The shipped set is checked first (the same pre-launch check as before). If it starts, nothing else happens.
3. Otherwise the alternates are checked newest first (before download, from their known ids and versions); a tie
   prefers one already in the store. The pick is fetched into `components/<family>/<sha512-12>/<file>` (SHA-512
   checked), then checked again with the real files. The log `logs/mod-configuration-<version>.log` names the pick.
4. Each alternate launches from its own folder (`mods-profile-<id>` in the account's game folder); the shared
   `instances/<version>/mods` folder is never changed by this.
5. Sodium/Iris settings files (`sodium-options.json`, `sodium-mixins.properties`, `sodium-fingerprint.json`,
   `iris.properties`) are swapped in before the game starts and the shipped set's are put back when it exits
   (`instances/<version>/config-overlays/<account>/`).
6. While a game of the profile runs on one configuration, Play refuses to start it on another.

## Measurements: the compatibility check on a 150-jar set (U5.1)

What Play pays for `LaunchPreflight.Evaluate` (which runs `CompatEngine.Check`) on a launch set of 150 small Fabric
jars (50 of them declare a dependency on `mod000`, which is in the set). Measured 2026-10-25 on Will's PC (Windows 11), three separate runs
of `dotnet test src/OriginLauncher.Tests --filter "FullyQualifiedName~ColdAndWarmCheckOfA150JarSet" --logger
"console;verbosity=detailed"` (Debug test build; each run is a fresh process, so the cold number also pays .NET's
first-call JIT). The test prints the line; it fails if warm is 25 ms or more, cold 400 ms or more, or warm is not
faster than cold.

| Run | Cold (every jar opened and read) | Re-check (jar metadata cached, verdict not) | Warm (verdict cached by the set's fingerprint) |
|---|---|---|---|
| 1 | 78.8 ms | 17.9 ms | 0.35 ms |
| 2 | 131.0 ms | 35.3 ms | 0.35 ms |
| 3 | 72.5 ms | 16.3 ms | 0.24 ms |

- Cold = the first Play after the jars changed. Re-check = the set changed but the jars were read before (a jar is
  re-read only when its size or time stamp changes). Warm = Play on an unchanged set: a folder listing and one hash.
- Not measured here: a set of large real jars (the real Fabric API + Origin jars have their own test,
  `ColdCheckOfTheRealFabricApiAndOriginJarsIsFast`, which runs only on a machine that has built them).
