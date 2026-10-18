// Free store codes (src/codes.js): made only with Will's admin key, each
// works once, no Stripe involved.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { handle } from '../src/index.js';
import { normCode } from '../src/codes.js';
import { d1 } from './d1.mjs';

const UUID = '069a79f444e94726a5befca90e38aaf5';
const OTHER = 'c'.repeat(32);
const KEY = 'origin_admin_test';
let env;

beforeEach(() => {
  env = { DB: d1(), CODES_ADMIN_SHA256: createHash('sha256').update(KEY).digest('hex') };
  globalThis.fetch = async url => { throw new Error(`no network in code tests (${url})`); };
});

const api = (path, body, key) => handle(new Request(`https://svc.test${path}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', ...(key ? { Authorization: `Bearer ${key}` } : {}) },
  body: JSON.stringify(body),
}), env);
const make = async (kind, count = 1) => (await (await api('/v1/admin/codes', { kind, count }, KEY)).json()).codes;
const redeem = (code, items = [], uuid = UUID, name = 'Will') => api('/v1/store/redeem', { uuid, name, code, items });
const player = async (uuid = UUID) => (await handle(new Request(`https://svc.test/v1/players/${uuid}`), env)).json();

test('making codes: only with the admin key; the right kind and count, all different', async () => {
  for (const key of [undefined, 'wrong', KEY.toUpperCase()]) assert.equal((await api('/v1/admin/codes', { kind: 'bag' }, key)).status, 401);
  delete env.CODES_ADMIN_SHA256;
  assert.equal((await api('/v1/admin/codes', { kind: 'bag' }, KEY)).status, 401);   // no hash set: nobody can
  env.CODES_ADMIN_SHA256 = createHash('sha256').update(KEY).digest('hex');
  for (const body of [{ kind: 'cape' }, { kind: 'bag', count: 0 }, { kind: 'bag', count: 101 }, { kind: 'bag', count: 1.5 }]) {
    assert.equal((await api('/v1/admin/codes', body, KEY)).status, 400, JSON.stringify(body));
  }
  const codes = await make('bag', 20);
  assert.equal(codes.length, 20);
  assert.equal(new Set(codes).size, 20);
  for (const c of codes) assert.match(c, /^ORIGIN-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$/);
});

test('a bag code gives exactly the bag, free, once; typed any which way', async () => {
  const [code] = await make('bag');
  const typed = ` ${code.toLowerCase().replace(/-/g, ' ')} `;
  assert.equal(normCode(typed), normCode(code));
  const r = await redeem(typed, ['cape-orbit', 'halo-gold']);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { kind: 'bag', items: ['cape-orbit', 'halo-gold'], plus: false, paying: false });
  assert.deepEqual((await player()).owned, ['cape-orbit', 'halo-gold']);
  // Spent: not for this player, not for anyone else.
  const again = await redeem(code, ['cape-void'], OTHER);
  assert.equal(again.status, 409);
  assert.match((await again.json()).error, /already been used/);
  assert.deepEqual((await player(OTHER)).owned, []);
});

test('a bag code that cannot be used stays unspent: empty bag, not for sale, already owned', async () => {
  const [code, first] = await make('bag', 2);
  await redeem(first, ['cape-void']);
  for (const [items, status, msg] of [
    [[], 400, /Add the cosmetics/],
    [['sword-of-doom'], 400, /isn’t on sale/],
    [['cape-plus'], 400, /isn’t on sale/],             // members-only, never given by a bag code
    [['cape-void', 'cape-orbit'], 409, /already owns the Void Cape/],
  ]) {
    const r = await redeem(code, items);
    assert.equal(r.status, status, JSON.stringify(items));
    assert.match((await r.json()).error, msg);
  }
  assert.equal((await redeem(code, ['cape-orbit'])).status, 200);   // still good after all that
});

test('bad input and unknown codes', async () => {
  for (const body of [null, { uuid: 'nope', name: 'Will', code: 'X' }, { uuid: UUID, name: 'bad name!', code: 'X' }, { uuid: UUID, name: 'Will', code: '' }]) {
    assert.equal((await api('/v1/store/redeem', body)).status, 400, JSON.stringify(body));
  }
  const r = await redeem('ORIGIN-2222-2222-2222', ['cape-orbit']);
  assert.equal(r.status, 404);
  assert.match((await r.json()).error, /doesn’t exist/);
});

test('an Origin+ code makes the player a member for good: perks on, bag untouched, can’t be stacked', async () => {
  const [code, second] = await make('plus', 2);
  const r = await redeem(code, ['cape-orbit']);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { kind: 'plus', items: [], plus: true, paying: false });
  const p = await player();
  assert.equal(p.plus, true);
  assert.ok(p.owned.includes('cape-plus'));
  assert.ok(!p.owned.includes('cape-orbit'));               // an Origin+ code gives no cosmetics from the bag
  // Still a member in 100 years.
  const later = Date.now() + 100 * 365 * 24 * 3600 * 1000;
  assert.ok(env.DB.raw.prepare(`SELECT 1 FROM plus WHERE uuid = ? AND period_end * 1000 > ?`).get(UUID, later));
  // A second one is refused and stays unspent for someone else.
  const dup = await redeem(second);
  assert.equal(dup.status, 409);
  assert.match((await dup.json()).error, /already has Origin\+ for good/);
  assert.equal((await redeem(second, [], OTHER)).status, 200);
  // A forever member can't be sold a membership.
  env.STRIPE_SECRET_KEY = 'sk_live_x';
  assert.equal((await api('/v1/plus/checkout', { uuid: UUID, name: 'Will', plan: 'month' })).status, 409);
});

test('an Origin+ code for someone already paying says to cancel the paid one', async () => {
  env.DB.raw.prepare(`INSERT INTO plus (subscription, uuid, name, customer, status, period_end, event_at) VALUES ('sub_1', ?, 'Will', 'cus_1', 'active', ?, 1)`)
    .run(UUID, Math.floor(Date.now() / 1000) + 86400);
  const [code] = await make('plus');
  assert.equal((await (await redeem(code)).json()).paying, true);
});

test('two players racing for one code: exactly one gets it', async () => {
  const [code] = await make('bag');
  const results = await Promise.all([redeem(code, ['cape-orbit']), redeem(code, ['cape-void'], OTHER)]);
  assert.deepEqual(results.map(r => r.status).sort(), [200, 409]);
  const owners = [(await player()).owned, (await player(OTHER)).owned].filter(o => o.length);
  assert.equal(owners.length, 1);
});
