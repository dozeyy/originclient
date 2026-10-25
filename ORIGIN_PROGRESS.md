# ORIGIN_PROGRESS - the Origin overhaul master checklist

The one tracking file for the 2026-10-25 overhaul (03: "a single ORIGIN_PROGRESS.md"). Every requirement from Will's four messages
(`docs/overhaul/requests/01`-`04`) and the open backlog has one row. Updated at every phase close by the closing chore-runner.

## How to resume
- **Docs:** `docs/overhaul/README.md` (how a phase runs, standing rules S1-S12, parallel tracks), `docs/overhaul/JUDGE.md` (the
  95/100 judge), `docs/overhaul/phase-0-cleanup.md` ... `phase-7-mod-updates.md` (one execution prompt per phase),
  `docs/overhaul/requests/` (Will's words), `docs/overhaul/audit/` (read-only audits of `de6b3eb6a`), `docs/overhaul/design/` (Workbench).
- **Where the docs live:** on main (merged 2026-10-25).
- **Current phase:** Phase 0 closed 2026-10-25 (judge 95). Running in parallel: P1 launcher (judging), P2 design system (fix loop), 5.1 compat engine, 6.1 benchmark, 7 mod updates, Sodium/Iris spike.
- **Next step:** P2 pass -> Phase 3 launcher redesign; P1 launcher merge on judge pass.
- **Statuses:** Done and tested · Done but untested · In progress · Not started · Blocked · Needs my decision.
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
| launcher-shots `p0-baseline` run | (U0.8) | |
| dev-shots `p0-baseline` run (Mod Menu reference shots) | (U0.8) | |
| boot-smoke all modules | (U0.8) | |
| launch-time 1.8.9, 1.16.5, 26.3 | (U0.8) | |
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
| G06 | Always the real Origin logo; never the fake one | 02 s1, 03 rule 6, 04 | 2 (all after) | Not started | logo decided (Decisions) |
| G07 | Never visually redesign the in-game Origin Mod Menu | 01 s1.1, 03 rule 7, 04 | all | In progress | proof method: `menu_*` shots vs `p0-baseline` (S4) |
| G08 | Verify every completed task; never claim untested work; never fabricate results | 01 s1.4, 03 rule 8, 04 | all | In progress | README S12 |
| G09 | Preserve working features, user data and unfinished work | 01 s1.3, 03 rule 9, 04 | all | In progress | README S9, Phase 0 archive rule |
| G10 | Do not silently skip difficult work; report blockers and keep going on independent work | 03 rule 13, 04 | all | In progress | |
| G11 | Hold for Will: destructive / irreversible actions, money, credentials, production infrastructure, design-direction changes | 03 rule 15, 04 must-not | all | In progress | README S11 |
| G12 | Full project audit; earlier requests classified | 01 s3 | prep | Done and tested | `docs/overhaul/audit/*.md` (2026-10-25) |
| G13 | Three or more design concepts, one recommended, Will's approval | 01 s16 | prep | Done and tested | `docs/overhaul/design/concepts.md`; Will chose Workbench (00) |
| G14 | Plan presented and approved before implementation | 01 s1.2, s20-21 | prep | Done and tested | 00, 03 |
| G15 | Independent judge per phase, 95/100, auto-rejects, loop until pass | 04 | all | Not started | `docs/overhaul/JUDGE.md` |
| G16 | Engineering standards: root causes, no needless deps or rewrites, reversible changes, validated downloads, responsive UI, no generic AI look | 01 s20-21, 04 Design | all | In progress | README S2/S3 |
| G17 | Real-install testing rules (no deleting worlds/saves/profiles/accounts, back up first, credentials untouched) | 04 | all | In progress | README S1 |
| G18 | Cosmetics service (store, capes, friends) stays exactly as it is | 04 | all | In progress | README S9 |
| G19 | Cross-version architecture: per-version best fit, no forced shared internals, documented shared spec | 01 s18, CLAUDE.md mandate 5 | 2, 4-6 | In progress | |
| G20 | Full QA list (launcher, Minecraft, website) with automated tests where practical | 01 s19 | all; final in 7 | Not started | |

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
| P0-12 | Accurate baseline (tests, launcher-shots, dev-shots, boot-smoke, launch-time) | 03 P0 | 0 | Not started | Baseline table |

### Phase 1 - Quick fixes
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P1-01 | Settings rail: first click on Origin Client opens Origin Client (root cause) | 03 P1, 01 s9 | 1 | Not started | static trace `audit/launcher-ui.md` s6 |
| P1-02 | Developer mode gone from public launcher navigation | 03 P1, 01 s9 | 1 | Not started | |
| P1-03 | Internal dev access kept without hidden-button "security" (dev build / `ORIGIN_DEV=1`) | 03 P1, 01 s9 | 1 | Not started | |
| P1-04 | Every public AI feature and AI-assistant reference removed (site, launcher, docs, CI) | 03 P1, 01 s17, 04 | 1 | Done and tested | judge 96/100; live on originclient.org: /assistant 404; deploy run 40176596535 |
| P1-05 | Removed AI routes no longer reachable; site still works | 01 s17 | 1 | Done and tested | judge 96/100; live on originclient.org: /assistant 404; deploy run 40176596535 |
| P1-06 | GitHub card restored | 03 P1 | 1 | Done and tested | judge 96/100; live on originclient.org (deploy run 40176596535) |
| P1-07 | Website navigation fixed on phones | 03 P1 | 1 | Done and tested | judge 96/100; live on originclient.org (deploy run 40176596535); phone nav fixed |
| P1-08 | "Website" button becomes a Store card | 03 P1, 01 s10 | 1 | Not started | |
| P1-09 | Store opens the configured Origin Store URL, proven by a check | 03 P1, 02 s6 | 1 | Not started | |
| P1-10 | No regressions from the quick fixes | 03 P1 | 1 | Not started | |

### Phase 2 - Unified design system
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P2-01 | Official logo and wordmark identified | 02 s1, 04 | 2 | Done and tested | Will's answer (04): `origin_logo.png` / `origin.ico`, `wordmark.png` |
| P2-02 | Launcher draws the official logo through one central reference (OriginMark, StartupIntro rings, Mark B in ModIcons replaced) | 02 s1, 04 | 2 | Not started | |
| P2-03 | Installer and uninstaller use the official logo (OrbitMark replaced) | 02 s1, s8, 04 | 2 | Not started | |
| P2-04 | In-game title and loading screens use the official logo; `OriginUi.mark()` fallback replaced; "Mod ID" placeholder `icon.png` fixed (1.21.10, 1.12.2, 1.21.5, shared) | 02 s1, s9, 04 | 2 | Not started | |
| P2-05 | Website uses the official logo (inline symbols, `world.js:396-425`) | 02 s1, 04 | 2 | Not started | |
| P2-06 | `check_logo.py` detector in build-check, proven able to fail | 02 s1 ("cannot come back") | 2 | Not started | |
| P2-07 | Store placement chosen from previews | 02 s6, s11 | 2 | Not started | |
| P2-08 | Account pill proportions chosen from previews | 02 s5, s11 | 2 | Not started | |
| P2-09 | Version-card number treatment chosen from previews (neutral) | 02 s3, s11 | 2 | Not started | |
| P2-10 | Main-menu icons without boxes chosen from previews | 02 s7, s11 | 2 | Not started | |
| P2-11 | "Origin's custom backgrounds" meaning chosen from previews | 03 P4 | 2 | Not started | |
| P2-12 | Spec: colours, typography, component sizing, spacing, corner radii | 03 P2, 01 s5 | 2 | Not started | |
| P2-13 | Spec: buttons, dropdowns, navigation, icons, hover / selected / disabled / focus states | 03 P2, 01 s5 | 2 | Not started | |
| P2-14 | Spec: animations, backgrounds, loading indicators | 03 P2, 01 s5 | 2 | Not started | |
| P2-15 | Spec: launcher-to-game continuity; Mod Menu documented as frozen; no shared runtime UI dependency | 01 s5 | 2 | Not started | |
| P2-16 | `check_tokens.py` detector (report in Phase 2, gate in Phase 3) | 01 s5, 02 s2 | 2 | Not started | |
| P2-17 | Hue removed from the logo glow (`Effect.OriginGlow` jade) | 00 soft accents, CLAUDE.md brand | 2 | Not started | |
| P2-18 | Display font for the numeral (new dependency) decided if the spec needs one | 01 s6 | 2 | Not started | |

### Phase 3 - Launcher redesign (and installer)
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P3-01 | Spec tokens applied; zero hard-coded colour / size bypasses | 03 P3, 01 s4-5 | 3 | Not started | |
| P3-02 | Fixed translucent top bar, readable, restrained border; blur only where practical | 03 P3 #1, 01 s7 | 3 | Not started | |
| P3-03 | Numerals: correct proportions (no 70 % squash), weight, sharp | 03 P3 #2, 01 s6 | 3 | Not started | cause `ArtStage.cs:348` |
| P3-04 | Numerals centred between the bar and the version strip, checked at several sizes | 03 P3 #2, 01 s6 | 3 | Not started | |
| P3-05 | Two-step version selector (family -> release), spacious grid, keyboard, selection kept | 03 P3 #3, 01 s8 | 3 | Not started | |
| P3-06 | Clear installed / downloadable states, accurate, computed off the UI thread | 03 P3 #4, 01 s8 | 3 | Not started | |
| P3-07 | Play replaces Download; installs what is missing automatically with progress, then launches | 03 P3 #5, 02 s4 | 3 | Not started | |
| P3-08 | Install failure shows a clear error and retry; files left intact | 02 s4, 01 s8 | 3 | Not started | |
| P3-09 | Version numbers integrated into the card artwork | 03 P3 #6, 02 s3 | 3 | Not started | |
| P3-10 | Player head and account pill proportions | 03 P3 #7, 02 s5 | 3 | Not started | |
| P3-11 | Store card placement and appearance | 03 P3 #8, 02 s6, 01 s10 | 3 | Not started | |
| P3-12 | Consistent buttons, dropdowns, icons, containers; no oversized bars or empty space | 03 P3 #9, 02 s2 | 3 | Not started | |
| P3-13 | Mods and Profiles screens redesigned | 03 P3 #10, 01 s4 | 3 | Not started | |
| P3-14 | Settings, dialogs, notifications, tooltips, menus, inputs redesigned (+ PR #34 content) | 03 P3 #10, 01 s4 | 3 | Not started | |
| P3-15 | Import, crash, launch overlay, intro, sign-in, friends screens re-skinned | 03 P3 #10, 01 s4 | 3 | Not started | |
| P3-16 | Installer, setup and uninstaller follow Workbench | 03 P3, 02 s8 | 3 | Not started | |
| P3-17 | Visual sanity check: every screen at three sizes, three DPI settings, every state | 02 s10, 01 s19 | 3 | Not started | |
| P3-18 | No startup or frame-rate regression (cold start < 3 s, 60 fps UI) | CLAUDE.md constraints, 01 s20-21 | 3 | Not started | |

### Phase 4 - In-game main menu and loading screens
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P4-01 | 1.21.1 title redesigned to match the launcher (Workbench, softer surfaces) | 03 P4, 01 s16, 00 | 4 | Not started | |
| P4-02 | Vanilla menu functions and version-specific buttons kept on every version | 03 P4, 01 s16 | 4 | Not started | |
| P4-03 | Mods button opens the existing Origin Mod Menu | 03 P4, 01 s16 | 4 | Not started | wired today (`audit/ingame-menus.md` s3), unverified per version |
| P4-04 | Icons without nested boxes | 03 P4, 02 s7 | 4 | Not started | |
| P4-05 | Origin's custom backgrounds | 03 P4, 01 s16 | 4 | Not started | |
| P4-06 | 1.21.1 loading screens: Origin look, real logo, lightweight | 03 P4, 02 s9 | 4 | Not started | |
| P4-07 | 1.21.1 design and function verified before any port | 03 P4 | 4 | Not started | |
| P4-08 | Port wave W1: 1.20, 1.20.1, 1.20.2, 1.20.4 | 03 P4 | 4 | Not started | |
| P4-09 | Port wave W2: 26.1, 26.2, 26.3 | 03 P4 | 4 | Not started | |
| P4-10 | Port wave W3: 1.21, 1.21.2-1.21.4, 1.21.6-1.21.8, 1.21.10, 1.21.11 | 03 P4 | 4 | Not started | |
| P4-11 | Port wave W4: 1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 | 03 P4 | 4 | Not started | |
| P4-12 | Port wave W5: 1.8.9 title (Forge, hand re-implementation) | 03 P4 | 4 | Not started | |
| P4-13 | Origin loading experience on every supported version, 1.8.9 especially, real logo | 03 P4, 02 s9 | 4 | Not started | |
| P4-14 | Mod Menu pixel-identical on every module | 03 P4, 01 s1.1 | 4 | Not started | |
| P4-15 | Menu and loading performance kept (draw calls, frame time) | 03 P4, 01 s5 | 4 | Not started | |
| P4-16 | Unsupported version features not shown (Realms only where it works) | 01 s16 | 4 | Not started | |

### Phase 5 - Back-end systems
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P5-01 | Experiments E1 (Sodium alternate on 1.21.11) and E2 (1.21.1 nested vs top-level Sodium) | `audit/architecture.md` s0 | 5 | Not started | |
| P5-02 | One shared compatibility checker (`Core/Compat`) | 03 5.1 | 5 | Not started | |
| P5-03 | Used by profiles, launch preparation, imports, dependency management, crash recovery; no duplicate logic | 03 5.1 | 5 | Not started | |
| P5-04 | Integrity: Fabric API, Simple Voice Chat and the 1.8.9 stack hash-pinned; "managed" decided by hash | 01 s12, s20-21 | 5 | Not started | |
| P5-05 | Per-profile versions of Origin-managed components; no global change breaks another profile | 03 5.2, 01 s12 | 5 | Not started | |
| P5-06 | One profile, several compatible configurations, picked automatically at Play, hidden from players | 04 s6 | 5 | Not started | |
| P5-07 | Incompatible dependencies isolated (own launch folders, config overlay); safe switching | 04 s6 | 5 | Not started | |
| P5-08 | Fix Compatibility: one click, original kept, repaired copy, "no fix" explained honestly | 03 5.3, 01 s12, 04 s6 | 5 | Not started | |
| P5-09 | Trusted dependencies only; Modrinth lookup sends mod ids and hashes only | 01 s12, 04 | 5 | Not started | |
| P5-10 | Crash V3: real crash vs normal close; facts from crash report, logs, hs_err | 03 5.4, 01 s13 | 5 | Not started | |
| P5-11 | Confidence levels (confirmed / strong / possible / unknown); safe auto-repairs only; five-section crash screen | 03 5.4, 01 s13 | 5 | Not started | |
| P5-12 | Crash test corpus, CI defect planting, privacy scrub; nothing uploaded | 01 s13 | 5 | Not started | |
| P5-13 | Guided import: one source, Everything or Choose, confirm, verify; never claims unsupported data | 03 5.5, 01 s11 | 5 | Not started | |
| P5-14 | Imports create Origin profiles; the everyday "drop a jar" path is checked too | 04, `audit/launcher-systems.md` s1 | 5 | Not started | |

### Phase 6 - 1.8.9 performance
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P6-01 | OptiFine M5 setting key names verified (E3) | `audit/perf-189.md` s7 | 6 | Not started | |
| P6-02 | Production-like 1.8.9 stack in CI (Forge, OptiFine, TexFix, BetterFps, Temurin 8) | `audit/perf-189.md` s6 | 6 | Not started | |
| P6-03 | Benchmark mode: avg FPS, 1 % lows, frame times, memory, other useful metrics; repeatable scenarios | 03 6.1, 01 s15 | 6 | Not started | |
| P6-04 | Benchmark summarizer, proven able to fail | 03 6.1 | 6 | Not started | |
| P6-05 | Competitive / Balanced / Quality presets implemented and tested (undo, no silent override) | 03 6.2, 01 s15 | 6 | Not started | |
| P6-06 | HUD rendering optimised (measured) | 03 6.3 | 6 | Not started | |
| P6-07 | Particles optimised (measured) | 03 6.3 | 6 | Not started | |
| P6-08 | Cosmetics optimised (measured) | 03 6.3 | 6 | Not started | |
| P6-09 | Blur optimised (measured) | 03 6.3 | 6 | Not started | |
| P6-10 | Other measured bottlenecks (rendering overhead, memory allocation, background work, entity culling, font) | 03 6.3, 01 s15 | 6 | Not started | |
| P6-11 | Java runtime and GC chosen from measurements | 01 s15 | 6 | Not started | Temurin 8u504 shipped unmeasured |
| P6-12 | Real GPU: one command for Will + a readable report; agent run on his PC; no CI number passed off as gameplay | 03 6.4, 00, 04 | 6 | Not started | |

### Phase 7 - Mod updates and close
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| P7-01 | Dependency audit: current, latest compatible stable, breaking changes | 03 P7, 01 s14 | 7 | Not started | |
| P7-02 | Covered versions boot-tested in CI, or the limitation reported and verified another way | 03 P7 | 7 | Not started | |
| P7-03 | Updates applied through the 8-step gate | 03 P7 | 7 | Not started | |
| P7-04 | Failing updates held or rejected, never shipped | 03 P7 | 7 | Not started | |
| P7-05 | Launcher-installed mod pins (catalog) audited the same way | 01 s14 | 7 | Not started | |
| P7-06 | Final pass: every row here has a final status and evidence | 01 s20-21, 03 | 7 | Not started | |
| P7-07 | One launcher release `launcher-v1.0.66`+ once tests, CI boots and the judge pass | 04 | 7 | Not started | |
| P7-08 | Real-install checks after the release (launcher, import, menus on real GPU) | 04 | 7 | Not started | |
| F-01 | Final report to Will (phases, judge scores, verified features, issues, blocked items, real benchmarks, what to test) | 04 Reports, 01 s20-21 | 7 | Not started | |
| F-02 | Additional recommendations list (not built) | 01 s22 | 7 | Not started | |

### Backlog folded in (2026-10-20 playtest list = B-01-B-25; other open ledger items = B-26+)
Status mapping from `docs/overhaul/audit/ledger.md`: Completed+verified = Done and tested; Implemented-unverified = Done but untested;
Partial = In progress; Not implemented = Not started.
| ID | Requirement | Source | Phase | Status | Evidence |
|---|---|---|---|---|---|
| B-01 | 1.8.9 loading screen: Cancel overlaps the status line | ledger s1 #1 | 4 | Done but untested | `eec9fd958` GuiConnectingMixin |
| B-02 | 1.8.9 black resource-pack screen | ledger s1 #2 | 4 | Done but untested | `eec9fd958` |
| B-03 | 1.8.9 Color Saturation flips the game | ledger s1 #3 | 4 | Done but untested | `eec9fd958` shader y flip |
| B-04 | 1.8.9 shader lag | ledger s1 #4 | 6 | In progress | `e2dfcbfd2` allowlist; no 1.8.9-specific fix |
| B-05 | Nametags behind water / NPCs (1.8.9) | ledger s1 #5 | 4 | Done but untested | `eec9fd958` |
| B-06 | Discord link goes to the website (1.8.9) | ledger s1 #6 | 4 | Done but untested | `268e8174f` |
| B-07 | Missing performance options (1.8.9) | ledger s1 #7 | 6 | In progress | `fc88c86ca`; which options Will meant is unrecorded |
| B-08 | Transparent Menus setting | ledger s1 #8 | 4 | Done but untested | `8e2433780` |
| B-09 | Cosmetics halo white centre, not smooth | ledger s1 #9 | 4 | Done but untested | `268e8174f`, `e7671debe` |
| B-10 | Jagged main-menu icons | ledger s1 #10 | 4 | Done but untested | `d2282be60` (superseded by P4-04) |
| B-11 | 1.8.9 launch / port issue | ledger s1 #11 | 0 | In progress | `b08c9128a`, `c4d67dfb5`; fix branch = P0-01 |
| B-12 | Esc menu pixelation (1.8.9) | ledger s1 #12 | 6 | Done but untested | `268e8174f` MenuBlur |
| B-13 | Lunar-level 1.8.9 performance | ledger s1 #13 | 6 | In progress | `fc88c86ca` only |
| B-14 | Per-world waypoints, every version | ledger s1 #14 | 4 | Done but untested | `8e2433780` |
| B-15 | Minecraft-style icons on main-menu buttons | ledger s1 #15 | 4 | Done but untested | `e7671debe`, `d2282be60` (restyled by P4-04) |
| B-16 | Hide Realms where it can't work | ledger s1 #16 | 4 | Done but untested | `e7671debe` |
| B-17 | 1.18 cosmetics halo | ledger s1 #17 | 4 | Done but untested | `e7671debe` |
| B-18 | Singleplayer dirt overlay 1.18 / 1.20 | ledger s1 #18 | 4 | In progress | 1.20 `e7671debe`; 1.18 unclear |
| B-19 | 26.x Friends crash | ledger s1 #19 | 4 | Done but untested | `21d1a91ab` |
| B-20 | 26.x cosmetics drag / switch | ledger s1 #20 | 4 | Done but untested | `21d1a91ab` (drag); "switch" from notes only |
| B-21 | 26.x Origin menus unclickable | ledger s1 #21 | 4 | Done but untested | `21d1a91ab` |
| B-22 | Launcher Mods-tab switch bug | ledger s1 #22 | 3 | Done but untested | `4a3d75f7e`, `123654d96` |
| B-23 | Website link box in the launcher | ledger s1 #23 | 1 | Done but untested | `90a2e62f8` (becomes the Store card, P1-08) |
| B-24 | Settings porting on import | ledger s1 #24 | 5 | Done but untested | `10f92ea05`, `369263285` |
| B-25 | Items for 1.16.5 / 1.17 named in the list header | ledger s1 #25 | 4 | Needs my decision | original list not stored |
| B-26 | PR #34: Settings Sync section, installer Customize option, Discord always-on | ledger s3 | 3 | Not started | held from Phase 0 (Decisions) |
| B-27 | MEMORY.md / CLAUDE.md drift (missing 10-19 entries, stale roadmap and brand) | ledger s0, s5 | 0 | Not started | = P0-09, P0-10 |
| B-28 | After-shaders pass for world overlays on modules other than 1.21.1 | ledger s2 | after 7 | In progress | `46ad446bc` (1.21.1 only); not requested in 01-04 |
| B-29 | 1.21.10+ emoji truncated by `(char) event.codepoint()` | ledger s2 | 4 | Not started | 1.21.10, 26.1, 26.3 |
| B-30 | Loading screen's own first frame 637 ms; prepare-stage pauses with many mods | ledger s2 | 4 | Not started | |
| B-31 | Never booted in CI: 1.21.2, 1.21.3, 1.21.7, 1.20.1, 26.1.1 | ledger s2 | 7 | Done but untested | = P7-02 |
| B-32 | Origin AI website helper (shipped 10-21) to remove | ledger s2 | 1 | Not started | `311f4fc72`..`de6b3eb6a`; = P1-04 |
| B-33 | Light theme | ledger s2 | after 7 | Not started | not requested in 01-04 |
| B-34 | Panorama grade is an approximation, not a true desaturate | ledger s2 | after 7 | Not started | notes only |
| B-35 | Crash system v2 roadmap item | ledger s2 | 5 | In progress | crash window v2 on main; = P5-10/11 |
| B-36 | 1.21.9 (pulled) and 1.18.1 (no stable Sodium/Iris) | ledger s2 | - | Blocked | `src/mods/VERSIONS.md` |
| B-37 | Held-sync stale etag | ledger s2 | - | Done and tested | `08410fd6`, harness-measured |
| B-38 | boot-smoke 1.18.2-1.20.4 on real Java 17 | ledger s2 | - | Done and tested | merge `25173c5a`, run 39268523584 |
| B-39 | Loading bars never go backward (pre-1.20) | ledger s2 | - | Done and tested | merge `bf9cc864`, test in build-check |
| B-40 | Launcher mod profiles (Profiles page, Join server) | ledger s2 | 5 | Done but untested | MEMORY 2026-10-18; profile-vs-version UX decided in Phase 3 |
| B-41 | Settings Sync comment says "off by default" (stale or conflict) | ledger s2 | 3 | Not started | `SettingsPage.xaml` ~279 |
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
