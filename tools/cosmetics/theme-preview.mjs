// Store / Cosmetics-screen pictures for menu themes: the real mod menu, in the theme.
//
//   node tools/cosmetics/theme-preview.mjs [theme-id ...]     (default: every theme-* item)
//
// Renders the design mock the in-game menu was built from (design/menu-redesign/mock/
// mock4, scene "menu": same layout, Inter font and icon set as the game, at 1920x1080
// = GUI scale 3) with the theme's tokens swapped into the mock's CSS variables, and
// crops the menu. Writes cosmetics/items/<id>/preview.png plus preview.tokens.json (the
// tokens it was rendered with); build.mjs turns the picture into texture.png and fails
// if the tokens no longer match the item, so a stale picture can't ship.
// Needs Playwright + Chromium (preinstalled in the Claude sandbox; never `playwright install`).
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mockCss } from '../../cosmetics/lib/theme.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const MOCK = path.join(ROOT, 'design/menu-redesign/mock');
// The menu panel sits at 500,250 920x580 in the mock; a 1024x640 window (16:10) around it.
const CLIP = { x: 448, y: 220, width: 1024, height: 640 };
const SCALE = 1.5;   // 1536x960: sharp on the store's large theme view and on HiDPI screens

function loadPlaywright() {
  const require = createRequire(import.meta.url);
  try { return require('playwright'); } catch { /* global install */ }
  return require(path.join(execSync('npm root -g').toString().trim(), 'playwright'));
}

const itemsDir = path.join(ROOT, 'cosmetics/items');
const args = process.argv.slice(2);
const ids = args.length ? args : fs.readdirSync(itemsDir).filter(d => d.startsWith('theme-'));
execSync(`python3 ${JSON.stringify(path.join(MOCK, 'build.py'))} mock4`, { stdio: 'ignore' });

const { chromium } = loadPlaywright();
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: SCALE });
for (const id of ids) {
  const item = (await import(pathToFileURL(path.join(itemsDir, id, 'item.mjs')).href)).default;
  if (!item.theme) throw new Error(`${id}: not a theme item`);
  const vars = mockCss(item.theme);
  await page.goto(pathToFileURL(path.join(MOCK, 'mock4.html')).href + '?scene=menu');
  await page.evaluate(() => window.ready);
  // The mock hard-codes three colours the game takes from tokens; point them at the tokens too.
  await page.addStyleTag({ content: `:root{${Object.entries(vars).map(([k, v]) => `${k}:${v}`).join(';')}}
    .hedit{color:var(--on-acc)} .card.on .st{color:var(--on-text)} .card.off .st{background:var(--off-soft);color:var(--off)}` });
  await page.evaluate(() => document.fonts.ready);
  const png = await page.screenshot({ clip: CLIP, omitBackground: true });
  fs.writeFileSync(path.join(itemsDir, id, 'preview.png'), png);
  fs.writeFileSync(path.join(itemsDir, id, 'preview.tokens.json'), JSON.stringify(item.theme, null, 2) + '\n');
  console.log(`${id}: preview.png ${Math.round(png.length / 1024)} KB`);
}
await browser.close();
