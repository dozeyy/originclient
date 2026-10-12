// Origin parties: create, invite, accept/decline, leave, kick, promote, party
// chat, pings and "warp party here". Contract: docs/SOCIAL.md. Positions
// (partyState) arrive through /v1/sync, see social.js.
//
// A party exists while it has members: the last one out deletes it with its
// invites, pings, warp and chat. A player is in at most one party
// (party_members is keyed by uuid).

import { json, fail, normUuid } from './common.js';
import {
  L, MSG, randomHex, cleanText, normServer, normVersion, normDim, coord,
  allow, areFriends, addChat, partyOut, MEMBER_COLUMNS,
} from './social-shared.js';

const PARTY_ID_RE = /^[0-9a-f]{8,64}$/;

// The caller's party, its leader and size, or null.
async function membership(env, uuid) {
  return env.DB.prepare(`SELECT m.party_id, p.leader,
        (SELECT COUNT(*) FROM party_members x WHERE x.party_id = m.party_id) AS count
      FROM party_members m JOIN parties p ON p.id = m.party_id WHERE m.uuid = ?`).bind(uuid).first();
}

// The party as action responses return it. Positions are left out here
// (they come with /v1/sync, filtered by server), so `state` is always null.
async function partyView(env, partyId, now) {
  const p = await env.DB.prepare('SELECT leader FROM parties WHERE id = ?').bind(partyId).first();
  if (!p) return null;
  const rows = (await env.DB.prepare(`SELECT ${MEMBER_COLUMNS} FROM party_members m
      LEFT JOIN names n ON n.uuid = m.uuid LEFT JOIN settings s ON s.uuid = m.uuid
      WHERE m.party_id = ? ORDER BY m.joined, m.uuid`).bind(partyId).all()).results;
  return partyOut(partyId, p.leader, rows, now, null);
}

// New members only see party chat sent after they joined: chat_from is the
// newest chat id at that moment.
const joinMember = (env, uuid, partyId, now) => env.DB.prepare(`INSERT INTO party_members (uuid, party_id, joined, chat_from)
    SELECT ?1, ?2, ?3, COALESCE((SELECT MAX(id) FROM chat), 0)
    WHERE (SELECT COUNT(*) FROM party_members WHERE party_id = ?2) < ${L.PARTY_MAX}`).bind(uuid, partyId, now);

async function createParty(env, uuid, now) {
  const id = randomHex(12);
  await env.DB.batch([
    env.DB.prepare('INSERT INTO parties (id, leader, created) VALUES (?, ?, ?)').bind(id, uuid, now),
    joinMember(env, uuid, id, now),
  ]);
  return id;
}

// Takes `uuid` out of their party in one transaction: the longest member
// (earliest joined) takes over if the leader left; the last one out deletes
// the party and everything that belongs to it. Also used by the cron.
export async function leaveParty(env, uuid) {
  const m = await env.DB.prepare('SELECT party_id FROM party_members WHERE uuid = ?').bind(uuid).first();
  if (!m) return false;
  const gone = 'NOT EXISTS (SELECT 1 FROM party_members WHERE party_id = ?1)';
  await env.DB.batch([
    env.DB.prepare('DELETE FROM party_members WHERE uuid = ?').bind(uuid),
    env.DB.prepare(`UPDATE parties SET leader =
        (SELECT uuid FROM party_members WHERE party_id = ?1 ORDER BY joined, uuid LIMIT 1)
      WHERE id = ?1 AND leader = ?2 AND EXISTS (SELECT 1 FROM party_members WHERE party_id = ?1)`).bind(m.party_id, uuid),
    env.DB.prepare(`DELETE FROM parties WHERE id = ?1 AND ${gone}`).bind(m.party_id),
    env.DB.prepare(`DELETE FROM party_invites WHERE party_id = ?1 AND ${gone}`).bind(m.party_id),
    env.DB.prepare(`DELETE FROM pings WHERE party_id = ?1 AND ${gone}`).bind(m.party_id),
    env.DB.prepare(`DELETE FROM warps WHERE party_id = ?1 AND ${gone}`).bind(m.party_id),
    env.DB.prepare(`DELETE FROM chat WHERE party_id = ?1 AND ${gone}`).bind(m.party_id),
  ]);
  return true;
}

