// Browser smoke test for originclient.org (website/), run by .github/workflows/website-check.yml.
// Serves website/ the way Vercel does (clean URLs + the headers from vercel.json, so a CSP that
// blocks the site's own scripts or services fails here) and opens every page in headless
// Chromium at phone to desktop widths.
//
//   node tools/website/smoke.mjs [--shots <dir>] [--chrome <path>] [--site <dir>]
//
// Fails on: any console error or page error (CSP refusals included), sideways scrolling, and
// the behaviours the bug sweep fixed (island stops drawing when covered, keeps moving even with reduced motion,
// download section per system, Checkout reachable on a laptop with a hat worn).
// Requests to other hosts are answered with 503 here, so the result never depends on the network
// (and the Store's "service down" path is what gets exercised).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const args = process.argv.slice(2);
const opt = name => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null; };
// --site <dir> tests another copy of the site (e.g. an older one, to see a check catch its bug).
const SITE = path.resolve(opt('--site') || path.join(path.dirname(fileURLToPath(import.meta.url)), '../../website'));
const SHOTS = opt('--shots');
const WIDTHS = [[320, 640], [390, 844], [768, 1024], [1366, 768], [1440, 900]];
const PAGES = ['/', '/store', '/terms', '/privacy', '/ad/banner', '/404'];

// ---- a tiny Vercel: cleanUrls + vercel.json headers (each source is a path-to-regexp pattern
// that is also a valid JavaScript regex, matched against the whole path).
const vercel = JSON.parse(fs.readFileSync(path.join(SITE, 'vercel.json'), 'utf8'));
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.png': 'image/png', '.webp': 'image/webp', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.txt': 'text/plain', '.xml': 'application/xml' };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  let file = path.join(SITE, decodeURIComponent(url.pathname));
  if (!file.startsWith(SITE)) { res.writeHead(403).end(); return; }
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
  else if (!path.extname(file) && fs.existsSync(file + '.html')) file += '.html';
  for (const rule of vercel.headers || []) {
    if (new RegExp('^' + rule.source + '$').test(url.pathname)) for (const h of rule.headers) res.setHeader(h.key, h.value);
  }
  if (!fs.existsSync(file)) {   // like Vercel: a missing page gets 404.html (status 404), a missing file a bare 404
    const page = path.join(SITE, '404.html');
    if (!path.extname(url.pathname) && fs.existsSync(page)) { res.writeHead(404, { 'Content-Type': 'text/html' }).end(fs.readFileSync(page)); return; }
    res.writeHead(404).end('not found'); return;
  }
  res.setHeader('Content-Type', TYPES[path.extname(file)] || 'application/octet-stream');
  res.end(fs.readFileSync(file));
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({ executablePath: opt('--chrome') || undefined, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const failures = [];
const fail = (where, what) => { failures.push(`${where}: ${what}`); console.log(`FAIL ${where}: ${what}`); };

// Counts requestAnimationFrame calls, so a test can tell whether the island is still drawing.
const COUNT_RAF = () => {
  window.__raf = 0;
  const raf = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = cb => { window.__raf++; return raf(cb); };
};

async function open(pathname, { width, height, ua, uaData, reducedMotion, where }) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, userAgent: ua, reducedMotion: reducedMotion ? 'reduce' : 'no-preference' });
  await ctx.route(u => !u.href.startsWith(BASE), r => r.fulfill({ status: 503, body: '' }));
  const page = await ctx.newPage();
  await page.addInitScript(COUNT_RAF);
  if (uaData) await page.addInitScript(d => {
    Object.defineProperty(navigator, 'userAgentData', { get: () => ({ platform: d.platform, mobile: d.mobile, getHighEntropyValues: async () => ({ architecture: d.arch }) }) });
  }, uaData);
  page.on('console', m => {
    // Requests to other hosts are refused on purpose (503 above); those lines aren't site faults.
    if (m.type() === 'error' && !/Failed to load resource: the server responded with a status of 503/.test(m.text())) fail(where, `console: ${m.text()}`);
  });
  page.on('pageerror', e => fail(where, `page error: ${e.message}`));
  await page.goto(BASE + pathname, { waitUntil: 'load' });
  await page.waitForTimeout(1200);
  return { ctx, page };
}

