# Development report — cosmetics, launch experience, Origin+

Branch `claude/stripe-store`, pushed (Will's OK, 2026-10-18). Not merged to main, not deployed.
Updated 2026-10-18.

## Features completed
- **Cosmetics v2 lineup** (1.21.1 + store), held to "Lunar = 10, ship at 8+". An independent
  judge scores every item, and anything still under 8 after 3 tries is taken off sale
  (owners keep it).
  - **Passed (8/10):**
    - Capes: Origin, Origin+ (members only), Void, Golden Hour, Frost, Inferno, Starfall,
      Orbit, Glitch (the last three passed on their third and final try)
    - Ember Wings, Explorer Pack, Golden Halo, Origin Bandana, Visor Mask (the last three on
      their second try; Will asked for an extra polish pass on the bandana and visor)
    - Orbit Halo (Will: "keep going with orbit", past the 3-try limit): redrawn as the Origin
      mark lying flat, three thin silver rings round a small bead with a soft glow, and passed
      on its eighth try. Back on sale.
    - Menu themes: Amethyst, Crimson, Ocean, Rose (reworked, below)
  - **Dropped after 3 tries:** Pixel Crown and Aurora Cape. Both are `onSale:false`, so owners
    still own and wear them.
- **Menu themes rebuilt on the real main theme** (Will: the old ones didn't match the in-game
  menu). A theme is now the main theme's own colour tokens (`DEF_` in OriginDeck/OriginTheme)
  re-tinted in OKLCH by `cosmetics/lib/theme.mjs`, so every contrast and alpha carries over;
  the main theme fed back in reproduces itself exactly (build fails otherwise). The mod only
  assigns the precomputed tokens from item.json. Store/screen pictures are the real menu design
  (mock4) rendered in the theme (`tools/cosmetics/theme-preview.mjs`), smooth, not pixel art.
  Emerald was replaced by Amethyst: re-tinted properly it was nearly the free default.
- **Menu themes are Origin+ menu unlocks** (Will, 2026-10-18), never sold: `plusOnly`, like the
  Origin+ cape. The service gives members every theme and hides a worn one when the membership
  ends (a test pins this). The store dropped its Themes tab; the Origin+ card lists "Every menu
  theme" with the four previews, and each swatch tries the theme on in the fitting room. In game,
  theme cards say "ORIGIN+ THEME", and a non-member's Themes tab says they come with Origin+.
- **Menu themes and the mod-card toggle.** The hovered ENABLED bar follows the theme's accent
  (before, it flashed green on every theme). On the red and pink themes (Crimson, Rose) the
  DISABLED bar is neutral grey, so "on" and "off" are never both red; the others keep the main
  theme's red. With no theme it is byte-identical to before.
- **Launcher opening animation** (`UI/Controls/StartupIntro`):
  - The three Origin rings draw themselves in and swing out to their angles, then the layer
    fades away. It lasts about 0.85 s.
  - It plays over the already-built Home page, leaves once the window has rendered and the
    UI is idle, and is capped at 2.2 s.
  - Any click or key skips it. A key that skips the intro does nothing else, so Enter can't
    start a game the player can't see yet.
  - It is skipped entirely when Windows "Show animations" is off.
  - There is no fake progress bar.
- **Store: no success shown before it's confirmed.**
  - Coming back from Stripe now shows "Confirming…". The page checks with the service until
    the items (or the membership) actually appear, and only then says thank you and empties
    the bag.
  - If they never appear, it says the payment is still being confirmed. The bag is kept, so
    a failed payment loses nothing.
- **Store: owned items.** Items the typed player already owns show "Owned ✓" and can't be
  added to the bag again. Owned ids are dropped from a saved bag.
- **Store: buttons are honest.** When the store is closed, Checkout and Join Origin+ are
  disabled, not clickable-but-silent. Pressing Back from Stripe no longer leaves the buttons
  stuck on "Opening checkout…".
- **In game: cosmetics screen failure states.**
  - A failed read now says "Couldn't load your cosmetics…" instead of "Loading…" forever.
  - Opening the screen after the game started offline looks for the service again right
    away, instead of waiting out a 10-minute timer.
- **In game: equip sends only the slots that change.** A lapsed member's saved Origin+ cape
  is no longer wiped by changing another slot, so it comes back when they rejoin.
- **Cosmetics v2 on every live version** (Will, 2026-10-18: "every version now, everything").
  Worn cosmetics, the Cosmetics screen, menu themes, the Origin+ badge, cape physics, effects
  and both Mods toggles were ported from 1.21.1 to 1.8.9, 1.16.5, 1.17.1, 1.18.2, 1.19.2-1.19.4,
  1.20-1.20.4, 1.21, 1.21.2-1.21.11 and 26.1-26.3, each on its own era's API (mandate 5). Every
  module keeps 1.21.1's menu colours, so the themes re-tint identically. `build.mjs` writes the
  item files into every live module. Small per-version differences in method:
  - 1.21.6/1.21.8: model pictures in the Cosmetics screen use one picture-in-picture slot per tile.
  - 1.21.10/1.21.11: those pictures are drawn flat on the CPU; glowing parts look a bit flatter.
  - 26.x: the nametag Origin+ badge is a font glyph (slightly sharper/blockier); capes with
    physics get no outline glow.
  - 1.8.9: no badge on a *sneaking* player's nametag (1.8.9 draws that tag elsewhere); no
    elytra/swim poses exist there.

## Bugs fixed
- The store showed "Thank you" and emptied the bag on `?purchase=done` alone, before the
  payment was confirmed.
- Service: async handlers were returned without `await`. A database error skipped the error
  handler and became a bare 500 with no CORS headers.
- `build.mjs` now writes the store's offline price list (`PRICE_LIST`) itself. It kept
  drifting from the service catalog.

## Build / test results (this sandbox)
- `npm test` in services/cosmetics: **55 pass, 0 fail**.
- `node tools/cosmetics/build.mjs --check`: run before every commit.
- `python tools/shared-sync/sync.py --check`: OK.
- `python tools/release/check_consistency.py`: OK.
- `node --check website/js/store.js`: OK.
- **Not built here.** The launcher (.NET) and the mod (Gradle/Fabric) can't build in this
  sandbox: the .NET SDK and the Fabric maven are blocked. A reviewer read StartupIntro and
  the Java changes as a compiler would and found no errors.
- **CI on 75e234a1 (GitHub build-check): all green.** Every live mod version compiled,
  including 1.21.1 with this branch's Java changes. The launcher published (StartupIntro
  compiled), the installer smoke test passed, and the launcher logic tests passed.
  cosmetics-service and launcher-shots also passed. Compiling is not running:
  **nothing has been run in game.**
