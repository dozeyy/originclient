// Runs the real Worker code against a real SQLite database (Node's built-in
// node:sqlite, wrapped to look like Cloudflare D1), with Tebex and Mojang
// faked. `npm test` (node --test) — no packages to install.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { handle, collect, parseCommand, slotOf } from '../src/index.js';
import { d1 } from './d1.mjs';   // D1 stand-in with every migration applied

const UUID = '069a79f444e94726a5befca90e38aaf5';
const DASHED = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
let env, calls, routes;

// Fake network: route by URL prefix; records every call.
function fakeFetch() {
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method || 'GET', headers: init.headers || {} });
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
  env = { DB: d1(), TEBEX_SECRET: 'test-secret' };
  calls = [];
  routes = [];
  fakeFetch();
});

const tebexQueue = (offline, players = [], nextCheck = 120) => {
  routes.push(['https://plugin.tebex.io/queue/offline-commands', { body: { meta: { limited: false }, commands: offline } }]);
  routes.push(['https://plugin.tebex.io/queue?', { status: 204 }]);
  routes.push(['https://plugin.tebex.io/queue', { body: { meta: { execute_offline: true, next_check: nextCheck, more: false }, players } }]);
};
const api = (method, path, body) => handle(new Request(`https://svc.test${path}`, {
  method, headers: { 'Content-Type': 'application/json' }, body: body && JSON.stringify(body),
}), env);
const deletes = () => calls.filter(c => c.method === 'DELETE');

test('commands: parse grant/revoke, reject junk', () => {
  assert.deepEqual(parseCommand(`origin-grant Will ${DASHED} cape-orbit`), { action: 'grant', name: 'Will', uuid: UUID, item: 'cape-orbit' });
  assert.equal(parseCommand(`origin-revoke Will ${UUID} halo-gold`).action, 'revoke');
  assert.equal(parseCommand('give Will diamond 1'), null);
  assert.equal(parseCommand(`origin-grant Will ${UUID} sword-of-doom`), null);   // not a cosmetic slot
  assert.equal(slotOf('crown-pixel'), 'head');
  assert.equal(slotOf('cape-void'), 'cape');
});

test('delivery: a purchase lands, Tebex is told it is done, next_check is obeyed', async () => {
  tebexQueue([{ id: 11, command: `origin-grant Will ${UUID} cape-orbit`, payment: 555, player: { id: 1, name: 'Will', uuid: DASHED } }]);
  const r = await collect(env, 1_000_000);
  assert.equal(r.delivered, 1);
  assert.ok(calls.every(c => c.headers['X-Tebex-Secret'] === 'test-secret' || !c.url.includes('tebex')));
  assert.match(deletes()[0].url, /\/queue\?ids\[\]=11$/);

  const view = await (await api('GET', `/v1/players/${DASHED}`)).json();
  assert.deepEqual(view.owned, ['cape-orbit']);

  calls = [];
  assert.deepEqual(await collect(env, 1_000_000 + 119_000), { skipped: true });   // Tebex said wait 120s
  assert.equal(calls.length, 0);
  routes = [];
  tebexQueue([]);
  assert.equal((await collect(env, 1_000_000 + 121_000)).delivered, 0);
});

test('delivery: never checks more often than once a minute even if Tebex says 0', async () => {
  tebexQueue([], [], 0);
  await collect(env, 5_000_000);
  calls = [];
  assert.deepEqual(await collect(env, 5_000_000 + 30_000), { skipped: true });
});

test('delivery: an unreadable command stays queued instead of being lost', async () => {
  tebexQueue([
    { id: 1, command: 'origin-grant Will', player: { uuid: UUID } },
    { id: 2, command: `origin-grant Will ${UUID} halo-gold`, player: { uuid: UUID } },
  ]);
  const r = await collect(env, 1);
  assert.equal(r.delivered, 1);
  assert.match(deletes()[0].url, /ids\[\]=2$/);
});

