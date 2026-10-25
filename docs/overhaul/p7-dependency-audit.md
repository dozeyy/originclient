# P7 dependency audit

Phase 7 (ORIGIN_PROGRESS P7-01, P7-05). Audited 2026-10-25 against the Modrinth API (`/v2/project/<id>/version`, filtered by the exact Minecraft version and loader `fabric`; the newest `release` build is "latest stable", alphas and betas never count). Current versions are read from the files: `src/mods/versions/<mc>/gradle.properties` (Fabric API, JEI) and the launcher's `PerformanceModCatalog.Data.cs` (the Sodium / Iris / Lithium / ... stack players are given; the dev runtime loads the same files).

**Decision key.** *bumped* = changed on `claude/p7-deps` (from -> to). *unchanged* = already the newest release (or the newest build is a beta/alpha, which is rejected). *held* = a newer release exists and was deliberately not taken (reason given).

**Boot column.** Run ids are real GitHub Actions runs on `claude/p7-deps`. *boot-smoke companions* = `companions=true`: the launcher catalog's ImmediatelyFast + Mod Menu jars were loaded into the dev client and the run FAILS unless the loaded mod list names them at the catalog versions. *not re-run* = nothing changed for that module in this phase (it keeps its earlier boot; the full matrix is not repeated, per "one check per change").

**Not covered by this table:** 1.8.9 (Forge 11.15.1.2318 + OptiFine, a fixed chain: no Modrinth Fabric stack; unchanged by P7). Fabric API on players' machines is not a pin at all: `FabricApiInstaller` looks the newest *release* up on Modrinth at install time (and, since this fix, verifies its SHA-512). The gradle pin only decides what the dev build compiles against.

## 26.3

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.165.0+26.3 | 0.165.0+26.3 | bumped | 0.162.0 -> 0.165.0 (dev build pin; players get the live lookup) | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| JEI | gradle.properties (bundled jar-in-jar) | 31.14.0.72 | 31.14.0.72 | bumped | 31.12.0.65 (beta) -> 31.14.0.72 (stable, Modrinth id Zebwcvua); javap: GuiEventHandler + ClientInputHandler identical to the old pin, every hook the 26.3 JEI mixins bind to is intact | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc26.3-0.9.2-fabric | mc26.3-0.9.2-fabric | unchanged | already the newest release; newer 0.9.3-beta.1 is beta-only, so rejected | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc26.3-0.26.2-fabric | mc26.3-0.26.2-fabric | unchanged | already the newest release | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 9.0.0-fabric | 9.0.0-fabric | unchanged | already the newest release | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.3.2 | 0.3.2 | unchanged | already the newest release | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.11.7+26.3-fabric | 1.11.7+26.3-fabric | unchanged | already the newest release | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.17.1+26.3-fabric | 1.17.1+26.3-fabric | unchanged | already the newest release | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 21.0.0 | 21.0.0 | unchanged | already the newest release | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | boot-smoke 40183076637 + 40184982512, dev-shots world 40184984449 (green) |

## 26.2

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.161.0+26.2 | 0.161.0+26.2 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 30.42.0.236 | 30.42.0.236 | unchanged | already the newest release; newer 30.48.0.241 is beta, rejected | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc26.2-0.9.2-fabric | mc26.2-0.9.2-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc26.2-0.25.3-fabric | mc26.2-0.25.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 9.0.0-fabric | 9.0.0-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.3.1 | 0.3.1 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.11.4+26.2-fabric | 1.11.4+26.2-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.16.5+26.2-fabric | 1.16.5+26.2-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 20.0.3 | 20.0.3 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |

