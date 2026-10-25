# Phase 4 - In-game main menu and loading screens

Covers ORIGIN_PROGRESS P4-01 to P4-16 and backlog B-01-B-03, B-05, B-06, B-08-B-10, B-14-B-21, B-25, B-29, B-30, B-42, B-44-B-51.
Standing rules: `docs/overhaul/README.md` (**S4 Mod Menu frozen**, S5 logo, S6 mod code layout + `origin-port` skill, S1 testing).
Phase branch `claude/ovh-p4`; one unit branch per module wave.

## 1. Objectives
1. Redesign the Minecraft title screen to match the launcher (Workbench, "match launcher softness": rounded, softer surfaces), on
   1.21.1 first; verify design and function; then port version by version, respecting each era's menus and features.
2. Keep every vanilla function and version-specific button; Mods opens the existing `OriginModMenuScreen`; icons without nested
   boxes; Origin's custom backgrounds (Phase 2 pick); performance kept.
3. Every supported version, 1.8.9 especially, gets the Origin loading experience with the real logo, lightweight.
4. Verify the open in-game backlog items (2026-10-20 playtest list and roadmap "awaiting pass" items) and fix what fails.

## 2. Original requests
03 "PHASE 4" (all). 02 sections 7 (icons without boxes), 9 (custom loading screen on every version, real logo, lightweight),
10 (sanity check). 01 sections 1.1 (Mod Menu), 16 (main menu redesign), 18 (cross-version architecture), 19. 00 "match launcher
softness" (replaces the pixel-grid square-button rule for non-Mod-Menu surfaces). 04 Design section.

## 3. Inspect first (`audit/ingame-menus.md` is the map; re-check lines)
- 1.21.1 title: `versions/1.21.1/src/client/java/com/origin/client/client/mixin/TitleScreenMixin.java` (432 lines, priority 2000;
  `renderPanorama` TAIL, `renderBackground` HEAD cancel, logo redirect, `init` TAIL adds Mods / Cosmetics / Friends,
  `layoutCentered` by translation key, dock order, `REALMS_WORKS`), `render/TitleLayout.java` (all geometry constants),
  `OriginScreenRenderer` (`renderTitleBackdropOver`, `renderTitleAccountChip`, `renderTitleWordmark`, `renderLoading*`, `:172`
  fade, `:282` hold), `OriginButtonRenderer.java` (`originOwnsScreen()` ~87, `vanillaIconButton` ~132-156, header comment
  `:20-31` is stale), `OriginStyle.vanillaTitle()` / `vanillaButtons()`, `theme/OriginTheme.java` (module copies are truth;
  shared copy stale), `StartupTransition` (REVEAL_MS 560, TITLE_ENTRANCE_MS 1200), `mixin/loading/LoadingOverlayRevealMixin`,
  `TitleScreenFadeMixin`, `StartupInputMixin`.
- Per-module diff table: `audit/ingame-menus.md` section 1 (1.21 4 lines ... 26.3 149; pre-1.20 via per-module `Gfx`;
  1.8.9 = `hooks/MenuHooks.onGuiOpen` -> `gui/OriginTitleScreen.java`, `render/MenuPanorama`, `render/OriginLoadingRenderer`,
  `mixin/menus/GuiConnectingMixin`, `GuiDownloadTerrainMixin`, `MinecraftServerProgressMixin`, `MinecraftWorldLoadMixin`).
- Vanilla features per era: `audit/ingame-menus.md` section 2 (Realms, accessibility, language, copyright).
- Mod Menu entry points: section 3 (title Mods button; Right Shift -> `HudEditorScreen` -> MODS). Frozen dimensions: section 4.
- Notes to read first (auto-memory, `C:\Users\Will\.claude\projects\C--Users-Will-Documents-Minecraft-Mods-Origin-Client\memory\<name>.md`): `deferred-gui-draw-call-cost` (1.21.6+ per-fill render states), `smooth-ui-baked-masks`,
  `1211-render-split-port-patterns`, `button-mixin-final-renderwidget` (1.21.11), `263-pipeline-startup-reload-trap`,
  `1214-gpu-sdf-port-boot-fixes`, `versions-md-porting-corrections`, `legacy-forge-port-recipe`. `src/mods/VERSIONS.md`.

