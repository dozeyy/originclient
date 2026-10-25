# Phase 2 - Unified design system (Workbench, refined)

Covers ORIGIN_PROGRESS P2-01 to P2-18. Standing rules: `docs/overhaul/README.md` (S4 Mod Menu, S5 logo, S11 new deps).
Phase branch `claude/ovh-p2`. The logo is decided (04). The four refinement proposals are previewed first and decided by the
orchestrator under 04's authorization ("details within the approved design"), each recorded so Will can override.

## 1. Objectives
1. Replace every recreated Origin logo with the official files (decided, below), through one central logo reference that every
   surface uses, so the fake logo can't come back (launcher, installer and uninstaller, in-game title and loading, website).
2. Preview the refinement decisions and pick the option that best meets 02's own wording, judged before it is built: Store placement, account pill, version-card number
   treatment, main-menu icons without boxes (plus the smaller open calls in section 9).
3. Write the one Workbench spec both the launcher and the Minecraft main menu follow: colours, typography, component sizing,
   spacing, corner radii, buttons, dropdowns, navigation, icons, hover/press/selected/disabled/focus states, motion, backgrounds,
   loading indicators. Shared as a documented spec plus detector scripts, never as a shared runtime dependency.

## 2. Original requests
03 "PHASE 2 - UNIFIED DESIGN SYSTEM" (all lines). 02 sections 1 (real logo, central reference, ask on ambiguity), 2 (system,
sizing, interactions), 3, 5, 6, 7 (the four proposals: version cards, account pill, Store, menu icons), 11 Phase 2 "Propose ... show previews ... wait for approval". 01 section 4
(aesthetic aims and the avoid list), section 5 (shared tokens, no shared runtime UI dependencies, Mod Menu exception, launcher to
game continuity). 00: main menu matches launcher softness; soft accents; neutral numeral; Workbench refined, not reinvented.
04: the logo decision and the list of recreations to replace; Design section (premium, not gamer-y; avoid neon, glow, gradients,
oversized cards, decorative boxes); "may decide alone: details within the approved design".

## 3. Inspect first
- `docs/overhaul/design/concepts.md` section A (Workbench tokens: palette dark, soft accents, type scale 12/13.5/16/22/36, radii
  card 12 / control 8 / dialog 16 / pills round / in-game 3 GUI px, 8 px grid, motion 120/80/220/140 ms, cubic-bezier(.2,.8,.2,1)),
  `A-Workbench-1..4.jpg`, `concepts-mock.html` (editable mock; its ring logo is fake).
