# The Origin Store (originclient.org/store.html)

The website's cosmetics shop. Players browse capes, halos and a crown, try them
on in a 3D preview, type the Minecraft name the items are for, and pay through
**Tebex**, the checkout service Minecraft servers use. It takes card, PayPal,
Apple Pay and Google Pay, and handles tax, receipts and refunds. The
Minecraft name is attached to the Tebex order, so the purchase belongs to that
player (gifting = typing a friend's name).

**Status (2026-10-12): showroom only.** Browsing, trying on, the name check and
the bag all work. Checkout shows "Soon" and makes no Tebex calls until
`STORE.live` is turned on. Keep it off until cosmetics actually show up in
game: the website only takes the order. The in-game side (the mod reading
who owns what and drawing it) isn't built yet, and charging for items
nobody can see would be wrong.

## Files

| File | Owns |
|---|---|
| `website/store.html` | the page |
| `website/css/store.css` | store-only styles (tokens/buttons come from `site.css`) |
| `website/js/store.js` | **what's for sale**: `STORE` switch, `ITEMS` (name, blurb, list price, Tebex package id), name lookup, bag, checkout |
| `website/js/cosmetics.js` | **how each item looks**: procedural art per id, the 3D player + fitting room, card thumbnails, animation loop |

Item ids are shared between the two JS files. Everything that moves repeats
every `LOOP` seconds (whole-number cycles only), which is what lets cards play
a seamless pre-rendered loop.

## Lineup and prices (Will: every price $5–$10)

- Animated capes (moving art + particles): Orbit $9.99, Glitch $9.99,
  Inferno $8.99, Aurora $8.99, Starfall $8.99
- Still capes $5.99: Origin, Golden Hour, Frost, Void
- Head: Orbit Halo $7.99, Pixel Crown $6.99, Golden Halo $5.99
- The Origin badge next to a player's name is **free for everyone**. It's not
  sold, only shown on the page.
- **Wings: not on sale** (Will, 2026-10-12: "no wings for now"). Their art and
  flap animation are still in `cosmetics.js`. To bring them back, add their
  ids (`wings-ember`, `wings-void`, `wings-frost`) to `ITEMS` and a Wings tab
  to `store.html`.

All art is placeholder, painted in code. Real artwork replaces one painter
function per item.

## Turning payments on (Will's steps)

1. Make a free account at tebex.io and create a webstore of type
   **Minecraft: Java Edition** (so Tebex checks the name the buyer types).
2. In Tebex, add one **package** per item, using the same names and prices as
   `ITEMS` in `website/js/store.js`. Note each package's numeric id.
3. Tebex → Integrations → **Headless API**: copy the webstore's **public token**
   (never the private key).
4. Give Claude the token and the package ids. Claude puts them in `STORE.webstore`
   and each item's `tebex` field.
5. Once in-game cosmetics exist, Claude flips `STORE.live = true`. Then make one
   real test purchase to your own Minecraft name and refund it from Tebex.

When live, the page loads Tebex's prices, so what's shown is always what's
charged. An item without a package id shows "Soon" and can't be bought.

## Outside services the page uses (all fail soft)

- `headless.tebex.io`: baskets and checkout (only when live).
- `playerdb.co`: checks the typed name exists and gets the UUID. If it can't
  be reached, checkout is still allowed and Tebex checks the name instead.
- `mc-heads.net`: the player-head image next to the name.
- `crafatar.com`, falling back to `mc-heads.net`: the real skin for the 3D preview.
  Unreachable means the neutral mannequin stays.
- No WebGL: cards and preview fall back to flat 2D art.
