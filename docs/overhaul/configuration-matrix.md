# Component configurations per module (Phase 5.2b)

What Play may pick for a mod profile (`Core/Components/ConfigurationCatalog.cs`). The rule: a pair is listed
only when the Sodium/Iris spike (`docs/overhaul/spike-sodium-iris.md`, 2026-10-25) booted Origin's unmodified jar
on it with zero mixin-apply failures. Sodium and Iris always move as a pair. A module with no proven alternate gets
its shipped set and nothing else; Play never offers more.

| Module | Shipped set (default) | Alternates, newest first | Proof | Notes |
|---|---|---|---|---|
| 1.21.1 | Sodium 0.6.13 + Iris 1.8.8, nested in originclient-1.21.1.jar | Sodium 0.6.5 + Iris 1.8.1 (top-level jars) | dev 13/13 mixins applied, prod top-level-over-nested 13/13, sigcheck 46/46 | Smart Terrain off (exact-pair gate). 0.6.0 + 1.8.0 left out: loses `OcclusionCuller.addNearbySections` (45/46). |
| 1.21.11 | Sodium 0.8.14 + Iris 1.10.8 (catalog jars) | Sodium 0.8.4 + Iris 1.10.5; Sodium 0.8.0 + Iris 1.10.0 | dev 10/10 mixins applied, sigcheck 32/32 | Dev runs only, no prod run. Smart Terrain off. 0.8.15-beta.1 not offered (beta). |
| every other live module | its shipped set | none | - | Not tested in the spike: nothing to offer. |

## What the proof is, and is not

- The proof is the spike's local runs (`run-dev.sh` / `run-prod.sh`, screenshots checked by eye) and the static
  signature check (`docs/overhaul/spike-tools/sigcheck.py`). **No CI run id exists for any alternate.** The spike's
  step 5 (a CI matrix job per pair) has not been built.
- 1.21.11 alternates were booted in the dev environment only; 1.21.1's alternate also booted in a production layout.
- Before adding a pair: boot it on that module in CI (dev-shots + boot-smoke), zero `Mixin apply ... failed` lines,
  then add its pins to `ManagedPins.Data.cs` (Modrinth SHA-512) and its row here and in the catalog.

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
