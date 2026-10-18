// Runs the real Worker code against a real SQLite database (Node's built-in
// node:sqlite, wrapped to look like Cloudflare D1), with Stripe faked and
// Mojang's signatures made by test/mojang.mjs. `npm test` (node --test) — no packages to install.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { handle, slotOf } from '../src/index.js';
import { CATALOG, PLUS_MANAGE_URL, forSale, verifySignature } from '../src/stripe.js';
import { d1 } from './d1.mjs';   // D1 stand-in with every migration applied
import { authBody, seedKeys } from './mojang.mjs';

// What a player wears with nothing on, slot by slot (cosmetics/FORMAT.md).
const BARE = { cape: null, head: null, face: null, back: null, theme: null };

const UUID = '069a79f444e94726a5befca90e38aaf5';
const DASHED = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
const WHSEC = 'whsec_test';
let env, calls, routes;

// Fake network: route by URL prefix; records every call.
function fakeFetch() {
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method || 'GET', headers: init.headers || {}, body: init.body });
    for (const [prefix, reply] of routes) {
      if (String(url).startsWith(prefix)) {
        const { status = 200, body } = typeof reply === 'function' ? reply(String(url)) : reply;
        return new Response(body === undefined ? null : JSON.stringify(body), { status });
      }
    }
    throw new Error(`unexpected fetch ${url}`);
  };
}

beforeEach(() => {
  env = { DB: d1(), STRIPE_SECRET_KEY: 'sk_test_x', STRIPE_WEBHOOK_SECRET: WHSEC, STRIPE_TEST_PLAYERS: DASHED };
  seedKeys(env);
  calls = [];
  routes = [];
  fakeFetch();
});

const api = (method, path, body, token) => handle(new Request(`https://svc.test${path}`, {
  method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  body: body && JSON.stringify(body),
}), env);

// A webhook call signed the way Stripe signs them.
function sign(raw, secret = WHSEC, t = Math.floor(Date.now() / 1000)) {
  return `t=${t},v1=${createHmac('sha256', secret).update(`${t}.${raw}`).digest('hex')}`;
}
function stripeEvent(type, object, signature, created = Math.floor(Date.now() / 1000)) {
  const raw = JSON.stringify({ id: 'evt_1', type, created, data: { object } });
  return handle(new Request('https://svc.test/v1/stripe/webhook', {
    method: 'POST', headers: { 'Stripe-Signature': signature ?? sign(raw) }, body: raw,
  }), env);
}
const paid = (items, pi = 'pi_1', uuid = UUID) => stripeEvent('checkout.session.completed', {
  id: 'cs_1', payment_status: 'paid', payment_intent: pi, metadata: { uuid, name: 'Will', items: items.join(',') },
});
const ownedNow = async () => (await (await api('GET', `/v1/players/${UUID}`)).json()).owned;

test('items: slots by id', () => {
  assert.equal(slotOf('crown-pixel'), 'head');
  assert.equal(slotOf('cape-void'), 'cape');
  assert.equal(slotOf('sword-of-doom'), null);
  for (const id of Object.keys(CATALOG)) assert.ok(slotOf(id), id);   // everything on sale can be worn
});

