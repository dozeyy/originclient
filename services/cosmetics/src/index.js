// Origin cosmetics service: a Cloudflare Worker with a D1 (SQLite) database.
//
// Jobs:
//  1. Store payments (stripe.js, contract in docs/STORE.md): the store page
//     opens a Stripe Checkout for the typed Minecraft name; Stripe's webhook
//     then records ownership by the player's permanent Minecraft UUID, and
//     takes it back on a full refund or chargeback. Origin+ memberships
//     (Stripe subscriptions) the same way, tracked in the plus table.
//  2. API for the Origin mod:
//       GET  /v1/players/<uuid>      what a player owns + is wearing, and
//                                    whether they're Origin+ (`plus`)
//       POST /v1/players/equipped    { uuids: [...] } -> who's wearing what,
//                                    and which of them are Origin+
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

import { CORS, json, fail, normUuid, slotOf, SLOTS } from './common.js';
import { CATALOG } from './catalog.gen.js';
import { handleSocial, socialCleanup, presenceSweep, session } from './social.js';
import { keysDiag } from './mojang.js';
import { catalog, checkout, plusCheckout, webhook, isMember, membersAmong, PLUS_CAPE } from './stripe.js';

export { normUuid, slotOf };

const MAX_BATCH = 100;                   // players per /equipped lookup

// --------------------------------------------------------------------- API
// Origin+ members also own the members-only items (the cape; never sold)
// while they're members. They stay saved as worn when a membership ends, but
// nobody sees them until they're a member again (looks()).
const PLUS_ITEMS = [...new Set([PLUS_CAPE, ...Object.keys(CATALOG).filter(id => CATALOG[id].plusOnly)])];
async function ownedBy(env, uuid, member) {
  const rows = (await env.DB.prepare('SELECT item FROM owned WHERE uuid = ? ORDER BY at').bind(uuid).all()).results.map(r => r.item);
  return member ? [...rows, ...PLUS_ITEMS.filter(id => !rows.includes(id))] : rows;
}
const looks = (row, member) => Object.fromEntries(SLOTS.map(slot => {
  const item = row?.[slot] ?? null;
  return [slot, item && (member || !PLUS_ITEMS.includes(item)) ? item : null];
}));
const COLS = SLOTS.join(', ');

async function playerView(env, uuid) {
  const member = await isMember(env, uuid);
  const eq = await env.DB.prepare(`SELECT ${COLS} FROM equipped WHERE uuid = ?`).bind(uuid).first();
  return { uuid, owned: await ownedBy(env, uuid, member), equipped: looks(eq, member), plus: member };
}

async function equippedBatch(env, body) {
  const uuids = [...new Set((Array.isArray(body?.uuids) ? body.uuids : []).map(normUuid).filter(Boolean))].slice(0, MAX_BATCH);
  const out = {};
  if (!uuids.length) return json({ players: out });
  const rows = await env.DB.prepare(`SELECT uuid, ${COLS} FROM equipped WHERE uuid IN (${uuids.map(() => '?').join(',')})`)
    .bind(...uuids).all();
  const members = await membersAmong(env, uuids);
  // Members are listed even wearing nothing, so the game can show their Origin+ badge.
  for (const r of rows.results) {
    const look = looks(r, members.has(r.uuid));
    if (SLOTS.some(slot => look[slot])) out[r.uuid] = { ...look, plus: members.has(r.uuid) };
  }
  for (const u of members) out[u] ??= { ...looks(null, true), plus: true };
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

  const owned = new Set(await ownedBy(env, uuid, await isMember(env, uuid)));
  // A slot the request leaves out stays as it is (a mod from before a slot
  // existed must not take it off); null takes it off.
  const worn = await env.DB.prepare(`SELECT ${COLS} FROM equipped WHERE uuid = ?`).bind(uuid).first();
  const want = {};
  for (const slot of SLOTS) {
    const item = Object.hasOwn(body, slot) ? body[slot] ?? null : worn?.[slot] ?? null;
    if (Object.hasOwn(body, slot) && item !== null && (slotOf(item) !== slot || !owned.has(item))) {
      return fail(403, `you don't own ${item}`);
    }
    want[slot] = item;
  }
  await env.DB.prepare(`INSERT OR REPLACE INTO equipped (uuid, ${COLS}, at) VALUES (?, ${SLOTS.map(() => '?').join(', ')}, ?)`)
    .bind(uuid, ...SLOTS.map(slot => want[slot]), now).run();
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
    if (request.method === 'POST' && url.pathname === '/v1/players/equipped') return await equippedBatch(env, await request.json().catch(() => null));
    if (request.method === 'POST' && url.pathname === '/v1/equip') return await equip(request, env, await request.json().catch(() => null));
    if (request.method === 'GET' && url.pathname === '/v1/store/catalog') return await catalog(env);
    if (request.method === 'POST' && url.pathname === '/v1/store/checkout') return await checkout(env, await request.json().catch(() => null));
    if (request.method === 'POST' && url.pathname === '/v1/plus/checkout') return await plusCheckout(env, await request.json().catch(() => null));
    if (request.method === 'POST' && url.pathname === '/v1/stripe/webhook') return await webhook(request, env);
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
