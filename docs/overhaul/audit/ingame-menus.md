# In-game menus audit (origin/main, worktree de6b3eb6a, 2026-10-21)

Read-only. Every path is under `src/mods/` of the audit worktree unless noted. Earlier first-pass findings from the stale main checkout (Frost/glass/rings/OriginTitleScreen "Friends instead of Realms") are discarded.

## 0. Doc drift to know before redesigning
- CLAUDE.md "Brand" (mono, 3 rings), memory `ui-redesign-decisions` (Frost) and the header comments of `OriginButtonRenderer.java:20-31` ("Ion Jade/glass") and `OriginScreenRenderer.java` (header, "living rings") describe an OLDER title. The shipped title (Will 2026-10-19) is: stock Minecraft buttons + font, version's own panorama blurred + dim + vignette, real Origin logo + ORIGIN wordmark, player chip top-left, Singleplayer/Multiplayer full rows, Options|Mods pair, dock of 20x20 vanilla squares with Origin mono icons.
- `shared/.../OriginTheme.java` is a stale mono palette copy; the 21 per-module copies are identical (same md5) and 1.21.1 `overrides.txt` forks it. Treat the module copies as truth.

## 1. Title screen per live version (status: Implemented-unverified except where a dev shot exists)
Design truth: `versions/1.21.1/src/client/java/com/origin/client/client/mixin/TitleScreenMixin.java` (432 lines), `@Mixin(TitleScreen, priority=2000)`. Geometry in `render/TitleLayout.java` (CORNER 10, CORNER_SIZE 20, BUTTON_H 20, ROW_STEP 24, MIN_ROW_STEP 21, MAIN_W 200, DOCK 20, DOCK_GAP 4, DOCK_BOTTOM 14, MARK 34, MARK_ABOVE 60, WORD_ABOVE 16; block starts at round(sh*0.49); drops logo, then wordmark, then tightens rows when short).
Mechanics: `renderPanorama` TAIL -> `OriginScreenRenderer.renderTitleBackdropOver` + `renderTitleAccountChip`; `renderBackground` HEAD cancelled; logo redirect -> `renderTitleWordmark`; splash and version line suppressed; `init` TAIL adds Mods (-> `OriginModMenuScreen`), Cosmetics (`CosmeticsScreen`), Friends (`FriendsScreen`); `layoutCentered` finds vanilla buttons by translation key; dock order realms (only if works), friends, cosmetics, language, accessibility, quit; icons `@px-friends/@px-shirt/@px-language/@px-access/@px-quit/@px-realms`; 8px rise + 55ms stagger over TITLE_ENTRANCE_MS 1200. `REALMS_WORKS=false` everywhere except 26.3. Vanilla fallback: Main Menu Style setting (`OriginStyle.vanillaTitle()`) adds an 80x20 "Origin Mods" button at (4,4).

| Module(s) | Title method | Diff vs 1.21.1 (changed lines) |
|---|---|---|
| 1.21.1 | mixin, reference | 0 |
| 1.21 | same mixin | 4 |
| 1.21.4 | same mixin | 6 |
| 1.21.6 / 1.21.8 | mixin; panorama hook in `ScreenBackgroundMixin` | 44 |
| 1.21.10 / 1.21.11 | mixin; own panorama hooks | 63 / 65 |
| 1.20 | mixin | 79 |
| 1.20.2 / 1.20.4 | mixin | 56 |
| 26.1 / 26.2 / 26.3 | mixin, extract* render era; own hooks | 131 / 147 / 149 |
| 1.16.5-1.19.4 | mixin, draws through per-module `Gfx`; ImageButton + translation key lookup | not diffed line-by-line |
| 1.8.9 | NOT a mixin: `hooks/MenuHooks.onGuiOpen` swaps GuiMainMenu for `gui/OriginTitleScreen.java` (368 lines); `render/MenuPanorama`, `render/OriginLoadingRenderer` | re-implementation, not a port |

