// Rules and small helpers shared by social.js (sign-in, sync, friends, DMs)
// and party.js (parties). Contract: docs/SOCIAL.md. Every limit lives here,
// in one place, so the "Service notes" section of the contract can quote it.

const SECOND = 1000, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR;

export const L = {
  TOKEN_TTL_MS: DAY,               // a sign-in lasts 24 h
  ONLINE_MS: 90 * SECOND,          // "online" = synced in the last 90 s
  STATS_WINDOW_MS: 5 * MINUTE,     // players-online counts the last 5 minutes
  STATS_CACHE_MS: MINUTE,          // ...recounted at most once a minute
  ETAG_REFRESH_MS: 10 * MINUTE,    // friendsEtag also changes every 10 minutes (safety net)
  SWEEP_MS: MINUTE,                // the cron's "who just went offline" window (it runs every minute)
  PRESENCE_WRITE_MS: 30 * SECOND,  // presence row rewritten at most every 30 s...
  PRESENCE_CHANGE_MS: 5 * SECOND,  // ...or after 5 s when server/activity/version changed
  STATE_WRITE_MS: 30 * SECOND,     // party position rewritten every 30 s when standing still...
  STATE_CHANGE_MS: 5 * SECOND,     // ...or after 5 s when it changed
  STATE_FRESH_MS: 90 * SECOND,     // older positions are not shown
  TEXT_MAX: 256,                   // DM / party chat length (UTF-16 units, like Java/C# strings)
  LABEL_MAX: 32,                   // ping label length
  RATE_MS: SECOND,                 // one DM / party line / ping / invite / friend request a second
  CHAT_KEEP_MS: 30 * DAY,
  PING_TTL_MS: MINUTE,
  WARP_TTL_MS: 2 * MINUTE,
  INVITE_TTL_MS: 10 * MINUTE,      // party invites run out after 10 minutes
  PARTY_MAX: 8,
  PARTY_IDLE_MS: HOUR,             // a member seen nowhere for an hour drops out (cron)
  FRIENDS_MAX: 200,
  OUTGOING_MAX: 50,                // pending friend requests you sent
  DM_HISTORY: 100,                 // first sync (since = 0): the newest 100 DMs...
  PARTY_HISTORY: 50,               // ...and the newest 50 party lines since you joined
  DM_PAGE: 200,                    // later syncs: at most this many per call; the
  PARTY_PAGE: 200,                 // cursor then stops at the last one returned
  RATE_KEEP_MS: HOUR,
};

export const CLIENTS = new Set(['game', 'launcher']);
export const ACTIVITIES = new Set(['menu', 'singleplayer', 'server']);

// Player-facing sentences used in more than one place.
export const MSG = {
  slowDown: 'Slow down a little: one a second.',
  notInParty: "You're not in a party.",
  leaderOnly: 'Only the party leader can do that.',
  textLength: `Messages can be 1 to ${L.TEXT_MAX} characters.`,
};

export function randomHex(bytes) {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return [...b].map(x => x.toString(16).padStart(2, '0')).join('');
}

export async function sha256Hex(text) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map(x => x.toString(16).padStart(2, '0')).join('');
}

// Chat text: line breaks become spaces; control characters, Minecraft's §
// colour codes and invisible direction/zero-width marks are removed (the game
// prints party chat into vanilla chat). null when empty or too long after that.
export function cleanText(raw, max) {
  if (typeof raw !== 'string') return null;
  const t = raw.replace(/[\r\n\t]+/g, ' ')
    .replace(/[\u0000-\u001f\u007f-\u009f§​-‏‪-‮⁦-⁩﻿]/g, '')
    .trim();
  return t.length >= 1 && t.length <= max ? t : null;
}

// "Play.Hypixel.net:25565" and "play.hypixel.net" are the same server: lower
// case, no trailing dot, no default port. null when it isn't a host[:port].
export function normServer(raw) {
  if (typeof raw !== 'string') return null;
  let s = raw.trim().toLowerCase().replace(/\.(?=:\d+$|$)/, '');
  if (s.endsWith(':25565')) s = s.slice(0, -6);
  if (s.length > 255) return null;
  return /^([a-z0-9_-]+(\.[a-z0-9_-]+)*|\[[0-9a-f:.]+\])(:\d{1,5})?$/.test(s) ? s : null;
}

