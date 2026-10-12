// Pieces shared by the cosmetics API (index.js) and Origin social
// (social.js, party.js) and identity checks (mojang.js): responses, UUIDs, names.

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
