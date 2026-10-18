// Inferno Cape: charred black cloth with flame tongues licking up from a glowing, cracked hem.
// Animated, scale 4: painted 1 art pixel = 4 texture pixels on the 40x64 outside face.
//
// Fire is built from 8 separate tongues (each its own height, lean and flicker) so dark cloth shows
// BETWEEN them above the hem -- a row of flames, not a gradient. Each tongue is shaded on a 5-step
// ramp (deep red edge -> red -> orange -> yellow -> white core): the hottest pixels sit low in the
// middle of each tongue, the rim and the tip stay red. Above the tips a little cool ash smoke
// drifts, and a few slow embers rise. The cloth itself is a near-black cool ramp that only picks up
// a warm underlight in the lowest rows. Every motion uses a whole number of cycles over the loop
// (periodic noise scrolled by exactly its period), so frame 23 -> frame 0 is seamless.
// Cape textures have no emissive channel, so the flame cores are simply the brightest pixels.
import { ramp, clamp, smoothstep, mix, hash2, bayer, rampThrough } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, N = 24, FPS = 12;
const YB = H - 4;                                       // flame base row (the crust sits below it)

// --- palettes -------------------------------------------------------------------------------
const clothFine = ramp('#1e1d22', 9, { lo: 0.02, hi: 0.115, tint: 0.025, shadowHue: 250, lightHue: 18, shift: 0.3 });
const FIRE = ['#6e1410', '#c42a14', '#ff7a18', '#ffc02e', '#fff4c8'];   // deep red, red, orange, yellow, white core
const WARM = ['#1d1012', '#2e1210', '#47160f', '#68200f'];              // cloth lit from below by the fire
const ASH = ['#2b2c33', '#3f4048', '#5b5e68'];                          // cool smoke
const OUTLINE = '#08080b';

// --- periodic value noise: repeats every K lattice cells in y, so scrolling by K cells loops ----
function pnoise(x, y, K, seed) {
  const xi = Math.floor(x), yi = Math.floor(y), fx = x - xi, fy = y - yi;
  const u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy), w = j => ((j % K) + K) % K;
  const a = hash2(xi, w(yi), seed), b = hash2(xi + 1, w(yi), seed), c = hash2(xi, w(yi + 1), seed), d = hash2(xi + 1, w(yi + 1), seed);
  return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v;
}

// --- cloth ------------------------------------------------------------------------------------
const VALLEYS = [-2, 11, 21, 31, 42];
const valleyX = (k, y) => VALLEYS[k] + Math.sin(y * 0.09 + k * 1.9) * 0.8;
const foldProfile = p => { const r = 0.4; return p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r)); };
function clothLevel(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a));
  let t = 0.22 + foldProfile(p) * 0.46 + (0.5 - y / H) * 0.1;
  t += (pnoise(x / 6, y / 7, 64, 7) - 0.5) * 0.16;                       // uneven char: patches burnt darker
  if ((x + y * 2) % 5 === 0) t -= 0.05;                                  // faint weave
  return clamp(Math.floor(clamp(t) * clothFine.length), 1, clothFine.length - 1);
}

// --- fire -------------------------------------------------------------------------------------
// c = centre column, w = half-width at the base, h = height, m = flicker cycles per loop, lean = sideways curl.
const TEETH = [
  { c: 3.0, w: 2.3, h: 15, m: 1, ph: 0.10, lean: -1.2, k: 0.9 }, { c: 9.6, w: 2.6, h: 27, m: 1, ph: 0.55, lean: 2.4, k: 1 },
  { c: 16.0, w: 2.4, h: 20, m: 2, ph: 0.30, lean: -1.8, k: 1 }, { c: 22.2, w: 2.8, h: 31, m: 1, ph: 0.80, lean: 1.6, k: 1 },
  { c: 28.6, w: 2.4, h: 22, m: 2, ph: 0.05, lean: -2.2, k: 1 }, { c: 35.2, w: 2.5, h: 17, m: 1, ph: 0.40, lean: 1.4, k: 0.9 },
  // short licks in the gaps: low, cooler, they keep the hem from reading as separate teeth
  { c: 6.3, w: 1.5, h: 8, m: 2, ph: 0.20, lean: 0.8, k: 0.72 }, { c: 12.9, w: 1.6, h: 10, m: 1, ph: 0.70, lean: -0.8, k: 0.72 },
  { c: 19.1, w: 1.5, h: 9, m: 2, ph: 0.45, lean: 0.9, k: 0.72 }, { c: 25.4, w: 1.6, h: 11, m: 1, ph: 0.15, lean: -1.0, k: 0.72 },
  { c: 31.9, w: 1.5, h: 8, m: 2, ph: 0.85, lean: 0.8, k: 0.72 }, { c: 38.0, w: 1.4, h: 7, m: 1, ph: 0.50, lean: -0.6, k: 0.72 },
];
const sinT = (t, m, ph) => Math.sin(2 * Math.PI * (t * m + ph));

