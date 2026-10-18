// Aurora Cape (try 3): night-sky cloth under an S-shaped aurora ribbon (soft lower edge, vertical
// rays that fade green -> teal -> violet -> pink tips) with a fainter ribbon behind it, a varied
// star field, and a crisp near-black pine treeline against a faint horizon glow.
// Animated, scale 4: 1 art pixel = 4 texture pixels on the 40x64 outside face, 24 frames.
//
// The ribbon's lower edge is one full sine across the cape (the S) plus a travelling ripple; where
// the edge is steep the ribbon is seen edge-on, so it gets brighter and shorter (a fold). Ray
// brightness is ordered-dithered between a 4-step ramp per hue. Every motion is a whole number of
// cycles per loop, so the loop is seamless.
import { clamp, smoothstep, mix, hash2, bayer, rampThrough } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, N = 24, FPS = 8, TAU = Math.PI * 2;
const SKY = rampThrough(['#080b22', '#0a1129', '#0c1a33', '#0d2038'], 14);          // smooth banded ramp, no checker dither
const GLOW_PEAK = '#2f8f74';                                                    // horizon glow colour (kept off the ribbon)
const GREEN = ['#0d4442', '#13785a', '#2cc27e', '#9af7b8'];
const TEAL = ['#0c3a4c', '#12667e', '#27b6bc', '#8ceee2'];
const VIOLET = ['#1d1856', '#40308c', '#7d52c8', '#c595f2'];
const PINK = ['#3a1654', '#7a2c8c', '#cc5cc2', '#f7b0e4'];
const OUTLINE = '#04060e';
const TREE_NEAR = '#010206', TREE_FAR = '#08171f';

const sinT = (t, m, ph) => Math.sin(TAU * (t * m + ph));

// RIBBONS: base = mean row of the lower edge, amp = S swing, len = ray length, dim, hue (shifts toward violet/pink)
const RIBBONS = [
  { base: 22, amp: 4.5, ph: 0.62, len: 13, seed: 9, dim: 0.34, hue: 0.3 },     // far
  { base: 35, amp: 6.0, ph: 0.0, len: 29, seed: 3, dim: 1.0, hue: 0.0 },        // near: the S
];
const edgeAt = (R, x, t) => R.base + R.amp * (1 + 0.1 * sinT(t, 1, R.ph)) * Math.sin(TAU * (x / W + R.ph)) + 2.2 * Math.sin(TAU * (2 * x / W - t + R.ph * 2.3));
function ribbon(R, x, y, t) {
  const e = edgeAt(R, x, t), slope = (edgeAt(R, x + 1, t) - edgeAt(R, x - 1, t)) / 2, fold = clamp(Math.abs(slope) / 1.1);
  const d = e - y;                                                  // rows above the lower edge
  if (d < -4.5) return null;
  const ph = R.seed * 0.37;
  const ray = Math.pow(0.5 + 0.5 * Math.sin(x * 1.75 + ph + 1.3 * sinT(t, 1, ph)) * Math.sin(x * 0.67 + ph * 2 - 0.9 * sinT(t, -1, ph)), 2.0);   // squared: fewer, cleaner rays with dark gaps
  const longRay = 0.5 + 0.5 * Math.sin(x * 0.43 + ph * 3);
  const len = Math.min(33, R.len * (0.62 + 0.55 * longRay) * (1 - 0.3 * fold));
  const u = clamp(d / len);
  // soft lower edge: rises over 7 rows to its peak 1.5 rows above the edge, then rays fade upward
  const low = d < 1.5 ? Math.pow(smoothstep(-4.5, 1.5, d), 1.6) : 1;
  const up = d < 1.5 ? 1 : Math.pow(1 - u, 1.35);
  const tip = Math.exp(-(((u - 0.78) / 0.16) ** 2)) * Math.pow(ray, 1.3) * 0.55;     // a few rays keep a pink-violet tip
  const inten = (low * up * (0.24 + 0.76 * ray) * 0.95 + tip) * (1 + 0.35 * fold) * R.dim;
  return { u: clamp(u + (d < 1.5 ? 0 : 0)), inten: clamp(inten * 1.2), hp: u };
}

// stepped fir: 3-4 tiers, each a small triangle that starts narrower than the one above ends (a ledge), 2 px trunk
function fir(c, cx, tipY, h, color) {
  const n = h >= 16 ? 4 : 3, body = h - 2, th = Math.ceil(body / n);
  for (let r = 0; r < h; r++) {
    let hw = 0;
    if (r < body) { const tier = Math.min(n - 1, Math.floor(r / th)), rt = r - tier * th; hw = tier + Math.floor(rt * 0.85); if (tier === 0 && rt === 0) hw = 0; }
    for (let dx = -hw; dx <= hw; dx++) c.set(cx + dx, tipY + r, color);
  }
}
const treesFor = (seed, h0, hv, g0, gv) => { const out = []; for (let x = 2, i = 0; x < W + 3; i++) { out.push({ cx: Math.round(x), h: h0 + Math.floor(hash2(i, 3, seed) * hv) }); x += g0 + hash2(i, 4, seed) * gv; } return out; };
const FAR = treesFor(71, 8, 4, 5.0, 3.0), NEAR = treesFor(61, 13, 8, 7.0, 3.0);