- **CI on e2e1e992 (every version ported): build-check all green**, ref-189 green.
- **Dev shots (label `cosv2`, branch `claude/dev-shots`):** every live version booted with zero
  mixin-apply failures and no Origin exceptions. The Cosmetics screen looks the same on all of
  them, model wearing cape, wings and halo; in a fresh world it opens over the game on every
  version except 1.16.5, whose world run failed twice downloading Mojang's game assets before
  the game started (infra, not this code; its menu run passed). Dev shots are automated
  headless boots: worn cosmetics on a player in a world, cape physics and the nametag badge
  are not in them, so they still need a player's test.

## Needs in-game / on-PC testing (nothing below has been seen running)
- Launcher start, normal and with Windows animations off: does the intro play, skip on a
  click or key, and leave?
- 1.21.1: every passed cosmetic worn (Orbit Halo: does its soft glow look right in game?), with animated cape speed, wings and cape clearance,
  and the Origin+ cape as member and non-member.
- 1.21.1: each menu theme on the mod menu, checking ENABLED/DISABLED and hover.
- 1.21.1: the Cosmetics screen offline, then online again.
- One version from each era (1.8.9, 1.16.5, 1.19.4, 1.21.11, 26.2): wear a cape, wings and a
  halo, walk/sneak (cape physics), check an Origin+ nametag and Tab, switch a menu theme.
- Store with a Stripe **test** card: confirm → thank-you; cancel → bag kept.

## Known limitations / external integrations
- Ads: research only, nothing added. See the summary below. The banner placeholder
  (`website/ad/banner.html`) is unchanged.
- The 1.21.1 title screen and LaunchLoadingOverlay were reviewed and left unchanged. They
  already have a staggered logo intro and an honest progress bar (a sweep while launching,
  real bytes while updating). Changing them untested risked the launch path.

