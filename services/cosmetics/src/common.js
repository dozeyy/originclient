// Pieces shared by the cosmetics API (index.js) and Origin social
// (social.js, party.js) and identity checks (mojang.js): responses, UUIDs, names.

import { CATALOG } from './catalog.gen.js';

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

// Which slot an item wears in (cape, head, face, back, theme), from the
// generated catalog (cosmetics/FORMAT.md). Unknown ids have none.
export const SLOTS = ['cape', 'head', 'face', 'back', 'theme'];
export function slotOf(item) {
  return Object.hasOwn(CATALOG, item) ? CATALOG[item].slot : null;
}

// D1 refuses any statement with more than 100 bound values, so a query over a
// list of players (WHERE uuid IN (...)) runs in slices that leave room for the
// statement's other values. `query(slice)` returns D1's { results }.
const IN_SLICE = 90;
export async function rowsIn(uuids, query) {
  const rows = [];
  for (let i = 0; i < uuids.length; i += IN_SLICE) rows.push(...(await query(uuids.slice(i, i + IN_SLICE))).results);
  return rows;
}