// ----------------------------------------------------------------- actions
// Each takes (env, me = { uuid, name, client }, body, now) and returns a Response.

async function create(env, me, body, now) {
  if (await membership(env, me.uuid)) return fail(400, "You're already in a party. Leave it first.");
  try {
    const id = await createParty(env, me.uuid, now);
    return json({ ok: true, party: await partyView(env, id, now) });
  } catch {
    // Two creates at once: the second one hits the one-party-per-player key.
    return fail(400, "You're already in a party. Leave it first.");
  }
}

// Friends only. Any member may invite. Inviting while not in a party starts
// one (you lead it), so "Invite to party" works from the friends list directly.
async function invite(env, me, body, now) {
  const them = normUuid(body.uuid);
  if (!them || them === me.uuid) return fail(400, 'Pick a friend to invite.');
  if (!(await areFriends(env, me.uuid, them))) return fail(403, 'You can only invite friends to your party.');

  let m = await membership(env, me.uuid);
  if (m && m.count >= L.PARTY_MAX) return fail(400, `Your party is full (${L.PARTY_MAX} players).`);
  const theirs = await env.DB.prepare('SELECT party_id FROM party_members WHERE uuid = ?').bind(them).first('party_id');
  if (m && theirs === m.party_id) return fail(400, "They're already in your party.");
  if (!(await allow(env, me.uuid, 'invite', now))) return fail(429, MSG.slowDown);

  if (!m) {
    await createParty(env, me.uuid, now);
    m = await membership(env, me.uuid);
  }
  await env.DB.prepare(`INSERT INTO party_invites (party_id, to_uuid, from_uuid, at) VALUES (?, ?, ?, ?)
      ON CONFLICT (party_id, to_uuid) DO UPDATE SET from_uuid = excluded.from_uuid, at = excluded.at`)
    .bind(m.party_id, them, me.uuid, now).run();
  return json({ ok: true, party: await partyView(env, m.party_id, now) });
}

// Joining leaves your old party first (after every check has passed).
async function accept(env, me, body, now) {
  const partyId = String(body.partyId || '');
  if (!PARTY_ID_RE.test(partyId)) return fail(400, 'That party invite is not valid.');
  const inv = await env.DB.prepare('SELECT at FROM party_invites WHERE party_id = ? AND to_uuid = ? AND at > ?')
    .bind(partyId, me.uuid, now - L.INVITE_TTL_MS).first();
  if (!inv) return fail(404, 'That party invite has run out.');
  const dropInvite = env.DB.prepare('DELETE FROM party_invites WHERE party_id = ? AND to_uuid = ?').bind(partyId, me.uuid);

  const size = await env.DB.prepare(`SELECT (SELECT COUNT(*) FROM parties WHERE id = ?1) AS exists_,
      (SELECT COUNT(*) FROM party_members WHERE party_id = ?1) AS count`).bind(partyId).first();
  if (!size.exists_) { await dropInvite.run(); return fail(404, "That party doesn't exist any more."); }
  const current = await membership(env, me.uuid);
  if (current?.party_id === partyId) { await dropInvite.run(); return json({ ok: true, party: await partyView(env, partyId, now) }); }
  if (size.count >= L.PARTY_MAX) return fail(400, `That party is full (${L.PARTY_MAX} players).`);

  if (current) await leaveParty(env, me.uuid);
  const [joined] = await env.DB.batch([joinMember(env, me.uuid, partyId, now), dropInvite]);
  if (!joined.meta?.changes) return fail(400, `That party is full (${L.PARTY_MAX} players).`);
  return json({ ok: true, party: await partyView(env, partyId, now) });
}

async function decline(env, me, body) {
  const partyId = String(body.partyId || '');
  if (!PARTY_ID_RE.test(partyId)) return fail(400, 'That party invite is not valid.');
  await env.DB.prepare('DELETE FROM party_invites WHERE party_id = ? AND to_uuid = ?').bind(partyId, me.uuid).run();
  return json({ ok: true });
}

async function leave(env, me) {
  if (!(await leaveParty(env, me.uuid))) return fail(400, MSG.notInParty);
  return json({ ok: true });
}

