// Runs the real Worker code against a real SQLite database (Node's built-in
// node:sqlite, wrapped to look like Cloudflare D1), with Stripe faked and
// Mojang's signatures made by test/mojang.mjs. `npm test` (node --test) — no packages to install.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { handle, slotOf } from '../src/index.js';
import { CATALOG, verifySignature } from '../src/stripe.js';
import { d1 } from './d1.mjs';   // D1 stand-in with every migration applied
import { authBody, seedKeys } from './mojang.mjs';

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
  env = { DB: d1(), STRIPE_SECRET_KEY: 'sk_test_x', STRIPE_WEBHOOK_SECRET: WHSEC };
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
function stripeEvent(type, object, signature) {
  const raw = JSON.stringify({ id: 'evt_1', type, data: { object } });
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
  assert.deepEqual(Object.fromEntries(items), CATALOG);
});

test('catalog: every item and its price, and whether checkout is open', async () => {
  let c = await (await api('GET', '/v1/store/catalog')).json();
  assert.equal(c.open, true);
  assert.equal(c.currency, 'USD');
  assert.deepEqual(c.items['cape-orbit'], { name: 'Orbit Cape', price: 9.99 });
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
  assert.equal(sent.get('line_items[0][price_data][unit_amount]'), '999');      // the server's price, never the page's
  assert.equal(sent.get('line_items[1][price_data][product_data][name]'), 'Golden Halo');
  assert.equal(sent.get('line_items[2][quantity]'), null);                       // duplicate in the bag dropped
  assert.equal(sent.get('metadata[uuid]'), UUID);
  assert.ok(Number(sent.get('expires_at')) > Date.now() / 1000 + 29 * 60);
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
  assert.deepEqual(view.equipped, { cape: null, head: null });

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
  assert.deepEqual((await r.json()).equipped, { cape: 'cape-orbit', head: 'halo-gold' });

  const batch = await (await api('POST', '/v1/players/equipped', { uuids: [DASHED, 'not-a-uuid', 'f'.repeat(32)] })).json();
  assert.deepEqual(batch.players, { [UUID]: { cape: 'cape-orbit', head: 'halo-gold' } });
  assert.equal(calls.length, 0);   // nothing asked of Mojang (or anyone)
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
