# Phase 3 - Complete launcher redesign (and installer / uninstaller)

Covers ORIGIN_PROGRESS P3-01 to P3-18, B-22, B-26 (PR #34), B-41, B-43. Standing rules: `docs/overhaul/README.md` (S1 testing, S2, S4 n/a here,
S5 logo, S9 data). Phase branch `claude/ovh-p3`. Spec: `docs/ORIGIN_UI_SYSTEM.md` (Phase 2) and the picks in ORIGIN_PROGRESS Decisions.

## 1. Objectives (03's priority order)
1. Fixed translucent top navigation bar. 2. Correct numeral typography and centring. 3. Two-step version selector.
4. Clear installed / downloadable states. 5. Play installs what is missing, then launches (no "Download" step).
6. Version numbers integrated into the card artwork. 7. Correct player-head and account-pill proportions.
8. Store card placement and look (Phase 2 pick). 9. Consistent buttons, dropdowns, icons, container sizing.
10. Every remaining launcher screen, dialog and interaction. Plus: installer, setup and uninstaller in Workbench;
no oversized bars or empty space; every component sized to its content; everything tested.

## 2. Original requests
03 "PHASE 3" (all). 02 sections 2 (sizing, interactions), 3 (version cards), 4 (Play replaces Download, steps 1-6), 5 (head + pill),
6 (Store), 8 (installer/setup/uninstaller), 10 (visual sanity check: every screen, sizes, DPI, states). 01 sections 4, 6 (numbers and
alignment), 7 (top bar), 8 (version selector), 10 (Store card), 19 (QA). 00: neutral numeral; soft accents only.

## 3. Inspect first (path:line at `de6b3eb6a`; re-check before editing)
- Tokens and bypasses: `audit/launcher-ui.md` section 2 (Colors/Typography/Spacing/Surfaces/Brand.xaml; hex bypasses at
  `Buttons.xaml:413,427,436`, `Inputs.xaml:158`, `PromoBanner.xaml:90-93`, `VersionSelectOverlay.xaml:38`, `ModsPage.xaml:79-80`;
  literal FontSize list; `ImportViewModels.cs:562-568`, `HomePage.xaml.cs:1458`, `ModIcons.cs:47-51`). `CrashFallback.cs` literals
  are an intentional fail-safe: keep them literal, match values only.
- Top bar: `MainWindow.xaml:58-186` (`TopBar`, no background, Height `Size.Bar` 56; brand :60-65; `NavTabs` :69-94; `BarActions`
  :96-185 incl. WebsiteButton/Store :115-126, AccountButton chip :153-169); top scrim `ArtStage.cs:89-93`; page inset 96/80.
- Numeral: `ArtStage.cs:348` (`Narrow = 0.7` squash), `:397-410` sizing, `:418-422` position (42 % of window height), `:448`,
  `:453-467` glyph render; `Typography.xaml:5-10` says the squash was removed (it was not).
- Version selector: `VersionSelectOverlay.xaml` (193) / `.cs` (298); `Core/Versions/VersionCatalog.cs:54-81`;
  `VersionManager.cs:59` `OriginBuilds`; `HomePage.xaml.cs` ~565 `InitVersionSelection`, ~589 `OnVersionSelected`
  (`SettingsStore.Update`, `PrewarmSelectedVersion`). No installed state exists today (`audit/launcher-ui.md` section 5).
- Play / launch path: `audit/launcher-systems.md` sections 5-6 (Java runtime, downloads and integrity), `LaunchLoadingOverlay`,
  `Core/Launch/*`; grep `-i download` under `UI/` for any remaining "Download" wording.
- Account: `AccountButton` chip (`MainWindow.xaml:153-169`), `AccountSwitcherPanel`, head rendering (grep `Head`/`Skin` in `UI/`).
- Screens list: `audit/launcher-ui.md` section 1 (every row is in scope for U3.7).
- Installer: `src/OriginSetup/Theme/Setup.xaml`, `Theme/Typography.xaml`, `UI/SetupWindow.xaml`, `UI/UninstallWindow.xaml`,
  `UI/TitleBar.xaml`, `UI/OrbitMark.xaml` (replaced in Phase 2), `UI/RenderTour.cs` (installer-screens artifact), engine
  `Engine/*` incl. `Uninstaller.cs` (behaviour frozen); `launcher-release.yml:156` builds + smoke-tests the zip and installer;
  `tools/release/check_consistency.py` (launcher vs installer copies).
- PR #34 (`integrate/sync-and-presence-2026-10-18`, held from Phase 0): Settings Sync section, installer Customize option,
  Discord always-on; and the Phase 0 U0.3 `14b/24b` Settings Sync shots.

