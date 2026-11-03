// Origin website: hero -> versions -> why Origin -> download, over the island in
// the sky (world.js). Scrolling blends the camera from one scene's pose to the
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

// Player screenshots for the "Straight from the game" section. The section
// stays hidden while this is empty. Put images in website/assets/screens/.
// e.g. { src: 'assets/screens/title-1.21.1.png', caption: 'Title screen · 1.21.1' }
const SCREENSHOTS = [];
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

// Scenes top to bottom: [section id, world pose, how much to darken the island].
// "why" darkens the island so the cards read over it.
const SCENES = [['top', 'hero', 0], ['versions', 'versions', 0.9], ['why', 'why', 0.92], ['get', 'get', 0.2]];
// From this shade on so little of the island shows that it is frozen instead of drawn
// (the last frame stays on screen): phones scroll the glass cards over a still picture.
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
    file: null, button: 'Send this page to your computer', sub: '',
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
    row.innerHTML = others.map(d => `<a href="${d.href}"><span>${d.label}</span>${badge(d)}<small>${d.note}</small></a>`).join('');
    row.hidden = false;
  }

  if (!DOWNLOAD.mac.live) return;
  for (const el of $$('[data-mac]')) el.hidden = false;
  if (mac) $('.get__steps').open = true;
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

function initVersions() {
  $('[data-vgrid]').innerHTML = VERSION_GROUPS.map((g, i) => `<article class="vcard${g.versions.length > 6 ? ' vcard--wide' : ''}" data-reveal style="--i:${i % 4}">`
    + `<header><h3>${g.title}</h3><span class="tag">${g.tag}</span></header>`
    + `<ul>${g.versions.map(v => `<li>${v}</li>`).join('')}</ul></article>`).join('');
  const count = VERSION_GROUPS.reduce((n, g) => n + g.versions.length, 0);
  for (const el of $$('[data-version-count]')) el.textContent = count;
  if (SCREENSHOTS.length) {
    $('[data-shots]').hidden = false;
    $('[data-shots-grid]').innerHTML = SCREENSHOTS.map(s => `<figure data-reveal><img src="${s.src}" alt="${s.caption}" loading="lazy"><figcaption>${s.caption}</figcaption></figure>`).join('');
  }
}

// The cards' loops only run while their card is on screen.
function initBento() {
  const io = new IntersectionObserver(es => es.forEach(e => e.target.classList.toggle('is-off', !e.isIntersecting)), { rootMargin: '80px' });
  $$('.bcard').forEach(c => { c.classList.add('is-off'); io.observe(c); });
}

// ---------------------------------------------------------------- scroll
let lastY = 0, queued = false;
function frame() {
  const H = innerHeight, y = scrollY;
  // each scene blends into the next as it scrolls up into view, finished by
  // the time its top is halfway up the screen, so the shade is already dark
  // behind the heading before anyone reads it (phones: the island sits there)
  let pose = ['hero', 'hero', 0], shade = 0;
  for (let i = 1; i < SCENES.length; i++) {
    const top = document.getElementById(SCENES[i][0]).getBoundingClientRect().top;
    if (top >= H) break;
    const t = smooth(clamp((H - top) / (H * 0.5))), [, pa, sa] = SCENES[i - 1], [, pb, sb] = SCENES[i];
    pose = [pa, pb, t]; shade = sa + (sb - sa) * t;
  }
  world?.setPose(...pose);
  world?.setCovered(shade >= COVERED);
  root.style.setProperty('--shade', shade.toFixed(3));

  // header: solid once scrolled; hides on scroll down, returns on scroll up
  const top = $('[data-top]');
  top.classList.toggle('is-solid', y > 40);
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
  const vw = document.documentElement.clientWidth, pad = parseFloat(getComputedStyle(hero).paddingLeft) || 0;
  const c = copy.getBoundingClientRect(), top = c.top + scrollY;
  const l = c.right + Math.max(16, vw * 0.01);
  const r = act ? act.getBoundingClientRect().right : vw - pad;   // the header's right edge: the island lines up with the nav
  return {
    cx: (l + r) / 2, cy: top + c.height / 2, w: Math.max(0, r - l), bandW: vw - 2 * pad,
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
  initBento();
  frame();
  addEventListener('scroll', requestFrame, { passive: true });
  addEventListener('resize', requestFrame);

  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('is-in'); io.unobserve(e.target); } }), { threshold: 0.15 });
  $$('.section__head, .get > *').forEach(el => { el.dataset.reveal = ''; });
  $$('[data-reveal]').forEach(el => io.observe(el));

  // the 3D world (fails soft to the flat sky backdrop)
  try {
    const { createWorld } = await import('./world.js');
    world = await createWorld($('#world'), { mobile: innerWidth <= 900, reducedMotion, heroZone: heroZone() });   // same 900px line as the CSS phone/tablet layout
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
