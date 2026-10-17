// Origin Store payments through Stripe Checkout. Contract: docs/STORE.md.
//
//   GET  /v1/store/catalog   what's for sale and its price (the store page shows these)
//   POST /v1/store/checkout  { uuid, name, items: [ids] } -> { url } of a Stripe
//                            Checkout page; the buyer pays there, never on our site
//   POST /v1/stripe/webhook  Stripe tells us a payment went through (grant) or was
//                            fully refunded / disputed (revoke)
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
//                          these Minecraft UUIDs may check out or be granted
//                          anything, so Stripe's fake test cards can never hand
//                          out real cosmetics (Will, 2026-10-17: test first)
import { json, fail, normUuid, slotOf, NAME_RE } from './common.js';

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

// THE price list: what Stripe charges. The store page loads it, so what's
// shown is what's charged. Ids are the contract with website/js/store.js ITEMS,
// website/js/cosmetics.js and the mod. Every price $5-$10 (Will, 2026-10-12).
export const CATALOG = {
  'cape-orbit':    { name: 'Orbit Cape',       cents: 999 },
  'cape-glitch':   { name: 'Glitch Cape',      cents: 999 },
  'cape-inferno':  { name: 'Inferno Cape',     cents: 899 },
  'cape-aurora':   { name: 'Aurora Cape',      cents: 899 },
  'cape-starfall': { name: 'Starfall Cape',    cents: 899 },
  'cape-origin':   { name: 'Origin Cape',      cents: 599 },
  'cape-golden':   { name: 'Golden Hour Cape', cents: 599 },
  'cape-frost':    { name: 'Frost Cape',       cents: 599 },
  'cape-void':     { name: 'Void Cape',        cents: 599 },
  'halo-orbit':    { name: 'Orbit Halo',       cents: 799 },
  'crown-pixel':   { name: 'Pixel Crown',      cents: 699 },
  'halo-gold':     { name: 'Golden Halo',      cents: 599 },
};

const testKey = env => /^(sk|rk)_test_/.test(env.STRIPE_SECRET_KEY || '');
const testers = env => new Set(String(env.STRIPE_TEST_PLAYERS || '').split(',').map(normUuid).filter(Boolean));

export function catalog(env) {
  const items = {};
  for (const [id, c] of Object.entries(CATALOG)) items[id] = { name: c.name, price: c.cents / 100 };
  return json({ currency: CURRENCY.toUpperCase(), open: !!env.STRIPE_SECRET_KEY, taxAdded: AUTOMATIC_TAX, items });
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

export async function checkout(env, body) {
  if (!env.STRIPE_SECRET_KEY) return fail(503, 'The store isn’t open yet.');
  const uuid = normUuid(body?.uuid);
  const name = String(body?.name ?? '');
  const ids = Array.isArray(body?.items) ? [...new Set(body.items)] : [];
  if (!uuid || !NAME_RE.test(name)) return fail(400, 'Type the Minecraft name these are for.');
  if (testKey(env) && !testers(env).has(uuid)) return fail(403, 'The store is being tested right now. Please check back soon.');
  if (!ids.length || ids.some(id => !CATALOG[id])) return fail(400, 'Something in your bag isn’t on sale. Remove it and try again.');

  // Paying twice for the same cosmetic gets nothing new, so stop it here.
  const owned = new Set((await env.DB.prepare('SELECT item FROM owned WHERE uuid = ?').bind(uuid).all()).results.map(r => r.item));
  const dupe = ids.find(id => owned.has(id));
  if (dupe) return fail(409, `${name} already owns the ${CATALOG[dupe].name}. Remove it from your bag.`);

  const meta = { uuid, name, items: ids.join(',') };
  const r = await fetch(`${STRIPE}/checkout/sessions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.STRIPE_SECRET_KEY}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form({
      mode: 'payment',
      success_url: `${STORE_PAGE}?purchase=done`,
      cancel_url: `${STORE_PAGE}?purchase=cancelled`,
      client_reference_id: uuid,
      // Short-lived, so an abandoned checkout can't be paid hours later for
      // something bought since (30 min is Stripe's minimum).
      expires_at: Math.floor(Date.now() / 1000) + 30 * 60,
      automatic_tax: { enabled: AUTOMATIC_TAX },
      line_items: ids.map(id => ({
        quantity: 1,
        price_data: {
          currency: CURRENCY,
          unit_amount: CATALOG[id].cents,
          tax_behavior: 'exclusive',
          product_data: { name: CATALOG[id].name, description: `For ${name} in Origin Client`, tax_code: TAX_CODE },
        },
      })),
      metadata: meta,
      // Also on the payment itself, so refunds and disputes (which only see
      // the payment) and the Stripe dashboard show who it was for.
      payment_intent_data: { metadata: meta, description: `Origin cosmetics for ${name}` },
    }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.url) {
    console.error('origin-cosmetics: Stripe checkout failed', r.status, j?.error?.message);
    return fail(502, 'Checkout isn’t available right now. Nothing was charged; please try again in a minute.');
  }
  return json({ url: j.url });
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
  if (!livemode && !testers(env).has(uuid)) {
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

export async function webhook(request, env) {
  const raw = await request.text();
  if (!(await verifySignature(env.STRIPE_WEBHOOK_SECRET, request.headers.get('Stripe-Signature'), raw))) {
    return fail(400, 'bad signature');
  }
  const event = JSON.parse(raw);
  const obj = event.data?.object || {};
  switch (event.type) {
    // Cards and wallets are paid on completion; slower methods report later.
    case 'checkout.session.completed':
      if (obj.payment_status === 'paid') await grant(env, obj, event.livemode);
      break;
    case 'checkout.session.async_payment_succeeded':
      await grant(env, obj, event.livemode);
      break;
    // Only a FULL refund takes items back; a partial refund is a manual call.
    case 'charge.refunded':
      if (obj.refunded) await revoke(env, obj.payment_intent);
      break;
    case 'charge.dispute.created':
      await revoke(env, obj.payment_intent);
      break;
  }
  // Any other event is acknowledged so Stripe stops retrying it.
  return json({ received: true });
}
