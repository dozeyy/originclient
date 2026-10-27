# Origin — standing decisions (Will's always / never rules)

Always loaded (imported by `CLAUDE.md`). One rule per line, with the date Will set it.
Newest wins; a superseded rule is noted under "Resolved conflicts". Add new rules here
the day Will gives them (one line). Background: `docs/memory-archive/` (grep, never read whole).

## Product
- No loader choice, ever: Fabric for every live version; 1.8.9 runs Forge 11.15.1.2318 + OptiFine (2026-10-13).
- 1.12.2 and 1.21.5 stay pulled (modules kept, out of picker and CI) (2026-08-06; 1.8.9 back live 2026-10-13).
- 1.21.1 is the baseplate: every live version matches 1.21.1's look and features ("make each version match 1.21.1") (2026-08-01, 2026-10-11).
- Working order: perfect 1.21.1, then 1.20, then the 26.x line, then down the list. One version at a time (2026-07-21).
- Per-version best-fit: the method may differ per version, the look may not (2026-07-21).
- No recipe viewer on a version where Fabric JEI doesn't exist; don't fake one (2026-10-12).
- Video Settings mod rail is removed; don't bring it back without asking (2026-10-12).
- Updates are mandatory for players (2026-07-08). Releases are deliberate `launcher-v*` tags, never every main push (2026-07-12/13, `docs/RELEASING.md`).
- A launcher self-update with no valid SHA-256 is refused, and never blocks Play (2026-10-25).
- Closing or crashing the game never closes the launcher; Play works again every time (2026-07-09).
- Settings Sync is on by default and carries mod settings + HUD positions; every change hits disk instantly (2026-10-18).
- Opening any screen must feel instant: no blocking entrance animations, no per-open loads (2026-10-18).
- Launcher settings writes go through `SettingsStore.Update(mutate)` only (2026-07-12/13).
- One game window per account, several at once; mods shared per version (2026-10-12).
- A player's own mods win: if their mods carry an id Origin also ships (Sodium, Iris, JEI, ...), theirs loads and Origin's steps aside (2026-10-25).
- When a player's mod does the same job as an Origin mod (freecam vs Freelook, EntityCulling vs entity distance, ...), Origin's turns off automatically, the Mod Menu says which mod replaced it, and the player can turn it back on (2026-10-26).
- Origin's Sodium/Iris hooks check the version; on one they don't know, that feature switches off and the rest of Origin keeps working (2026-10-26).
- 1.21.1 ships Sodium 0.8 by default (Will's own mods need it); Origin Render (experimental terrain culler, Sodium 0.6 only) stays removed (2026-10-26).
- Fix Compatibility may look up a missing dependency on Modrinth and installs it only when the file matches Modrinth's SHA-512 (2026-10-27).

## Look
- Vanilla screens (pause, options, world select, every other Minecraft menu) use Origin's own DARK buttons in the Default Dark Mode look, drawn by Origin (`tools/dark-widgets/make_widgets.py`; that pack is CC BY-NC-SA, never copied) and built into the jar, never a resource pack in the pack list. The title screen's buttons are those same dark Minecraft buttons with Minecraft's font, words only (2026-10-27). Origin's widget skin stays only inside Origin's own screens: mod menu, HUD editor, shader browser, waypoints, item size (2026-10-26).
- Title-screen panoramas: each version's own landscape showing that update's content, daytime, shaders on, rendered in CI and shipped in the jar (2026-10-26).
- Each title panorama shows its update's ONE main highlight where it really generates or spawns (real seed + coords); no placed blocks and no mobs summoned where they'd never be found (2026-10-27).
- Sodium's translations (Translations for Sodium, CC0) ship built into the jar, never as a resource pack (2026-10-26).
- In-game text is Minecraft's own font everywhere; no Inter/Space Grotesk/MSDF in game; no hand-rolled glyph rendering (2026-07-08, re-confirmed 2026-09-09).
- Exception (2026-10-25, Will approved the Workbench mockup and asked to "fix the font on the middle"): Origin's own title-screen text (player name, footer) uses Inter via the MSDF font; every other in-game text stays Minecraft's font.
- Title dock: 20 px dark Minecraft buttons with premade open-licence pixel icons in white (HackerNoon crown = Cosmetics, Teenyicons server rack = Realms, Kenney X = Quit; credits ship in the jar), centred like vanilla's icon buttons; Friends is vanilla's chat bubble, Language and Accessibility stay vanilla's own. Never hand-drawn icons (2026-10-27).
- Loading screens use Minecraft's font on every version, with the Workbench Cancel button and one backdrop (2026-10-27).
- Ring/orbit/oval shapes exist ONLY inside the real logo PNG. Anywhere a drawn lookalike stood for the logo (title, loading, reload, connecting screens, any version), show `origin_logo.png` itself; loading progress is a plain capsule bar. No hover labels on the title dock icons (they look like extra buttons) (2026-10-25).
- 26.2+ title dock keeps Mojang's own Friends button with the single-person glyph (Origin's Friends keeps the two-person glyph) (2026-10-25).
- Workbench design system (`docs/overhaul/DESIGN-SYSTEM.md`): neutral base, small soft-hued accents, never big colour fills; Home version numeral stays neutral (2026-10-25).
- Logos: only `textures/ui/origin_logo.png` (= `Assets/origin.ico`) and `Assets/Brand/wordmark.png`. Never redraw a logo in XAML, SVG, CSS or code (2026-10-25).
- Icons: Origin's own icon set per the Icon Packs rules, not borrowed sets (2026-09-03).
- Numeral shadows are drawn in `ArtStage.OnRender`, never a WPF `DropShadowEffect` on animating elements (2026-10-25).
- No ovals anywhere: every pill, badge, chip and orbit ring is an Apple capsule (radius = height/2, even padding); round dots stay circles (2026-10-25).
- Launcher Home: Store lives in the top bar; no profile button on Home, the profile is put on in the Profiles page only (2026-10-25).
- Launcher Home has no orbit rings around the numeral: they read as a fake Origin logo (2026-10-25).
- Version picker: click a family card, it expands (animated) into a panel with one compact release dropdown; picking is the selection, Esc/click-out returns Home, Back returns to the grid; no "Download" wording (2026-10-25).

