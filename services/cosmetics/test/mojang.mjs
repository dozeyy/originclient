// Test stand-in for Mojang: real RSA keys, signed exactly the way Mojang
// signs player certificates and profiles, plus the player's own signature.
// Shared by service.test.mjs and social.test.mjs.
import { generateKeyPairSync, sign } from 'node:crypto';

const rsa = () => generateKeyPairSync('rsa', { modulusLength: 2048 });
export const MOJANG_CERT = rsa();      // signs player certificates (playerCertificateKeys)
export const MOJANG_PROFILE = rsa();   // signs profile properties (profilePropertyKeys)
export const NOT_MOJANG = rsa();       // someone pretending to be Mojang
export const RETIRED = rsa();          // an unrelated key also listed (Mojang publishes several)
export const PLAYER_KEY = rsa();       // a player certificate's key pair
export const OTHER_PLAYER_KEY = rsa();

export const spki = key => key.publicKey.export({ type: 'spki', format: 'der' });
export const b64 = buf => Buffer.from(buf).toString('base64');

// Mojang's keys as the GitHub job stores them in D1 meta 'mojang_keys'.
// An extra unrelated key sits first in each list: any one of them may match.
export function seedKeys(env, { profile = [RETIRED, MOJANG_PROFILE], certificate = [RETIRED, MOJANG_CERT], at = 1 } = {}) {
  const v = JSON.stringify({
    profilePropertyKeys: profile.map(k => b64(spki(k))),
    playerCertificateKeys: certificate.map(k => b64(spki(k))),
    at,
  });
  env.DB.raw.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('mojang_keys', ?)").run(v);
}

// uuid-msb ‖ uuid-lsb ‖ keyExpiresAt int64 BE ‖ SPKI DER (Minecraft's ProfilePublicKey.Data).
export function certPayload(uuid, expiresAt, der) {
  const b = Buffer.alloc(24 + der.length);
  Buffer.from(uuid.replace(/-/g, ''), 'hex').copy(b, 0);
  b.writeBigInt64BE(BigInt(expiresAt), 16);
  der.copy(b, 24);
  return b;
}

// A full, valid /v1/auth v2 body for `name`/`uuid` at time `now`. Every
// part can be overridden to build the failure cases.
export function authBody({
  name, uuid, client = 'game', now,
  at = now,
  keyExpiresAt = now + 48 * 3600_000,
  playerKey = PLAYER_KEY,          // whose key the certificate is for
  signingKey = playerKey,          // who signs the possession string
  certSigner = MOJANG_CERT,
  certUuid = uuid,
  profileSigner = MOJANG_PROFILE,
  profileId = uuid, profileName = name, timestamp = now,
  publicKeyText,                   // override how publicKey is written (PEM etc.)
}) {
  const der = spki(playerKey);
  const value = b64(JSON.stringify({ timestamp, profileId, profileName, textures: {} }));
  return {
    client, uuid, name, at,
    sig: b64(sign('sha256', Buffer.from(`origin-auth|${uuid.replace(/-/g, '').toLowerCase()}|${client}|${at}`, 'utf8'), signingKey.privateKey)),
    publicKey: publicKeyText ?? b64(der),
    keyExpiresAt,
    keySignature: b64(sign('sha1', certPayload(certUuid, keyExpiresAt, der), certSigner.privateKey)),
    textures: { value, signature: b64(sign('sha1', Buffer.from(value, 'utf8'), profileSigner.privateKey)) },
  };
}
