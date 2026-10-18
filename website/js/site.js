// Origin website: hero -> versions -> why Origin -> download, over the island in
// the sky (world.js). Scrolling blends the camera from one scene's pose to the
// next; the island is always drawn with its shader lighting.

// ---------------------------------------------------------------------------
// THE ONE SWITCH: flip `live` to true on release day.
const DOWNLOAD = {
  live: false,
  url: 'https://github.com/dozeyy/originclient/releases/latest/download/OriginLauncher-Setup.exe',
};

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

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = t => t * t * (3 - 2 * t);
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const root = document.documentElement;

let world = null;

function initDownload() {
  for (const a of $$('[data-download]')) {
    if (DOWNLOAD.live) {
      a.href = DOWNLOAD.url;
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
  if (DOWNLOAD.live) $('[data-get-note]').textContent = 'Free for Windows 10 and 11. Setup takes about a minute, and Origin keeps itself up to date.';
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

// The six cards' loops only run while their card is on screen.
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
// Desktop: the island and its rings are centred in the free area to the right of the
// hero copy (up to the header's right edge) and sized to fit it, so the two never
// overlap and the right side is never empty. Phone/tablet: the island sits in the band
// between the header and the copy and shrinks if that band is too short for it. Uses
// layout offsets, not scroll position, so it holds still while scrolling.
function heroZone() {
  const hero = $('.hero'), copy = $('.hero__copy'), head = $('.top'), act = $('.top__action');
  if (!hero || !copy) return null;
  const pad = parseFloat(getComputedStyle(hero).paddingLeft) || 0;
  const l = copy.offsetLeft + copy.offsetWidth + Math.max(24, innerWidth * 0.02);
  const r = act ? act.getBoundingClientRect().right : innerWidth - pad;   // the header's right edge: the island lines up with the nav
  return {
    cx: (l + r) / 2, cy: copy.offsetTop + copy.offsetHeight / 2, w: Math.max(0, r - l),
    ...(innerWidth <= 900 && { w: innerWidth - 2 * pad }),
    // (the header may be slid away when resized mid-page, hence the floor)
    top: Math.max(head ? head.getBoundingClientRect().bottom : 0, 62) + 4, bottom: copy.offsetTop - 14,
  };
}
function syncHeroZone() { const z = heroZone(); if (world && z) world.setHeroZone(z); }

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
    document.fonts?.ready.then(syncHeroZone);
    frame();
  } catch (err) {
    console.warn('Origin: 3D world unavailable, using the flat backdrop.', err);
    document.body.classList.add('no-webgl');
  }
}
boot();
