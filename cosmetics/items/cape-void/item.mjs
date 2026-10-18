// Void cape: inky purple-black cloth with a gravitational rift in the middle (a black hole seen
// slightly from the side: dark core, a thin hot photon ring, a tilted accretion disc whose near half
// crosses the core, and the far half lensed into an arc over the top), sparse hand-placed stars with
// tiny cross glints, and soft violet nebula clusters in the cloth. Static, scale 8: painted 1 art pixel =
// 1 texture pixel on the 80x128 outside face. Light is top-left (the disc's left side is the bright,
// approaching one); shadows run toward indigo, lights toward lilac/white. Gradients are halftone
// CLUSTERS (lib cluster dither), never flat bands.
import { ramp, rampThrough, clamp, smoothstep, mix, cluster, bayer, fbm, rng } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 8, W = 10 * S, H = 16 * S;
const CX = 40, CY = 60;                      // rift centre (pixel seam)
const HOLE = 11.5;                           // event-horizon radius (the rim is the outermost 1 px of this circle)
const TILT = -14 * Math.PI / 180;            // disc tilt: right end rises
const DISC_RX = 33, DISC_RY = 8.2;

// 9-step cloth, near-black violet; step 0 = outlines/seams, 8 = lit fold ridge.
const cloth = ramp('#150f22', 9, { lo: 0.02, hi: 0.17, tint: 0.2, shadowHue: 262, lightHue: 276, shift: 0.3, satLo: 1.0, satHi: 1.0 });
// accretion / glow ramp: indigo -> violet -> lilac -> near-white
// disc / lensed light: hot white-pink at the hole, cooling outward through lilac to a cool blue-violet
const DISC = rampThrough(['#101648', '#242a82', '#443fc0', '#7a62e0', '#c394e6', '#f6c4e6', '#fff7fb'], 7);
const HEAT = rampThrough(['#120b26', '#2a1a58', '#4b2f94', '#7a56cf', '#b79cf2', '#e4d8ff', '#faf6ff'], 7);
const GLOW = rampThrough(['#150f22', '#1d1336', '#2a1b4d', '#3b2670', '#52399a'], 5);

// ---------------------------------------------------------------- folds (cape-origin construction, softer)
const VALLEYS = [-3, 19, 46, 62, 83];
const valleyX = (k, y) => 40 + (VALLEYS[k] - 40) * (0.95 + 0.1 * (y / H)) + Math.sin(y * 0.04 + k * 1.9) * 1.6;
function foldProfile(p) {
  const r = 0.38;
  return p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r));
}
function foldT(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a));
  return foldProfile(p);                                    // 0 valley .. 1 ridge
}

// ---------------------------------------------------------------- the rift
function disc(x, y) {                                       // tilted-frame coordinates of a pixel centre
  const dx = x + 0.5 - CX, dy = y + 0.5 - CY, c = Math.cos(TILT), s = Math.sin(TILT);
  return { u: dx * c + dy * s, v: -dx * s + dy * c, r: Math.hypot(dx, dy), ang: Math.atan2(dy, dx), dx, dy };
}
// A mix amount quantised to 1/12 steps with a small cluster nudge: gradients stay smooth (no screen-door grid)
// but the step edges break into round clusters instead of hard contour lines.
const soft = (a, x, y) => clamp(Math.round(a * 12 + (cluster(x, y, 4) - 0.5) * 0.9) / 12);
const heat = (h, th) => HEAT[Math.min(HEAT.length - 1, Math.floor(clamp(h) * (HEAT.length - 1) + (clamp(h) * (HEAT.length - 1) % 1 > th ? 1 : 0)))];

// Accretion-disc heat at a pixel, or -1 when the disc is not there. Hot at the horizon, cooling outward,
// with orbital streaks (noise stretched along the orbit) and a brighter approaching (left) side. The disc
// is two wings either side of the horizon: nothing is drawn inside the rim, so the core stays a clean circle.
function discHeat(p) {
  const ee = Math.hypot(p.u / DISC_RX, p.v / DISC_RY), e0 = (HOLE - 1.5) / DISC_RX;
  if (ee < e0 || ee > 1.02) return -1;
  const radial = 1 - (ee - e0) / (1 - e0);
  const streak = fbm(p.ang * 2.6 + 3, ee * 16, 11, 3);
  let h = Math.pow(clamp(radial), 1.15) * (0.8 + 0.4 * streak) * (1 + 0.2 * (-p.u / DISC_RX));
  h *= smoothstep(1.02, 0.82, ee);                          // soft outer rim
  h *= 0.55 + 0.45 * (1 - Math.min(1, Math.abs(p.v) / DISC_RY) ** 1.6);   // brightest on the disc's mid-plane
  return clamp(h);
}
// Lensed light wrapped round the horizon: ONE continuous ring just outside the rim, thick over the top
// (the far side of the disc bent over the hole) and thin underneath. -1 outside it.
function arcHeat(p) {
  const out = Math.max(0, p.r - (HOLE - 1.5));        // starts under the rim so no dark seam shows between rim and ring
  if (p.r < HOLE - 1.5) return -1;
  const up = Math.max(0, -p.dy / p.r), down = Math.max(0, p.dy / p.r);
  const thick = 1.6 + 6.4 * Math.pow(up, 1.25) + 1.2 * down;       // px: ~8 at the top, ~2.8 at the bottom, 1.6 at the sides
  if (out >= thick) return -1;
  const f = 1 - out / thick;
  return clamp(Math.pow(f, 1.1) * (0.62 + 0.38 * up + 0.2 * down) * (1 + 0.14 * (-p.dx / HOLE)));
}
const toneAt = (ramp_, h, x, y) => {                         // ramp colour for h in 0..1, stepped, cluster-nudged at the step edges
  const g = clamp(h) * (ramp_.length - 1) + (cluster(x, y, 3) - 0.5) * 0.55;
  return ramp_[clamp(Math.round(g), 0, ramp_.length - 1)];
};

