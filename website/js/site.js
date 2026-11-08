// Origin website: hero -> the product (launcher + in game) -> versions -> cosmetics -> download,
// over the island in the sky (world.js). Scrolling blends the camera from one scene's pose to the
// next; the island is always drawn with its shader lighting.

// ---------------------------------------------------------------------------
// THE ONE SWITCH: flip `live` to true on release day.
const DOWNLOAD = {
  live: true,
  url: 'https://github.com/dozeyy/originclient/releases/latest/download/OriginLauncher-Setup.exe',
  // Linux (64-bit PCs only: Mojang ships no Java or game libraries for ARM Linux).
  // Names are fixed by tools/linux/package.sh and checked by check_consistency.py.
  linux: [
    { label: 'AppImage', file: 'OriginLauncher-linux-x86_64.AppImage', note: 'any distro' },
    { label: '.deb', file: 'origin-launcher_amd64.deb', note: 'Ubuntu, Debian, Mint' },
    { label: '.rpm', file: 'origin-launcher.x86_64.rpm', note: 'Fedora, openSUSE, Alma' },
    { label: '.tar.gz', file: 'OriginLauncher-linux-x64.tar.gz', note: 'unpack and run' },
  ],
  // The Mac beta (docs/RELEASING.md, launcher-release.yml mac job). `live: false` hides every
  // [data-mac] element and the Mac downloads, so the site shows Windows and Linux only.
  mac: {
    live: true,
    appleSilicon: 'https://github.com/dozeyy/originclient/releases/latest/download/Origin-Mac-AppleSilicon.dmg',
    intel: 'https://github.com/dozeyy/originclient/releases/latest/download/Origin-Mac-Intel.dmg',
  },
};
const RELEASE_FILE = f => 'https://github.com/dozeyy/originclient/releases/latest/download/' + f;
const UA = navigator.userAgent, UA_PLATFORM = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
// Phones and tablets can't run Origin, so no file is offered to them (an iPad also says "Mac":
// touch points tell it apart; Android also says "Linux").
const ON_PHONE = !!(navigator.userAgentData && navigator.userAgentData.mobile)
  || /android|iphone|ipad|ipod|mobile/i.test(UA) || (/mac/i.test(UA_PLATFORM) && navigator.maxTouchPoints > 1);
// A Chromebook runs the Linux builds once Linux is turned on in its settings.
const ON_CHROMEOS = !ON_PHONE && (/CrOS/.test(UA) || /chrome ?os/i.test(UA_PLATFORM));
const ON_LINUX = !ON_PHONE && (ON_CHROMEOS || /linux/i.test(UA_PLATFORM || UA));
const ON_MAC = DOWNLOAD.mac.live && !ON_PHONE && /mac/i.test(UA_PLATFORM);

// ---------------------------------------------------------------------------

// Every live Minecraft version, grouped exactly like the launcher's picker
// (src/OriginLauncher.App/Core/Versions/VersionCatalog.cs; supported =
// VersionManager.OriginBuilds). Keep this list, the version count in index.html
// ("26") and its meta description in step with that file.
const VERSION_GROUPS = [
  { title: '26.1 – 26.3', tag: 'The Latest Releases', versions: ['26.3', '26.2', '26.1.2', '26.1.1', '26.1'] },
  { title: '1.21 – 1.21.11', tag: 'Tricky Trials', versions: ['1.21.11', '1.21.10', '1.21.8', '1.21.7', '1.21.6', '1.21.4', '1.21.3', '1.21.2', '1.21.1', '1.21'] },
  { title: '1.20 – 1.20.4', tag: 'Trails & Tales', versions: ['1.20.4', '1.20.2', '1.20.1', '1.20'] },
  { title: '1.19.2 – 1.19.4', tag: 'The Wild Update', versions: ['1.19.4', '1.19.3', '1.19.2'] },
  { title: '1.18.2', tag: 'Caves & Cliffs II', versions: ['1.18.2'] },
  { title: '1.17.1', tag: 'Caves & Cliffs I', versions: ['1.17.1'] },
  { title: '1.16.5', tag: 'The Nether Update', versions: ['1.16.5'] },
  { title: '1.8.9', tag: 'The Bountiful Update', versions: ['1.8.9'] },
];

