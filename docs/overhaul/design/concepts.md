# Origin design directions (launcher and in-game title, one product)

Proposal only. Nothing in the project was changed. Visual mocks: `concepts.html` (same folder; `#a`, `#b`, `#c` open a tab directly).
Source of truth used: audit worktree (origin/main), 1.21.1 module for in-game, `origin/claude/dev-shots` 1.21.1-icons4 shots.

## The fixed point (approved, unchanged in every direction)
Origin Mod Menu (`gui/OriginModMenuScreen.java`, identical in all 20 modules).
- Palette "Workbench" (`OriginDeck`): panel #141816 at 94 %, card #1D2320, card hover #232A26, lines white 6 % / 10 %, text parchment #F1E9D2 (dim 68 %, mute 42 %), emerald #5FD09B (hover #83E2B5, on-accent #0A1710), red #D9675A, gold #E9C46A.
- Type: Inter 400/600 through MSDF; tabs and pills tracked uppercase.
- Shape: SDF rounded panels, radii 1/2/3 GUI px (`OriginTheme.RADIUS_SM/MD/LG`). 920 x 580, M 24, header 64, card 184, row 44.
- Parts: five tabs (Mods, Settings, Waypoints, Cosmetics, Friends), Edit HUD, filter pills, 4-column card grid, Options and Enabled/Disabled pills.
- Reached by: title "Mods" (direct) and Right Shift, then HUD editor, then Mods. Both stay.

