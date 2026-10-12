// Origin social: sign-in, the one polling call (/v1/sync), friends, DMs,
// settings, players online, and the cron cleanup. Parties are in party.js.
// Contract: docs/SOCIAL.md (its "Service notes" section lists the exact
// limits, status codes and cursor rules implemented here).
//
// Cloudflare's free plan has no WebSockets, so clients poll /v1/sync. That
// call does all of its reads and writes in ONE D1 batch (one round trip, one
// consistent snapshot) and writes as little as it can: presence at most every
// 30 s per (account, client), a party position only when it moved.

import { json, fail, normUuid, NAME_RE, SERVER_ID_RE, verifyMojangJoin } from './common.js';
import {
  L, MSG, CLIENTS, ACTIVITIES, randomHex, sha256Hex, cleanText, normServer, normVersion, normDim, coord, num,
  allow, areFriends, addChat, partyOut, MEMBER_COLUMNS,
} from './social-shared.js';
import { PARTY_ROUTES, leaveParty } from './party.js';

const MOJANG_PROFILE = 'https://api.mojang.com/users/profiles/minecraft/';

// ------------------------------------------------------------------ sign-in
// Same Mojang proof as /v1/equip. The token is shown once; only its SHA-256
// is stored, and it lasts 24 h. The account's current name is remembered:
// a row in `names` is what "on Origin" means.
async function signIn(env, body, now) {
  const name = String(body.name || ''), serverId = String(body.serverId || ''), client = body.client;
  if (!NAME_RE.test(name) || !SERVER_ID_RE.test(serverId) || !CLIENTS.has(client)) {
    return fail(400, "Couldn't sign in to Origin. Please update Origin and try again.");
  }
  const who = await verifyMojangJoin(env, name, serverId, now);
  if (who.error === 'reused') return fail(401, 'That sign-in check was already used. Please try again.');
  if (who.error) return fail(401, "Couldn't confirm your Minecraft account. Sign in with Microsoft to use friends.");

  const token = randomHex(32);
  const expiresAt = now + L.TOKEN_TTL_MS;
  await env.DB.batch([
    env.DB.prepare('INSERT INTO sessions (token_hash, uuid, client, created, expires) VALUES (?, ?, ?, ?, ?)')
      .bind(await sha256Hex(token), who.uuid, client, now, expiresAt),
    env.DB.prepare(`INSERT INTO names (uuid, name, name_lower, updated) VALUES (?1, ?2, ?3, ?4)
        ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, updated = excluded.updated`)
      .bind(who.uuid, who.name, who.name.toLowerCase(), now),
  ]);
  return json({ token, uuid: who.uuid, name: who.name, expiresAt });
}

// `Authorization: Bearer <token>` -> { uuid, name, client } or null.
async function session(request, env, now) {
  const m = /^Bearer\s+([0-9a-f]{64})$/i.exec(request.headers.get('Authorization') || '');
  if (!m) return null;
  const row = await env.DB.prepare(`SELECT s.uuid, s.client, n.name FROM sessions s
      LEFT JOIN names n ON n.uuid = s.uuid WHERE s.token_hash = ? AND s.expires > ?`)
    .bind(await sha256Hex(m[1].toLowerCase()), now).first();
  return row ? { uuid: row.uuid, client: row.client, name: row.name ?? '' } : null;
}

// ----------------------------------------------------------- players online
// Distinct accounts seen by launcher or game in the last 5 minutes, recounted
// at most once a minute: the count lives in `meta` (shared by every Worker
// machine) and in memory per machine. Cloudflare's Cache API would avoid the
// meta write, but it does nothing on *.workers.dev, where this service runs.
const onlineMemo = new WeakMap();   // keyed by the DB binding