test('catalog: the store page sells exactly what the service charges for, at the same list prices', () => {
  const page = readFileSync(new URL('../../../website/js/store.js', import.meta.url), 'utf8');
  const items = [...page.matchAll(/\{ id: '([a-z0-9-]+)', name: '([^']+)',.*?price: ([\d.]+) \}/g)]
    .map(([, id, name, price]) => [id, { name, cents: Math.round(Number(price) * 100) }]);
  const sold = Object.entries(CATALOG).filter(([id]) => forSale(id)).map(([id, c]) => [id, { name: c.name, cents: c.cents }]);
  assert.deepEqual(Object.fromEntries(items), Object.fromEntries(sold));
});

test('catalog: every item and its price, and whether checkout is open', async () => {
  let c = await (await api('GET', '/v1/store/catalog')).json();
  assert.equal(c.open, true);
  assert.equal(c.currency, 'USD');
  assert.deepEqual(c.items['cape-orbit'], { name: 'Orbit Cape', price: 9.99, memberPrice: 7.99 });
  assert.deepEqual(c.plus, { month: 4.99, year: 39.99, percentOff: 20, manageUrl: PLUS_MANAGE_URL });
  for (const { price } of Object.values(c.items)) assert.ok(price >= 5 && price <= 10);   // Will: $5-$10
  delete env.STRIPE_SECRET_KEY;
  c = await (await api('GET', '/v1/store/catalog')).json();
  assert.equal(c.open, false);
});

test('checkout: makes a Stripe Checkout with our prices, the player on it, and tax on', async () => {
  routes.push(['https://api.stripe.com/v1/checkout/sessions', { body: { id: 'cs_1', url: 'https://checkout.stripe.com/c/pay/cs_1' } }]);
  const r = await api('POST', '/v1/store/checkout', { uuid: DASHED, name: 'Will', items: ['cape-orbit', 'halo-gold', 'cape-orbit'] });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).url, 'https://checkout.stripe.com/c/pay/cs_1');
  const sent = new URLSearchParams(calls[0].body.toString());
  assert.equal(calls[0].headers.Authorization, 'Bearer sk_test_x');
  assert.equal(sent.get('mode'), 'payment');
  assert.equal(sent.get('automatic_tax[enabled]'), 'true');
  assert.equal(sent.get('line_items[0][price_data][product_data][tax_code]'), 'txcd_10201000');
  assert.equal(sent.get('line_items[0][price_data][unit_amount]'), '999');      // the server's price, never the page's
  assert.equal(sent.get('line_items[1][price_data][product_data][name]'), 'Golden Halo');
  assert.equal(sent.get('line_items[2][quantity]'), null);                       // duplicate in the bag dropped
  assert.equal(sent.get('metadata[uuid]'), UUID);
  assert.ok(Number(sent.get('expires_at')) >= Date.now() / 1000 + 31 * 60);   // Stripe refuses < 30 min on arrival
  assert.equal(sent.get('metadata[items]'), 'cape-orbit,halo-gold');
  assert.equal(sent.get('payment_intent_data[metadata][uuid]'), UUID);
  assert.match(sent.get('success_url'), /store\.html\?purchase=done$/);
});

test('checkout: refuses bad input, things already owned, and a closed store; Stripe errors say nothing was charged', async () => {
  for (const body of [null, { uuid: 'nope', name: 'Will', items: ['cape-orbit'] }, { uuid: UUID, name: 'bad name!', items: ['cape-orbit'] },
    { uuid: UUID, name: 'Will', items: [] }, { uuid: UUID, name: 'Will', items: ['sword-of-doom'] }]) {
    assert.equal((await api('POST', '/v1/store/checkout', body)).status, 400, JSON.stringify(body));
  }
  await paid(['cape-void']);
  const r = await api('POST', '/v1/store/checkout', { uuid: UUID, name: 'Will', items: ['cape-orbit', 'cape-void'] });
  assert.equal(r.status, 409);
  assert.match((await r.json()).error, /already owns the Void Cape/);

  routes.push(['https://api.stripe.com/', { status: 400, body: { error: { message: 'Stripe Tax is not active' } } }]);
  const bad = await api('POST', '/v1/store/checkout', { uuid: UUID, name: 'Will', items: ['cape-orbit'] });
  assert.equal(bad.status, 502);
  assert.match((await bad.json()).error, /Nothing was charged/);

  delete env.STRIPE_SECRET_KEY;
  assert.equal((await api('POST', '/v1/store/checkout', { uuid: UUID, name: 'Will', items: ['cape-orbit'] })).status, 503);
  assert.equal(calls.length, 1);   // only the one Stripe call above
});

