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
//       POST /v1/players/equipped    { uuids: [...] } -> who's wearing what
//                                    (+ their height tweaks), which of them
//                                    are Origin+, and which are in game on
//                                    Origin right now (`origin`, the name badge)
//       POST /v1/equip               change what you wear; `Authorization:
//            Bearer <token>` from Origin sign-in (/v1/auth, identity v2:
//            Mojang-signed proofs checked offline, mojang.js). No Microsoft
//            or Mojang token ever reaches us.
//  3. Free store codes Will makes (codes.js): a bag of cosmetics, or Origin+
//     for good, with no card.
//  4. Origin social (friends, chat, parties, players online): social.js and
//     party.js, contract in docs/SOCIAL.md. Its upkeep runs on the cron.
//
// Secrets/bindings (wrangler.toml + Cloudflare dashboard, never the repo):
//   DB                     the D1 database (schema in migrations/; owned.payment
//                          holds the Stripe payment id)
//   STRIPE_SECRET_KEY      creates Checkout pages
//   STRIPE_WEBHOOK_SECRET  checks that webhook calls really come from Stripe

import { CORS, json, fail, normUuid, slotOf, SLOTS, rowsIn } from './common.js';
import { CATALOG } from './catalog.gen.js';
import { L } from './social-shared.js';
import { handleSocial, socialCleanup, presenceSweep, session, socialBudget } from './social.js';
import { keysDiag } from './mojang.js';
import { catalog, checkout, plusCheckout, webhook, isMember, membersAmong, PLUS_CAPE } from './stripe.js';
import { makeCodes, redeem } from './codes.js';

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

// Position tweaks (FORMAT.md): how far each worn slot sits from its default spot, model px
// in 0.5 steps, at most ADJUST_MAX either way. Every slot moves up (+) / down (-) under its
// own name; head and face items also sideways (headX / faceX, + = the player's left) and
// forward (headZ / faceZ, + = toward the face). Theme has none.
const ADJUST_KEYS = ['cape', 'head', 'face', 'back', 'headX', 'headZ', 'faceX', 'faceZ'];
const ADJUST_MAX = 4;
function cleanAdjust(raw) {
  const out = {};
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out;
  for (const key of ADJUST_KEYS) {
    const v = Number(raw[key]);
    if (!Number.isFinite(v)) continue;
    const r = Math.round(Math.max(-ADJUST_MAX, Math.min(ADJUST_MAX, v)) * 2) / 2;
    if (r !== 0) out[key] = r;
  }
  return out;
}
const adjustOf = row => { try { return cleanAdjust(JSON.parse(row?.adjust ?? 'null')); } catch { return {}; } };

// "Using Origin" = the game wrote presence lately (it does every minute while
// running; social.js). Drives the Origin badge by every Origin player's name.
// This route is public (no sign-in), so anyone could poll it to see when a
// player is playing: a player who turned on Appear offline is never reported
// (docs/SOCIAL.md: invisible = no activity anywhere). A block can't be honoured
// here (the caller is anonymous); Appear offline is the shield against anyone.
const ORIGIN_RECENT_MS = 10 * 60 * 1000;
async function inGameAmong(env, uuids, now) {
  if (!uuids.length) return new Set();
  const rows = await rowsIn(uuids, part => env.DB.prepare(`SELECT DISTINCT p.uuid FROM presence p
      WHERE p.client = 'game' AND p.at > ? AND p.uuid IN (${part.map(() => '?').join(',')})
        AND NOT EXISTS (SELECT 1 FROM settings s WHERE s.uuid = p.uuid AND s.appear_offline = 1)`)
    .bind(now - ORIGIN_RECENT_MS, ...part).all());
  return new Set(rows.map(r => r.uuid));
}

async function playerView(env, uuid) {
  const member = await isMember(env, uuid);
  const eq = await env.DB.prepare(`SELECT ${COLS}, adjust FROM equipped WHERE uuid = ?`).bind(uuid).first();
  return { uuid, owned: await ownedBy(env, uuid, member), equipped: looks(eq, member), adjust: adjustOf(eq), plus: member };
}