const sideways = page => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
const rafIn = async (page, ms) => { const a = await page.evaluate(() => window.__raf); await page.waitForTimeout(ms); return (await page.evaluate(() => window.__raf)) - a; };

// ---- every page at every width
for (const p of PAGES) {
  for (const [width, height] of WIDTHS) {
    const where = `${p} @${width}`;
    const { ctx, page } = await open(p, { width, height, where });
    const over = await sideways(page);
    if (over > 0) fail(where, `scrolls sideways by ${over}px`);
    if (SHOTS) {
      fs.mkdirSync(SHOTS, { recursive: true });
      const name = (p === '/' ? 'home' : p.slice(1).replace(/\//g, '-')) + `-${width}`;
      await page.screenshot({ path: path.join(SHOTS, `${name}.png`) });
      if (p === '/') {
        await page.evaluate(() => document.getElementById('get').scrollIntoView({ behavior: 'instant' }));
        await page.waitForTimeout(900);
        await page.screenshot({ path: path.join(SHOTS, `${name}-get.png`) });
      }
    }
    await ctx.close();
  }
}

// ---- the island rests while covered; with reduced motion it still moves (Will, 2026-11-05)
{
  const where = 'island';
  const { ctx, page } = await open('/', { width: 1366, height: 768, where });
  if (await page.evaluate(() => document.body.classList.contains('no-webgl'))) console.log('note: no WebGL here, island checks skipped');
  else {
    const top = await rafIn(page, 1000);
    if (top < 5) fail(where, `the hero island should animate (${top} frames in 1s)`);
    await page.evaluate(() => document.getElementById('why').scrollIntoView({ behavior: 'instant' }));
    await page.waitForTimeout(800);
    const covered = await rafIn(page, 1500);
    if (covered > 2) fail(where, `still drawing under the Why cards (${covered} frames in 1.5s)`);
    await page.evaluate(() => document.getElementById('get').scrollIntoView({ behavior: 'instant' }));
    await page.waitForTimeout(1000);
    const back = await rafIn(page, 3000);
    console.log(`island frames: hero ${top}/s, covered ${covered}/1.5s, download ${back}/3s`);
    // A resting island asks for no frames at all (0). CI's software renderer can crawl while the
    // camera glides close past the island to this pose (1-2 frames/s seen there), so any drawing
    // counts as resumed.
    if (back < 2) fail(where, `didn't resume drawing at the download section (${back} frames in 3s)`);
  }
  await ctx.close();
  const rm = await open('/', { width: 390, height: 844, reducedMotion: true, where: 'island reduced motion' });
  if (!(await rm.page.evaluate(() => document.body.classList.contains('no-webgl')))) {
    await rm.page.waitForTimeout(1000);
    const still = await rafIn(rm.page, 1500);
    console.log(`island frames with reduced motion: ${still}/1.5s`);
    // DECISIONS 2026-11-05: the island glides, floats and sways whatever the OS reduced-motion setting says.
    if (still < 5) fail('island reduced motion', `should keep moving with reduced motion (${still} frames in 1.5s)`);
  }
  await rm.ctx.close();
}

// ---- the download section for each kind of visitor
const MAC_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const cases = [
  { name: 'windows', ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    uaData: { platform: 'Windows', mobile: false, arch: 'x86' }, button: 'Download for Windows', file: 'OriginLauncher-Setup.exe' },
  { name: 'mac arm', ua: MAC_UA, uaData: { platform: 'macOS', mobile: false, arch: 'arm' }, button: 'Download for Mac', file: 'Origin-Mac-AppleSilicon.dmg' },
  { name: 'mac intel', ua: MAC_UA, uaData: { platform: 'macOS', mobile: false, arch: 'x86' }, button: 'Download for Mac', file: 'Origin-Mac-Intel.dmg' },
  { name: 'linux', ua: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    uaData: { platform: 'Linux', mobile: false, arch: 'x86' }, button: 'Download for Linux', file: 'OriginLauncher-linux-x86_64.AppImage' },
  { name: 'chromebook', ua: 'Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    uaData: { platform: 'Chrome OS', mobile: false, arch: 'x86' }, button: 'Download for Chromebook', file: 'origin-launcher_amd64.deb' },
  { name: 'iphone', ua: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
    width: 390, height: 844, button: 'Share this page', file: null },
  { name: 'android', ua: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
    uaData: { platform: 'Android', mobile: true, arch: '' }, width: 390, height: 844, button: 'Share this page', file: null },
  { name: 'small phone', ua: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
    width: 320, height: 568, button: 'Share this page', file: null },
];
for (const c of cases) {
  const where = `download ${c.name}`;
  const { ctx, page } = await open('/', { width: c.width || 1366, height: c.height || 768, ua: c.ua, uaData: c.uaData, where });
  const got = await page.evaluate(() => ({
    text: document.querySelector('[data-get-main]').textContent.trim(),
    href: document.querySelector('[data-get-main]').href,
    header: document.querySelector('.top__action').href,
    others: document.querySelectorAll('[data-other-downloads] a').length,
    macSteps: !document.querySelector('.get__steps').hidden,
    right: document.querySelector('[data-get-main]').getBoundingClientRect().right,
    width: document.documentElement.clientWidth,
  }));
  if (got.right > got.width) fail(where, 'the big button runs off the screen');
  if (!got.text.startsWith(c.button)) fail(where, `big button says "${got.text}"`);
  if (c.file && !got.href.endsWith('/' + c.file)) fail(where, `big button is ${got.href}`);
  if (!c.file) {
    if (/releases/.test(got.href) || /releases/.test(got.header)) fail(where, 'a phone is handed a download file');
    if (got.others) fail(where, 'a phone is shown the computer downloads');
    if (got.macSteps) fail(where, 'a phone is shown the Mac first-open steps');
  }
  if (SHOTS && !c.file) {
    await page.evaluate(() => document.getElementById('get').scrollIntoView({ behavior: 'instant' }));
    await page.waitForTimeout(900);
    await page.screenshot({ path: path.join(SHOTS, `home-${c.name}-get.png`) });
  }
  await ctx.close();
}

// ---- Store: Checkout stays on screen on laptop windows with a hat worn
for (const [width, height] of [[1366, 768], [1280, 720], [1440, 900]]) {
  const where = `store checkout @${width}x${height}`;
  const { ctx, page } = await open('/store', { width, height, where });
  await page.click('[data-filter="wear"]');
  const tries = page.locator('[data-try]');
  for (let i = 0; i < Math.min(2, await tries.count()); i++) await tries.nth(i).click();
  await page.waitForTimeout(300);
  await page.evaluate(() => window.scrollTo({ top: document.documentElement.scrollHeight / 2, behavior: 'instant' }));
  await page.waitForTimeout(500);
  const r = await page.evaluate(() => {
    const b = document.querySelector('[data-checkout]').getBoundingClientRect();
    return { top: b.top, bottom: b.bottom, h: innerHeight, adjust: !document.querySelector('[data-adjust]').hidden };
  });
  if (!r.adjust) fail(where, 'the Adjust panel did not open for a hat');
  if (r.bottom > r.h || r.top < 0) fail(where, `Checkout is off screen (top ${Math.round(r.top)}, bottom ${Math.round(r.bottom)}, window ${r.h})`);
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, `store-checkout-${width}x${height}.png`) });
  await ctx.close();
}

await browser.close();
server.close();
console.log(failures.length ? `website smoke: ${failures.length} problem(s)` : 'website smoke: ok');
process.exit(failures.length ? 1 : 0);
