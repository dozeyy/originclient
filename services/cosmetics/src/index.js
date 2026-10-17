// Origin cosmetics service: a Cloudflare Worker with a D1 (SQLite) database.
//
// Jobs:
//  1. Store payments (stripe.js, contract in docs/STORE.md): the store page
//     opens a Stripe Checkout for the typed Minecraft name; Stripe's webhook
//     then records ownership by the player's permanent Minecraft UUID, and
//     takes it back on a full refund or chargeback.
//  2. API for the Origin mod:
//       GET  /v1/players/<uuid>      what a player owns + is wearing
//       POST /v1/players/equipped    { uuids: [...] } -> who's wearing what
//       POST /v1/equip               change what you wear; `Authorization:
//            Bearer <token>` from Origin sign-in (/v1/auth, identity v2:
//            Mojang-signed proofs checked offline, mojang.js). No Microsoft
//            or Mojang token ever reaches us.
//  3. Origin social (friends, chat, parties, players online): social.js and
//     party.js, contract in docs/SOCIAL.md. Its upkeep runs on the cron.
//
// Secrets/bindings (wrangler.toml + Cloudflare dashboard, never the repo):
//   DB                     the D1 database (schema in migrations/; owned.payment
//                          holds the Stripe payment id)
//   STRIPE_SECRET_KEY      creates Checkout pages
//   STRIPE_WEBHOOK_SECRET  checks that webhook calls really come from Stripe

import { CORS, json, fail, normUuid, slotOf } from './common.js';
import { handleSocial, socialCleanup, presenceSweep, session } from './social.js';
import { keysDiag } from './mojang.js';
import { catalog, checkout, webhook } from './stripe.js';

export { normUuid, slotOf };

const MAX_BATCH = 100;                   // players per /equipped lookup

// --------------------------------------------------------------------- API
async function playerView(env, uuid) {
  const owned = await env.DB.prepare('SELECT item FROM owned WHERE uuid = ? ORDER BY at').bind(uuid).all();
  const eq = await env.DB.prepare('SELECT cape, head FROM equipped WHERE uuid = ?').bind(uuid).first();
  return { uuid, owned: owned.results.map(r => r.item), equipped: { cape: eq?.cape ?? null, head: eq?.head ?? null } };
}

async function equippedBatch(env, body) {
  const uuids = [...new Set((Array.isArray(body?.uuids) ? body.uuids : []).map(normUuid).filter(Boolean))].slice(0, MAX_BATCH);
  const out = {};
  if (!uuids.length) return json({ players: out });
  const rows = await env.DB.prepare(`SELECT uuid, cape, head FROM equipped WHERE uuid IN (${uuids.map(() => '?').join(',')})`)
    .bind(...uuids).all();
  for (const r of rows.results) if (r.cape || r.head) out[r.uuid] = { cape: r.cape ?? null, head: r.head ?? null };
  return json({ players: out });
}

// The same Origin sign-in as the social calls: `Authorization: Bearer
// <token>`. Mods from before identity v2 send { name, serverId } (a Mojang
// check the Worker can no longer make) and are told to update.
async function equip(request, env, body, now = Date.now()) {
  if (!body || typeof body !== 'object' || Array.isArray(body) || 'serverId' in body) {
    return fail(400, 'Please update Origin to change cosmetics.');
  }
  const me = await session(request, env, now);
  if (!me) return fail(401, 'Your Origin sign-in ran out. Signing you in again...');
  const uuid = me.uuid;

  const owned = new Set((await env.DB.prepare('SELECT item FROM owned WHERE uuid = ?').bind(uuid).all()).results.map(x => x.item));
  const want = {};
  for (const slot of ['cape', 'head']) {
    const item = body[slot] ?? null;
    if (item !== null && (slotOf(item) !== slot || !owned.has(item))) return fail(403, `you don't own ${item}`);
    want[slot] = item;
  }
  await env.DB.prepare('INSERT OR REPLACE INTO equipped (uuid, cape, head, at) VALUES (?, ?, ?, ?)')
    .bind(uuid, want.cape, want.head, now).run();
  return json(await playerView(env, uuid));
}

export async function handle(request, env) {
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
  const url = new URL(request.url);
  const parts = url.pathname.split('/').filter(Boolean);
  try {
    if (request.method === 'GET' && parts[0] === 'v1' && parts[1] === 'players' && parts.length === 3) {
      const uuid = normUuid(parts[2]);
      return uuid ? json(await playerView(env, uuid)) : fail(400, 'bad uuid');
    }
    if (request.method === 'POST' && url.pathname === '/v1/players/equipped') return equippedBatch(env, await request.json().catch(() => null));
    if (request.method === 'POST' && url.pathname === '/v1/equip') return equip(request, env, await request.json().catch(() => null));
    if (request.method === 'GET' && url.pathname === '/v1/store/catalog') return catalog(env);
    if (request.method === 'POST' && url.pathname === '/v1/store/checkout') return checkout(env, await request.json().catch(() => null));
    if (request.method === 'POST' && url.pathname === '/v1/stripe/webhook') return webhook(request, env);
    if (request.method === 'GET' && url.pathname === '/') return json({ service: 'origin-cosmetics', ok: true });
    // How many of Mojang's keys the service holds (counts only), checked after every deploy.
    if (request.method === 'GET' && url.pathname === '/v1/diag/keys') return json(await keysDiag(env));
    const social = await handleSocial(request, env, url);
    if (social) return social;
    return fail(404, 'not found');
  } catch (err) {
    console.error('origin-cosmetics:', err);
    return fail(500, 'something went wrong');
  }
}

// Cron, every minute: the friends "who went offline" sweep every time,
// social cleanup every 10th minute, which is plenty since every read already
// ignores expired rows. They fail independently.
export async function scheduled(event, env, now = Date.now()) {
  const t = event?.scheduledTime ?? now;
  const minute = Math.floor(t / 60_000);
  await Promise.all([
    presenceSweep(env, minute * 60_000).catch(err => console.error('origin-cosmetics: offline sweep failed', err)),
    minute % 10 === 0 ? socialCleanup(env, now).catch(err => console.error('origin-cosmetics: social cleanup failed', err)) : null,
  ]);
}

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (event, env, ctx) => ctx.waitUntil(scheduled(event, env)),
};
