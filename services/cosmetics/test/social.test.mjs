// Origin social (docs/SOCIAL.md) against the real Worker code and a real
// SQLite database, with Mojang played by test/mojang.mjs (real RSA keys,
// signed like Mojang signs) and the clock under the test's control.
// `npm test` (node --test) — no packages to install.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { handle, scheduled } from '../src/index.js';
import { socialCleanup, presenceSweep, holdClock } from '../src/social.js';
import { d1 } from './d1.mjs';
import { authBody, seedKeys, b64, spki, MOJANG_CERT, MOJANG_PROFILE, NOT_MOJANG, PLAYER_KEY, OTHER_PLAYER_KEY } from './mojang.mjs';

// ------------------------------------------------------------- the harness
let env, clock, step, calls, duringHold, checks;

// Every API call moves the clock on by `step` ms (default just over the
// 1-per-second limits) so ordinary flows never trip them; tests about
// timing set step = 0 and move the clock themselves.
const tick = ms => { clock += ms; };
Date.now = () => clock;

function uuidFor(name) { return createHash('md5').update(name).digest('hex'); }

beforeEach(() => {
  env = { DB: d1() };
  seedKeys(env);
  clock = 1_800_000_000_000;
  step = 1001;
  calls = [];
  // A held sync sleeps on the test's clock; `duringHold(t)` runs inside each
  // sleep (another player acting mid-hold), and `checks` counts the sleeps.
  duringHold = null; checks = 0;
  holdClock.sleep = async ms => { checks++; clock += ms; if (duringHold) await duringHold(clock); };
  // The service must never call out for sign-in or friends (Mojang blocks
  // Workers), and nothing on the cron calls out either.
  globalThis.fetch = async url => {
    calls.push(String(url));
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
// A /v1/auth body for `name`, valid now unless `opts` breaks it.
const proof = (name, opts = {}) => authBody({ name, uuid: uuidFor(name), now: clock, ...opts });
async function signIn(name, client = 'game') {
  const r = await call(null, '/v1/auth', proof(name, { client }));
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
test('auth: Mojang-signed certificate + profile get a 24 h token, stored only as its SHA-256', async () => {
  const body = proof('Will', { client: 'launcher' });
  const u = body.uuid;
  body.uuid = `${u.slice(0, 8)}-${u.slice(8, 12)}-${u.slice(12, 16)}-${u.slice(16, 20)}-${u.slice(20)}`.toUpperCase();
  const r = await call(null, '/v1/auth', body);                 // dashed upper-case uuid is fine
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.match(r.body.token, /^[0-9a-f]{64}$/);
  assert.equal(r.body.uuid, uuidFor('Will'));
  assert.equal(r.body.name, 'Will');
  assert.equal(r.body.expiresAt, clock - step + 24 * 3600_000);
  const [s] = rows('SELECT * FROM sessions');
  assert.equal(s.token_hash, createHash('sha256').update(r.body.token).digest('hex'));
  assert.equal(s.client, 'launcher');
  assert.ok(!JSON.stringify(rows('SELECT * FROM sessions')).includes(r.body.token));
  assert.deepEqual(rows('SELECT name, name_lower FROM names').map(r => ({ ...r })), [{ name: 'Will', name_lower: 'will' }]);
  assert.equal((await call(r.body.token, '/v1/sync', { since: 0 })).status, 200);
  assert.equal(calls.length, 0);                                 // nothing asked of Mojang
});

test('auth: accepts PEM keys (Mojang\'s "RSA PUBLIC KEY" armour), URL-safe base64 and missing padding', async () => {
  const der = b64(spki(PLAYER_KEY));
  const pem = `-----BEGIN RSA PUBLIC KEY-----\n${der.match(/.{1,76}/g).join('\n')}\n-----END RSA PUBLIC KEY-----\n`;
  assert.equal((await call(null, '/v1/auth', proof('Will', { publicKeyText: pem }))).status, 200);
  const urlSafe = s => s.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const body = proof('Alex');
  body.sig = urlSafe(body.sig);
  body.keySignature = urlSafe(body.keySignature);
  body.textures.signature = urlSafe(body.textures.signature);
  assert.equal((await call(null, '/v1/auth', body)).status, 200);
});

test('auth: the certificate must be Mojang\'s, for this uuid, and unexpired', async () => {
  const expectFail = async (opts, status, re) => {
    const r = await call(null, '/v1/auth', proof('Will', opts));
    assert.equal(r.status, status, JSON.stringify([opts, r.body]));
    assert.match(r.body.error, re);
    assert.ok(!/sha|rsa|signature|certificate|key/i.test(r.body.error), r.body.error);   // no internals
  };
  await expectFail({ certSigner: NOT_MOJANG }, 401, /Minecraft account/);              // signed by a stranger
  await expectFail({ certSigner: MOJANG_PROFILE }, 401, /Minecraft account/);          // Mojang's other key doesn't vouch for certificates
  await expectFail({ certUuid: uuidFor('Alex') }, 401, /Minecraft account/);           // someone else's certificate
  await expectFail({ keyExpiresAt: clock - 1 }, 401, /ran out/);                       // expired
  const tampered = proof('Will');
  tampered.keyExpiresAt += 1000;                                                       // expiry not what Mojang signed
  assert.equal((await call(null, '/v1/auth', tampered)).status, 401);
  // A real certificate for Will, but the request names Alex: the uuid is part of what Mojang signed.
  const stolen = proof('Will');
  stolen.uuid = uuidFor('Alex');
  assert.equal((await call(null, '/v1/auth', stolen)).status, 401);
  assert.equal(rows('SELECT * FROM sessions').length, 0);
});

test('auth: the sender must hold the certificate key, recently, once', async () => {
  let r = await call(null, '/v1/auth', proof('Will', { signingKey: OTHER_PLAYER_KEY }));
  assert.equal(r.status, 401);
  assert.match(r.body.error, /Minecraft account/);
  const forged = proof('Will');
  forged.client = 'launcher';                                    // signed as "game"
  assert.equal((await call(null, '/v1/auth', forged)).status, 401);

  r = await call(null, '/v1/auth', proof('Will', { at: clock - 5 * 60_000 - 1 }));
  assert.equal(r.status, 401);
  assert.match(r.body.error, /clock/);
  assert.equal((await call(null, '/v1/auth', proof('Will', { at: clock + 5 * 60_000 + 10_000 }))).status, 401);
  assert.equal((await call(null, '/v1/auth', proof('Will', { at: clock - 4 * 60_000 }))).status, 200);   // inside ±5 min

  const once = proof('Will');
  assert.equal((await call(null, '/v1/auth', once)).status, 200);
  r = await call(null, '/v1/auth', once);                        // replayed within the 5 minutes
  assert.equal(r.status, 401);
  assert.match(r.body.error, /already used/);
  tick(10 * 60_000);
  assert.equal((await call(null, '/v1/auth', once)).status, 401); // ...and stale after them
});

test('auth: the name must be Mojang-signed, for this uuid, exact, and from the last 24 h', async () => {
  const expectName = async (opts, re = /Minecraft name/) => {
    const r = await call(null, '/v1/auth', proof('Will', opts));
    assert.equal(r.status, 401, JSON.stringify(opts));
    assert.match(r.body.error, re);
  };
  await expectName({ profileSigner: NOT_MOJANG });
  await expectName({ profileSigner: MOJANG_CERT });
  await expectName({ profileName: 'will' });                    // exact spelling
  await expectName({ profileName: 'Notch' });
  await expectName({ profileId: uuidFor('Notch') });
  await expectName({ timestamp: clock - 24 * 3600_000 - 1 });
  await expectName({ timestamp: clock + 10 * 60_000 });
  const edited = proof('Will');
  edited.textures.value = edited.textures.value.replace(/=*$/, '') + ' ';   // not the bytes Mojang signed
  assert.equal((await call(null, '/v1/auth', edited)).status, 401);
  const renamed = proof('Will');
  renamed.name = 'Notch';                                        // claims another name than the signed one
  assert.equal((await call(null, '/v1/auth', renamed)).status, 401);
  assert.equal((await call(null, '/v1/auth', proof('Will', { timestamp: clock - 23 * 3600_000 }))).status, 200);
});

test('auth: malformed bodies are a 400 "update Origin"; no Mojang keys yet is a friendly 503', async () => {
  const good = proof('Will');
  const broken = [
    {}, { ...good, client: 'phone' }, { ...good, name: 'Will!' }, { ...good, uuid: 'abc' }, { ...good, at: '123' },
    { ...good, keyExpiresAt: 1.5 }, { ...good, sig: '' }, { ...good, sig: 'not base64!' }, { ...good, publicKey: 'AAAA' },
    { ...good, textures: null }, { ...good, textures: { value: good.textures.value } },
    // The v1 body (Mojang server-join check) is refused the same way.
    { name: 'Will', serverId: 'ab'.repeat(16), client: 'game' },
  ];
  for (const b of broken) {
    const r = await call(null, '/v1/auth', b);
    assert.equal(r.status, 400, JSON.stringify(b).slice(0, 120));
    assert.match(r.body.error, /update Origin/);
  }

  env.DB.raw.prepare("DELETE FROM meta WHERE k = 'mojang_keys'").run();
  let r = await call(null, '/v1/auth', proof('Will'));
  assert.equal(r.status, 503);
  assert.match(r.body.error, /try again/i);
  seedKeys(env, { profile: [], certificate: [MOJANG_CERT] });   // an empty list counts as missing
  assert.equal((await call(null, '/v1/auth', proof('Will'))).status, 503);
  seedKeys(env);                                                 // keys arrive: works at once (misses aren't cached)
  assert.equal((await call(null, '/v1/auth', proof('Will'))).status, 200);
});

test('diag: /v1/diag/keys reports only how many usable keys are stored', async () => {
  let r = await call(null, '/v1/diag/keys', undefined, 'GET');
  assert.deepEqual(r.body, { profile: 2, certificate: 2, at: 1 });
  env.DB.raw.prepare("DELETE FROM meta WHERE k = 'mojang_keys'").run();
  r = await call(null, '/v1/diag/keys', undefined, 'GET');
  assert.deepEqual(r.body, { profile: 0, certificate: 0, at: null });
});

test('auth: bad or expired tokens are refused', async () => {
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

  // Anyone who never signed in to Origin (real Minecraft player or not) is "not on Origin".
  let r = await call(will.token, '/v1/friends/request', { name: 'steve' });
  assert.equal(r.status, 404);
  assert.match(r.body.error, /steve isn't on Origin/);
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

test('friends: names are found in Origin\'s own names table, any case; after a rename the newest sign-in wins', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  let r = await call(will.token, '/v1/friends/request', { name: 'ALEX' });
  assert.deepEqual(r.body, { ok: true, uuid: alex.uuid, name: 'Alex', friends: false });
  // Alex renames to Alexis; a new account takes the name Alex and signs in later.
  const signInAs = async (name, uuid) => {
    const res = await call(null, '/v1/auth', authBody({ name, uuid, now: clock }));
    assert.equal(res.status, 200, JSON.stringify(res.body));
    return res.body;
  };
  await signInAs('Alexis', alex.uuid);
  r = await call(will.token, '/v1/friends/request', { name: 'alexis' });
  assert.equal(r.body.uuid, alex.uuid);
  assert.equal((await call(will.token, '/v1/friends/request', { name: 'Alex' })).status, 404);
  const newAlex = await signInAs('Alex', uuidFor('NewAlex'));
  assert.equal((await call(will.token, '/v1/friends/request', { name: 'alex' })).body.uuid, newAlex.uuid);
  assert.equal(calls.length, 0);                                 // no Mojang lookup
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

  // Online = seen in the last 150 s.
  tick(140_000);                                                // a few calls already passed since Will's write
  assert.equal((await sync(alex)).friends[0].online, true);
  tick(10_000);
  f = (await sync(alex)).friends[0];
  assert.equal(f.online, false);
  assert.equal(f.server, null);
});

test('presence: written at most every 60 s, sooner (5 s) only when it changed', async () => {
  const will = await signIn('Will');
  step = 0;
  const atNow = () => rows('SELECT at FROM presence')[0].at;
  await sync(will, onServer('a.net'));
  const t0 = atNow();
  tick(30_000); await sync(will, onServer('a.net'));
  assert.equal(atNow(), t0);                                    // same place, 30 s later: no write
  tick(29_000); await sync(will, onServer('a.net'));
  assert.equal(atNow(), t0);                                    // 59 s: still no write
  tick(1_000); await sync(will, onServer('a.net'));
  assert.equal(atNow(), t0 + 60_000);                           // 60 s: heartbeat write
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

test('cron: social cleanup runs every 10th minute, and nothing on the cron calls out', async () => {
  await signIn('Will');
  env.DB.raw.prepare('UPDATE sessions SET expires = ?').run(clock - 1);
  await scheduled({ scheduledTime: 600_000 * 5 + 60_000 }, env, clock);       // minute 501: not a cleanup minute
  assert.equal(rows('SELECT * FROM sessions').length, 1);
  await scheduled({ scheduledTime: 600_000 * 5 }, env, clock);                // minute 500
  assert.equal(rows('SELECT * FROM sessions').length, 0);
  assert.equal(calls.length, 0);
});

// ------------------------------------------------- free-plan savings (etag)
test('friendsEtag: an unchanged friends list is skipped; every kind of change brings it back', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex'), sam = await signIn('Sam');
  await befriend(will, alex);
  const lists = r => ['friends', 'requests', 'blocked'].filter(k => k in r);

  // Old clients (no etag) always get everything.
  let r = await sync(will);
  assert.deepEqual(lists(r), ['friends', 'requests', 'blocked']);
  assert.equal(typeof r.friendsEtag, 'string');
  r = await sync(will);
  assert.deepEqual(lists(r), ['friends', 'requests', 'blocked']);

  let etag = r.friendsEtag;
  const poll = async (body = {}) => { const x = await sync(will, { friendsEtag: etag, ...body }); const got = lists(x).length > 0; etag = x.friendsEtag; return { got, x }; };
  assert.equal((await poll()).got, false);                          // nothing changed
  assert.equal((await poll(onServer('a.net'))).got, false);         // my own presence isn't in my list

  // Alex comes online -> refresh; her 60 s heartbeat -> no refresh; she changes server -> refresh.
  await sync(alex, onServer('b.net'));
  let p = await poll();
  assert.equal(p.got, true);
  assert.equal(p.x.friends[0].server, 'b.net');
  tick(60_000);
  await sync(alex, onServer('b.net'));
  assert.equal((await poll()).got, false);
  tick(6_000);
  await sync(alex, onServer('c.net'));
  assert.equal((await poll()).x.friends[0].server, 'c.net');

  // Appear offline -> refresh.
  await call(alex.token, '/v1/settings', { appearOffline: true });
  p = await poll();
  assert.equal(p.x.friends[0].online, false);
  await call(alex.token, '/v1/settings', { appearOffline: false });
  assert.equal((await poll()).got, true);

  // Requests in, decline, block/unblock, remove -> refresh each time.
  await call(sam.token, '/v1/friends/request', { name: 'Will' });
  p = await poll();
  assert.deepEqual(p.x.requests.incoming.map(q => q.name), ['Sam']);
  assert.equal((await poll()).got, false);
  await call(will.token, '/v1/friends/respond', { uuid: sam.uuid, accept: false });
  assert.equal((await poll()).x.requests.incoming.length, 0);
  await call(will.token, '/v1/friends/block', { uuid: sam.uuid });
  assert.deepEqual((await poll()).x.blocked.map(b => b.name), ['Sam']);
  await call(will.token, '/v1/friends/unblock', { uuid: sam.uuid });
  assert.equal((await poll()).x.blocked.length, 0);
  await call(alex.token, '/v1/friends/remove', { uuid: will.uuid });
  assert.equal((await poll()).x.friends.length, 0);
  assert.equal((await poll()).got, false);

  // The 10-minute safety refresh.
  tick(10 * 60_000);
  assert.equal((await poll()).got, true);
});

test('friendsEtag: a friend going offline (no write at all) is caught by the cron sweep', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  await befriend(will, alex);
  step = 0;
  clock = Math.ceil(clock / 600_000) * 600_000 + 10_000;             // just after a minute (and 10-min) boundary
  await sync(alex, onServer('b.net'));
  const seenAt = clock;
  let r = await sync(will);
  assert.equal(r.friends[0].online, true);
  const etag = r.friendsEtag;

  // Alex quits. The minute-sweep whose window holds seenAt + 90 s bumps Will.
  const sweepMinute = Math.ceil((seenAt + 150_000) / 60_000) * 60_000;
  await presenceSweep(env, sweepMinute - 60_000);                     // window ends before she went stale
  clock = sweepMinute - 60_000 + 1;
  assert.equal('friends' in (await sync(will, { friendsEtag: etag })), false);
  await presenceSweep(env, sweepMinute);
  clock = sweepMinute + 1;
  r = await sync(will, { friendsEtag: etag });
  assert.equal(r.friends[0].online, false);

  // The cron runs the sweep every minute.
  const before = rows(`SELECT friends_rev FROM names WHERE uuid = '${will.uuid}'`)[0].friends_rev;
  await sync(alex, onServer('b.net'));                                // back online...
  const back = clock;
  await scheduled({ scheduledTime: Math.ceil((back + 150_000) / 60_000) * 60_000 }, env, clock);
  // +1 for coming back online, +1 for the sweep seeing her go stale again.
  assert.equal(rows(`SELECT friends_rev FROM names WHERE uuid = '${will.uuid}'`)[0].friends_rev, before + 2);
});

test('wantState: true only when another online party member is on my server', async () => {
  const will = await signIn('Will'), alex = await signIn('Alex');
  await befriend(will, alex);
  assert.equal((await sync(will, onServer('a.net'))).wantState, false);            // no party
  const pid = (await call(will.token, '/v1/party/create', {})).body.party.id;
  assert.equal((await sync(will, onServer('a.net'))).wantState, false);            // alone
  await call(will.token, '/v1/party/invite', { uuid: alex.uuid });
  await call(alex.token, '/v1/party/accept', { partyId: pid });
  assert.equal((await sync(will, onServer('a.net'))).wantState, false);            // Alex not in game

  await sync(alex, onServer('b.net'));
  assert.equal((await sync(will, onServer('a.net'))).wantState, false);            // other server
  tick(6_000);                                                        // past the 5 s change throttle
  await sync(alex, onServer('A.net:25565'));
  assert.equal((await sync(will, onServer('a.net'))).wantState, true);             // same (normalised) server
  assert.equal((await sync(will, { presence: { client: 'launcher', activity: 'menu' } })).wantState, false);

  // LAN: the host plays "singleplayer" and holds positions back; stateServer
  // says where it would share them, so it learns when a member joined.
  tick(6_000);
  await sync(alex, onServer('192.168.1.5:41000'));
  const host = { presence: { client: 'game', activity: 'singleplayer', version: '1.21.1' } };
  assert.equal((await sync(will, host)).wantState, false);
  assert.equal((await sync(will, { ...host, stateServer: '192.168.1.5:41000' })).wantState, true);
  // Sent anyway while wantState is false: still accepted and stored.
  await sync(will, { ...host, partyState: at('lan.only') });
  assert.equal(rows(`SELECT state_server FROM party_members WHERE uuid = '${will.uuid}'`)[0].state_server, 'lan.only');

  // Alex quits: once she counts as offline (150 s), nobody wants Will's position.
  tick(151_000);
  assert.equal((await sync(will, { ...host, stateServer: '192.168.1.5:41000' })).wantState, false);
});

test('party state: a moving position is rewritten at most every 5 s, a still one every 30 s', async () => {
  const will = await signIn('Will');
  await call(will.token, '/v1/party/create', {});
  step = 0;
  const stored = () => rows(`SELECT state, state_at FROM party_members WHERE uuid = '${will.uuid}'`)[0];
  await sync(will, { partyState: at('a.net', 1) });
  const t0 = stored().state_at;
  tick(4_000); await sync(will, { partyState: at('a.net', 2) });
  assert.equal(stored().state_at, t0);                                // moved, but < 5 s
  tick(1_000); await sync(will, { partyState: at('a.net', 3) });
  assert.equal(JSON.parse(stored().state).x, 3);                      // 5 s: written
  tick(20_000); await sync(will, { partyState: at('a.net', 3) });
  assert.equal(stored().state_at, t0 + 5_000);                        // standing still
  tick(10_000); await sync(will, { partyState: at('a.net', 3) });
  assert.equal(stored().state_at, t0 + 35_000);                       // 30 s heartbeat
  tick(1_000); await sync(will, { partyState: at('b.net', 3) });
  assert.equal(stored().state_at, t0 + 36_000);                       // new server: at once
});

// ------------------------------------------------------------ held sync (wait)
// A held answer for `who` (sync() with `wait`), plus how long it took on the clock.
async function held(who, body) {
  const t = clock;
  const r = await sync(who, { wait: 20_000, ...body });
  return { r, took: clock - t };
}
async function chatParty() {
  const will = await signIn('Will'), alex = await signIn('Alex');
  await befriend(will, alex);
  const pid = (await call(will.token, '/v1/party/invite', { uuid: alex.uuid })).body.party.id;
  await call(alex.token, '/v1/party/accept', { partyId: pid });
  await call(will.token, '/v1/party/chat', { text: 'hello' });
  const first = await sync(alex);
  assert.ok(first.cursor > 0);
  step = 0;
  return { will, alex, pid, since: first.cursor, friendsEtag: first.friendsEtag };
}
const settleMicrotasks = async (done, what) => {
  for (let i = 0; i < 1000 && !done(); i++) await new Promise(r => setImmediate(r));
  assert.ok(done(), what);
};

test('held sync: a quiet one waits the full 20 s (checking every 3 s) and says so', async () => {
  const { alex, since, friendsEtag } = await chatParty();
  const { r, took } = await held(alex, { since, friendsEtag });
  assert.equal(took, 20_000);
  assert.equal(r.held, 20_000);
  assert.equal(checks, 7);                                        // sleeps 3+3+3+3+3+3+2: 8 chat checks in all
  assert.equal(r.partyMessages.length, 0);
  assert.equal(r.cursor, since);
  assert.ok(!('friends' in r));                                   // etag still current
  // wait is capped at 20 s
  assert.equal((await held(alex, { since, friendsEtag, wait: 600_000 })).took, 20_000);
});

test('held sync: a party line sent mid-hold is answered within one check (3 s)', async () => {
  const { will, alex, since, friendsEtag } = await chatParty();
  let sentAt = null;
  const start = clock;
  duringHold = async () => {
    if (sentAt === null && clock - start >= 9_000) {
      sentAt = clock - 2_500;                                     // landed 2.5 s before this check: the worst case
      await call(will.token, '/v1/party/chat', { text: '/originpc hi' });
    }
  };
  const { r, took } = await held(alex, { since, friendsEtag });
  assert.deepEqual(r.partyMessages.map(m => [m.name, m.text]), [['Will', '/originpc hi']]);
  assert.equal(took, 9_000);                                      // answered at the first check after it
  assert.equal(r.held, 9_000);
  assert.ok(clock - sentAt <= 3_000);
  assert.ok(r.cursor > since);
  // the sender's own line also ends the sender's hold (another client of theirs shows it)
  duringHold = null;
  const w = await held(will, { since, friendsEtag: (await sync(will, { since })).friendsEtag });
  assert.equal(w.took, 0);
  assert.equal(w.r.held, 0);
  assert.deepEqual(w.r.partyMessages.map(m => m.text), ['/originpc hi']);
});

test('held sync: only chat ends it (a DM to you, a party line); friends-list and party changes ride along', async () => {
  const { will, alex, since, friendsEtag } = await chatParty();
  const sam = await signIn('Sam');
  step = 1001;
  await befriend(will, sam);                                      // not Alex's business: Alex's etag unchanged
  step = 0;
  // a DM to Alex
  duringHold = async () => { if (checks === 2) await call(will.token, '/v1/messages/send', { to: alex.uuid, text: 'dm' }); };
  let h = await held(alex, { since, friendsEtag });
  assert.equal(h.took, 6_000);
  assert.deepEqual(h.r.messages.map(m => m.text), ['dm']);
  // a friend request to Alex bumps Alex's list but doesn't end the hold (no extra requests per friend change)
  checks = 0;
  duringHold = async () => { if (checks === 1) await call(sam.token, '/v1/friends/request', { name: 'Alex' }); };
  h = await held(alex, { since: h.r.cursor, friendsEtag: h.r.friendsEtag });
  assert.equal(h.took, 20_000);
  const after = await sync(alex, { since: h.r.cursor, friendsEtag: h.r.friendsEtag });
  assert.deepEqual(after.requests.incoming.map(q => q.name), ['Sam']);   // the next sync brings it
  // kicked from the party: shown by the hold's answer, without ending it early
  checks = 0;
  duringHold = async () => { if (checks === 3) await call(will.token, '/v1/party/kick', { uuid: alex.uuid }); };
  h = await held(alex, { since: after.cursor, friendsEtag: after.friendsEtag });
  assert.equal(h.took, 20_000);
  assert.equal(h.r.party, null);
  // someone else's DMs don't wake a hold
  checks = 0;
  tick(1_000);
  duringHold = async () => { if (checks === 1) await call(will.token, '/v1/messages/send', { to: sam.uuid, text: 'not for alex' }); };
  h = await held(alex, { since: h.r.cursor, friendsEtag: h.r.friendsEtag });
  assert.equal(h.took, 20_000);
});

test('held sync: answered at once for a stale friendsEtag or chat newer than `since`; no `held` without `wait`', async () => {
  const { alex, since, friendsEtag } = await chatParty();
  let h = await held(alex, { since: 0, friendsEtag });             // chat exists: the first check answers
  assert.equal(h.took, 0); assert.equal(h.r.held, 0);
  assert.deepEqual(h.r.partyMessages.map(m => m.text), ['hello']);
  h = await held(alex, { since: 0 });                              // no etag yet = stale
  assert.equal(h.took, 0); assert.equal(h.r.held, 0);
  h = await held(alex, { since, friendsEtag: 'old' });
  assert.equal(h.took, 0); assert.equal(h.r.held, 0);
  assert.ok(Array.isArray(h.r.friends));
  const plain = await sync(alex, { since, friendsEtag });
  assert.ok(!('held' in plain));                                  // old clients see the old answer
  assert.equal(checks, 0);
});

test('held sync: a cursor of 0 with no chat stored yet is still held (holds never switch off)', async () => {
  const will = await signIn('Will');
  await call(will.token, '/v1/party/create', {});
  const first = await sync(will);
  assert.equal(first.cursor, 0);
  step = 0;
  const h = await held(will, { since: 0, friendsEtag: first.friendsEtag });
  assert.equal(h.took, 20_000);
  assert.equal(h.r.held, 20_000);
});

test('held sync: presence is written when the request arrives, never stamped with the answer time', async () => {
  const { will, alex, since, friendsEtag } = await chatParty();
  const presenceRow = () => rows(`SELECT server, at FROM presence WHERE uuid = '${alex.uuid}' AND client = 'game'`)[0];
  const start = clock;
  let seenByWill = null, rowMidHold = null;
  duringHold = async () => {
    if (checks === 2) {
      rowMidHold = { ...presenceRow() };
      seenByWill = (await sync(will, { since })).friends.find(f => f.uuid === alex.uuid);
    }
  };
  const h = await held(alex, { since, friendsEtag, ...onServer('a.net') });
  assert.equal(h.took, 20_000);
  assert.deepEqual(rowMidHold, { server: 'a.net', at: start });  // written before the hold, at the request's time
  assert.equal(seenByWill.server, 'a.net');                       // friends see the right server during the hold
  assert.deepEqual({ ...presenceRow() }, { server: 'a.net', at: start });   // the answer didn't rewrite it 20 s later
  // a held sync carrying a LAN position writes it at the start too
  checks = 0;
  const lan = at('192.168.1.5:41000', 7);
  await held(alex, { since: h.r.cursor, friendsEtag: h.r.friendsEtag, presence: { client: 'game', activity: 'singleplayer', version: '1.21.1' }, partyState: lan });
  const st = rows(`SELECT state, state_at FROM party_members WHERE uuid = '${alex.uuid}'`)[0];
  assert.equal(JSON.parse(st.state).x, 7);
  assert.equal(st.state_at, start + 20_000);                      // = when that second request arrived
});

test('held sync: one hold per account; a newer hold ends the older one at once', async () => {
  const { alex, since, friendsEtag } = await chatParty();
  const pending = [];
  holdClock.sleep = ms => new Promise(r => pending.push(() => { clock += ms; r(); }));
  const t0 = clock;
  let first = null, second = null;
  held(alex, { since, friendsEtag }).then(r => { first = r; });
  await settleMicrotasks(() => pending.length === 1, 'first hold sleeping');
  held(alex, { since, friendsEtag }).then(r => { second = r; });
  await settleMicrotasks(() => first !== null, 'the older hold answered when the newer one started');
  assert.equal(first.took, 0);                                    // no time passed: it was ended, not waited out
  assert.ok(second === null);
  await settleMicrotasks(() => pending.length === 2, 'second hold sleeping');
  pending.shift();                                                // the first hold's abandoned sleep: never fires
  while (second === null) {
    if (pending.length) pending.shift()();
    await new Promise(r => setImmediate(r));
  }
  assert.equal(second.r.held, 20_000);
  assert.equal(clock - t0, 20_000);
});
