// The website's live download count (src/downloads.js) against the real
// migrations on node:sqlite. `npm test`.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { handle } from '../src/index.js';
import { downloadCleanup, addressKey } from '../src/downloads.js';
import { d1 } from './d1.mjs';

let env;
beforeEach(() => { env = { DB: d1() }; });

const click = (origin, ip) => handle(new Request('https://api.test/v1/downloads', {
  method: 'POST',
  headers: { ...(origin ? { Origin: origin } : {}), ...(ip ? { 'CF-Connecting-IP': ip } : {}) },
}), env).then(r => r.json());
const count = () => handle(new Request('https://api.test/v1/downloads'), env).then(r => r.json());

test('starts at zero', async () => {
  assert.deepEqual(await count(), { count: 0 });
});

test('a click from the live site counts once per address per day', async () => {
  assert.deepEqual(await click('https://originclient.org', '1.2.3.4'), { count: 1, counted: true });
  assert.deepEqual(await click('https://originclient.org', '1.2.3.4'), { count: 1, counted: false });
  assert.deepEqual(await click('https://www.originclient.org', '5.6.7.8'), { count: 2, counted: true });
  assert.deepEqual(await count(), { count: 2 });
});

test('previews, other sites and requests with no address never count', async () => {
  for (const origin of ['http://localhost:3000', 'https://originclient-git-x.vercel.app', 'https://evil.example', null]) {
    assert.equal((await click(origin, '1.2.3.4')).counted, false, String(origin));
  }
  assert.equal((await click('https://originclient.org', null)).counted, false);
  assert.deepEqual(await count(), { count: 0 });
});

test('the address is never stored, only a hash; old days are cleaned up', async () => {
  await click('https://originclient.org', '1.2.3.4');
  const rows = env.DB.raw.prepare('SELECT who, day FROM download_clicks').all();
  assert.equal(rows.length, 1);
  assert.match(rows[0].who, /^[0-9a-f]{64}$/);
  assert.ok(!rows[0].who.includes('1.2.3.4'));
  await downloadCleanup(env, (rows[0].day + 1) * 86_400_000);
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) AS n FROM download_clicks').get().n, 0);
  assert.deepEqual(await count(), { count: 1 }, 'cleanup never lowers the total');
});

test('IPv6 counts per /64: one home cycling its addresses counts once', async () => {
  assert.equal(addressKey('2001:db8:aa:bb:1:2:3:4'), '2001:db8:aa:bb::/64');
  assert.equal(addressKey('2001:DB8:AA:BB::9'), '2001:db8:aa:bb::/64');
  assert.equal(addressKey('2001:db8::1'), '2001:db8:0:0::/64');
  assert.equal(addressKey('203.0.113.7'), '203.0.113.7');
  assert.equal((await click('https://originclient.org', '2001:db8:aa:bb:1:2:3:4')).counted, true);
  assert.equal((await click('https://originclient.org', '2001:db8:aa:bb::ffff')).counted, false);
  assert.equal((await click('https://originclient.org', '2001:db8:aa:cc::1')).counted, true);
});

test('a flood stops counting at the daily cap', async () => {
  const day = Math.floor(Date.now() / 86_400_000);
  const ins = env.DB.raw.prepare('INSERT INTO download_clicks (who, day) VALUES (?, ?)');
  for (let i = 0; i < 5000; i++) ins.run(`x${i}`, day);
  assert.equal((await click('https://originclient.org', '9.9.9.9')).counted, false);
  assert.deepEqual(await count(), { count: 0 });
});

test('the day salt is random and goes with the cleanup', async () => {
  await click('https://originclient.org', '1.2.3.4');
  const { day, salt } = env.DB.raw.prepare('SELECT day, salt FROM download_salt').get();
  assert.match(salt, /^[0-9a-f]{32}$/);
  await downloadCleanup(env, (day + 1) * 86_400_000);
  assert.equal(env.DB.raw.prepare('SELECT COUNT(*) AS n FROM download_salt').get().n, 0);
});

test('answers CORS so the site can read it', async () => {
  const r = await handle(new Request('https://api.test/v1/downloads'), env);
  assert.equal(r.headers.get('Access-Control-Allow-Origin'), '*');
});
