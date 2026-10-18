// Starfall Cape: a deep night hung as cloth, darkest navy at the collar and lifting through blue to a violet
// horizon at the hem; a milky-way band with a warm core, ragged dust lanes and dense star clusters; stars of
// different sizes in clusters, three of which flare long cross glints once per loop; and a slow meteor
// shower (2-3 streaks at once), each a bright 2x2 head and a tail that fades out over four steps.
// Scale 4 (animated), 32 frames at 12 fps. Seamless: every twinkle and flare is periodic in the loop and each
// meteor's flight wraps modulo the loop (frame 32 = frame 0). Nothing is random per frame: seeded layout only.
import { Canvas, ramp, rampThrough, clamp, lerp, smoothstep, mix, rng, fbm, hash2, bayer, ditherIndex } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, FRAMES = 32;
const cloth = [0, 2, 4, 6, 8].map(i => ramp('#1a2250', 9, { lo: 0.04, hi: 0.3, tint: 0.05, shadowHue: 250, lightHue: 225, shift: 0.3 })[i]);
// night: near-black navy at the top, a violet-rose lift at the hem (cool shadows, warm light)
const SKY = rampThrough(['#010208', '#040a20', '#0a1642', '#12245f', '#1d3680', '#2e4a9a', '#4a5aa6', '#76629e', '#a07a9c'], 14);
const MILK = ['#26356f', '#3d4f93', '#5f70b0', '#8f9cd0', '#c3cae8', '#e8ecfa'];                 // cool dust -> pale light
const WARM = ['#5f4a6c', '#8e6a78', '#bb8a7e', '#dca884', '#f0c894', '#f6dcb0'];                           // the warm galactic core
const STAR = ['#4a5a96', '#8294cc', '#c6d2f2', '#fff6df'];                                       // dim -> warm white
const TAIL = ['#e9f6ff', '#8cc0ee', '#4b7fc8', '#27468f'];                                        // meteor tail, hot -> cool

// ---------------------------------------------------------------- planet: a small rocky world, lit from the top-left
const PLANET = ['#0b0f2a', '#161c45', '#2a2f6a', '#4d4f93', '#8a85b8', '#d9c9d8'];              // night side -> sunlit rim
function drawPlanet(c, cx, cy, R) {
  const L = [-0.62, -0.62, 0.48], ll = Math.hypot(...L);
  for (let y = Math.floor(cy - R - 2); y <= Math.ceil(cy + R + 2); y++) for (let x = Math.floor(cx - R - 2); x <= Math.ceil(cx + R + 2); x++) {
    const px = x + 0.5 - cx, py = y + 0.5 - cy, d = Math.hypot(px, py);
    if (d > R) { if (d <= R + 1.1) c.set(x, y, mix(c.get(x, y), '#6a64a8', (px * L[0] + py * L[1]) / d > 0.35 ? 0.35 : 0.1)); continue; }   // 1 px atmosphere, brighter on the lit side
    const nz = Math.sqrt(Math.max(0, 1 - (d / R) ** 2)), lit = (px / R * L[0] + py / R * L[1] + nz * L[2]) / ll;
    let i = clamp(lit * 1.15 + 0.1) * (PLANET.length - 1);
    const band = Math.sin(py * 1.1 + px * 0.25) > 0.55 ? 0.6 : 0;                        // two faint cloud bands
    i = clamp((i + band * (lit > 0.1 ? 1 : 0)) / (PLANET.length - 1)) * (PLANET.length - 1);
    c.set(x, y, PLANET[Math.round(i)]);
  }
}

