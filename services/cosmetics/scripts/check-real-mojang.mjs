// CI check (cosmetics-service.yml, test job): the service's signature code
// (src/mojang.js) accepts REAL Mojang data, not just the test stand-in's.
// Fetches Mojang's published keys and a real signed profile (Notch's) and
// verifies it exactly as /v1/auth does; a tampered copy must fail. The
// certificate check can't be run here (it needs a signed-in account), but it
// uses the same key import and verify path with Mojang's other key list.
//   node scripts/check-real-mojang.mjs
// Exit 1 = our code rejects real Mojang data (a bug). Mojang unreachable =
// a warning only, so a Mojang outage never fails CI.
import { importMojangKeys, signedProfile } from '../src/mojang.js';
import { normUuid } from '../src/common.js';

const NOTCH = '069a79f444e94726a5befca90e38aaf5';
const UA = { 'User-Agent': 'OriginClient-CI/1.0 (+https://originclient.org)', Accept: 'application/json' };

async function get(url) {
  try {
    const r = await fetch(url, { headers: UA });
    if (r.ok) return await r.json();
    console.log(`::warning::${url} answered ${r.status}; skipping the real-Mojang check.`);
  } catch (err) {
    console.log(`::warning::${url} unreachable (${err}); skipping the real-Mojang check.`);
  }
  process.exit(0);
}

const fail = msg => { console.log(`::error::${msg}`); process.exit(1); };

const published = await get('https://api.minecraftservices.com/publickeys');
const keys = {
  profile: await importMojangKeys(published.profilePropertyKeys),
  certificate: await importMojangKeys(published.playerCertificateKeys),
};
console.log(`Mojang publishes ${published.profilePropertyKeys?.length} profile / ${published.playerCertificateKeys?.length} certificate keys;`
  + ` usable: ${keys.profile.length} / ${keys.certificate.length}`);
if (!keys.profile.length || !keys.certificate.length) fail("Mojang's published keys don't import as RSA keys.");

const profile = await get(`https://sessionserver.mojang.com/session/minecraft/profile/${NOTCH}?unsigned=false`);
const textures = profile.properties?.find(p => p.name === 'textures');
if (!textures?.signature) fail('The signed profile has no textures signature.');
const decoded = await signedProfile(keys, textures);
if (!decoded) fail("A real Mojang-signed profile did not verify with Mojang's profilePropertyKeys.");
if (normUuid(decoded.profileId) !== NOTCH || decoded.profileName !== profile.name) {
  fail(`Decoded profile ${decoded.profileId}/${decoded.profileName} doesn't match ${NOTCH}/${profile.name}.`);
}
console.log(`Real profile verified: ${decoded.profileName}, timestamp ${decoded.timestamp} (age ${Date.now() - decoded.timestamp} ms).`);

const tampered = { value: textures.value.slice(0, -4) + (textures.value.endsWith('AAAA') ? 'BBBB' : 'AAAA'), signature: textures.signature };
if (await signedProfile(keys, tampered)) fail('A tampered profile verified — the check is not checking.');
console.log('Tampered profile refused. Real-Mojang check passed.');