test('test mode: only listed testers can check out or be given anything; a live key sells to everyone', async () => {
  const OTHER = 'f'.repeat(32);
  routes.push(['https://api.stripe.com/v1/checkout/sessions', { body: { id: 'cs_1', url: 'https://checkout.stripe.com/c/pay/cs_1' } }]);
  const buy = uuid => api('POST', '/v1/store/checkout', { uuid, name: 'Alex', items: ['halo-gold'] });
  assert.equal((await buy(OTHER)).status, 403);
  assert.equal((await buy(UUID)).status, 200);
  await paid(['halo-gold'], 'pi_t', OTHER);
  assert.deepEqual((await (await api('GET', `/v1/players/${OTHER}`)).json()).owned, []);

  env.STRIPE_SECRET_KEY = 'rk_live_x';
  assert.equal((await buy(OTHER)).status, 200);
  const raw = JSON.stringify({ id: 'evt_2', type: 'checkout.session.completed', livemode: true,
    data: { object: { id: 'cs_2', payment_status: 'paid', payment_intent: 'pi_l', metadata: { uuid: OTHER, items: 'halo-gold' } } } });
  await handle(new Request('https://svc.test/v1/stripe/webhook', { method: 'POST', headers: { 'Stripe-Signature': sign(raw) }, body: raw }), env);
  assert.deepEqual((await (await api('GET', `/v1/players/${OTHER}`)).json()).owned, ['halo-gold']);
});

test('test mode: a tester listed by NAME can buy only as the UUID Mojang gives that name', async () => {
  const OTHER = 'f'.repeat(32);
  env.STRIPE_TEST_PLAYERS = 'Dozeyy, bxlt';
  routes.push(['https://api.stripe.com/v1/checkout/sessions', { body: { id: 'cs_1', url: 'https://checkout.stripe.com/c/pay/cs_1' } }]);
  routes.push(['https://api.mojang.com/users/profiles/minecraft/dozeyy', { body: { id: UUID, name: 'dozeyy' } }]);
  routes.push(['https://api.mojang.com/users/profiles/minecraft/bxlt', { status: 503 }]);
  const buy = (uuid, name) => api('POST', '/v1/store/checkout', { uuid, name, items: ['halo-gold'] });
  assert.equal((await buy(UUID, 'dozeyy')).status, 200);
  assert.equal((await buy(OTHER, 'dozeyy')).status, 403);   // borrowing a tester's name
  assert.equal((await buy(OTHER, 'Alex')).status, 403);
  assert.equal((await buy(OTHER, 'bxlt')).status, 403);     // Mojang down: no
  await paid(['halo-gold'], 'pi_n');                          // metadata name 'Will' isn't listed
  assert.deepEqual(await ownedNow(), []);
  env.STRIPE_TEST_PLAYERS = 'will';
  await paid(['halo-gold'], 'pi_n2');
  assert.deepEqual(await ownedNow(), ['halo-gold']);
});

test('webhook: a paid checkout gives the items once, however often Stripe repeats it', async () => {
  assert.equal((await paid(['cape-orbit', 'halo-gold'])).status, 200);
  assert.equal((await paid(['cape-orbit', 'halo-gold'])).status, 200);
  assert.deepEqual((await ownedNow()).sort(), ['cape-orbit', 'halo-gold']);
  assert.equal(calls.length, 0);   // the webhook never calls out
});

test('webhook: unpaid sessions wait for the late payment; unknown items and events are ignored', async () => {
  const session = { id: 'cs_2', payment_status: 'unpaid', payment_intent: 'pi_2', metadata: { uuid: UUID, name: 'Will', items: 'cape-frost,sword-of-doom' } };
  await stripeEvent('checkout.session.completed', session);
  assert.deepEqual(await ownedNow(), []);
  await stripeEvent('checkout.session.async_payment_succeeded', { ...session, payment_status: 'paid' });
  assert.deepEqual(await ownedNow(), ['cape-frost']);
  assert.equal((await stripeEvent('customer.created', { id: 'cus_1' })).status, 200);
  assert.equal((await stripeEvent('checkout.session.completed', { id: 'cs_3', payment_status: 'paid', metadata: {} })).status, 200);
});