// Scenes top to bottom, one per section (DOM order). The island opens the page (hero), comes
// back beside the versions sheet (a low shot from the far side, island on the right) and closes
// it (get); the launcher chapter and the cosmetics shot sit on the page's own colour, so the
// island rests under them.
//   cover: how much of the page colour lies over the whole island (from COVERED on it is frozen);
//   left:  a shade from the left edge only, so text there reads while the island stays clear on
//          the right (desktop). A phone has no side to spare and any island there would sit under
//          the sheet's text, so `phone` replaces the cover (the island rests under the versions);
//   start: delays the blend until the section's top is that far down the screen (default 1 = as
//          soon as it appears). Get waits until it is well up, so the island never fades back in
//          under the cosmetics shot.
const SCENES = [
  { id: 'top', pose: 'hero', cover: 0 },
  { id: 'why', pose: 'why', cover: 1 },
  { id: 'versions', pose: 'versions', cover: 0.1, left: 0.94, phone: 1 },
  { id: 'cosmetics', pose: 'versions', cover: 1 },
  { id: 'get', pose: 'get', cover: 0.08, start: 0.7 },
];
// From this cover on so little of the island shows that it is frozen instead of drawn
// (the last frame stays on screen).
const COVERED = 0.85;

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = t => t * t * (3 - 2 * t);
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const root = document.documentElement;

let world = null;

// Which Mac: Chromium browsers say it outright (high-entropy "architecture": "arm" or "x86").
// Elsewhere the graphics chip's name can: an Intel Mac reports an Intel, AMD or NVIDIA GPU.
// Safari hides both, and then Apple Silicon (every Mac sold since 2020) stays the guess.
async function macIsIntel() {
  try {
    const hints = await navigator.userAgentData?.getHighEntropyValues?.(['architecture']);
    if (hints?.architecture) return hints.architecture === 'x86';
  } catch { /* not offered: fall through to the GPU name */ }
  try {
    const gl = document.createElement('canvas').getContext('webgl');
    const info = gl && gl.getExtension('WEBGL_debug_renderer_info');
    const gpu = info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL)) : '';
    gl?.getExtension('WEBGL_lose_context')?.loseContext();
    return /intel|amd|radeon|nvidia/i.test(gpu) && !/apple/i.test(gpu);
  } catch { return false; }
}

async function initDownload() {
  for (const a of $$('[data-download]')) {
    if (DOWNLOAD.live) {
      a.href = ON_LINUX ? RELEASE_FILE(DOWNLOAD.linux[0].file) : DOWNLOAD.url;
      a.removeAttribute('data-soon');
      a.removeAttribute('aria-disabled');
    } else {
      a.dataset.soon = '';
      a.setAttribute('aria-disabled', 'true');
      a.title = 'Download coming soon';
      a.href = '#get';
      // Inside the download section the button has nowhere further to go.
      if (a.closest('#get')) a.addEventListener('click', e => e.preventDefault());
    }
  }
  if (!DOWNLOAD.live) {
    // The HTML's own text is the live state (so the page works without JavaScript).
    $('[data-get-note]').textContent = 'Origin isn’t available to download just yet. This button lights up the day it is.';
    $('[data-get-main]').textContent = 'Download';
    $('[data-hero-label]').textContent = 'Download';
    $('[data-hero-sub]').textContent = 'Free · coming soon';
    return;
  }
  initGet(ON_PHONE ? 'phone' : ON_MAC ? (await macIsIntel() ? 'macIntel' : 'mac') : ON_CHROMEOS ? 'chromeos' : ON_LINUX ? 'linux' : 'win');
  pinMissingDownloads();
}