## Ad / monetization research (summary)
Sources: search snippets only; primary pages were blocked here.
- Lunar shows one bottom-right launcher ad through Overwolf, which now owns Nitro. Nitro's
  own desktop docs point to Overwolf.
- AdSense's WebView policy covers mobile apps only, so treat it as unsupported for a desktop
  launcher.
- Mojang's guidelines: selling cosmetics is fine, selling gameplay advantage is not. The
  guidelines say nothing explicit about ads in a launcher. Will should read the official
  text before relying on this.
- **Recommendation:**
  - one home-banner slot only: no video, no sound, refresh every 60 s or slower, never in
    game or during loading;
  - Origin+ members see no ads;
  - a consent prompt before the first ad request;
  - ask Nitro/Overwolf in writing whether they accept WebView2 inventory.

## Main files changed
- `cosmetics/items/*/item.mjs`: art sources.
- Generated from them: `website/cosmetics/`, the mod's `assets/originclient/cosmetics/`,
  `services/cosmetics/src/catalog.gen.js`.
- `tools/cosmetics/build.mjs`
- `website/js/store.js`
- `services/cosmetics/src/index.js`
- `src/OriginLauncher.App/UI/Controls/StartupIntro.xaml(.cs)`, `MainWindow.xaml(.cs)`
- Mod 1.21.1: `gui/OriginDeck.java`, `gui/OriginModMenuScreen.java`, `gui/CosmeticsScreen.java`,
  `cosmetics/CosmeticsService.java`

---

# Origin Client — development report

Branch `claude/origin-client-development-4790bv` · 2026-10-18 · **1.21.1 only** (current focus; nothing in `shared/`).