## 26.1

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.155.3+26.1.2 | 0.155.3+26.1.2 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 29.37.0.100 (beta) | 29.47.0.110 | held | 29.47.0.110 (release) FAILS the javap hook check: `UserInput` moved from `mezz.jei.gui.input` to `mezz.jei.common.input` (the mixin signatures stop matching) and `GuiEventHandler.renderCompactPotionIndicators` gained an `AbstractContainerScreen` parameter, so the toggle would silently stop applying. Held at 29.37.0.100 until the 26.1 mixins are re-targeted | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc26.1.1-0.8.9-fabric | mc26.1.1-0.8.9-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc26.1.2-0.24.7-fabric | mc26.1.2-0.24.7-fabric | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 9.0.0-fabric | 9.0.0-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.3.0 | 0.3.0 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.10.9+26.1-fabric | 1.11.4+26.1-fabric | held | Iris 1.11.4+26.1 requires Sodium 0.9.2, which is published for 26.1.2 only; on 26.1 / 26.1.1 Sodium stops at 0.8.9. 26.1.2 already has the 1.11.4 + 0.9.2 pair (covered-version rows) | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.15.3+26.1-fabric | 1.15.3+26.1-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 18.0.2 | 18.0.2 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |
| *covered version 26.1.1* | | | | | | |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc26.1.1-0.8.9-fabric | mc26.1.1-0.8.9-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc26.1.2-0.24.7-fabric | mc26.1.2-0.24.7-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 9.0.0-fabric | 9.0.0-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.3.0 | 0.3.0 | unchanged | already the newest release | not booted (no `covers` mode) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.10.9+26.1-fabric | 1.11.4+26.1-fabric | held | same as 26.1: Iris 1.11.4 needs Sodium 0.9.2, published for 26.1.2 only | not booted (no `covers` mode) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.15.3+26.1-fabric | 1.15.3+26.1-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 18.0.2 | 18.0.2 | unchanged | already the newest release | not booted (no `covers` mode) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not booted (no `covers` mode) |
| *covered version 26.1.2* | | | | | | |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc26.1.2-0.9.2-fabric | mc26.1.2-0.9.2-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc26.1.2-0.24.7-fabric | mc26.1.2-0.24.7-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 9.0.0-fabric | 9.0.0-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.3.0 | 0.3.0 | unchanged | already the newest release | not booted (no `covers` mode) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.11.4+26.1-fabric | 1.11.4+26.1-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.15.3+26.1-fabric | 1.15.3+26.1-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 18.0.2 | 18.0.2 | unchanged | already the newest release | not booted (no `covers` mode) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not booted (no `covers` mode) |

## 1.21.1

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.116.17+1.21.1 | 0.116.17+1.21.1 | bumped | 0.116.13 -> 0.116.17 (dev build pin; players get the live lookup) | boot-smoke 40182818181 (green) |
| JEI | gradle.properties (bundled jar-in-jar) | 19.27.0.340 (beta) | 19.64.0.455 | held | 19.64.0.455 drops the GUI render hooks Origin's JEI toggle mixins bind to (`onDrawScreenPost` / `onDrawForeground`), so the toggle would stop applying | boot-smoke 40182818181 (green) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.1-0.6.13-fabric | mc1.21.1-0.8.13-fabric | held | newest release mc1.21.1-0.8.13-fabric: Sodium 0.8.x is a shader-breaking pair on 1.21.1: the newest Iris there is 1.8.8 (stable, needs Sodium 0.6.x); the only newer Iris, 1.8.14-beta.1, is a beta. Stays on 0.6.13 (see video-settings-mod-rail) | boot-smoke 40182818181 (green) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.35+mc1.21 | 1.0.35+mc1.21 | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.1-0.15.4-fabric | mc1.21.1-0.15.4-fabric | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 7.0.3-fabric | 7.0.3-fabric | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.8 | 0.2.8 | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.8.8+1.21.1-fabric | 1.8.8+1.21.1-fabric | unchanged | already the newest release; newer 1.8.14-beta.1 is beta, rejected | boot-smoke 40182818181 (green) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.6.15+1.21.1-fabric | 1.6.15+1.21.1-fabric | bumped | 1.6.11 -> 1.6.15 | boot-smoke 40182818181 (green) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.25.1+mc1.21.1 | 5.25.1+mc1.21.1 | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 11.0.5 | 11.0.5 | bumped | 11.0.4 -> 11.0.5 | boot-smoke 40182818181 (green) |