- Launcher tokens, `audit/launcher-ui.md` section 2: `Theme/Colors.xaml`, `Typography.xaml` (Inter static TTFs), `Spacing.xaml`
  (sizes, radii, motion, shadows), `Surfaces.xaml`, `Brand.xaml` (`Effect.OriginGlow` jade #4BC8AE: conflicts with "no hue in glows"),
  hex and font-size bypasses listed there; `UI/FamilyTheme.cs` per-family hues.
- In-game tokens, `audit/ingame-menus.md` sections 0, 4, 6: `theme/OriginTheme.java` (shared copy stale; the 21 module copies are
  identical; 1.21.1 forks it in `overrides.txt`), `gui/OriginDeck.java` (Mod Menu palette), `OriginButtonRenderer.java`, `TitleLayout.java`.
- Stale spec to replace: `docs/ORIGIN_UI_SYSTEM.md` (still says Monocraft in the launcher). Keep `docs/MOTION.md` (launch journey).
- **Logo decision (Will, 04, 2026-10-25):** the official logo IS
  `src/mods/shared/src/client/resources/assets/originclient/textures/ui/origin_logo.png` (three tilted rings, 256x256; same art as
  `src/OriginLauncher.App/Assets/origin.ico`); the wordmark IS `src/OriginLauncher.App/Assets/Brand/wordmark.png`. Use these exact
  files everywhere, never redrawn. Recreations to replace (04's list): `UI/Controls/OriginMark.xaml`, the `StartupIntro.xaml` rings,
  `src/OriginSetup/UI/OrbitMark.xaml`, the website's inline logo symbols and `website/js/world.js:396-425`, `OriginUi.mark()` (mods),
  "Mark B" in `ModIcons.cs:81-91`. Also fix the "Mod ID" placeholder `icon.png` in 1.21.10, 1.12.2, 1.21.5 and shared (it becomes the
  official logo). `tools/loading-screen/generate_wordmark.py` must render from the official file or be retired.

## 4. Agent units (in order)
**Step 1 - in parallel.**
- **U2.1 Logo replacement map** - `researcher`, read-only. For every recreation above plus a repo-wide search (XAML Ellipse/Path
  groups near "Origin"/"Mark"/"Orbit", SVG `<circle>`/`<ellipse>` rings, Java code drawing rings, PNGs under logo-like names whose
  SHA-256 differs from the official file): path:line, which surface shows it, and what the official file replaces it with (size, the
  scaling filter: the PNG is pixel art at 256 px, so launcher/installer scale with high-quality filtering from a pre-scaled size set,
  in-game uses nearest at integer scales). Output `docs/overhaul/p2-logo-map.md`. No question for Will.
- **U2.2 Refinement previews** - `design-director` (opus), worktree `claude/ovh-p2-previews`. Static preview images (HTML mock or a
  throwaway launcher preview window; rendered in CI or on Will's PC under README S1), 2-3 options each, from the Workbench tokens in
  `design/concepts.md`, using the official logo files:
  1. **Store placement** (02 section 6): top-bar right cluster beside the account pill / Home side card / Settings-only link.
  2. **Account pill** (02 section 5): compact rounded rectangle, head at the pill height minus 8 px, name truncates; vs current.
  3. **Version-card number treatment** (02 section 3): number set into the artwork (bottom-left, large, neutral, readable over art)
     vs a small badge vs a separate label row. Neutral white/grey only (00: accent numeral rejected).
  4. **Main-menu icons without boxes** (02 section 7, 00 "match launcher softness"): glyph-only on soft rounded buttons vs glyph
     beside the label, at 1.21.1 menu scale (GUI scale 2 and 4).
  5. **"Origin's custom backgrounds"** (03 Phase 4): the version panorama the menu uses today vs the launcher's version art.
  Output: `docs/overhaul/p2-previews/<n>-<topic>/option-<x>.png` + one `_sheet.jpg`. The design-director recommends one option per
  topic with the 02 sentence it satisfies; a `design-reviewer` (separate context) confirms or flips each pick against 02 and the
  JUDGE.md auto-rejects (generic AI look, oversized containers, empty space). The orchestrator records the picks in ORIGIN_PROGRESS
  Decisions as "decided overnight, Will can override" and keeps the sheet for his morning look.

**Step 2 - after the picks are recorded.**
- **U2.3 The spec** - `design-director` writes, `design-reviewer` checks. Rewrite `docs/ORIGIN_UI_SYSTEM.md` as the single source
  for both the launcher and the in-game menus (keep it under ~400 lines; tables over prose). Sections: colour roles (dark base,
  soft accents only on icon tiles, status dots, badges, progress, selected rings; never on large fills or glows), typography (Inter
  in the launcher; in-game uses Minecraft's font with the size and weight rules that map onto the launcher scale), component sizing
  (sized to content: no bar taller than its content + padding), 8 px spacing grid, radii, buttons (primary / secondary / ghost /
  icon), dropdowns, navigation (top bar, tabs, rail), icons (one family, line weight, size steps, no boxes inside boxes), states
  (hover, press, selected, disabled, focus ring, loading), motion (durations + easing; link `docs/MOTION.md` for the launch journey),
  backgrounds, loading indicators, the avoid-list from 01 section 4, the launcher-to-game continuity rules (01 section 5), and the
  **Mod Menu exception** (`OriginModMenuScreen` and `OriginDeck` keep their current look; the spec documents them as frozen).
  Each token gets its launcher resource name and its `OriginTheme` constant, side by side.
- **U2.4 Central logo reference** - `desktop-app-dev` (launcher + installer), `fabric-mod-dev` (mods) and `website-dev` (site), in
  parallel worktrees after U2.1 (they touch disjoint folders).
  - Launcher: one `OriginLogo` control reading one asset in `Assets/Brand/` through `Theme/Brand.xaml`; `OriginMark.xaml` and the
    `StartupIntro` rings either use it or are removed (the intro motion may stay if it animates the real asset).
  - Installer: `src/OriginSetup` links the same file (no second copy); `OrbitMark.xaml` replaced.
  - Mods: one texture path constant in shared code, every title/loading screen reads it; `tools/loading-screen/generate_wordmark.py`
    either renders from the official file or is retired.
  - Website: favicon and header mark point at the official asset (a website change: S7 judge rule applies).
  - Detector `tools/design/check_logo.py`: fails if any file outside the allow-list draws or ships an Origin mark (XAML/SVG ring
    geometry near "Origin" names, PNGs whose hash is not the official one under a logo-like name). Runs in build-check.
    `verify-auditor` plants a fake ring in a scratch branch and confirms the detector fails.
- **U2.5 Token detector** - `desktop-app-dev`: `tools/design/check_tokens.py` lists hard-coded hex colours and font sizes outside
  the token files (launcher XAML/C#; in-game outside `OriginTheme`/`OriginDeck`). In Phase 2 it reports only (baseline count in
  ORIGIN_PROGRESS); Phase 3 turns it into a gate once U3.0 clears the backlog. `verify-auditor` proves it can fail.
- **Glow**: drop the jade hue from `Effect.OriginGlow` (`Theme/Brand.xaml`) to keep hue in accents only - in U2.4, visible in the
  launcher-shots run.

## 5. Changes this phase makes (and only these)
Spec doc, two detector scripts (+ build-check wiring), the central logo reference on every surface, the glow hue removal, the
preview images. **No launcher layout or screen restyle lands here** (that is Phase 3); no in-game menu restyle (Phase 4).

## 6. Must NOT change
- `OriginModMenuScreen`, `OriginDeck` and everything they draw (S4). The logo swap must not touch Mod Menu pixels: prove with the
  `menu_*` dev-shots against the Phase 0 baseline.
  The Mod Menu already draws the official `origin_logo.png` (1.21.1: `OriginModMenuScreen.java:495` -> `OriginDeck.logo` `:430` ->
  `OriginUi.logo`; `OriginUi.java:537` falls back to the procedural `mark()` rings only if the texture is missing). Replacing that
  fallback (e.g. with a plain texture retry, never drawn rings) must leave the Mod Menu pixel-identical.
- Launcher behaviour, settings, saved data. The installer engine (`src/OriginSetup` install/uninstall logic).
- Workbench direction itself: refine, never reinvent (02 opening, 00). Neutral numeral (00).
- No new runtime dependency shared between launcher and mod (01 section 5): the shared thing is the spec + detectors.

## 7. Dependencies
Phase 1 closed and merged. U2.4 waits for U2.1's map. U2.3 waits for the recorded U2.2 picks. A new font file (e.g. Inter Display
for the numeral) is decided by the orchestrator only if the spec needs it, recorded in Decisions (S11).

**Parallel and agents.** Track A. U2.1 (researcher) and U2.2 (design-director) in parallel; then U2.3 (design-director, checked by
design-reviewer), U2.4 (desktop-app-dev, fabric-mod-dev, website-dev in three worktrees) and U2.5 (desktop-app-dev) in parallel.
Judge: `design-reviewer`. Tracks B, C, D may run at the same time; none of them touch logo files or `docs/ORIGIN_UI_SYSTEM.md`.

## 8. Verification
- `python tools/design/check_logo.py` and `check_tokens.py` run in build-check; `verify-auditor` report shows each fails on a
  planted defect (scratch branch, never merged).
- launcher-shots run (label `p2-logo`): every screen that shows the mark shows the official asset. installer-screens artifact from
  build-check: same. dev-shots `set=design` on 1.21.1, 1.20, 26.3, 1.16.5 and 1.8.9 (label `p2-logo`): title and loading screens
  show the official asset; `menu_*` shots pixel-identical to the Phase 0 baseline (or differ only where the old logo was, and the
  Mod Menu does not draw the logo).
- `design-reviewer` reads the spec against the previews Will picked and against `audit/launcher-ui.md` + `audit/ingame-menus.md`:
  every token has both a launcher and an in-game value; no contradictions with 02.
- `code-reviewer` + `verifier` in parallel on the merged phase branch (S2). Build-check green (tests >= the Phase 0 count).

## 9. Completion criteria
1. Every recreation in U2.1's map replaced by the official files; ORIGIN_PROGRESS P2 logo rows have evidence (shots + detector).
2. The five preview picks recorded in ORIGIN_PROGRESS Decisions (decided overnight, Will can override), sheet kept.
3. `docs/ORIGIN_UI_SYSTEM.md` rewritten and passed by design-reviewer; it covers every item in 03 Phase 2's list.
4. No surface draws a recreated mark: check_logo.py green in build-check, and proven able to fail.
5. check_tokens.py baseline count recorded.
6. **The judge passes Phase 2 (`docs/overhaul/JUDGE.md`: 95/100, no auto-reject).**
7. Phase branch merged to main and pushed by the close unit after review and verify pass (S8). Short phase report to Will.

## 10. Needs Will
Nothing blocks this phase. For his morning look (report "Needs testing"): the preview sheet and the five picks (he can override any),
and the logo on the launcher-shots, installer-screens and dev-shots sheets.
