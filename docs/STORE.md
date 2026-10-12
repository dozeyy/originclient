# The Origin Store (originclient.org/store.html)

The website's cosmetics shop. Players browse capes, halos and a crown, try them
on in a 3D preview, type the Minecraft name the items are for, and pay through
**Tebex**, the checkout service Minecraft servers use. It takes card, PayPal,
Apple Pay and Google Pay, and handles tax, receipts and refunds. The
Minecraft name is attached to the Tebex order, so the purchase belongs to that
player (gifting = typing a friend's name).

**Status (2026-10-12): showroom; delivery + in-game built, not yet switched on.**
Browsing, trying on, the name check and the bag all work; Checkout shows
"Soon" and makes no Tebex calls until `STORE.live` is turned on. The delivery
service (`services/cosmetics`) and the 1.21.1 in-game side are built but wait
on Cloudflare setup (below). Order to go live: Cloudflare + service deployed →
`website/cosmetics/service.json` points at it → Will sees his own test item
in game → `STORE.live = true` → Tebex "Submit for Review".

## How a purchase reaches the game

```
store page ──basket+name──▶ Tebex checkout ──paid──▶ Tebex queues
  origin-grant <name> <uuid> <item>  for the "Origin Cosmetics" server
        │
services/cosmetics (Cloudflare Worker, cron each minute, obeys next_check)
  pulls the queue with that server's secret, records owner by UUID in D1,
  marks the commands done (refunds: origin-revoke removes + unequips)
        │
Origin mod (1.21.1) ── reads website/cosmetics/service.json for the service
  address ── Cosmetics screen (mod menu rail): pick what you wear; saved with
  Minecraft's server-join check (Mojang joinServer → service hasJoined), so
  no token ever reaches us ── every Origin player looks up who wears what
  (batched, cached 5 min) and draws capes (vanilla cape slot) + head items
```

**Microsoft sign-in on the store page: blocked.** The launcher's sign-in works
only through a borrowed client ID (`MicrosoftAuthenticator.IsTestMode`); a
website can't use that ID, and Origin's own Azure app
(de37d9e5-82d5-43a7-8f66-ebac788e8ba5) still needs Mojang's approval for the
Minecraft API (aka.ms/mce-reviewappid). Build it once approved (SPA redirect
on that app + a token-exchange endpoint on the Worker).

## Files

| File | Owns |
|---|---|
| `website/store.html` | the page |
| `website/css/store.css` | store-only styles (tokens/buttons come from `site.css`) |
| `website/js/store.js` | **what's for sale**: `STORE` switch, `ITEMS` (name, blurb, list price, Tebex package id), name lookup, bag, checkout |
| `website/js/cosmetics.js` | **how each item looks**: procedural art per id, the 3D player + fitting room, card thumbnails, animation loop |

Item ids are shared between the two JS files. Everything that moves repeats
every `LOOP` seconds (whole-number cycles only; the Orbit Halo uses 3x LOOP),
which is what lets cards play a seamless pre-rendered loop.

## Lineup and prices (Will: every price $5–$10)

- Animated capes (moving art + particles): Orbit $9.99, Glitch $9.99,
  Inferno $8.99, Aurora $8.99, Starfall $8.99
- Still capes $5.99: Origin, Golden Hour, Frost, Void
- Head: Orbit Halo $7.99, Pixel Crown $6.99, Golden Halo $5.99
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
   - **Deliverable (Tebex requires one per package):** a game-server command
     to a stand-in server named **Origin Cosmetics** (plugin type, nothing
     installed). On purchase: `origin-grant {username} {id} <item-id>`; on
     refund/chargeback: `origin-revoke {username} {id} <item-id>`; "player must
     be online" OFF. `<item-id>` is the item's id in `ITEMS` (e.g. `cape-orbit`).
     **This is the delivery contract:** the future ownership service plays that
     server, pulling these commands from Tebex's plugin API (with the stand-in
     server's secret key, kept out of chat and out of the repo) to record who
     owns what, then marking them done.
3. Tebex → Integrations → **Headless API**: copy the webstore's **public token**
   (never the private key).
4. Give Claude the token and the package ids. Claude puts them in `STORE.webstore`
   and each item's `tebex` field. **Done 2026-10-12** (all 12 packages; ids in
   `website/js/store.js`). Check anytime in a browser:
   `https://headless.tebex.io/api/accounts/<STORE.webstore>/categories?includePackages=1`.