export async function onlineCount(env, now = Date.now()) {
  const fresh = c => c && now - c.at < L.STATS_CACHE_MS;
  let c = onlineMemo.get(env.DB);
  if (fresh(c)) return c;
  try { c = JSON.parse(await env.DB.prepare("SELECT v FROM meta WHERE k = 'social_online'").first('v')); } catch { c = null; }
  if (!fresh(c)) {
    const n = await env.DB.prepare('SELECT COUNT(DISTINCT uuid) AS n FROM presence WHERE at > ?')
      .bind(now - L.STATS_WINDOW_MS).first('n');
    c = { online: Number(n) || 0, at: now };
    await env.DB.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('social_online', ?)").bind(JSON.stringify(c)).run();
  }
  onlineMemo.set(env.DB, c);
  return c;
}

// --------------------------------------------------------------------- sync
function readPresence(raw, fallbackClient) {
  if (!raw || typeof raw !== 'object') return null;
  const activity = ACTIVITIES.has(raw.activity) ? raw.activity : 'menu';
  return {
    client: CLIENTS.has(raw.client) ? raw.client : fallbackClient,
    activity,
    server: activity === 'server' ? normServer(raw.server) : null,
    version: normVersion(raw.version),
  };
}

// The game's own position for the party HUD. A malformed one is ignored, never
// an error: sync must keep working whatever a client sends.
function readPartyState(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const s = {
    server: normServer(raw.server), dim: normDim(raw.dim),
    x: coord(raw.x), y: coord(raw.y), z: coord(raw.z),
    health: num(raw.health, 1e6, 2), maxHealth: num(raw.maxHealth, 1e6, 2),   // half hearts
  };
  return Object.values(s).some(v => v === null) ? null : s;
}

// DMs to or from `uuid` with id > since: oldest first (dir ASC) for a normal
// sync, newest first (dir DESC, reversed later) for the first one. Two index
// ranges (chat_to, chat_from) instead of one OR, so it reads only what it returns.
// `limit` is one of our constants, written into the SQL as an integer.
function dmQuery(env, uuid, since, dir, limit) {
  const branch = col => `SELECT * FROM (SELECT id, from_uuid, to_uuid, text, at FROM chat
      WHERE ${col} = ?1 AND party_id IS NULL AND id > ?2 ORDER BY id ${dir} LIMIT ${limit | 0})`;
  return env.DB.prepare(`SELECT * FROM (${branch('to_uuid')} UNION ALL ${branch('from_uuid')}) ORDER BY id ${dir} LIMIT ${limit | 0}`)
    .bind(uuid, since);
}

function partyChatQuery(env, uuid, since, dir, limit) {
  return env.DB.prepare(`SELECT c.id, c.from_uuid, n.name, c.text, c.at FROM party_members me
      JOIN chat c ON c.party_id = me.party_id AND c.id > me.chat_from AND c.id > ?2
      LEFT JOIN names n ON n.uuid = c.from_uuid
      WHERE me.uuid = ?1 ORDER BY c.id ${dir} LIMIT ${limit | 0}`).bind(uuid, since);
}

// One friend as the friends list shows them. `rows` = their presence rows
// (0-2: launcher and/or game). The game wins over the launcher when both are on.
function friendView(f, now) {
  const seen = f.rows.length ? Math.max(...f.rows.map(r => r.at)) : null;
  if (f.appearOffline) {
    // Invisible: offline to friends, and "last seen" frozen at when they went invisible.
    const lastSeen = seen !== null && f.offlineSince ? Math.min(seen, f.offlineSince) : seen;
    return { uuid: f.uuid, name: f.name, online: false, client: null, activity: null, server: null, version: null, lastSeen };
  }
  const live = f.rows.filter(r => r.at > now - L.ONLINE_MS)
    .sort((a, b) => (a.client === 'game' ? 0 : 1) - (b.client === 'game' ? 0 : 1) || b.at - a.at)[0];
  return {
    uuid: f.uuid, name: f.name, online: !!live,
    client: live?.client ?? null, activity: live?.activity ?? null,
    server: live?.activity === 'server' ? live.server : null,
    version: live?.version ?? null, lastSeen: seen,
  };
}

