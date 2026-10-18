// Starfall Cape: a deep navy night sky hung as cloth, a faint milky-way band, twinkling stars with cross
// glints, and a slow meteor shower: 2-3 streaks on screen at once, each a bright head with a tapering,
// ordered-dithered tail. Scale 4 (animated), 32 frames at 12 fps. Seamless loop: every star twinkles a
// whole number of times per loop, and each meteor's flight is placed modulo the loop so one that
// started late in the loop finishes at its start (frame 32 = frame 0).
import { Canvas, ramp, rampThrough, clamp, smoothstep, mix, rng, fbm, bayer, ditherIndex } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, FRAMES = 32;
const cloth = [0, 2, 4, 6, 8].map(i => ramp('#1a2250', 9, { lo: 0.04, hi: 0.3, tint: 0.05, shadowHue: 250, lightHue: 225, shift: 0.3 })[i]);
const SKY = rampThrough(['#03051a', '#080f33', '#101c4e', '#1a2b6b', '#2a3a86', '#3d4590'], 8);   // top -> horizon, a violet warm-up at the foot
const MILK = ['#34478c', '#5a6bab', '#8b96c6', '#c0c6e2'];                                       // milky-way dust, cool shadow -> pale light
const STAR = ['#4a5a96', '#8294cc', '#c6d2f2', '#fff6df'];                                       // dim -> warm white
const TAIL = ['#233a7a', '#3f67ae', '#7fb0e0', '#bfe0f6', '#f1f9ff'];                             // meteor tail, cool -> hot

// ---------------------------------------------------------------- sky (static)
const VALLEYS = [-1.5, 9.5, 23, 31, 41.5];
const valleyX = (k, y) => 20 + (VALLEYS[k] - 20) * (0.95 + 0.1 * (y / H)) + Math.sin(y * 0.08 + k * 1.9) * 0.8;
function fold(x, y) {                                      // -0.5..0.5: broad uneven cloth folds, lit from the left
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a)), r = 0.38;
  return (p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r))) - 0.5;
}
// milky way: a soft diagonal band from the lower left to the upper right
const BAND = { x0: -4, y0: 54, dx: 0.74, dy: -0.67 };
function milk(x, y) {
  const rx = x - BAND.x0, ry = y - BAND.y0, along = rx * BAND.dx + ry * BAND.dy, q = -rx * BAND.dy + ry * BAND.dx;   // q: signed distance across the band
  const core = Math.exp(-(((q + Math.sin(along * 0.12)) / 5.2) ** 2));
  const grain = fbm(x * 0.16 + 4, y * 0.16 + 9, 7, 4);
  const lane = Math.exp(-(((q - 1.6) / 1.3) ** 2)) * 0.45;                         // a dark dust lane through the band
  return clamp(core * (0.35 + 1.0 * grain) - lane * core);
}
let SKYIMG = null, STARS = null;
function buildSky() {
  const c = newCapeArt(S);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const t = clamp(0.04 + 0.78 * (y / H) + fold(x, y) * 0.16 - 0.06 * smoothstep(0.5, 1.2, Math.hypot((x - 20) / 24, (y - 32) / 40)));
    let col = SKY[ditherIndex(t, SKY.length, bayer(x, y, 4))];
    const m = milk(x, y);
    if (m > 0.12) {
      const i = ditherIndex(clamp((m - 0.12) * 1.5), MILK.length + 1, bayer(x, y, 4));
      if (i > 0) col = mix(col, MILK[i - 1], 0.3 + 0.07 * i);
    }
    c.set(x, y, col);
  }
  // collar (gathered band) and hem (one stitched fold), so the night reads as cloth with edges
  for (let y = 1; y < 3; y++) for (let x = 0; x < W; x++) c.set(x, y, cloth[1 + ((fold(x, y) > 0) ? 1 : 0)]);
  for (let x = 0; x < W; x++) { c.set(x, 3, cloth[0]); c.set(x, 4, ((x >> 1) & 1) ? cloth[3] : cloth[2]); }
  const hy = H - 6;
  for (let x = 0; x < W; x++) {
    c.set(x, hy, cloth[0]); c.set(x, hy + 1, cloth[3]);
    for (let y = hy + 2; y < H - 1; y++) c.set(x, y, cloth[fold(x, y) > 0 ? 2 : 1]);
    c.set(x, hy + 3, ((x % 4) === 3) ? cloth[1] : '#6f7fb0');
  }
  const o = cloth[0];
  for (let x = 0; x < W; x++) { c.set(x, 0, o); c.set(x, H - 1, o); }
  for (let y = 0; y < H; y++) { c.set(0, y, o); c.set(W - 1, y, o); }
  SKYIMG = c;

  // stars: faint ones and a few with cross glints. k = whole twinkles per loop.
  const r = rng('starfall'), list = [], taken = new Set();
  const place = (n, big) => { for (let i = 0; i < n; i++) {
    let x, y, tries = 0;
    do { x = 4 + r.int(W - 8); y = 6 + r.int(H - 14); tries++; } while (tries < 30 && [...taken].some(t => { const [tx, ty] = t.split(',').map(Number); return Math.hypot(tx - x, ty - y) < (big ? 8 : 4); }));
    taken.add(x + ',' + y); list.push({ x, y, big, k: 1 + r.int(2), ph: r.range(0, 1), base: big ? 0.55 : 0.3 + 0.35 * r() });
  } };
  place(7, true); place(30, false);
  STARS = list;
}

