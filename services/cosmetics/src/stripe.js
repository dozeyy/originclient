// Origin Store payments through Stripe Checkout. Contract: docs/STORE.md.
//
//   GET  /v1/store/catalog   what's for sale and its price (the store page shows these)
//   POST /v1/store/checkout  { uuid, name, items: [ids] } -> { url } of a Stripe
//                            Checkout page; the buyer pays there, never on our site
//   POST /v1/plus/checkout   { uuid, name, plan: 'month'|'year' } -> { url } of a
//                            Stripe Checkout page that starts an Origin+ membership
//   POST /v1/stripe/webhook  Stripe tells us a payment went through (grant), was
//                            fully refunded / disputed (revoke), or an Origin+
//                            membership started, renewed or ended (plusChanged)
//
// The cosmetic goes to the Minecraft UUID typed on the store page (gifts are
// fine). It rides on the payment as metadata, so the webhook alone knows who
// gets what: nothing is stored until Stripe says the money arrived.
//
// Secrets (Cloudflare dashboard, never the repo):
//   STRIPE_SECRET_KEY      creates Checkout pages (a restricted key with
//                          Checkout Sessions: Write is enough)
//   STRIPE_WEBHOOK_SECRET  proves a webhook call really came from Stripe (whsec_...)
// Var (wrangler.toml, not secret):
//   STRIPE_TEST_PLAYERS    while the key is a TEST key (sk_test_/rk_test_), only
//                          these players may check out or be granted anything,
//                          so Stripe's fake test cards can never hand out real
//                          cosmetics (Will, 2026-10-17: test first). Comma-
//                          separated Minecraft names or UUIDs; a name is checked
//                          against Mojang at checkout, so nobody can borrow it.
import { json, fail, normUuid, slotOf, NAME_RE } from './common.js';
import { CATALOG } from './catalog.gen.js';

const STRIPE = 'https://api.stripe.com/v1';
const STORE_PAGE = 'https://originclient.org/store.html';
const CURRENCY = 'usd';
// Will, 2026-10-17: Stripe Tax works out the right tax for each buyer and adds
// it on top of the list price at checkout (needs Stripe Tax switched on).
const AUTOMATIC_TAX = true;
// Stripe's tax category for every item: "Video Games - downloaded - non
// subscription - with permanent rights", the closest fit for in-game items
// that are kept for good. Set here so the dashboard default doesn't matter.
const TAX_CODE = 'txcd_10201000';
const SIGNATURE_TOLERANCE_S = 300;   // Stripe's own default for webhook replays

// THE price list lives in catalog.gen.js, generated from cosmetics/items/*
// (cosmetics/FORMAT.md). Every price $5-$10 (Will, 2026-10-12). SALE = what can
// be bought: on sale and not members-only.
export { CATALOG };
export const forSale = id => Object.hasOwn(CATALOG, id) && CATALOG[id].onSale && !CATALOG[id].plusOnly;

// ------------------------------------------------------------------ Origin+
// Will, 2026-10-17. Every perk is cosmetic, never a gameplay advantage:
// the members-only Origin+ cape (PLUS_CAPE) and badge, MEMBER_PERCENT_OFF off
// the store, and no ads in the launcher. Prices are what Stripe charges and
// the store page shows (through the catalog), same as CATALOG.
export const PLUS = {
  month: { cents: 499 },
  year:  { cents: 3999 },
};
export const PLUS_CAPE = 'cape-plus';        // never sold: members wear it while they're members
export const MEMBER_PERCENT_OFF = 20;
// The Stripe product every membership is a price of (made once through the
// Stripe API, id chosen to match; a test-mode key needs the same product made
// in test mode). Its tax category lives on the product: "Video Games -
// Streamed - Subscription - With Conditional Rights" (txcd_10201004).
const PLUS_PRODUCT = 'origin-plus';
// Stripe's hosted page where members sign in with their checkout email to
// cancel, change card or see receipts (billing portal config "Origin+ members").
// Signing in by email is what stops anyone who knows a name from cancelling it.
export const PLUS_MANAGE_URL = 'https://billing.stripe.com/p/login/eVqfZi2uF97zdlEgr40co00';
// past_due: a renewal failed and Stripe is retrying the card; keep the perks
// meanwhile. Stripe cancels the membership if the retries all fail.
const MEMBER_STATUSES = ['active', 'trialing', 'past_due'];
// If a renewal's webhook is ever lost, perks outlast the paid period by this
// much before stopping, instead of vanishing the second it ends.
const PLUS_GRACE_MS = 2 * 24 * 3600 * 1000;
const memberSql = `status IN (${MEMBER_STATUSES.map(s => `'${s}'`).join(',')}) AND period_end * 1000 + ${PLUS_GRACE_MS} > ?`;

