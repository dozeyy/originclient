// Inferno Cape (try 2): charred black cloth, four big curved flame tongues rising from a cracked
// ember hem, and a dithered heat glow that climbs the cloth. Animated, scale 4: 1 art pixel = 4
// texture pixels on the 40x64 outside face, 24 frames.
//
// Each tongue is a teardrop around an S-shaped spine; the spine wave travels UP the flame so the
// tongue writhes instead of bouncing. Shading is concentric around the spine, hottest near the
// base: deep red rim -> red -> orange -> yellow -> pale core, so the layers follow the curve.
// A warm glow (4-step dither) fades up the cloth from the tongues. Five large embers rise through
// the loop. Every motion is a whole number of cycles per loop (seamless). Cape textures have no
// emissive channel, so the flame cores are simply the brightest pixels.
import { ramp, clamp, smoothstep, mix, hash2, bayer } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, N = 24, FPS = 12, TAU = Math.PI * 2;
const YB = H - 4;                                        // flame base row (the crust sits below it)

const clothFine = ramp('#1e1d22', 9, { lo: 0.02, hi: 0.115, tint: 0.025, shadowHue: 250, lightHue: 18, shift: 0.3 });
const FIRE = ['#7a1612', '#cf2a14', '#ff7a18', '#ffc02e', '#fff0c0'];   // deep red, red, orange, yellow, pale core
const WARM = ['#2a1114', '#401511', '#6a1b0f', '#982a0e'];              // cloth lit by the fire (dithered in)
const OUTLINE = '#08080b';
const sinT = (t, m, ph) => Math.sin(TAU * (t * m + ph));

// periodic value noise: repeats every K lattice cells in y, so scrolling by K cells loops
function pnoise(x, y, K, seed) {
  const xi = Math.floor(x), yi = Math.floor(y), fx = x - xi, fy = y - yi;
  const u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy), w = j => ((j % K) + K) % K;
  const a = hash2(xi, w(yi), seed), b = hash2(xi + 1, w(yi), seed), c = hash2(xi, w(yi + 1), seed), d = hash2(xi + 1, w(yi + 1), seed);
  return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v;
}

// ---- cloth ----
const VALLEYS = [-2, 11, 21, 31, 42];
const valleyX = (k, y) => VALLEYS[k] + Math.sin(y * 0.09 + k * 1.9) * 0.8;
const foldProfile = p => { const r = 0.4; return p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r)); };
function clothLevel(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a));
  let t = 0.22 + foldProfile(p) * 0.46 + (0.5 - y / H) * 0.1;
  t += (pnoise(x / 6, y / 7, 64, 7) - 0.5) * 0.16;
  if ((x + y * 2) % 5 === 0) t -= 0.05;                                  // faint weave
  return clamp(Math.floor(clamp(t) * clothFine.length), 1, clothFine.length - 1);
}

// ---- flame tongues: c = base column, w = half-width at the base, h = height, a = S-curve sway ----
const TONGUES = [
  { c: 7.5, w: 5.0, h: 30, a: 3.6, ph: 0.00 },
  { c: 17.5, w: 5.6, h: 42, a: 4.4, ph: 0.30 },
  { c: 28.0, w: 5.4, h: 36, a: 4.0, ph: 0.62 },
  { c: 36.5, w: 4.4, h: 25, a: 3.0, ph: 0.85 },
];
function tongueAt(T, x, y, t) {                      // -> heat 0..1 at pixel centre, or -1 outside
  const v = (YB - y) / (T.h * (1 + 0.08 * sinT(t, 1, T.ph)));
  if (v < 0 || v > 1) return -1;
  // S spine: 1.1 waves over the height, travelling up; anchored at the base, whipping at the tip
  const cx = T.c + T.a * Math.pow(v, 0.8) * Math.sin(TAU * (1.1 * v - t + T.ph));
  const wob = (pnoise(x / 3.5 + T.c, (y + t * 18) / 3.5, 6, 31) - 0.5) * 1.0;
  const wd = T.w * Math.pow(1 - v, 0.8) + wob * (0.4 + v) * 0.6;
  if (wd < 0.3) return -1;
  const dn = Math.abs(x - cx) / wd;
  if (dn >= 1) return -1;
  return clamp((1 - Math.pow(dn, 1.1)) * (1.12 - 0.62 * v) + (pnoise(x / 2, y / 2 + t * 12, 6, 53) - 0.5) * 0.1);
}

