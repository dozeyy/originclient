// Turns the store's cape paintings (website/js/cosmetics.js) into the cape
// textures the Origin mod draws in game, so the store and the game always
// match. Run after changing or adding a cape:
//
//   node tools/cosmetics/export-capes.mjs [--out <resources dir>] [--fx <file.json>]
//
// Needs Playwright + Chromium (preinstalled in the Claude cloud sandbox).
//
// FORMAT. One PNG per cape, in Minecraft's cape layout scaled by S, where S is
// pixels per vanilla cape pixel and comes from cosmetics.js capeScale(id):
//   - static capes   S = 8  -> one frame of 512x256   (vanilla capes are 64x32)
//   - animated capes S = 4  -> a vertical strip of FRAMES (24) frames, each 256x128,
//                              covering one LOOP; frame n shows for LOOP/FRAMES s
//                              (the mod's CapeTextures.FRAME_MS must match).
// The mod derives S from the sheet itself: S = width / 64, frame height = width / 2,
// frame count = height / (width / 2). Every PNG stays within the 4 MB GPU-texture
// budget (width x height x 4 <= 4,194,304); the script refuses to write one that is not.
//
// Layout of one frame (the vanilla 10x16x1 cape box at texture offset 0,0, times S):
//   (S,0)    10S x S     top edge       (11S,0)  10S x S    bottom edge
//   (0,S)    S x 16S     side edge      (11S,S)  S x 16S    side edge
//   (S,S)    10S x 16S   OUTSIDE (seen from behind)
//   (12S,S)  10S x 16S   inside (against the back): the same art, in shade
// The four edge faces are NOT flat colours: each is a 1 px strip of the art taken just
// inside the outer line (source row/column floor(EDGE_INSET * S), EDGE_INSET from
// cosmetics.js, so the hem's border colour continues round the cape's thin edge).
// The (0,S) side takes the art's left column and the (11S,S) side its right column.
//
// --fx writes cosmetics.js FX (the particle effects table, plain data) as JSON for
// the mod's particle code.
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const FRAMES = 24;
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const WEBSITE = path.join(ROOT, 'website');
const argOut = process.argv.indexOf('--out'), argFx = process.argv.indexOf('--fx');
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
    const out = {};
    for (const id of m.CAPE_IDS) {
      const S = m.capeScale(id), W = 64 * S, H = 32 * S, A = 10 * S, B = 16 * S;
      const frames = m.isAnimated(id) ? FRAMES : 1;
      const sheet = document.createElement('canvas');
      sheet.width = W; sheet.height = H * frames;
      const ctx = sheet.getContext('2d');
      ctx.imageSmoothingEnabled = false;
      const inset = Math.floor(m.EDGE_INSET * S);
      for (let f = 0; f < frames; f++) {
        const y = f * H, art = m.capeArt(id, f / frames);
        // edges: 1 px strips of the art, stretched across the thin faces
        ctx.drawImage(art, 0, inset, A, 1, S, y, A, S);                       // top
        ctx.drawImage(art, 0, B - 1 - inset, A, 1, 11 * S, y, A, S);          // bottom
        ctx.drawImage(art, inset, 0, 1, B, 0, y + S, S, B);                   // side (left column)
        ctx.drawImage(art, A - 1 - inset, 0, 1, B, 11 * S, y + S, S, B);      // side (right column)
        ctx.drawImage(art, S, y + S);
        ctx.drawImage(art, 12 * S, y + S);
        ctx.fillStyle = 'rgba(6,6,10,.52)';             // the inside is the same art, in shade
        ctx.fillRect(12 * S, y + S, A, B);
      }
      out[id] = { frames, S, w: sheet.width, h: sheet.height, png: sheet.toDataURL('image/png').split(',')[1] };
    }
    return { capes: out, fx: m.FX };
  }, { FRAMES });

  fs.mkdirSync(OUT, { recursive: true });
  for (const [id, { frames, S, w, h, png }] of Object.entries(capes.capes)) {
    if (w * h * 4 > 4194304) throw new Error(`${id}: ${w}x${h} is over the 4 MB GPU-texture budget`);
    fs.writeFileSync(path.join(OUT, `${id}.png`), Buffer.from(png, 'base64'));
    console.log(`${id}.png  S=${S}  ${w}x${h}  ${frames} frame${frames > 1 ? 's' : ''}  ${(w * h * 4 / 1048576).toFixed(2)} MB GPU`);
  }
  if (argFx > 0) {
    fs.mkdirSync(path.dirname(path.resolve(process.argv[argFx + 1])), { recursive: true });
    fs.writeFileSync(path.resolve(process.argv[argFx + 1]), JSON.stringify(capes.fx, null, 1));
    console.log(`fx -> ${process.argv[argFx + 1]}`);
  }
} finally {
  await browser.close();
  server.close();
}