export async function isMember(env, uuid, now = Date.now()) {
  return !!(await env.DB.prepare(`SELECT 1 FROM plus WHERE uuid = ? AND ${memberSql}`).bind(uuid, now).first());
}
// Which of these players are members, in one query (the /equipped batch).
export async function membersAmong(env, uuids, now = Date.now()) {
  if (!uuids.length) return new Set();
  const rows = await env.DB.prepare(`SELECT DISTINCT uuid FROM plus WHERE uuid IN (${uuids.map(() => '?').join(',')}) AND ${memberSql}`)
    .bind(...uuids, now).all();
  return new Set(rows.results.map(r => r.uuid));
}
const memberCents = cents => Math.round(cents * (100 - MEMBER_PERCENT_OFF) / 100);

// ------------------------------------------------------------- test lock
const testKey = env => /^(sk|rk)_test_/.test(env.STRIPE_SECRET_KEY || '');
const testList = env => String(env.STRIPE_TEST_PLAYERS || '').split(',').map(s => s.trim()).filter(Boolean);
const testUuids = env => new Set(testList(env).map(normUuid).filter(Boolean));
const testNames = env => new Set(testList(env).filter(s => !normUuid(s)).map(s => s.toLowerCase()));
// Mojang is the authority on which UUID a name belongs to; any failure says no.
async function mojangUuid(name) {
  try {
    const r = await fetch(`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(name)}`);
    return r.ok ? normUuid((await r.json())?.id) : null;
  } catch { return null; }
}
async function mayTest(env, uuid, name) {
  if (testUuids(env).has(uuid)) return true;
  return testNames(env).has(name.toLowerCase()) && (await mojangUuid(name)) === uuid;
}
// For webhooks: a test session or subscription only exists if checkout let it
// through (only our key makes them), so its name was already checked against
// Mojang there.
const isTester = (env, uuid, name) => testUuids(env).has(uuid) || testNames(env).has(String(name || '').toLowerCase());

export function catalog(env) {
  const items = {};
  for (const [id, c] of Object.entries(CATALOG)) if (forSale(id)) items[id] = { name: c.name, price: c.cents / 100, memberPrice: memberCents(c.cents) / 100 };
  const plus = { month: PLUS.month.cents / 100, year: PLUS.year.cents / 100, percentOff: MEMBER_PERCENT_OFF, manageUrl: PLUS_MANAGE_URL };
  return json({ currency: CURRENCY.toUpperCase(), open: !!env.STRIPE_SECRET_KEY, taxAdded: AUTOMATIC_TAX, items, plus });
}

// Stripe's API takes form fields with bracketed keys: line_items[0][quantity]=1.
function form(obj, prefix = '', out = new URLSearchParams()) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}[${k}]` : k;
    if (v !== null && typeof v === 'object') form(v, key, out);
    else if (v !== undefined) out.append(key, String(v));
  }
  return out;
}

// Makes a Checkout page; the answer is its url, or a sentence for the player.
async function checkoutPage(env, params) {
  const r = await fetch(`${STRIPE}/checkout/sessions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.STRIPE_SECRET_KEY}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form({
      client_reference_id: params.metadata.uuid,
      // Short-lived, so an abandoned checkout can't be paid hours later for
      // something bought since. Stripe's minimum is 30 min counted from when
      // the request ARRIVES, so 35 leaves room for the trip there.
      expires_at: Math.floor(Date.now() / 1000) + 35 * 60,
      automatic_tax: { enabled: AUTOMATIC_TAX },
      ...params,
    }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.url) {
    console.error('origin-cosmetics: Stripe checkout failed', r.status, j?.error?.message);
    return fail(502, 'Checkout isn’t available right now. Nothing was charged; please try again in a minute.');
  }
  return json({ url: j.url });
}