async function equippedBatch(env, body, now = Date.now()) {
  const uuids = [...new Set((Array.isArray(body?.uuids) ? body.uuids : []).map(normUuid).filter(Boolean))].slice(0, MAX_BATCH);
  const out = {};
  if (!uuids.length) return json({ players: out });
  const rows = await rowsIn(uuids, part => env.DB.prepare(`SELECT uuid, ${COLS}, adjust FROM equipped WHERE uuid IN (${part.map(() => '?').join(',')})`)
    .bind(...part).all());
  const members = await membersAmong(env, uuids);
  const inGame = await inGameAmong(env, uuids, now);
  const entry = (uuid, look, adjust) => {
    const e = { ...look, plus: members.has(uuid), origin: inGame.has(uuid) };
    if (Object.keys(adjust).length) e.adjust = adjust;
    return e;
  };
  // Members and players in game on Origin are listed even wearing nothing, so the
  // game can show their badge.
  for (const r of rows) {
    const look = looks(r, members.has(r.uuid));
    if (SLOTS.some(slot => look[slot])) out[r.uuid] = entry(r.uuid, look, adjustOf(r));
  }
  for (const u of [...members, ...inGame]) out[u] ??= entry(u, looks(null, members.has(u)), {});
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
  const worn = await env.DB.prepare(`SELECT ${COLS}, adjust FROM equipped WHERE uuid = ?`).bind(uuid).first();
  const want = {};
  for (const slot of SLOTS) {
    const item = Object.hasOwn(body, slot) ? body[slot] ?? null : worn?.[slot] ?? null;
    if (Object.hasOwn(body, slot) && item !== null && (slotOf(item) !== slot || !owned.has(item))) {
      return fail(403, `you don't own ${item}`);
    }
    want[slot] = item;
  }
  // Height tweaks: sent = replaced, left out = kept (mods from before them don't send it).
  const adjust = Object.hasOwn(body, 'adjust') ? cleanAdjust(body.adjust) : adjustOf(worn);
  await env.DB.prepare(`INSERT OR REPLACE INTO equipped (uuid, ${COLS}, adjust, at) VALUES (?, ${SLOTS.map(() => '?').join(', ')}, ?, ?)`)
    .bind(uuid, ...SLOTS.map(slot => want[slot]), Object.keys(adjust).length ? JSON.stringify(adjust) : null, now).run();
  return json(await playerView(env, uuid));
}

// ------------------------------------------------------------ rate limits
// The routes anyone can call without an Origin sign-in, limited per caller IP
// so one script looping them can't use up the free plan's D1 reads for the day
// or Stripe's API limit and take the store and Friends down for everyone.
// Cloudflare's own rate limiter (wrangler.toml [[ratelimits]], counted at each
// Cloudflare location, no D1 writes); docs/SOCIAL.md "Rate limits". Signed-in
// social calls have their own per-account limits (social-shared.js allow()).
// The Stripe webhook is never limited (it is not in LIMITED, and a test keeps it
// that way), and the day's request quota keeps a reserve for the store and the
// webhook: Friends polling slows itself down first (social.js socialBudget,
// docs/SOCIAL.md "Store reserve"). A missing binding (tests, a local run)
// or a limiter error lets the call through: the limiter must never break the store.
//   LIMIT_STORE   10 a minute: checkouts, Origin+ checkouts, code redeems, admin codes
//   LIMIT_LOOKUP 120 a minute: who wears what (a game asks in batches, cached 5 min)
//   LIMIT_AUTH    20 a minute: Origin sign-in (a client signs in about once a day)
const LIMITED = {
  'POST /v1/store/checkout': 'LIMIT_STORE',
  'POST /v1/plus/checkout': 'LIMIT_STORE',
  'POST /v1/store/redeem': 'LIMIT_STORE',
  'POST /v1/admin/codes': 'LIMIT_STORE',
  'GET /v1/players/*': 'LIMIT_LOOKUP',
  'POST /v1/players/equipped': 'LIMIT_LOOKUP',
  'POST /v1/auth': 'LIMIT_AUTH',
};
async function overLimit(request, env, parts) {
  const route = request.method === 'GET' && parts.length === 3 && parts[0] === 'v1' && parts[1] === 'players' ? 'v1/players/*' : parts.join('/');
  const limiter = env[LIMITED[`${request.method} /${route}`]];
  if (!limiter) return false;
  try {
    const { success } = await limiter.limit({ key: request.headers.get('CF-Connecting-IP') || 'unknown' });
    return !success;
  } catch (err) {
    console.error('origin-cosmetics: rate limiter failed, letting the call through', err);
    return false;
  }
}

export async function handle(request, env) {
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
  const url = new URL(request.url);
  const parts = url.pathname.split('/').filter(Boolean);
  try {
    if (await overLimit(request, env, parts)) {
      const r = fail(429, 'Too many tries from your network. Wait a minute and try again.');
      r.headers.set('Retry-After', '60');
      return r;
    }
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
    if (request.method === 'POST' && url.pathname === '/v1/store/redeem') return await redeem(env, await request.json().catch(() => null));
    if (request.method === 'POST' && url.pathname === '/v1/admin/codes') return await makeCodes(request, env, await request.json().catch(() => null));
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
// ignores expired rows, and the store reserve (Friends' share of the day's
// requests, social.js socialBudget) every 5th. They fail independently.
export async function scheduled(event, env, now = Date.now()) {
  const t = event?.scheduledTime ?? now;
  const minute = Math.floor(t / 60_000);
  await Promise.all([
    presenceSweep(env, minute * 60_000).catch(err => console.error('origin-cosmetics: offline sweep failed', err)),
    minute % 10 === 0 ? socialCleanup(env, now).catch(err => console.error('origin-cosmetics: social cleanup failed', err)) : null,
    minute % L.BUDGET_EVERY_MIN === 0 ? socialBudget(env, t).catch(err => console.error('origin-cosmetics: store reserve failed', err)) : null,
  ]);
}

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (event, env, ctx) => ctx.waitUntil(scheduled(event, env)),
};
