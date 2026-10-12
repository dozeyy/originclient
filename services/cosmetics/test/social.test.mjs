// Origin social (docs/SOCIAL.md) against the real Worker code and a real
// SQLite database, with Mojang faked and the clock under the test's control.
// `npm test` (node --test) — no packages to install.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { handle, scheduled } from '../src/index.js';
import { socialCleanup } from '../src/social.js';
import { d1 } from './d1.mjs';

// ------------------------------------------------------------- the harness
let env, clock, step, players, badJoins, mojangDown, calls;

// Every API call moves the clock on by `step` ms (default just over the
// 1-per-second limits) so ordinary flows never trip them; tests about
// timing set step = 0 and move the clock themselves.
const tick = ms => { clock += ms; };
Date.now = () => clock;

function uuidFor(name) { return createHash('md5').update(name).digest('hex'); }
function player(name) { players.set(name.toLowerCase(), { id: uuidFor(name), name }); return uuidFor(name); }

beforeEach(() => {
  env = { DB: d1(), TEBEX_SECRET: 'test-secret' };
  clock = 1_800_000_000_000;
  step = 1001;
  players = new Map();
  badJoins = new Set();
  mojangDown = false;
  calls = [];
  globalThis.fetch = async url => {
    const u = new URL(String(url));
    calls.push(u.href);
    const reply = (status, body) => new Response(body === undefined ? null : JSON.stringify(body), { status });
    if (u.href.startsWith('https://sessionserver.mojang.com/session/minecraft/hasJoined')) {
      const p = players.get(u.searchParams.get('username').toLowerCase());
      return p && !badJoins.has(u.searchParams.get('serverId')) ? reply(200, { id: p.id, name: p.name }) : reply(204);
    }
    if (u.href.startsWith('https://api.mojang.com/users/profiles/minecraft/')) {
      if (mojangDown) return reply(503);
      const p = players.get(decodeURIComponent(u.pathname.split('/').pop()).toLowerCase());
      return p ? reply(200, { id: p.id, name: p.name }) : reply(204);
    }
    throw new Error(`unexpected fetch ${url}`);
  };
});