export async function checkout(env, body) {
  if (!env.STRIPE_SECRET_KEY) return fail(503, 'The store isn’t open yet.');
  const uuid = normUuid(body?.uuid);
  const name = String(body?.name ?? '');
  const ids = Array.isArray(body?.items) ? [...new Set(body.items)] : [];
  if (!uuid || !NAME_RE.test(name)) return fail(400, 'Type the Minecraft name these are for.');
  if (testKey(env) && !(await mayTest(env, uuid, name))) return fail(403, 'The store is being tested right now. Please check back soon.');
  if (!ids.length || ids.some(id => !forSale(id))) return fail(400, 'Something in your bag isn’t on sale. Remove it and try again.');

  // Paying twice for the same cosmetic gets nothing new, so stop it here.
  const owned = new Set((await env.DB.prepare('SELECT item FROM owned WHERE uuid = ?').bind(uuid).all()).results.map(r => r.item));
  const dupe = ids.find(id => owned.has(id));
  if (dupe) return fail(409, `${name} already owns the ${CATALOG[dupe].name}. Remove it from your bag.`);

  // The Origin+ discount goes by who the items are FOR, the only player the
  // store knows (gifting to a member gets it too, which is fine).
  const member = await isMember(env, uuid);
  const meta = { uuid, name, items: ids.join(','), ...(member ? { plus: '1' } : {}) };
  return checkoutPage(env, {
    mode: 'payment',
    success_url: `${STORE_PAGE}?purchase=done`,
    cancel_url: `${STORE_PAGE}?purchase=cancelled`,
    line_items: ids.map(id => ({
      quantity: 1,
      price_data: {
        currency: CURRENCY,
        unit_amount: member ? memberCents(CATALOG[id].cents) : CATALOG[id].cents,
        tax_behavior: 'exclusive',
        product_data: {
          name: CATALOG[id].name,
          description: `For ${name} in Origin Client${member ? ` (Origin+ ${MEMBER_PERCENT_OFF}% off)` : ''}`,
          tax_code: TAX_CODE,
        },
      },
    })),
    metadata: meta,
    // Also on the payment itself, so refunds and disputes (which only see
    // the payment) and the Stripe dashboard show who it was for.
    payment_intent_data: { metadata: meta, description: `Origin cosmetics for ${name}` },
  });
}

// Starts a membership for the typed player. Membership is then tracked only
// from Stripe's subscription webhooks (plusChanged), never from this call.
export async function plusCheckout(env, body) {
  if (!env.STRIPE_SECRET_KEY) return fail(503, 'Origin+ isn’t open yet.');
  const uuid = normUuid(body?.uuid);
  const name = String(body?.name ?? '');
  const plan = Object.hasOwn(PLUS, String(body?.plan)) ? String(body.plan) : null;
  if (!uuid || !NAME_RE.test(name)) return fail(400, 'Type the Minecraft name the membership is for.');
  if (!plan) return fail(400, 'Pick monthly or yearly.');
  if (testKey(env) && !(await mayTest(env, uuid, name))) return fail(403, 'Origin+ is being tested right now. Please check back soon.');
  if (await isMember(env, uuid)) return fail(409, `${name} is already an Origin+ member. Use “Manage or cancel” to change it.`);

  const meta = { uuid, name, plan };
  return checkoutPage(env, {
    mode: 'subscription',
    success_url: `${STORE_PAGE}?plus=done`,
    cancel_url: `${STORE_PAGE}?plus=cancelled`,
    line_items: [{
      quantity: 1,
      price_data: {
        currency: CURRENCY,
        unit_amount: PLUS[plan].cents,
        tax_behavior: 'exclusive',
        product: PLUS_PRODUCT,
        recurring: { interval: plan },
      },
    }],
    metadata: meta,
    // On the subscription too: its webhooks only see the subscription.
    subscription_data: { metadata: meta, description: `Origin+ for ${name}` },
  });
}

