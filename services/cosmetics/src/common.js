// Pieces shared by the cosmetics API (index.js) and Origin social
// (social.js, party.js): responses, UUIDs, Mojang's server-join check.

export const MOJANG_HAS_JOINED = 'https://sessionserver.mojang.com/session/minecraft/hasJoined';

export const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  // Authorization: social calls carry `Bearer <token>`.
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
};

export const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...CORS },
});
// `error` is shown to the player as is, so write it as a sentence for them.
export const fail = (status, error) => json({ error }, status);

// Minecraft UUIDs arrive dashed or not, any case; store one form.
export function normUuid(raw) {
  const u = String(raw || '').replace(/-/g, '').toLowerCase();
  return /^[0-9a-f]{32}$/.test(u) ? u : null;
}

export const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;
export const SERVER_ID_RE = /^[0-9a-f]{16,64}$/;

// Mojang's server-join check, the way a Minecraft server verifies a player:
// the client told Mojang "I'm joining <serverId>" with its own session, so
// hasJoined names the account that did it. Each serverId is accepted once
// (the `joins` table, pruned by the cron after a day).
// Returns { uuid, name } or { error: 'unverified' | 'reused' }.
export async function verifyMojangJoin(env, name, serverId, now = Date.now()) {
  const r = await fetch(`${MOJANG_HAS_JOINED}?username=${encodeURIComponent(name)}&serverId=${encodeURIComponent(serverId)}`);
  if (r.status !== 200) return { error: 'unverified' };
  const profile = await r.json().catch(() => null);
  const uuid = normUuid(profile?.id);
  if (!uuid) return { error: 'unverified' };

  const fresh = await env.DB.prepare('INSERT OR IGNORE INTO joins (server_id, at) VALUES (?, ?)').bind(serverId, now).run();
  if (!fresh.meta?.changes) return { error: 'reused' };
  return { uuid, name: NAME_RE.test(profile?.name || '') ? profile.name : name };
}
