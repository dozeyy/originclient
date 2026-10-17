// Render harness for the cosmetics judge: contact sheets of every item on the
// store's own 3D renderer.
//
//   node tools/cosmetics/shots.mjs [<id> ...] [--all] [--out <dir>] [--with <id,id>]
//
// For each item writes <out>/<id>/_sheet.jpg (all views on one image), the
// individual PNGs, and texture@Nx.png (the raw texture, nearest-neighbour). Views:
// front, back, 3/4 left, 3/4 right, side, close-up, far (~8 blocks), and for
// animated items 4 frames across the loop (+ an airborne frame when the item reacts
// to flight). --with wears extra items too (e.g. a cape under wings).
// Needs Playwright + Chromium (preinstalled in the Claude sandbox; never `playwright install`).
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG } from '../../cosmetics/lib/px.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const args = process.argv.slice(2);
const opt = (name, d = null) => (args.includes(name) ? args[args.indexOf(name) + 1] : d);
const OUT = path.resolve(opt('--out', path.join(ROOT, 'cosmetics/out/v2-shots')));
const WITH = (opt('--with', '') || '').split(',').filter(Boolean);
const flagVals = new Set(['--out', '--with']);
const catalog = JSON.parse(fs.readFileSync(path.join(ROOT, 'website/cosmetics/catalog.json'), 'utf8')).items;
const ids = args.includes('--all') ? catalog.map(i => i.id) : args.filter((a, i) => !a.startsWith('--') && !flagVals.has(args[i - 1]));
if (!ids.length) { console.error('usage: shots.mjs <id> ... | --all [--out dir]'); process.exit(2); }