// --------------------------------------------------------------- webhook
const hex = buf => [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');

// Stripe-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">[,v1=...]
export async function verifySignature(secret, header, raw, nowMs = Date.now()) {
  if (!secret || !header) return false;
  const parts = header.split(',').map(p => p.split('='));
  const t = Number(parts.find(([k]) => k === 't')?.[1]);
  const sigs = parts.filter(([k]) => k === 'v1').map(([, v]) => v);
  if (!t || !sigs.length || Math.abs(nowMs / 1000 - t) > SIGNATURE_TOLERANCE_S) return false;
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const want = hex(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(`${t}.${raw}`)));
  // Constant-time compare, so timing can't reveal how much of a guess matched.
  return sigs.some(s => s.length === want.length && [...s].reduce((d, c, i) => d | (c.charCodeAt(0) ^ want.charCodeAt(i)), 0) === 0);
}

async function grant(env, session, livemode) {
  const uuid = normUuid(session.metadata?.uuid);
  const ids = String(session.metadata?.items || '').split(',').filter(id => CATALOG[id]);
  if (!uuid || !ids.length) {
    // Not one of ours (another Stripe product) or damaged metadata: say so loudly, keep nothing.
    console.error('origin-cosmetics: paid session without Origin metadata', session.id);
    return;
  }
  if (!livemode && !isTester(env, uuid, session.metadata?.name)) {
    console.warn('origin-cosmetics: test payment for a player not in STRIPE_TEST_PLAYERS ignored', session.id);
    return;
  }
  const payment = String(session.payment_intent || session.id);
  // Stripe retries a "paid" event it didn't hear back about, even after a refund.
  if (await env.DB.prepare('SELECT 1 FROM revoked WHERE payment = ?').bind(payment).first()) return;
  const now = Date.now();
  // INSERT OR IGNORE: Stripe may send the same event more than once.
  const results = await env.DB.batch(ids.map(id =>
    env.DB.prepare('INSERT OR IGNORE INTO owned (uuid, item, payment, at) VALUES (?, ?, ?, ?)').bind(uuid, id, payment, now)));
  // Already owned from ANOTHER payment (two checkouts paid for the same item):
  // the buyer paid for nothing, so flag it for a manual refund.
  for (const [i, r] of results.entries()) {
    if (r?.meta?.changes) continue;
    const prev = await env.DB.prepare('SELECT payment FROM owned WHERE uuid = ? AND item = ?').bind(uuid, ids[i]).first('payment');
    if (prev !== payment) console.error(`origin-cosmetics: REFUND NEEDED, ${uuid} paid twice for ${ids[i]} (kept ${prev}, extra ${payment})`);
  }
}

// Everything bought in that payment goes away, and comes off if worn. The
// payment is remembered, so a late or repeated "paid" event can't undo this.
// A won dispute doesn't give items back by itself: re-grant by hand.
async function revoke(env, paymentIntent) {
  if (!paymentIntent) return;
  const rows = (await env.DB.prepare('SELECT uuid, item FROM owned WHERE payment = ?').bind(String(paymentIntent)).all()).results;
  const stmts = [
    env.DB.prepare('INSERT OR IGNORE INTO revoked (payment, at) VALUES (?, ?)').bind(String(paymentIntent), Date.now()),
    env.DB.prepare('DELETE FROM owned WHERE payment = ?').bind(String(paymentIntent)),
  ];
  for (const { uuid, item } of rows) {
    const slot = slotOf(item);
    stmts.push(env.DB.prepare(`UPDATE equipped SET ${slot} = NULL WHERE uuid = ? AND ${slot} = ?`).bind(uuid, item));
  }
  await env.DB.batch(stmts);
}