// Every download Origin has. `os` is the system it is for, `id` names the file so the big
// button can pick it; `badge` marks the Mac beta.
function allDownloads() {
  const list = [{ os: 'win', id: 'win', label: 'Windows', note: 'Windows 10 & 11', href: DOWNLOAD.url }];
  if (DOWNLOAD.mac.live) {
    list.push({ os: 'mac', id: 'mac', label: 'Mac', badge: 'Beta', note: 'Apple Silicon', href: DOWNLOAD.mac.appleSilicon },
      { os: 'mac', id: 'macIntel', label: 'Mac', badge: 'Beta', note: 'Intel', href: DOWNLOAD.mac.intel });
  }
  DOWNLOAD.linux.forEach((d, i) => list.push({ os: 'linux', id: i ? 'linux ' + d.label : 'linux', label: 'Linux ' + d.label, note: d.note, href: RELEASE_FILE(d.file) }));
  return list;
}

// For each kind of visitor: the file the big button is (`file`, an allDownloads id), what it
// says, and the lines around it. The Windows installer is small and fetches the rest itself,
// so no size is quoted (one would drift with every release).
const MAC_NOTE = 'Free for Mac as a beta, on macOS 15 or newer. The first open takes one extra step, shown below, and Origin keeps itself up to date.';
// `hero` is the hero's own line under its button, `also` the quiet link to the other systems.
const GET_COPY = {
  win: {
    file: 'win', button: 'Download for Windows', sub: 'Windows 10 &amp; 11 · <code>OriginLauncher-Setup.exe</code>',
    note: 'Free for Windows 10 and 11. A small installer that fetches the rest. Origin keeps itself up to date.',
    hero: 'Free · Windows 10 & 11', also: 'Also for Mac <span class="badge">Beta</span> and Linux',
  },
  mac: { file: 'mac', button: 'Download for Mac', sub: 'macOS 15 or newer · Apple Silicon (M1 and later)', note: MAC_NOTE,
    hero: 'Free · macOS 15+ · Apple Silicon', also: 'Also for Windows and Linux' },
  macIntel: { file: 'macIntel', button: 'Download for Mac', sub: 'macOS 15 or newer · Intel Mac', note: MAC_NOTE,
    hero: 'Free · macOS 15+ · Intel', also: 'Also for Windows and Linux' },
  linux: {
    file: 'linux', button: 'Download for Linux', sub: 'AppImage · any 64-bit distro',
    note: 'Free for Linux on 64-bit PCs. Make the AppImage executable and open it, and Origin keeps itself up to date.',
    hero: 'Free · AppImage · any 64-bit distro', also: 'Other Linux files, Windows and Mac <span class="badge">Beta</span>',
  },
  // Linux on a Chromebook installs from the .deb (64-bit Intel/AMD only, like every Linux build).
  chromeos: {
    file: 'linux .deb', button: 'Download for Chromebook', sub: '.deb · Chromebooks with an Intel or AMD chip',
    note: 'Turn on Linux in your Chromebook’s settings (under Developers), then open the download to install Origin.',
    hero: 'Free · .deb · Intel or AMD Chromebooks', also: 'Other systems',
  },
  // A phone or tablet has nothing to download, so the big button shares the page to open on a computer.
  phone: {
    file: null, button: 'Share this page', sub: '',
    note: 'Origin runs on Windows, Mac and Linux computers. Open originclient.org on one to download it.',
    hero: 'Free · for Windows, Mac Beta and Linux PCs', also: '', heroButton: 'Get it on your computer',
  },
};
// The small download glyph at the end of each "other systems" row.
const DOWN_GLYPH = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3v10M5.5 9 10 13.5 14.5 9M4 17h12"/></svg>';

