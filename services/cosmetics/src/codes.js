// Free store codes (Will, 2026-10-18): Will makes them on his PC
// (tools/store/make-codes.ps1); a player types one on the store page and gets
// what it gives, with no card and no Stripe. Every code works once.
//
//   POST /v1/admin/codes    { kind, count } -> { codes: [...] }; `Authorization:
//                           Bearer <admin key>` (Will's, never in the repo)
//   POST /v1/store/redeem   { uuid, name, code, items } -> { kind, items, plus }
//
// Kinds:
//   bag   everything in the player's bag, free (any cosmetic on sale)
//   plus  Origin+ for good: a plus row that never runs out, so every perk
//         (cape, badge, themes, member prices, no ads) follows from isMember
//
// Var (wrangler.toml, not secret):
//   CODES_ADMIN_SHA256  hex SHA-256 of the admin key. Only the hash is kept,
//                       so the repo can't make codes; a new key = a new hash.
import { json, fail, normUuid, NAME_RE } from './common.js';
import { CATALOG, forSale } from './stripe.js';

export const KINDS = ['bag', 'plus'];
const MAX_MADE = 100;                           // codes per request
// No 0/O, 1/I/L: codes get read aloud and typed off screenshots.
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
const GROUPS = 3, GROUP_LEN = 4;                // ORIGIN-XXXX-XXXX-XXXX, ~59 bits: unguessable
// A plus row's period_end for a code membership: 31 Dec 9999, i.e. never.
export const FOREVER_S = 253402300799;

const hex = buf => [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
const sha256 = async s => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));

function newCode() {
  // Rejection sampling keeps every letter equally likely.
  const out = [];
  while (out.length < GROUPS * GROUP_LEN) {
    for (const b of crypto.getRandomValues(new Uint8Array(32))) {
      if (b < 256 - (256 % ALPHABET.length) && out.length < GROUPS * GROUP_LEN) out.push(ALPHABET[b % ALPHABET.length]);
    }
  }
  return out.join('');
}
// Stored and compared bare (no prefix, dashes or spaces, upper case), so
// "origin-ab12-..." and "AB12 ..." both match.
export const normCode = raw => String(raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '').replace(/^ORIGIN/, '');
const show = code => `ORIGIN-${code.match(new RegExp(`.{${GROUP_LEN}}`, 'g')).join('-')}`;

async function isAdmin(request, env) {
  const want = String(env.CODES_ADMIN_SHA256 || '').toLowerCase();
  const key = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
  if (!/^[0-9a-f]{64}$/.test(want) || !key) return false;
  const got = await sha256(key);
  return [...got].reduce((d, c, i) => d | (c.charCodeAt(0) ^ want.charCodeAt(i)), 0) === 0;
}

export async function makeCodes(request, env, body, now = Date.now()) {
  if (!(await isAdmin(request, env))) return fail(401, 'That admin key isn’t right.');
  const kind = String(body?.kind ?? '');
  const count = Number(body?.count ?? 1);
  if (!KINDS.includes(kind)) return fail(400, `kind must be one of: ${KINDS.join(', ')}`);
  if (!Number.isInteger(count) || count < 1 || count > MAX_MADE) return fail(400, `count must be 1-${MAX_MADE}`);
  const codes = Array.from({ length: count }, newCode);
  await env.DB.batch(codes.map(c => env.DB.prepare('INSERT INTO codes (code, kind, made_at) VALUES (?, ?, ?)').bind(c, kind, now)));
  return json({ kind, codes: codes.map(show) });
}

export async function redeem(env, body, now = Date.now()) {
  const uuid = normUuid(body?.uuid);
  const name = String(body?.name ?? '');
  const code = normCode(body?.code);
  if (!uuid || !NAME_RE.test(name)) return fail(400, 'Type the Minecraft name this is for.');
  if (!code) return fail(400, 'Type your code.');
  const row = await env.DB.prepare('SELECT kind, used_at FROM codes WHERE code = ?').bind(code).first();
  if (!row) return fail(404, 'That code doesn’t exist. Check it and try again.');
  if (row.used_at != null) return fail(409, 'That code has already been used.');

  let ids = [];
  if (row.kind === 'bag') {
    ids = Array.isArray(body?.items) ? [...new Set(body.items)] : [];
    if (!ids.length) return fail(400, 'Add the cosmetics you want to your bag, then use the code.');
    if (ids.some(id => !forSale(id))) return fail(400, 'Something in your bag isn’t on sale. Remove it and try again.');
    const owned = new Set((await env.DB.prepare('SELECT item FROM owned WHERE uuid = ?').bind(uuid).all()).results.map(r => r.item));
    const dupe = ids.find(id => owned.has(id));
    if (dupe) return fail(409, `${name} already owns the ${CATALOG[dupe].name}. Remove it from your bag.`);
  } else if (row.kind === 'plus') {
    const forever = await env.DB.prepare('SELECT 1 FROM plus WHERE uuid = ? AND period_end >= ?').bind(uuid, FOREVER_S).first();
    if (forever) return fail(409, `${name} already has Origin+ for good. Save the code for someone else.`);
  } else {
    return fail(400, 'That code doesn’t work any more.');
  }

  // One transaction: claim the code, then give only if THIS call claimed it
  // (two players racing for one code: exactly one gets it).
  const ours = 'EXISTS (SELECT 1 FROM codes WHERE code = ? AND used_by = ? AND used_at = ?)';
  const payment = `code:${code}`;
  const give = row.kind === 'bag'
    ? ids.map(id => env.DB.prepare(`INSERT OR IGNORE INTO owned (uuid, item, payment, at) SELECT ?, ?, ?, ? WHERE ${ours}`)
      .bind(uuid, id, payment, now, code, uuid, now))
    : [env.DB.prepare(`INSERT OR IGNORE INTO plus (subscription, uuid, name, customer, status, period_end, event_at) SELECT ?, ?, ?, '', 'active', ?, 0 WHERE ${ours}`)
      .bind(payment, uuid, name, FOREVER_S, code, uuid, now)];
  const [claim] = await env.DB.batch([
    env.DB.prepare('UPDATE codes SET used_by = ?, used_name = ?, used_at = ?, items = ? WHERE code = ? AND used_at IS NULL')
      .bind(uuid, name, now, ids.join(',') || null, code),
    ...give,
  ]);
  if (!claim?.meta?.changes) return fail(409, 'That code has already been used.');
  // Still paying for a membership they now have for free: tell them to stop it.
  const paying = row.kind === 'plus' &&
    !!(await env.DB.prepare(`SELECT 1 FROM plus WHERE uuid = ? AND subscription NOT LIKE 'code:%' AND status IN ('active', 'trialing', 'past_due')`).bind(uuid).first());
  return json({ kind: row.kind, items: ids, plus: row.kind === 'plus', paying });
}