## 1.21

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.102.0+1.21 | 0.102.0+1.21 | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| JEI | gradle.properties (bundled jar-in-jar) | 19.27.0.340 (beta) | 19.64.0.455 | held | 19.64.0.455 drops the GUI render hooks Origin's JEI toggle mixins bind to (`onDrawScreenPost` / `onDrawForeground`), so the toggle would stop applying (same JEI line as 1.21.1) | boot-smoke 40182818181 (green) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21-0.5.11-fabric | mc1.21.1-0.6.13-fabric | held | newest release mc1.21.1-0.6.13-fabric: hand-pinned era pair (catalog EXCEPTION 2026-07-12): Sodium 0.5.11 + Iris 1.7.3 is what resolves on exact 1.21; the 0.6.13 build is the 1.21.1 line, whose Iris 1.8.8 would be refused next to 0.5.11. Moving the pair needs its own gate run | boot-smoke 40182818181 (green) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.35+mc1.21 | 1.0.35+mc1.21 | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.1-0.15.2-fabric | mc1.21.1-0.15.2-fabric | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 7.0.0-fabric | 7.0.0-fabric | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.8 | 0.2.8 | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.7.3+1.21 | 1.8.8+1.21.1-fabric | held | see Sodium: Iris 1.8.8 needs Sodium 0.6.x, the catalog pins the 0.5.x pair (EXCEPTION 2026-07-12) | boot-smoke 40182818181 (green) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.6.15+1.21.1-fabric | 1.6.15+1.21.1-fabric | bumped | 1.6.11 -> 1.6.15 | boot-smoke 40182818181 (green) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.19.1+mc1.21 | 5.19.1+mc1.21 | unchanged | already the newest release | boot-smoke 40182818181 (green) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 11.0.5 | 11.0.5 | bumped | 11.0.4 -> 11.0.5 | boot-smoke 40182818181 (green) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.8.2 | 1.8.2 | unchanged | already the newest release | boot-smoke 40182818181 (green) |

## 1.21.4

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.119.4+1.21.4 | 0.119.4+1.21.4 | unchanged | already the newest release | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.4-0.6.13-fabric | mc1.21.4-0.6.13-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.4-0.15.3-fabric | mc1.21.4-0.15.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 7.1.3-fabric | 7.1.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.8 | 0.2.8 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.8.8+1.21.4-fabric | 1.8.8+1.21.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.8.7+1.21.4-fabric | 1.8.7+1.21.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.20.3+mc1.21.4 | 5.20.3+mc1.21.4 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 13.0.4 | 13.0.4 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |
| *covered version 1.21.2* | | | | | | |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.3-0.6.1-fabric | mc1.21.3-0.6.13-fabric | held | newest release mc1.21.3-0.6.13-fabric: newer release exists; covered versions have no CI boot (the `covers` mode is not built, B-31), so a bump could not be proven | not booted (no `covers` mode) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.3-0.14.6-fabric | mc1.21.3-0.14.6-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.8 | 0.2.8 | unchanged | already the newest release | not booted (no `covers` mode) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.8.0+1.21.3-fabric | 1.8.0+1.21.3-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.7.2+1.21.3-fabric | 1.7.2+1.21.3-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 12.0.1 | 12.0.1 | unchanged | already the newest release | not booted (no `covers` mode) |
| *covered version 1.21.3* | | | | | | |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.3-0.6.8-fabric | mc1.21.3-0.6.13-fabric | held | newest release mc1.21.3-0.6.13-fabric: newer release exists; covered versions have no CI boot (the `covers` mode is not built, B-31), so a bump could not be proven | not booted (no `covers` mode) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.3-0.14.6-fabric | mc1.21.3-0.14.6-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 7.1.0-hotfix-fabric | 7.1.0-hotfix-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.8 | 0.2.8 | unchanged | already the newest release | not booted (no `covers` mode) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.8.1+1.21.3-fabric | 1.8.1+1.21.3-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.7.2+1.21.3-fabric | 1.7.2+1.21.3-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 12.0.1 | 12.0.1 | unchanged | already the newest release | not booted (no `covers` mode) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not booted (no `covers` mode) |

