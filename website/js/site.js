// Origin website, told as one film of the island in the sky (world.js): hero -> the
// launcher -> versions -> in game -> download. Each chapter is a camera shot; scrolling
// blends the camera from one shot to the next, and the shade layers keep text legible.

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

// Shots top to bottom (DOM order): [section id, world pose, shade left, shade right, shade all].
// Text sits on the darkened side and the island fills the other; "why" (in game) is the cut
// inside: the whole frame goes dark and the real in-game stills take over.
const SCENES = [
  ['top', 'hero', 0, 0, 0],
  ['launcher', 'launcher', 0, 0.9, 0.12],
  ['versions', 'versions', 0.9, 0, 0.1],
  ['why', 'why', 0, 0, 0.94],
  ['get', 'get', 0.86, 0, 0.06],
];
// From this "shade all" on so little of the island shows that it is frozen instead of drawn
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
// The hero's line under its button, per visitor (the button itself says what GET_COPY says).
const HERO_SUB = {
  win: 'Free · Windows 10 & 11 · also for <a href="#get">Mac (Beta) and Linux</a>',
  mac: 'Free · Mac Beta, macOS 15 or newer · <a href="#get">first-open steps and other systems</a>',
  macIntel: 'Free · Mac Beta, macOS 15 or newer · <a href="#get">first-open steps and other systems</a>',
  linux: 'Free · AppImage for any 64-bit distro · <a href="#get">.deb, .rpm and the rest</a>',
  chromeos: 'Free · for Chromebooks with Linux turned on · <a href="#get">all downloads</a>',
  phone: 'Free for Windows, Mac (Beta) and Linux computers.',
};
const MAC_NOTE = 'Free for Mac as a beta, on macOS 15 or newer. The first open takes one extra step, shown below, and Origin keeps itself up to date.';
const GET_COPY = {
  win: {
    file: 'win', button: 'Download for Windows', sub: 'Windows 10 & 11 · a small installer that fetches the rest',
    note: 'Free for Windows 10 and 11. Setup takes about a minute, and Origin keeps itself up to date.',
  },
  mac: { file: 'mac', button: 'Download for Mac', sub: 'macOS 15 or newer · Apple Silicon (M1 and later)', note: MAC_NOTE },
  macIntel: { file: 'macIntel', button: 'Download for Mac', sub: 'macOS 15 or newer · Intel Mac', note: MAC_NOTE },
  linux: {
    file: 'linux', button: 'Download for Linux', sub: 'AppImage · any 64-bit distro',
    note: 'Free for Linux on 64-bit PCs. Make the AppImage executable and open it, and Origin keeps itself up to date.',
  },
  // Linux on a Chromebook installs from the .deb (64-bit Intel/AMD only, like every Linux build).
  chromeos: {
    file: 'linux .deb', button: 'Download for Chromebook', sub: '.deb · Chromebooks with an Intel or AMD chip',
    note: 'Turn on Linux in your Chromebook’s settings (under Developers), then open the download to install Origin.',
  },
  // A phone or tablet has nothing to download, so the big button shares the page to open on a computer.
  phone: {
    file: null, button: 'Share this page', sub: '',
    note: 'Origin is a launcher for Windows, Mac and Linux computers. Open originclient.org on your computer to download it.',
  },
};

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
  const hero = $('[data-hero-main]');
  if (hero) {
    hero.innerHTML = (main ? copy.button : 'Get it on your computer') + (main ? badge(main) : '');
    $('[data-hero-sub]').innerHTML = HERO_SUB[kind];
  }
  const big = $('[data-get-main]');
  big.innerHTML = copy.button + (main ? badge(main) : '');
  if (main) big.href = main.href;
  else {
    big.href = location.href.split('#')[0];
    big.addEventListener('click', sharePage);
  }
  const sub = $('[data-get-sub]');
  sub.textContent = copy.sub;
  sub.hidden = !copy.sub;
  $('[data-get-note]').textContent = copy.note;

  // Every other download under the big button: the rest of this system's files first.
  // A phone gets no list: every file is for a computer.
  if (main) {
    const others = all.filter(d => d !== main && d.os === main.os).concat(all.filter(d => d.os !== main.os));
    const row = $('[data-other-downloads]');
    row.innerHTML = others.map(d => `<a href="${d.href}"><span>${d.label}${badge(d)}</span><small>${d.note}</small></a>`).join('');
    row.hidden = false;
    $('[data-others-title]').hidden = false;
  }

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