## 4. Agent units
| Unit | Agent | Scope | Order |
|---|---|---|---|
| U4.0 Coverage audit | researcher | every live module: title method, loading surfaces (startup, resource reload, world load, connect, terrain), logo source, panorama source | first, read-only |
| U4.1 1.21.1 title | fabric-mod-dev | `versions/1.21.1` title files (section 3) | after U4.0 |
| U4.2 1.21.1 loading | fabric-mod-dev | `versions/1.21.1` loading renderers + transition | parallel with U4.1 (separate worktree; disjoint files) |
| U4.3 1.21.1 gate | verifier + design-reviewer | dev-shots, boot-smoke, Mod Menu compare, real-install run | after U4.1 + U4.2 |
| U4.4 Ports | `mc-port-parity` per era group, dispatched through the `origin-port` skill (its brief template and scoped CI loop) | one wave per family (below) | after U4.3 passes |
| U4.5 Backlog verification | verifier (+ fabric-mod-dev for fixes) | the B-items listed at the top | parallel with U4.4, per module after its wave |
| U4.6 Emoji truncation | fabric-mod-dev | 1.21.10, 26.1, 26.3 char input | any time; no visual change |
Waves (03: port version by version; each wave finished, gated and merged before the next starts so a broken port never spreads):
W1 1.20 + 1.20.1 (covered) + 1.20.2 + 1.20.4 · W2 26.1 + 26.2 + 26.3 · W3 1.21, 1.21.2/3/4, 1.21.6/7/8, 1.21.10, 1.21.11 ·
W4 pre-1.20 `Gfx` modules 1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4 · W5 1.8.9 (Forge, hand re-implementation). Inside a wave,
modules may run in parallel worktrees (one module per writer). Judge: `design-reviewer` (the visual parts) and `architect` (boot
safety, performance); the lower score counts. Shared files go through `src/mods/shared` + `sync.py` (S6), one writer.