// ---------------------------------------------------------------- stars (hand-placed)
const STARS = [
  // big sparkles of three different sizes: cross glints with a soft diagonal shimmer
  { x: 13, y: 21, arm: 9, big: true }, { x: 66, y: 99, arm: 6, big: true }, { x: 60, y: 19, arm: 3, big: true },
  // medium: 2 px, mixed brightness
  { x: 31, y: 16, med: true, k: 0.8 }, { x: 8, y: 49, med: true, k: 0.5 }, { x: 72, y: 38, med: true, k: 1 }, { x: 22, y: 100, med: true, k: 0.6 },
  { x: 49, y: 106, med: true, k: 0.9 }, { x: 71, y: 76, med: true, k: 0.45 }, { x: 6, y: 83, med: true, k: 0.7 },
];
function placeStars(c) {
  const put = (x, y, col, k = 1) => { if (x > 1 && y > 9 && x < W - 2 && y < H - 11) c.set(x, y, k >= 1 ? col : mix(c.get(x, y), col, k)); };
  const cool = '#f4f0ff', warm = '#ffe9d2', glow = '#b9a3f0';
  for (const s of STARS) {
    const col = (s.x + s.y) % 3 === 0 ? warm : cool;
    if (s.big) {
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) if (dx === 0 || dy === 0) put(s.x + dx, s.y + dy, '#ffffff');   // 3 px plus core
      put(s.x - 1, s.y - 1, glow, 0.5); put(s.x + 1, s.y - 1, glow, 0.5); put(s.x - 1, s.y + 1, glow, 0.5); put(s.x + 1, s.y + 1, glow, 0.5);
      for (let i = 2; i <= s.arm; i++) {                                               // cross glint: arms fade out
        const k = 1 - (i - 1.5) / (s.arm - 0.5);
        for (const [dx, dy] of [[i, 0], [-i, 0], [0, i], [0, -i]]) put(s.x + dx, s.y + dy, mix(c.get(s.x + dx, s.y + dy), col, 0.25 + 0.65 * k));
      }
    } else if (s.med) {
      const k = s.k ?? 1, dim = mix(col, '#7b6cc0', 1 - k);
      put(s.x, s.y, dim); put(s.x + 1, s.y, mix(c.get(s.x + 1, s.y), dim, 0.7)); put(s.x, s.y + 1, mix(c.get(s.x, s.y + 1), dim, 0.7)); put(s.x + 1, s.y + 1, mix(c.get(s.x + 1, s.y + 1), dim, 0.35));
    }
  }
  // dust: 1 px stars scattered with a fixed seed, kept out of the bright rift and off the stars above
  const r = rng('void-dust'), taken = new Set(STARS.map(s => `${s.x},${s.y}`));
  let placed = 0, tries = 0;
  while (placed < 46 && tries++ < 4000) {
    const x = 3 + r.int(W - 6), y = 12 + r.int(H - 26), p = disc(x, y);
    if (Math.hypot(p.u / (DISC_RX + 6), p.v / 18) < 1 || p.r < 24) continue;
    if (STARS.some(s => Math.hypot(s.x - x, s.y - y) < 6) || taken.has(`${x},${y}`)) continue;
    const t = r();
    put(x, y, t < 0.08 ? warm : t < 0.22 ? '#ffffff' : t < 0.45 ? cool : mix('#8a7acb', '#4a3d7a', r()), 1);   // wide brightness range
    placed++;
  }
}