test('webhook: refuses forged, wrongly signed and stale calls', async () => {
  const raw = JSON.stringify({ type: 'checkout.session.completed', data: { object: {} } });
  assert.equal((await stripeEvent('checkout.session.completed', {}, 'garbage')).status, 400);
  assert.equal((await stripeEvent('checkout.session.completed', {}, sign(raw, 'whsec_other'))).status, 400);
  assert.equal(await verifySignature(WHSEC, sign(raw, WHSEC, Math.floor(Date.now() / 1000) - 600), raw), false);   // 10 min old
  assert.equal(await verifySignature(WHSEC, sign(raw), raw), true);
  assert.equal(await verifySignature(WHSEC, sign(raw), raw + ' '), false);                                          // body changed
  delete env.STRIPE_WEBHOOK_SECRET;
  assert.equal((await paid(['cape-orbit'])).status, 400);
  assert.deepEqual(await ownedNow(), []);
});

test('refunds and chargebacks take that payment\'s items back and off; partial refunds and other payments stay', async () => {
  await paid(['cape-orbit', 'halo-gold'], 'pi_a');
  await paid(['cape-void'], 'pi_b');
  await api('POST', '/v1/equip', { cape: 'cape-orbit', head: 'halo-gold' }, await signIn());

  await stripeEvent('charge.refunded', { id: 'ch_a', payment_intent: 'pi_a', refunded: false });   // partial
  assert.equal((await ownedNow()).length, 3);
  await stripeEvent('charge.refunded', { id: 'ch_a', payment_intent: 'pi_a', refunded: true });
  const view = await (await api('GET', `/v1/players/${UUID}`)).json();
  assert.deepEqual(view.owned, ['cape-void']);
  assert.deepEqual(view.equipped, BARE);

  await stripeEvent('charge.dispute.created', { id: 'dp_1', payment_intent: 'pi_b' });
  assert.deepEqual(await ownedNow(), []);
});

test('a "paid" event Stripe retries after the refund gives nothing back; paying twice for one item is flagged', async () => {
  await stripeEvent('charge.refunded', { id: 'ch_c', payment_intent: 'pi_c', refunded: true });   // refund seen first
  await paid(['cape-orbit'], 'pi_c');
  assert.deepEqual(await ownedNow(), []);

  const logged = [], error = console.error;
  console.error = (...a) => logged.push(a.join(' '));
  try {
    await paid(['cape-void'], 'pi_d');
    await paid(['cape-void'], 'pi_d');          // a repeat of the same payment: fine, silent
    assert.equal(logged.length, 0);
    await paid(['cape-void'], 'pi_e');          // a second payment for the same cape
  } finally { console.error = error; }
  assert.match(logged.join('\n'), /REFUND NEEDED.*cape-void.*pi_d.*pi_e/);
  assert.deepEqual(await ownedNow(), ['cape-void']);
});

async function signIn() {
  const r = await api('POST', '/v1/auth', authBody({ name: 'Will', uuid: UUID, now: Date.now() }));
  assert.equal(r.status, 200);
  return (await r.json()).token;
}
async function own(...items) {
  await paid(items);
}

test('equip: wear owned items, signed in with an Origin token', async () => {
  await own('cape-orbit', 'halo-gold');
  const token = await signIn();
  const r = await api('POST', '/v1/equip', { cape: 'cape-orbit', head: 'halo-gold' }, token);
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).equipped, { ...BARE, cape: 'cape-orbit', head: 'halo-gold' });

  const batch = await (await api('POST', '/v1/players/equipped', { uuids: [DASHED, 'not-a-uuid', 'f'.repeat(32)] })).json();
  assert.deepEqual(batch.players, { [UUID]: { ...BARE, cape: 'cape-orbit', head: 'halo-gold', plus: false } });
  assert.equal(calls.length, 0);   // nothing asked of Mojang (or anyone)
});

test('equip: a slot the request leaves out stays on (older mods only know some slots); null takes it off', async () => {
  await own('cape-orbit', 'halo-gold');
  const token = await signIn();
  const wear = async body => (await (await api('POST', '/v1/equip', body, token)).json()).equipped;
  assert.deepEqual(await wear({ cape: 'cape-orbit' }), { ...BARE, cape: 'cape-orbit' });
  assert.deepEqual(await wear({ head: 'halo-gold' }), { ...BARE, cape: 'cape-orbit', head: 'halo-gold' });
  assert.deepEqual(await wear({ cape: null }), { ...BARE, head: 'halo-gold' });
  assert.equal((await api('POST', '/v1/equip', { back: 'cape-orbit' }, token)).status, 403);   // a cape isn't worn on the back
  assert.deepEqual(await wear({}), { ...BARE, head: 'halo-gold' });
});