## 1.21.6

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.128.2+1.21.6 | 0.128.2+1.21.6 | unchanged | already the newest release | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.8-0.7.3-fabric | mc1.21.8-0.7.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.6-0.17.0-fabric | mc1.21.6-0.17.0-fabric | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 8.0.4-fabric | 8.0.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.9 | 0.2.9 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.9.6+1.21.8-fabric | 1.9.6+1.21.8-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.10.3+1.21.6-fabric | 1.10.3+1.21.6-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 15.0.2 | 15.0.2 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.8.2 | 1.8.2 | unchanged | already the newest release | not re-run (unchanged) |
| *covered version 1.21.7* | | | | | | |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.8-0.7.3-fabric | mc1.21.8-0.7.3-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.7-0.18.0-fabric | mc1.21.7-0.18.0-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 8.0.4-fabric | 8.0.4-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.9 | 0.2.9 | unchanged | already the newest release | not booted (no `covers` mode) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.9.6+1.21.8-fabric | 1.9.6+1.21.8-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.11.2+1.21.7-fabric | 1.11.2+1.21.7-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 15.0.2 | 15.0.2 | unchanged | already the newest release | not booted (no `covers` mode) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.8.2 | 1.8.2 | unchanged | already the newest release | not booted (no `covers` mode) |

## 1.21.8

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.136.1+1.21.8 | 0.136.1+1.21.8 | unchanged | already the newest release | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.8-0.7.3-fabric | mc1.21.8-0.7.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.8-0.18.1-fabric | mc1.21.8-0.18.1-fabric | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 8.0.4-fabric | 8.0.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.9 | 0.2.9 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.9.6+1.21.8-fabric | 1.9.6+1.21.8-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.12.6+1.21.8-fabric | 1.12.6+1.21.8-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 15.0.2 | 15.0.2 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |

## 1.21.10

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.138.4+1.21.10 | 0.138.4+1.21.10 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 26.3.0.31 (beta) | none | unchanged | no release exists for this version; current pin is a beta | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.10-0.7.3-fabric | mc1.21.10-0.7.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.10-0.20.1-fabric | mc1.21.10-0.20.1-fabric | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 8.1.0-fabric | 8.1.0-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.10 | 0.2.10 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.9.7+1.21.10-fabric | 1.9.7+1.21.10-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.13.6+1.21.10-fabric | 1.13.6+1.21.10-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 16.0.1 | 16.0.1 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |

## 1.21.11

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.141.6+1.21.11 | 0.141.6+1.21.11 | bumped | 0.141.4 -> 0.141.6 (dev build pin; players get the live lookup) | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| JEI | gradle.properties (bundled jar-in-jar) | 27.17.0.50 (beta) | 27.48.0.106 | held | 27.48.0.106 drops the GUI render hooks Origin's JEI toggle mixins bind to (`drawForScreen` / `drawForContainerScreen`), so the toggle would stop applying | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.21.11-0.8.14-fabric | mc1.21.11-0.8.14-fabric | bumped | 0.8.12 -> 0.8.14 | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.21.11-0.21.4-fabric | mc1.21.11-0.21.4-fabric | unchanged | already the newest release | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 8.2.0-fabric | 8.2.0-fabric | unchanged | already the newest release | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.10 | 0.2.10 | unchanged | already the newest release | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.10.8+1.21.11-fabric | 1.10.8+1.21.11-fabric | bumped | 1.10.7 -> 1.10.8 | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.14.3+1.21.11-fabric | 1.14.3+1.21.11-fabric | unchanged | already the newest release | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 17.0.1 | 17.0.1 | bumped | 17.0.0 -> 17.0.1 | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | boot-smoke 40182818181 + dev-shots world 40182853615 (green) |