async function sync(env, me, body, now) {
  const sinceRaw = Number(body.since);
  const since = Number.isSafeInteger(sinceRaw) && sinceRaw > 0 ? sinceRaw : 0;
  const first = since === 0;
  const pres = readPresence(body.presence, me.client);
  const state = readPartyState(body.partyState);
  // Where the caller is right now: party positions and pings are only shared
  // between members on the same server.
  const myServer = state?.server ?? (pres?.activity === 'server' ? pres.server : null);

  const keys = [], stmts = [];
  const add = (key, stmt) => { keys.push(key); stmts.push(stmt); };

  // Writes first, so the reads below already see them.
  if (pres) {
    add('presence', env.DB.prepare(`INSERT INTO presence (uuid, client, activity, server, version, at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        ON CONFLICT (uuid, client) DO UPDATE SET activity = excluded.activity, server = excluded.server,
          version = excluded.version, at = excluded.at
        WHERE presence.at <= excluded.at - ${L.PRESENCE_WRITE_MS}
           OR ((presence.activity IS NOT excluded.activity OR presence.server IS NOT excluded.server
                OR presence.version IS NOT excluded.version) AND presence.at <= excluded.at - ${L.PRESENCE_CHANGE_MS})`)
      .bind(me.uuid, pres.client, pres.activity, pres.server, pres.version, now));
  }
  if (state) {
    add('state', env.DB.prepare(`UPDATE party_members SET state = ?2, state_server = ?3, state_at = ?4
        WHERE uuid = ?1 AND (state_at IS NULL OR state_at <= ?4 - ${L.STATE_WRITE_MS}
          OR state_server IS NOT ?3 OR (state IS NOT ?2 AND state_at <= ?4 - ${L.STATE_CHANGE_MS}))`)
      .bind(me.uuid, JSON.stringify(state), state.server, now));
  }

  add('maxId', env.DB.prepare('SELECT COALESCE(MAX(id), 0) AS id FROM chat'));
  add('settings', env.DB.prepare('SELECT appear_offline FROM settings WHERE uuid = ?').bind(me.uuid));
  add('friends', env.DB.prepare(`SELECT f.b AS uuid, n.name, s.appear_offline, s.offline_since,
        p.client, p.activity, p.server, p.version, p.at
      FROM friends f LEFT JOIN names n ON n.uuid = f.b LEFT JOIN settings s ON s.uuid = f.b
      LEFT JOIN presence p ON p.uuid = f.b WHERE f.a = ?`).bind(me.uuid));
  add('incoming', env.DB.prepare(`SELECT r.from_uuid AS uuid, n.name, r.at FROM friend_requests r
      LEFT JOIN names n ON n.uuid = r.from_uuid WHERE r.to_uuid = ? ORDER BY r.at`).bind(me.uuid));
  add('outgoing', env.DB.prepare(`SELECT r.to_uuid AS uuid, n.name, r.at FROM friend_requests r
      LEFT JOIN names n ON n.uuid = r.to_uuid WHERE r.from_uuid = ? ORDER BY r.at`).bind(me.uuid));
  add('blocked', env.DB.prepare(`SELECT b.blocked AS uuid, n.name FROM blocks b
      LEFT JOIN names n ON n.uuid = b.blocked WHERE b.blocker = ? ORDER BY b.at`).bind(me.uuid));
  add('dms', dmQuery(env, me.uuid, since, first ? 'DESC' : 'ASC', first ? L.DM_HISTORY : L.DM_PAGE + 1));
  add('party', env.DB.prepare(`SELECT p.id, p.leader FROM party_members me JOIN parties p ON p.id = me.party_id
      WHERE me.uuid = ?`).bind(me.uuid));
  add('members', env.DB.prepare(`SELECT ${MEMBER_COLUMNS} FROM party_members me
      JOIN party_members m ON m.party_id = me.party_id
      LEFT JOIN names n ON n.uuid = m.uuid LEFT JOIN settings s ON s.uuid = m.uuid
      WHERE me.uuid = ? ORDER BY m.joined, m.uuid`).bind(me.uuid));
  add('partyChat', partyChatQuery(env, me.uuid, since, first ? 'DESC' : 'ASC', first ? L.PARTY_HISTORY : L.PARTY_PAGE + 1));
  add('pings', env.DB.prepare(`SELECT g.id, g.from_uuid, n.name, g.server, g.dim, g.x, g.y, g.z, g.label, g.at, g.expires
      FROM party_members me JOIN pings g ON g.party_id = me.party_id AND g.expires > ?2 AND g.server = ?3
      LEFT JOIN names n ON n.uuid = g.from_uuid WHERE me.uuid = ?1 ORDER BY g.id`).bind(me.uuid, now, myServer));
  add('invites', env.DB.prepare(`SELECT i.party_id, i.from_uuid, n.name, i.at FROM party_invites i
      LEFT JOIN names n ON n.uuid = i.from_uuid
      WHERE i.to_uuid = ?1 AND i.at > ?2
        AND i.party_id IS NOT (SELECT party_id FROM party_members WHERE uuid = ?1) ORDER BY i.at`)
    .bind(me.uuid, now - L.INVITE_TTL_MS));
  add('warp', env.DB.prepare(`SELECT w.server, w.version, w.at, w.from_uuid, n.name FROM party_members me
      JOIN warps w ON w.party_id = me.party_id LEFT JOIN names n ON n.uuid = w.from_uuid
      WHERE me.uuid = ?1 AND w.at > ?2 AND w.from_uuid != ?1`).bind(me.uuid, now - L.WARP_TTL_MS));

  const res = {};
  (await env.DB.batch(stmts)).forEach((r, i) => { res[keys[i]] = r.results || []; });

  // ---- chat + cursor. The cursor is the newest chat id this answer covers.
  // First sync: the latest history, cursor = newest id overall. Later syncs:
  // everything after `since`; if a page overflows, the cursor stops at the
  // last line returned and the rest comes next time.
  const maxId = Number(res.maxId[0]?.id) || 0;
  let dms = res.dms, pcs = res.partyChat, cursor = maxId;
  if (first) {
    dms = [...dms].reverse();
    pcs = [...pcs].reverse();
  } else {
    if (dms.length > L.DM_PAGE) { dms = dms.slice(0, L.DM_PAGE); cursor = Math.min(cursor, dms.at(-1).id); }
    if (pcs.length > L.PARTY_PAGE) { pcs = pcs.slice(0, L.PARTY_PAGE); cursor = Math.min(cursor, pcs.at(-1).id); }
    dms = dms.filter(m => m.id <= cursor);
    pcs = pcs.filter(m => m.id <= cursor);
  }

  // ---- friends: full list, online first, then by name.
  const byUuid = new Map();
  for (const r of res.friends) {
    let f = byUuid.get(r.uuid);
    if (!f) byUuid.set(r.uuid, f = { uuid: r.uuid, name: r.name ?? '', appearOffline: !!r.appear_offline, offlineSince: r.offline_since, rows: [] });
    if (r.client) f.rows.push(r);
  }
  const friends = [...byUuid.values()].map(f => friendView(f, now))
    .sort((a, b) => (b.online - a.online) || a.name.toLowerCase().localeCompare(b.name.toLowerCase()));

  const party = res.party[0];
  const warp = res.warp[0];
  return json({
    cursor,
    me: { uuid: me.uuid, name: me.name, appearOffline: !!res.settings[0]?.appear_offline },
    friends,
    requests: { incoming: res.incoming, outgoing: res.outgoing },
    blocked: res.blocked,
    messages: dms.map(m => ({ id: m.id, from: m.from_uuid, to: m.to_uuid, text: m.text, at: m.at })),
    party: party ? partyOut(party.id, party.leader, res.members, now, myServer) : null,
    partyMessages: pcs.map(m => ({ id: m.id, from: m.from_uuid, name: m.name ?? '', text: m.text, at: m.at })),
    pings: res.pings.map(g => ({
      id: g.id, from: g.from_uuid, name: g.name ?? '', server: g.server, dim: g.dim,
      x: g.x, y: g.y, z: g.z, label: g.label, at: g.at, expiresAt: g.expires,
    })),
    partyInvites: res.invites.map(i => ({ partyId: i.party_id, from: i.from_uuid, name: i.name ?? '', at: i.at })),
    warp: warp ? { server: warp.server, version: warp.version, at: warp.at, from: warp.from_uuid, name: warp.name ?? '' } : null,
  });
}

