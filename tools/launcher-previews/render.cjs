// Renders the home banner's preview art (scenes.html) and the website preview
// into src/OriginLauncher.App/Assets/Promos/. Run from the repo root:
//   node tools/launcher-previews/render.cjs
// Uses the Playwright + Chromium already on the machine (a global install is
// fine: CommonJS require honours NODE_PATH, e.g. NODE_PATH=$(npm root -g)).
// Output is JPEG at 2x the banner card's size (800x200).
const { chromium } = require('playwright');
const { createServer } = require('node:http');
const { readFile, mkdir } = require('node:fs/promises');
const { extname, join, resolve } = require('node:path');

const root = resolve(__dirname, '../..');
const out = join(root, 'src/OriginLauncher.App/Assets/Promos');
const scenes = ['menu', 'map', 'hud', 'chat', 'shaders'];

// The website needs http (module scripts, fetch), so serve website/ locally.
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.json': 'application/json' };
const site = createServer(async (req, res) => {
  try {
    let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p.endsWith('/')) p += 'index.html';
    const body = await readFile(join(root, 'website', p));
    res.writeHead(200, { 'content-type': types[extname(p)] ?? 'application/octet-stream' }).end(body);
  } catch { res.writeHead(404).end(); }
}).listen(0);
const port = site.address().port;

(async () => {
await mkdir(out, { recursive: true });
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 400, height: 100 }, deviceScaleFactor: 2 });
  for (const s of scenes) {
    await page.goto(`file://${join(root, 'tools/launcher-previews/scenes.html')}?scene=${s}`);
    await page.waitForFunction(() => window.ready).catch(() => {});
    await page.evaluate(() => window.ready);
    await page.screenshot({ path: join(out, `${s}.jpg`), type: 'jpeg', quality: 86 });
    console.log('wrote', s);
  }

  // The website card: the live site's hero, framed on its right-hand island.
  const web = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
  await web.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'networkidle' });
  // Only the island and sky: the launcher lays its own text over the left.
  await web.addStyleTag({ content: '.top, .hero__copy, .hero__hint { visibility: hidden !important; }' });
  await web.waitForTimeout(2500);   // three.js island fades in
  await web.screenshot({ path: join(out, 'website.jpg'), type: 'jpeg', quality: 86,
    clip: { x: 320, y: 190, width: 960, height: 240 } });
  console.log('wrote website');
} finally {
  await browser.close();
  site.close();
}
})();
