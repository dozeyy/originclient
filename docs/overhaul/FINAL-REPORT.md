# Overhaul final report (2026-10-25)

Shipped in `launcher-v1.0.66`. Item-by-item status and evidence: `ORIGIN_PROGRESS.md`.

## Phases

| Phase | What landed | Judge (pass = 95) |
|---|---|---|
| 0 Cleanup | repo, branches, docs tidied | 95 |
| 1 Quick fixes | the playtest list | 96 |
| 2 Design system | Workbench tokens, real logo everywhere, no ring lookalikes | 95-98 |
| 3 Launcher redesign | top bar, numerals, two-step version picker, Play installs what is missing, Mods/Profiles/Settings/dialogs/import/crash/sign-in/friends/installer in Workbench | 80 |
| 4 Main menu | 1.21.1 title and loading look on all 20 live versions (1.8.9 included), Mod Menu identical everywhere | 74 |
| 5 Backend | one compatibility checker, pinned Fabric API/Voice Chat, per-profile Sodium+Iris pairs, Fix Compatibility, Crash V3, guided import | 89 (UI 84) |
| 6 1.8.9 speed | production-like bench in CI, presets, HUD/cosmetics/blur/minimap/particle work, Java/GC A/B (shipped settings win) | 81 |
| 7 Mod updates | dependency audit, covered versions boot-tested, 1.21.2 Sodium fix | 89 |

Phases 3-7 were judged once. Will cut re-judging for speed (2026-10-25); the launcher findings were fixed
after the judge (update badge, ASCII text, toast position, import layout), the rest are follow-ups below.

## Verified (by CI or tests, not by playing)

- Launcher: 2396 tests, installer 63; screenshots at 3 sizes x 3 DPI with nothing clipped.
- Every live version boots to the title screen with zero mixin failures; dev-shots per version match 1.21.1.
- Crash V3: 7/7 planted crashes named correctly; 3.1% "unknown" on the case corpus.
- Older Sodium+Iris pairs on 1.21.1 and 1.21.11 reach the title screen.
- 1.8.9: no Java/GC variant beats the shipped preset; OptiFine keys checked against the real jar (71/71).

## Not verified (Will checks after the release)

- The look of every version in a real game, on a real GPU.
- Play installing a version on families other than 1.21.1.
- A real import from another launcher; Fix Compatibility clicked through; crash recovery after a real crash.
- 1.8.9 bench on the real PC (`tools/bench/bench-189.ps1`).
- Launcher cold start (< 3 s target) and frame time; sign-in card size on a real account.

## Known issues / follow-ups

- In-game loading screens differ between eras: Inter text on 1.20+ but Minecraft font before; Workbench Cancel only on
  1.21.1; backdrop opaque on some versions, see-through on others; the pre-1.20 world-loading bar shows a stub at 0%.
- Menu frame time not measured; P6 multiplayer, baseline and cosmetics (+18.6%) checks not run.
- Bench switches for picks 3-5 are on `claude/p6-judge-fix`, not merged and never run.
- Covered versions boot only to the title, with newest Fabric API instead of the pinned one.
- Compatibility repairs still missing: "reset Java arguments", "install a missing dependency".
- Old remote branches to look over: dev-shots, fervent-feynman-u4xm34, friends-icon-fix-before/before2,
  gracious-pascal-m8yp6n, main-menu-button-styling-2i6rfu, minecraft-client-startup-31680x,
  origin-mod-menu-layout-1nrfhf, practical-lamport-2cn1ob, trusting-shannon-k4vzd2.

## Recommendations (not built)

1. Make the in-game loading screens one look on every era (the four differences above) in one port round.
2. Add an in-world step to boot-smoke so menus and HUD are proven past the title screen.
3. Turn the JEI self-check into a signature check (it only checks method names and misses moved classes).
4. Run the held 1.8.9 pick A/B once game runs are allowed; revert any pick that does not help.
5. Measure launcher cold start on a clean Windows install once per release.