function paintFrame(f) {
  const t = f / N, c = newCapeArt(S), breathe = 0.65 + 0.35 * sinT(t, 1, 0.2);
  // ---- sky: smooth banded ramp (no dither) + a horizon glow in clean 1-2 row bands, well below the ribbon
  for (let y = 0; y < H; y++) {
    let col = SKY[Math.min(SKY.length - 1, Math.floor(y / H * SKY.length))];
    const g = smoothstep(52, 59, y) * (1 - 0.55 * smoothstep(58, 63, y)) * (0.85 + 0.15 * breathe);
    col = mix(col, GLOW_PEAK, Math.round(g * 5) / 5 * 0.62);
    for (let x = 0; x < W; x++) c.set(x, y, col);
  }
  // ---- stars: varied size + brightness; a few twinkle; 3 sparkle crosses
  for (let i = 0; i < 44; i++) {
    const x = 2 + Math.floor(hash2(i, 1, 21) * (W - 4)), y = 8 + Math.floor(Math.pow(hash2(i, 2, 21), 1.25) * 36);
    const tw = i % 3 === 0 ? 0.5 + 0.5 * sinT(t, 1, hash2(i, 3, 21)) : 1, big = hash2(i, 4, 21);
    const base = big > 0.86 ? 0.8 : big > 0.55 ? 0.5 : 0.24, col = i % 5 === 0 ? '#b8c6ff' : i % 7 === 0 ? '#ffe9c8' : '#eef2fc';
    c.set(x, y, mix(c.get(x, y), col, base * (0.6 + 0.4 * tw)));
    if (big > 0.93) for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) c.set(x + dx, y + dy, mix(c.get(x + dx, y + dy), col, 0.3 * tw));
  }
  // ---- ribbons (far first)
  for (const R of RIBBONS) {
    for (let y = 4; y < H - 4; y++) for (let x = 1; x < W - 1; x++) {
      const r = ribbon(R, x + 0.5, y + 0.5, t); if (!r || r.inten < 0.07) continue;
      const th = bayer(x, y, 4), hp = r.hp + R.hue * 0.5 + (th - 0.5) * 0.14;
      const pal = hp < 0.28 ? GREEN : hp < 0.5 ? TEAL : hp < 0.74 ? VIOLET : PINK;
      const p = r.inten * 4.6, fl = Math.floor(p), k = clamp(fl - 1 + (p - fl > 0.5 + (th - 0.5) * 0.45 ? 1 : 0), -1, 3);   // narrow dither: mostly crisp steps
      if (k < 0) continue;
      c.set(x, y, k === 0 ? mix(c.get(x, y), pal[0], 0.8) : pal[k]);
    }
  }
  // ---- crest: one clean bright line along the S (near ribbon's lower edge), so the ribbon's form reads under the rays
  {
    const R = RIBBONS[RIBBONS.length - 1];
    let prev = Math.round(edgeAt(R, 0.5, t) - 1.5);
    for (let x = 1; x < W - 1; x++) {
      const y = Math.round(edgeAt(R, x + 0.5, t) - 1.5), lo = Math.min(prev, y), hi = Math.max(prev, y);
      for (let yy = lo; yy <= hi; yy++) c.set(x, yy, '#c8fbe0');
      c.set(x, hi + 1, mix(c.get(x, hi + 1), '#58e8a4', 0.7));                                   // 1 px of bright green under the line
      prev = y;
    }
  }
  // ---- collar band
  for (let x = 1; x < W - 1; x++) { for (let y = 1; y < 5; y++) c.set(x, y, '#060919'); c.set(x, 5, OUTLINE); c.set(x, 6, (x >> 1) & 1 ? '#232a4a' : '#161b34'); }
  // ---- treeline: far layer, near layer, ground
  for (const T of FAR) fir(c, T.cx, H - 2 - T.h, T.h, TREE_FAR);
    for (const T of NEAR) fir(c, T.cx, H - 1 - T.h, T.h, TREE_NEAR);
  for (let x = 0; x < W; x++) for (let y = H - 3; y < H; y++) c.set(x, y, TREE_NEAR);
  // ---- 1 px dark outline all round
  for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
  for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
  return c;
}

export default {
  // Dropped 2026-10-18: judged 7/10 after 3 tries (Will's rule: under 8 after 3 tries, dump it). Kept so
  // anyone who bought the round-1 Aurora Cape still owns and wears it; never sold again.
  meta: { id: 'cape-aurora', name: 'Aurora Cape', kind: 'cape', cents: 899, onSale: false, blurb: 'A rippling green, teal and violet aurora over a starry night and a dark pine horizon.' },
  cape: { scale: S, frames: N, fps: FPS },
  paint: paintFrame,
};