## Website (originclient.org — served by Vercel, not GitHub Pages, 2026-10-18)
- Never claim "every version" (2026-10-13).
- Shaders on across the whole site experience (2026-10-11).
- The site is the island in the sky. The scroll-through Install/Launch replicas and the in-game "Play" chapter are removed; don't re-add without asking (2026-10-11).
- The AI helper page is removed (2026-10-25).
- Website-only changes deploy without waiting for Will: push ONLY website commits to `main` (2026-10-13). Never ship unreleased launcher/mod work with them.

## How Claude works here
- Local sessions build and run things themselves; only cloud sessions hand Will commands or use CI as the compiler (2026-10-25).
- Never open Minecraft (runClient, boot-smoke) on Will's PC: in-game proof comes from CI dev-shots / boot-smoke on a pushed `claude/<name>` branch (2026-10-18).
- The only local runs: launcher `OriginLauncher.App.exe --render-shots <scratch dir>` and installer `OriginSetup.exe --render-tour <scratch dir>` (made-up data, no clicks, exit by themselves). No session runs the launcher or installer interactively (2026-10-25).
- Never touch `%LocalAppData%\OriginLauncher` (Will's live data) without his go — that includes an interactive launcher run (gate list).
- Title panoramas are rendered on Will's PC in a separate dev copy (never his Origin folder): Complementary + Voxy/far terrain, camera on the ground, open view all round; for that job Minecraft may open and the mouse may be used (Will, 2026-10-27).
- Never move Will's mouse cursor, not even onto a window Claude launched (SetCursorPos in a render-shots timing run; Will: "stop touching my mouse", 2026-10-26).
- Never click or type into a window an agent didn't launch (an agent's clicks once landed in his FL Studio project, 2026-10-18).
- CI trap: dev-shots can report success with 0 shots — count the PNGs (2026-10-18).
- Judge loops (`docs/overhaul/JUDGE.md`, pass = 95/100; website judge 9/10) run only on overhaul or redesign work (2026-10-25).
- Implementation runs on opus in this project (repeated redo failures on sonnet) (2026-10-25).
- Done = Will can run it now: built locally (or released), plus a screenshot of that build for UI changes. In-game changes are done at release; until then report "gated deploy left" (2026-10-25).

## Resolved conflicts
- 2026-07-08 "default Minecraft text everywhere" vs 2026-09-09 Workbench role fonts (Space Grotesk/Inter in game): the later 2026-09-09 typography pass removed them again. Minecraft font in game; the WPF launcher uses its own bundled font.
- 2026-10-18 "no windows on Will's PC, never run the launcher or --render-shots locally" vs 2026-10-25 "local sessions build and run it; verifier screenshots the launcher": 2026-10-25 wins for builds, `--render-shots` and `--render-tour` (no other local runs); the no-Minecraft-window and no-clicks-into-other-apps parts stand.
- 2026-10-13 "website judge >= 9/10 before every publish" vs 2026-10-25 "judges only on overhaul/redesign work": small website fixes ship without a judge round.
- CLAUDE.md "ask whatever clarifying questions" / "plain-English summary after every change" replaced 2026-10-25 by 1-3 clickable questions and the Field Report one-line done.
- 2026-08-23 "vanilla screens use STOCK Minecraft buttons" vs 2026-10-26 "change all the UI buttons besides the main menu custom ones to the default dark mode ones": 2026-10-26 wins; vanilla screens get Origin's dark buttons.
- 2026-10-25 Workbench card title buttons with Inter labels and parchment dock glyphs vs 2026-10-27 "the exact vanilla dark buttons", premade dock icons: 2026-10-27 wins.
- Palette history (Aurora -> Slate -> Ion Jade -> Workbench, 2026-09-03 to 2026-09-09): Workbench is current.
- 2026-10-25 Inter/MSDF title-text exception vs 2026-10-27 "Minecraft's font on the loading screens; title buttons are Minecraft's dark buttons with Minecraft's font": 2026-10-27 wins. Loading screens and title buttons use Minecraft's font.
