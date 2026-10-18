// Cosmetics v2 build: cosmetics/items/<id>/item.mjs -> every output in cosmetics/FORMAT.md.
//
//   node tools/cosmetics/build.mjs               write everything
//   node tools/cosmetics/build.mjs --only <id>   repaint just that item (lists are always rewritten)
//   node tools/cosmetics/build.mjs --check       exit 1 if any output is stale (CI drift gate)
//
// Deterministic: no dates, seeded RNG only. PNGs are compared pixel-by-pixel in --check
// (zlib's byte output is not guaranteed across Node versions).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Canvas, decodePNG } from '../../cosmetics/lib/px.mjs';
import { checkModel, cubeSize } from '../../cosmetics/lib/model.mjs';
import { composeCape } from '../../cosmetics/lib/cape.mjs';
import { checkIdentity } from '../../cosmetics/lib/theme.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ITEMS_DIR = path.join(ROOT, 'cosmetics/items');
const OUT_SITE = path.join(ROOT, 'website/cosmetics');
// Every live mod module bundles the same item files (src/mods/live-modules.json is THE list),
// under its client resources, or main resources where the module has no split client set
// (1.17.1 and older).
const OUT_MODS = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/mods/live-modules.json'), 'utf8')).modules.map(({ module }) => {
  const res = ['client', 'main'].map(set => path.join(ROOT, 'src/mods/versions', module, 'src', set, 'resources'))
    .find(dir => fs.existsSync(path.join(dir, 'assets/originclient')));
  if (!res) throw new Error(`cosmetics build: src/mods/versions/${module} has no assets/originclient resources folder`);
  return path.join(res, 'assets/originclient/cosmetics');
});
const OUT_GEN = path.join(ROOT, 'services/cosmetics/src/catalog.gen.js');

const args = process.argv.slice(2);
const CHECK = args.includes('--check');
const only = args.includes('--only') ? args[args.indexOf('--only') + 1] : null;

const SLOT_OF = { cape: 'cape', wings: 'back', pack: 'back', mask: 'face', bandana: 'face', hat: 'head', halo: 'head', crown: 'head', theme: 'theme' };
const PREFIX_KIND = { cape: 'cape', wings: 'wings', pack: 'pack', mask: 'mask', bandana: 'bandana', hat: 'hat', halo: 'halo', crown: 'crown', theme: 'theme' };
const META_ORDER = ['id', 'name', 'kind', 'slot', 'cents', 'blurb', 'animated', 'plusOnly', 'onSale', 'hidesCape', 'capeOffset'];
const fail = msg => { console.error(`cosmetics build: ${msg}`); process.exit(1); };
const readJson = f => JSON.parse(fs.readFileSync(f, 'utf8'));

// ---------------------------------------------------------------- loading
const shop = readJson(path.join(ROOT, 'cosmetics/shop.json')).order;
const pending = readJson(path.join(ROOT, 'cosmetics/pending.json')).items;
const dirs = fs.existsSync(ITEMS_DIR) ? fs.readdirSync(ITEMS_DIR).filter(d => fs.existsSync(path.join(ITEMS_DIR, d, 'item.mjs'))).sort() : [];
const sources = new Map();
for (const id of dirs) sources.set(id, (await import(pathToFileURL(path.join(ITEMS_DIR, id, 'item.mjs')).href)).default);
for (const id of Object.keys(pending)) if (sources.has(id)) fail(`${id} has an item.mjs AND a line in cosmetics/pending.json: delete the pending line`);

function normMeta(id, src) {
  const m = src.meta || fail(`${id}: item.mjs has no meta`);
  if (m.id !== id) fail(`${id}: meta.id is "${m.id}" (must match the folder)`);
  if (!/^[a-z0-9-]+$/.test(id)) fail(`${id}: bad id`);
  const prefix = id.split('-')[0], kind = m.kind || PREFIX_KIND[prefix];
  if (!kind || PREFIX_KIND[prefix] !== kind) fail(`${id}: kind "${kind}" does not match the id prefix`);
  const slot = SLOT_OF[kind];
  if (m.slot && m.slot !== slot) fail(`${id}: slot must be ${slot} for kind ${kind}`);
  if (!m.name || !m.blurb) fail(`${id}: name and blurb are required`);
  const plusOnly = !!m.plusOnly;
  if (plusOnly ? m.cents !== 0 : !(m.cents >= 500 && m.cents <= 1000)) fail(`${id}: cents ${m.cents} (every sold price is $5-$10; plusOnly is 0)`);
  const animated = m.animated ?? !!((src.cape?.frames > 1) || (src.anim?.frames > 1) || src.anim?.motion?.length || src.fx?.length);
  const out = { id, name: m.name, kind, slot, cents: m.cents, blurb: m.blurb, animated, plusOnly, onSale: m.onSale ?? true, hidesCape: !!m.hidesCape, capeOffset: m.capeOffset ?? 0 };
  return Object.fromEntries(META_ORDER.map(k => [k, out[k]]));
}
const metas = new Map([...sources].map(([id, src]) => [id, normMeta(id, src)]));