export function normVersion(raw) {
  return typeof raw === 'string' && /^[0-9A-Za-z][0-9A-Za-z.+_ -]{0,31}$/.test(raw.trim()) ? raw.trim() : null;
}

export function normDim(raw) {
  return typeof raw === 'string' && raw.length <= 100 && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(raw) ? raw : null;
}

// A finite number within ±limit, rounded to 1/perUnit (so a player standing
// still sends the same numbers and nothing is rewritten). null otherwise.
export function num(v, limit, perUnit) {
  if (typeof v !== 'number' || !Number.isFinite(v) || Math.abs(v) > limit) return null;
  return Math.round(v * perUnit) / perUnit;
}
export const coord = v => num(v, 3.2e7, 10);    // 0.1 block; the world border is 30 M blocks out

// One action of `kind` per second per account. True = go ahead. A single
// conditional upsert: it only writes (and reports a change) when the last
// one was at least a second ago.
export async function allow(env, uuid, kind, now) {
  const r = await env.DB.prepare(`INSERT INTO rate (uuid, kind, at) VALUES (?1, ?2, ?3)
      ON CONFLICT (uuid, kind) DO UPDATE SET at = excluded.at WHERE rate.at <= excluded.at - ${L.RATE_MS}`)
    .bind(uuid, kind, now).run();
  return !!r.meta?.changes;
}

// friendsEtag bumps (see names.friends_rev). Statements, so callers can put
// them in the same batch as the change itself.
export const bumpRev = (env, ...uuids) => env.DB.prepare(
  `UPDATE names SET friends_rev = friends_rev + 1 WHERE uuid IN (${uuids.map(() => '?').join(', ')})`).bind(...uuids);
// Everyone whose friends list shows `uuid` (its friends).
export const bumpFriendsOf = (env, uuid) => env.DB.prepare(
  'UPDATE names SET friends_rev = friends_rev + 1 WHERE uuid IN (SELECT b FROM friends WHERE a = ?)').bind(uuid);

export async function areFriends(env, a, b) {
  return !!(await env.DB.prepare('SELECT 1 AS x FROM friends WHERE a = ? AND b = ?').bind(a, b).first());
}

// Adds a DM (to set) or a party line (partyId set). `at` never goes below
// the newest existing line, so time order always matches id (= cursor) order
// even if two Worker machines' clocks disagree by a few ms.
export function addChat(env, { partyId = null, from, to = null, text }, now) {
  return env.DB.prepare(`INSERT INTO chat (party_id, from_uuid, to_uuid, text, at)
      SELECT ?1, ?2, ?3, ?4, MAX(?5, COALESCE((SELECT MAX(at) FROM chat), 0)) RETURNING id, at`)
    .bind(partyId, from, to, text, now).first();
}

// One party member as clients see them. `state` only when the caller is on
// the same server as that member's last position (and it is recent).
export function memberView(row, now, callerServer) {
  const visible = !!callerServer && row.state_server === callerServer && row.state_at > now - L.STATE_FRESH_MS;
  let state = null;
  if (visible) try { state = JSON.parse(row.state); } catch { state = null; }
  return {
    uuid: row.uuid,
    name: row.name ?? '',
    online: !row.appear_offline && row.seen > now - L.ONLINE_MS,
    state,
    stateAt: state ? row.state_at : null,
  };
}

// Leader first, then everyone else in the order they joined.
export function partyOut(id, leader, rows, now, callerServer) {
  const members = rows.map(r => memberView(r, now, callerServer));
  members.sort((a, b) => (b.uuid === leader) - (a.uuid === leader));
  return { id, leader, members };
}

// Columns memberView needs, for `m` = party_members (joined to names `n` and settings `s`).
export const MEMBER_COLUMNS = `m.uuid, n.name, s.appear_offline, m.state, m.state_server, m.state_at,
  (SELECT MAX(at) FROM presence p WHERE p.uuid = m.uuid) AS seen`;
