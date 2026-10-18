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
- **CI on e2e1e992 (every version ported): build-check all green**, ref-189 green. Dev shots on
  1.20, 1.20.2, 1.20.4 and 1.21 booted with no mixin-apply failures and the Cosmetics screen
  showed the worn cape, wings and halo on the model. Dev shots are automated headless boots,
  not a player's test.

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