// ----------------------------------------------------------------- settings
async function settings(env, me, body, now) {
  if (typeof body.appearOffline !== 'boolean') return fail(400, 'Appear offline must be on or off.');
  const on = body.appearOffline ? 1 : 0;
  // offline_since: when invisibility went on (kept if it already was on).
  await env.DB.prepare(`INSERT INTO settings (uuid, appear_offline, offline_since, at)
      VALUES (?1, ?2, CASE WHEN ?2 = 1 THEN ?3 END, ?3)
      ON CONFLICT (uuid) DO UPDATE SET appear_offline = excluded.appear_offline,
        offline_since = CASE WHEN excluded.appear_offline = 1
          THEN COALESCE(CASE WHEN settings.appear_offline = 1 THEN settings.offline_since END, excluded.at) END,
        at = excluded.at`).bind(me.uuid, on, now).run();
  return json({ ok: true, appearOffline: !!on });
}

// ------------------------------------------------------------------ friends
// Minecraft name -> { uuid, name } (Mojang's spelling), null = no such
// player, 'down' = couldn't tell. Mojang answers 204/404 for unknown names.
// If Mojang is down or rate-limits us, names Origin already knows still work.
async function lookupName(env, name) {
  try {
    const r = await fetch(MOJANG_PROFILE + encodeURIComponent(name));
    if (r.status === 204 || r.status === 404) return null;
    if (r.ok) {
      const p = await r.json();
      const uuid = normUuid(p?.id);
      if (uuid) return { uuid, name: NAME_RE.test(p?.name || '') ? p.name : name };
    }
  } catch { /* fall through to what we know */ }
  const row = await env.DB.prepare('SELECT uuid, name FROM names WHERE name_lower = ? ORDER BY updated DESC LIMIT 1')
    .bind(name.toLowerCase()).first();
  return row ? { uuid: row.uuid, name: row.name } : 'down';
}