## 1.20

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.83.0+1.20 | 0.83.0+1.20 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 14.0.0.11 (beta) | none | unchanged | no release exists for this version; current pin is a beta | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.20-0.4.10 | mc1.20-0.4.10 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.18+mc1.20 | 1.0.18+mc1.20 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.20-0.11.2 | mc1.20-0.11.2 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 6.0.1 | 6.0.1 | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.3 | 0.2.3 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.6.4+1.20 | 1.6.11+1.20.1 | held | catalog EXCEPTION 2026-07-10: Iris 1.6.11 hard-requires Sodium 0.5.7+, and Sodium never published past 0.4.10 for plain 1.20; 1.6.4 is the Iris that resolves next to it | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.5.5+1.20.4-fabric | 1.5.5+1.20.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.0.2+mc1.20 | 5.0.2+mc1.20 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 7.0.1 | 7.0.1 | unchanged | already the newest release | not re-run (unchanged) |
| *covered version 1.20.1* | | | | | | |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.20.1-0.5.13-fabric | mc1.20.1-0.5.13-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.36+mc1.20.1 | 1.0.36+mc1.20.1 | unchanged | already the newest release | not booted (no `covers` mode) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.20.1-0.11.4-fabric | mc1.20.1-0.11.4-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 6.0.1 | 6.0.1 | unchanged | already the newest release | not booted (no `covers` mode) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.3 | 0.2.3 | unchanged | already the newest release | not booted (no `covers` mode) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.7.6+1.20.1 | 1.7.6+1.20.1 | unchanged | already the newest release | not booted (no `covers` mode) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.5.5+1.20.4-fabric | 1.5.5+1.20.4-fabric | unchanged | already the newest release | not booted (no `covers` mode) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.25.2+mc1.20.1 | 5.25.2+mc1.20.1 | unchanged | already the newest release | not booted (no `covers` mode) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 7.2.2 | 7.2.2 | unchanged | already the newest release | not booted (no `covers` mode) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not booted (no `covers` mode) |

## 1.20.2

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.91.6+1.20.2 | 0.91.6+1.20.2 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 16.0.0.2 (beta) | none | unchanged | no release exists for this version; current pin is a beta | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.20.2-0.5.5 | mc1.20.2-0.5.5 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.28+mc1.20.4 | 1.0.28+mc1.20.4 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.20.2-0.12.0 | mc1.20.2-0.12.0 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 6.0.1 | 6.0.1 | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.6 | 0.2.6 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.6.14+1.20.2 | 1.6.14+1.20.2 | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.5.5+1.20.4-fabric | 1.5.5+1.20.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.10.1+mc1.20.2 | 5.10.1+mc1.20.2 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 8.0.1 | 8.0.1 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |

## 1.20.4

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.97.3+1.20.4 | 0.97.3+1.20.4 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 17.3.1.5 (beta) | none | unchanged | no release exists for this version; current pin is a beta | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.20.4-0.5.8 | mc1.20.4-0.5.8 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.31+mc1.20.4 | 1.0.31+mc1.20.4 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.20.4-0.12.1 | mc1.20.4-0.12.1 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 6.0.3-fabric | 6.0.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.6 | 0.2.6 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.7.2+1.20.4 | 1.7.2+1.20.4 | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.5.5+1.20.4-fabric | 1.5.5+1.20.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.17.0+mc1.20.4 | 5.17.0+mc1.20.4 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 9.2.0 | 9.2.0 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |

