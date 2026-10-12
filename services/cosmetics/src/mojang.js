// Identity v2 (docs/SOCIAL.md -> "Identity v2"): who is signing in, proven
// with two things Mojang already signs for every player, checked here with
// Mojang's published public keys. No call to Mojang ever leaves the Worker:
// Mojang answers every request from Cloudflare Workers with 403, so GitHub
// Actions fetches https://api.minecraftservices.com/publickeys instead and
// stores it in D1 `meta` (scripts/store-mojang-keys.sh).
//
// All checks are RSASSA-PKCS1-v1_5 via Web Crypto:
//  - the player certificate: Mojang's publicKeySignatureV2, SHA-1, over
//    uuid (16 raw bytes) ‖ keyExpiresAt (int64 big-endian ms) ‖ the
//    certificate's public key (SPKI DER, exactly the bytes sent), against
//    any of Mojang's playerCertificateKeys (Minecraft's own ProfilePublicKey);
//  - possession: the client's `sig`, SHA-256, over
//    `origin-auth|<uuid>|<client>|<at>` with the certificate's key;
//  - the name: Mojang's textures signature, SHA-1, over the raw
//    textures.value string, against any of Mojang's profilePropertyKeys.

import { normUuid, NAME_RE } from './common.js';

const MINUTE = 60_000, HOUR = 60 * MINUTE;
export const AUTH = {
  AT_SKEW_MS: 5 * MINUTE,          // |now - at| for the signed string
  TEXTURES_MAX_AGE_MS: 24 * HOUR,  // the signed profile must be this fresh...
  TEXTURES_FUTURE_MS: 5 * MINUTE,  // ...and not from the future (clock slack)
  KEYS_CACHE_MS: 10 * MINUTE,      // Mojang's keys re-read from D1 this often per Worker machine
};
export const KEYS_META = 'mojang_keys';   // meta row: { profilePropertyKeys: [b64], playerCertificateKeys: [b64], at }

// ------------------------------------------------------------- encodings
// Base64 -> bytes. Accepts standard or URL-safe alphabet, with or without
// padding, line breaks anywhere, and PEM armour ("-----BEGIN ... KEY-----"
// with any label: Mojang labels X.509 SPKI keys "RSA PUBLIC KEY"). null when
// it isn't base64.
export function b64bytes(raw) {
  if (typeof raw !== 'string') return null;
  let s = raw.replace(/-----(BEGIN|END)[^-]*-----/g, '').replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/');
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(s)) return null;
  s = s.replace(/=+$/, '');
  if (s.length % 4 === 1) return null;
  s += '='.repeat((4 - (s.length % 4)) % 4);
  try {
    const bin = atob(s);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

const hex = bytes => [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2, '0')).join('');
const utf8 = text => new TextEncoder().encode(text);

const rsa = hash => ({ name: 'RSASSA-PKCS1-v1_5', hash });
async function importSpki(der, hash) {
  try {
    return await crypto.subtle.importKey('spki', der, rsa(hash), false, ['verify']);
  } catch {
    return null;
  }
}
async function verify(key, sig, data) {
  try {
    return await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, sig, data);
  } catch {
    return false;
  }
}
async function verifyAny(keys, sig, data) {
  for (const k of keys) if (await verify(k, sig, data)) return true;
  return false;
}

// ----------------------------------------------------------- Mojang's keys
// Read from D1 and imported once per Worker machine, refreshed every 10
// minutes. A missing/empty row is never cached, so keys written by the next
// GitHub run are picked up at once.
const keysMemo = new WeakMap();   // keyed by the DB binding

// A list of base64 SPKI keys -> the ones that import as RSA SHA-1 verify
// keys. Entries may also be Mojang's own { publicKey } objects.
export async function importMojangKeys(list) {
  const out = [];
  for (const entry of Array.isArray(list) ? list : []) {
    const b = typeof entry === 'string' ? entry : entry?.publicKey;
    const der = b64bytes(b);
    const k = der && await importSpki(der, 'SHA-1');
    if (k) out.push(k);
  }
  return out;
}

// -> { profile: [CryptoKey], certificate: [CryptoKey], at } or null.
export async function loadMojangKeys(env, now = Date.now(), { cached = true } = {}) {
  const memo = cached && keysMemo.get(env.DB);
  if (memo && now - memo.loadedAt < AUTH.KEYS_CACHE_MS) return memo.keys;
  let row = null;
  try { row = JSON.parse(await env.DB.prepare('SELECT v FROM meta WHERE k = ?').bind(KEYS_META).first('v')); } catch { row = null; }
  const keys = {
    profile: await importMojangKeys(row?.profilePropertyKeys),
    certificate: await importMojangKeys(row?.playerCertificateKeys),
    at: Number.isFinite(row?.at) ? row.at : null,
  };
  if (!keys.profile.length || !keys.certificate.length) return null;
  keysMemo.set(env.DB, { loadedAt: now, keys });
  return keys;
}