// A membership started, renewed, changed or ended. Rows are only ever updated
// by a NEWER event, so Stripe sending events out of order can't bring an old
// status back, and an ended membership stays ended (Stripe never revives one).
// Events stamped the same second (created + paid often are) can't go back to
// `incomplete`, the only status a membership never returns to.
// A failed renewal still moves period_end on (Stripe starts the new period when
// it bills it), so past_due members keep their perks while the card is retried.
async function plusChanged(env, sub, livemode, eventAt) {
  const uuid = normUuid(sub.metadata?.uuid);
  const item = sub.items?.data?.[0];
  const product = typeof item?.price?.product === 'string' ? item.price.product : item?.price?.product?.id;
  if (!uuid || product !== PLUS_PRODUCT) {
    console.error('origin-cosmetics: subscription without Origin+ metadata', sub.id);
    return;
  }
  if (!livemode && !isTester(env, uuid, sub.metadata?.name)) {
    console.warn('origin-cosmetics: test subscription for a player not in STRIPE_TEST_PLAYERS ignored', sub.id);
    return;
  }
  // Newer Stripe API versions keep the period on the item, older on the subscription.
  const periodEnd = Number(item?.current_period_end ?? sub.current_period_end) || 0;
  await env.DB.prepare(`INSERT INTO plus (subscription, uuid, name, customer, status, period_end, event_at) VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT (subscription) DO UPDATE SET status = excluded.status, period_end = excluded.period_end, event_at = excluded.event_at
    WHERE plus.status NOT IN ('canceled', 'incomplete_expired') AND (excluded.event_at > plus.event_at
      OR (excluded.event_at = plus.event_at AND NOT (excluded.status = 'incomplete' AND plus.status != 'incomplete')))`)
    .bind(sub.id, uuid, String(sub.metadata?.name || ''), String(sub.customer || ''), String(sub.status), periodEnd, Number(eventAt) || 0).run();
  // Two checkouts finished for the same player: they're paying twice for one membership.
  const live = await env.DB.prepare(`SELECT subscription FROM plus WHERE uuid = ? AND ${memberSql}`).bind(uuid, Date.now()).all();
  if (live.results.length > 1) {
    console.error(`origin-cosmetics: REFUND NEEDED, ${uuid} has ${live.results.length} Origin+ memberships (${live.results.map(r => r.subscription).join(', ')}); cancel and refund all but one`);
  }
}

export async function webhook(request, env) {
  const raw = await request.text();
  if (!(await verifySignature(env.STRIPE_WEBHOOK_SECRET, request.headers.get('Stripe-Signature'), raw))) {
    return fail(400, 'bad signature');
  }
  const event = JSON.parse(raw);
  const obj = event.data?.object || {};
  switch (event.type) {
    // Cards and wallets are paid on completion; slower methods report later.
    // Origin+ checkouts are handled by the subscription events below.
    case 'checkout.session.completed':
      if (obj.mode !== 'subscription' && obj.payment_status === 'paid') await grant(env, obj, event.livemode);
      break;
    case 'checkout.session.async_payment_succeeded':
      if (obj.mode !== 'subscription') await grant(env, obj, event.livemode);
      break;
    // Only a FULL refund takes items back; a partial refund is a manual call.
    // Refunding an Origin+ payment doesn't end the membership: cancel it in
    // the Stripe dashboard too (the cancel then arrives as an event below).
    case 'charge.refunded':
      if (obj.refunded) await revoke(env, obj.payment_intent);
      break;
    case 'charge.dispute.created':
      await revoke(env, obj.payment_intent);
      break;
    case 'customer.subscription.created':
    case 'customer.subscription.updated':
    case 'customer.subscription.deleted':
      await plusChanged(env, obj, event.livemode, event.created);
      break;
  }
  // Any other event is acknowledged so Stripe stops retrying it.
  return json({ received: true });
}