export default {
  meta: { id: 'cape-void', name: 'Void Cape', kind: 'cape', cents: 599, blurb: 'A black hole rift in violet-black cloth, ringed in light, under a scatter of stars.' },
  cape: { scale: S, frames: 1 },
  paint() {
    const c = newCapeArt(S);
    // ------- cloth: soft fold light, isotropic nebula clouds as ordered-dither pixel clusters, a glow round the rift
    const NEB = ['#1d1336', '#2a1b4d', '#3a2a6e', '#4b3a8c'];
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const p = disc(x, y), fold = foldT(x, y);
      let base = 0.13 + fold * 0.17 + (0.5 - y / H) * 0.1;           // the cloth itself: folds kept gentle
      base -= 0.1 * smoothstep(0.5, 1.1, Math.hypot((x - 40) / 46, (y - 64) / 80));
      let col = cloth[clamp(Math.floor(clamp(base) * 9), 1, 5)];
      // nebula: domain-warped fbm, thresholded into 3 tones; ordered (bayer 4) dither only across each narrow edge
      const wx = x + 14 * (fbm(x * 0.03, y * 0.03, 21, 2) - 0.5), wy = y + 14 * (fbm(x * 0.03 + 9, y * 0.03, 22, 2) - 0.5);
      const neb = fbm(wx * 0.05, wy * 0.045, 5, 4) * (0.55 + 0.45 * smoothstep(16, 52, p.r));
      const lv = (neb - 0.36) / 0.07;                                 // 0 = no cloud; each whole step = next tone
      if (lv > 0) {
        const idx = Math.min(NEB.length, Math.floor(lv + bayer(x, y, 4)));   // dither across the edge between steps
        if (idx > 0) col = NEB[idx - 1];
      }
      // rift glow: radial falloff, swirled by a spiral, stepped onto the violet ramp with a light cluster nudge
      const swirl = 0.5 + 0.5 * Math.sin(p.ang * 2 + Math.log(Math.max(p.r, 1)) * 3.1);
      const g = Math.pow(1 - smoothstep(HOLE, 46, p.r), 2.0) * (0.55 + 0.45 * swirl);
      if (g > 0.03) {
        const gv = g * 0.8 * (HEAT.length - 3), lo = Math.floor(gv), tone = mix(HEAT[lo], HEAT[lo + 1], gv - lo);
        col = mix(col, tone, soft(0.3 + g * 0.6, x + 1, y + 2));
      }
      c.set(x, y, col);
    }

    // ------- collar: darker gathered band under a seam
    for (let y = 1; y < 7; y++) for (let x = 0; x < W; x++) c.set(x, y, mix(cloth[1 + ((x >> 3) & 1)], cloth[0], 0.35));
    for (let x = 0; x < W; x++) { c.set(x, 7, cloth[0]); c.set(x, 8, ((x >> 1) & 1) ? '#3a2d63' : '#2a2049'); }

    // ------- the rift proper: disc wings + lensed ring (hotter wins), then the clean horizon circle with its 1 px rim
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const p = disc(x, y), d = discHeat(p), a = arcHeat(p), h = Math.max(d, a);
      if (h <= 0.02) continue;
      const col = toneAt(DISC, h * 1.04, x, y);
      c.set(x, y, h > 0.12 ? col : mix(c.get(x, y), col, h / 0.12));
    }
    const inside = (x, y) => Math.hypot(x + 0.5 - CX, y + 0.5 - CY) < HOLE;        // the horizon disc
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      if (!inside(x, y)) continue;
      const rim = !inside(x + 1, y) || !inside(x - 1, y) || !inside(x, y + 1) || !inside(x, y - 1);   // 4-neighbour outline = an even 1 px ring
      if (!rim) { c.set(x, y, '#030208'); continue; }
      const lit = clamp(0.5 + 0.5 * (-(x + 0.5 - CX) - (y + 0.5 - CY)) / (HOLE * 1.41));   // top-left brightest
      c.set(x, y, mix('#f0d8f8', '#ffffff', lit));
    }

    // ------- hem: one fold-over with a single stitched line, violet thread
    const hy = H - 9;
    for (let x = 0; x < W; x++) {
      c.set(x, hy, cloth[0]);
      c.set(x, hy + 1, cloth[clamp(Math.round(2 + foldT(x, hy) * 3), 1, 6)]);
      for (let y = hy + 2; y < H - 1; y++) c.set(x, y, cloth[clamp(Math.round(1 + foldT(x, y) * 3), 1, 4)]);
      c.set(x, hy + 3, ((x % 4) === 3) ? cloth[1] : '#6d5aa6');
      c.set(x, hy + 4, cloth[0]);
    }

    placeStars(c);

    // ------- 1 px dark outline all round
    const OUT = cloth[0];
    for (let x = 0; x < W; x++) { c.set(x, 0, OUT); c.set(x, H - 1, OUT); }
    for (let y = 0; y < H; y++) { c.set(0, y, OUT); c.set(W - 1, y, OUT); }
    return c;
  },
};