function toothAt(T, x, y, t) {            // -> { heat 0..1, v } of tooth T at pixel centre, or null outside
  const hh = T.h * (1 + 0.16 * sinT(t, T.m, T.ph)), v = (YB - y) / hh;
  if (v < 0 || v > 1) return null;
  const cx = T.c + T.lean * v * v * sinT(t, 1, T.ph + 0.25) + 0.6 * sinT(t, 1, T.ph) * v;
  const wob = (pnoise(x / 3.2 + T.c, (y + t * 16) / 3.2, 6, 31) - 0.5) * 1.1;   // 16 px scroll = 8 cells x 2 px: loops
  const wd = T.w * Math.pow(1 - v, 0.85) + wob * (0.5 + v) * 0.6;
  if (wd <= 0.25) return null;
  const dn = Math.abs(x - cx) / wd;
  if (dn >= 1) return null;
  return { heat: T.k * clamp((1 - Math.pow(dn, 1.5)) * 0.5 + (1 - v) * 0.48 - 0.1), v, dn };
}

function paintFrame(f) {
  const t = f / N, c = newCapeArt(S), heat = new Float32Array(W * H).fill(-1), tipY = new Float32Array(W).fill(H);
  // ---- charred cloth + outline
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, clothFine[clothLevel(x, y)]);
  for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); }
  for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
  // collar: gathered band + stitched seam
  for (let y = 1; y < 5; y++) for (let x = 1; x < W - 1; x++) c.set(x, y, clothFine[clamp(clothLevel(x, y) - 1, 1, 3)]);
  for (let x = 1; x < W - 1; x++) { c.set(x, 5, OUTLINE); c.set(x, 6, (x >> 1) & 1 ? clothFine[4] : clothFine[3]); }

  // ---- flame field
  for (let y = 0; y < YB; y++) for (let x = 0; x < W; x++) {
    let best = null;
    for (const T of TEETH) { const r = toothAt(T, x + 0.5, y + 0.5, t); if (r && (!best || r.heat > best.heat)) best = r; }
    if (best) {
      heat[y * W + x] = best.heat;
      tipY[x] = Math.min(tipY[x], y);
    }
  }
  // ---- warm underlight on the cloth: box-blurred heat, quantised to 4 dithered steps, strongest at the hem
  for (let y = 8; y < H; y++) for (let x = 0; x < W; x++) {
    if (heat[y * W + x] >= 0) continue;
    let s = 0, n = 0;
    for (let dy = -4; dy <= 2; dy++) for (let dx = -3; dx <= 3; dx++) {
      const xx = x + dx, yy = y + dy; if (xx < 0 || xx >= W || yy < 0 || yy >= H) continue;
      n++; if (heat[yy * W + xx] >= 0) s += 0.4 + heat[yy * W + xx];
    }
    const L = clamp(s / n * 1.5 + smoothstep(H - 14, H - 1, y) * 0.25);
    const i = Math.floor(L * WARM.length * 1.1);
    const k = i + (L * WARM.length * 1.1 - i > bayer(x, y, 4) ? 1 : 0);
    if (k > 0) { const w = WARM[Math.min(WARM.length - 1, k - 1)], base = c.get(x, y); c.set(x, y, mix(base, w, x === 0 || x === W - 1 ? 0 : 0.85)); }
  }
  // ---- smoke: cool ash above the tips, thin and dithered, scrolling up
  for (let y = 10; y < YB - 4; y++) for (let x = 1; x < W - 1; x++) {
    if (heat[y * W + x] >= 0) continue;
    let near = 99;
    for (let dx = -4; dx <= 4; dx++) { const xx = x + dx; if (xx >= 0 && xx < W) near = Math.min(near, Math.abs(dx) + Math.max(0, tipY[xx] - y) * 0.6); }
    const above = y < tipY[x] ? tipY[x] - y : 0;
    if (above <= 0 || above > 13) continue;
    const dens = pnoise(x / 3.6, (y + t * 24) / 3.6, 12, 5) * (1 - above / 13) * (1 - near / 12) * 1.15;
    if (dens > 0.2 + bayer(x, y, 4) * 0.4) {
      const k = dens > 0.55 ? 2 : dens > 0.38 ? 1 : 0;
      c.set(x, y, mix(c.get(x, y), ASH[k], 0.8));
    }
  }
  // ---- flames
  for (let y = 0; y < YB; y++) for (let x = 0; x < W; x++) {
    const h = heat[y * W + x]; if (h < 0) continue;
    const p = clamp(h * 1.08) * 4.3, lo = Math.floor(p), up = p - lo > 0.62 && bayer(x, y, 2) < (p - lo) ? 1 : 0;   // tiny 2x2 dither only where two steps meet
    c.set(x, y, FIRE[clamp(lo + up, 0, 4)]);
  }
  // ---- cracked hem crust: dark char with pulsing ember cracks, flames rise out of it
  for (let y = YB; y < H; y++) for (let x = 0; x < W; x++) {
    const n = pnoise(x / 3, y / 2, 16, 91) + 0.35 * Math.sin(2 * Math.PI * (t + hash2(x >> 1, y, 3)));
    const crack = n > 1.0 - (y === YB ? 0.18 : 0.08);
    if (y === H - 1) c.set(x, y, crack ? FIRE[0] : OUTLINE);
    else if (crack) c.set(x, y, FIRE[n > 1.25 ? 3 : n > 1.12 ? 2 : 1]);
    else c.set(x, y, y === YB ? '#4a1a10' : clothFine[1 + (hash2(x, y, 4) > 0.7 ? 1 : 0)]);
  }
  // ---- ember pinholes in the upper cloth: a few dull glows that breathe
  for (let i = 0; i < 6; i++) {
    const ex = 4 + Math.floor(hash2(i, 1, 77) * 32), ey = 12 + Math.floor(hash2(i, 2, 77) * 22);
    const br = 0.5 + 0.5 * sinT(t, 1, hash2(i, 3, 77));
    if (heat[ey * W + ex] < 0) c.set(ex, ey, br > 0.55 ? FIRE[1] : FIRE[0]);
  }
  // ---- rising embers: whole cycles per loop, hidden at both ends of their life so the wrap never pops
  for (let i = 0; i < 9; i++) {
    const k = i % 3 === 0 ? 2 : 1, life = (t * k + hash2(i, 5, 11)) % 1;
    if (life < 0.1 || life > 0.9) continue;
    const x = Math.floor(3 + hash2(i, 6, 11) * 34 + 2.2 * Math.sin(2 * Math.PI * (life * 1.5 + hash2(i, 7, 11))));
    const y = Math.floor(YB - 3 - life * (k === 2 ? 20 : 38));
    const col = life < 0.35 ? FIRE[3] : life < 0.65 ? FIRE[2] : FIRE[1];
    c.set(x, y, col);
    if (life < 0.4 && i % 2 === 0) c.set(x, y + 1, FIRE[1]);                 // short trail on the young ones
  }
  return c;
}

export default {
  meta: { id: 'cape-inferno', name: 'Inferno Cape', kind: 'cape', cents: 899, blurb: 'Charred black cloth with living flames licking up from a glowing hem.' },
  cape: { scale: S, frames: N, fps: FPS },
  fx: [{ bone: 'body', at: [0, -14, -3.4], spread: [7, 0.6, 0.4], color: '#ff6a1a', color2: '#ffc65a', perSecond: 3, life: [1.6, 2.6], size: 0.35, rise: 3.5, drift: [0, 0, -0.8], glow: true }],
  paint: paintFrame,
};
