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

## Known issues / follow-ups (status 2026-10-27, release v1.0.73)

| Item | Status | Where |
|---|---|---|
| In-game loading screens differ between eras | Done: one loading look (Minecraft font, opaque backdrop, real logo, capsule bar with no 0% stub, Workbench Cancel) on 1.21.1, ported to every live version in this round | loading-screens recipe "Parity spec"; CI dev-shots per era group |
| Menu frame time; P6 multiplayer, baseline, cosmetics checks | Open: needs a real GPU (CI has no frame data for menus) | P4-15, P6-12 |
| Bench switches for picks 3-5 unmerged, never run | Done: merged and run in CI; picks 3-5 kept (pick 3 HUD -0.9 ms p50, heap -21%) | p6-measurements.md |
| Covered versions boot only to the title, newest Fabric API | Partly done: boot-smoke now enters a flat world (HUD + Mod Menu shots, Save and Quit); the pinned-API boot is still open | live-modules.json `inworld` |
| Compatibility repairs missing | Done: "Reset Java arguments" and "Install missing dependency" (Modrinth, SHA-512 checked) | Fix Compatibility |
| Old remote branches | Audited, nothing deleted: main-menu-button-styling-2i6rfu has extras worth saving; the other 8 are safe to delete (Will's call) | dev-shots, fervent-feynman-u4xm34, friends-icon-fix-before/before2, gracious-pascal-m8yp6n, minecraft-client-startup-31680x, origin-mod-menu-layout-1nrfhf, practical-lamport-2cn1ob, trusting-shannon-k4vzd2 |

## Recommendations (status 2026-10-27)

1. One loading look on every era: done (this round).
2. In-world step in boot-smoke: done (`smoke.py --inworld`, HUD and Mod Menu shots in a flat world).
3. JEI self-check as a signature check: done (owner + name + descriptor rows in CompatGates, checked against the jars in CI).
4. 1.8.9 pick A/B: done in CI, every kept pick measured faster; the real-GPU run (`tools/bench/bench-189.ps1`) is Will's.
5. Launcher cold start on a clean Windows install per release: open, not worth automating yet (needs a clean VM).