// Friendship both ways; any pending request either way is used up.
const befriend = (env, a, b, now) => env.DB.batch([
  env.DB.prepare('INSERT OR IGNORE INTO friends (a, b, at) VALUES (?, ?, ?)').bind(a, b, now),
  env.DB.prepare('INSERT OR IGNORE INTO friends (a, b, at) VALUES (?, ?, ?)').bind(b, a, now),
  env.DB.prepare('DELETE FROM friend_requests WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)').bind(a, b),
]);

async function friendRequest(env, me, body, now) {
  const name = String(body.name || '').trim();
  if (!NAME_RE.test(name)) return fail(400, "That isn't a Minecraft username.");
  if (name.toLowerCase() === me.name.toLowerCase()) return fail(400, "You can't add yourself.");
  // Before the Mojang lookup, so nobody can use us to hammer Mojang.
  if (!(await allow(env, me.uuid, 'friend', now))) return fail(429, MSG.slowDown);

  const found = await lookupName(env, name);
  if (found === 'down') return fail(503, "Couldn't look that player up right now. Try again in a minute.");
  if (!found) return fail(404, `There's no Minecraft player called ${name}.`);
  const them = found.uuid;
  if (them === me.uuid) return fail(400, "You can't add yourself.");

  const [onOrigin, theyBlocked, iBlocked, already, opposite, counts] = (await env.DB.batch([
    env.DB.prepare('SELECT 1 AS x FROM names WHERE uuid = ?').bind(them),
    env.DB.prepare('SELECT 1 AS x FROM blocks WHERE blocker = ? AND blocked = ?').bind(them, me.uuid),
    env.DB.prepare('SELECT 1 AS x FROM blocks WHERE blocker = ? AND blocked = ?').bind(me.uuid, them),
    env.DB.prepare('SELECT 1 AS x FROM friends WHERE a = ? AND b = ?').bind(me.uuid, them),
    env.DB.prepare('SELECT 1 AS x FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?').bind(them, me.uuid),
    env.DB.prepare(`SELECT (SELECT COUNT(*) FROM friends WHERE a = ?1) AS friends,
        (SELECT COUNT(*) FROM friend_requests WHERE from_uuid = ?1) AS outgoing,
        (SELECT COUNT(*) FROM friend_requests WHERE from_uuid = ?1 AND to_uuid = ?2) AS mine`).bind(me.uuid, them),
  ])).map(r => r.results?.[0] ?? null);

  // Someone who blocked you looks exactly like someone not on Origin.
  if (!onOrigin || theyBlocked) return fail(404, `${found.name} isn't on Origin yet.`);
  if (iBlocked) return fail(400, `You blocked ${found.name}. Unblock them first.`);
  if (already) return fail(400, `You're already friends with ${found.name}.`);
  if (counts.friends >= L.FRIENDS_MAX) return fail(400, `You already have ${L.FRIENDS_MAX} friends. Remove someone first.`);
  if (opposite) {
    // They already asked you: that's a yes from both sides.
    await befriend(env, me.uuid, them, now);
    return json({ ok: true, uuid: them, name: found.name, friends: true });
  }
  if (!counts.mine) {
    if (counts.outgoing >= L.OUTGOING_MAX) return fail(400, 'You have too many friend requests waiting. Cancel some first.');
    await env.DB.prepare('INSERT OR IGNORE INTO friend_requests (from_uuid, to_uuid, at) VALUES (?, ?, ?)').bind(me.uuid, them, now).run();
  }
  return json({ ok: true, uuid: them, name: found.name, friends: false });
}