## 5. Changes to make
**U4.1 (1.21.1 title).** Apply the spec's in-game column (`docs/ORIGIN_UI_SYSTEM.md`): rounded soft panels for the button column and
dock (SDF panels via `OriginUi.panel`, radii from the spec's in-game values), icons per the Phase 2 pick with no box inside a box
(the dock's 20x20 vanilla squares go), the official logo + wordmark through the central reference (Phase 2), backgrounds per the
Phase 2 pick, entrance motion kept within `TITLE_ENTRANCE_MS`. Keep: Singleplayer, Multiplayer, Options, Mods (-> `OriginModMenuScreen`),
Cosmetics, Friends, language, accessibility, quit, Realms only where it works (`REALMS_WORKS`, 26.3 only today), the player chip,
copyright line where the era has one, the Button Style and Main Menu Style settings (vanilla title + the 80x20 "Origin Mods" button
still work), fail-soft latches (`broken`, `backdropBroken`). `originOwnsScreen()` must still return false for the Mod Menu's screens
so its look cannot change. Fix the stale header comments (`OriginButtonRenderer.java:20-31`, `OriginScreenRenderer` header).

**U4.2 (1.21.1 loading).** Startup, resource-reload, world-load, connect and terrain screens use the spec's loading indicator, the
official logo and the Origin backdrop; bars never go backward (keep the bf9cc864 test green); cut the loading screen's own 637 ms
first frame (B-30: measure with `dev-shots framelog=true` before and after; the target is first Origin frame within one vanilla frame
of the loading overlay appearing, report the measured numbers). No new per-frame allocation (check with the frame log).

**U4.4 (ports).** Use the `origin-port` skill per module. Same look, each era's own mechanism (CLAUDE.md mandate 5): 1.21.6+ deferred
GUI (bake panel masks, keep draw calls per frame at or under the 1.21.1 count + 10 %); 1.21.11 `renderWidget` final rule; 26.x
extract/submit era and the startup-reload pipeline trap; pre-1.20 through the module's `Gfx` wrapper with no blur dependency; 1.8.9
inside `OriginTitleScreen` + `OriginLoadingRenderer` (Forge splash can't host Origin drawing: the Origin loading look starts at the first
frame Origin can draw and replays the hand-off), lightweight (no shader dependency, no per-frame texture uploads). Each module's
version-specific buttons stay (section 2 table).

**U4.5 (backlog).** For each listed B-item: reproduce on the module named in `audit/ledger.md` section 1-2 (dev-shots, boot-smoke, or
`runClient` / the installed game on Will's PC under S1), mark Done and tested with evidence, or fix it in the wave's branch. B-25
(1.16.5/1.17 items): the original list is lost; record what was checked and mark the rest "Needs my decision".

**U4.6.** Replace `(char) event.codepoint()` with code-point-safe insertion (`Character.toChars`) in `WaypointScreen` and the Mod Menu's
text input on 1.21.10, 26.1, 26.3 (input handling only: Mod Menu pixels unchanged).

## 6. Must NOT change
- **`OriginModMenuScreen`, `OriginDeck` and every screen they draw: no visual change on any module (S4, JUDGE auto-reject).**
  Its dimensions, tabs, Esc behaviour, Right Shift -> HUD editor -> MODS flow stay.
- Vanilla menu functions on every version; the Button Style / Main Menu Style settings; Terraformers Mod Menu stays installed (its
  title button stays hidden).
- Pre-1.20 modules and 1.8.9 stay `.no-shared-sync`; no module is moved to another loader or MC pin; no new mod dependency.
- Boot safety (mandate 4): every new hook fails soft to vanilla, never crashes; mixins keep their comment (target, why, what breaks).

## 7. Dependencies and parallel tracks
Phase 2 merged (spec, logo reference, picks). Track A after Phase 3 (the launcher look is the reference the menu matches; 03 order).
Track C (Phase 6) owns `versions/1.8.9` performance code: Phase 6's 1.8.9 changes merge before W5 starts, and W5 rebases on them.
Track D (Phase 7) updates a module's dependencies only after that module's wave merged.

## 8. Verification (per wave; 1.21.1 first)
- `.\gradlew.bat build` per module; `python tools/shared-sync/sync.py --check`; build-check green.
- `boot-smoke.yml modules=<wave>`: zero `Mixin apply .* failed`, Origin self-check lines present, reaches the title.
- `dev-shots.yml modules=<wave> set=design label=p4-<wave>` and `set=handoff video=true framelog=true`: title, every loading surface,
  hand-off; **`menu_*` shots pixel-compared with the module's `p0-baseline` shots** (script the compare; any changed pixel in a Mod Menu
  shot fails the wave unless it is outside the Mod Menu's frame).
- Draw-call / frame-time check from the frame log vs the `p0-baseline` run: no regression over 10 % on title or loading frames.
- Real install (S1): on Will's PC, `runClient` per wave (one module), or the installed game after the release, to confirm the menu on
  real GPU; never touch his worlds or saves, back up `options.txt` before any run that might change it.
- design-reviewer reads every wave's sheets against 1.21.1 (same look, era-fair differences only) and the spec.
- code-reviewer + verifier per wave (S2).

## 9. Completion criteria
1. 1.21.1 title and loading pass U4.3; every live module ported in waves W1-W5, each with boot-smoke + dev-shots evidence in
   ORIGIN_PROGRESS.
2. Every live module, 1.8.9 included, shows the Origin loading experience with the official logo (shot per module).
3. Mod Menu pixel-identical to `p0-baseline` on every module.
4. Every listed B-item is Done and tested, fixed, or explicitly Needs my decision with the reason.
5. **The judge passes Phase 4 (`docs/overhaul/JUDGE.md`: 95/100, every critical requirement passing, no auto-reject).**
6. Phase branch merged to `main` and pushed; ORIGIN_PROGRESS, MEMORY updated; short report. No release here (S8).

## 10. Needs Will
Nothing blocks the phase. "Needs testing": the menu feel at his GUI scale, the loading-to-menu hand-off on his GPU, and B-items
that are about feel (halo smoothness, Esc blur). Decided by the orchestrator (Will can override): B-25 handling above.