## Shared across all three directions (the screens)
- **Home.** Fixed top nav (Home / Mods / Profiles / Settings) as a solid surface at about 88 % alpha with a 1 px hairline, no blur (a WPF BlurEffect over a VisualBrush is too costly for the 60 fps budget; Mica is whole-window only). Numeral: remove `ArtStage.Narrow = 0.7` (ArtStage.cs:348, used at :405-410, :448, :461-467) and centre it in the free band from the 56 px nav to the top of the bottom strip, instead of at 42 % of window height (:422). Neutral colour, never accent-tinted. Bottom strip: version button (thumb, number, tagline, chevron), profile pill, status line, Store card ("Origin Store / Capes and cosmetics", opens https://originclient.org/store through `Core.Promos.ExternalLink.Open`; replaces the Website button at MainWindow.xaml:115-126), My Mods switch, Play.
- **Version selector.** Step 1: grid of the 8 VersionCatalog families (26, 1.21, 1.20, 1.19, 1.18, 1.17, 1.16, 1.8) with art, range, tagline, and "n of m installed". Step 2: family art + description on the left, release list on the right with Installed / "Downloads when you press Play", footer "Install only" + "Play <version>". Selection writes `LauncherSettings.SelectedVersion` through `SettingsStore.Update` only.
- **Settings.** Left rail with soft-hue icon tiles (Performance, Game, Java, Origin Client, Startup), grouped cards on the right.
- **Import.** Dialog with stepper: Source, What to bring, Importing, Report. Scope rows: Worlds, Servers, Resource and shader packs, Settings and keybinds, Mods (only mods made for an Origin version). Copy: "Your old launcher keeps its copy".
- **Crash report.** Title in plain words ("Minecraft 1.21.1 closed"), reassurance line, Likely cause (culprit named when CrashAnalyzer has evidence), Recommended fix, "Fix automatically" (primary), "Play with Origin mods only", Technical details folded with Copy report.
- **Dialogs and toasts.** Action keeps its name ("Remove profile" then "Profile removed" with Undo). Danger buttons tinted, not filled.
- **In-game title.** Keep today's structure and order: player chip, logo + ORIGIN wordmark, Singleplayer, Multiplayer, Options | Mods pair, dock of squares (Friends, Cosmetics, Language, Accessibility, Quit), copyright line. Mods opens `OriginModMenuScreen` directly. Realms only on 26.3, as the first dock square. 1.8.9 (`gui/OriginTitleScreen.java`, not a mixin) drops Accessibility. Background: the version's own blurred panorama plus dim and vignette (no real blur before 1.20.5: dim only there).
- **Continuity.** Same background token from launcher to Minecraft loading to title, same logo position, one shared progress element driven by real progress. Reuses `StartupTransition` (REVEAL_MS 560) and `Motion.Intro`; nothing waits on a fixed-length animation, so no startup delay is added.

## A. Workbench (recommended)
The Mod Menu's own deepslate, parchment and emerald, grown into the whole product.
- **Personality:** grounded, composed, warm, exact.
- **Palette dark:** bg #0E1110, bg2 #121614, surface #1D2320, raised #232A26, nav glass rgba(20,24,22,.88), lines white 6 % / 11 %, text #F1E9D2, dim 68 %, muted 44 %, signal #5FD09B (on #0A1710; Play, toggles, progress, selected ring only), danger #D9675A, success #68C490, warning #E9C46A.
- **Palette light:** bg #E8EBE6, surface #F5F6F2, raised #E2E6E0, text #16201A, dim #46524A, signal #237A52, danger #B9473A, success #2F8F5B.
- **Soft accents:** emerald #5FD09B, sky #6FAEE8, gold #E9C46A, lilac #B49CE8, coral #E08A6B. Tiles at 16 % tint with a full-colour glyph; dots, badges, progress, selected rings. Never fills.
- **Type:** launcher Inter 400-700 (already bundled), scale 12 / 13.5 / 16 / 22 / 36; numeral and page titles Inter Display ExtraBold, natural width, tabular figures (one more static OFL TTF; the mock uses Inter Tight 800 as stand-in). In-game: Inter 400/600 through the existing MSDF atlas, the Mod Menu's own font. No new in-game font.
- **Shape:** launcher card 12, control 8, dialog 16, pills round. In-game 3 GUI px (RADIUS_LG). 8 px grid, 4 px half-step.
- **Motion:** hover 120 ms, press 80 ms at scale .97, enter 220 ms, exit 140 ms, cubic-bezier(.2,.8,.2,1). In-game hover via `1 - exp(-dt/tau)` smoothing.
- **Signatures:** (1) Orbit thread: the logo's three rings as 1 px parchment hairlines behind the numeral; on Play one ring fills in emerald with real progress, and the same ring keeps filling around the logo on the Minecraft loading screen. (2) Soft-hue tiles everywhere colour appears. (3) Workbench glass nav, solid, no blur.
- **Anti-references:** Lunar / Feather neon glass, Discord clone, AI bento dashboard, Orbitron / Michroma esports type.
- **Cost:** lowest. Tokens already exist in OriginTheme/OriginDeck; launcher palette moves from neutral #111 to the moss-black set.

## B. Bedrock
Quarried stone and glowstone light; a soft bevel taken from the Minecraft button, with no retro pixels.
- **Personality:** solid, architectural, tactile, quiet.
- **Palette dark:** bg #0F1113, bg2 #14171A, surface #1A1E22, raised #23282D, text #E8EAEC, dim #A9AFB5, muted #737A82, lamplight #E7B566 (on #1E1608), danger #E0705F, success #79C28C, warning #D9A04E.
- **Palette light:** bg #E3E5E7, surface #F1F2F3, raised #D9DCDF, text #15181B, dim #4A5057, lamplight #A8711C, danger #B9483A, success #3E8A55.
- **Soft accents (dyes):** cyan #5FB3BF, light blue #7AA7E0, orange #E39A5A, magenta #C88AC9, lime #9CCB6A.
- **Type:** launcher Archivo (Expanded 800 for numeral and titles, normal width 400-700 for UI; static OFL TTFs). In-game Inter 600 MSDF; Monocraft (already bundled) for the ORIGIN wordmark only.
- **Shape:** card 6, control 4, dialog 8, squared-soft chips. In-game 2 GUI px. 8 px grid, 44 px rows.
- **Motion:** hover 90 ms, press 60 ms (sink 1 px, bevel inverts), enter 180 ms, exit 110 ms, cubic-bezier(.3,.7,.2,1).
- **Signatures:** soft 1 px bevel on every surface; 16 px block grid at 3 % in scrims; 16-block segmented progress shared by launcher and loading screen.
- **Anti-references:** 8-bit retro launcher, dirt textures, Bootstrap grey, stone photo skeuomorphism.
- **Cost:** medium. New launcher font family; bevel lines must be baked at physical resolution; taste risk if values drift.

## C. Cartograph
A collector's atlas of Minecraft's eras: light serif numeral, key art as plates, ink-dark smoked surfaces.
- **Personality:** editorial, cinematic, unhurried, collected.
- **Palette dark:** bg #100F0E, bg2 #161412, surface #1C1A17, raised #262320, glass rgba(18,16,14,.74), lines ivory 9 % / 20 %, text #EDE6D8, dim #B5AC9C, muted #7E776B, primary ivory #EDE6D8 (on #14120F), danger #D9705F, success #8DBE85, warning #D4AE68.
- **Palette light:** cool paper bg #E6E6E1, surface #F3F3EF, raised #DCDCD5, text #1A1815, dim #4F4A42, primary #1A1815, danger #B4503F, success #4D8A47.
- **Soft accents (map inks):** sea #79AFC4, moss #93BE8A, ochre #D4AE68, rose #D69088, plum #A895C9.
- **Type:** Fraunces Light (opsz 144) for numerals and titles, Instrument Sans 400-600 for UI. In-game Inter 600 MSDF; the serif version numeral baked as a texture per GUI scale.
- **Shape:** card 14, control 10, dialog 20, primary buttons fully round. In-game 3 GUI px. 48 px page gutter.
- **Motion:** hover 160 ms, press 100 ms at .985, enter 280 ms cubic-bezier(.16,1,.3,1), crossfades.
- **Signatures:** atlas-plate family cards (art inset 6 px); serif numeral; in-game menu moved to the left third with the serif version bottom-right.
- **Anti-references:** cream + serif magazine template, Apple TV frosted glass, wedding-invitation gold.
- **Cost:** highest. Furthest from the Mod Menu (opening Mods feels like another room); new title layout on every module; two new launcher fonts; thin serif strokes over busy art.

## Build risks (all directions)
1. **Title restyle on ~20 modules.** Today's title uses vanilla button sprites. Each module's buttons must be re-pointed to rounded panels. 1.8.9 is a separate hand-made `OriginTitleScreen`; pre-1.20 modules are `.no-shared-sync`, so edits there are manual per module.
2. **Anti-aliased corners.** Use the existing baked-mask helpers (masks at physical resolution, blitted 1:1); on 1.21.6+ the deferred GUI allocates a render state per fill, so per-pixel AA panels cost about 130 draws each (MEMORY: deferred-GUI draw-call cost). 1.21.1 keeps its SDF panels.
3. **Version picker step 2 is real logic.** Nothing today knows installed vs downloadable; needs a disk check (`instances/<version>/` plus the version json) off the UI thread.
4. **Fonts.** A: none in-game, one TTF in the launcher. B and C: new launcher families; C also baked serif textures in-game; 1.8.9 goes through OriginTtfFont.
5. **Brand note conflict.** CLAUDE.md says "no hue even in the glow"; Workbench moves the base to moss-black and Brand.xaml already carries a jade glow #4BC8AE. Will should confirm the tinted base (or Workbench drops the glow and keeps hue to accents only, as proposed).

## Recommendation: A, Workbench
- Its palette, font and radii are the approved Mod Menu's own tokens, so the Mod Menu sits inside the product instead of beside it.
- Zero new in-game font work on any of the ~20 modules; the corner helpers already exist.
- It keeps every decision Will already made: soft colour accents in small tiles (the Settings icons he liked), neutral numeral, rounded in-game menus.
- The orbit-thread progress gives the launch-to-title continuity a single, cheap, branded element (three ellipses and one stroke).
- Lowest risk on 1.8.9 performance: flat fills and baked masks only, no blur, no shaders.