// shop order: shop.json first (drawn or pending), then anything else, drawn items before pending ones
const known = new Set([...metas.keys(), ...Object.keys(pending)]);
const order = [...shop.filter(id => known.has(id)), ...[...known].filter(id => !shop.includes(id)).sort()];

// -------------------------------------------------------------- geometry check
// FORMAT.md "Clearance": static-pose bounding box of every cube, in the attach bone's frame.
const rad = d => (d * Math.PI) / 180;
const rotXYZ = ([ax, ay, az], p) => {            // applied Z, then Y, then X
  let [x, y, z] = p, c = Math.cos(rad(az)), s = Math.sin(rad(az)); [x, y] = [x * c - y * s, x * s + y * c];
  c = Math.cos(rad(ay)); s = Math.sin(rad(ay)); [x, z] = [x * c + z * s, -x * s + z * c];
  c = Math.cos(rad(ax)); s = Math.sin(rad(ax)); [y, z] = [y * c - z * s, y * s + z * c];
  return [x, y, z];
};
function modelBounds(model) {
  const byName = new Map(model.bones.map(b => [b.name, b])), lo = [1e9, 1e9, 1e9], hi = [-1e9, -1e9, -1e9];
  const chain = b => (b.parent ? [...chain(byName.get(b.parent)), b] : [b]);
  for (const bone of model.bones) for (const cube of bone.cubes || []) {
    const inf = cube.inflate || 0;
    for (let i = 0; i < 8; i++) {
      let p = [0, 1, 2].map(k => ((i >> k) & 1 ? cube.to[k] + inf : cube.from[k] - inf));
      if (cube.rotation) { const o = cube.rotation.origin; p = rotXYZ(cube.rotation.angle, p.map((v, k) => v - o[k])).map((v, k) => v + o[k]); }
      for (const b of chain(bone).reverse()) if (b.rotation) { p = rotXYZ(b.rotation, p.map((v, k) => v - b.pivot[k])).map((v, k) => v + b.pivot[k]); }
      for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], p[k]); hi[k] = Math.max(hi[k], p[k]); }
    }
  }
  return { lo, hi };
}
function checkClearance(id, meta, model) {
  const { lo, hi } = modelBounds(model), bad = [];
  if (meta.slot === 'head' && meta.kind !== 'halo') {
    if (hi[1] > 11.001) bad.push(`top ${hi[1].toFixed(2)} > 11 (max 3 px above the head)`);
    if (Math.max(hi[0], -lo[0], hi[2], -lo[2]) > 5.501) bad.push(`sticks out ${(Math.max(hi[0], -lo[0], hi[2], -lo[2]) - 4).toFixed(2)} px past the head (max 1.5)`);
  }
  if (meta.kind === 'halo') {
    if (lo[1] < 9.99 || hi[1] > 13.001) bad.push(`halo y ${lo[1].toFixed(2)}..${hi[1].toFixed(2)} outside 10..13`);
  }
  if (meta.slot === 'face' && hi[2] > 5.001) bad.push(`face item z reaches ${hi[2].toFixed(2)} (max 5)`);
  if (meta.kind === 'wings') {
    if (hi[1] > 6.001) bad.push(`wings rise to y ${hi[1].toFixed(2)} (max 6)`);
    if (Math.max(hi[0], -lo[0]) > 14.001) bad.push(`wings reach ${Math.max(hi[0], -lo[0]).toFixed(2)} px from the spine (max 14)`);
  }
  if (meta.kind === 'pack') {
    if (Math.max(hi[0], -lo[0]) > 4.001) bad.push('backpack wider than x -4..4');
    if (lo[2] < -7.001) bad.push(`backpack ${(-2 - lo[2]).toFixed(2)} px deep (max 5)`);
  }
  if (bad.length) fail(`${id}: ${bad.join('; ')}`);
}

