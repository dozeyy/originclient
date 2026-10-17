# The Origin Store (originclient.org/store.html)

The website's cosmetics shop. Players browse capes, halos and a crown, try them
on in a 3D preview, type the Minecraft name the items are for, and pay on
**Stripe Checkout** (card, Apple Pay, Google Pay; Stripe Tax adds sales tax
where it applies, Will 2026-10-17). The player's Minecraft UUID rides on the
payment, so the purchase belongs to that player (gifting = typing a friend's
name). Tebex was dropped 2026-10-17: its review rejected the custom delivery
("invalid or missing deliverables"), and nothing was ever sold through it.

**Status (2026-10-17): showroom; Stripe built, waiting on Will's Stripe account.**
Browsing, trying on, the name check and the bag all work; Checkout shows
"Soon" and the page never calls the service until `STORE.live` is turned on.
Order to go live: Will's Stripe steps below → Claude flips `STORE.live` →
one real purchase to Will's own name shows up in game → refund it → gone.

## How a purchase reaches the game

```
store page ──{uuid, name, items}──▶ services/cosmetics POST /v1/store/checkout
  (prices from stripe.js CATALOG; refuses items that player already owns)
        │ Stripe Checkout Session, uuid + items in its metadata
        ▼
buyer pays on checkout.stripe.com ──▶ back to store.html?purchase=done
        │
Stripe webhook ──▶ POST /v1/stripe/webhook (signature checked)
  checkout.session.completed (paid) / async_payment_succeeded → owned rows
  charge.refunded (FULL refund only) / charge.dispute.created → rows for that
  payment removed and taken off (owned.payment = the Stripe payment intent)
        │
Origin mod ── reads website/cosmetics/service.json for the service address ──
  Cosmetics screen: pick what you wear (Origin sign-in token) ── every Origin
  player looks up who wears what (batched, cached 5 min) and draws capes
  (vanilla cape slot) + head items
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
| `website/js/store.js` | **the shop window**: `STORE` switch, `ITEMS` (name, blurb, list price), name lookup, bag, checkout call |
| `services/cosmetics/src/stripe.js` | **what's charged**: `CATALOG` (same ids and prices as `ITEMS`; a service test fails if they differ), checkout, webhook |
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

The account holder must be an adult (ID + bank account for payouts).

1. **Account:** sign up at stripe.com and finish activation. Business website
   `https://originclient.org`; what you sell: "Cosmetic items (capes and hats)
   for the Origin Minecraft client. Looks only, no gameplay advantage."
2. **Stripe Tax:** Dashboard → Tax → set it up: your address, default product
   tax code for digital goods / video game items, prices **exclusive** of tax
   (tax added on top). Add registrations where Stripe's monitoring says you
   need them. The service sends `automatic_tax` on every checkout, so checkout
   fails ("Checkout isn't available right now") until Stripe Tax is on.
3. **Receipts:** Settings → Customer emails → turn on **Successful payments**
   (the store's thank-you note promises an emailed receipt).
4. **Webhook:** Developers → Webhooks → add an endpoint
   `https://origin-cosmetics.will-f11.workers.dev/v1/stripe/webhook` with the
   events `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
   `charge.refunded`, `charge.dispute.created`. Copy its **signing secret** (`whsec_...`).
5. **API key:** Developers → API keys → **Create restricted key** with
   Write on **Checkout Sessions**, **Products** and **Prices** (everything else
   None). Copy it (`rk_live_...`).
6. **Into Cloudflare, never chat or the repo:** Workers & Pages →
   `origin-cosmetics` → Settings → Variables and Secrets → add **Secret**
   `STRIPE_SECRET_KEY` (step 5) and **Secret** `STRIPE_WEBHOOK_SECRET` (step 4).
   Delete the old `TEBEX_SECRET`.
7. Tell Claude. `GET /v1/store/catalog` then says `"open": true` (the
   cosmetics-service smoke test prints it); Claude flips `STORE.live = true`.
8. Buy the cheapest item for your own name, see it in game, refund it from the
   Stripe dashboard (Payments → the payment → Refund), see it disappear.
   Then close the Tebex webstore.

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
5. **Stripe secrets:** see "Turning payments on", step 6.
6. **Live 2026-10-12: `https://origin-cosmetics.will-f11.workers.dev`** (deploy
   needed token permissions Account → Workers Scripts Edit + D1 Edit + Account
   Settings Read). Give Claude the Worker's address;
   it goes in `website/cosmetics/service.json` (`"api"`), which switches the
   in-game side on with no mod release. Empty `"api"` = cosmetics off.

Service API (all JSON, CORS open): `GET /v1/players/<uuid>` → owned +
equipped; `POST /v1/players/equipped {uuids}` → who wears what (≤100);
`POST /v1/equip {cape, head}` (Origin sign-in Bearer token);
`GET /v1/store/catalog`, `POST /v1/store/checkout {uuid, name, items}`,
`POST /v1/stripe/webhook` (Stripe-signed; stripe.js). Tests:
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

- The cosmetics service (address from `website/cosmetics/service.json`):
  prices and checkout, only when live. Unreachable = list prices shown and
  Checkout says it can't be reached right now.
- `playerdb.co`: checks the typed name exists and gets the UUID the items go
  to. Checkout waits for a found name; if the lookup can't answer, the buyer
  is asked to try again in a moment.
- The real skin for the 3D preview, in order: the skin file on Mojang's
  `textures.minecraft.net` (its address and arm model, slim or classic, come
  from the lookup), then `crafthead.net`, then `mc-heads.net`. If none
  answer, the neutral mannequin stays. **Not Crafatar:** when it can't reach
  Mojang it quietly serves a default Steve, which is what players saw before
  2026-10-12.
- `crafthead.net`: the player-head image next to the name (unless the lookup supplies one).
- No WebGL: cards and preview fall back to flat 2D art.
