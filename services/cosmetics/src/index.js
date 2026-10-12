// Origin cosmetics service: a Cloudflare Worker with a D1 (SQLite) database.
//
// Two jobs:
//  1. Delivery (cron, every minute). Every Tebex package carries a command
//     like `origin-grant <name> <uuid> cape-orbit` (and `origin-revoke ...`
//     for refunds/chargebacks) aimed at a stand-in game server called
//     "Origin Cosmetics". This Worker plays that server: it pulls the due
//     commands from Tebex's plugin API with the server's secret key, records
//     ownership by the player's permanent Minecraft UUID, then tells Tebex
//     they're done. Contract documented in docs/STORE.md.
//  2. API for the Origin mod:
//       GET  /v1/players/<uuid>      what a player owns + is wearing
//       POST /v1/players/equipped    { uuids: [...] } -> who's wearing what
//       POST /v1/equip               change what you wear; proven with
//            Minecraft's own server-join check (Mojang hasJoined), the same
//            way a Minecraft server verifies a player. No tokens reach us.
//
// Secrets/bindings (wrangler.toml + Cloudflare dashboard, never the repo):
//   DB            the D1 database (schema in migrations/)
//   TEBEX_SECRET  the "Origin Cosmetics" game server's secret key

const TEBEX = 'https://plugin.tebex.io';
const MOJANG_HAS_JOINED = 'https://sessionserver.mojang.com/session/minecraft/hasJoined';
const MIN_CHECK_SECONDS = 60;            // Tebex's next_check is obeyed, never undercut
const MAX_BATCH = 100;                   // players per /equipped lookup
const JOIN_TTL_MS = 24 * 3600 * 1000;    // how long a used serverId is remembered

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};

// ------------------------------------------------------------------- items
// Which slot an item id wears in. The id is the contract shared with the
// store (website/js/store.js ITEMS), the Tebex commands and the mod.
export function slotOf(item) {
  if (/^cape-[a-z0-9-]+$/.test(item)) return 'cape';
  if (/^(halo|crown|hat)-[a-z0-9-]+$/.test(item)) return 'head';
  return null;
}

// Minecraft UUIDs arrive dashed or not, any case; store one form.
export function normUuid(raw) {
  const u = String(raw || '').replace(/-/g, '').toLowerCase();
  return /^[0-9a-f]{32}$/.test(u) ? u : null;
}

// `origin-grant <name> <uuid> <item>` / `origin-revoke ...` after Tebex has
// filled in {username} and {id}. Returns null for anything else.
export function parseCommand(text) {
  const m = /^\s*origin-(grant|revoke)\s+(\S+)\s+(\S+)\s+([a-z0-9-]+)\s*$/.exec(String(text || ''));
  if (!m || !slotOf(m[4])) return null;
  return { action: m[1], name: m[2], uuid: normUuid(m[3]), item: m[4] };
}

// ---------------------------------------------------------------- delivery
async function tebex(env, method, path) {
  const r = await fetch(TEBEX + path, { method, headers: { 'X-Tebex-Secret': env.TEBEX_SECRET, Accept: 'application/json' } });
  if (!r.ok) throw new Error(`Tebex ${method} ${path} -> ${r.status}`);
  return r.status === 204 ? null : r.json();
}

// Applies one queued Tebex command. Returns true when it's handled (so it
// can be marked done), false to leave it queued: a command we can't read is
// never thrown away, it waits in Tebex until someone fixes the package.
export async function applyCommand(env, cmd, fallbackPlayer) {
  const parsed = parseCommand(cmd.command);
  const player = cmd.player || fallbackPlayer || {};
  const uuid = normUuid(player.uuid) || parsed?.uuid;
  if (!parsed || !uuid) {
    console.error('origin-cosmetics: unreadable command left in queue', cmd.id, cmd.command);
    return false;
  }
  if (parsed.action === 'grant') {
    await env.DB.prepare('INSERT OR IGNORE INTO owned (uuid, item, payment, at) VALUES (?, ?, ?, ?)')
      .bind(uuid, parsed.item, String(cmd.payment ?? ''), Date.now()).run();
  } else {
    const slot = slotOf(parsed.item);
    await env.DB.batch([
      env.DB.prepare('DELETE FROM owned WHERE uuid = ? AND item = ?').bind(uuid, parsed.item),
      // Taking an item away also takes it off.
      env.DB.prepare(`UPDATE equipped SET ${slot} = NULL WHERE uuid = ? AND ${slot} = ?`).bind(uuid, parsed.item),
    ]);
  }
  return true;
}