async function friendRespond(env, me, body, now) {
  const them = normUuid(body.uuid);
  if (!them || typeof body.accept !== 'boolean') return fail(400, 'That friend request is not valid.');
  const req = await env.DB.prepare('SELECT 1 AS x FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?').bind(them, me.uuid).first();
  if (!req) return fail(404, "That friend request isn't there any more.");
  if (!body.accept) {
    await env.DB.prepare('DELETE FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?').bind(them, me.uuid).run();
    return json({ ok: true, friends: false });
  }
  const count = await env.DB.prepare('SELECT COUNT(*) AS n FROM friends WHERE a = ?').bind(me.uuid).first('n');
  if (count >= L.FRIENDS_MAX) return fail(400, `You already have ${L.FRIENDS_MAX} friends. Remove someone first.`);
  await befriend(env, me.uuid, them, now);
  return json({ ok: true, friends: true });
}

// Unfriends, and also cancels a pending request either way ("Cancel request").
async function friendRemove(env, me, body) {
  const them = normUuid(body.uuid);
  if (!them) return fail(400, 'Pick a friend.');
  await env.DB.batch([
    env.DB.prepare('DELETE FROM friends WHERE (a = ?1 AND b = ?2) OR (a = ?2 AND b = ?1)').bind(me.uuid, them),
    env.DB.prepare('DELETE FROM friend_requests WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)').bind(me.uuid, them),
  ]);
  return json({ ok: true });
}

// Blocking also ends the friendship, requests and party invites between you.
async function friendBlock(env, me, body, now) {
  const them = normUuid(body.uuid);
  if (!them || them === me.uuid) return fail(400, 'Pick a player to block.');
  await env.DB.batch([
    env.DB.prepare('INSERT OR IGNORE INTO blocks (blocker, blocked, at) VALUES (?, ?, ?)').bind(me.uuid, them, now),
    env.DB.prepare('DELETE FROM friends WHERE (a = ?1 AND b = ?2) OR (a = ?2 AND b = ?1)').bind(me.uuid, them),
    env.DB.prepare('DELETE FROM friend_requests WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)').bind(me.uuid, them),
    env.DB.prepare('DELETE FROM party_invites WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)').bind(me.uuid, them),
  ]);
  return json({ ok: true });
}

