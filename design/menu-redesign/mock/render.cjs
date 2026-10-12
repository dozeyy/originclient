// Renders mock.html's scenes to out/<round>/<scene>.png at 1920x1080 (GUI scale 3).
// node render.cjs <round> [page] [WxHxScale] [scenes]   (NODE_PATH=$(npm root -g))
// e.g. node render.cjs r3 mock3 480x270x4
const { chromium } = require('playwright');
const { join } = require('node:path');
const { mkdirSync } = require('node:fs');
const round = process.argv[2] || 'r1';
const page = process.argv[3] || 'mock';
const [W, H, S] = (process.argv[4] || '640x360x3').split('x').map(Number);
const scenes = (process.argv[5] || 'menu,settings,hud,hudfrom').split(',');
const out = join(__dirname, 'out', round);
mkdirSync(out, { recursive: true });
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: S });
  for (const s of scenes) {
    await p.goto(`file://${join(__dirname, page + '.html')}?scene=${s}`);
    await p.evaluate(() => window.ready);
    await p.screenshot({ path: join(out, `${s}.png`) });
    console.log('wrote', s);
  }
  await b.close();
})();
