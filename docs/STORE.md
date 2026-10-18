# The Origin Store (originclient.org/store)

The website's cosmetics shop. Players browse capes, halos and a crown, try them
on in a 3D preview, type the Minecraft name the items are for, and pay on
**Stripe Checkout** (card, Apple Pay, Google Pay; Stripe Tax adds sales tax
where it applies, Will 2026-10-17). The player's Minecraft UUID rides on the
payment, so the purchase belongs to that player (gifting = typing a friend's
name). Tebex was dropped 2026-10-17: its review rejected the custom delivery
("invalid or missing deliverables"), and nothing was ever sold through it.

**Status (2026-10-17): switched on, waiting on Will's live Stripe keys.** Will
skipped test mode ("do it with the real live stuff"). `STORE.live = true`, so
the page asks the service for its catalog; until `STRIPE_SECRET_KEY` is in
Cloudflare the service says `open: false` and Checkout keeps saying "Soon".
The moment the key lands, the store opens by itself (no deploy).

## How a purchase reaches the game

```
store page ──{uuid, name, items}──▶ services/cosmetics POST /v1/store/checkout
  (prices from stripe.js CATALOG; refuses items that player already owns)
        │ Stripe Checkout Session, uuid + items in its metadata
        ▼
buyer pays on checkout.stripe.com ──▶ back to /store?purchase=done
        │
Stripe webhook ──▶ POST /v1/stripe/webhook (signature checked)
  checkout.session.completed (paid) / async_payment_succeeded → owned rows
  charge.refunded (FULL refund only) / charge.dispute.created → rows for that
  payment removed and taken off (owned.payment = the Stripe payment intent)
  and remembered in `revoked`, so a retried "paid" event can't give them back.
  A WON dispute does not re-grant: give the items back by hand. Two paid
  checkouts for one item log "REFUND NEEDED" (Cloudflare Worker logs).
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

## Origin+ membership (Will, 2026-10-17)

**$4.99/month or $39.99/year**, a Stripe subscription for the typed Minecraft
name (gifts fine). Perks are cosmetic only, never a gameplay advantage:

| Perk | Where it lives | State |
|---|---|---|
| Members-only **Origin+ cape** (`cape-plus`, never sold) | service: members own it while they're members; a worn one is hidden (not deleted) when the membership ends and comes back on rejoining | service done; in-game art + item: game phase |
| **Origin+ badge** (ring mark by the name in friends + above the head) | service: `plus` on `/v1/players/<uuid>` and every `/equipped` entry (members listed even wearing nothing) | service done; drawing it: game phase |
| **20% off the store** | service: `checkout` charges `memberPrice` when the items are FOR a member (`metadata.plus=1`); catalog shows both prices | live |
| **No launcher ads** | launcher hides the home-banner ad for members | launcher update |

```
store page ──{uuid, name, plan}──▶ POST /v1/plus/checkout (409 if already a member)
        │ Checkout Session, mode=subscription, product `origin-plus`,
        │ uuid/name on subscription_data.metadata
        ▼
customer.subscription.created/updated/deleted ──▶ `plus` table (one row per
  subscription; only a NEWER event updates a row; a canceled one stays ended)
member = a row with status active/trialing/past_due and period_end + 2 days > now
```

- **Stripe pieces** (made 2026-10-17 by Claude through the API): product
  `origin-plus` (tax code `txcd_10201004`, "Video Games - Streamed -
  Subscription - With Conditional Rights": **Will/accountant to confirm**);
  billing portal configuration "Origin+ members" (cancel at period end, change
  card, receipts, change email) with a hosted sign-in page,
  `https://billing.stripe.com/p/login/eVqfZi2uF97zdlEgr40co00`
  (`PLUS_MANAGE_URL`). Members sign in with their checkout email, so knowing
  a player's name is not enough to cancel their membership.
- **Refunds:** refunding an Origin+ payment does NOT end the membership.
  Cancel the subscription in the dashboard too (Customers → the customer →
  Cancel subscription); the cancel event ends the perks. Two memberships for
  one player log "REFUND NEEDED" (Worker logs).
- **Test mode:** same lock as the store (`STRIPE_TEST_PLAYERS`); a test key
  also needs a product with id `origin-plus` made in test mode.
- Prices: `PLUS` and `MEMBER_PERCENT_OFF` in stripe.js; the page reads them
  from the catalog.