async function friendUnblock(env, me, body) {
  const them = normUuid(body.uuid);
  if (!them) return fail(400, 'Pick a player to unblock.');
  await env.DB.prepare('DELETE FROM blocks WHERE blocker = ? AND blocked = ?').bind(me.uuid, them).run();
  return json({ ok: true });
}

// --------------------------------------------------------------------- DMs
async function sendMessage(env, me, body, now) {
  const to = normUuid(body.to);
  if (!to || to === me.uuid) return fail(400, 'Pick a friend to message.');
  const text = cleanText(body.text, L.TEXT_MAX);
  if (!text) return fail(400, MSG.textLength);
  if (!(await areFriends(env, me.uuid, to))) return fail(403, 'You can only message your friends.');
  if (!(await allow(env, me.uuid, 'dm', now))) return fail(429, MSG.slowDown);
  const row = await addChat(env, { from: me.uuid, to, text }, now);
  return json({ ok: true, message: { id: row.id, from: me.uuid, to, text, at: row.at } });
}

// ------------------------------------------------------------------ routing
const ROUTES = {
  '/v1/sync': sync,
  '/v1/settings': settings,
  '/v1/friends/request': friendRequest,
  '/v1/friends/respond': friendRespond,
  '/v1/friends/remove': friendRemove,
  '/v1/friends/block': friendBlock,
  '/v1/friends/unblock': friendUnblock,
  '/v1/messages/send': sendMessage,
  ...PARTY_ROUTES,
};

// Returns a Response for a social path, or null when the path isn't one
// (index.js then answers 404).
export async function handleSocial(request, env, url, now = Date.now()) {
  const path = url.pathname;
  if (path === '/v1/stats/online' && request.method === 'GET') return json(await onlineCount(env, now));
  if (path !== '/v1/auth' && !ROUTES[path]) return null;
  if (request.method !== 'POST') return fail(405, 'Use POST.');

  let body = await request.json().catch(() => null);
  if (!body || typeof body !== 'object' || Array.isArray(body)) body = {};
  if (path === '/v1/auth') return signIn(env, body, now);

  const me = await session(request, env, now);
  if (!me) return fail(401, 'Your Origin sign-in ran out. Signing you in again...');
  return ROUTES[path](env, me, body, now);
}

// ------------------------------------------------------------------ cleanup
// Run by the cron (index.js, every 10 minutes). Every read already ignores
// expired rows, so this only keeps the tables small.
export async function socialCleanup(env, now = Date.now()) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE expires <= ?').bind(now),
    env.DB.prepare('DELETE FROM chat WHERE at < ?').bind(now - L.CHAT_KEEP_MS),
    env.DB.prepare('DELETE FROM pings WHERE expires <= ?').bind(now),
    env.DB.prepare('DELETE FROM warps WHERE at <= ?').bind(now - L.WARP_TTL_MS),
    env.DB.prepare('DELETE FROM party_invites WHERE at <= ?').bind(now - L.INVITE_TTL_MS),
    env.DB.prepare('DELETE FROM rate WHERE at < ?').bind(now - L.RATE_KEEP_MS),
    // Also done after each Tebex pass; repeated here so it happens even
    // while the store side is switched off or failing.
    env.DB.prepare('DELETE FROM joins WHERE at < ?').bind(now - L.TOKEN_TTL_MS),
  ]);
  // Players who closed everything an hour ago leave their party, so nobody's
  // client keeps polling fast for a party that is really over.
  const idle = (await env.DB.prepare(`SELECT m.uuid FROM party_members m
      WHERE m.joined < ?1 AND NOT EXISTS (SELECT 1 FROM presence p WHERE p.uuid = m.uuid AND p.at >= ?1) LIMIT 50`)
    .bind(now - L.PARTY_IDLE_MS).all()).results;
  for (const r of idle) await leaveParty(env, r.uuid);
  return { idleLeft: idle.length };
}