// Shared checks for kick / promote: caller leads, target is another member.
async function leaderAndTarget(env, me, body) {
  const m = await membership(env, me.uuid);
  if (!m) return { error: fail(400, MSG.notInParty) };
  if (m.leader !== me.uuid) return { error: fail(403, MSG.leaderOnly) };
  const them = normUuid(body.uuid);
  if (!them || them === me.uuid) return { error: fail(400, 'Pick another party member.') };
  const theirs = await env.DB.prepare('SELECT party_id FROM party_members WHERE uuid = ?').bind(them).first('party_id');
  if (theirs !== m.party_id) return { error: fail(404, "They're not in your party.") };
  return { m, them };
}

async function kick(env, me, body, now) {
  const { error, m, them } = await leaderAndTarget(env, me, body);
  if (error) return error;
  await env.DB.prepare('DELETE FROM party_members WHERE uuid = ? AND party_id = ?').bind(them, m.party_id).run();
  return json({ ok: true, party: await partyView(env, m.party_id, now) });
}

async function promote(env, me, body, now) {
  const { error, m, them } = await leaderAndTarget(env, me, body);
  if (error) return error;
  await env.DB.prepare('UPDATE parties SET leader = ? WHERE id = ?').bind(them, m.party_id).run();
  return json({ ok: true, party: await partyView(env, m.party_id, now) });
}

async function chat(env, me, body, now) {
  const m = await membership(env, me.uuid);
  if (!m) return fail(400, MSG.notInParty);
  const text = cleanText(body.text, L.TEXT_MAX);
  if (!text) return fail(400, MSG.textLength);
  if (!(await allow(env, me.uuid, 'party', now))) return fail(429, MSG.slowDown);
  const row = await addChat(env, { partyId: m.party_id, from: me.uuid, text }, now);
  return json({ ok: true, message: { id: row.id, from: me.uuid, name: me.name, text, at: row.at } });
}

// A marker on a block for the whole party, for 60 s. Only members on the
// same server see it (sync filters by server).
async function ping(env, me, body, now) {
  const m = await membership(env, me.uuid);
  if (!m) return fail(400, MSG.notInParty);
  const server = normServer(body.server), dim = normDim(body.dim);
  const x = coord(body.x), y = coord(body.y), z = coord(body.z);
  if (!server || !dim || x === null || y === null || z === null) return fail(400, "That ping doesn't say where it is.");
  let label = null;
  if (body.label !== undefined && body.label !== null && body.label !== '') {
    label = cleanText(body.label, L.LABEL_MAX);
    if (!label) return fail(400, `Ping labels can be up to ${L.LABEL_MAX} characters.`);
  }
  if (!(await allow(env, me.uuid, 'ping', now))) return fail(429, MSG.slowDown);
  const expiresAt = now + L.PING_TTL_MS;
  const row = await env.DB.prepare(`INSERT INTO pings (party_id, from_uuid, server, dim, x, y, z, label, at, expires)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`)
    .bind(m.party_id, me.uuid, server, dim, x, y, z, label, now, expiresAt).first();
  return json({ ok: true, ping: { id: row.id, from: me.uuid, name: me.name, server, dim, x, y, z, label, at: now, expiresAt } });
}

// Leader only: everyone else in the party gets `warp` in /v1/sync for 2 minutes.
async function warp(env, me, body, now) {
  const m = await membership(env, me.uuid);
  if (!m) return fail(400, MSG.notInParty);
  if (m.leader !== me.uuid) return fail(403, MSG.leaderOnly);
  const server = normServer(body.server), version = normVersion(body.version);
  if (!server || !version) return fail(400, 'Warp needs the server and the Minecraft version.');
  await env.DB.prepare('INSERT OR REPLACE INTO warps (party_id, from_uuid, server, version, at) VALUES (?, ?, ?, ?, ?)')
    .bind(m.party_id, me.uuid, server, version, now).run();
  return json({ ok: true });
}

export const PARTY_ROUTES = {
  '/v1/party/create': create,
  '/v1/party/invite': invite,
  '/v1/party/accept': accept,
  '/v1/party/decline': decline,
  '/v1/party/leave': leave,
  '/v1/party/kick': kick,
  '/v1/party/promote': promote,
  '/v1/party/chat': chat,
  '/v1/party/ping': ping,
  '/v1/party/warp': warp,
};