function initGet(kind) {
  const all = allDownloads();
  const copy = GET_COPY[kind];
  const main = all.find(d => d.id === copy.file) || null;
  const badge = d => d.badge ? ` <span class="badge">${d.badge}</span>` : '';
  const mac = main?.os === 'mac';

  // Header and hero buttons: straight to the file on Windows and Linux. A Mac visitor goes to
  // the download section instead, because the first open needs the Open Anyway steps there,
  // and so does a phone, which has nothing to download.
  for (const a of $$('[data-download]')) if (!a.closest('#get')) a.href = main && !mac ? main.href : '#get';
  $('[data-hero-label]').innerHTML = (copy.heroButton || copy.button) + (main?.badge ? badge(main) : '');
  $('[data-hero-sub]').textContent = copy.hero;
  const also = $('[data-hero-also]');
  also.innerHTML = copy.also;
  also.hidden = !copy.also;
  const big = $('[data-get-main]');
  big.innerHTML = copy.button + (main ? badge(main) : '');
  if (main) big.href = main.href;
  else {
    big.href = location.href.split('#')[0];
    big.addEventListener('click', sharePage);
  }
  const sub = $('[data-get-sub]');
  sub.innerHTML = copy.sub;   // our own strings (a file name is marked up as data)
  sub.hidden = !copy.sub;
  $('[data-get-note]').textContent = copy.note;

  // Every other download under the big button: the rest of this system's files first.
  // A phone gets no list: every file is for a computer.
  if (main) {
    const others = all.filter(d => d !== main && d.os === main.os).concat(all.filter(d => d.os !== main.os));
    const row = $('[data-other-downloads]');
    row.innerHTML = others.map(d => `<a href="${d.href}"><span>${d.label}${badge(d)}</span><small>${d.note}</small>${DOWN_GLYPH}</a>`).join('');
    row.hidden = false;
    $('[data-others-wrap]').hidden = false;
  }

  // A phone's panel stays short (the note already names the systems), so at the end of the page
  // the island still has the band above it.
  $('.specs').hidden = !main;
  if (!DOWNLOAD.mac.live) return;
  for (const el of $$('[data-mac]')) el.hidden = false;
  if (mac) $('.get__steps').open = true;
  if (!main) $('.get__steps').hidden = true;   // a phone opens nothing, so no first-open steps
}

// The phone's big button: the system share sheet where there is one, else the link is copied.
async function sharePage(e) {
  e.preventDefault();
  const url = location.href.split('#')[0], sub = $('[data-get-sub]');
  try {
    if (navigator.share) { await navigator.share({ title: 'Origin Client', url }); return; }
    await navigator.clipboard.writeText(url);
    sub.textContent = 'Link copied. Paste it into a browser on your computer.';
  } catch (err) {
    if (err?.name === 'AbortError') return;   // the share sheet was closed
    sub.textContent = url;
  }
  sub.hidden = false;
}

// Every link above names a file on GitHub's "latest" release. A release appears with its Windows
// (and Linux) files first; the Mac disk images are added minutes later by a separate job, and a
// failed Mac or Linux job leaves a release without them. During that window /latest/download/<file>
// is GitHub's 404 page. So: one look at the releases list, and any file the newest release doesn't
// have links to the newest release that does. On any failure (offline, rate limit) the links stay
// as they are. The release workflow and the launchers' self-updates are untouched by this.
const RELEASES_API = 'https://api.github.com/repos/dozeyy/originclient/releases?per_page=15';
const LATEST_PREFIX = 'https://github.com/dozeyy/originclient/releases/latest/download/';

async function pinMissingDownloads() {
  const links = $$('a[href^="' + LATEST_PREFIX + '"]');
  if (!links.length) return;
  let releases;
  try {
    const res = await fetch(RELEASES_API, { headers: { Accept: 'application/vnd.github+json' } });
    if (!res.ok) return;
    releases = await res.json();
  } catch { return; }
  // launcher-v releases, newest version first (the workflow keeps Latest on the newest version).
  const version = r => (/^launcher-v(\d+)\.(\d+)\.(\d+)$/.exec(r.tag_name) || []).slice(1).map(Number);
  const list = (Array.isArray(releases) ? releases : [])
    .filter(r => !r.draft && !r.prerelease && version(r).length === 3)
    .sort((a, b) => { const x = version(a), y = version(b); return y[0] - x[0] || y[1] - x[1] || y[2] - x[2]; });
  if (!list.length) return;
  const fileUrl = (r, name) => ((r.assets || []).find(a => a.name === name) || {}).browser_download_url;
  for (const a of links) {
    const name = decodeURIComponent(a.getAttribute('href').slice(LATEST_PREFIX.length));
    if (fileUrl(list[0], name)) continue;
    const older = list.find(r => fileUrl(r, name));
    if (older) a.href = fileUrl(older, name);
  }
}