5. Once in-game cosmetics exist, Claude flips `STORE.live = true`. Then make one
   real test purchase to your own Minecraft name and refund it from Tebex.

When live, the page loads Tebex's prices, so what's shown is always what's
charged. An item without a package id shows "Soon" and can't be bought.

## Delivery service setup (Will, one time)

1. Free account at **cloudflare.com**.
2. **D1 database:** dashboard → Storage & Databases → D1 → Create, name
   `origin-cosmetics`. Give Claude its **Database ID** (not secret) for
   `services/cosmetics/wrangler.toml`.
3. **API token:** profile → API Tokens → Create Token → "Edit Cloudflare
   Workers" template, plus permission Account → D1 → Edit. Copy the token.
4. **GitHub secrets** (repo → Settings → Secrets and variables → Actions):
   `CLOUDFLARE_API_TOKEN` (step 3) and `CLOUDFLARE_ACCOUNT_ID` (shown on the
   Cloudflare dashboard home). The `cosmetics-service` workflow then tests
   and deploys on every push to main (it skips deploy until all of this exists).
5. **Tebex secret:** the stand-in server's key was once shown in a chat
   screenshot, so make a fresh one in Tebex (Game Servers → Origin Cosmetics),
   then Cloudflare → Workers & Pages → `origin-cosmetics` → Settings →
   Variables and Secrets → add **Secret** `TEBEX_SECRET`. Never in the repo
   or chat.
6. Give Claude the Worker's address (`https://origin-cosmetics.<name>.workers.dev`);
   it goes in `website/cosmetics/service.json` (`"api"`), which switches the
   in-game side on with no mod release. Empty `"api"` = cosmetics off.

Service API (all JSON, CORS open): `GET /v1/players/<uuid>` → owned +
equipped; `POST /v1/players/equipped {uuids}` → who wears what (≤100);
`POST /v1/equip {name, serverId, cape, head}` (Mojang-verified). Tests:
`cd services/cosmetics && npm test` (Node 22, no installs).

## In game (1.21.1 first; other versions follow the working order)

- Files: `cosmetics/` (`Cosmetics` item list, `CosmeticsService`,
  `CapeTextures`, `HeadModels`, `CosmeticHeadLayer`), `gui/CosmeticsScreen`,
  `mixin/CosmeticCapeMixin`; rail entry "Cosmetics" in `OriginModMenuScreen`.
- Capes: textures from `node tools/cosmetics/export-capes.mjs` (re-run after
  any cape art change); animated ones swap frames every 100 ms (= LOOP/24).
  Head items are geometry ported 1:1 from the website models.
- Not yet in game: the cape particles the website shows.
- Testing without a live service: launch with
  `-Dorigin.cosmetics.api=<url>` to point at another deployment.

## Outside services the page uses (all fail soft)

- `headless.tebex.io`: baskets and checkout (only when live).
- `playerdb.co`: checks the typed name exists and gets the UUID. If it can't
  be reached, checkout is still allowed and Tebex checks the name instead.
- The real skin for the 3D preview, in order: the skin file on Mojang's
  `textures.minecraft.net` (its address and arm model, slim or classic, come
  from the lookup), then `crafthead.net`, then `mc-heads.net`. If none
  answer, the neutral mannequin stays. **Not Crafatar:** when it can't reach
  Mojang it quietly serves a default Steve, which is what players saw before
  2026-10-12.
- `crafthead.net`: the player-head image next to the name (unless the lookup supplies one).
- No WebGL: cards and preview fall back to flat 2D art.
