// Origin website: the scroll story.
//
// The page is one long "download journey": hero -> Install (the real Origin
// Setup window, step by step) -> Launch (the launcher's Home) -> versions ->
// shaders -> download. Each pinned chapter maps its scroll progress (0..1)
// onto the replica's state; world.js renders the island in the sky behind
// everything, with shaders on the whole way down.

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
const ALL_VERSIONS = VERSION_GROUPS.filter(g => !g.dev).flatMap(g => g.versions);

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = t => t * t * (3 - 2 * t);
const span = (p, a, b) => clamp((p - a) / (b - a));
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const isMobile = () => innerWidth < 900;
const root = document.documentElement;

// ===================================================================== setup
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

// The launcher/installer OrbitMark: three ellipses, each turning on its own period.
function initOrbits() {
  for (const o of $$('.orbit')) {
    o.innerHTML = '<svg viewBox="0 0 200 200" fill="none" stroke="currentColor" aria-hidden="true">'
      + '<g><ellipse cx="100" cy="100" rx="90" ry="32"/></g>'
      + '<g style="rotate:60deg"><ellipse cx="100" cy="100" rx="75" ry="27"/></g>'
      + '<g style="rotate:120deg"><ellipse cx="100" cy="100" rx="60" ry="22"/></g></svg>';
    // keep each ring's starting angle while the animation adds its spin
    $$('g', o).forEach((g, i) => { g.style.rotate = ''; g.style.animationDelay = `${-[0, 60, 120][i] / 360 * [26, 34, 42][i]}s`; });
  }
}

// Scale the fixed-size window replicas to fit their frame.
function fitFrames() {
  for (const f of $$('[data-fit]')) {
    const [w, h] = f.dataset.fit.split('x').map(Number);
    const r = f.getBoundingClientRect();
    const s = Math.min(r.width / w, r.height / h, 1.15);
    f.style.setProperty('--s', s.toFixed(4));
  }
}

// ============================================================ cursor helper
// Moves a fake cursor through keyframes [{p, el|xy}] inside a scaled window.
function cursorPath(win, cursor, keys, p) {
  if (!keys.length || p < keys[0].p || p > keys[keys.length - 1].p + 0.02) { cursor.style.opacity = 0; return; }
  const wr = win.getBoundingClientRect(), s = wr.width / win.offsetWidth;
  const at = k => {
    if (k.xy) return k.xy;
    const r = $(k.el, win).getBoundingClientRect();
    return [(r.left - wr.left + r.width * (k.fx ?? 0.55)) / s, (r.top - wr.top + r.height * (k.fy ?? 0.6)) / s];
  };
  let i = 0;
  while (i < keys.length - 1 && p > keys[i + 1].p) i++;
  const a = keys[i], b = keys[Math.min(i + 1, keys.length - 1)];
  const t = a === b ? 0 : smooth(span(p, a.p, b.p));
  const [ax, ay] = at(a), [bx, by] = at(b);
  cursor.style.opacity = 1;
  cursor.style.translate = `${ax + (bx - ax) * t}px ${ay + (by - ay) * t}px`;
}
// Hover/press windows: [{el, p0, p1}] -> .is-hover inside, .is-press near the end.
function pressStates(win, presses, p) {
  for (const pr of presses) {
    const el = $(pr.el, win);
    el.classList.toggle('is-hover', p >= pr.p0 && p < pr.p1 + 0.01);
    el.classList.toggle('is-press', p >= pr.p1 - 0.025 && p < pr.p1 + 0.01);
  }
}

// ================================================================== story
function setBeat(chapter, i) {
  $$('[data-beat]', chapter).forEach(a => a.classList.toggle('is-on', +a.dataset.beat === i));
}