// The spec sheet: one row per family, newest first, straight from VERSION_GROUPS.
function initVersions() {
  $('[data-vrows]').innerHTML = VERSION_GROUPS.map(g => `<tr${g.versions.length > 1 ? '' : ' class="is-single"'}>`
    + `<th scope="row" class="spec__fam">${g.title}<small>${g.versions.length} ${g.versions.length > 1 ? 'releases' : 'release'}</small></th>`
    + `<td class="spec__upd">${g.tag}</td>`
    + `<td class="spec__rel"><ul aria-label="${g.title} releases">${g.versions.map(v => `<li>${v}</li>`).join('')}</ul></td></tr>`).join('');
  const count = VERSION_GROUPS.reduce((n, g) => n + g.versions.length, 0);
  for (const el of $$('[data-version-count]')) el.textContent = count;
}

// Phone menu: a sheet under the bar. Esc, a link inside it or a tap outside closes it; focus
// goes into it on open and back to the button on close.
function initMenu() {
  const btn = $('[data-menu-btn]'), sheet = $('[data-menu]');
  if (!btn || !sheet) return;
  const set = open => {
    btn.setAttribute('aria-expanded', String(open));
    sheet.hidden = !open;
    document.body.classList.toggle('menu-open', open);
    if (open) sheet.querySelector('a')?.focus();
  };
  btn.addEventListener('click', () => set(sheet.hidden));
  sheet.addEventListener('click', e => { if (e.target.closest('a')) set(false); });
  addEventListener('keydown', e => { if (e.key === 'Escape' && !sheet.hidden) { set(false); btn.focus(); } });
  addEventListener('pointerdown', e => { if (!sheet.hidden && !sheet.contains(e.target) && !btn.contains(e.target)) set(false); });
  // grown past the phone layout: the sheet has no button any more
  matchMedia('(min-width: 901px)').addEventListener('change', e => { if (e.matches) set(false); });
}

// ---------------------------------------------------------------- scroll
let queued = false;
function frame() {
  const H = innerHeight, y = scrollY, phone = innerWidth <= 900;   // the CSS phone/tablet line
  const cover = s => (phone && s.phone !== undefined ? s.phone : s.cover), left = s => (phone ? 0 : s.left || 0);
  // each scene blends into the next as it scrolls up into view, finished by
  // the time its top is halfway up the screen, so the shade is already dark
  // behind the heading before anyone reads it (phones: the island sits there)
  let pose = ['hero', 'hero', 0], shade = 0, side = 0;
  for (let i = 1; i < SCENES.length; i++) {
    const a = SCENES[i - 1], b = SCENES[i], start = H * (b.start ?? 1);
    const top = document.getElementById(b.id).getBoundingClientRect().top;
    if (top >= start) break;
    const t = smooth(clamp((start - top) / (H * 0.5)));
    pose = [a.pose, b.pose, t];
    shade = cover(a) + (cover(b) - cover(a)) * t;
    side = left(a) + (left(b) - left(a)) * t;
  }
  world?.setPose(...pose);
  world?.setCovered(shade >= COVERED);
  root.style.setProperty('--shade', shade.toFixed(3));
  root.style.setProperty('--side', side.toFixed(3));

  // header: solid once scrolled (it stays in view, so Download is always there)
  $('[data-top]').classList.toggle('is-solid', y > 40);
  for (const a of $$('.top__nav a[href^="#"]')) {
    const r = document.querySelector(a.getAttribute('href')).getBoundingClientRect();
    a.classList.toggle('is-on', r.top < H * 0.5 && r.bottom > H * 0.5);
  }
}
function requestFrame() {
  if (queued) return;
  queued = true;
  requestAnimationFrame(() => { queued = false; frame(); });
}