## Free codes (Will, 2026-10-18)
Will makes one-time codes on his PC; a player types one under "Have a code?"
on the store page and gets it straight away: no card, no Stripe.
- **Kinds:** `bag` gives everything in the player's bag (any cosmetic on sale);
  `plus` gives Origin+ for good (a `plus` row with subscription `code:<CODE>` and
  a period_end of year 9999, so every perk follows from isMember).
- **Each code works once.** A redeem that fails (empty bag, owned item, typo)
  never spends it. Codes and who used them: the `codes` table (migration 0006).
- **Making them:** `tools/store/make-codes.ps1` (`-Count N`, `-Plus`), which calls
  `POST /v1/admin/codes` with the admin key from `%USERPROFILE%\.origin-admin-key`.
  The repo only holds the key's SHA-256 (wrangler.toml `CODES_ADMIN_SHA256`); to
  replace the key, put the new key's hash there and push.
- Code: `services/cosmetics/src/codes.js`; tests: `test/codes.test.mjs`.



- Animated capes (moving art + particles): Orbit $9.99, Glitch $9.99,
  Inferno $8.99, Aurora $8.99, Starfall $8.99
- Still capes $5.99: Origin, Golden Hour, Frost, Void
- Head: Orbit Halo $7.99, Pixel Crown $6.99, Golden Halo $5.99
- **Wings: not on sale** (Will, 2026-10-12: "no wings for now"). Their art and
  flap animation are still in `cosmetics.js`. To bring them back, add their
  ids (`wings-ember`, `wings-void`, `wings-frost`) to `ITEMS` and a Wings tab
  to `store.html` (served at /store).

Stripe's product list (Dashboard → Product catalog) mirrors this lineup as
`origin-<item id>` products (made 2026-10-17 because Stripe's setup asked for
them). They're for the dashboard only: checkout charges the prices in
`services/cosmetics/src/stripe.js` CATALOG, so change a price there AND in Stripe.

All art is placeholder, painted in code. Real artwork replaces one painter
function per item.

## Turning payments on (Will's steps)

The account holder must be an adult (ID + bank account for payouts).

1. **Account:** sign up at stripe.com and finish activation. Business website
   `https://originclient.org`; what you sell: "Cosmetic items (capes and hats)
   for the Origin Minecraft client. Looks only, no gameplay advantage."
2. **Stripe Tax:** Dashboard → Tax → set it up: your business address (the
   one thing it was missing on 2026-10-17). Prices are already **exclusive**
   of tax (tax added on top), and the service puts the tax category on every
   item itself (`txcd_10201000`, video game items kept for good), so the
   dashboard's default category doesn't matter. Add registrations where Stripe's monitoring says you
   need them. The service sends `automatic_tax` on every checkout, so checkout
   fails ("Checkout isn't available right now") until Stripe Tax is on.
3. **Receipts:** Settings → Customer emails → turn on **Successful payments**
   (the store's thank-you note promises an emailed receipt).
4. **Webhook:** (created 2026-10-17 by Claude via the API; its first secret was rolled by Will 2026-10-17) Developers → Webhooks → add an endpoint
   `https://origin-cosmetics.will-f11.workers.dev/v1/stripe/webhook` with the
   events `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
   `charge.refunded`, `charge.dispute.created`, and for Origin+
   `customer.subscription.created`, `.updated`, `.deleted`. Copy its **signing secret** (`whsec_...`).
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

### Testing first (Will, 2026-10-17)

Optional: do steps 2–6 on a Stripe **sandbox / test mode** first (keys start
`rk_test_`, secrets `whsec_` from the test webhook). While the service holds a
test key, only the players in `STRIPE_TEST_PLAYERS` (services/cosmetics/
wrangler.toml: comma-separated Minecraft names or UUIDs, not secret; a name is
checked against Mojang so nobody can borrow it) can check out or be granted
anything, so Stripe's fake cards can't hand out real cosmetics. Current list:
dozeyy, bxlt, lozeyy, silo5. Open `originclient.org/store`, pay with
`4242 4242 4242 4242` (any future date, any CVC), see the item in game, refund
it in the Stripe dashboard, see it go. Then swap both Cloudflare secrets for
the live ones. With a LIVE key the list does nothing: everyone can buy.

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
`POST /v1/plus/checkout {uuid, name, plan}`, `POST /v1/stripe/webhook` (Stripe-signed; stripe.js). Tests:
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