export async function collect(env, now = Date.now()) {
  const next = await env.DB.prepare("SELECT v FROM meta WHERE k = 'tebex_next_check'").first('v');
  if (next && now < Number(next)) return { skipped: true };

  const queue = await tebex(env, 'GET', '/queue');
  // Record Tebex's requested wait straight away, so even a run that fails
  // part-way never asks again sooner than allowed (Tebex revokes keys that do).
  const wait = Math.max(MIN_CHECK_SECONDS, Number(queue?.meta?.next_check) || 0);
  await env.DB.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('tebex_next_check', ?)").bind(String(now + wait * 1000)).run();

  const done = [];
  const offline = await tebex(env, 'GET', '/queue/offline-commands');
  for (const cmd of offline?.commands || []) if (await applyCommand(env, cmd)) done.push(cmd.id);
  // Commands that were (wrongly) set to wait for the player to be online
  // still get delivered: Origin has no server for them to be online on.
  for (const p of queue?.players || []) {
    const online = await tebex(env, 'GET', `/queue/online-commands/${encodeURIComponent(p.id)}`);
    for (const cmd of online?.commands || []) if (await applyCommand(env, cmd, online.player || p)) done.push(cmd.id);
  }
  if (done.length) await tebex(env, 'DELETE', `/queue?${done.map(id => `ids[]=${encodeURIComponent(id)}`).join('&')}`);

  // Grants and revokes are safe to repeat, so a run that dies before this
  // DELETE just re-applies the same commands next time.
  await env.DB.prepare('DELETE FROM joins WHERE at < ?').bind(now - JOIN_TTL_MS).run();
  return { delivered: done.length };
}

// --------------------------------------------------------------------- API
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...CORS },
});
const fail = (status, error) => json({ error }, status);

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

// The mod picks a random serverId, tells Mojang "I'm joining serverId" with
// the player's own session (client side), then sends us name + serverId.
// Mojang confirms which account did that. Each serverId works once.
async function equip(env, body, now = Date.now()) {
  const name = String(body?.name || '');
  const serverId = String(body?.serverId || '');
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || !/^[0-9a-f]{16,64}$/.test(serverId)) return fail(400, 'bad request');

  const r = await fetch(`${MOJANG_HAS_JOINED}?username=${encodeURIComponent(name)}&serverId=${encodeURIComponent(serverId)}`);
  if (r.status !== 200) return fail(401, 'could not verify this Minecraft account');
  const uuid = normUuid((await r.json())?.id);
  if (!uuid) return fail(401, 'could not verify this Minecraft account');

  const fresh = await env.DB.prepare('INSERT OR IGNORE INTO joins (server_id, at) VALUES (?, ?)').bind(serverId, now).run();
  if (!fresh.meta?.changes) return fail(401, 'this check was already used');

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
    if (request.method === 'POST' && url.pathname === '/v1/equip') return equip(env, await request.json().catch(() => null));
    if (request.method === 'GET' && url.pathname === '/') return json({ service: 'origin-cosmetics', ok: true });
    return fail(404, 'not found');
  } catch (err) {
    console.error('origin-cosmetics:', err);
    return fail(500, 'something went wrong');
  }
}

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (event, env, ctx) => ctx.waitUntil(collect(env).catch(err => console.error('origin-cosmetics: delivery failed', err))),
};