test('equip: refuses items you do not own, wrong slots, missing/bad tokens and old mods', async () => {
  await own('cape-orbit');
  const token = await signIn();
  assert.equal((await api('POST', '/v1/equip', { cape: 'cape-void', head: null }, token)).status, 403);
  assert.equal((await api('POST', '/v1/equip', { cape: null, head: 'cape-orbit' }, token)).status, 403);
  assert.equal((await api('POST', '/v1/equip', { cape: 'cape-orbit', head: null }, token)).status, 200);
  assert.equal((await api('POST', '/v1/equip', { cape: 'cape-orbit', head: null }, token)).status, 200);   // repeatable

  let r = await api('POST', '/v1/equip', { cape: null, head: null });                     // no token
  assert.equal(r.status, 401);
  assert.ok((await r.json()).error.length > 0);
  assert.equal((await api('POST', '/v1/equip', { cape: null, head: null }, 'f'.repeat(64))).status, 401);

  // A mod from before identity v2 still sends Mojang's server-join proof.
  r = await api('POST', '/v1/equip', { name: 'Will', serverId: 'a'.repeat(40), cape: null, head: null });
  assert.equal(r.status, 400);
  assert.match((await r.json()).error, /update Origin to change cosmetics/);
  assert.equal((await api('POST', '/v1/equip', { serverId: 'a'.repeat(40), cape: null, head: null }, token)).status, 400);
});

test('api: CORS preflight, bad uuid, unknown path', async () => {
  assert.equal((await api('OPTIONS', '/v1/equip')).status, 204);
  assert.equal((await api('GET', '/v1/players/nope')).status, 400);
  assert.equal((await api('GET', '/v2/whatever')).status, 404);
  assert.equal((await api('GET', '/v1/players/' + UUID)).headers.get('Access-Control-Allow-Origin'), '*');
});

// ------------------------------------------------------------------ Origin+
const DAY = 24 * 3600;
const nowS = () => Math.floor(Date.now() / 1000);
// A subscription the way Stripe's events carry it (period on the item, as in
// newer API versions; older ones put it on the subscription itself).
const sub = (status, { id = 'sub_1', uuid = UUID, periodEnd = nowS() + 30 * DAY, product = 'origin-plus', name = 'Will' } = {}) => ({
  id, object: 'subscription', status, customer: 'cus_1', metadata: { uuid, name, plan: 'month' },
  items: { data: [{ current_period_end: periodEnd, price: { product, recurring: { interval: 'month' } } }] },
});
const subEvent = (type, s, created = nowS()) => stripeEvent(type, s, undefined, created);
const me = async () => (await (await api('GET', `/v1/players/${UUID}`)).json());

test('origin+ checkout: a Stripe subscription at our price, the player on the subscription, tax on', async () => {
  routes.push(['https://api.stripe.com/v1/checkout/sessions', { body: { id: 'cs_2', url: 'https://checkout.stripe.com/c/pay/cs_2' } }]);
  const r = await api('POST', '/v1/plus/checkout', { uuid: DASHED, name: 'Will', plan: 'year' });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).url, 'https://checkout.stripe.com/c/pay/cs_2');
  const sent = new URLSearchParams(calls[0].body.toString());
  assert.equal(sent.get('mode'), 'subscription');
  assert.equal(sent.get('automatic_tax[enabled]'), 'true');
  assert.equal(sent.get('line_items[0][price_data][unit_amount]'), '3999');
  assert.equal(sent.get('line_items[0][price_data][recurring][interval]'), 'year');
  assert.equal(sent.get('line_items[0][price_data][product]'), 'origin-plus');
  assert.equal(sent.get('subscription_data[metadata][uuid]'), UUID);
  assert.equal(sent.get('subscription_data[metadata][name]'), 'Will');
  assert.match(sent.get('success_url'), /store\.html\?plus=done$/);
  assert.equal(sent.get('payment_intent_data[metadata][uuid]'), null);   // not allowed in subscription mode
});