// GET /v1/diag/keys: how many usable keys the service holds, never the keys.
export async function keysDiag(env, now = Date.now()) {
  let row = null;
  try { row = JSON.parse(await env.DB.prepare('SELECT v FROM meta WHERE k = ?').bind(KEYS_META).first('v')); } catch { row = null; }
  return {
    profile: (await importMojangKeys(row?.profilePropertyKeys)).length,
    certificate: (await importMojangKeys(row?.playerCertificateKeys)).length,
    at: Number.isFinite(row?.at) ? row.at : null,
  };
}

// Mojang's signed profile property: { value, signature } as sessionserver
// returns it (?unsigned=false). -> the decoded JSON ({ timestamp, profileId,
// profileName, ... }) when the signature is Mojang's, else null.
export async function signedProfile(keys, textures) {
  const sig = b64bytes(textures?.signature);
  if (typeof textures?.value !== 'string' || !sig?.length) return null;
  if (!(await verifyAny(keys.profile, sig, utf8(textures.value)))) return null;
  try { return JSON.parse(new TextDecoder().decode(b64bytes(textures.value) ?? new Uint8Array())); } catch { return null; }
}

// --------------------------------------------------------------- the checks
// Player-facing sentences (shown as is; no internals).
export const AUTH_MSG = {
  update: "Couldn't sign in to Origin. Please update Origin and try again.",
  notReady: "Origin's friends service is starting up. Please try again in a few minutes.",
  account: "Couldn't confirm your Minecraft account. Please restart Origin and try again.",
  expired: 'Your Minecraft sign-in ran out. Please restart Origin and try again.',
  clock: "Couldn't sign in: your computer's clock looks wrong. Set it to the right time and try again.",
  reused: 'That sign-in check was already used. Please try again.',
  name: "Couldn't confirm your Minecraft name. Please restart Origin and try again.",
};

const isMs = v => Number.isSafeInteger(v) && v > 0;
const short = (v, max) => typeof v === 'string' && v.length > 0 && v.length <= max;

// Reads and checks a /v1/auth v2 body. Returns { uuid, name, client } or
// { status, error }. `clients` = the allowed client names. Single use: the
// signature's SHA-256 goes into `joins` (the same table v1 used), last, so a
// request that fails a check can be fixed and retried.
export async function verifyIdentity(env, body, clients, now = Date.now()) {
  const bad = (status, error) => ({ status, error });
  const uuid = normUuid(body.uuid);
  const { name, client, at, keyExpiresAt, textures } = body;
  if (!uuid || typeof name !== 'string' || !NAME_RE.test(name) || !clients.has(client) || !isMs(at) || !isMs(keyExpiresAt)
      || !short(body.sig, 2048) || !short(body.publicKey, 4096) || !short(body.keySignature, 2048)
      || !textures || typeof textures !== 'object' || !short(textures.value, 8192) || !short(textures.signature, 2048)) {
    return bad(400, AUTH_MSG.update);
  }
  const sig = b64bytes(body.sig), publicKey = b64bytes(body.publicKey), keySig = b64bytes(body.keySignature);
  if (!sig?.length || !publicKey?.length || !keySig?.length || !b64bytes(textures.signature)?.length) return bad(400, AUTH_MSG.update);
  const playerKey = await importSpki(publicKey, 'SHA-256');
  if (!playerKey) return bad(400, AUTH_MSG.update);

  const keys = await loadMojangKeys(env, now);
  if (!keys) return bad(503, AUTH_MSG.notReady);

  if (Math.abs(now - at) > AUTH.AT_SKEW_MS) return bad(401, AUTH_MSG.clock);

  // 1. The certificate: Mojang vouches that this key belongs to this uuid until keyExpiresAt.
  const signed = new Uint8Array(24 + publicKey.length);
  for (let i = 0; i < 16; i++) signed[i] = parseInt(uuid.slice(i * 2, i * 2 + 2), 16);
  new DataView(signed.buffer).setBigInt64(16, BigInt(keyExpiresAt), false);
  signed.set(publicKey, 24);
  if (!(await verifyAny(keys.certificate, keySig, signed))) return bad(401, AUTH_MSG.account);
  if (keyExpiresAt <= now) return bad(401, AUTH_MSG.expired);

  // 2. Possession: whoever sent this holds the certificate's private key.
  if (!(await verify(playerKey, sig, utf8(`origin-auth|${uuid}|${client}|${at}`)))) return bad(401, AUTH_MSG.account);

  // 3. The name: Mojang's signed profile says this uuid is called `name`, recently.
  const prof = await signedProfile(keys, textures);
  if (!prof || normUuid(prof.profileId) !== uuid || prof.profileName !== name) return bad(401, AUTH_MSG.name);
  const ts = Number(prof.timestamp);
  if (!Number.isFinite(ts) || ts < now - AUTH.TEXTURES_MAX_AGE_MS || ts > now + AUTH.TEXTURES_FUTURE_MS) return bad(401, AUTH_MSG.name);

  // 4. Each proof works once.
  const sigHash = hex(await crypto.subtle.digest('SHA-256', sig));
  const fresh = await env.DB.prepare('INSERT OR IGNORE INTO joins (server_id, at) VALUES (?, ?)').bind(`auth:${sigHash}`, now).run();
  if (!fresh.meta?.changes) return bad(401, AUTH_MSG.reused);
  return { uuid, name, client };
}
