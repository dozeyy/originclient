# ORIGIN_PROGRESS - the Origin overhaul master checklist

The one tracking file for the 2026-10-25 overhaul (03: "a single ORIGIN_PROGRESS.md"). Every requirement from Will's four messages
(`docs/overhaul/requests/01`-`04`) and the open backlog has one row. Updated at every phase close by the closing chore-runner.

## How to resume
- **Docs:** `docs/overhaul/README.md` (how a phase runs, standing rules S1-S12, parallel tracks), `docs/overhaul/JUDGE.md` (the
  95/100 judge), `docs/overhaul/phase-0-cleanup.md` ... `phase-7-mod-updates.md` (one execution prompt per phase),
  `docs/overhaul/requests/` (Will's words), `docs/overhaul/audit/` (read-only audits of `de6b3eb6a`), `docs/overhaul/design/` (Workbench).
- **Where the docs live:** on main (merged 2026-10-25).
- **Current phase:** all phases implemented; judges + release in progress (2026-10-25).
- **Next step:** judge passes for phases 3-7, then the one launcher release (orchestrator tags launcher-v1.0.66+); items marked "Needs Will" are his to check.
- **Statuses:** Done and tested / Done but untested / In progress / Not started / Blocked / Needs my decision.
  "Done and tested" needs evidence (commit, CI run id, test name, shot path). A judge pass is an estimate, not Will's approval.

## Decisions
| Date | Decision | Source |
|---|---|---|
| 2026-10-25 | Design direction: **Workbench (concept A), refined, not reinvented.** | 00, 02 |
| 2026-10-25 | In-game main menu and other non-Mod-Menu surfaces: "match launcher softness" (rounded, softer); replaces the pixel-grid square-button rule there. Mod Menu unchanged. | 00 |
| 2026-10-25 | Launcher colour: keep soft accents (icon tiles, status dots, badges, progress, selected rings); never big fills; the Home numeral stays neutral white/grey. | 00 |
| 2026-10-25 | The 2026-10-20 playtest backlog is folded into the overhaul (rows B-01-B-25). | 00 |
| 2026-10-25 | 1.8.9 real-GPU benchmarks: "I run one command"; since 04 agents may also run it on Will's PC. | 00, 04 |
| 2026-10-25 | Plan and phase order approved (03 is the official order). | 00, 03 |
| 2026-10-25 | **Logo decided:** official logo = `textures/ui/origin_logo.png` (same art as `Assets/origin.ico`); wordmark = `src/OriginLauncher.App/Assets/Brand/wordmark.png`. Every recreation is replaced (list in 04 / Phase 2). | 04 |
| 2026-10-25 | Only the AI assistant Worker (`origin-assistant-api`) is retired; the cosmetics service stays exactly as it is. Phase 1 removes every AI-assistant reference. | 04 |
| 2026-10-25 | Phase 5: **importing creates Origin profiles** (reverses `bdcd7dc5a`). | 04 |
| 2026-10-25 | Phase 5: **Fix Compatibility may look up missing dependencies on Modrinth**, sending mod ids and hashes only. | 04 |
| 2026-10-25 | Phase 5 scope adds 04 section 6: one profile, several compatible configurations, automatic pick, isolated incompatible deps, one-click repair. | 04 |
| 2026-10-25 | Testing on Will's real install is allowed (never delete worlds/saves/profiles/accounts, back up first, never change credentials). | 04 |
| 2026-10-25 | One launcher release (`launcher-v1.0.66`+) once tests, CI boots and the judge all pass; tagged by the orchestrator. | 04 |
| 2026-10-25 | Each phase passes the independent judge (`docs/overhaul/JUDGE.md`, 95/100) before it closes. | 04 |
| 2026-10-25 | Orchestrator, Will can override: PR #34 held and folded into Phase 3 Settings; old stash kept; main checkout catch-up runs with a backup first. | Phase 0 doc |
| 2026-10-25 | Orchestrator, Will can override: Phase 2's five preview picks (Store placement, account pill, card numbers, menu icons, backgrounds) are made by design-director + design-reviewer against 02's wording and recorded here when made. | Phase 2 doc |
| 2026-10-25 | Orchestrator, Will can override: architecture picks A1 (component store + pins), B1 (`Core/Compat`), C2 (crash facts + rules), D1 (launcher writes 1.8.9 presets). | `audit/architecture.md` |
| 2026-10-25 | Orchestrator, Will can override: 1.8.9 presets are an explicit, previewed, undoable bundle (exception to "one control, one job"), include Origin's own toggles, inventory blur off in Competitive. | Phase 6 doc |
| 2026-10-25 | Orchestrator, Will can override: no separate third-party installed-mods list in game (Terraformers Mod Menu already provides it). | 01 section 16 |

## Baseline (filled by Phase 0 U0.8)
| Item | Value | Evidence |
|---|---|---|
| origin/main at audit | `de6b3eb6a` | audits |
| Latest release | `launcher-v1.0.65` (`d2282be60`) | `git tag` |
| Launcher tests | 1434 passed, 0 failed (`de6b3eb6a`) | audit `launcher-systems.md` section 0 |
| build-check run / three test-project counts | launcher 1523 passed, setup 61 passed | Phase 0 close, 2026-10-25 |
| launcher-shots `p0-baseline` run | run 40178166465, success, 263 shots (main 924a5809d) | CI |
| dev-shots `p0-baseline` run (Mod Menu reference shots) | run 40178168462, success, 20 modules / 2166 shots; Mod Menu refs = menu_*, menu_list_*, mod_settings_* per module | CI |
| boot-smoke all modules | run 40178170380, 20/20 passed | CI |
| launch-time 1.8.9, 1.16.5, 26.3 | run 40178172247 (1 round, single samples), Play->java start cold/warm ms: 1.21.1 3005/101, 1.16.5 3775/122, 1.8.9 3896/281, 26.3 3223/105 | CI |
| Worktrees / local branches / remote branches before cleanup | 148 / 249 / 102 (2026-10-25) | Phase 0 inventory |
| Main at Phase 0 close | `3892cf1f6` | git log |
| Worktrees after cleanup | 28 (was 148) | Phase 0 cleanup |
| Latest tag | `launcher-v1.0.65` | `git tag` |

## Requirements
Source codes: 01 = master overhaul (section), 02 = Workbench refinement (section), 03 = work order, 04 = overnight authorization.

### Global and process
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| G01 | Main chat is the orchestrator; specialised agents do the work; no two agents edit the same files at once | 01 s2, 03 rules 3-4, 04 Agents | all | In progress | README "How a phase runs", parallel table |
| G02 | One detailed execution prompt per phase before implementation | 03 | prep | Done but untested | `docs/overhaul/phase-0..7`, branch `claude/overhaul-docs` (not yet judged) |
| G03 | Short plain-English report after every phase (Done / Original requests / Judge score / Needs testing / You need to do / Next) | 03, 04 | all | Not started | |
| G04 | One master checklist, updated every phase, resumable across sessions | 03 | all | In progress | this file |
| G05 | Preserve and refine Workbench; no other direction without Will's approval | 02, 03 rule 5, 04 | 2-4 | In progress | Decisions |
| G06 | Always the real Origin logo; never the fake one | 02 s1, 03 rule 6, 04 | 2 (all after) | Done and tested | check_logo.py gate in build-check (green 40230306341; planted-lookalike tests d0db2cd4b); 618b1ce73 installer, 332def388 in game, 5a8c3b771 website (judge 98); rings retired 01f009a13 |
| G07 | Never visually redesign the in-game Origin Mod Menu | 01 s1.1, 03 rule 7, 04 | all | Done and tested | P4-14 verifier: every module's Mod Menu differs from baseline 40178168462 only in the intended ring-backdrop removal; panel interior pixel-identical at 1366 (max diff <= 10) |
| G08 | Verify every completed task; never claim untested work; never fabricate results | 01 s1.4, 03 rule 8, 04 | all | In progress | README S12 |
| G09 | Preserve working features, user data and unfinished work | 01 s1.3, 03 rule 9, 04 | all | In progress | README S9, Phase 0 archive rule |
| G10 | Do not silently skip difficult work; report blockers and keep going on independent work | 03 rule 13, 04 | all | In progress | |
| G11 | Hold for Will: destructive / irreversible actions, money, credentials, production infrastructure, design-direction changes | 03 rule 15, 04 must-not | all | In progress | README S11 |
| G12 | Full project audit; earlier requests classified | 01 s3 | prep | Done and tested | `docs/overhaul/audit/*.md` (2026-10-25) |
| G13 | Three or more design concepts, one recommended, Will's approval | 01 s16 | prep | Done and tested | `docs/overhaul/design/concepts.md`; Will chose Workbench (00) |
| G14 | Plan presented and approved before implementation | 01 s1.2, s20-21 | prep | Done and tested | 00, 03 |
| G15 | Independent judge per phase, 95/100, auto-rejects, loop until pass | 04 | all | In progress | judge passed for phases 0-2 (95, 96, 95-98 per phase); phases 3-7 judged once: P3 80, P4 74, P5 89 / UI 84, P6 81, P7 89 (all below 95); Will (2026-10-25) cut re-judges for speed, findings fixed or listed as follow-ups; docs/overhaul/JUDGE.md |
| G16 | Engineering standards: root causes, no needless deps or rewrites, reversible changes, validated downloads, responsive UI, no generic AI look | 01 s20-21, 04 Design | all | In progress | README S2/S3 |
| G17 | Real-install testing rules (no deleting worlds/saves/profiles/accounts, back up first, credentials untouched) | 04 | all | In progress | README S1 |
| G18 | Cosmetics service (store, capes, friends) stays exactly as it is | 04 | all | In progress | README S9 |
| G19 | Cross-version architecture: per-version best fit, no forced shared internals, documented shared spec | 01 s18, CLAUDE.md mandate 5 | 2, 4-6 | In progress | |
| G20 | Full QA list (launcher, Minecraft, website) with automated tests where practical | 01 s19 | all; final in 7 | In progress | launcher tests 2378 on main, shots 294+153 0 clipped (c0f24144d), CI boots and dev-shots per phase; final QA list and Will's real-install pass (P7-08) pending |

### Phase 0 - Cleanup
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P0-01 | Merge the fix: imported mods can't stop the game starting (`claude/launch-crash-fix2`) | 03 P0 | 0 | Done and tested | judge 95/100; 1523 launcher + 61 setup tests; CI build-check and launcher-shots green; merged 3892cf1f6 |
| P0-02 | Prove that fix works (tests, build-check, launch-time) | 03 P0 | 0 | Done and tested | judge 95/100; 1523 launcher + 61 setup tests; CI build-check and launcher-shots green; merged 3892cf1f6 |
| P0-03 | Merge the fix: self-update checked against its SHA-256 (`claude/shots-and-update-hash`) | 03 P0 | 0 | Done and tested | judge 95/100; 1523 launcher + 61 setup tests; CI build-check and launcher-shots green; merged 3892cf1f6 |
| P0-04 | Prove that fix works (update tests, `.sha256` in the release artifact, opus security review) | 03 P0 | 0 | Done and tested | judge 95/100; 1523 launcher + 61 setup tests; CI build-check and launcher-shots green; merged 3892cf1f6 |
| P0-05 | Inventory every worktree, branch and stash | 03 P0 | 0 | Done and tested | 148 -> 28 worktrees; old branches bundled to artifacts/git-backup-2026-10-25/old-branches.bundle before deletion; 79 merged remote claude/* branches deleted; kept: anything with unique or uncommitted work, saves, PR #34, the 1.8.9 stash |
| P0-06 | Identify what is safe to remove; preserve unique work (`archive/<name>`) | 03 P0 | 0 | Done and tested | 148 -> 28 worktrees; old branches bundled to artifacts/git-backup-2026-10-25/old-branches.bundle before deletion; 79 merged remote claude/* branches deleted; kept: anything with unique or uncommitted work, saves, PR #34, the 1.8.9 stash |
| P0-07 | Remove obsolete in-project copies only after verification | 03 P0 | 0 | Done and tested | 148 -> 28 worktrees; old branches bundled to artifacts/git-backup-2026-10-25/old-branches.bundle before deletion; 79 merged remote claude/* branches deleted; kept: anything with unique or uncommitted work, saves, PR #34, the 1.8.9 stash |
| P0-08 | Leftovers outside the project (TEMP worktrees, sibling folders, Codex folder) | 03 P0, gate list | 0 | Needs my decision | held: deletes outside the project |
| P0-09 | Carry the three 2026-10-19 MEMORY entries + entries for v1.0.62-v1.0.65 and the Origin AI work | 03 P0 | 0 | Done and tested | MEMORY.md 2026-10-19 entries (257a79721) + v1.0.62-65 catch-up |
| P0-10 | Refresh stale notes: CLAUDE.md Current state, Roadmap, Brand (soft accents, Workbench); auto-memory marked superseded | 03 P0 | 0 | Done and tested | CLAUDE.md current state, roadmap and brand updated |
| P0-11 | Bring the main checkout (480 behind, dirty) onto current main safely, backup first | brief | 0 | Done and tested | main checkout == origin/main (924a5809d); backup in scratchpad backup-main/ |
| P0-12 | Accurate baseline (tests, launcher-shots, dev-shots, boot-smoke, launch-time) | 03 P0 | 0 | Done and tested | Baseline table filled from 4 green CI runs on 924a5809d |

### Phase 1 - Quick fixes
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P1-01 | Settings rail: first click on Origin Client opens Origin Client (root cause) | 03 P1, 01 s9 | 1 | Done and tested | 8b16956e8 (Phase 1 close, judge 96): SettingsRailSpy + SettingsRailSpyTests (first-click root cause) |
| P1-02 | Developer mode gone from public launcher navigation | 03 P1, 01 s9 | 1 | Done and tested | 8b16956e8 (Phase 1 close, judge 96): DeveloperAccess removes Developer mode from public navigation; DeveloperAccessTests |
| P1-03 | Internal dev access kept without hidden-button "security" (dev build / `ORIGIN_DEV=1`) | 03 P1, 01 s9 | 1 | Done and tested | 8b16956e8 (Phase 1 close, judge 96): dev access via dev build / ORIGIN_DEV=1, no hidden button; DeveloperAccessTests |
| P1-04 | Every public AI feature and AI-assistant reference removed (site, launcher, docs, CI) | 03 P1, 01 s17, 04 | 1 | Done and tested | judge 96/100; live on originclient.org: /assistant 404; deploy run 40176596535 |
| P1-05 | Removed AI routes no longer reachable; site still works | 01 s17 | 1 | Done and tested | judge 96/100; live on originclient.org: /assistant 404; deploy run 40176596535 |
| P1-06 | GitHub card restored | 03 P1 | 1 | Done and tested | judge 96/100; live on originclient.org (deploy run 40176596535) |
| P1-07 | Website navigation fixed on phones | 03 P1 | 1 | Done and tested | judge 96/100; live on originclient.org (deploy run 40176596535); phone nav fixed |
| P1-08 | "Website" button becomes a Store card | 03 P1, 01 s10 | 1 | Done and tested | 8b16956e8 (Phase 1 close, judge 96): Website chip became the Store card; Store later sits in the top bar (ebac963b6, ca0a22ff4) |
| P1-09 | Store opens the configured Origin Store URL, proven by a check | 03 P1, 02 s6 | 1 | Done and tested | 8b16956e8 (Phase 1 close, judge 96): OriginLinks + OriginLinksTests prove the Store opens the configured URL |
| P1-10 | No regressions from the quick fixes | 03 P1 | 1 | Done and tested | 8b16956e8 (Phase 1 close, judge 96): build-check and launcher-shots green; later runs 2378 tests on main, 0 failed |

### Phase 2 - Unified design system
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P2-01 | Official logo and wordmark identified | 02 s1, 04 | 2 | Done and tested | Will's answer (04): `origin_logo.png` / `origin.ico`, `wordmark.png` |
| P2-02 | Launcher draws the official logo through one central reference (OriginMark, StartupIntro rings, Mark B in ModIcons replaced) | 02 s1, 04 | 2 | Done and tested | f515c3929 (merge 21ee496f3): BrandLogo + Brand.Logo, OriginMark/StartupIntro rings/Mark B removed; build-check 40230306341; p3-sanity tests 2098/63/3 |
| P2-03 | Installer and uninstaller use the official logo (OrbitMark replaced) | 02 s1, s8, 04 | 2 | Done and tested | 618b1ce73 installer + uninstaller on Workbench with the real logo (judge 95); installer tour 150/100/200% in c0f24144d; setup tests 63 |
| P2-04 | In-game title and loading screens use the official logo; `OriginUi.mark()` fallback replaced; "Mod ID" placeholder `icon.png` fixed (1.21.10, 1.12.2, 1.21.5, shared) | 02 s1, s9, 04 | 2 | Done and tested | 332def388 official logo in game (hand-drawn mark removed, icon.png fixed); rings retired on every module (01f009a13); boot-smoke zero mixin-apply failures; build-check 40230306341 |
| P2-05 | Website uses the official logo (inline symbols, `world.js:396-425`) | 02 s1, 04 | 2 | Done and tested | 5a8c3b771 website uses the real logo everywhere (judge 98) |
| P2-06 | `check_logo.py` detector in build-check, proven able to fail | 02 s1 ("cannot come back") | 2 | Done and tested | f515c3929: tools/design/check_logo.py in build-check, green 40230306341; planted drawing and copied/lookalike logo tests (d0db2cd4b) |
| P2-07 | Store placement chosen from previews | 02 s6, s11 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-08 | Account pill proportions chosen from previews | 02 s5, s11 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-09 | Version-card number treatment chosen from previews (neutral) | 02 s3, s11 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-10 | Main-menu icons without boxes chosen from previews | 02 s7, s11 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-11 | "Origin's custom backgrounds" meaning chosen from previews | 03 P4 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-12 | Spec: colours, typography, component sizing, spacing, corner radii | 03 P2, 01 s5 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-13 | Spec: buttons, dropdowns, navigation, icons, hover / selected / disabled / focus states | 03 P2, 01 s5 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-14 | Spec: animations, backgrounds, loading indicators | 03 P2, 01 s5 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-15 | Spec: launcher-to-game continuity; Mod Menu documented as frozen; no shared runtime UI dependency | 01 s5 | 2 | Done and tested | judge 95/100 round 3 (81 -> 92 -> 95); docs/overhaul/DESIGN-SYSTEM.md + design-preview; merged 2026-10-25 |
| P2-16 | `check_tokens.py` detector (report in Phase 2, gate in Phase 3) | 01 s5, 02 s2 | 2 | Done and tested | f515c3929, d0db2cd4b: check_tokens.py is a build-check gate at 0 bypasses (catches named colours, planted tests); green 40230306341 |
| P2-17 | Hue removed from the logo glow (`Effect.OriginGlow` jade) | 00 soft accents, CLAUDE.md brand | 2 | Done and tested | f515c3929: Effect.OriginGlow deleted; no glow in the launcher (d0db2cd4b) |
| P2-18 | Display font for the numeral (new dependency) decided if the spec needs one | 01 s6 | 2 | Done and tested | DESIGN-SYSTEM.md: numerals are Inter 800 at natural width (type.numeral), font already bundled, no new dependency |

### Phase 3 - Launcher redesign (and installer)
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P3-01 | Spec tokens applied; zero hard-coded colour / size bypasses | 03 P3, 01 s4-5 | 3 | Done and tested | f515c3929, d0db2cd4b: token and logo gates in build-check at 0 bypasses (planted tests); build-check 40230306341; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-02 | Fixed translucent top bar, readable, restrained border; blur only where practical | 03 P3 #1, 01 s7 | 3 | Done and tested | aa5bb789c, be757e4a7, d0db2cd4b: top bar on its own Workbench surface, opaque so nothing reads through (DESIGN-SYSTEM 11.4); shots 0 clipped; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-03 | Numerals: correct proportions (no 70 % squash), weight, sharp | 03 P3 #2, 01 s6 | 3 | Done and tested | aa5bb789c numeral at natural width (Inter 800, no squash); 4eae4685f numeral above the bottom scrim; shots 294+153 0 clipped; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-04 | Numerals centred between the bar and the version strip, checked at several sizes | 03 P3 #2, 01 s6 | 3 | Done and tested | aa5bb789c, f214c99f9, 4eae4685f, 3b943863b: centred in the bar-to-strip band, contrast shots incl 1180 size; p3-sanity verifier 7/7; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-05 | Two-step version selector (family -> release), spacious grid, keyboard, selection kept | 03 P3 #3, 01 s8 | 3 | Done and tested | 0d01e5a1f (family cards -> releases, keyboard nav, shots, tests), 026e48157 expanding card + compact dropdown, 4037b0e44 keyboard test; merge ca0a22ff4; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-06 | Clear installed / downloadable states, accurate, computed off the UI thread | 03 P3 #4, 01 s8 | 3 | Done and tested | 009bec154 InstalledVersions service (off the UI thread), 0d01e5a1f states; tests in the 2098-test run (c0f24144d); judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-07 | Play replaces Download; installs what is missing automatically with progress, then launches | 03 P3 #5, 02 s4 | 3 | Done and tested | 528f46259 Play installs a missing or partly installed version; PlayInstallTests; merge 6cd015fde; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-08 | Install failure shows a clear error and retry; files left intact | 02 s4, 01 s8 | 3 | Done and tested | 528f46259, 09469cfd0 failure screen with Retry (a timeout is a failure, not Cancelled), f6380d108 journal failures logged; PlayInstallTests; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-09 | Version numbers integrated into the card artwork | 03 P3 #6, 02 s3 | 3 | Done and tested | ffb598993, 3b943863b: numbers set into the card art with an art-aware shade; contrast shots; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-10 | Player head and account pill proportions | 03 P3 #7, 02 s5 | 3 | Done and tested | 807010d13, 4247c71d3: account pill (crisp head, truncating name, offline dot); merge 05ec18b07; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-11 | Store card placement and appearance | 03 P3 #8, 02 s6, 01 s10 | 3 | Done and tested | ebac963b6, ca0a22ff4: Store in the top bar, no Home profile button (DECISIONS 2026-10-25); judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-12 | Consistent buttons, dropdowns, icons, containers; no oversized bars or empty space | 03 P3 #9, 02 s2 | 3 | Done and tested | capsule tokens (ebac963b6, c1bc885b2); c0f24144d pixel diff vs main 176 identical / 38 explained; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-13 | Mods and Profiles screens redesigned | 03 P3 #10, 01 s4 | 3 | Done and tested | 652965a3c, df26a8b22, 4f8f1277d (judge fixes), merges e686846c3, 9164b0247; every-state shots; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-14 | Settings, dialogs, notifications, tooltips, menus, inputs redesigned (+ PR #34 content) | 03 P3 #10, 01 s4 | 3 | Done and tested | b01c8c298 (Settings Sync folded in from PR #34), c1bc885b2, merge cdacde54c; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-15 | Import, crash, launch overlay, intro, sign-in, friends screens re-skinned | 03 P3 #10, 01 s4 | 3 | Done and tested | 7d07f97cd, merge c34699547: crash, import steps, welcome, sign-in, friends restyled; true capsules; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-16 | Installer, setup and uninstaller follow Workbench | 03 P3, 02 s8 | 3 | Done and tested | 618b1ce73 (judge 95); installer tour 150/100/200% in c0f24144d; setup tests 63; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-17 | Visual sanity check: every screen at three sizes, three DPI settings, every state | 02 s10, 01 s19 | 3 | Done and tested | c0f24144d p3-sanity: verifier 7/7; 294+153 shots 0 clipped; 81 dpi-* shots (3 sizes x 3 DPIs); tests 2098/63/3; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |
| P3-18 | No startup or frame-rate regression (cold start < 3 s, 60 fps UI) | CLAUDE.md constraints, 01 s20-21 | 3 | Done but untested | render-mode first frame 541-729 ms in render shots; a real cold start needs an interactive launcher run (gated). Needs Will; judge 80/100 (fail; its launcher findings fixed in claude/judge-polish, no re-judge: Will's speed call 2026-10-25) |

### Phase 4 - In-game main menu and loading screens
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P4-01 | 1.21.1 title redesigned to match the launcher (Workbench, softer surfaces) | 03 P4, 01 s16, 00 | 4 | Done and tested | 4a66d7be6, da702dc1e, 3bf094a5e, 31623e004: 1.21.1 title in the Workbench look; dev-shots incl title hover/press/focus; zero mixin-apply failures; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); 2026-10-27: title buttons are now Minecraft's own dark buttons + font, premade dock icons (577216cf8) |
| P4-02 | Vanilla menu functions and version-specific buttons kept on every version | 03 P4, 01 s16 | 4 | Done but untested | vanilla buttons present in the 20-module title grid (rings-out dev-shots runs 40226765644, 40227021985, 40227676918, 40227998378, 40227645964 (95-117 PNGs per module, mixinfail=0)); no click-through of every function. Needs Will; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |
| P4-03 | Mods button opens the existing Origin Mod Menu | 03 P4, 01 s16 | 4 | Done but untested | Mods button present in the title grid and menu_* Mod Menu shots exist per module (P4-14); the click path is not driven in CI. Needs Will; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |
| P4-04 | Icons without nested boxes | 03 P4, 02 s7 | 4 | Done and tested | plain parchment dock glyphs, no boxes, on all 20 live modules (a38361fc6..0df7f9c74, 144cf8190); 20-module title grid matches 1.21.1; boot-smoke 40244625568; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |
| P4-05 | Origin's custom backgrounds | 03 P4, 01 s16 | 4 | Done but untested | title backdrop per the P2-11 pick (DESIGN-SYSTEM s12); ring backdrop removed everywhere (01f009a13); not separately verified per version. Needs Will; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |
| P4-06 | 1.21.1 loading screens: Origin look, real logo, lightweight | 03 P4, 02 s9 | 4 | Done and tested | 8bbd42914, 7c2d2541c, 58ddfab19: one Workbench loading picture with the real logo, first frame decodes nothing, logo flies into the title; dev-shots [shots:1.21.1]; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |
| P4-07 | 1.21.1 design and function verified before any port | 03 P4 | 4 | Done and tested | 1.21.1 done and shot first (4a66d7be6, da702dc1e) before the port merges; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |
| P4-08 | Port wave W1: 1.20, 1.20.1, 1.20.2, 1.20.4 | 03 P4 | 4 | Done and tested | 1.20 a679b5cb6 (1a3154a38); 1.20.2, 1.20.4 in 3952f6b0b; 1.20.1 rides the 1.20 module; rings-out dev-shots runs 40226765644, 40227021985, 40227676918, 40227998378, 40227645964 (95-117 PNGs per module, mixinfail=0); judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); title-port shot runs 40236720080, 40236886941, 40237137504, 40237558497, 40237626010, 40238188228, 40238195619, 40238943451 |
| P4-09 | Port wave W2: 26.1, 26.2, 26.3 | 03 P4 | 4 | Done and tested | 8bca11949 + merge ceff622a4 (26.1, 26.2, 26.3); 999776dcd 26.3 resting cursor; rings-out dev-shots runs 40226765644, 40227021985, 40227676918, 40227998378, 40227645964 (95-117 PNGs per module, mixinfail=0); judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); title-port shot runs 40236720080, 40236886941, 40237137504, 40237558497, 40237626010, 40238188228, 40238195619, 40238943451 |
| P4-10 | Port wave W3: 1.21, 1.21.2-1.21.4, 1.21.6-1.21.8, 1.21.10, 1.21.11 | 03 P4 | 4 | Done and tested | 3952f6b0b (1.21, 1.21.4), f4ecc96f2 (1.21.6-1.21.11), merges 67e0a8931, 2bb3a018c; rings-out dev-shots runs 40226765644, 40227021985, 40227676918, 40227998378, 40227645964 (95-117 PNGs per module, mixinfail=0); judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); title-port shot runs 40236720080, 40236886941, 40237137504, 40237558497, 40237626010, 40238188228, 40238195619, 40238943451 |
| P4-11 | Port wave W4: 1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 | 03 P4 | 4 | Done and tested | dbf586430 (1.17.1, 1.16.5), f922f4936 (1.18.2-1.19.4), 1e757d98f; merges 4b95f9838, 1004f3ecd; rings-out dev-shots runs 40226765644, 40227021985, 40227676918, 40227998378, 40227645964 (95-117 PNGs per module, mixinfail=0); judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); title-port shot runs 40236720080, 40236886941, 40237137504, 40237558497, 40237626010, 40238188228, 40238195619, 40238943451 |
| P4-12 | Port wave W5: 1.8.9 title (Forge, hand re-implementation) | 03 P4 | 4 | Done and tested | 6d0eb9dd6 + merge a38361fc6: 1.8.9 title with Workbench buttons, Inter text, 4-icon dock; rings-out dev-shots runs 40226765644, 40227021985, 40227676918, 40227998378, 40227645964 (95-117 PNGs per module, mixinfail=0); judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); title-port shot runs 40236720080, 40236886941, 40237137504, 40237558497, 40237626010, 40238188228, 40238195619, 40238943451 |
| P4-13 | Origin loading experience on every supported version, 1.8.9 especially, real logo | 03 P4, 02 s9 | 4 | Done and tested | real origin_logo.png + plain capsule bar on every live module incl 1.8.9 (7fece3e18, d51024325, 071d4d209, d920df694, e81f31959); build-check 40230306341; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); 2026-10-27: one loading look (Minecraft font, opaque backdrop, no 0% stub, Workbench Cancel, Realms connecting) on 1.21.1 (cf4c658da, a5a77715f) and ported to every live version |
| P4-14 | Mod Menu pixel-identical on every module | 03 P4, 01 s1.1 | 4 | Done and tested | verifier: every module differs from baseline 40178168462 only in the ring-backdrop removal; panel interior at 1366 pixel-identical (max diff <= 10); judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |
| P4-15 | Menu and loading performance kept (draw calls, frame time) | 03 P4, 01 s5 | 4 | In progress | frame time NOT measured (dev-shots logs carry no frame data; framelog input needs set=handoff); 1.21.1 first frame decodes nothing (58ddfab19); judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks); 2026-10-27: still open, needs a real GPU (Will) |
| P4-16 | Unsupported version features not shown (Realms only where it works) | 01 s16 | 4 | Done and tested | verifier: Realms shown only on 26.3; Mojang's Friends button kept on 26.2+ (bea3f5e89); B-16 e7671debe; judge 74/100 (fail; in-game loading consistency is a follow-up, no game runs until Will checks) |

### Phase 5 - Back-end systems
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P5-01 | Experiments E1 (Sodium alternate on 1.21.11) and E2 (1.21.1 nested vs top-level Sodium) | `audit/architecture.md` s0 | 5 | Done and tested | spike-sodium-iris.md answers E1 and E2 (five pairs on 1.21.11, two on 1.21.1; top-level jars beat the nested ones); CI pair proof boot-smoke 40252040490; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-02 | One shared compatibility checker (`Core/Compat`) | 03 5.1 | 5 | Done and tested | 44be3fd4a Core/Compat shared engine (judge 97); launcher tests 2378 on main; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-03 | Used by profiles, launch preparation, imports, dependency management, crash recovery; no duplicate logic | 03 5.1 | 5 | Done and tested | 44be3fd4a, 667abaecf, 1fe2731c4, 94c2eb12f, 7d48a1049: import, launch, profiles, mod ledger and crash repair all use Core/Compat; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-04 | Integrity: Fabric API, Simple Voice Chat and the 1.8.9 stack hash-pinned; "managed" decided by hash | 01 s12, s20-21 | 5 | Done and tested | 7c0870f3e: Fabric API, Simple Voice Chat, 1.8.9 Forge files pinned and checked; ModrinthFileGuard tests; merge 8d0cbc7e7, build-check 40253196656; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-05 | Per-profile versions of Origin-managed components; no global change breaks another profile | 03 5.2, 01 s12 | 5 | Done and tested | 8d0cbc7e7 (component store under OriginPaths, ComponentPins via SettingsStore.Update); opus security review; verifier pass; build-check 40253196656. In-world mixins under older pairs unverified; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-06 | One profile, several compatible configurations, picked automatically at Play, hidden from players | 04 s6 | 5 | Done and tested | 2bed6d103 + configuration-matrix.md: shipped set first, then proven alternates; pair boots 40252040490 (1.21.1 0.6.5+1.8.1; 1.21.11 0.8.4+1.10.5, 0.8.0+1.10.0) 0 mixin fails; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-07 | Incompatible dependencies isolated (own launch folders, config overlay); safe switching | 04 s6 | 5 | Done and tested | 2bed6d103: each alternate launches from its own mods-profile-<id> folder, Sodium/Iris config overlay swapped in and restored; boot proof 40252040490. Live Modrinth and real click-through unverified; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-08 | Fix Compatibility: one click, original kept, repaired copy, "no fix" explained honestly | 03 5.3, 01 s12, 04 s6 | 5 | Done and tested | 68f198868, b2ce4160b: journaled copy profile '<name> fixed', original kept, one fix at a time; opus security review (1 Medium fixed); verifier incl 3 planted-defect proofs. Real click-through Needs Will; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-09 | Trusted dependencies only; Modrinth lookup sends mod ids and hashes only | 01 s12, 04 | 5 | Done and tested | 8d0cbc7e7: Modrinth one GET per missing id (id only), SHA-512 pinned downloads; opus security review; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-10 | Crash V3: real crash vs normal close; facts from crash report, logs, hs_err | 03 5.4, 01 s13 | 5 | Done and tested | 99a59154b Crash V3: CrashFacts/CrashRules/CrashEngine; crash-plant 40250622488 7/7 + corpus pass; tests 2299; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-11 | Confidence levels (confirmed / strong / possible / unknown); safe auto-repairs only; five-section crash screen | 03 5.4, 01 s13 | 5 | Done and tested | 99a59154b: confirmed/strong/possible/unknown; fix only at confirmed/strong via CrashRepair.DisableBlamed; a2bea4af8, 54f7cf721; opus security review; verifier incl planted-defect proofs; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-12 | Crash test corpus, CI defect planting, privacy scrub; nothing uploaded | 01 s13 | 5 | Done and tested | 99a59154b: 31-32 case corpus, crash-plant CI (40250622488; after fixes 40251956117), scrub of names/servers/IPv6/ids/tokens (d6086b29e); nothing uploaded; build-check 40251955934; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-13 | Guided import: one source, Everything or Choose, confirm, verify; never claims unsupported data | 03 5.5, 01 s11 | 5 | Done but untested | 1a181e50f, 4e3a98d72, 16a959972, c7c32fc3b; merge 178b43118: four steps (pick, choose, review, report); ImportFlowModelTests; no verifier run recorded. Real imports from Will's launchers Needs Will; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |
| P5-14 | Imports create Origin profiles; the everyday "drop a jar" path is checked too | 04, `audit/launcher-systems.md` s1 | 5 | Done but untested | 462e3e308 every imported setup becomes its own Origin profile; 4e3a98d72 Play on Done starts it; merge 178b43118; unit tests only, the drop-a-jar path not separately checked. Needs Will; judge 89/100 backend, 84/100 UI (fail; fixes merged, no re-judge: Will's speed call 2026-10-25) |

### Phase 6 - 1.8.9 performance
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P6-01 | OptiFine M5 setting key names verified (E3) | `audit/perf-189.md` s7 | 6 | Done and tested | E3: 71/71 keys rebuilt from the real OptiFine jar (b66686af3, 3dd1108ba), CI 40254131135; test_prod189.py JarKeys; no preset change; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-02 | Production-like 1.8.9 stack in CI (Forge, OptiFine, TexFix, BetterFps, Temurin 8) | `audit/perf-189.md` s6 | 6 | Done and tested | 800255b3c production-like stack (Forge, OptiFine M5, Xvfb + llvmpipe), CI runs 40190206267, 40192448235; shader variant a4cbd8671; p6-measurements.md; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-03 | Benchmark mode: avg FPS, 1 % lows, frame times, memory, other useful metrics; repeatable scenarios | 03 6.1, 01 s15 | 6 | Done and tested | 800255b3c, 3af9591df BenchProf: avg FPS, p50/p99, alloc MB/s, per-hook ns/frame; scenes idle/hud/menu/pause/players/cosmetics (runs 40190206267, 40191213373, 40192448235). CI numbers are relative, not player FPS; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-04 | Benchmark summarizer, proven able to fail | 03 6.1 | 6 | Done and tested | tools/bench/summarize.py + test_summarize.py; the gate went red on a hud-less probe run and on the 30 FPS cap regression (40191592587), so it can fail; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-05 | Competitive / Balanced / Quality presets implemented and tested (undo, no silent override) | 03 6.2, 01 s15 | 6 | Done and tested | 9216daecb presets engine (Competitive/Balanced/Quality, preview + undo, OptiFine round-trip gate); GamePresetTests.EveryOptiFineKeyIsVerified; keys re-checked 71/71; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-06 | HUD rendering optimised (measured) | 03 6.3 | 6 | Done and tested | deed72708, c91661e61, 29946572d (merge 4957fbe1b): HUD fills drawn as one batch; the post-change HUD number is not recorded in p6-measurements.md; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks); 2026-10-27: measured in CI, pick 3 HUD -0.9 ms p50, heap -21% at 427x240 (p6-measurements.md) |
| P6-07 | Particles optimised (measured) | 03 6.3 | 6 | Done and tested | c7f5e9e76: particle filter resolved once per config epoch; measured as noise (alloc 47.0 vs 46.7 MB/s), kept at zero cost; p6-measurements.md; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-08 | Cosmetics optimised (measured) | 03 6.3 | 6 | Done and tested | 38b7566ff, 8f7974f98, 6cf43d628: cosmetic layer 261 -> 26 KB/frame, cape 54 -> 2.6, output bit-identical; display lists for still bones (llvmpipe, relative); judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-09 | Blur optimised (measured) | 03 6.3 | 6 | Done and tested | 5007ba4b4, a6ffa9291, ef0765ebd: Menu Blur reuses its backdrop while a single-player pause is frozen; pause cost +5.13 -> +3.23 ms (11 rounds, CI relative); judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks); 2026-10-27: Competitive keeps Menu Blur off (cheaper than capture-once); Balanced keeps it on |
| P6-10 | Other measured bottlenecks (rendering overhead, memory allocation, background work, entity culling, font) | 03 6.3, 01 s15 | 6 | Done but untested | minimap 6f904bdc5, 5f4ca283a done; entity occlusion and Patcher-style knobs can only be ranked on a real GPU. Needs Will; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-11 | Java runtime and GC chosen from measurements | 01 s15 | 6 | Done and tested | U6.4 Java/GC A/B (runs 40245185796, 40245198108, 40245200772; 3b4bc78a6, 1364366d3): no variant beats the shipped Temurin 8u504 + G1 flags; preset unchanged; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks) |
| P6-12 | Real GPU: one command for Will + a readable report; agent run on his PC; no CI number passed off as gameplay | 03 6.4, 00, 04 | 6 | Done but untested | tools/bench/bench-189.ps1 and the CI report exist; the real-GPU run on Will's PC is not done. Needs Will; judge 81/100 (fail; measurement gaps are follow-ups, no game runs until Will checks); 2026-10-27: still Will's run |

### Phase 7 - Mod updates and close
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P7-01 | Dependency audit: current, latest compatible stable, breaking changes | 03 P7, 01 s14 | 7 | Done but untested | `docs/overhaul/p7-dependency-audit.md`: every live module (+ covered versions) vs the newest Modrinth release for its exact MC version; held list with reasons. Judge-reviewed (96), rows not independently spot-checked. 1.8.9 (Forge + OptiFine) is a fixed chain, not in the table |
| P7-02 | Covered versions boot-tested in CI, or the limitation reported and verified another way | 03 P7 | 7 | Done and tested | 10404ac05 covers mode (tools/boot-smoke/covers.py, 6 CoversBoot tests): 1.21.3, 1.21.7, 1.20.1, 26.1, 26.1.1, 26.1.2 boot clean (run 40245168662); 1.21.2 boots clean after the Sodium 0.6.0 pin (bd38abe04, run 40245754930); boots stop at the title screen; judge 89/100 (fail; audit doc corrected 52d7cda8d, remaining items are follow-ups) |
| P7-03 | Updates applied through the 8-step gate | 03 P7 | 7 | Done and tested | `claude/p7-deps`: 1.21.11 Sodium 0.8.14 + Iris 1.10.8 + Mod Menu 17.0.1 + Fabric API 0.141.6; 1.21.1 / 1.21 ImmediatelyFast 1.6.15 + Mod Menu 11.0.5 (1.21.1 Fabric API 0.116.17); 26.3 Fabric API 0.165.0. Boot proof with the companions loaded at the catalog versions in the audit's boot evidence. 26.3 JEI 31.12.0.65 -> 31.14.0.72 (javap hook check identical). Evidence: boot-smoke 40182818181 (1.21.11, 1.21.1, 1.21 with companions), 40183076637 + 40184982512 (26.3), dev-shots world 40182853615 (1.21.11, 10/10 mixins) and 40184984449 (26.3, both JEI mixins applied); build-check 40182811038; 1565/1565 launcher tests. Judge 96 (an estimate, not Will's approval). In-game feel is Will's to check |
| P7-04 | Failing updates held or rejected, never shipped | 03 P7 | 7 | Done and tested | No update failed its gate. Held (reasons in the audit): JEI 27.48 (1.21.11) and 19.64 (1.21.1, 1.21) dropped the GUI render hooks; 1.21.1 Sodium 0.8.x (no stable Iris); 26.3 Sodium (newer build beta-only); 26.1 JEI 29.47.0.110 (javap: `UserInput` moved package, `renderCompactPotionIndicators` gained a parameter); other newer releases listed as held, not run through the gate |
| P7-05 | Launcher-installed mod pins (catalog) audited the same way | 01 s14 | 7 | Done and tested | The audit reads the catalog (`PerformanceModCatalog.Data.cs`) for every module and every covered version. Also: `FabricApiInstaller` now verifies Modrinth's SHA-512, host and file name and fails soft (`ModrinthFileGuard`, 38 tests) |
| P7-06 | Final pass: every row here has a final status and evidence | 01 s20-21, 03 | 7 | In progress | statuses filled from merged work 2026-10-25; rows marked Needs Will and the judge scores stay open; judge 89/100 (fail; audit doc corrected 52d7cda8d, remaining items are follow-ups); 2026-10-27: FINAL-REPORT known issues and recommendations each carry a status |
| P7-07 | One launcher release `launcher-v1.0.66`+ once tests, CI boots and the judge pass | 04 | 7 | Not started | |
| P7-08 | Real-install checks after the release (launcher, import, menus on real GPU) | 04 | 7 | Not started | Needs Will: real-install checks after the release (launcher, import, menus on a real GPU); judge 89/100 (fail; audit doc corrected 52d7cda8d, remaining items are follow-ups) |
| F-01 | Final report to Will (phases, judge scores, verified features, issues, blocked items, real benchmarks, what to test) | 04 Reports, 01 s20-21 | 7 | Done | docs/overhaul/FINAL-REPORT.md |
| F-02 | Additional recommendations list (not built) | 01 s22 | 7 | Done | docs/overhaul/FINAL-REPORT.md, Recommendations |

### Backlog folded in (2026-10-20 playtest list = B-01-B-25; other open ledger items = B-26+)
Status mapping from `docs/overhaul/audit/ledger.md`: Completed+verified = Done and tested; Implemented-unverified = Done but untested;
Partial = In progress; Not implemented = Not started.
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| B-01 | 1.8.9 loading screen: Cancel overlaps the status line | ledger s1 #1 | 4 | Done but untested | `eec9fd958` GuiConnectingMixin |
| B-02 | 1.8.9 black resource-pack screen | ledger s1 #2 | 4 | Done but untested | `eec9fd958` |
| B-03 | 1.8.9 Color Saturation flips the game | ledger s1 #3 | 4 | Done but untested | `eec9fd958` shader y flip |
| B-04 | 1.8.9 shader lag | ledger s1 #4 | 6 | Done but untested | p6-measurements.md: Origin's fixed cost is about 0.3 ms plus cosmetics in view; shader-pack bench variant a4cbd8671. Proving it needs a real-GPU A/B. Needs Will |
| B-05 | Nametags behind water / NPCs (1.8.9) | ledger s1 #5 | 4 | Done but untested | `eec9fd958` |
| B-06 | Discord link goes to the website (1.8.9) | ledger s1 #6 | 4 | Done but untested | `268e8174f` |
| B-07 | Missing performance options (1.8.9) | ledger s1 #7 | 6 | In progress | presets 9216daecb; which options Will meant is still unrecorded |
| B-08 | Transparent Menus setting | ledger s1 #8 | 4 | Done but untested | `8e2433780` |
| B-09 | Cosmetics halo white centre, not smooth | ledger s1 #9 | 4 | Done but untested | `268e8174f`, `e7671debe` |
| B-10 | Jagged main-menu icons | ledger s1 #10 | 4 | Done but untested | `d2282be60` (superseded by P4-04) |
| B-11 | 1.8.9 launch / port issue | ledger s1 #11 | 0 | Done and tested | = P0-01 (merge 3892cf1f6, judge 95) |
| B-12 | Esc menu pixelation (1.8.9) | ledger s1 #12 | 6 | Done but untested | `268e8174f` MenuBlur |
| B-13 | Lunar-level 1.8.9 performance | ledger s1 #13 | 6 | In progress | all five ranked picks merged (p6-measurements.md); Lunar-level on a real GPU Needs Will (P6-12); 2026-10-27: picks 3-5 merged and measured in CI; real GPU still Will's |
| B-14 | Per-world waypoints, every version | ledger s1 #14 | 4 | Done but untested | `8e2433780` |
| B-15 | Minecraft-style icons on main-menu buttons | ledger s1 #15 | 4 | Done but untested | `e7671debe`, `d2282be60` (restyled by P4-04) |
| B-16 | Hide Realms where it can't work | ledger s1 #16 | 4 | Done but untested | `e7671debe` |
| B-17 | 1.18 cosmetics halo | ledger s1 #17 | 4 | Done but untested | `e7671debe` |
| B-18 | Singleplayer dirt overlay 1.18 / 1.20 | ledger s1 #18 | 4 | In progress | 1.20 `e7671debe`; 1.18 unclear |
| B-19 | 26.x Friends crash | ledger s1 #19 | 4 | Done but untested | `21d1a91ab` |
| B-20 | 26.x cosmetics drag / switch | ledger s1 #20 | 4 | Done but untested | `21d1a91ab` (drag); "switch" from notes only |
| B-21 | 26.x Origin menus unclickable | ledger s1 #21 | 4 | Done but untested | `21d1a91ab` |
| B-22 | Launcher Mods-tab switch bug | ledger s1 #22 | 3 | Done but untested | `4a3d75f7e`, `123654d96`; Phase 3 pages rebuilt (merge 21ee496f3) |
| B-23 | Website link box in the launcher | ledger s1 #23 | 1 | Done but untested | `90a2e62f8`; now the Store entry in the top bar (P1-08, ebac963b6) |
| B-24 | Settings porting on import | ledger s1 #24 | 5 | Done but untested | `10f92ea05`, `369263285` |
| B-25 | Items for 1.16.5 / 1.17 named in the list header | ledger s1 #25 | 4 | Needs my decision | original list not stored |
| B-26 | PR #34: Settings Sync section, installer Customize option, Discord always-on | ledger s3 | 3 | Done and tested | PR #34 content merged: Settings Sync b01c8c298, installer Customize kept 618b1ce73, Discord always on b89f3cd5d (boot-smoke 40244625568); 2026-10-27: PR #34 closed, its content is on main |
| B-27 | MEMORY.md / CLAUDE.md drift (missing 10-19 entries, stale roadmap and brand) | ledger s0, s5 | 0 | Done and tested | = P0-09, P0-10 (done in Phase 0) |
| B-28 | After-shaders pass for world overlays on modules other than 1.21.1 | ledger s2 | after 7 | In progress | `46ad446bc` (1.21.1 only); not requested in 01-04 |
| B-29 | 1.21.10+ emoji truncated by `(char) event.codepoint()` | ledger s2 | 4 | Done but untested | 2820bfcef (1.21.10, 1.21.11, 26.x), 840a53770 (pre-1.21.10), 7c3050930; CI boots clean; typing an emoji in game Needs Will |
| B-30 | Loading screen's own first frame 637 ms; prepare-stage pauses with many mods | ledger s2 | 4 | Done but untested | 58ddfab19, 8bbd42914, ebf2d8d02, 7c2d2541c: first frame decodes nothing, prep on a background thread; no before/after number recorded |
| B-31 | Never booted in CI: 1.21.2, 1.21.3, 1.21.7, 1.20.1, 26.1.1 | ledger s2 | 7 | Done and tested | = P7-02 (10404ac05, runs 40245168662 / 40245754930) |
| B-32 | Origin AI website helper (shipped 10-21) to remove | ledger s2 | 1 | Done and tested | = P1-04 (55d350ca3); /assistant 404 live; Worker deleted by Will |
| B-33 | Light theme | ledger s2 | after 7 | Not started | not requested in 01-04 |
| B-34 | Panorama grade is an approximation, not a true desaturate | ledger s2 | after 7 | Not started | notes only |
| B-35 | Crash system v2 roadmap item | ledger s2 | 5 | Done and tested | = P5-10/11 (99a59154b, Crash V3) |
| B-36 | 1.21.9 (pulled) and 1.18.1 (no stable Sodium/Iris) | ledger s2 | - | Blocked | `src/mods/VERSIONS.md` |
| B-37 | Held-sync stale etag | ledger s2 | - | Done and tested | `08410fd6`, harness-measured |
| B-38 | boot-smoke 1.18.2-1.20.4 on real Java 17 | ledger s2 | - | Done and tested | merge `25173c5a`, run 39268523584 |
| B-39 | Loading bars never go backward (pre-1.20) | ledger s2 | - | Done and tested | merge `bf9cc864`, test in build-check |
| B-40 | Launcher mod profiles (Profiles page, Join server) | ledger s2 | 5 | Done but untested | MEMORY 2026-10-18; profile-vs-version UX decided in Phase 3 |
| B-41 | Settings Sync comment says "off by default" (stale or conflict) | ledger s2 | 3 | Done and tested | SettingsPage.xaml:116 says Settings Sync is on by default (b01c8c298) |
| B-42 | 1.8.9 and 26.1-26.3 in-game pass (#32) | ledger s2 | 4 | Done but untested | live since v1.0.4x |
| B-43 | v1.0.61 launcher pass (arrowing, Play, 4K art, head, Lunar import, crash window, installer) | ledger s2 | 3 | Done but untested | tag `launcher-v1.0.61` |
| B-44 | Loading-to-menu hand-off on 1.19.2, real hardware | ledger s2 | 4 | Done but untested | hand-off v2 `fc10e6d2` |
| B-45 | Launch v2, loading, Pack Hub, FPS graph on every version | ledger s2 | 4 | Done but untested | `1c094fe0b` (#33) |
| B-46 | Cosmetics over the outer skin layer; head/face 3-axis | ledger s2 | 4 | Done but untested | `d9226c6da` |
| B-47 | Screen-open lag (snappy menus) on every version | ledger s2 | 4 | Done but untested | `bfd7b59cc` |
| B-48 | Button Style setting + full vanilla menus | ledger s2 | 4 | Done but untested | in all 21 modules |
| B-49 | Cosmetics round 2026-10-18 (cape mesh, wear/unwear, Adjust, halo spin, live theme hover) | ledger s2 | 4 | Done but untested | MEMORY 2026-10-18 |
| B-50 | Origin+ membership: in-game cape/badge, launcher no-ads | ledger s2 | 4 | Done but untested | `OriginPlusMembership.cs` |
| B-51 | 26.3 `@plus` empty circle | ledger s2 | 4 | Done but untested | MEMORY 2026-10-19 (notes only) |
