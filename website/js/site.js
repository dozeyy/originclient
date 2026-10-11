// Origin website: hero -> versions -> shaders -> download, over the island in
// the sky (world.js). Scrolling blends the camera from one scene's pose to the
// next; shaders are on the whole way down.

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

// Every live Minecraft version, grouped like the launcher's picker
// (VersionCatalog.cs; supported = VersionManager.OriginBuilds).
const VERSION_GROUPS = [
  { title: '1.21 – 1.21.11', tag: 'Tricky Trials', versions: ['1.21.11', '1.21.10', '1.21.8', '1.21.7', '1.21.6', '1.21.4', '1.21.3', '1.21.2', '1.21.1', '1.21'] },
  { title: '1.20 – 1.20.4', tag: 'Trails & Tales', versions: ['1.20.4', '1.20.2', '1.20.1', '1.20'] },
  { title: '1.19 – 1.19.4', tag: 'The Wild Update', versions: ['1.19.4', '1.19.3', '1.19.2'] },
  { title: '1.18.2', tag: 'Caves & Cliffs II', versions: ['1.18.2'] },
  { title: '1.17.1', tag: 'Caves & Cliffs I', versions: ['1.17.1'] },
  { title: '1.16.5', tag: 'The Nether Update', versions: ['1.16.5'] },
  { title: '26.x', tag: 'The Latest Releases', versions: ['26.2'], dev: true },
];

// Scenes top to bottom: [section id, world pose, how much to darken the island].
const SCENES = [['top', 'hero', 0], ['versions', 'versions', 0.62], ['shaders', 'shaders', 0], ['get', 'get', 0.2]];

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = t => t * t * (3 - 2 * t);
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const root = document.documentElement;

// Shaders are on for the whole experience; the switch in the Shaders section
// lets a player compare against plain Minecraft.
let world = null, shaders = true;

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
  $('[data-vgrid]').innerHTML = VERSION_GROUPS.map(g => `<article class="vcard${g.dev ? ' vcard--dev' : ''}" data-reveal>`
    + `<header><h3>${g.title}</h3><span class="tag">${g.dev ? 'In development' : g.tag}</span></header>`
    + `<ul>${g.versions.map(v => `<li${g.dev ? ' class="dev"' : ''}>${v}</li>`).join('')}</ul></article>`).join('');
  if (SCREENSHOTS.length) {
    $('[data-shots]').hidden = false;
    $('[data-shots-grid]').innerHTML = SCREENSHOTS.map(s => `<figure data-reveal><img src="${s.src}" alt="${s.caption}" loading="lazy"><figcaption>${s.caption}</figcaption></figure>`).join('');
  }
}

function initShaders() {
  const sw = $('[data-shaders]');
  const paint = () => {
    sw.setAttribute('aria-checked', String(shaders));
    root.style.setProperty('--sunset', shaders ? 1 : 0);
  };
  paint();
  sw.addEventListener('click', () => {
    shaders = !shaders;
    paint();
    world?.setShaders(shaders);
  });
}

// ---------------------------------------------------------------- scroll
let lastY = 0, queued = false;
function frame() {
  const H = innerHeight, y = scrollY;
  // each scene blends into the next as it scrolls up into view
  let pose = ['hero', 'hero', 0], shade = 0;
  for (let i = 1; i < SCENES.length; i++) {
    const top = document.getElementById(SCENES[i][0]).getBoundingClientRect().top;
    if (top >= H) break;
    const t = smooth(clamp(1 - top / H)), [, pa, sa] = SCENES[i - 1], [, pb, sb] = SCENES[i];
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
  for (const a of $$('.top__nav a')) {
    const r = document.querySelector(a.getAttribute('href')).getBoundingClientRect();
    a.classList.toggle('is-on', r.top < H * 0.5 && r.bottom > H * 0.5);
  }
}
function requestFrame() {
  if (queued) return;
  queued = true;
  requestAnimationFrame(() => { queued = false; frame(); });
}

// ------------------------------------------------------------------ boot
async function boot() {
  initDownload();
  initVersions();
  initShaders();
  frame();
  addEventListener('scroll', requestFrame, { passive: true });
  addEventListener('resize', requestFrame);

  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('is-in'); io.unobserve(e.target); } }), { threshold: 0.15 });
  $$('.section__head, .shaders__copy, .get > *').forEach(el => { el.dataset.reveal = ''; });
  $$('[data-reveal]').forEach(el => io.observe(el));

  // the 3D world (fails soft to the flat sky backdrop)
  try {
    const { createWorld } = await import('./world.js');
    world = await createWorld($('#world'), { mobile: innerWidth < 900, reducedMotion, shaders });
    frame();
  } catch (err) {
    console.warn('Origin: 3D world unavailable, using the flat backdrop.', err);
    document.body.classList.add('no-webgl');
  }
}
boot();