// ---------------------------------------------------------------- meteors
// start frame, entry (x, y) above the cape, direction angle (rad from straight down, toward -x), path length
const D = 18, TAILLEN = 15;
const METEORS = [
  { s: 0,  x: 34, y: -6, a: 0.50, len: 56 },
  { s: 6,  x: 18, y: -9, a: 0.62, len: 46 },
  { s: 13, x: 40, y: -4, a: 0.45, len: 60 },
  { s: 19, x: 28, y: -8, a: 0.56, len: 52 },
  { s: 26, x: 12, y: -6, a: 0.40, len: 58 },
];
function meteor(c, m, f) {
  const tau = ((f - m.s) % FRAMES + FRAMES) % FRAMES;
  if (tau >= D) return;
  const dx = -Math.sin(m.a), dy = Math.cos(m.a), u = tau / D;
  const hx = m.x + dx * m.len * u, hy = m.y + dy * m.len * u, env = clamp((D - tau) / 5) * clamp((tau + 1) / 2);
  for (let y = Math.max(5, Math.floor(hy - TAILLEN - 2)); y <= Math.min(H - 8, Math.ceil(hy + 3)); y++) for (let x = Math.max(1, Math.floor(hx - TAILLEN - 4)); x <= Math.min(W - 2, Math.ceil(hx + TAILLEN + 4)); x++) {
    const rx = x + 0.5 - hx, ry = y + 0.5 - hy, behind = -(rx * dx + ry * dy), perp = Math.abs(rx * dy - ry * dx);
    if (behind < 0 || behind > TAILLEN) continue;
    const a = behind / TAILLEN, width = 1.2 * (1 - a) + 0.2;
    const I = (1 - a) ** 1.1 * clamp(1 - perp / (width + 0.35)) * env;
    const i = ditherIndex(I * 1.7, TAIL.length + 1, bayer(x, y, 4));
    if (i > 0) c.set(x, y, TAIL[i - 1]);
  }
  for (let y = Math.max(5, Math.floor(hy - 3)); y <= Math.min(H - 8, Math.ceil(hy + 3)); y++) for (let x = Math.max(1, Math.floor(hx - 3)); x <= Math.min(W - 2, Math.ceil(hx + 3)); x++) {
    const d = Math.hypot(x + 0.5 - hx, y + 0.5 - hy);
    if (d <= 0.95) c.set(x, y, '#ffffff');
    else if (d <= 1.45 && bayer(x, y, 4) < 0.75) c.set(x, y, TAIL[3]);
    else if (d <= 2.3 && bayer(x, y, 4) < 0.4 * env) c.set(x, y, TAIL[1]);
  }
}

export default {
  meta: { id: 'cape-starfall', name: 'Starfall Cape', kind: 'cape', cents: 899, blurb: 'A deep navy night with a slow meteor shower and twinkling stars.' },
  cape: { scale: S, frames: FRAMES, fps: 12 },
  paint(f) {
    if (!SKYIMG) buildSky();
    const c = SKYIMG.clone(), t = f / FRAMES;
    for (const s of STARS) {
      const b = clamp(s.base + (1 - s.base) * (0.5 + 0.5 * Math.cos(2 * Math.PI * (t * s.k + s.ph))) * (s.big ? 1 : 0.7));
      const core = STAR[Math.min(3, Math.floor(b * 4))];
      c.set(s.x, s.y, core);
      if (s.big && b > 0.62) {                                // cross glint: arms grow and fade with the twinkle
        const arm = b > 0.88 ? 2 : 1;
        for (let i = 1; i <= arm; i++) for (const [ax, ay] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) c.set(s.x + ax * i, s.y + ay * i, i === 1 ? STAR[b > 0.88 ? 2 : 1] : STAR[1]);
      }
    }
    for (const m of METEORS) meteor(c, m, f);
    return c;
  },
};