// -------------------------------------------------------------- painting
// A theme's picture is the real menu rendered in it by tools/cosmetics/theme-preview.mjs
// (Chromium, so it is rendered once and committed, not repainted here). The store gets it
// full size; the mod gets a 4x box-filtered copy at about the size the Cosmetics screen
// draws it, so the game's plain (nearest) blit doesn't shimmer.
function themePicture(id) {
  const src = sources.get(id), dir = path.join(ITEMS_DIR, id);
  const rerun = `run node tools/cosmetics/theme-preview.mjs ${id}`;
  if (!src.theme?.deck || !src.theme?.workbench) fail(`${id}: theme must be themeTokens(...) from cosmetics/lib/theme.mjs`);
  if (!fs.existsSync(path.join(dir, 'preview.png'))) fail(`${id}: no preview.png; ${rerun}`);
  const was = JSON.parse(fs.readFileSync(path.join(dir, 'preview.tokens.json'), 'utf8'));
  if (JSON.stringify(was) !== JSON.stringify(src.theme)) fail(`${id}: preview.png shows older colours; ${rerun}`);
  const site = decodePNG(fs.readFileSync(path.join(dir, 'preview.png')));
  return { site, mod: shrink(site, 4) };
}
function shrink(c, k) {
  const out = new Canvas(Math.floor(c.w / k), Math.floor(c.h / k));
  for (let y = 0; y < out.h; y++) for (let x = 0; x < out.w; x++) {
    const sum = [0, 0, 0, 0];
    for (let j = 0; j < k; j++) for (let i = 0; i < k; i++) { const p = c.get(x * k + i, y * k + j); for (let n = 0; n < 4; n++) sum[n] += p[n]; }
    out.set(x, y, sum.map(v => Math.round(v / (k * k))));
  }
  return out;
}

function paintItem(id) {
  const src = sources.get(id), meta = metas.get(id);
  const frames = src.cape?.frames ?? src.anim?.frames ?? 1;
  const png = [];
  let model = src.model, cape = src.cape;
  if (meta.kind === 'cape') {
    if (!cape || ![4, 8].includes(cape.scale)) fail(`${id}: cape.scale must be 4 or 8`);
    if (cape.scale === 8 && frames > 1) fail(`${id}: animated capes are scale 4 (GPU budget)`);
    if (64 * cape.scale * 32 * cape.scale * 4 * frames > 4 * 1024 * 1024) fail(`${id}: cape texture over the 4 MB GPU budget`);
  } else if (meta.kind !== 'theme') {
    if (!model) fail(`${id}: model required`);
    if (![2, 4, 8].includes(model.scale)) fail(`${id}: model.scale must be 2, 4 or 8`);
    const bad = checkModel(model); if (bad.length) fail(`${id}: ${bad.join('; ')}`);
    if (model.texSize[0] * model.texSize[1] * model.scale ** 2 * 4 * frames > 4 * 1024 * 1024) fail(`${id}: texture over the 4 MB GPU budget`);
    checkClearance(id, meta, model);
  }
  for (let f = 0; f < frames; f++) {
    let c = src.paint(f);
    if (!(c instanceof Canvas)) fail(`${id}: paint(${f}) must return a Canvas`);
    if (meta.kind === 'cape') {
      const S = cape.scale;
      if (c.w === 10 * S && c.h === 16 * S) c = composeCape(c, S);
      if (c.w !== 64 * S || c.h !== 32 * S) fail(`${id}: cape frame must be ${64 * S}x${32 * S}, got ${c.w}x${c.h}`);
    } else if (meta.kind !== 'theme') {
      const w = model.texSize[0] * model.scale, h = model.texSize[1] * model.scale;
      if (c.w !== w || c.h !== h) fail(`${id}: paint(${f}) must be ${w}x${h}, got ${c.w}x${c.h}`);
    }
    png.push(c);
  }
  const sheet = new Canvas(png[0].w, png[0].h * frames);
  png.forEach((c, i) => sheet.blit(c, 0, i * png[0].h));
  return sheet;
}

// Compact, stable JSON: arrays of numbers and short leaf objects stay on one line.
function stringify(v, indent = '') {
  if (v === null || typeof v !== 'object') return JSON.stringify(v);   // a long string is still one leaf
  const inline = JSON.stringify(v);
  const leafish = x => x === null || typeof x !== 'object' || (Array.isArray(x) ? x.every(leafish) : Object.values(x).every(leafish));
  if (leafish(v) && inline.length <= 100) return inline;
  const pad = indent + '  ';
  if (Array.isArray(v)) return '[\n' + v.map(x => pad + stringify(x, pad)).join(',\n') + '\n' + indent + ']';
  return '{\n' + Object.entries(v).filter(([, x]) => x !== undefined).map(([k, x]) => `${pad}${JSON.stringify(k)}: ${stringify(x, pad)}`).join(',\n') + '\n' + indent + '}';
}

function itemJson(id) {
  const src = sources.get(id);
  const j = { ...metas.get(id) };
  for (const k of ['model', 'cape', 'anim', 'fx', 'theme', 'view']) if (src[k]) j[k] = src[k];
  return stringify(j) + '\n';
}