## Done
**Resource Pack Hub** (`packs/`). It opens from a new "Browse Packs" button on Minecraft's Resource Packs screen.
- **Browse tab**: live Modrinth search for the running game version.
  - Search waits 350 ms after you stop typing.
  - Sort by downloads, relevance, follows, newest or updated.
  - Resolution filter uses the author-declared Modrinth tags only.
  - Category chips: PvP (Modrinth's `combat`), Vanilla+, Realistic, Simplistic, Themed, Tweaks, Utility, Decoration.
  - Each row has a preview, author, downloads, resolution, categories, license and a Modrinth page link.
  - Infinite scroll, 40 per page, up to 1000 results.
  - Error, retry, rate-limit and offline states. Offline uses a disk-cached copy and labels it as such.
- **Install**:
  - Downloads only from Modrinth's https hosts. A redirect off those hosts is refused.
  - Download goes to a `.part` file, with live progress and Cancel.
  - The sha512/sha1 hash is verified before anything is kept.
  - `pack.mcmeta` and zip-slip checks run before the file is moved in.
  - New files get a unique name, so nothing is ever overwritten. A byte-identical pack that's already installed is recognised and not downloaded twice.
  - A pack made for another version is flagged.
- **Installed tab**: every zip and pack folder.
  - Turn packs on/off and reorder them (top wins).
  - Update: hand-added Modrinth packs are recognised by hash too.
  - Uninstall asks first, and the hub never deletes folders.
  - Presets: save, apply, delete.
  - Open folder.
- **Applying changes**: edits are staged. On close they are written into the vanilla screen's own selection, so vanilla's Done does the single reload. Built-in and locked packs keep their place.
  - Windows locks loaded packs, so their uninstall waits for the next resource reload or game start.
  - If the accessor mixin ever fails, the hub falls back to install-only with a notice.

**FPS module: frame pacing.** Three new options, all off by default:
- Show 1% Low
- Show Frame Time (ms)
- Frame Time Graph: red bar = hitch, gold bar = a hitch that a JVM garbage-collection pause overlapped.

`FrameStats` records each frame at `GameRenderer.render` HEAD: one `nanoTime` call and one array store, no allocation. The sort and the GC lookup run only 4×/s or on spike frames.

## Origin Render (experimental, off by default)
**What it is.** GPU occlusion culling for Sodium's opaque terrain. Settings → Performance → "Origin Render (Experimental)".
- Sodium still picks the sections in view on the CPU (frustum and cave culling).
- The graphics card then skips sections hidden behind terrain *already drawn this frame*. It uses a two-phase hierarchical-Z test:
  1. Draw what was visible last frame.
  2. Build a depth pyramid.
  3. Test every section and draw what's newly visible.
- Because the test is against this frame's real depth, nothing should pop in when you turn.
- Cutout terrain (leaves, grass) is tested against the finished solid depth. Translucent terrain is untouched.

**How it plugs in.**
- One HEAD inject on Sodium 0.6.13's `DefaultChunkRenderer.render`.
- It uses Sodium's own vertex arrays, index buffer, program and begin/end, so Iris's hooks still run. Only the draw call changes: GPU-written `glMultiDrawElementsIndirect`, still one draw per region.
- Written from scratch. Nothing is copied from Sodium (PolyForm Shield), Nvidium (NVIDIA-only) or Voxy (All-Rights-Reserved).

**When it falls back to plain Sodium.** No crash, and the F3 line says why:
- setting off;
- a shaderpack is on (Iris owns the pipeline; mandate 3);
- no OpenGL 4.5 (e.g. macOS);
- terrain isn't drawing to the main screen;
- any exception or GL error. It then stays off until restart, and turning it on in the menu explains why in chat.

**Proven so far**, on a real GL 4.5 driver (Mesa) through `tools/origin-render-tests` (37 passed):
- the depth pyramid matches a CPU reference;
- across 36 000 random sections (including ones placed right at occluder edges), every section it culled was confirmed hidden by a GPU occlusion query, so 0 were wrongly hidden;
- GL state that Minecraft and Sodium cache is untouched;
- indirect draws render.

Deliberately broken shaders (no depth margin, too-coarse pyramid level, one-pixel sampling) are caught by these tests.

**Not yet proven.** Whether it makes the game faster. It saves vertex and fragment work on hidden terrain (caves, hills, towns), but costs a small compute pass. Gains are likely only when the GPU is the bottleneck. Measure with the frame-time graph; no FPS claims until then.

## Launch journey v2 (launcher → game → world, 1.21.1 first)
Spec, timings and the handshake: `docs/MOTION.md`. One picture — the loading scene's orbiting rings — carries from the Play press to the world.
- **Play:** the button folds into the Origin mark; its 3 rings unfold, spin up and fly out into the loading orbit while the charcoal rises (1100 ms). The launcher's scene (`UI/Controls/LoadingScene.cs`) is drawn by the game's own loading-screen formulas, so it is the same picture. The loader name is no longer shown (mandate 1).
- **Handoff:** the game opens its window invisibly, centred on the scene, and prints its rect. The launcher lifts the scene into a click-through window (`SceneHandoffWindow.cs`) that glides and stretches onto that rect; the game window fades in under the same picture; the copy melts away. Fullscreen, older builds and other versions keep the v1 dissolve.
- **Title:** no panorama — the rings slow, drift and lean toward the mouse; the logo settles out of them (`render/RingField.java`).
- **World:** the rings swing back into the loading orbit with real chunk progress, then open past the screen edges like a lens with the world inside (820 ms, over the HUD, input live).
- Fallbacks unchanged: no hello in 3 s → old behaviour; no loading frame in 20 s → lower anyway; 90 s → ready; the game window shows within 3 s of its first frame no matter what.

## Performance
No blind optimisations and no FPS claims. The stack (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix, Krypton) already covers what Lunar, Feather and Badlion ship. The new frame graph and 1% low are the measuring tools that should come first.

## Bugs fixed (found in self-review)
- Catalog paging never reset after a query change.
- A reinstalled pack could stay queued for deletion and be removed later.
- Decompression-bomb images could exhaust the heap (decode now limited to 4096 px per side / 16 MP).
- An update retired the old file before confirming the new one was on disk.
- A failed local scan hid the last network message.

## Build / test
- `python3 tools/packhub-tests/run.py --gson <gson.jar>`: **73 passed, 0 failed**. Covers file names, mcmeta, zips, search/URL building, the index, ordering, the installer (local HTTP server), the library scan and FrameStats.
- `python3 tools/origin-render-tests/run.py`: **37 passed, 0 failed** (GPU culler on Mesa GL 4.5; downloads LWJGL itself).
- **Not compiled against Minecraft**: Fabric/Mojang maven is 403 in the sandbox. Pushing the branch lets build-check CI compile it. The Origin Render glue was type-checked by javac against stubs of the exact Sodium 0.6.13 / Iris signatures.
- `dotnet test src/OriginLauncher.Tests`: **358 passed** (about 30 new handshake and progress tests: stages, token spoofing, every fallback).
- The launcher can't build its XAML in the sandbox (BG1002), so every launcher `.cs` was type-checked against generated XAML stubs: **0 errors**. CI builds it for real.
- The new mod classes and mixins compile against stubs. Mixin field names (`LoadingOverlay.fadeOutStart/fadeIn/minecraft`, `TitleScreen.fadeInStart`) are only proven at mixin apply.
- An independent review judged the game-side API names right. It flagged only `PackSelectionScreen.model` / `reload()` as "verify at mixin apply".

## Needs Will's in-game test (1.21.1, `runClient`)
1. Zero `Mixin apply ... failed` lines, especially `PackSelectionScreenAccessor`.
2. Options → Resource Packs → Browse Packs. Search, filters, scroll past 40, Install, Cancel, Enable, close, Done: the pack is applied.
3. Installed tab:
   - Reorder and toggle, then close: vanilla's list matches.
   - Programmer Art / High Contrast stay in place.
   - Uninstall an *enabled* pack: it's gone after Done on Windows.
4. Toggle a pack in vanilla's list *first*, then open the hub and come back: the toggle must survive.
5. FPS module → Frame Times options; the HUD editor box should size to include the graph.
6. Origin Render: zero `Mixin apply ... failed` for `Sodium*` / `DebugScreenOriginRenderMixin`. Turn it on, press F3: "Origin Render: on, drawing N of M solid sections".
   - Spin fast, fly, and walk through caves and forests. There must be no holes, flicker or late-appearing blocks.
   - Load a shaderpack: F3 says paused and the shaders look normal. Unload it: it resumes.
   - Compare FPS and the frame-time graph with it on and off at the same spot.

7. Launch journey (launcher + 1.21.1):
   - Press Play: the button folds into rings that spin out into the loading scene. No jump anywhere.
   - When Minecraft starts, the scene glides onto the game window and the game appears under it with no pop, white flash or black frame. Repeat with the launcher on a second monitor and maximised.
   - Title: rings drift behind the menu and lean toward the mouse. Enter a world: the rings swing back with a real bar, then open like a lens around the world.
   - Also check fullscreen (falls back to a dissolve), F3+T, Cancel while Minecraft is starting, and 1.8.9 still launching as before.

## Main files
- `src/mods/versions/1.21.1/src/client/java/com/origin/client/client/packs/*`
- `mixin/PackSelectionScreenAccessor.java` + `originclient.client.mixins.json`
- `OriginClientMod.java` (button + cleanup hook)
- `hud/FrameStats.java`, `hud/HudElements.java`, `mixin/GameRendererFrameMixin.java`, `mods/Mods.java` (1.21.1 fork)
- `tools/packhub-tests/`
- Launch journey: `Core/Launch/GameHandoff.cs`, `StageProgress.cs`, `UI/Pages/HomePage.xaml(.cs)`, `UI/Controls/LaunchLoadingOverlay.xaml(.cs)`, `LoadingScene.cs`, `SceneHandoffWindow.cs`, `OriginMark.xaml(.cs)`; mod `OriginHandoff.java`, `mixin/loading/WindowHandoffMixin.java`, `render/RingField.java`, `StartupTransition.java`, `mixin/loading/LoadingOverlayRevealMixin.java`, `TitleScreenFadeMixin.java`, `render/OriginScreenRenderer.java`, `mixin/GuiHudMixin.java`, `mixin/TitleScreenMixin.java`
- `render/terrain/*` (OriginRender, TerrainCuller, TerrainShaders), `mixin/Sodium*`, `mixin/DebugScreenOriginRenderMixin.java`, `tools/origin-render-tests/`

## Next
CI compile, then Will's pass. If Origin Render measures a win, the next step is moving Sodium's CPU section list onto the GPU too. After that, port the hub to 1.20 (next in the working order). The pure classes copy as-is; only the screen and accessor need a per-era check.
