// Inferno Cape (try 3): charred black cloth, one solid fire bed along the hem with four teardrop
// flame tongues (one tall centre, two medium, one short) rising from it, and a dithered heat glow. Animated, scale 4: 1 art pixel = 4
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
const YB = H - 5;                                        // flame base row (the crust sits below it)

const clothFine = ramp('#1e1d22', 9, { lo: 0.02, hi: 0.115, tint: 0.025, shadowHue: 250, lightHue: 18, shift: 0.3 });
const FIRE = ['#7a1612', '#cf2a14', '#ff7a18', '#ffc02e', '#fff0c0'];   // deep red, red, orange, yellow, pale core
const WARM = ['#2a1114', '#451612', '#7a2010', '#b0340f'];              // cloth lit by the fire (dithered in)
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
const TONGUES = [      // c = base column, w = base half-width, h = height, a = slight S, s = side-to-side tip sway
  { c: 6.5, w: 6.0, h: 32, a: 2.6, s: 3.4, ph: 0.15 },     // medium
  { c: 18.5, w: 7.2, h: 45, a: 3.0, s: 4.6, ph: 0.55 },    // tall centre
  { c: 29.5, w: 6.2, h: 34, a: 2.6, s: 3.6, ph: 0.85 },    // medium
  { c: 37.5, w: 4.4, h: 20, a: 1.8, s: 2.6, ph: 0.35 },    // short
];
const tongueH = (T, t) => T.h * (1 + 0.07 * sinT(t, 1, T.ph));
const spine = (T, v, t) => T.c + T.a * Math.pow(v, 0.8) * Math.sin(TAU * (0.8 * v - t + T.ph)) + T.s * v * v * Math.sin(TAU * (t + T.ph));
function tongueAt(T, x, y, t) {                      // -> heat 0..1 at pixel centre, or -1 outside
  const v = (YB - y) / tongueH(T, t);
  if (v < 0 || v > 1) return -1;
  const wob = (pnoise(x / 3.5 + T.c, (y + t * 18) / 3.5, 6, 31) - 0.5) * 0.8;
  const wd = T.w * Math.pow(1 - v, 1.3) + wob * (0.3 + v) * 0.5;       // teardrop: wide at the base, tapering fast
  if (wd < 0.35) return -1;
  const dn = Math.abs(x - spine(T, v, t)) / wd;
  if (dn >= 1) return -1;
  return clamp((1 - Math.pow(dn, 1.1)) * (1.12 - 0.62 * v) + (pnoise(x / 2, y / 2 + t * 12, 6, 53) - 0.5) * 0.1);
}
// solid fire bed along the hem: ragged top edge, deep red at the top -> yellow at the bottom, no gaps
function bedAt(x, y, t) {
  const top = YB - 5 + (pnoise(x / 2.6, 3 + t * 8, 8, 17) - 0.5) * 3.2;
  if (y < top) return -1;
  const k = clamp((y - top) / (YB + 2 - top));
  return clamp(0.06 + 0.5 * k + (pnoise(x / 2.2, y / 1.6 + t * 6, 6, 29) - 0.5) * 0.24);
}