test('origin+ checkout: refuses bad input, a closed store, non-testers on a test key, and existing members', async () => {
  routes.push(['https://api.stripe.com/v1/checkout/sessions', { body: { id: 'cs_2', url: 'https://checkout.stripe.com/c/pay/cs_2' } }]);
  assert.equal((await api('POST', '/v1/plus/checkout', { uuid: DASHED, name: 'Will', plan: 'week' })).status, 400);
  assert.equal((await api('POST', '/v1/plus/checkout', { uuid: DASHED, name: 'Will', plan: 'toString' })).status, 400);
  assert.equal((await api('POST', '/v1/plus/checkout', { uuid: 'nope', name: 'Will', plan: 'month' })).status, 400);
  assert.equal((await api('POST', '/v1/plus/checkout', { uuid: 'f'.repeat(32), name: 'Someone', plan: 'month' })).status, 403);
  await subEvent('customer.subscription.created', sub('active'));
  const r = await api('POST', '/v1/plus/checkout', { uuid: DASHED, name: 'Will', plan: 'month' });
  assert.equal(r.status, 409);
  assert.match((await r.json()).error, /already an Origin\+ member/);
  delete env.STRIPE_SECRET_KEY;
  assert.equal((await api('POST', '/v1/plus/checkout', { uuid: DASHED, name: 'Will', plan: 'month' })).status, 503);
  assert.equal(calls.length, 0);   // no Stripe call for any of these
});

test('origin+ webhook: a member once paid, through renewals, until it ends; out-of-order events change nothing', async () => {
  await subEvent('customer.subscription.created', sub('incomplete'), 100);   // card not through yet
  assert.equal((await me()).plus, false);
  await subEvent('customer.subscription.updated', sub('active'), 101);
  let m = await me();
  assert.equal(m.plus, true);
  assert.ok(m.owned.includes('cape-plus'));
  await subEvent('customer.subscription.updated', sub('incomplete'), 100);   // a stale event arriving late
  assert.equal((await me()).plus, true);
  await subEvent('customer.subscription.updated', sub('past_due'), 102);     // renewal failed, Stripe retrying
  assert.equal((await me()).plus, true);
  await subEvent('customer.subscription.deleted', sub('canceled'), 103);
  m = await me();
  assert.equal(m.plus, false);
  assert.ok(!m.owned.includes('cape-plus'));
  await subEvent('customer.subscription.updated', sub('active'), 104);       // an ended membership stays ended
  assert.equal((await me()).plus, false);
});

test('origin+ webhook: "paid" and "created" stamped the same second, in either order, leave a member', async () => {
  await subEvent('customer.subscription.updated', sub('active'), 200);
  await subEvent('customer.subscription.created', sub('incomplete'), 200);
  assert.equal((await me()).plus, true);
});

test('origin+ webhook: perks stop a little after an unrenewed period; other products, non-testers and old API shapes', async () => {
  await subEvent('customer.subscription.created', sub('active', { periodEnd: nowS() - 3 * DAY }));
  assert.equal((await me()).plus, false);                                    // past the 2-day grace
  await subEvent('customer.subscription.updated', sub('active', { periodEnd: nowS() - DAY }));
  assert.equal((await me()).plus, true);                                     // inside it
  await subEvent('customer.subscription.created', sub('active', { id: 'sub_x', product: 'something-else', uuid: 'a'.repeat(32) }));
  await subEvent('customer.subscription.created', sub('active', { id: 'sub_y', uuid: 'b'.repeat(32), name: 'Someone' }));   // test mode, not a tester
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) n FROM plus').get().n, 1);
  // Older Stripe API versions: the period on the subscription, the product expanded.
  const old = { ...sub('active', { id: 'sub_2' }), current_period_end: nowS() + 30 * DAY };
  old.items = { data: [{ price: { product: { id: 'origin-plus' } } }] };
  await subEvent('customer.subscription.created', old);
  assert.equal(env.DB.raw.prepare("SELECT period_end > 0 ok FROM plus WHERE subscription = 'sub_2'").get().ok, 1);
});