function loadPlaywright() {
  const require = createRequire(import.meta.url);
  try { return require('playwright'); } catch { /* global install */ }
  return require(path.join(execSync('npm root -g').toString().trim(), 'playwright'));
}
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
const server = http.createServer((req, res) => {
  const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  const [base, rel] = p.startsWith('/__viewer/') ? [path.join(ROOT, 'tools/cosmetics/viewer'), p.slice(10)] : [path.join(ROOT, 'website'), p];
  const file = path.join(base, rel);
  if (!file.startsWith(base) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' }).end(fs.readFileSync(file));
});
await new Promise(ok => server.listen(0, '127.0.0.1', ok));
const base = `http://127.0.0.1:${server.address().port}`;

const { chromium } = loadPlaywright();
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 520, height: 520 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push(String(e)));
  await page.goto(`${base}/__viewer/index.html`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  const SIZE = 480;
  await page.evaluate(([w, h]) => window.__init(w, h), [SIZE, SIZE]);

  const PI = Math.PI;
  for (const id of ids) {
    const dir = path.join(OUT, id);
    fs.mkdirSync(dir, { recursive: true });
    for (const f of fs.readdirSync(dir)) fs.rmSync(path.join(dir, f), { force: true });
    const { item, loop } = await page.evaluate(i => window.__item(i), id);
    await page.evaluate(i => window.__wear(i), [id, ...WITH]);
    const kind = item.kind, back = ['cape', 'wings', 'pack'].includes(kind), face = kind === 'mask' || kind === 'bandana';
    const home = back ? PI : 0;                                        // the side the item is meant to be seen from
    const close = back
      ? { look: kind === 'cape' ? 14 : kind === 'pack' ? 18 : 20, dist: kind === 'cape' ? 38 : kind === 'pack' ? 36 : 56 }
      : face ? { look: 29.5, dist: 22 } : { look: 31, dist: 27 };
    const body = { look: 17, dist: 76, lift: 4 };
    const T = 0.25 * loop;                                              // a mid-flap moment for the static views
    const views = [
      ['front', { ...body, yaw: 0, t: T }],
      ['back', { ...body, yaw: PI, t: T }],
      ['three-quarter left', { ...body, yaw: home - PI / 4 * (back ? -1 : 1) * -1 + 0, t: T }],
      ['three-quarter right', { ...body, yaw: home + PI / 4 * (back ? -1 : 1) * -1 + 0, t: T }],
      ['side', { ...body, yaw: -PI / 2, t: T }],
      ['close-up', { ...close, lift: 3, yaw: home + (back ? 0.5 : 0.45), t: T }],
      ['far (8 blocks)', { look: 17, dist: 128, lift: 6, yaw: home + (back ? 0.35 : 0.35), t: T }],
    ];
    // 3/4 left = the player's left side toward the camera, from the front (or the back for back items)
    views[2][1].yaw = back ? -3 * PI / 4 : -PI / 4;
    views[3][1].yaw = back ? 3 * PI / 4 : PI / 4;
    if (item.animated && item.anim?.motion?.length || item.cape?.frames > 1 || item.anim?.frames > 1) {
      for (let k = 0; k < 4; k++) views.push([`frame ${k + 1}/4`, { ...(back ? { ...body, look: 19, dist: 58 } : { ...close }), lift: 3, yaw: home + 0.5, t: (k / 4) * loop }]);
      if (item.anim?.motion?.some(m => m.airAmp)) views.push(['airborne', { ...body, look: 19, dist: 62, yaw: PI - 0.5, t: 0.25 * loop, air: 1, walk: 1 }]);
    }
    const shots = [];
    for (const [label, v] of views) {
      const url = await page.evaluate(v2 => window.__shot(v2), v);
      const file = `${String(shots.length + 1).padStart(2, '0')}-${label.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.png`;
      fs.writeFileSync(path.join(dir, file), Buffer.from(url.split(',')[1], 'base64'));
      shots.push({ label, url });
    }
    // raw texture, nearest-neighbour upscaled to >= 1024 px on its long side
    const texBuf = fs.readFileSync(path.join(ROOT, 'website/cosmetics', id, 'texture.png'));
    const tex = decodePNG(texBuf), k = Math.max(1, Math.ceil(1024 / Math.max(tex.w, tex.h)));
        fs.writeFileSync(path.join(dir, `texture@${k}x.png`), tex.upscale(k).toPNG());
    // capes: the outside face on its own (what is seen), 4x, for the sheet and for close inspection
    let sheetTex = texBuf;
    if (item.cape) {
      const S = item.cape.scale, face = tex.crop(S, S, 10 * S, 16 * S), fk = Math.max(2, Math.floor(640 / face.h));
      sheetTex = face.upscale(fk).toPNG();
      fs.writeFileSync(path.join(dir, `outside@${fk}x.png`), sheetTex);
    }
    // contact sheet, composed in the page: checkerboard under the texture so alpha reads
    const texUrl = 'data:image/png;base64,' + sheetTex.toString('base64');
    const sheet = await page.evaluate(async ({ shots, texUrl, label, cols }) => {
      const cell = 360, gap = 6, lab = 22, n = shots.length + 1, rows = Math.ceil(n / cols);
      const c = document.createElement('canvas');
      c.width = cols * (cell + gap) + gap; c.height = rows * (cell + lab + gap) + gap + 28;
      const g = c.getContext('2d');
      g.fillStyle = '#121513'; g.fillRect(0, 0, c.width, c.height);
      g.font = '600 15px monospace'; g.fillStyle = '#e8e8e8'; g.fillText(label, gap + 2, 20);
      const load = u => new Promise(ok => { const i = new Image(); i.onload = () => ok(i); i.src = u; });
      const all = [...shots, { label: 'texture (nearest)', url: texUrl, tex: true }];
      for (let i = 0; i < all.length; i++) {
        const x = gap + (i % cols) * (cell + gap), y = 28 + gap + Math.floor(i / cols) * (cell + lab + gap);
        const im = await load(all[i].url);
        g.fillStyle = '#9a9f9c'; g.font = '12px monospace'; g.fillText(all[i].label, x + 2, y + 14);
        if (all[i].tex) {
          const s = Math.min(cell / im.width, cell / im.height), w = im.width * s, h = im.height * s;
          for (let yy = 0; yy < h; yy += 12) for (let xx = 0; xx < w; xx += 12) { g.fillStyle = ((xx + yy) / 12) % 2 ? '#3a403d' : '#2c312f'; g.fillRect(x + xx, y + lab + yy, Math.min(12, w - xx), Math.min(12, h - yy)); }
          g.imageSmoothingEnabled = false; g.drawImage(im, x, y + lab, w, h);
        } else { g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high'; g.drawImage(im, x, y + lab, cell, cell); }
      }
      return c.toDataURL('image/jpeg', 0.92);
    }, { shots, texUrl, label: `${item.id}  ${item.name}  ${item.kind}${WITH.length ? '  +' + WITH.join('+') : ''}`, cols: shots.length > 8 ? 4 : 4 });
    fs.writeFileSync(path.join(dir, '_sheet.jpg'), Buffer.from(sheet.split(',')[1], 'base64'));
    console.log(`${id}: ${path.join(dir, '_sheet.jpg')}`);
  }
} finally {
  await browser.close();
  server.close();
}
if (errors.length) { console.error('console errors:\n  ' + [...new Set(errors)].join('\n  ')); process.exitCode = 1; }