test('delivery: commands wrongly set to "player must be online" still deliver', async () => {
  routes.push(['https://plugin.tebex.io/queue/online-commands/77', { body: { player: { id: 77, name: 'Will', uuid: UUID }, commands: [{ id: 9, command: `origin-grant Will ${UUID} crown-pixel` }] } }]);
  tebexQueue([], [{ id: 77, name: 'Will', uuid: UUID }]);
  assert.equal((await collect(env, 1)).delivered, 1);
  const view = await (await api('GET', `/v1/players/${UUID}`)).json();
  assert.deepEqual(view.owned, ['crown-pixel']);
});

// Mojang's server-join check: 200 + profile when the player really joined.
const joined = (ok = true) => routes.push(['https://sessionserver.mojang.com/', ok ? { body: { id: UUID, name: 'Will' } } : { status: 204 }]);
async function own(...items) {
  tebexQueue(items.map((item, i) => ({ id: i + 1, command: `origin-grant Will ${UUID} ${item}`, player: { uuid: UUID } })));
  await collect(env, 1);
  routes = [];
}

test('equip: wear owned items, verified through Mojang', async () => {
  await own('cape-orbit', 'halo-gold');
  joined();
  const r = await api('POST', '/v1/equip', { name: 'Will', serverId: 'a'.repeat(40), cape: 'cape-orbit', head: 'halo-gold' });
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).equipped, { cape: 'cape-orbit', head: 'halo-gold' });

  const batch = await (await api('POST', '/v1/players/equipped', { uuids: [DASHED, 'not-a-uuid', 'f'.repeat(32)] })).json();
  assert.deepEqual(batch.players, { [UUID]: { cape: 'cape-orbit', head: 'halo-gold' } });
});

test('equip: refuses items you do not own, wrong slots, failed checks and reused checks', async () => {
  await own('cape-orbit');
  joined();
  assert.equal((await api('POST', '/v1/equip', { name: 'Will', serverId: 'b'.repeat(40), cape: 'cape-void', head: null })).status, 403);
  assert.equal((await api('POST', '/v1/equip', { name: 'Will', serverId: 'c'.repeat(40), cape: null, head: 'cape-orbit' })).status, 403);
  assert.equal((await api('POST', '/v1/equip', { name: 'Will', serverId: 'd'.repeat(40), cape: 'cape-orbit', head: null })).status, 200);
  assert.equal((await api('POST', '/v1/equip', { name: 'Will', serverId: 'd'.repeat(40), cape: 'cape-orbit', head: null })).status, 401);
  assert.equal((await api('POST', '/v1/equip', { name: 'Will!', serverId: 'e'.repeat(40) })).status, 400);
  routes = [];
  joined(false);
  assert.equal((await api('POST', '/v1/equip', { name: 'Will', serverId: 'f'.repeat(40), cape: null, head: null })).status, 401);
});

test('refund: revoking an item removes it and takes it off', async () => {
  await own('cape-orbit');
  joined();
  await api('POST', '/v1/equip', { name: 'Will', serverId: '1'.repeat(40), cape: 'cape-orbit', head: null });
  tebexQueue([{ id: 5, command: `origin-revoke Will ${UUID} cape-orbit`, player: { uuid: UUID } }]);
  await collect(env, 10_000_000);
  const view = await (await api('GET', `/v1/players/${UUID}`)).json();
  assert.deepEqual(view.owned, []);
  assert.deepEqual(view.equipped, { cape: null, head: null });
});

test('api: CORS preflight, bad uuid, unknown path', async () => {
  assert.equal((await api('OPTIONS', '/v1/equip')).status, 204);
  assert.equal((await api('GET', '/v1/players/nope')).status, 400);
  assert.equal((await api('GET', '/v2/whatever')).status, 404);
  assert.equal((await api('GET', '/v1/players/' + UUID)).headers.get('Access-Control-Allow-Origin'), '*');
});