test('origin+ checkout completion grants nothing by itself; the subscription events do', async () => {
  await stripeEvent('checkout.session.completed', { id: 'cs_2', mode: 'subscription', payment_status: 'paid', metadata: { uuid: UUID, name: 'Will', plan: 'month' } });
  const m = await me();
  assert.equal(m.plus, false);
  assert.deepEqual(m.owned, []);
});

test('origin+: members pay 20% less in the store, worked out by the service', async () => {
  routes.push(['https://api.stripe.com/v1/checkout/sessions', { body: { id: 'cs_1', url: 'https://checkout.stripe.com/c/pay/cs_1' } }]);
  await subEvent('customer.subscription.created', sub('active'));
  await api('POST', '/v1/store/checkout', { uuid: DASHED, name: 'Will', items: ['cape-orbit', 'cape-origin'] });
  const sent = new URLSearchParams(calls[0].body.toString());
  assert.equal(sent.get('line_items[0][price_data][unit_amount]'), '799');   // 9.99 -> 7.99
  assert.equal(sent.get('line_items[1][price_data][unit_amount]'), '479');   // 5.99 -> 4.79
  assert.equal(sent.get('metadata[plus]'), '1');
  assert.match(sent.get('line_items[0][price_data][product_data][description]'), /Origin\+ 20% off/);
});

test('origin+ cape and badge: members wear the cape and show up for their badge; it hides when the membership ends', async () => {
  await subEvent('customer.subscription.created', sub('active'), 100);
  const token = await signIn();
  let r = await api('POST', '/v1/equip', { cape: 'cape-plus', head: null }, token);
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).equipped, { ...BARE, cape: 'cape-plus' });
  const other = 'c'.repeat(32);
  env.STRIPE_TEST_PLAYERS += `,${other}`;
  await subEvent('customer.subscription.created', sub('active', { id: 'sub_3', uuid: other }));   // a member wearing nothing
  let batch = await (await api('POST', '/v1/players/equipped', { uuids: [UUID, other] })).json();
  assert.deepEqual(batch.players, {
    [UUID]: { ...BARE, cape: 'cape-plus', plus: true },
    [other]: { ...BARE, plus: true },
  });

  await subEvent('customer.subscription.deleted', sub('canceled'), 101);
  batch = await (await api('POST', '/v1/players/equipped', { uuids: [UUID] })).json();
  assert.deepEqual(batch.players, {});                                       // no cape shown, no badge
  assert.deepEqual((await me()).equipped, BARE);
  assert.equal((await api('POST', '/v1/equip', { cape: 'cape-plus', head: null }, token)).status, 403);
  // Joining again brings the saved cape straight back.
  await subEvent('customer.subscription.created', sub('active', { id: 'sub_4' }));
  assert.deepEqual((await me()).equipped, { ...BARE, cape: 'cape-plus' });
});

test('origin+ menu themes: members unlock every theme, nobody can buy one, and it hides when the membership ends', async () => {
  const themes = Object.keys((await import('../src/catalog.gen.js')).CATALOG).filter(id => id.startsWith('theme-'));
  assert.ok(themes.length >= 4);
  const c = await (await api('GET', '/v1/store/catalog')).json();
  for (const id of themes) assert.equal(c.items[id], undefined);              // not in the store's price list
  const token = await signIn();
  assert.equal((await api('POST', '/v1/equip', { theme: themes[0] }, token)).status, 403);
  await subEvent('customer.subscription.created', sub('active', { id: 'sub_th' }), 100);
  const m = await me();
  for (const id of themes) assert.ok(m.owned.includes(id));
  assert.equal((await api('POST', '/v1/equip', { theme: themes[0] }, token)).status, 200);
  assert.equal((await me()).equipped.theme, themes[0]);
  await subEvent('customer.subscription.deleted', sub('canceled', { id: 'sub_th' }), 101);
  assert.equal((await me()).equipped.theme, null);
  assert.ok(!(await me()).owned.includes(themes[0]));
});