Inconsistencies vs 1.21.1: Realms only on 26.3; 1.8.9 is a replacement screen so any vanilla-title change (Realms/accessibility/language) must be re-done by hand there; pre-1.20 modules are `.no-shared-sync` so title changes there are manual per module; 1.21.6/1.21.8 put the backdrop hook outside the title mixin.

## 2. Vanilla title features by era (likely, from vanilla knowledge + the code's key handling; not read from each jar)
| Era | Realms btn | Accessibility btn | Language btn | Copyright |
|---|---|---|---|---|
| 1.8.9 | none (Realms row exists) | none | language button beside Options | plain text, bottom right |
| 1.16.5 | yes | icon (ImageButton) | icon (ImageButton) | plain text |
| 1.17.1-1.18.2 | yes | icon | icon | 1.18+ clickable text |
| 1.19.x-1.20.x | yes | SpriteIconButton from 1.20 (likely) | same | clickable text |
| 1.21.x | yes | SpriteIconButton | SpriteIconButton | clickable text |
| 26.x | yes (26.3 works) | yes | yes | yes |
Origin hides/relocates all of these into the dock and suppresses the vanilla version/splash.

## 3. How the main menu opens the Mod Menu (Completed in code, Implemented-unverified for most versions)
- Title: "Mods" button -> `new OriginModMenuScreen()` directly (all Fabric modules; 1.8.9 `OriginTitleScreen`).
- In-world: Right Shift (`OriginKeyBindings.openModMenu`) -> `HudEditorScreen(true)` (`OriginClientMod.java` ~381-385: only when `client.screen == null`). The HUD editor's MODS button opens `OriginModMenuScreen`; shortcuts: `@title-options` -> settings tab, waypoints, `@shirt` -> Cosmetics, `@user` -> Friends (`HudEditorScreen.java` ~268-275). 1.8.9: `hooks/ModMenuHooks.openIfPressed`. Right Shift is ignored while any screen is open.
- Terraformers Mod Menu: bundled on 1.21.1 (`build.gradle:128`, modmenu_version 11.0.4); launcher installs it on every Fabric version (`PerformanceModCatalog.Data.cs`, 31 entries). Its title button is hidden by Origin; its pause-menu button likely remains.

## 4. Mod Menu (APPROVED - do not redesign)
`gui/OriginModMenuScreen.java` (~1659 lines), identical across all 20 modules. W 920, H 580, M 24, HEAD_H 64, BAR_Y 80, BODY_Y 128, GAP 16, CARD_H 184, ROW_H 44, OPT_H 40, SEARCH_W 176. Tabs (line ~510): Mods, Settings, Waypoints, Cosmetics, Friends; EDIT HUD + close in header; Esc goes up one level; Right Shift closes. Look: Workbench palette in `gui/OriginDeck.java` (deepslate/emerald/parchment), MSDF Inter, SDF rounded panels (`OriginUi.panel`, radii 1/2/3 px). Third-party installed-mod list: Origin has none of its own; Terraformers Mod Menu is the separate list. Redesign must keep: the title "Mods" entry, the Right Shift -> HUD editor -> MODS flow, and these dimensions/tabs.

## 5. Loading and launch-to-menu continuity (Implemented-unverified on real hardware for several)
- `StartupTransition` (REVEAL_MS 560, TITLE_ENTRANCE_MS 1200); settle-hold in `mixin/loading/LoadingOverlayRevealMixin`; `TitleScreenFadeMixin`; `StartupInputMixin` (1.21.1); launcher `Motion.Intro` mirrors REVEAL_MS.
- Loading renderers: `OriginScreenRenderer.renderLoading*`, `renderLoadingHold` (282), `renderLoadingFade` (172).
- 1.8.9: Forge splash cannot host Origin drawing, so `OriginLoadingRenderer` replays the ending itself; `mixin/menus/GuiConnectingMixin`, `GuiDownloadTerrainMixin`, `MinecraftServerProgressMixin`, `MinecraftWorldLoadMixin` cover connect/terrain/world-load.
- Fail-soft: latches `OriginScreenRenderer.broken`, `backdropBroken`, `OriginButtonRenderer.broken`; `defaultRequire:0`.
- Known open per CLAUDE.md: loading->menu freeze fix awaiting Will's in-game pass; 1.19.2 not checked on real hardware.