## 1.19.4

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.87.2+1.19.4 | 0.87.2+1.19.4 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 13.1.0.19 (beta) | none | unchanged | no release exists for this version; current pin is a beta | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.19.4-0.4.10 | mc1.19.4-0.4.10 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.19+mc1.19.4 | 1.0.19+mc1.19.4 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.19.4-0.11.1 | mc1.19.4-0.11.1 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 5.2.0-fabric | 5.2.0-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.2 | 0.2.2 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.6.11+1.19.4 | 1.6.11+1.19.4 | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.2.11+1.19.4-fabric | 1.2.11+1.19.4-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.7.2+mc1.19.4 | 5.7.2+mc1.19.4 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 6.3.1 | 6.3.1 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.11.3 | 1.11.3 | unchanged | already the newest release | not re-run (unchanged) |

## 1.19.3

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.76.1+1.19.3 | 0.76.1+1.19.3 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 12.4.0.22 (beta) | none | unchanged | no release exists for this version; current pin is a beta | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.19.3-0.4.9 | mc1.19.3-0.4.9 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.14+mc1.19.3 | 1.0.14+mc1.19.3 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.19.3-0.11.1 | mc1.19.3-0.11.1 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 5.1.0-fabric | 5.1.0-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.1 | 0.2.1 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.5.2+1.19.3 | 1.5.2+1.19.3 | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.1.12+1.19.3 | 1.1.12+1.19.3 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 5.1.0 | 5.1.0 | unchanged | already the newest release | not re-run (unchanged) |
| EntityCulling | launcher catalog (+ module libs/ or maven pin) | 1.7.3 | 1.7.3 | unchanged | already the newest release | not re-run (unchanged) |

## 1.19.2

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.77.0+1.19.2 | 0.77.0+1.19.2 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 11.8.1.1035 (beta) | 11.68.0.1086 | held | 11.68.0.1086 is a release 57 builds ahead of the pin; not run through the gate (big jump, hook check needed) | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.19.2-0.4.4 | mc1.19.2-0.4.4 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.9+mc1.19.2 | 1.0.9+mc1.19.2 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.19.2-0.11.1 | mc1.19.2-0.11.1 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 5.0.3-fabric | 5.0.3-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.2.1 | 0.2.1 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.6.11+1.19.2 | 1.6.11+1.19.2 | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.4.0+1.19.2-fabric | 1.4.0+1.19.2-fabric | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.18.1+mc1.19.2 | 5.18.1+mc1.19.2 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 4.1.2 | 4.1.2 | unchanged | already the newest release; newer 4.2.0-beta.2 is beta, rejected | not re-run (unchanged) |

## 1.18.2

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.77.0+1.18.2 | 0.77.0+1.18.2 | unchanged | already the newest release | not re-run (unchanged) |
| JEI | gradle.properties (bundled jar-in-jar) | 10.2.1.1002 (beta) | none | unchanged | no release exists for this version; current pin is a beta | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.18.2-0.4.1 | mc1.18.2-0.4.1 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.7+mc1.18.2 | 1.0.7+mc1.18.2 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.18.2-0.10.3 | mc1.18.2-0.10.3 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 4.2.1-fabric | 4.2.1-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.1.9 | 0.1.9 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.6.11+1.18.2 | 1.6.11+1.18.2 | unchanged | already the newest release | not re-run (unchanged) |
| ImmediatelyFast | launcher catalog (+ module libs/ or maven pin) | 1.1.12+1.18.2 | 1.1.12+1.18.2 | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.18.0+mc1.18.2 | 5.18.0+mc1.18.2 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 3.2.5 | 3.2.5 | unchanged | already the newest release | not re-run (unchanged) |

## 1.17.1

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.46.1+1.17 | 0.46.1+1.17 | unchanged | already the newest release | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.17.1-0.3.4 | mc1.17.1-0.3.4 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.1+mc1.17.1 | 1.0.1+mc1.17.1 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.17.1-0.7.5 | mc1.17.1-0.7.5 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 3.1.0 (beta) | 3.0.3 | unchanged | current pin 3.1.0 is a beta, newer than the newest release (3.0.3); pre-existing hand pin, not downgraded | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.1.4 | 0.1.4 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.2.7+1.17.1 | 1.17.x-v1.2.7 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 2.0.17 | 2.0.17 | unchanged | already the newest release | not re-run (unchanged) |

