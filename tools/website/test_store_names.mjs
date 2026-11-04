// The Store's Minecraft-name check (website/js/names.mjs): only a clear "no such player" may
// tell a buyer their name doesn't exist; PlayerDB's own failures must read as "couldn't check".
// Run: node tools/website/test_store_names.mjs
import assert from 'node:assert/strict';
import { nameResult, headUrl } from '../../website/js/names.mjs';

const found = nameResult(200, {
  success: true, code: 'player.found',
  data: { player: { username: 'Notch', id: '069a79f4-44e9-4726-a5be-fca90e38aaf5', avatar: 'https://crafthead.net/avatar/x' } },
});
assert.equal(found.state, 'ok');
assert.equal(found.player.uuid, '069a79f444e94726a5befca90e38aaf5');
assert.equal(found.player.name, 'Notch');

// The head picture comes from the UUID on crafthead (in the CSP's img-src), never PlayerDB's avatar host.
const elsewhere = nameResult(200, {
  success: true, code: 'player.found',
  data: { player: { username: 'Notch', id: '069a79f4-44e9-4726-a5be-fca90e38aaf5', avatar: 'https://cdn.example.com/head.png' } },
});
assert.equal(elsewhere.player.avatar, undefined);
assert.equal(headUrl(elsewhere.player.uuid), 'https://crafthead.net/avatar/069a79f444e94726a5befca90e38aaf5/48');
assert.equal(headUrl('069A79F444E94726A5BEFCA90E38AAF5'), 'https://crafthead.net/avatar/069a79f444e94726a5befca90e38aaf5/48');
assert.equal(headUrl(''), null);
assert.equal(headUrl(undefined), null);
assert.equal(headUrl('../../evil'), null);

// A name nobody has.
assert.equal(nameResult(400, { success: false, code: 'minecraft.invalid_username', data: {} }).state, 'missing');
assert.equal(nameResult(404, { success: false, data: {} }).state, 'missing');
assert.equal(nameResult(400, { success: false }).state, 'missing');

// PlayerDB or Mojang having a bad moment: never "no such account".
assert.equal(nameResult(500, { success: false, code: 'minecraft.api_failure', data: {} }).state, 'unknown');
assert.equal(nameResult(429, { success: false, code: 'rate_limited' }).state, 'unknown');
assert.equal(nameResult(502, null).state, 'unknown');
assert.equal(nameResult(200, { success: false, code: 'api.unavailable' }).state, 'unknown');

console.log('store name checks: ok');