function paintFrame(f) {
  const t = f / N, c = newCapeArt(S), heat = new Float32Array(W * H).fill(-1);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, clothFine[clothLevel(x, y)]);

  // ---- fire field first (the glow needs to know where the flames are)
  for (let y = 0; y < H - 2; y++) for (let x = 0; x < W; x++) {
    let h = y >= YB - 8 ? bedAt(x + 0.5, y + 0.5, t) : -1;
    if (y < YB) for (const T of TONGUES) h = Math.max(h, tongueAt(T, x + 0.5, y + 0.5, t));
    heat[y * W + x] = h;
  }
  // ---- heat glow climbing the cloth: 4-step dither; strongest right above the flame edge, then fading up
  const pulse = 0.93 + 0.07 * sinT(t, 1, 0);
  for (let y = 7; y < H - 2; y++) for (let x = 1; x < W - 1; x++) {
    if (heat[y * W + x] >= 0) continue;
    let plume = 0;
    for (const T of TONGUES) plume = Math.max(plume, Math.exp(-(((x - T.c) / 11) ** 2)));
    let near = 0;                                                // share of flame pixels within 3 px: the rim glow
    for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) { const xx = x + dx, yy = y + dy; if (xx >= 0 && xx < W && yy >= 0 && yy < H && heat[yy * W + xx] >= 0) near++; }
    const L = Math.max(Math.pow(clamp(1 - (YB - y) / 56), 1.5) * (0.45 + 0.55 * plume) * 0.95, Math.min(1, near / 22) * 1.15) * pulse;
    const p = L * 4.6, fl = Math.floor(p), k = fl + (p - fl > bayer(x, y, 4) ? 1 : 0);
    if (k > 0) c.set(x, y, mix(c.get(x, y), WARM[Math.min(3, k - 1)], 0.92));
  }
  // ---- outline + collar
  for (let x = 0; x < W; x++) c.set(x, 0, OUTLINE);
  for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
  for (let y = 1; y < 5; y++) for (let x = 1; x < W - 1; x++) c.set(x, y, clothFine[clamp(clothLevel(x, y) - 1, 1, 3)]);
  for (let x = 1; x < W - 1; x++) { c.set(x, 5, OUTLINE); c.set(x, 6, (x >> 1) & 1 ? clothFine[4] : clothFine[3]); }
  // ---- flames: bands follow the teardrop
  for (let y = 0; y < H - 2; y++) for (let x = 0; x < W; x++) {
    const h = heat[y * W + x]; if (h < 0) continue;
    c.set(x, y, FIRE[h < 0.13 ? 0 : h < 0.3 ? 1 : h < 0.52 ? 2 : h < 0.78 ? 3 : 4]);
  }
  // ---- hem: two rows of charred crust with ember specks under the fire bed
  for (let y = H - 2; y < H; y++) for (let x = 0; x < W; x++) {
    const n = pnoise(x / 2.5, y / 2, 16, 91) + 0.3 * Math.sin(TAU * (t + hash2(x >> 1, y, 3)));
    c.set(x, y, y === H - 1 ? (n > 1.05 ? FIRE[0] : OUTLINE) : n > 0.95 ? FIRE[1] : '#3a150f');
  }
  // ---- embers: each breaks off a flame tip at a fixed point of the loop, then drifts up and sways
  const EMB = [[1, 0.05, 1], [0, 0.40, 1], [2, 0.62, 1], [1, 0.80, 0], [3, 0.22, 0]];   // [tongue, spawn phase, big?]
  EMB.forEach(([ti, ts, big], i) => {
    const life = ((t - ts) % 1 + 1) % 1; if (life > 0.9) return;
    const T = TONGUES[ti], x = Math.round(spine(T, 0.97, ts) + (i % 2 ? 1 : -1) * life * 5 + 1.8 * Math.sin(TAU * (life + i * 0.3)));
    const y = Math.round(YB - tongueH(T, ts) * 0.97 - life * 24);
    if (big && life < 0.55) { c.set(x, y, life < 0.2 ? FIRE[3] : FIRE[2]); c.set(x + 1, y, FIRE[1]); c.set(x, y + 1, FIRE[1]); c.set(x + 1, y + 1, FIRE[0]); }
    else if (life < 0.8) c.set(x, y, life < 0.3 ? FIRE[3] : FIRE[1]);
    else c.set(x, y, FIRE[0]);
  });
  return c;
}

export default {
  meta: { id: 'cape-inferno', name: 'Inferno Cape', kind: 'cape', cents: 899, blurb: 'Charred black cloth with living flames licking up from a glowing hem.' },
  cape: { scale: S, frames: N, fps: FPS },
  fx: [{ bone: 'body', at: [0, -14, -3.4], spread: [7, 0.6, 0.4], color: '#ff6a1a', color2: '#ffc65a', perSecond: 3, life: [1.6, 2.6], size: 0.35, rise: 3.5, drift: [0, 0, -0.8], glow: true }],
  paint: paintFrame,
};