## 1.16.5

| Dependency | Where pinned | Current | Latest stable | Decision | Reason | Boot |
|---|---|---|---|---|---|---|
| Fabric API | gradle.properties | 0.42.0+1.16 | 0.42.0+1.16 | unchanged | already the newest release | not re-run (unchanged) |
| Sodium | launcher catalog (+ module libs/ or maven pin) | mc1.16.5-0.2.0 | mc1.16.5-0.2.0 | unchanged | already the newest release | not re-run (unchanged) |
| Indium | launcher catalog (+ module libs/ or maven pin) | 1.0.0+mc1.16.5 | 1.0.0+mc1.16.5 | unchanged | already the newest release | not re-run (unchanged) |
| Lithium | launcher catalog (+ module libs/ or maven pin) | mc1.16.5-0.6.6 | mc1.16.5-0.6.6 | unchanged | already the newest release | not re-run (unchanged) |
| FerriteCore | launcher catalog (+ module libs/ or maven pin) | 2.1.1-fabric | 2.1.1-fabric | unchanged | already the newest release | not re-run (unchanged) |
| Krypton | launcher catalog (+ module libs/ or maven pin) | 0.1.2 | 0.1.2 | unchanged | already the newest release | not re-run (unchanged) |
| Iris | launcher catalog (+ module libs/ or maven pin) | 1.4.5+1.16.5 | 1.4.5+1.16.5 | unchanged | already the newest release | not re-run (unchanged) |
| ModernFix | launcher catalog (+ module libs/ or maven pin) | 5.18.0+mc1.16.5 | 5.18.0+mc1.16.5 | unchanged | already the newest release | not re-run (unchanged) |
| Mod Menu | launcher catalog (+ module libs/ or maven pin) | 1.16.23 | 1.16.23 | unchanged | already the newest release | not re-run (unchanged) |

## Held list

Newer releases that exist and were deliberately not taken. Each needs its own gate run (build, boot-smoke, dev-shots) before it can move.

| Version | Dependency | Held at | Newest release | Why |
|---|---|---|---|---|
| 1.21.11 | JEI | 27.17.0.50 | 27.48.0.106 | drops the GUI render hooks Origin's JEI toggle mixins bind to (`drawForScreen` / `drawForContainerScreen`), so the toggle would stop applying |
| 1.21.1, 1.21 | JEI | 19.27.0.340 | 19.64.0.455 | drops the GUI render hooks Origin's JEI toggle mixins bind to (`onDrawScreenPost` / `onDrawForeground`), so the toggle would stop applying |
| 1.21.1 | Sodium | 0.6.13 | 0.8.13 | Iris has no stable build for Sodium 0.8 on 1.21.1 (newest stable Iris 1.8.8 needs Sodium 0.6.x; 1.8.14-beta.1 is a beta) |
| 26.3 | Sodium | 0.9.2 | 0.9.2 (current) | the only newer Sodium is 0.9.3-beta.1: beta-only, rejected (Iris 1.11.7 pairs with 0.9.x) |
| 26.1 | JEI | 29.37.0.100 | 29.47.0.110 | javap hook check FAILED: `UserInput` moved to `mezz.jei.common.input`, `renderCompactPotionIndicators` gained a parameter; the 26.1 JEI mixins would stop applying silently |
| 1.19.2 | JEI | 11.8 | 11.68.0.1086 | release exists, 57 builds ahead; hook check + boot not done |
| 26.1, 26.1.1 | Iris | 1.10.9 | 1.11.4 | needs Sodium 0.9.2, published for 26.1.2 only |
| 1.21 | Sodium / Iris | 0.5.11 / 1.7.3 | 0.6.13 / 1.8.8 | era-pair exception in the catalog (Iris 1.8.8 is refused next to Sodium 0.5.x) |
| 1.20 | Iris | 1.6.4 | 1.6.11 | catalog exception: 1.6.11 needs Sodium 0.5.7+, none exists for plain 1.20 |
| 1.21.2, 1.21.3 | Sodium | 0.6.1 / 0.6.8 | 0.6.13 | covered versions have no CI boot, so a bump cannot be proven |