## 6. Shared tokens
`theme/OriginTheme.java` (252 lines, identical on 21 modules, runtime-static so cosmetics themes can retheme). Own windows use Workbench palette, Inter MSDF, radii 1/2/3 px. Title uses vanilla sprites (`widget/button`, `button_highlighted`, `button_disabled`) via `OriginButtonRenderer.vanillaIconButton` (~132-156): icon size min(12, h-8), white or 0xFFA0A0A0 when disabled. `OriginButtonRenderer.originOwnsScreen()` (~87) decides Origin-vs-vanilla button look per screen; `OriginStyle.vanillaButtons()` is always true. Hover easing HOVER_IN_MS/HOVER_OUT_MS.

## 7. Paused worktrees (Partial/Blocked: nothing left to finish)
- `.claude/worktrees/agent-af2df8266a0d6b8aa` (branch worktree-agent-af2df8266a0d6b8aa): `git status --short` empty, `git diff --stat` empty, HEAD fc88c86ca (2026-10-20, "1.8.9 perf: cached GUI scale..."). 0 commits ahead, 45 behind origin/main; HEAD is an ancestor of origin/main. The 1.8.9 GuiConnectingMixin loading-overlap rewrite is NOT here (no uncommitted or unique work). Not finishable from this folder; the rewrite would have to be redone.
- `.claude/worktrees/agent-afa2b156c517d5602`: clean, HEAD 21d1a91ab (2026-10-20, "26.x: 26.3 Origin menus accept clicks again (SDL3...)"). 0 ahead, 47 behind, ancestor of origin/main. That commit already landed. The 26.x renderer / menu-click / cosmetics follow-ups are not present as unmerged work.
- One stash exists in the shared repo: `stash@{0}: On legacy-1.8.9-rewrite: epitaxy: pre-switch from legacy-1.8.9-rewrite` (unrelated branch; not inspected, likely old).
- Both worktrees are stale mirrors of old main; safe candidates for Will to delete (not done: read-only).

## 8. Branch `claude/menu-redesign-lunar-local` (Partial, tooling only)
3 commits (3aceb4565, 570055e6c, 7d05474ac, 2026-10-12) touching `.github/workflows/dev-shots.yml`, `design/menu-redesign/FEATURES.md`, `OriginDevShots.java`. FEATURES.md is a feature inventory for a MOD MENU + HUD editor redesign (Lunar vs Origin scoring; verdicts like top chips, header search, live preview, snap guides, right-click to settings, Ctrl+Z). It is NOT a title/main-menu redesign, and it targets the approved mod menu, so most of it conflicts with the "do not redesign mod menu" decision. No title code on that branch.

## 9. Dev shots (branch origin/claude/dev-shots, read via git show/ls-tree only)
Newest title shots per version, dir -> source commit date: 1.8.9-icons3 / 1.16.5-icons3 / 1.21.1-icons3 / 1.21.11-icons3 (source f2ea8586607b, 2026-10-20); 1.8.9-icons4 exists (source d2282be6026533a1028b072bfd99afbe199b7087, newer). 1.17.1, 1.19.2/.3/.4, 26.1, 26.2, 26.3: `-rel-noperf` (3eec248d6172, 2026-10-20); 1.18.2 and 1.20: `-oldbatch` (e7671debe8e4, 2026-10-20); 1.20.2 `-sdm` (2026-10-19); 1.20.4 `-barafter-film`; 1.21 `-integ`; 1.21.4 `-rg-hf`; 1.21.6/1.21.8 `-lows`; 1.21.10 `-p2-clau...`. Each dir has SOURCE.txt, `_sheet.jpg`, `title_1920x1080_s0.png`, `menu_*`, `rshift_*`. I did not view any image (so no visual verification of any version by me). Sources dated 2026-10-19 or earlier predate the 2026-10-19 title restyle in part: likely stale for 1.20.2, 1.20.4, 1.21, 1.21.4, 1.21.6, 1.21.8, 1.21.10.