## 4. Agent units
| Unit | Agent | Scope | Order |
|---|---|---|---|
| U3.0 Tokens + base styles | ui-builder | `Theme/*.xaml`, hex/size bypasses, `check_tokens.py` becomes a build-check gate | first, alone |
| U3.1 Top bar + numeral | ui-builder | `MainWindow.xaml` TopBar, `ArtStage.cs` numeral | after U3.0 |
| U3.2 Version selector + installed state | desktop-app-dev (service) then ui-builder (overlay) | `Core/Versions/`, `VersionSelectOverlay` | parallel with U3.1 |
| U3.3 Play installs automatically | desktop-app-dev | `HomePage` Play path, `Core/Launch`, `LaunchLoadingOverlay` | after U3.2's service |
| U3.4 Version-card numbers | ui-builder | selector cards (Phase 2 pick) | after U3.2 |
| U3.5 Account pill + Store | ui-builder | `BarActions`, `AccountSwitcherPanel` head | after U3.1 |
| U3.7a Mods + Profiles | ui-builder | `ModsPage`, `ProfilesPage` | parallel, after U3.0 |
| U3.7b Settings, dialogs, toasts, tooltips, menus, inputs (+ PR #34) | ui-builder + desktop-app-dev | `SettingsPage`, `ConfirmOverlay`, `ToastQueue`, `ToolTips/Menus/Inputs.xaml` | parallel, after U3.0 |
| U3.7c Import, crash windows, launch overlay, intro, sign-in, friends | ui-builder | `ImportPanel`, `ImportPromptCard`, `CrashWindow`, `CrashReportWindow`, `LaunchLoadingOverlay`, `StartupIntro`, `MicrosoftSignInPanel`, `FriendsPanel` | parallel, after U3.0 |
| U3.8 Installer + uninstaller | ui-builder (+ desktop-app-dev for RenderTour) | `src/OriginSetup/Theme`, `UI/*` only | parallel, after U3.0 |
| U3.9 Sanity pass | design-reviewer, then ui-builder for fixes | everything above | last |
Parallel writers each get their own worktree (`isolation: "worktree"`); U3.7a/b/c and U3.8 touch disjoint files. A unit that must
change a shared style file (`Theme/*.xaml`) lands that change in a small U3.0 follow-up instead, so styles have one writer.

## 5. Changes to make
**U3.0** Apply the Phase 2 spec values to the launcher token files (one token per value; no new hard-coded hex or font size). Fix every
bypass in `audit/launcher-ui.md` section 2 except `CrashFallback.cs` (values matched, still literal). Make `tools/design/check_tokens.py`
a build-check gate at 0 bypasses (the Phase 2 baseline count goes to 0). Button, dropdown and input heights follow the spec's size steps
(one height per size step across every screen: JUDGE auto-reject "inconsistent button or dropdown sizing").

**U3.1** Top bar: a translucent surface behind `TopBar` (about 88 % alpha of the bar token, a restrained bottom border), fixed while
pages scroll. 01 section 7 asks for "subtle background blur where practical": use option d in `audit/launcher-ui.md` section 4 (a blurred
top strip of the current art, decoded once per art change, under the bar) if the frame trace shows no per-frame cost; otherwise option a
(solid, no blur). Never a live `BlurEffect` or DWM backdrop (re-rasterises every frame). Record which shipped and the measurement; bar height fits its
content (no taller than controls + spacing). Numeral: delete the `Narrow` squash (or make it a spec token at 1.0); size and centre it in
the free band between the bar bottom and the Home strip top (a Grid row or measured band, not `0.42 * height`); neutral ink only (00);
correct the `Typography.xaml:5-10` comment. Cache glyph `FormattedText` per size (no re-create on every SizeChanged if measurable).

**U3.2** Two-step selector: step 1 = family (the 8 `VersionGroup` tiles), step 2 = that family's versions, each with a state:
Installed / Not installed (downloads on Play) / Update ready if the Origin jar changed. State comes from a new `InstalledVersions`
service in `Core/Versions/` that checks disk off the UI thread (instance folder + version JSON + Origin mod jar present), caches the
result, and refreshes after a launch or an install. Selection still persists only through `SettingsStore.Update` in `OnVersionSelected`.
Unit tests for the service (installed / partial / missing / unreadable folder). Keyboard: arrows, Enter, Esc, back to step 1.

**U3.3** Play: the primary action always says "Play" (02 section 4). Pressing it on a missing or partial version runs the existing
provisioning path with visible progress (what is happening, percent, cancel) in the launch overlay, then launches. A failure shows a
clear message with a retry and leaves files intact (never silent; never a half-written instance marked installed). Newest launch action
for the same account still cancels the old one. Prove with `launch-time.yml` `versions=` one per family incl. 1.8.9 (first run =
nothing installed on the runner). The harness keys off Fabric's `Loading N mods` line, which Forge never prints: add a Forge marker
(e.g. the FML `Forge Mod Loader version` line or Origin's own boot line on 1.8.9) to the harness and its tests in `tools/ci/` first.

**U3.4** Version-card numbers per the Phase 2 pick: the number set into the card art, neutral, readable over light and dark art (art-
aware scrim under the number only), same type as the Home numeral.

**U3.5** Account pill: compact rounded rectangle; head = pill height minus 8 px, crisp (nearest-neighbour scaling of the 8x8 face +
hat layer), name truncates with tooltip; same proportions in the drawer. Store: placed per the Phase 2 pick, opens
`OriginLinks.Store` (Phase 1 check stays green), styled as a normal bar control, icon from the existing icon set or none.

**U3.7a-c** Restyle every remaining screen and state in `audit/launcher-ui.md` section 1 to the spec: buttons, dropdowns, inputs,
toggles, empty/loading/error states, dialogs, toasts, tooltips, context menus. Phase 3 re-skins the import and crash screens; their new
flows are Phase 5. U3.7b folds PR #34 in: port its Settings Sync section, installer Customize option and Discord always-on onto the
redesigned page (re-derive, do not merge the stale branch), then close PR #34 with a comment pointing at the merge commit.

**U3.8** Installer + uninstaller: Workbench tokens, official logo (Phase 2 central asset), same type, buttons, progress bar and
title bar as the launcher. Engine code unchanged.
The engine has no desktop-shortcut or Start-menu-shortcut flags (it always creates both), so the old shortcut checkboxes
are out of scope for U3.8; the page offers "Open Origin when Windows starts" instead.

**U3.9** Visual sanity check (02 section 10): design-reviewer reads every screen and state at 960x600, 1180x740 and 1920x1080, at
100 / 150 / 200 % DPI (add a DPI option to the launcher-shots harness `RenderShots` if it has none), and the installer-screens set. It
lists clipped text, misaligned edges, uneven sizes, empty space, oversized bars, inconsistent icons; ui-builder fixes; one re-shot.
UiTests: first click on every nav tab and Settings rail item lands on the right target; every dropdown opens, selects, closes by
keyboard. Startup: compare the launcher's startup trace (cold start to first frame, tab switch) against the Phase 0 baseline; a
regression over 10 % is a bug to fix, not a note (constraint: cold start < 3 s, 60 fps UI).

## 6. Must NOT change
- Launcher behaviour other than Play-installs and the selector states: settings meaning, account storage (DPAPI, device-bound),
  `SettingsStore.Update` as the only settings writer, one control = one job, per-account instance isolation, the shared mods
  folder rule (add / delete / rename-swap only), launch cancellation scope.
- Installer engine: install/uninstall steps, paths (`%LocalAppData%\Programs\OriginLauncher`, user data under `OriginLauncher`),
  silent-install switches, the uninstaller never deleting user data unless the player ticks it.
- The neutral numeral (00); hue only in soft accents (00); the official logo through the central reference (S5).
- Anything in-game (Phase 4) and any back-end flow (Phase 5).

## 7. Dependencies and parallel tracks
Needs Phase 2 merged (spec, picks, logo reference, detectors). Track A. Phase 5 (track B) may run at the same time in `Core/`;
its UI units start after U3.0 merges. Phase 6's presets UI (track C) starts after U3.0. If U3.3 and Phase 5.2 both touch the launch
path, U3.3 merges first and Phase 5 rebases.

## 8. Verification
- build-check green: `dotnet test` (all three projects) count >= Phase 2's, `check_tokens.py` 0, `check_logo.py` green,
  `check_consistency.py` green; `verify-auditor` proves `check_tokens.py` fails on a planted hex.
- launcher-shots run per unit (label `p3-u<n>`) and one final full run (label `p3-final`, all sizes and DPI); design-reviewer reads the
  `_sheet.jpg` sets against the spec and `p0-baseline`.
- installer-screens artifact from build-check: every installer and uninstaller page in Workbench, official logo.
- `launch-time.yml` with one version per family incl. 1.8.9, from empty: every launch reaches the in-game marker; the report quotes
  the per-version times. `launcher-release.yml` dispatch (no publish): the installer silent-install smoke passes.
- Real install (S1): Will's installed launcher only gets new code through the final release, so its real-install pass happens after
  the release (Phase 7 close). Before that a dev build may run on his PC only if `Core/OriginPaths.cs` supports a separate data
  folder (check; never point a dev build at his real data folder); otherwise CI only.
- code-reviewer + verifier per unit (S2); opus reviewer with the security checklist for U3.3 (downloads, integrity) and U3.8
  (installer).

## 9. Completion criteria
1. All ten 03 priorities and the installer/uninstaller done, each with a shot or test as evidence in ORIGIN_PROGRESS.
2. Play on a missing version installs and launches on every family in `launch-time` (1.8.9 included); failure path tested.
3. Zero token bypasses; zero recreated logos; no button/dropdown size outliers in the U3.9 list.
4. PR #34 content landed and the PR closed.
5. **The judge passes Phase 3 (`docs/overhaul/JUDGE.md`: 95/100, every critical requirement passing, no auto-reject).**
6. Phase branch merged to `main` and pushed; ORIGIN_PROGRESS, MEMORY updated; short report. No release here (S8).

## 10. Needs Will
Nothing blocks the phase. "Needs testing" in the report: the look and feel of the launcher at his real size and DPI, the Play
flow on his PC, the installer when the release ships. Orchestrator decisions to record (Will can override): how profiles and versions
share the selector (a profile pins a version; the selector shows the profile's version and offers "change version" inside the profile).