// ---------------------------------------------------------------- sky (static)
const VALLEYS = [-1.5, 9.5, 23, 31, 41.5];
const valleyX = (k, y) => 20 + (VALLEYS[k] - 20) * (0.95 + 0.1 * (y / H)) + Math.sin(y * 0.08 + k * 1.9) * 0.8;
function fold(x, y) {                                      // -0.5..0.5: broad uneven cloth folds, lit from the left
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a)), r = 0.38;
  return (p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r))) - 0.5;
}
// the band runs lower left -> upper right; `al` is the distance along it, `q` across it
const BAND = { x0: -2, y0: 56, dx: 0.74, dy: -0.67 }, CORE_AL = 14;
const bandCoords = (x, y) => { const rx = x - BAND.x0, ry = y - BAND.y0; return { al: rx * BAND.dx + ry * BAND.dy, q: -rx * BAND.dy + ry * BAND.dx }; };
function milk(x, y) {                                      // -> { d: density 0..1, warm: 0..1, lane: bool }
  const { al, q: q0 } = bandCoords(x, y), q = q0 + Math.sin(al * 0.13) * 1.6;
  const width = 5.2 + 3.2 * Math.exp(-(((al - CORE_AL) / 9) ** 2));            // fatter at the core
  const body = Math.exp(-((q / width) ** 2));
  const grain = fbm(x * 0.2 + 4, y * 0.2 + 9, 7, 4);
  const core = Math.exp(-(((al - CORE_AL) / 7) ** 2 + (q / 4.2) ** 2));
  // two dark dust lanes run along the band (the long one splits it, a shorter one rides its lower edge),
  // plus a few ragged wisps cut out by thresholded noise
  const lane1 = Math.exp(-(((q - 1.4 - 0.9 * Math.sin(al * 0.09)) / 1.15) ** 2)) * smoothstep(-8, 4, al) * (1 - smoothstep(34, 46, al));
  const lane2 = Math.exp(-(((q + 3.6 - 0.6 * Math.sin(al * 0.12 + 1)) / 0.9) ** 2)) * smoothstep(6, 12, al) * (1 - smoothstep(26, 34, al));
  const lane = Math.max(lane1, lane2 * 0.9);
  const wisp = fbm(x * 0.33 + 31, y * 0.33 + 5, 3, 3) > 0.64 + 0.12 * (1 - body) ? 1 : 0;
  const d = clamp(body * (0.32 + 1.15 * grain) + core * 0.3) * (1 - 0.92 * lane) * (1 - 0.6 * wisp);
  return { d, warm: clamp(core * 1.3) * (1 - lane), al, q, body };
}
let SKYIMG = null, STARS = null, FLARES = null;
function buildSky() {
  const c = newCapeArt(S);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const t = clamp((y / H) ** 1.05 * 1.02 + fold(x, y) * 0.05 - 0.04 * smoothstep(0.5, 1.2, Math.hypot((x - 20) / 24, (y - 32) / 40)));
    let col = SKY[ditherIndex(t, SKY.length, bayer(x, y, 8))];
    const m = milk(x, y);
    if (m.d > 0.1) {
      const lvl = clamp((m.d - 0.1) * 1.05);
      const i = ditherIndex(lvl, MILK.length + 1, bayer(x, y, 4));
      if (i > 0) {
        const cool = MILK[Math.min(MILK.length - 1, i - 1)];
        col = mix(col, mix(cool, WARM[Math.min(WARM.length - 1, i - 1)], m.warm * 0.85), 0.42 + 0.06 * i);
      }
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
  drawPlanet(c, 31, 47, 4.4);
  SKYIMG = c;

  // stars: dense pinpoints inside the band, loose clusters elsewhere, a few lone brighter ones.
  const r = rng('starfall3'), list = [], used = new Set();
  const free = (x, y) => x >= 2 && x < W - 2 && y >= 6 && y < H - 8 && !used.has(x + ',' + y);
  const add = (x, y, o) => { x = Math.round(x); y = Math.round(y); if (!free(x, y)) return; used.add(x + ',' + y); list.push({ x, y, k: 1 + r.int(2), ph: r.range(0, 1), base: 0.3 + 0.5 * r(), size: 1, ...o }); };
  for (let i = 0; i < 70; i++) {                                 // band clusters: rejection sample by band density
    const x = 2 + r.int(W - 4), y = 6 + r.int(H - 14), m = milk(x, y);
    if (r() < m.d * 1.1 - 0.08) add(x, y, { base: 0.25 + 0.45 * r(), warm: m.warm > 0.4 });
  }
  const centres = [[30, 14], [8, 30], [22, 52], [10, 12]];                // open clusters off the band
  centres.forEach(([cx, cy], j) => { const n = 4 + r.int(4); for (let i = 0; i < n; i++) add(cx + (r() + r() - 1) * 5, cy + (r() + r() - 1) * 4, { base: 0.3 + 0.55 * r(), size: j % 2 && i === 0 ? 2 : 1 }); });
  for (let i = 0; i < 9; i++) add(2 + r.int(W - 4), 6 + r.int(H - 14), { base: 0.35 + 0.5 * r(), size: i < 3 ? 2 : 1 });
  STARS = list;
  // three flare stars: far apart, each flares once per loop at its own phase
  FLARES = [{ x: 9, y: 17, ph: 0.12 }, { x: 31, y: 24, ph: 0.46 }, { x: 14, y: 45, ph: 0.8 }];
}

// ---------------------------------------------------------------- meteors
// start frame, entry (x, y) above the cape, direction angle (rad from straight down, toward -x), path length
const D = 22, TAILLEN = 17;
const METEORS = [
  { s: 0,  x: 37, y: 4, a: 0.50, len: 56 },
  { s: 8,  x: 27, y: 4, a: 0.58, len: 50 },
  { s: 16, x: 39, y: 4, a: 0.44, len: 58 },
  { s: 24, x: 31, y: 4, a: 0.54, len: 52 },
];
function meteor(c, m, f) {
  const tau = ((f - m.s) % FRAMES + FRAMES) % FRAMES;
  if (tau >= D) return;
  const dx = -Math.sin(m.a), dy = Math.cos(m.a), u = tau / D, travelled = m.len * u;
  const hx = m.x + dx * travelled, hy = m.y + dy * travelled;
  const env = clamp((D - tau) / 4) * clamp((tau + 1) / 3);                         // fades in under the collar, out at the hem
  const y0 = 5, y1 = H - 8, tl = Math.min(TAILLEN, travelled + 2);                // the tail only exists behind the entry point
  for (let y = Math.max(y0, Math.floor(hy - tl - 2)); y <= Math.min(y1, Math.ceil(hy + 3)); y++) for (let x = Math.max(1, Math.floor(hx - TAILLEN - 4)); x <= Math.min(W - 2, Math.ceil(hx + TAILLEN + 4)); x++) {
    const rx = x + 0.5 - hx, ry = y + 0.5 - hy, behind = -(rx * dx + ry * dy), perp = Math.abs(rx * dy - ry * dx);
    if (behind < 0.8 || behind > tl) continue;
    const a = behind / TAILLEN, width = lerp(1.15, 0.45, a);
    if (perp > width) continue;
    if (a > 0.7 && bayer(x, y, 4) < (a - 0.7) * 2.4 + (1 - env) * 0.9) continue;                  // the last steps dissolve
    c.set(x, y, TAIL[clamp(Math.floor(a * 4 + (bayer(x, y, 4) - 0.5) * 0.8), 0, 3)]);
  }
  const bx = Math.round(hx) - 1, by = Math.round(hy) - 1;                                         // 2x2 head on the nearest pixel seam
  for (let y = by - 1; y <= by + 2; y++) for (let x = bx - 1; x <= bx + 2; x++) {
    if (y < y0 || y > y1 || x < 1 || x > W - 2) continue;
    const inner = x >= bx && x <= bx + 1 && y >= by && y <= by + 1;
    if (inner) { if (bayer(x, y, 4) < env * 1.4) c.set(x, y, '#ffffff'); }
    else if (bayer(x, y, 4) < 0.5 * env) c.set(x, y, TAIL[1]);
  }
}

// A four-point star: the astroid |dx|^0.4 + |dy|^0.4 <= R^0.4, so every point tapers from a fat centre to a 1 px tip.
const SPARKLE = ['#ffffff', '#fff6df', '#c6d2f2', '#8294cc', '#4a5a96'];
function sparkle(c, x, y, R, e) {
  const lim = R ** 0.4, n = Math.ceil(R) + 1;
  for (let j = -n; j <= n; j++) for (let i = -n; i <= n; i++) {
    const v = Math.abs(i) ** 0.4 + Math.abs(j) ** 0.4;
    if (v > lim + 1e-6) continue;
    const dist = Math.max(Math.abs(i), Math.abs(j)) / Math.max(R, 1);
    const idx = Math.min(4, Math.floor(dist * 4.4 + (e < 0.25 ? 1.2 : 0)));
    if (c.alpha(x + i, y + j)) c.set(x + i, y + j, SPARKLE[idx]);
  }
}

export default {
  meta: { id: 'cape-starfall', name: 'Starfall Cape', kind: 'cape', cents: 899, blurb: 'A deep navy night with a slow meteor shower and twinkling stars.' },
  cape: { scale: S, frames: FRAMES, fps: 12 },
  paint(f) {
    if (!SKYIMG) buildSky();
    const c = SKYIMG.clone(), t = f / FRAMES;
    for (const s of STARS) {
      const b = clamp(s.base + (1 - s.base) * (0.5 + 0.5 * Math.cos(2 * Math.PI * (t * s.k + s.ph))) * 0.7);
      const core = s.warm ? ['#8a7a88', '#c4a890', '#f0d0a0', '#fff0cc'][Math.min(3, Math.floor(b * 4))] : STAR[Math.min(3, Math.floor(b * 4))];
      c.set(s.x, s.y, core);
      if (s.size === 2) { c.set(s.x + 1, s.y, STAR[Math.max(0, Math.min(3, Math.floor(b * 4)) - 1)]); c.set(s.x, s.y + 1, STAR[Math.max(0, Math.min(3, Math.floor(b * 4)) - 1)]); }
    }
    for (const fl of FLARES) {
      const e = Math.max(0, Math.cos(2 * Math.PI * (t - fl.ph))) ** 5;         // 0 most of the loop, 1 at the flare peak
      sparkle(c, fl.x, fl.y, 1.4 + 6.4 * e, e);
    }
    for (const m of METEORS) meteor(c, m, f);
    return c;
  },
};