## Boot evidence

All runs are on branch `claude/p7-deps` (heads `1e89723fe` for the first four runs, `c4dc19308` for the two 26.3 JEI runs). The companions gate (`tools/ci/companion_mods.py verify_loaded`, called from `tools/boot-smoke/smoke.py`) fails a `companions=true` run unless the Fabric "Loading N mods" list names ImmediatelyFast and Mod Menu at the launcher catalog's versions.

| Run | Module | Loaded (from the game log) | Origin Sodium/Iris mixins applied | Mixin apply failures | Result |
|---|---|---|---|---|---|
| boot-smoke 40182818181 | 1.21.11 | immediatelyfast 1.14.3+1.21.11, modmenu 17.0.1, sodium 0.8.14, iris 1.10.8 | 3 | 0 | success, title screen reached in 40 s |
| boot-smoke 40182818181 | 1.21.1 | immediatelyfast 1.6.15+1.21.1, modmenu 11.0.5, sodium 0.6.13, iris 1.8.8 | 3 | 0 | success, 53 s |
| boot-smoke 40182818181 | 1.21 | immediatelyfast 1.6.15+1.21.1, modmenu 11.0.5, sodium 0.5.11, iris 1.7.3 | 2 | 0 | success, 38 s |
| boot-smoke 40183076637 | 26.3 | immediatelyfast 1.17.1+26.3, modmenu 21.0.0, sodium 0.9.2, iris 1.11.7 | 3 | 0 | success, 58 s |
| boot-smoke 40184982512 | 26.3 (JEI 31.14.0.72) | immediatelyfast 1.17.1+26.3, modmenu 21.0.0, jei 31.14.0.72, sodium 0.9.2, iris 1.11.7 | 3 | 0 | success, 52 s |
| dev-shots 40184984449 (`set=world`, 26.3) | 26.3 (JEI 31.14.0.72) | sodium 0.9.2+mc26.3, iris 1.11.7+mc26.3; `JeiGuiEventHandlerMixin` and `JeiClientInputHandlerMixin` both applied, no JEI self-check or foreign-JEI warning in run.log | 8 of 8 declared | 0 | world loaded, success |
| dev-shots 40182853615 (`set=world`, shaderpack on) | 1.21.11 | sodium 0.8.14+mc1.21.11, iris 1.10.8+mc1.21.11 | 10 of 10 declared | 0 | world loaded, perf-gate counts 10/10 |

Boot-smoke stops at the title screen, so its 3 / 3 / 2 / 3 counts are the mixins that apply before a world exists. The dev-shots world run is the only evidence for the in-world render mixins (all 10 applied on 1.21.11 with Sodium 0.8.14 + Iris 1.10.8).

Earlier boot-smoke runs 40180270272, 40180272944 and 40180280870 are superseded: they passed with `companions=true` while loading no companions (1.21.11 had no `-PextraMods` hook), so they proved nothing about Mod Menu 17.0.1.

## Not done

- `covers` boot mode (U7.2): not built. A covered version (1.21.2, 1.21.3, 1.21.7, 1.20.1, 26.1.1, 26.1.2) needs its own Minecraft, Fabric API, Sodium and Iris pins inside the module's Gradle build, which is a per-version Gradle override, not a workflow flag. Covered versions therefore keep no CI boot (ORIGIN_PROGRESS B-31 stays open).
- JEI newer than the pins: 26.3 and 26.1 were javap hook-checked (26.3 bumped, 26.1 held, see above); 1.19.2's newer JEI was not checked.
- `SimpleVoiceChatInstaller` downloads with the same unguarded pattern `FabricApiInstaller` had; not changed here (follow-up).