async function call(token, path, body, method = 'POST') {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await handle(new Request(`https://svc.test${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), env);
  tick(step);
  return { status: r.status, body: await r.json().catch(() => null), headers: r.headers };
}
const serverIdOf = n => n.toString(16).padStart(32, '0');
let nextServerId = 1;
async function signIn(name, client = 'game') {
  player(name);
  const r = await call(null, '/v1/auth', { name: name.toLowerCase(), serverId: serverIdOf(nextServerId++), client });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return { token: r.body.token, uuid: r.body.uuid, name: r.body.name };
}
const sync = (who, body = {}) => call(who.token, '/v1/sync', { since: 0, ...body }).then(r => { assert.equal(r.status, 200, JSON.stringify(r.body)); return r.body; });
async function befriend(a, b) {
  assert.equal((await call(a.token, '/v1/friends/request', { name: b.name })).status, 200);
  assert.equal((await call(b.token, '/v1/friends/respond', { uuid: a.uuid, accept: true })).status, 200);
}
const onServer = (server, extra = {}) => ({ presence: { client: 'game', activity: 'server', server, version: '1.21.1' }, ...extra });
const at = (server, x = 0) => ({ server, dim: 'minecraft:overworld', x, y: 64, z: 0, health: 20, maxHealth: 20 });
const rows = sql => env.DB.raw.prepare(sql).all();

// -------------------------------------------------------------------- auth
test('auth: a verified account gets a 24 h token, stored only as its SHA-256', async () => {
  player('Will');
  const r = await call(null, '/v1/auth', { name: 'will', serverId: 'ab'.repeat(16), client: 'launcher' });
  assert.equal(r.status, 200);
  assert.match(r.body.token, /^[0-9a-f]{64}$/);
  assert.equal(r.body.uuid, uuidFor('Will'));
  assert.equal(r.body.name, 'Will');                         // Mojang's spelling, not what was typed
  assert.equal(r.body.expiresAt, clock - step + 24 * 3600_000);
  const [s] = rows('SELECT * FROM sessions');
  assert.equal(s.token_hash, createHash('sha256').update(r.body.token).digest('hex'));
  assert.ok(!JSON.stringify(rows('SELECT * FROM sessions')).includes(r.body.token));
  assert.equal(rows('SELECT name FROM names')[0].name, 'Will');
  assert.equal((await call(r.body.token, '/v1/sync', { since: 0 })).status, 200);
});

test('auth: refuses failed checks, reused serverIds, bad input and bad or expired tokens', async () => {
  player('Will');
  badJoins.add('cd'.repeat(16));
  assert.equal((await call(null, '/v1/auth', { name: 'Will', serverId: 'cd'.repeat(16), client: 'game' })).status, 401);
  assert.equal((await call(null, '/v1/auth', { name: 'Nobody', serverId: 'ce'.repeat(16), client: 'game' })).status, 401);
  assert.equal((await call(null, '/v1/auth', { name: 'Will', serverId: 'ef'.repeat(16), client: 'game' })).status, 200);
  const reused = await call(null, '/v1/auth', { name: 'Will', serverId: 'ef'.repeat(16), client: 'game' });
  assert.equal(reused.status, 401);
  assert.match(reused.body.error, /already used/);
  assert.equal((await call(null, '/v1/auth', { name: 'Will', serverId: 'ab'.repeat(16), client: 'phone' })).status, 400);
  assert.equal((await call(null, '/v1/auth', { name: 'Will!', serverId: 'ab'.repeat(16), client: 'game' })).status, 400);
  // A serverId spent on /v1/equip can't sign in either (one shared joins table).
  await call(null, '/v1/equip', { name: 'Will', serverId: '12'.repeat(16), cape: null, head: null });
  assert.equal((await call(null, '/v1/auth', { name: 'Will', serverId: '12'.repeat(16), client: 'game' })).status, 401);

  assert.equal((await call(null, '/v1/sync', {})).status, 401);
  assert.equal((await call('f'.repeat(64), '/v1/sync', {})).status, 401);
  const will = await signIn('Will');
  assert.equal((await call(will.token, '/v1/sync', {})).status, 200);
  tick(24 * 3600_000);
  const expired = await call(will.token, '/v1/sync', {});
  assert.equal(expired.status, 401);
  assert.ok(expired.body.error.length > 0);
});

test('api: social routes answer CORS preflight with Authorization allowed; GET on a POST route is refused', async () => {
  const r = await call(null, '/v1/sync', undefined, 'OPTIONS');
  assert.equal(r.status, 204);
  assert.match(r.headers.get('Access-Control-Allow-Headers'), /Authorization/);
  assert.equal((await call(null, '/v1/sync', undefined, 'GET')).status, 405);
  assert.equal((await call(null, '/v1/nothing', {})).status, 404);
});

// ----------------------------------------------------------------- friends
test('friends: request -> accept, both sides see it; mistakes get clear errors', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  player('Steve');                                             // a real player who never used Origin

  let r = await call(will.token, '/v1/friends/request', { name: 'Nobody_Real' });
  assert.equal(r.status, 404);
  assert.match(r.body.error, /no Minecraft player/);
  r = await call(will.token, '/v1/friends/request', { name: 'steve' });
  assert.equal(r.status, 404);
  assert.match(r.body.error, /Steve isn't on Origin/);
  assert.equal((await call(will.token, '/v1/friends/request', { name: 'WILL' })).status, 400);
  assert.equal((await call(will.token, '/v1/friends/request', { name: 'two words' })).status, 400);

  r = await call(will.token, '/v1/friends/request', { name: 'alex' });
  assert.deepEqual(r.body, { ok: true, uuid: alex.uuid, name: 'Alex', friends: false });
  assert.equal((await call(will.token, '/v1/friends/request', { name: 'alex' })).status, 200);   // repeat is harmless
  assert.equal(rows('SELECT * FROM friend_requests').length, 1);

  let w = await sync(will), a = await sync(alex);
  assert.deepEqual(w.requests.outgoing.map(x => x.name), ['Alex']);
  assert.deepEqual(a.requests.incoming.map(x => [x.uuid, x.name]), [[will.uuid, 'Will']]);
  assert.equal(typeof a.requests.incoming[0].at, 'number');

  assert.equal((await call(alex.token, '/v1/friends/respond', { uuid: '0'.repeat(32), accept: true })).status, 404);
  assert.equal((await call(alex.token, '/v1/friends/respond', { uuid: will.uuid, accept: 'yes' })).status, 400);
  assert.deepEqual((await call(alex.token, '/v1/friends/respond', { uuid: will.uuid, accept: true })).body, { ok: true, friends: true });

  w = await sync(will); a = await sync(alex);
  assert.deepEqual(w.friends.map(f => f.name), ['Alex']);
  assert.deepEqual(a.friends.map(f => f.name), ['Will']);
  assert.equal(w.requests.outgoing.length + a.requests.incoming.length, 0);
  assert.match((await call(will.token, '/v1/friends/request', { name: 'Alex' })).body.error, /already friends/);

  // Remove ends it for both.
  assert.equal((await call(alex.token, '/v1/friends/remove', { uuid: will.uuid })).status, 200);
  assert.equal((await sync(will)).friends.length, 0);
});

test('friends: opposite requests auto-accept; decline and remove cancel requests', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam');
  await call(will.token, '/v1/friends/request', { name: 'Alex' });
  const r = await call(alex.token, '/v1/friends/request', { name: 'Will' });
  assert.equal(r.body.friends, true);
  assert.equal(rows('SELECT * FROM friends').length, 2);        // stored both ways
  assert.equal(rows('SELECT * FROM friend_requests').length, 0);

  await call(sam.token, '/v1/friends/request', { name: 'Will' });
  assert.deepEqual((await call(will.token, '/v1/friends/respond', { uuid: sam.uuid, accept: false })).body, { ok: true, friends: false });
  assert.equal((await sync(sam)).requests.outgoing.length, 0);
  assert.equal((await sync(will)).friends.length, 1);

  await call(sam.token, '/v1/friends/request', { name: 'Will' });
  await call(sam.token, '/v1/friends/remove', { uuid: will.uuid });    // "cancel request"
  assert.equal((await sync(will)).requests.incoming.length, 0);
});

test('friends: Mojang lookup down -> names Origin knows still work, others get a try-again', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  mojangDown = true;
  assert.equal((await call(will.token, '/v1/friends/request', { name: 'ALEX' })).body.uuid, alex.uuid);
  const r = await call(will.token, '/v1/friends/request', { name: 'Stranger' });
  assert.equal(r.status, 503);
  assert.match(r.body.error, /Try again/);
});

test('blocks: a blocker looks "not on Origin", blocking ends everything between you, unblock restores requests', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  await befriend(will, alex);
  await call(alex.token, '/v1/party/invite', { uuid: will.uuid });
  assert.equal((await sync(will)).partyInvites.length, 1);

  assert.equal((await call(alex.token, '/v1/friends/block', { uuid: will.uuid })).status, 200);
  assert.equal((await sync(will)).friends.length, 0);
  assert.equal((await sync(will)).partyInvites.length, 0);
  assert.deepEqual((await sync(alex)).blocked.map(b => b.name), ['Will']);

  const r = await call(will.token, '/v1/friends/request', { name: 'Alex' });
  assert.equal(r.status, 404);
  assert.match(r.body.error, /isn't on Origin/);
  assert.match((await call(alex.token, '/v1/friends/request', { name: 'Will' })).body.error, /Unblock them first/);
  assert.equal((await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'hi' })).status, 403);

  await call(alex.token, '/v1/friends/unblock', { uuid: will.uuid });
  assert.equal((await sync(alex)).blocked.length, 0);
  assert.equal((await call(will.token, '/v1/friends/request', { name: 'Alex' })).body.friends, false);
});

// --------------------------------------------------------------------- DMs
test('DMs: friends only, 1..256 characters, cleaned, 1 per second, seen only by the two of them', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam');
  assert.equal((await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'hi' })).status, 403);
  await befriend(will, alex);
  assert.equal((await call(will.token, '/v1/messages/send', { to: alex.uuid, text: '' })).status, 400);
  assert.equal((await call(will.token, '/v1/messages/send', { to: alex.uuid, text: '   ' })).status, 400);
  assert.equal((await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'x'.repeat(257) })).status, 400);
  step = 0;
  assert.equal((await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'x'.repeat(256) })).status, 200);
  const sent = await call(will.token, '/v1/messages/send', { to: alex.uuid, text: '§cred\nline' });
  assert.equal(sent.status, 429);
  assert.match(sent.body.error, /one a second/);
  tick(1000);
  const ok = await call(will.token, '/v1/messages/send', { to: alex.uuid, text: '§cred\nline' });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.message.text, 'cred line');
  assert.equal(ok.body.message.from, will.uuid);
  step = 1001;

  const a = await sync(alex);
  assert.deepEqual(a.messages.map(m => [m.from, m.to, m.text.length]), [[will.uuid, alex.uuid, 256], [will.uuid, alex.uuid, 9]]);
  assert.deepEqual((await sync(will)).messages.length, 2);      // your own sent messages too
  assert.equal((await sync(sam)).messages.length, 0);
});

test('DMs: the cursor delivers each message exactly once, in order, across pages', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  await befriend(will, alex);
  await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'old' });
  const first = await sync(alex);
  assert.deepEqual(first.messages.map(m => m.text), ['old']);
  assert.ok(first.cursor >= first.messages[0].id);

  // Nothing new -> nothing returned, cursor stays.
  let r = await sync(alex, { since: first.cursor });
  assert.equal(r.messages.length, 0);
  assert.equal(r.cursor, first.cursor);

  // 450 new lines (written straight to the table, same clock ms) -> 200 + 200 + 50.
  const ins = env.DB.raw.prepare('INSERT INTO chat (from_uuid, to_uuid, text, at) VALUES (?, ?, ?, ?)');
  for (let i = 0; i < 450; i++) ins.run(i % 2 ? will.uuid : alex.uuid, i % 2 ? alex.uuid : will.uuid, `m${i}`, clock);
  const seen = [];
  let cursor = first.cursor;
  for (const expect of [200, 200, 50, 0]) {
    r = await sync(alex, { since: cursor });
    assert.equal(r.messages.length, expect);
    seen.push(...r.messages.map(m => m.text));
    cursor = r.cursor;
  }
  assert.deepEqual(seen, Array.from({ length: 450 }, (_, i) => `m${i}`));

  // A first sync (since = 0) gets only the newest 100, oldest first.
  const fresh = await sync(alex);
  assert.equal(fresh.messages.length, 100);
  assert.equal(fresh.messages.at(-1).text, 'm449');
  assert.equal(fresh.cursor, cursor);

  // Time order matches cursor order even when another machine's clock ran ahead.
  ins.run(will.uuid, alex.uuid, 'from a fast clock', clock + 60_000);
  const next = await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'after it' });
  assert.equal(next.body.message.at, clock - step + 60_000);
});

// ---------------------------------------------------------------- presence
test('presence: friends see where you play; appear offline hides it; strangers see nothing', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam');
  await befriend(will, alex);

  await sync(will, onServer('Play.Hypixel.net:25565'));
  let f = (await sync(alex)).friends[0];
  assert.deepEqual({ ...f, lastSeen: typeof f.lastSeen }, {
    uuid: will.uuid, name: 'Will', online: true, client: 'game', activity: 'server',
    server: 'play.hypixel.net', version: '1.21.1', lastSeen: 'number',
  });
  assert.equal((await sync(sam)).friends.length, 0);

  // The game wins over the launcher when both are open.
  await call((await signIn('Will', 'launcher')).token, '/v1/sync', { presence: { client: 'launcher', activity: 'menu', version: '1.21.1' } });
  assert.equal((await sync(alex)).friends[0].client, 'game');

  assert.deepEqual((await call(will.token, '/v1/settings', { appearOffline: true })).body, { ok: true, appearOffline: true });
  assert.equal((await call(will.token, '/v1/settings', { appearOffline: 'on' })).status, 400);
  await sync(will, onServer('mc.example.org'));
  f = (await sync(alex)).friends[0];
  assert.deepEqual([f.online, f.client, f.activity, f.server, f.version], [false, null, null, null, null]);
  assert.ok(f.lastSeen > 0);
  assert.equal((await sync(will)).me.appearOffline, true);

  await call(will.token, '/v1/settings', { appearOffline: false });
  assert.equal((await sync(alex)).friends[0].server, 'mc.example.org');

  // Online = seen in the last 90 s.
  tick(91_000);
  f = (await sync(alex)).friends[0];
  assert.equal(f.online, false);
  assert.equal(f.server, null);
});

test('presence: written at most every 30 s, sooner (5 s) only when it changed', async () => {
  const will = await signIn('Will');
  step = 0;
  const atNow = () => rows('SELECT at FROM presence')[0].at;
  await sync(will, onServer('a.net'));
  const t0 = atNow();
  tick(10_000); await sync(will, onServer('a.net'));
  assert.equal(atNow(), t0);                                    // same place, 10 s later: no write
  tick(20_000); await sync(will, onServer('a.net'));
  assert.equal(atNow(), t0 + 30_000);                           // 30 s: heartbeat write
  tick(2_000); await sync(will, onServer('b.net'));
  assert.equal(rows('SELECT server FROM presence')[0].server, 'a.net');   // changed, but < 5 s
  tick(4_000); await sync(will, onServer('b.net'));
  assert.equal(rows('SELECT server FROM presence')[0].server, 'b.net');   // changed, 6 s: written
  assert.equal(rows('SELECT * FROM presence').length, 1);
  await sync(will, { presence: null });                                   // a pure poll writes nothing
});

test('players online: distinct accounts in 5 minutes, invisible ones included, counted once a minute', async () => {
  assert.deepEqual((await call(null, '/v1/stats/online', undefined, 'GET')).body.online, 0);
  const will = await signIn('Will'), alex = await signIn('Alex');
  const willLauncher = await signIn('Will', 'launcher');
  step = 0;
  tick(60_000);
  await sync(will, onServer('a.net'));
  await call(willLauncher.token, '/v1/sync', { presence: { client: 'launcher', activity: 'menu' } });
  await call(alex.token, '/v1/settings', { appearOffline: true });
  await sync(alex, { presence: { client: 'launcher', activity: 'menu' } });
  let r = await call(null, '/v1/stats/online', undefined, 'GET');
  assert.equal(r.body.online, 2);
  assert.equal(r.body.at, clock);

  const sam = await signIn('Sam');
  await sync(sam, { presence: { client: 'game', activity: 'menu' } });
  tick(30_000);
  assert.equal((await call(null, '/v1/stats/online', undefined, 'GET')).body.online, 2);   // cached
  env.DB = Object.assign(Object.create(Object.getPrototypeOf(env.DB)), env.DB);         // another Worker machine: no memory cache
  assert.equal((await call(null, '/v1/stats/online', undefined, 'GET')).body.online, 2);   // ...still cached via meta
  tick(31_000);
  assert.equal((await call(null, '/v1/stats/online', undefined, 'GET')).body.online, 3);
  tick(5 * 60_000);
  assert.equal((await call(null, '/v1/stats/online', undefined, 'GET')).body.online, 0);
});

// ------------------------------------------------------------------ parties
test('party: create, invite (friends only), accept, promote, kick, leave, last one out deletes it', async () => {
  const [will, alex, sam, kim, joe] = [await signIn('Will'), await signIn('Alex'), await signIn('Sam'), await signIn('Kim'), await signIn('Joe')];
  await befriend(will, alex); await befriend(will, sam); await befriend(alex, kim);

  let r = await call(will.token, '/v1/party/create', {});
  assert.equal(r.status, 200);
  const pid = r.body.party.id;
  assert.deepEqual([r.body.party.leader, r.body.party.members.map(m => m.name)], [will.uuid, ['Will']]);
  assert.equal((await call(will.token, '/v1/party/create', {})).status, 400);
  assert.equal((await call(will.token, '/v1/party/invite', { uuid: joe.uuid })).status, 403);   // not a friend

  await call(will.token, '/v1/party/invite', { uuid: alex.uuid });
  const inv = (await sync(alex)).partyInvites;
  assert.deepEqual(inv.map(i => [i.partyId, i.from, i.name]), [[pid, will.uuid, 'Will']]);
  r = await call(alex.token, '/v1/party/accept', { partyId: pid });
  assert.deepEqual(r.body.party.members.map(m => m.name), ['Will', 'Alex']);
  assert.equal((await sync(alex)).partyInvites.length, 0);
  assert.equal((await call(alex.token, '/v1/party/accept', { partyId: pid })).status, 404);    // invite used up

  // Any member may invite their own friends.
  await call(alex.token, '/v1/party/invite', { uuid: kim.uuid });
  await call(kim.token, '/v1/party/accept', { partyId: pid });
  await call(will.token, '/v1/party/invite', { uuid: sam.uuid });
  assert.deepEqual((await call(sam.token, '/v1/party/decline', { partyId: pid })).body, { ok: true });
  assert.equal((await sync(sam)).partyInvites.length, 0);

  // Leader-only actions.
  assert.equal((await call(alex.token, '/v1/party/kick', { uuid: kim.uuid })).status, 403);
  assert.equal((await call(will.token, '/v1/party/kick', { uuid: joe.uuid })).status, 404);
  assert.equal((await call(will.token, '/v1/party/kick', { uuid: will.uuid })).status, 400);
  r = await call(will.token, '/v1/party/promote', { uuid: alex.uuid });
  assert.equal(r.body.party.leader, alex.uuid);
  assert.equal(r.body.party.members[0].uuid, alex.uuid);                                      // leader listed first
  assert.equal((await call(will.token, '/v1/party/promote', { uuid: will.uuid })).status, 403);
  r = await call(alex.token, '/v1/party/kick', { uuid: kim.uuid });
  assert.deepEqual(r.body.party.members.map(m => m.name), ['Alex', 'Will']);
  assert.equal((await sync(kim)).party, null);

  // Leader leaves -> the longest member (Will, who made it) leads.
  await call(alex.token, '/v1/party/leave', {});
  const w = await sync(will);
  assert.equal(w.party.leader, will.uuid);
  assert.deepEqual(w.party.members.map(m => m.uuid), [will.uuid]);
  await call(will.token, '/v1/party/chat', { text: 'bye' });
  await call(will.token, '/v1/party/leave', {});
  assert.equal((await call(will.token, '/v1/party/leave', {})).status, 400);
  for (const t of ['parties', 'party_members', 'party_invites', 'pings', 'warps']) assert.equal(rows(`SELECT * FROM ${t}`).length, 0, t);
  assert.equal(rows('SELECT * FROM chat WHERE party_id IS NOT NULL').length, 0);
});

test('party: inviting with no party starts one; accepting moves you; max 8; invites run out', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  await befriend(will, alex);
  const r = await call(will.token, '/v1/party/invite', { uuid: alex.uuid });
  assert.equal(r.body.party.leader, will.uuid);
  const willParty = r.body.party.id;

  // Alex is in her own party and moves to Will's.
  const own = (await call(alex.token, '/v1/party/create', {})).body.party.id;
  await call(alex.token, '/v1/party/accept', { partyId: willParty });
  assert.equal((await sync(alex)).party.id, willParty);
  assert.equal(rows(`SELECT * FROM parties WHERE id = '${own}'`).length, 0);

  // Fill to 8.
  const more = [];
  for (let i = 0; i < 7; i++) { const p = await signIn(`Friend${i}`); await befriend(will, p); more.push(p); }
  for (const p of more.slice(0, 6)) await call(will.token, '/v1/party/invite', { uuid: p.uuid });
  for (const p of more.slice(0, 6)) assert.equal((await call(p.token, '/v1/party/accept', { partyId: willParty })).status, 200);
  assert.equal((await sync(will)).party.members.length, 8);
  const full = await call(will.token, '/v1/party/invite', { uuid: more[6].uuid });
  assert.equal(full.status, 400);
  assert.match(full.body.error, /full/);

  // An invite sent earlier can't squeeze a 9th in either.
  await call(more[0].token, '/v1/party/leave', {});
  await call(will.token, '/v1/party/invite', { uuid: more[6].uuid });
  await call(will.token, '/v1/party/invite', { uuid: more[0].uuid });
  await call(more[6].token, '/v1/party/accept', { partyId: willParty });
  assert.equal((await call(more[0].token, '/v1/party/accept', { partyId: willParty })).status, 400);

  // Invites last 10 minutes.
  await call(more[0].token, '/v1/party/create', {});
  await call(more[0].token, '/v1/party/invite', { uuid: will.uuid });
  tick(10 * 60_000);
  assert.equal((await sync(will)).partyInvites.length, 0);
  const pid = rows('SELECT party_id FROM party_invites')[0].party_id;
  assert.equal((await call(will.token, '/v1/party/accept', { partyId: pid })).status, 404);
});

test('party state: positions only between members on the same server, and only recent ones', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam'), kim = await signIn('Kim');
  await befriend(will, alex); await befriend(will, sam);
  const pid = (await call(will.token, '/v1/party/invite', { uuid: alex.uuid })).body.party.id;
  await call(will.token, '/v1/party/invite', { uuid: sam.uuid });
  await call(alex.token, '/v1/party/accept', { partyId: pid });
  await call(sam.token, '/v1/party/accept', { partyId: pid });

  await sync(will, onServer('mc.a.net', { partyState: at('mc.a.net', 10.04) }));
  await sync(sam, onServer('other.net', { partyState: at('other.net') }));
  const a = await sync(alex, onServer('MC.A.NET:25565', { partyState: at('mc.a.net', 5) }));
  const byName = Object.fromEntries(a.party.members.map(m => [m.name, m]));
  assert.deepEqual(byName.Will.state, { server: 'mc.a.net', dim: 'minecraft:overworld', x: 10, y: 64, z: 0, health: 20, maxHealth: 20 });
  assert.equal(typeof byName.Will.stateAt, 'number');
  assert.equal(byName.Will.online, true);
  assert.equal(byName.Sam.state, null);                          // other server
  assert.equal(byName.Sam.stateAt, null);
  assert.equal((await sync(sam, onServer('other.net'))).party.members.find(m => m.name === 'Will').state, null);
  // The launcher (not on a server) never gets positions; outsiders don't get the party at all.
  assert.ok((await sync(alex, { presence: { client: 'launcher', activity: 'menu' } })).party.members.every(m => m.state === null));
  assert.equal((await sync(kim)).party, null);
  // Junk positions are ignored, not an error.
  assert.equal((await call(will.token, '/v1/sync', { since: 0, partyState: { server: 'mc.a.net', x: 'NaN' } })).status, 200);

  // An appear-offline member shows offline, but party members still get the position.
  await call(will.token, '/v1/settings', { appearOffline: true });
  const w = (await sync(alex, onServer('mc.a.net'))).party.members.find(m => m.name === 'Will');
  assert.deepEqual([w.online, w.state?.x], [false, 10]);

  // Old positions (> 90 s) aren't shown.
  tick(91_000);
  assert.equal((await sync(alex, onServer('mc.a.net'))).party.members.find(m => m.name === 'Will').state, null);
});

test('party chat: members only, 1/s, newcomers see only what came after they joined', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam');
  await befriend(will, alex); await befriend(will, sam);
  assert.equal((await call(will.token, '/v1/party/chat', { text: 'hi' })).status, 400);       // no party
  const pid = (await call(will.token, '/v1/party/invite', { uuid: alex.uuid })).body.party.id;
  await call(alex.token, '/v1/party/accept', { partyId: pid });
  step = 0;
  const sent = await call(will.token, '/v1/party/chat', { text: 'before sam' });
  assert.equal(sent.body.message.name, 'Will');
  assert.equal((await call(will.token, '/v1/party/chat', { text: 'too fast' })).status, 429);
  step = 1001; tick(1000);
  assert.equal((await call(will.token, '/v1/party/chat', { text: 'x'.repeat(257) })).status, 400);

  await call(will.token, '/v1/party/invite', { uuid: sam.uuid });
  await call(sam.token, '/v1/party/accept', { partyId: pid });
  await call(alex.token, '/v1/party/chat', { text: 'after sam' });
  const a = await sync(alex);
  assert.deepEqual(a.partyMessages.map(m => [m.name, m.text]), [['Will', 'before sam'], ['Alex', 'after sam']]);
  assert.deepEqual((await sync(sam)).partyMessages.map(m => m.text), ['after sam']);
  assert.equal(a.messages.length, 0);                            // party lines are not DMs
  const again = await sync(alex, { since: a.cursor });
  assert.equal(again.partyMessages.length, 0);
});

test('pings: same-server members see them for 60 s; labels up to 32; 1/s', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam');
  await befriend(will, alex); await befriend(will, sam);
  const ping = { server: 'mc.a.net', dim: 'minecraft:the_nether', x: 1.25, y: 70, z: -3, label: 'here' };
  assert.equal((await call(will.token, '/v1/party/ping', ping)).status, 400);                  // no party
  const pid = (await call(will.token, '/v1/party/invite', { uuid: alex.uuid })).body.party.id;
  await call(will.token, '/v1/party/invite', { uuid: sam.uuid });
  await call(alex.token, '/v1/party/accept', { partyId: pid });
  await call(sam.token, '/v1/party/accept', { partyId: pid });

  assert.equal((await call(will.token, '/v1/party/ping', { ...ping, label: 'y'.repeat(33) })).status, 400);
  assert.equal((await call(will.token, '/v1/party/ping', { ...ping, x: 'far' })).status, 400);
  step = 0;
  const r = await call(will.token, '/v1/party/ping', ping);
  assert.equal(r.status, 200);
  assert.equal(r.body.ping.expiresAt - r.body.ping.at, 60_000);
  assert.equal((await call(will.token, '/v1/party/ping', { ...ping, label: null })).status, 429);
  step = 1001;

  const p = (await sync(alex, onServer('mc.a.net'))).pings;
  assert.deepEqual(p.map(x => [x.name, x.dim, x.x, x.label]), [['Will', 'minecraft:the_nether', 1.3, 'here']]);
  assert.equal((await sync(sam, onServer('elsewhere.net'))).pings.length, 0);
  tick(60_000);
  assert.equal((await sync(alex, onServer('mc.a.net'))).pings.length, 0);
});

test('warp: leader only; the other members see it for 2 minutes', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  await befriend(will, alex);
  const pid = (await call(will.token, '/v1/party/invite', { uuid: alex.uuid })).body.party.id;
  await call(alex.token, '/v1/party/accept', { partyId: pid });
  assert.equal((await call(alex.token, '/v1/party/warp', { server: 'a.net', version: '1.21.1' })).status, 403);
  assert.equal((await call(will.token, '/v1/party/warp', { server: 'a.net' })).status, 400);
  assert.equal((await call(will.token, '/v1/party/warp', { server: 'Play.A.net', version: '1.21.1' })).status, 200);
  const w = (await sync(alex)).warp;
  assert.deepEqual({ ...w, at: typeof w.at }, { server: 'play.a.net', version: '1.21.1', at: 'number', from: will.uuid, name: 'Will' });
  assert.equal((await sync(will)).warp, null);                   // not to the leader themself
  tick(120_000);
  assert.equal((await sync(alex)).warp, null);
});

// ---------------------------------------------------------------- cleanup
test('cleanup: expired sessions, 30-day-old chat, expired pings/warps/invites, idle party members', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam');
  await befriend(will, alex); await befriend(will, sam);
  await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'old news' });
  const pid = (await call(will.token, '/v1/party/invite', { uuid: alex.uuid })).body.party.id;
  await call(alex.token, '/v1/party/accept', { partyId: pid });
  await call(will.token, '/v1/party/invite', { uuid: sam.uuid });
  await call(will.token, '/v1/party/ping', { server: 'a.net', dim: 'minecraft:overworld', x: 0, y: 0, z: 0 });
  await call(will.token, '/v1/party/warp', { server: 'a.net', version: '1.21.1' });
  await sync(alex, { presence: { client: 'launcher', activity: 'menu' } });

  // 30 minutes on, Will is still around (so the party survives): only short-lived rows go.
  tick(30 * 60_000);
  await sync(will, { presence: { client: 'game', activity: 'menu' } });
  await socialCleanup(env, clock);
  assert.equal(rows('SELECT * FROM pings').length + rows('SELECT * FROM warps').length + rows('SELECT * FROM party_invites').length, 0);
  assert.equal(rows('SELECT * FROM party_members').length, 2);

  // 61 minutes after Alex was last seen anywhere, she leaves the party.
  tick(31 * 60_000);
  await sync(will, { presence: { client: 'game', activity: 'menu' } });
  assert.deepEqual(await socialCleanup(env, clock), { idleLeft: 1 });
  assert.deepEqual(rows('SELECT uuid FROM party_members').map(r => r.uuid), [will.uuid]);

  tick(24 * 3600_000);
  await socialCleanup(env, clock);
  assert.equal(rows('SELECT * FROM sessions').length, 0);
  assert.equal(rows('SELECT * FROM joins').length, 0);
  assert.equal(rows('SELECT * FROM parties').length, 0);         // Will idle too now: party gone
  assert.equal(rows('SELECT * FROM chat').length, 1);
  tick(30 * 24 * 3600_000);
  await socialCleanup(env, clock);
  assert.equal(rows('SELECT * FROM chat').length, 0);
});

test('cron: social cleanup runs every 10th minute next to delivery, never instead of it', async () => {
  await signIn('Will');
  env.DB.raw.prepare("INSERT INTO meta (k, v) VALUES ('tebex_next_check', ?)").run(String(clock + 3600_000));
  env.DB.raw.prepare('UPDATE sessions SET expires = ?').run(clock - 1);
  await scheduled({ scheduledTime: 600_000 * 5 + 60_000 }, env, clock);       // minute 501: not a cleanup minute
  assert.equal(rows('SELECT * FROM sessions').length, 1);
  await scheduled({ scheduledTime: 600_000 * 5 }, env, clock);                // minute 500
  assert.equal(rows('SELECT * FROM sessions').length, 0);
  assert.equal(calls.filter(c => c.includes('tebex')).length, 0);             // delivery still obeyed its own wait
});