// ========================================================== 1 · INSTALL
const install = (() => {
  const ch = $('#install'), win = $('[data-setup]'), cursor = $('[data-cursor]', win);
  const pages = $$('[data-page]', win), steps = $$('[data-steps] li', win);
  const ram = $('[data-ram]', win), ramFill = $('[data-ram-fill]', win), ramKnob = $('[data-ram-knob]', win);
  const fill = $('[data-install-fill]', win), stage = $('[data-install-stage]', win), pct = $('[data-install-pct]', win);
  const startup = $('[data-startup]', win);
  const RAM_MIN = 2, RAM_MAX = 16;
  const setRam = gb => {
    const v = ((gb - RAM_MIN) / (RAM_MAX - RAM_MIN) * 100).toFixed(2) + '%';
    ramFill.style.setProperty('--v', v);
    ramKnob.style.setProperty('--v', v);
    ram.textContent = `${Number.isInteger(gb) ? gb : gb.toFixed(2).replace(/0$/, '')} GB`;
  };
  const keys = [
    { p: 0.04, xy: [760, 560] }, { p: 0.15, el: '[data-press=customize]' }, { p: 0.2, el: '[data-press=customize]' },
    { p: 0.25, el: '[data-ram-knob]', fx: 0.5, fy: 0.5 }, { p: 0.35, el: '[data-ram-knob]', fx: 0.5, fy: 0.5 },
    { p: 0.39, el: '[data-startup]' }, { p: 0.43, el: '[data-startup]' },
    { p: 0.47, el: '[data-press=cz-install]' }, { p: 0.5, el: '[data-press=cz-install]' },
    { p: 0.81, xy: [600, 520] }, { p: 0.9, el: '[data-press=launch]' }, { p: 0.97, el: '[data-press=launch]' },
  ];
  const presses = [
    { el: '[data-press=customize]', p0: 0.14, p1: 0.2 },
    { el: '[data-press=cz-install]', p0: 0.46, p1: 0.5 },
    { el: '[data-press=launch]', p0: 0.89, p1: 0.97 },
  ];
  return p => {
    const page = p < 0.2 ? 0 : p < 0.5 ? 1 : p < 0.8 ? 2 : 3;
    setBeat(ch, page);
    pages.forEach((el, i) => { el.classList.toggle('is-on', i === page); el.classList.toggle('is-past', i < page); });
    steps.forEach((el, i) => { el.classList.toggle('is-on', i === page); el.classList.toggle('is-done', i < page); });
    setRam(Math.round((6 + 2 * smooth(span(p, 0.26, 0.35))) * 4) / 4);
    startup.classList.toggle('on', p >= 0.425);
    const f = span(p, 0.52, 0.78);
    fill.style.width = `${(f * 100).toFixed(1)}%`;
    pct.textContent = `${Math.round(f * 100)}%`;
    stage.textContent = f < 0.02 ? 'Getting ready' : f < 0.9 ? 'Unpacking Origin' : 'Adding shortcuts';
    cursorPath(win, cursor, keys, p);
    pressStates(win, presses, p);
  };
})();

// =========================================================== 2 · LAUNCH
const launch = (() => {
  const ch = $('#launch'), win = $('[data-launcher]'), cursor = $('[data-cursor]', win);
  const ver = $('[data-ver]', win), load = $('[data-lload]', win), lstage = $('[data-lstage]', win);
  const STAGES = [[0, 'Preparing'], [0.1, 'Signing in'], [0.24, 'Preparing game files...'], [0.4, 'Installing Fabric loader...'], [0.58, 'Installing Origin Client...'], [0.8, 'Launching']];
  const keys = [
    { p: 0.02, xy: [1100, 700] }, { p: 0.08, el: '[data-press=version]' }, { p: 0.36, el: '[data-press=version]' },
    { p: 0.44, el: '[data-press=play]' }, { p: 0.5, el: '[data-press=play]' },
  ];
  const presses = [{ el: '[data-press=play]', p0: 0.43, p1: 0.5 }];
  let shown = '';
  return p => {
    setBeat(ch, p < 0.4 ? 0 : p < 0.62 ? 1 : 2);
    // roll through every version, then settle on 1.21.1
    const target = ALL_VERSIONS.indexOf('1.21.1');
    let idx = 0;
    if (p >= 0.08 && p < 0.3) idx = Math.round(span(p, 0.08, 0.3) * (ALL_VERSIONS.length - 1));
    else if (p >= 0.3) idx = Math.round(ALL_VERSIONS.length - 1 - span(p, 0.3, 0.36) * (ALL_VERSIONS.length - 1 - target));
    const v = ALL_VERSIONS[idx];
    if (v !== shown) {
      shown = v;
      ver.textContent = v;
      ver.classList.remove('is-roll'); void ver.offsetWidth; ver.classList.add('is-roll');
    }
    $('[data-press=version]', win).classList.toggle('is-hover', p > 0.07 && p < 0.37);
    const lp = span(p, 0.5, 1);
    load.classList.toggle('is-on', p >= 0.5);
    lstage.textContent = STAGES.filter(s => lp >= s[0]).pop()[1];
    cursorPath(win, cursor, p < 0.5 ? keys : [], p);
    pressStates(win, presses, p);
  };
})();

// Icons are CSS masks so CSS can tint them; the URL must be absolute
// (a relative url() inside a custom property resolves against the stylesheet).
function paintIcons(el = document) {
  for (const i of $$('[data-icon]', el)) i.style.setProperty('--i', `url("${new URL(`assets/icons/${i.dataset.icon}.svg`, location.href)}")`);
}