// ------------------------------------------------------------------ outputs
const outputs = new Map();    // absolute path -> string | Canvas (a PNG)
const paintIds = only ? [only] : [...sources.keys()];
if (only && !sources.has(only)) fail(`--only ${only}: no such item`);
{
  const bad = checkIdentity();
  if (bad.length) fail(`cosmetics/lib/theme.mjs no longer reproduces the main menu colours:\n  ${bad.join('\n  ')}`);
}
for (const id of paintIds) {
  const json = itemJson(id);
  const tex = metas.get(id).kind === 'theme' ? themePicture(id) : (t => ({ site: t, mod: t }))(paintItem(id));
  outputs.set(path.join(OUT_SITE, id, 'item.json'), json);
  outputs.set(path.join(OUT_SITE, id, 'texture.png'), tex.site);
  for (const out of OUT_MODS) {
    outputs.set(path.join(out, id, 'item.json'), json);
    outputs.set(path.join(out, id, 'texture.png'), tex.mod);
  }
}
const catalogItems = order.filter(id => metas.has(id)).map(id => metas.get(id));
const catalogJson = stringify({ schema: 2, items: catalogItems }) + '\n';
for (const base of [OUT_SITE, ...OUT_MODS]) outputs.set(path.join(base, 'catalog.json'), catalogJson);

const q = s => `'${String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
const row = (id, c) => `  '${id}': { name: ${q(c.name)}, cents: ${c.cents}, slot: ${q(c.slot)}, kind: ${q(c.kind)}, onSale: ${c.onSale}, plusOnly: ${c.plusOnly} },`;
outputs.set(OUT_GEN, [
  '// GENERATED by tools/cosmetics/build.mjs from cosmetics/items/*/item.mjs (+ cosmetics/pending.json) — never edit by hand.',
  '// THE price list: what Stripe charges and the store shows. onSale false = still',
  '// owned and worn by whoever bought it, no longer sold. plusOnly = Origin+ members wear it, never sold.',
  'export const CATALOG = {',
  ...order.map(id => row(id, metas.get(id) || pending[id])),
  '};',
  '',
].join('\n'));

// The store page's offline fallback (website/js/store.js PRICE_LIST): the for-sale
// part of the catalog, one line per item (a service test holds it to catalog.gen.js).
// A pending item has no blurb here, so it keeps the one already on its line.
{
  const file = path.join(ROOT, 'website/js/store.js');
  const src = fs.readFileSync(file, 'utf8');
  const start = src.indexOf('const PRICE_LIST = [\n'), end = src.indexOf('\n];', start);
  if (start < 0 || end < 0) fail('website/js/store.js: no PRICE_LIST block');
  const oldBlurb = id => (src.slice(start, end).match(new RegExp(`\\{ id: '${id}',.*?blurb: '((?:[^'\\\\]|\\\\.)*)'`)) || [])[1];
  const lines = order.map(id => [id, metas.get(id) || pending[id]])
    .filter(([, c]) => c.onSale !== false && !c.plusOnly)
    .map(([id, c]) => `  { id: '${id}', name: ${q(c.name)}, blurb: ${c.blurb != null ? q(c.blurb) : `'${oldBlurb(id) ?? ''}'`}, price: ${(c.cents / 100).toFixed(2)} },`);
  outputs.set(file, src.slice(0, start) + 'const PRICE_LIST = [\n' + lines.join('\n') + src.slice(end));
}

// -------------------------------------------------------------------- write / check
const stale = [];
const same = (file, want) => {
  if (!fs.existsSync(file)) return false;
  if (want instanceof Canvas) {
    try { const have = decodePNG(fs.readFileSync(file)); return have.w === want.w && have.h === want.h && Buffer.compare(Buffer.from(norm(have)), Buffer.from(norm(want))) === 0; } catch { return false; }
  }
  return fs.readFileSync(file, 'utf8') === want;
};
const norm = c => { const d = new Uint8ClampedArray(c.w * c.h * 4); for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) { const p = c.get(x, y); if (p[3]) d.set(p, (y * c.w + x) * 4); } return d; };
for (const [file, want] of outputs) {
  if (same(file, want)) continue;
  stale.push(path.relative(ROOT, file));
  if (CHECK) continue;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, want instanceof Canvas ? want.toPNG() : want);
}
if (CHECK) {
  if (stale.length) { console.error(`cosmetics build --check: ${stale.length} stale output(s); run node tools/cosmetics/build.mjs\n  ` + stale.join('\n  ')); process.exit(1); }
  console.log(`cosmetics build --check: ${outputs.size} outputs up to date`);
} else console.log(`cosmetics build: ${paintIds.length} item(s) painted, ${stale.length} file(s) written, catalog of ${catalogItems.length} + ${order.length - catalogItems.length} pending`);
