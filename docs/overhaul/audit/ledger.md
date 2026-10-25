# Origin: previously requested work, status ledger (audit 2026-10-25)

Statuses were checked against **origin/main @ de6b3eb6a** (2026-10-21), using the detached audit worktree `scratchpad/audit-wt`.
Latest tag: `launcher-v1.0.65` = d2282be60 (2026-10-20). origin/main has 9 website-only commits after it (Origin AI helper, 10-21).
Status key: Completed+verified / Implemented-unverified (code on main, Will's in-game pass outstanding) / Partial / Broken / Not implemented / Blocked / N/A.
"notes only" = no cheap code check was done.

## 0. Repo-state findings (read first)
- The **local `main` checkout is 480 commits behind origin/main** (HEAD efdfdb880, 2026-10-18). The CLAUDE.md that loads in the main checkout is stale: it says v1.0.55, while origin/main CLAUDE.md says v1.0.61. Both are behind the real latest tag, v1.0.65.
- **origin/main CLAUDE.md "Current state" is stale.** It stops at v1.0.61 and says nothing about v1.0.62 to v1.0.65 or the 10-21 Origin AI helper. Its roadmap still lists "cosmetics outer layer, then tag v1.0.57", but that work has merged.
- **origin/main MEMORY.md ends at "2026-10-20 Released launcher-v1.0.61" (line 5473).** It has no entries for the 10-20 playtest round (v1.0.62 to v1.0.65), the speed mods, the icons, or Origin AI (10-21).
- **3 MEMORY entries exist only as uncommitted text in the stale main checkout** and are not on origin/main: "2026-10-19 Loading → main-menu hand-off v2", "2026-10-19 Loading bars never go backward", and "2026-10-19 Failed-reload guard + boot-smoke on real Java 17". These must be carried over by hand, or they are lost.
- Open PR: **#34** "Settings Sync is easy to find (and in setup), Discord always shows Origin Client" (branch integrate/sync-and-presence-2026-10-18, opened 10-18, unmerged). No open issues.
- TODO/FIXME in `src/`: **0**.

## 1. Will's 2026-10-20 playtest list (memory: paused-playtest-backlog-2026-10-20.md)
The two paused worktrees (agent-af2df8266a0d6b8aa for 1.8.9, agent-afa2b156c517d5602 for 26.x) are now clean, and their branches are merged (ahead=0). Their work shipped as a035dac7d and 5634f67ac in v1.0.62 to v1.0.65.

| # | item | source | status | evidence |
|---|---|---|---|---|
| 1 | 1.8.9 loading-screen overlap (Cancel over status line) | backlog l.22 | Implemented-unverified | eec9fd958 GuiConnectingMixin (clamps Cancel ≥36 px under status line) |
| 2 | 1.8.9 black resource pack screen | backlog l.22 | Implemented-unverified | eec9fd958 GuiScreenBackgroundMixin (in-world drawBackground → drawWorldBackground) |
| 3 | 1.8.9 Color Saturation flips the game | backlog l.22 | Implemented-unverified | eec9fd958 origin_color_grade.vsh / motion_blur.vsh y flip |
| 4 | 1.8.9 shader lag | backlog l.22 | Partial / unverified | e2dfcbfd2 shader allowlist and incompatible-pack warning; MEMORY 10-20 "Shader speed-ups on every version" (v1.0.58 to v1.0.60). No 1.8.9-specific lag fix found. |
| 5 | Nametags behind water/NPCs (1.8.9) | backlog l.22 | Implemented-unverified | eec9fd958 WorldLabel nametag depth write |
| 6 | Discord link → website (1.8.9) | backlog l.22 | Implemented-unverified | 268e8174f DiscordPresence "site button" |
| 7 | Missing performance options (1.8.9) | backlog l.22 | Partial | fc88c86ca adds Pack Icon Downscale and Disable Text Shadow (1.8.9 only). Which options Will found missing is not recorded. |
| 8 | Transparent Menus setting | backlog l.22 | Implemented-unverified | 8e2433780; "Transparent Menus" present in all 21 modules |
| 9 | Cosmetics halo white centre + not smooth | backlog l.22 | Implemented-unverified | 268e8174f (1.8.9 halo glow alpha, smooth preview); e7671debe GlowRenderType 1.16.5 to 1.20.4 |
| 10 | Jagged main-menu icons | backlog l.22 | Implemented-unverified | 268e8174f crisp dock icons; e7516681a / f2ea85866 / d2282be60 pixel dock icons (v1.0.65) |
| 11 | 1.8.9 launch/port issue | backlog l.22 | Partial | b08c9128a / c4d67dfb5: capped heap, Temurin 8. **Unmerged:** claude/launch-crash-fix(+1) and launch-crash-fix2(+2) (import mods can't block launch; other clients' core files never imported). Their files (ImportedModFit.cs, ClientCoreMods.cs) are absent on main. |
| 12 | Esc pixelation (1.8.9; added after the pause) | backlog l.18 | Implemented-unverified | 268e8174f MenuBlur "smoother Esc blur" |
| 13 | Lunar-style 1.8.9 massive perf (research Lunar) | backlog l.19 | Partial | fc88c86ca cuts Origin's own per-frame waste only. No Lunar research note or doc found. |
| 14 | Per-world waypoints (all versions) | backlog l.22 | Implemented-unverified | 8e2433780; WaypointScope in all 21 modules |
| 15 | Minecraft-style icons on main-menu buttons | backlog l.22 | Implemented-unverified | e7671debe + d2282be60 (dock = colour pixel; mod menu went back to mono vector) |
| 16 | Hide Realms where unsupported | backlog l.22 | Implemented-unverified | e7671debe "Realms hidden where it cannot join" |
| 17 | 1.18 cosmetics halo | backlog l.22 | Implemented-unverified | e7671debe 1.18.2 GlowRenderType + ModelCosmetic |
| 18 | Singleplayer dirt overlay 1.18 / 1.20 | backlog l.22 | 1.20: Implemented-unverified. 1.18: unclear | e7671debe rewrote the 1.20 AbstractSelectionListMixin. 1.18.2's mixin already cleared renderTopAndBottom on 10-19 (03283a91b) before Will's report, and no fix after the report was found. |
| 19 | 26.x Friends crash | backlog l.22 | Implemented-unverified | 21d1a91ab double-blur guard on 26.2/26.3 |
| 20 | 26.x cosmetics drag / switch | backlog l.22 | Implemented-unverified | 21d1a91ab (SDL3 button numbering, so drag starts). The "switch" part is from notes only. |
| 21 | 26.x Origin menus unclickable | backlog l.22 | Implemented-unverified | 21d1a91ab (26.3; 26.1/26.2 kept identical) |
| 22 | Launcher Mods-tab switch bug | backlog l.22 | Implemented-unverified | 4a3d75f7e nav click closes the version picker; 123654d96 / 332775ddf Home never blank |
| 23 | Website link box (launcher) | backlog l.22 | Implemented-unverified | 90a2e62f8 / 4a3d75f7e "Website chip / button" |
| 24 | Settings porting (import) not working | backlog l.22 | Implemented-unverified | 10f92ea05 / 369263285 Lunar 1.8 → 1.8.9, era-filtered settings, 26.x options created |
| 25 | Items for 1.16.5 / 1.17 (named in the list header) | backlog l.11 | status from notes only | The full original message is not stored. The only 1.16.5 to 1.17.1 work after the report is e7671debe (icons, halo, Realms). |

## 2. Roadmap / "awaiting Will's pass" (origin/main CLAUDE.md l.188-206)
| item | source | status | evidence |
|---|---|---|---|
| 1.8.9 + 26.1 to 26.3 in-game pass (#32) | CLAUDE.md l.194,201 | Implemented-unverified | Live since v1.0.4x. PR #32 merged. |
| v1.0.61 launcher pass (version arrowing, Play, 4K art, signed-in head, Lunar import, crash window, installer) | CLAUDE.md l.190 | Implemented-unverified | tag launcher-v1.0.61 |
| Launcher mod profiles (Profiles page, Join server) | CLAUDE.md l.191 | Implemented-unverified. Open UX question. | MEMORY l.5312. Profile ignored when another version is picked: Will's call. |
| Loading → menu freeze fix (1.19.2 on real HW) | CLAUDE.md l.193 | Superseded by hand-off v2 | Hand-off v2 shipped fc10e6d2 on 10-19; Will confirmed smooth on 1.21.1 (uncommitted MEMORY entry). 1.19.2 on real hardware: unverified. |
| Launch v2 / loading / Pack Hub / FPS graph on every version | CLAUDE.md l.194 | Implemented-unverified | 1c094fe0b (#33), abb337733, 73b244f16, 42bd88426 |
| Settings Sync on + instant saving (v1.0.54) | CLAUDE.md l.195 | Implemented-unverified | MEMORY l.5252. SettingsPage.xaml l.279 comment still says "off by default": a stale comment or a conflict, not checked. |
| Cosmetics over the outer skin layer + head/face 3-axis | CLAUDE.md l.203 | Implemented-unverified (merged; roadmap text stale) | d9226c6da / 08f3e0717 / ce6b7ca45 on main |
| Screen-open lag (snappy menus) | CLAUDE.md l.205 | Implemented-unverified (ported) | 1.21.1 (MEMORY l.5231), then bfd7b59cc "cheaper menus" across 182 files, e445fd28f |
| Button Style setting (Origin/Minecraft) + full vanilla menus | CLAUDE.md l.206 | Implemented-unverified | "Button Style" in shared + all 21 modules; the 10-19 "clean look" commits. No MEMORY entry. |
| Cosmetics round 10-18 (cape mesh, wear/unwear, Adjust, halo atom spin, live theme hover) | MEMORY l.5282 | Implemented-unverified | "spin" channel in CosmeticDef on all 20 modules |
| Origin+ membership: in-game cape/badge + launcher no-ads | MEMORY l.9 | Implemented-unverified (notes + grep) | cape-plus in 20 modules; OriginPlusMembership.cs + PromoBanner. Store `live: true`. |
| 1.21.9 (pulled), 1.18.1 (no stable Sodium/Iris) | CLAUDE.md l.202 | Blocked / N/A | VERSIONS.md l.188 |
| Crash system v2 | CLAUDE.md l.202 | Partial | 3a178cc66 / fc4ca2fb4 crash window v2 on main. Roadmap still unchecked. launch-crash-fix2 (blame other clients) unmerged. |
| Light theme | CLAUDE.md l.202 | Not implemented | no trace |
| After-shaders pass for world overlays on other modules | MEMORY l.5357 / grep l.827 | Partial (1.21.1 only) | 46ad446bc. MEMORY: 1.16.5 to 1.21 and 1.21.6 to 1.21.11 "not done" |
| 1.21.10+ emoji truncated by `(char) event.codepoint()` | MEMORY l.5365 "Open" | Not implemented | still in WaypointScreen / OriginModMenuScreen on 1.21.10, 26.1, 26.3 |
| 26.3 `@plus` empty circle | MEMORY l.5365 | Completed per notes | MEMORY 10-19 "Launcher icons clipped… 26.3 plus" |
| Held-sync stale etag | MEMORY l.5365 | Completed+verified | 08410fd6, harness-measured |
| Loading bar backward jump pre-1.20 | uncommitted MEMORY | Completed (per uncommitted note) | merge bf9cc864 on main; 1.4M-check test in build-check |
| Loading screen's own first frame 637 ms; prepare-stage pauses with many mods | uncommitted MEMORY "Open follow-ups" | Not implemented | no trace |
| boot-smoke for 1.18.2 to 1.20.4 on real Java 17 | uncommitted MEMORY | Completed+verified | merge 25173c5a, run 39268523584 green |
| Never booted in CI: 1.21.2, 1.21.3, 1.21.7, 1.20.1, 26.1.1 (shader speed-ups) | MEMORY l.5420 | Implemented-unverified | notes only |
| Origin AI website helper (10-21) | origin/main 311f4fc72 to de6b3eb6a | Implemented, not in MEMORY or CLAUDE.md | 9 commits after v1.0.65. Website auto-deploys from main. |
| UI redesign propagation + legacy icon fallback (07-15 note) | ui-redesign-open-items.md #4 | Completed (stale note) | MEMORY 10-11 "Every live version brought onto the 1.21.1 design" |
| Panorama grade = approximation, not true desaturate | ui-redesign-open-items.md #5 | Not implemented / status from notes only | no trace checked |

## 3. Unmerged branches (vs origin/main) and what each holds
| branch | ahead | date | holds | verdict |
|---|---|---|---|---|
| claude/launch-crash-fix (+origin) | 1 | 10-20 | 97708fbc3: importing mods can't stop the game starting; read-only empty account mods folder no longer blocks the merge | **Real unmerged fix.** Its worktree is agent-a736495a534e79a18. |
| claude/launch-crash-fix2 (+origin) | 2 | 10-20 | the above + 7fa5d69ca: Feather/Lunar/Badlion/LabyMod core files never imported; CrashAnalyzer blames them | **Real unmerged fix.** Its worktree is agent-ac51e61ffd87d4d29. It is a superset of launch-crash-fix. |
| claude/shots-and-update-hash (+origin) | 5 | 10-19 | Self-update zip checked against its SHA-256; a failed update check never blocks play; stale update zips cleaned | **Real, unmerged.** Its worktree is agent-af73f2c98dcb667bb. UpdateService on main has no hash check. |
| integrate/sync-and-presence-2026-10-18 (PR #34) | 8 | 10-18 | merges feat/settings-sync-section (Settings Sync section + installer Customize option) and feat/discord-presence-always-on (no Discord Status toggle, reconnect, IPC deadlines) | **Unmerged, PR open.** Main still has the "Discord Status" toggle (Mods.java:811). Worktrees: int-wt, iw, sync-wt, wt. |
| claude/import-flow | 4 | 10-19 | Lunar-style import drawer | Superseded: cherry-picked to main (68e9a63ff, then f3894b77c made it a full Import page) |
| claude/launcher-poster-pages | 1 | 10-19 | WIP Profiles cards + form, launch count ("held for page-by-page rounds") | Held WIP, not on main |
| worktree-agent-a9e4d8a0c3b814a8b | 1 | 10-19 | merge of launcher-import-grid into launcher-poster | WIP, likely superseded |
| claude/shader-port-261-mc261 | 1 | 10-20 | TEMP dev-shots: boot the 26.1 jar on MC 26.1 | Never merge; scratch |
| claude/reload-guard-java17-before (+origin), origin/claude/handoff-settle-gate-before, origin/claude/friends-icon-fix-before / -before2 | 1 | 10-18/19 | "before" measurement baselines | Scratch, never merge |
| origin/claude/dev-shots | 2 | 10-21 | CI screenshot publish branch | Infra, not work |
| backup/local-wip-2026-10-18 | 2 | 10-18 | backup of local work before moving to latest main | Check before deleting |
| origin/claude/trusting-shannon-k4vzd2 | 30 | 10-17 | Claude Lean + website mini-game | Superseded (Lean, agents and website are on main in newer form) |
| origin/claude/fervent-feynman-u4xm34 | 187 | 10-13 | 1.8.9 + 26.x port merge | Superseded (#32 merged) |
| origin/claude/gracious-pascal-m8yp6n | 1 | 10-13 | origin-website skill | Probably stale; not checked |
| origin/claude/practical-lamport-2cn1ob, claude/menu-redesign-lunar-local | 3 | 10-12 | cosmetics tabs / Mod Menu Layout; dev-shots tweak | Superseded by the 10-12 redesign; not checked line by line |
| integrate/test-2026-10-13 | 2 | 10-13 | sign-in fix test build | Stale |
| claude/bold-faraday-024c25 | 1 | 10-09 | ignore artifacts/ and graphify-out/ | Tiny; not on main (artifacts/ and graphify-out/ show untracked in the checkout) |
| claude/origin-mod-menu-layout-1nrfhf (+origin) | 9 | 07-26 | 1.21.11 scoreboard port etc. | Stale (July) |
| origin/claude/minecraft-client-startup-31680x, claude/website-simple-alt, origin/claude/main-menu-button-styling-2i6rfu, legacy-1.8.9-rewrite | 1-26 | July | July auth / website / perf catalog / 1.8.9 rewrite | Stale (July), superseded |

## 4. Worktrees (145 total, none prunable)
- **125 are clean, and their HEAD is already in origin/main.** These are leftovers and safe to clean up; the list is in the appendix. They include both paused 10-20 worktrees and 102 `agent-*` worktrees.
- Worktrees holding commits that are not on origin/main: agent-ac51e61ffd87d4d29 (launch-crash-fix2), agent-a736495a534e79a18 (launch-crash-fix), agent-af73f2c98dcb667bb (shots-and-update-hash), wt-import-flow (import-flow, superseded), int-wt / iw (PR #34 integration, iw is detached), sync-wt, wt (PR #34 feature branches).
- Worktrees with uncommitted changes:
  - `%TEMP%\oit`, `%TEMP%\orel`, `%TEMP%\ocw`, `…\signin-wt`, `…\menu-redesign-wt`: 1,677 to 2,519 files show as **deleted** and nothing is modified. These are sparse or emptied checkouts (10-12/13) with no real work in them.
  - `…\icons-wt` (claude/icon-pixel-atlas, 10-11): +427 lines in 1.21.1 ModIcons.java / OriginDevShots / OriginClientMod (pixel icon atlas). Superseded by the 10-20 pixel icons on main.
  - `…\wt-before` (detached d4fcf82b4, 10-18): launcher RenderShots / SocialShots harness stubs. Scratch.
  - `…\wt-merge` (chore/memory-2026-10-19): 1 dirty entry; branch merged.
  - `Origin Store Preview` (detached, +2): CLAUDE.md edit only.
  - `.claude/worktrees/zen-franklin-c954bb` and `epic-cartwright-cdc6e3` (July): CLAUDE.md one-line edit plus the Codex AGENTS.md / CODEX_CONTEXT.md. Stale.

## 5. Uncommitted changes in the main checkout (stale main, 480 behind)
- Group A, **website Origin AI helper (early draft)**: website/index.html, store.html, css/site.css, terms.html (modified), plus untracked assistant.html / css/assistant.css / js/assistant.js. origin/main already has a newer version of all of these (nav link, "Minecraft helper" card, Terms §10, and assistant.js 105 lines different). **Superseded; drop it.**
- Group B, **MEMORY.md**: 60 added lines, the 3 dated 2026-10-19 entries that are missing from origin/main (see §0). **Keep: re-apply them onto origin/main's MEMORY.md.**
- Group C, **.claude/settings.json**: 22 lines of read-only permission allows (unzip -l/-p, javap, Get-*, Select-String, JDK25 javap, a few MCP read tools). With .claude/.permissions-scan (untracked stamp), this is the output of the weekly permission scan. Not on origin/main.
- Untracked output folders: artifacts/ (oni_talespire, setup-preview, title-parity, title-every-version.jpg) and graphify-out/ (4 dated runs). These are tool output, not source.

## Appendix: all worktrees (last-commit date | dirty files | commits ahead of origin/main | branch | folder)
```
2026-10-21|dirty=0|ahead=0|(detached)|origin-ai-site-publish
2026-10-20|dirty=0|ahead=2|claude/launch-crash-fix2|agent-ac51e61ffd87d4d29
2026-10-20|dirty=0|ahead=1|claude/launch-crash-fix|agent-a736495a534e79a18
2026-10-20|dirty=0|ahead=0|worktree-agent-afa2b156c517d5602|agent-afa2b156c517d5602
2026-10-20|dirty=0|ahead=0|worktree-agent-af2df8266a0d6b8aa|agent-af2df8266a0d6b8aa
2026-10-20|dirty=0|ahead=0|worktree-agent-ae08c4781f87ae159|agent-ae08c4781f87ae159
2026-10-20|dirty=0|ahead=0|worktree-agent-aa5c497fb2c75272f|agent-aa5c497fb2c75272f
2026-10-20|dirty=0|ahead=0|worktree-agent-a8d9e808ae1debe8a|agent-a8d9e808ae1debe8a
2026-10-20|dirty=0|ahead=0|worktree-agent-a7d4c122faff151a1|agent-a7d4c122faff151a1
2026-10-20|dirty=0|ahead=0|worktree-agent-a7472345745e1682a|agent-a7472345745e1682a
2026-10-20|dirty=0|ahead=0|worktree-agent-a0b1faa2c5b3e535a|agent-a0b1faa2c5b3e535a
2026-10-20|dirty=0|ahead=0|setup-preview|agent-ab9921b6d97ad4e5e
2026-10-20|dirty=0|ahead=0|release/shader-speed|agent-ae8ea33b4246c1be2
2026-10-20|dirty=0|ahead=0|release/next|agent-a6d6b51987ff7ab06
2026-10-20|dirty=0|ahead=0|release/merge-20261020|agent-afd6609d170bb4894
2026-10-20|dirty=0|ahead=0|recipe-1214|recipe-1214
2026-10-20|dirty=0|ahead=0|main-merge|port-skills
2026-10-20|dirty=0|ahead=0|integration/v1.0.62|agent-a7ed899137ee4b7ea
2026-10-20|dirty=0|ahead=0|integ-release|agent-ac6185a5e8f3a0420
2026-10-20|dirty=0|ahead=0|docs/shader-memory|agent-a86ed1a48c62a166d
2026-10-20|dirty=0|ahead=0|claude/waypoints-transparent|agent-a4233da17e6bcade4
2026-10-20|dirty=0|ahead=0|claude/uninstall-match|agent-a096dc1b57b7ab5ff
2026-10-20|dirty=0|ahead=0|claude/speed-mods|agent-ad5e689b19baf11be
2026-10-20|dirty=0|ahead=0|claude/shader-port-pre120|agent-aa66d65857f89d894
2026-10-20|dirty=0|ahead=0|claude/shader-port-262|agent-a90f617cadc632f2c
2026-10-20|dirty=0|ahead=0|claude/shader-port-121|agent-a96e8ead956cd4245
2026-10-20|dirty=0|ahead=0|claude/shader-port-1216|agent-aedd8e57b989d0d1e
2026-10-20|dirty=0|ahead=0|claude/shader-port-12111|agent-a8c3274adbe3ec513
2026-10-20|dirty=0|ahead=0|claude/shader-port-120|agent-a082fee54ceb3fb7f
2026-10-20|dirty=0|ahead=0|claude/shader-fix-1214|agent-a696d6377ce97afc3
2026-10-20|dirty=0|ahead=0|claude/profile-icons-hq|agent-aed84202e4a7342e3
2026-10-20|dirty=0|ahead=0|claude/port-skills|agent-af2786c4addbe4c30
2026-10-20|dirty=0|ahead=0|claude/player-heads|agent-a60be66a95e2034b1
2026-10-20|dirty=0|ahead=0|claude/launcher-polish|agent-aec9fe4d5ccb26a2c
2026-10-20|dirty=0|ahead=0|claude/launcher-cohesion|agent-a3cb3fb0b8cd9851f
2026-10-20|dirty=0|ahead=0|claude/ingame-port-26|agent-a68928e8ca0624595
2026-10-20|dirty=0|ahead=0|claude/ingame-integration|agent-a50f846e0fa27f4ca
2026-10-20|dirty=0|ahead=0|claude/import-fix|agent-a6018ed227aaf1b6b
2026-10-20|dirty=0|ahead=0|claude/icons-shadow|agent-aca36c78143400f09
2026-10-20|dirty=0|ahead=0|claude/hq-family-art|agent-a4f13d49a3672bac9
2026-10-20|dirty=0|ahead=0|claude/home-tooltips-version-sheet|agent-a3b751c5bab02d086
2026-10-20|dirty=0|ahead=0|claude/home-blank-fix|agent-af66501f51d1c50d0
2026-10-20|dirty=0|ahead=0|claude/fix-press-scale-transform|agent-ad1b9a79cbb715e52
2026-10-20|dirty=0|ahead=0|claude/fix-familytheme-frozen-brush|agent-a9d0a03f57f94190a
2026-10-20|dirty=0|ahead=0|claude/crash-friendly|agent-a58b39055fcbcf6dc
2026-10-20|dirty=0|ahead=0|claude/189-shader-allowlist|agent-af19789588353a657
2026-10-20|dirty=0|ahead=0|(detached)|agent-ae76e6628eac4e165
2026-10-19|dirty=0|ahead=5|claude/shots-and-update-hash|agent-af73f2c98dcb667bb
2026-10-19|dirty=0|ahead=4|claude/import-flow|wt-import-flow
2026-10-19|dirty=0|ahead=0|tt-work|agent-a9eece9bb699b2b49
2026-10-19|dirty=0|ahead=0|tmp-flow|agent-a8647f3917c5bbc7a
2026-10-19|dirty=0|ahead=0|poster-int|agent-a02939baffdc61cc4
2026-10-19|dirty=0|ahead=0|launcher-slider|agent-a30eec5832255aee8
2026-10-19|dirty=0|ahead=0|land-guard|agent-a99a9db5aa80cee6c
2026-10-19|dirty=0|ahead=0|land-bar|agent-aaed1b0dcea0c522c
2026-10-19|dirty=0|ahead=0|integ|agent-a6a4b8be29a75b319
2026-10-19|dirty=0|ahead=0|integ-poster|agent-a7f074bc9c4943d14
2026-10-19|dirty=0|ahead=0|integ-poster-2|agent-a9e4d8a0c3b814a8b
2026-10-19|dirty=0|ahead=0|fix119|agent-a5d50d80edac0583a
2026-10-19|dirty=0|ahead=0|fix/sodium-rail|agent-a6b3b937e10b04f90
2026-10-19|dirty=0|ahead=0|fix-title-116|agent-a964b39f32be98f86
2026-10-19|dirty=0|ahead=0|fix-ingame-low|agent-a3e7f690757a1a962
2026-10-19|dirty=0|ahead=0|fix-189-lows|agent-ae858024c3d97b74d
2026-10-19|dirty=0|ahead=0|fix-12110|agent-ad16eacab81d633b4
2026-10-19|dirty=0|ahead=0|fix-120-skin|agent-a054e6babd47eec15
2026-10-19|dirty=0|ahead=0|claude/trim-claude-md|wt-trim
2026-10-19|dirty=0|ahead=0|claude/sodium-mixin-targets|wt-sodium
2026-10-19|dirty=0|ahead=0|claude/shader-speed|wt-shader-speed
2026-10-19|dirty=0|ahead=0|claude/reload-guard-java17|agent-ab7f24a3fa53368c5
2026-10-19|dirty=0|ahead=0|claude/release-notes-filter|agent-abdbbd608248c179f
2026-10-19|dirty=0|ahead=0|claude/problem-helper|agent-ae62a477aa06f4a58
2026-10-19|dirty=0|ahead=0|claude/loading-bar-monotonic|agent-a53129ac050e75e1f
2026-10-19|dirty=0|ahead=0|claude/launcher-tooltips|origin-tooltips-wt
2026-10-19|dirty=0|ahead=0|claude/launcher-poster|agent-a3b27048b358518ba
2026-10-19|dirty=0|ahead=0|claude/launcher-page-settings|agent-a4ade4f0257e34960
2026-10-19|dirty=0|ahead=0|claude/launcher-page-mods|agent-a7d8601b8fc542a55
2026-10-19|dirty=0|ahead=0|claude/launcher-page-import|agent-a83f1020b1a4292aa
2026-10-19|dirty=0|ahead=0|claude/launcher-page-friends|agent-a97c932daf578fecd
2026-10-19|dirty=0|ahead=0|claude/launcher-import-grid|agent-aa203fe2c46d151d5
2026-10-19|dirty=0|ahead=0|claude/integration-sodium|agent-ad4273c1d295e2662
2026-10-19|dirty=0|ahead=0|claude/integration-1058|agent-a3d59bec4684117d1
2026-10-19|dirty=0|ahead=0|claude/ingame-port-189|agent-a2fab0f3e97ef516e
2026-10-19|dirty=0|ahead=0|claude/ingame-port-1214|agent-a95170296d5246584
2026-10-19|dirty=0|ahead=0|claude/ingame-port-12110|agent-abf3317db38d925a4
2026-10-19|dirty=0|ahead=0|claude/ingame-port-120|agent-afdd13bf1bc101da5
2026-10-19|dirty=0|ahead=0|claude/ingame-port-119|agent-a99726945cd9da682
2026-10-19|dirty=0|ahead=0|claude/ingame-port-116|agent-a595d9a7495d8b3d4
2026-10-19|dirty=0|ahead=0|claude/ingame-clean|agent-aec6648d8eae2587a
2026-10-19|dirty=0|ahead=0|claude/import-stress|agent-a8573af1683aeba89
2026-10-19|dirty=0|ahead=0|claude/import-accuracy|agent-ae02c2b97b96d7f37
2026-10-19|dirty=0|ahead=0|claude/hud-thumbs-fixes|agent-a338d35b9171d4424
2026-10-19|dirty=0|ahead=0|claude/handoff-integration|agent-a42a5c77203f27920
2026-10-19|dirty=0|ahead=0|claude/faster-boot|agent-ae419f01a69eb345f
2026-10-19|dirty=0|ahead=0|claude/faster-boot-2|agent-a94413b3f774946a2
2026-10-19|dirty=0|ahead=0|claude/devshots-publish-fix|agent-abbbd5f33600137fb
2026-10-19|dirty=0|ahead=0|claude/ci-sodium-iris|agent-a87618448533bdf6c
2026-10-19|dirty=0|ahead=0|claude/ci-hardening|agent-abe058e51e290699a
2026-10-19|dirty=0|ahead=0|claude/button-style|agent-a2dc017c23f345707
2026-10-19|dirty=0|ahead=0|(detached)|wt-main
2026-10-19|dirty=0|ahead=0|(detached)|vfy-4673
2026-10-19|dirty=0|ahead=0|(detached)|agent-ae85ae62e71c2742c
2026-10-19|dirty=0|ahead=0|(detached)|agent-ace33ecbdbf0c5357
2026-10-19|dirty=0|ahead=0|(detached)|agent-a88dc83291b8caa6b
2026-10-19|dirty=0|ahead=0|(detached)|agent-a8491e17c4d2e0736
2026-10-19|dirty=0|ahead=0|(detached)|agent-a317488e2c9d823c4
2026-10-19|dirty=0|ahead=0|(detached)|Origin shader test
2026-10-18|dirty=4|ahead=0|(detached)|wt-before
2026-10-18|dirty=1|ahead=0|chore/memory-2026-10-19|wt-merge
2026-10-18|dirty=12|ahead=0|main|Origin Client
2026-10-18|dirty=0|ahead=8|integrate/sync-and-presence-2026-10-18|int-wt
2026-10-18|dirty=0|ahead=8|(detached)|iw
2026-10-18|dirty=0|ahead=3|feat/settings-sync-section|sync-wt
2026-10-18|dirty=0|ahead=3|feat/discord-presence-always-on|wt
2026-10-18|dirty=0|ahead=0|worktree-agent-aaca17054ae92da10|agent-aaca17054ae92da10
2026-10-18|dirty=0|ahead=0|redesign/social-launcher|wt-social-launcher
2026-10-18|dirty=0|ahead=0|redesign/social-ingame|wt-social-game
2026-10-18|dirty=0|ahead=0|redesign/launcher-site-match|wt-restyle
2026-10-18|dirty=0|ahead=0|merge/icon-fix|wt-iconfix
2026-10-18|dirty=0|ahead=0|fix/originpc-latency|wt-originpc
2026-10-18|dirty=0|ahead=0|claude/waypoint-over-shader-clouds|wt
2026-10-18|dirty=0|ahead=0|claude/social-port-d|wt-port-d
2026-10-18|dirty=0|ahead=0|claude/social-port-c|wt-port-c
2026-10-18|dirty=0|ahead=0|claude/social-port-b|wt-port-b
2026-10-18|dirty=0|ahead=0|claude/social-port-a|wt-port-a
2026-10-18|dirty=0|ahead=0|claude/plus-icon-263|wt
2026-10-18|dirty=0|ahead=0|claude/overlay-after-shaders|bold-faraday-024c25
2026-10-18|dirty=0|ahead=0|claude/import-wiring|agent-a07762254a7fd9c8b
2026-10-18|dirty=0|ahead=0|claude/import-screens|agent-a3424cf5c0727c6e3
2026-10-18|dirty=0|ahead=0|claude/import-bulk-engine|agent-a5d4dca0d161f4a39
2026-10-18|dirty=0|ahead=0|claude/hero-spacing-fix|hero-wt
2026-10-18|dirty=0|ahead=0|claude/held-sync-stale-etag|wt
2026-10-18|dirty=0|ahead=0|claude/handoff-port-modern|agent-ad7c7ee1eb3ec60a5
2026-10-18|dirty=0|ahead=0|claude/handoff-port-legacy|agent-ac8e6450ce3397114
2026-10-18|dirty=0|ahead=0|(detached)|wt-merge
2026-10-18|dirty=0|ahead=0|(detached)|origin-settle-gate
2026-10-13|dirty=2519|ahead=2|integrate/test-2026-10-13|oit
2026-10-13|dirty=2519|ahead=0|release/1.0.52|orel
2026-10-13|dirty=2516|ahead=0|(detached)|ocw
2026-10-13|dirty=2479|ahead=0|fix/second-account-signin|signin-wt
2026-10-13|dirty=0|ahead=0|fix/cyclebutton-origin-look|agent-ab6732e82e9c2001c
2026-10-12|dirty=1|ahead=2|(detached)|Origin Store Preview
2026-10-12|dirty=1677|ahead=3|claude/menu-redesign-lunar-local|menu-redesign-wt
2026-10-11|dirty=1907|ahead=0|claude/icon-pixel-atlas|icons-wt
2026-07-22|dirty=3|ahead=0|(detached)|zen-franklin-c954bb
2026-07-22|dirty=3|ahead=0|(detached)|epic-cartwright-cdc6e3
```