// The versions chapter: one spec-sheet row per family (range, update name, every release in it).
function initVersions() {
  // A one-release family is its own range, so it shows no list (it would repeat the range).
  $('[data-vlist]').innerHTML = VERSION_GROUPS.map(g => `<li class="vrow${g.versions.length === 1 ? ' vrow--one' : ''}"><span class="vrow__range">${g.title}</span>`
    + `<span class="vrow__name">${g.tag}</span>`
    + (g.versions.length > 1 ? `<ul class="vrow__list" aria-label="${g.tag}: ${g.versions.join(', ')}">${g.versions.map(v => `<li>${v}</li>`).join('')}</ul>` : '') + '</li>').join('');
  const count = VERSION_GROUPS.reduce((n, g) => n + g.versions.length, 0);
  for (const el of $$('[data-version-count]')) el.textContent = count;
}

// In game: the last caption whose heading has passed the middle of the screen picks the still in
// the sticky frame, so the caption you are reading is level with the frame showing it. Measured on
// the scroll frame (not an IntersectionObserver band, which a jump or Page Down can skip over).
// Phones show each still inline under its caption instead; the frame is hidden there.
let beatHeads = [], beatStills = [], beatOn = -1;
function initBeats() {
  beatHeads = $$('[data-beat] h3');
  beatStills = $$('[data-still]');
}
function updateBeats(H) {
  if (!beatHeads.length) return;
  let on = 0;
  beatHeads.forEach((h, i) => { if (h.getBoundingClientRect().top < H * 0.56) on = i; });
  if (on === beatOn) return;
  beatOn = on;
  beatHeads.forEach((h, i) => h.parentElement.classList.toggle('is-on', i === on));
  beatStills.forEach((s, i) => s.classList.toggle('is-on', i === on));
}

// The phone menu: a full-screen sheet. Esc, Close or picking a link closes it; focus goes in
// and comes back to the Menu button.
function initMenu() {
  const btn = $('[data-menu]'), top = $('[data-top]'), nav = $('[data-nav]');
  if (!btn) return;
  const set = open => {
    top.classList.toggle('is-open', open);
    document.body.classList.toggle('menu-open', open);
    // the page behind the sheet can't be tabbed into while it is open
    for (const el of $$('main, .foot')) el.inert = open;
    btn.setAttribute('aria-expanded', String(open));
    if (open) nav.querySelector('a')?.focus();
  };
  btn.addEventListener('click', () => set(btn.getAttribute('aria-expanded') !== 'true'));
  nav.addEventListener('click', e => { if (e.target.closest('a') && top.classList.contains('is-open')) set(false); });
  addEventListener('keydown', e => { if (e.key === 'Escape' && top.classList.contains('is-open')) { set(false); btn.focus(); } });
  // the sheet only exists on phones: widening the window closes it
  matchMedia('(min-width: 901px)').addEventListener('change', m => { if (m.matches) set(false); });
}

// ---------------------------------------------------------------- scroll
let lastY = 0, queued = false;
function frame() {
  const H = innerHeight, y = scrollY;
  // each scene blends into the next as it scrolls up into view, finished by
  // the time its top is halfway up the screen, so the shade is already dark
  // behind the heading before anyone reads it (phones: the island sits there)
  let pose = ['hero', 'hero', 0], shade = [0, 0, 0];
  for (let i = 1; i < SCENES.length; i++) {
    const top = document.getElementById(SCENES[i][0]).getBoundingClientRect().top;
    if (top >= H) break;
    const t = smooth(clamp((H - top) / (H * 0.5))), a = SCENES[i - 1], b = SCENES[i];
    pose = [a[1], b[1], t];
    shade = [2, 3, 4].map(k => a[k] + (b[k] - a[k]) * t);
  }
  world?.setPose(...pose);
  world?.setCovered(shade[2] >= COVERED);
  root.style.setProperty('--sl', shade[0].toFixed(3));
  root.style.setProperty('--sr', shade[1].toFixed(3));
  root.style.setProperty('--sf', shade[2].toFixed(3));
  updateBeats(H);

  // header: solid once scrolled; hides on scroll down, returns on scroll up
  const top = $('[data-top]');
  top.classList.toggle('is-solid', y > 40);
  if (top.classList.contains('is-open')) { lastY = y; return; }
  if (y > lastY + 1 && y > H) top.classList.add('is-hidden');
  else if (y < lastY - 1 || y <= H) top.classList.remove('is-hidden');
  lastY = y;
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
  // middle of the screen (the copy sits low), never so big that it reaches the header.
  return {
    // The island's visible shape is about half its bounding sphere, hence the generous sizes.
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
  initBeats();
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