// ------------------------------------------------------------ hero island
// Desktop: the island is centred in the free area to the right of the
// hero copy (up to the header's right edge) and sized to fit it, so the two never
// overlap and the right side is never empty. Phone/tablet: the island sits in the band
// between the header and the copy and shrinks if that band is too short for it. All in
// viewport space (getBoundingClientRect, clientWidth = the width the canvas really has,
// without the scrollbar); y is taken at scroll 0, where the hero is, so scrolling never
// moves it.
function heroZone() {
  const hero = $('.hero'), copy = $('.hero__copy'), head = $('.top'), act = $('.top__action');
  if (!hero || !copy) return null;
  const vw = document.documentElement.clientWidth, vh = innerHeight, pad = parseFloat(getComputedStyle(hero).paddingLeft) || 0;
  const c = copy.getBoundingClientRect(), top = c.top + scrollY;
  const l = c.right + Math.max(24, vw * 0.02);
  const ar = act ? act.getBoundingClientRect().right : 0;
  const r = ar > 0 ? ar : vw - pad;   // the header's right edge: the island lines up with the nav
  // The opening shot: the island big in the free area beside the copy, centred a touch below the
  // middle of the screen, never so big that it reaches the header. Its visible shape is about half
  // its bounding sphere, hence the generous sizes (maxH: sphere radius as a share of the screen
  // height; kMin: how far the camera may come in; phones: grow past the band, drop a few px).
  return {
    cx: (l + r) / 2, cy: vh * 0.52, w: Math.max(0, r - l), maxH: 0.42, kMin: 0.35, grow: 1.35, drop: 6, bandW: vw - 2 * pad,
    // (the header may be slid away when resized mid-page, hence the floor)
    top: Math.max(head ? head.getBoundingClientRect().bottom : 0, 62) + 4, bottom: top - 14,
  };
}
// Resize storms and font loads only mark the zone dirty; it is measured once, on the next frame.
let zoneDirty = false;
function syncHeroZone() {
  if (zoneDirty) return;
  zoneDirty = true;
  requestAnimationFrame(() => { zoneDirty = false; const z = heroZone(); if (world && z) world.setHeroZone(z); });
}

// ------------------------------------------------------------------ boot
async function boot() {
  initDownload();
  initVersions();
  initMenu();
  frame();
  addEventListener('scroll', requestFrame, { passive: true });
  addEventListener('resize', requestFrame);


  // the 3D world (fails soft to the flat sky backdrop)
  try {
    const { createWorld } = await import('./world.js');
    // The island always glides, floats and sways, whatever the OS reduced-motion setting says: with it on, the
    // camera snapped from pose to pose while scrolling down the island and nothing floated (Will, 2026-11-05:
    // "looks dumb, revert it"). Other page animations still respect reduced motion.
    world = await createWorld($('#world'), { mobile: innerWidth <= 900, reducedMotion: false, heroZone: heroZone() });   // same 900px line as the CSS phone/tablet layout
    addEventListener('resize', syncHeroZone);
    // a scrollbar coming or going shifts the layout without a resize event
    if (window.ResizeObserver) { let cw = document.documentElement.clientWidth; new ResizeObserver(() => { const w = document.documentElement.clientWidth; if (w !== cw) { cw = w; syncHeroZone(); } }).observe(document.documentElement); }
    document.fonts?.ready.then(syncHeroZone).catch(() => {});
    frame();
  } catch (err) {
    console.warn('Origin: 3D world unavailable, using the flat backdrop.', err);
    document.body.classList.add('no-webgl');
  }
}
boot();