// ======================================================== versions + shots
function initVersions() {
  $('[data-vgrid]').innerHTML = VERSION_GROUPS.map(g => `<article class="vcard${g.dev ? ' vcard--dev' : ''}" data-reveal>`
    + `<header><h3>${g.title}</h3><span class="tag">${g.dev ? 'In development' : g.tag}</span></header>`
    + `<ul>${g.versions.map(v => `<li${g.dev ? ' class="dev"' : ''}>${v}</li>`).join('')}</ul></article>`).join('');
  if (SCREENSHOTS.length) {
    $('[data-shots]').hidden = false;
    $('[data-shots-grid]').innerHTML = SCREENSHOTS.map(s => `<figure data-reveal><img src="${s.src}" alt="${s.caption}" loading="lazy"><figcaption>${s.caption}</figcaption></figure>`).join('');
  }
}

// ============================================================= the scroll
const scenes = {};
// Shaders are on for the whole experience; the switch in the Shaders section
// lets a player compare against plain Minecraft.
let world = null, shaders = true;

function chapterProgress(el) {
  const r = el.getBoundingClientRect();
  return clamp(-r.top / (r.height - innerHeight));
}

function frame() {
  const H = innerHeight, y = scrollY;
  const rect = id => scenes[id].getBoundingClientRect();
  const ins = rect('install'), lau = rect('launch'), ver = rect('versions');

  install(chapterProgress(scenes.install));
  launch(chapterProgress(scenes.launch));

  // ---- world pose + shade: each scene blends into the next as it scrolls in
  const SCENES = [['hero', 0], ['install', 0.55], ['launch', 0.55], ['versions', 0.62], ['shaders', 0], ['get', 0.25]];
  let pose = ['hero', 'hero', 0], shade = 0;
  for (let i = 1; i < SCENES.length; i++) {
    const top = rect(SCENES[i][0]).top;
    if (top >= H) break;
    const t = smooth(clamp(1 - top / H)), [pa, sa] = SCENES[i - 1], [pb, sb] = SCENES[i];
    pose = [pa, pb, t]; shade = sa + (sb - sa) * t;
  }
  const step = ins.top > H * 0.5 ? 0 : lau.top > H * 0.5 ? 1 : ver.top > H * 0.5 ? 2 : 3;
  world?.setPose(...pose);
  root.style.setProperty('--shade', shade.toFixed(3));

  // ---- chrome: header, journey rail, nav
  const top = $('[data-top]');
  top.classList.toggle('is-solid', y > 40);
  // hide on scroll down, come back on scroll up
  if (y > lastY + 1 && y > H) top.classList.add('is-hidden');
  else if (y < lastY - 1 || y <= H) top.classList.remove('is-hidden');
  lastY = y;
  const journey = $('[data-journey]');
  journey.classList.toggle('is-away', step >= 3);   // done with the journey once past Launch
  $$('li', journey).forEach((li, i) => { li.classList.toggle('is-on', i === step); li.classList.toggle('is-done', i < step); });
  const navOn = step === 1 ? 'install' : step === 2 ? 'launch' : ver.top < H * 0.5 && ver.bottom > H * 0.5 ? 'versions' : '';
  $$('.top__nav a').forEach(a => a.classList.toggle('is-on', a.getAttribute('href') === `#${navOn}`));
}
let lastY = 0, queued = false;
function requestFrame() {
  if (queued) return;
  queued = true;
  requestAnimationFrame(() => { queued = false; frame(); });
}

// =================================================================== boot
async function boot() {
  initDownload();
  paintIcons();
  initOrbits();
  initVersions();
  for (const el of $$('[data-scene]')) scenes[el.id === 'top' ? 'hero' : el.id] = el;
  fitFrames();
  frame();
  addEventListener('scroll', requestFrame, { passive: true });
  addEventListener('resize', () => { fitFrames(); requestFrame(); });

  // shaders switch
  const sw = $('[data-shaders]');
  const paintShaders = () => {
    sw.setAttribute('aria-checked', String(shaders));
    root.style.setProperty('--sunset', shaders ? 1 : 0);
  };
  paintShaders();
  sw.addEventListener('click', () => {
    shaders = !shaders;
    paintShaders();
    world?.setShaders(shaders);
  });

  // reveal-on-scroll
  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('is-in'); io.unobserve(e.target); } }), { threshold: 0.15 });
  $$('[data-reveal]').forEach(el => io.observe(el));
  $$('.section__head, .shaders, .get > *').forEach(el => { el.dataset.reveal = ''; io.observe(el); });

  // the 3D world (fails soft to the flat backdrop)
  try {
    const { createWorld } = await import('./world.js');
    world = await createWorld($('#world'), { mobile: isMobile(), reducedMotion, shaders });
    frame();
  } catch (err) {
    console.warn('Origin: 3D world unavailable, using the flat backdrop.', err);
    document.body.classList.add('no-webgl');
  }
}
boot();
