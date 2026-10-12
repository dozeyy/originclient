// Turns the store's cape paintings (website/js/cosmetics.js) into the cape
// textures the Origin mod draws in game, so the store and the game always
// match. Run after changing or adding a cape:
//
//   node tools/cosmetics/export-capes.mjs [--out <resources dir>]
//
// Needs Playwright + Chromium (preinstalled in the Claude cloud sandbox).
// Output, one PNG per cape, in Minecraft's cape layout at 4x (256x128 per
// frame; vanilla capes are 64x32). Animated capes are a vertical strip of
// FRAMES frames covering one LOOP (website/js/cosmetics.js), so frame n is
// shown for LOOP/FRAMES seconds; the mod's CapeTextures.FRAME_MS must match.
//
// Layout (4x of the vanilla 10x16x1 cape box at texture offset 0,0):
//   (4,0)  40x4  top edge        (44,0) 40x4  bottom edge
//   (0,4)  4x64  side edge       (4,4)  40x64 OUTSIDE (seen from behind)
//   (44,4) 4x64  side edge       (48,4) 40x64 inside (against the back)
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const FRAMES = 24;
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const WEBSITE = path.join(ROOT, 'website');
const argOut = process.argv.indexOf('--out');
const OUT = argOut > 0 ? path.resolve(process.argv[argOut + 1])
  : path.join(ROOT, 'src/mods/versions/1.21.1/src/client/resources/assets/originclient/textures/cosmetics/capes');

function loadPlaywright() {
  const require = createRequire(import.meta.url);
  try { return require('playwright'); } catch { /* fall through to the global install */ }
  return require(path.join(execSync('npm root -g').toString().trim(), 'playwright'));
}

// Serve website/ locally: the page imports cosmetics.js as a module.
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const file = path.join(WEBSITE, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!file.startsWith(WEBSITE) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' }).end(fs.readFileSync(file));
});
await new Promise(ok => server.listen(0, '127.0.0.1', ok));
const base = `http://127.0.0.1:${server.address().port}`;

const { chromium } = loadPlaywright();
const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  await page.goto(`${base}/store.html`, { waitUntil: 'domcontentloaded' }).catch(() => {});
  const capes = await page.evaluate(async ({ FRAMES }) => {
    const m = await import('/js/cosmetics.js');
    const S = 4, W = 64 * S, H = 32 * S;
    const out = {};
    for (const id of m.CAPE_IDS) {
      const frames = m.isAnimated(id) ? FRAMES : 1;
      const sheet = document.createElement('canvas');
      sheet.width = W; sheet.height = H * frames;
      const ctx = sheet.getContext('2d');
      ctx.imageSmoothingEnabled = false;
      for (let f = 0; f < frames; f++) {
        const y = f * H, art = m.capeArt(id, f / frames);
        ctx.fillStyle = m.capeEdge(id);
        ctx.fillRect(4, y, 40, 4); ctx.fillRect(44, y, 40, 4);
        ctx.fillRect(0, y + 4, 4, 64); ctx.fillRect(44, y + 4, 4, 64);
        ctx.drawImage(art, 4, y + 4);
        ctx.drawImage(art, 48, y + 4);
        ctx.fillStyle = 'rgba(0,0,0,.45)';            // the inside is the same art, in shade
        ctx.fillRect(48, y + 4, 40, 64);
      }
      out[id] = { frames, png: sheet.toDataURL('image/png').split(',')[1] };
    }
    return out;
  }, { FRAMES });

  fs.mkdirSync(OUT, { recursive: true });
  for (const [id, { frames, png }] of Object.entries(capes)) {
    fs.writeFileSync(path.join(OUT, `${id}.png`), Buffer.from(png, 'base64'));
    console.log(`${id}.png  ${frames} frame${frames > 1 ? 's' : ''}`);
  }
} finally {
  await browser.close();
  server.close();
}