function paintFrame(f) {
  const t = f / N, c = newCapeArt(S), heat = new Float32Array(W * H).fill(-1);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, clothFine[clothLevel(x, y)]);

  // ---- heat glow climbing the cloth: 4-step dither, strongest above the tongues
  const pulse = 0.92 + 0.08 * sinT(t, 1, 0);
  for (let y = 7; y < YB; y++) for (let x = 1; x < W - 1; x++) {
    let plume = 0;
    for (const T of TONGUES) plume = Math.max(plume, Math.exp(-(((x - T.c) / 11) ** 2)));
    const L = Math.pow(clamp(1 - (YB - y) / 56), 1.5) * (0.45 + 0.55 * plume) * pulse * 0.9;
    const p = L * 4.6, fl = Math.floor(p), k = fl + (p - fl > bayer(x, y, 4) ? 1 : 0);
    if (k > 0) c.set(x, y, mix(c.get(x, y), WARM[Math.min(3, k - 1)], 0.9));
  }
  // ---- outline + collar (drawn before the flames so fire can lick the sides)
  for (let x = 0; x < W; x++) c.set(x, 0, OUTLINE);
  for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
  for (let y = 1; y < 5; y++) for (let x = 1; x < W - 1; x++) c.set(x, y, clothFine[clamp(clothLevel(x, y) - 1, 1, 3)]);
  for (let x = 1; x < W - 1; x++) { c.set(x, 5, OUTLINE); c.set(x, 6, (x >> 1) & 1 ? clothFine[4] : clothFine[3]); }

  // ---- flames: hottest value of any tongue; bands follow the curve
  for (let y = 0; y < YB; y++) for (let x = 0; x < W; x++) {
    let h = -1;
    for (const T of TONGUES) h = Math.max(h, tongueAt(T, x + 0.5, y + 0.5, t));
    if (h < 0) continue;
    c.set(x, y, FIRE[h < 0.13 ? 0 : h < 0.3 ? 1 : h < 0.52 ? 2 : h < 0.78 ? 3 : 4]);
  }
  // ---- cracked hem crust with pulsing ember cracks
  for (let y = YB; y < H; y++) for (let x = 0; x < W; x++) {
    const n = pnoise(x / 3, y / 2, 16, 91) + 0.35 * Math.sin(TAU * (t + hash2(x >> 1, y, 3)));
    const crack = n > 1.0 - (y === YB ? 0.18 : 0.08);
    if (y === H - 1) c.set(x, y, crack ? FIRE[0] : OUTLINE);
    else if (crack) c.set(x, y, FIRE[n > 1.25 ? 3 : n > 1.12 ? 2 : 1]);
    else c.set(x, y, y === YB ? '#4a1a10' : clothFine[1 + (hash2(x, y, 4) > 0.7 ? 1 : 0)]);
  }
  // ---- five large embers, one rise per loop, staggered; hidden at both ends of their life
  const EMBERS = [[5, 0.00], [14, 0.46], [22, 0.18], [31, 0.70], [36, 0.93]];
  EMBERS.forEach(([x0, off], i) => {
    const life = (t + off) % 1; if (life < 0.06 || life > 0.94) return;
    const x = Math.round(x0 + 2.6 * Math.sin(TAU * (life * 1 + hash2(i, 1, 9)))), y = Math.round(YB - 14 - life * 46);
    const big = life < 0.62;
    if (big) { c.set(x, y, FIRE[2]); c.set(x + 1, y, FIRE[1]); c.set(x, y + 1, FIRE[1]); c.set(x + 1, y + 1, FIRE[0]); c.set(x, y, life < 0.3 ? FIRE[3] : FIRE[2]); }
    else c.set(x, y, FIRE[1]);
  });
  return c;
}

export default {
  meta: { id: 'cape-inferno', name: 'Inferno Cape', kind: 'cape', cents: 899, blurb: 'Charred black cloth with living flames licking up from a glowing hem.' },
  cape: { scale: S, frames: N, fps: FPS },
  fx: [{ bone: 'body', at: [0, -14, -3.4], spread: [7, 0.6, 0.4], color: '#ff6a1a', color2: '#ffc65a', perSecond: 3, life: [1.6, 2.6], size: 0.35, rise: 3.5, drift: [0, 0, -0.8], glow: true }],
  paint: paintFrame,
};
