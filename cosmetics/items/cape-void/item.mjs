// Void cape: inky purple-black cloth with a gravitational rift in the middle (a black hole seen
// slightly from the side: dark core, a thin hot photon ring, a tilted accretion disc whose near half
// crosses the core, and the far half lensed into an arc over the top), sparse hand-placed stars with
// tiny cross glints, and soft violet nebula clusters in the cloth. Static, scale 8: painted 1 art pixel =
// 1 texture pixel on the 80x128 outside face. Light is top-left (the disc's left side is the bright,
// approaching one); shadows run toward indigo, lights toward lilac/white. Gradients are halftone
// CLUSTERS (lib cluster dither), never flat bands.
import { ramp, rampThrough, clamp, smoothstep, mix, cluster, fbm, rng } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 8, W = 10 * S, H = 16 * S;
const CX = 40, CY = 60;                      // rift centre (pixel seam)
const HOLE = 10.5;                           // event-horizon radius
const TILT = -14 * Math.PI / 180;            // disc tilt: right end rises
const DISC_RX = 33, DISC_RY = 8.2;

// 9-step cloth, near-black violet; step 0 = outlines/seams, 8 = lit fold ridge.
const cloth = ramp('#150f22', 9, { lo: 0.02, hi: 0.17, tint: 0.2, shadowHue: 262, lightHue: 276, shift: 0.3, satLo: 1.0, satHi: 1.0 });
// accretion / glow ramp: indigo -> violet -> lilac -> near-white
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
const heat = (h, th) => HEAT[Math.min(HEAT.length - 1, Math.floor(clamp(h) * (HEAT.length - 1) + (clamp(h) * (HEAT.length - 1) % 1 > th ? 1 : 0)))];

// Accretion-disc heat at a pixel, or -1 when the disc is not there. Hot at the hole, cooling outward,
// with orbital streaks (noise stretched along the orbit) and a brighter approaching (left) side.
function discHeat(p, front) {
  const ee = Math.hypot(p.u / DISC_RX, p.v / DISC_RY), e0 = HOLE * 0.98 / DISC_RX;
  if (ee < e0 || ee > 1.02) return -1;
  if (front ? p.v < 0 : p.v >= 0) return -1;
  const radial = (1 - (ee - e0) / (1 - e0));
  const streak = fbm(p.ang * 2.6 + 3, ee * 16, 11, 3);
  let h = Math.pow(clamp(radial), 1.35) * (0.78 + 0.5 * streak) * (1 + 0.22 * (-p.u / DISC_RX));
  h *= smoothstep(1.02, 0.84, ee);                          // soft outer rim
  h *= smoothstep(0, 0.12, Math.abs(p.v) / DISC_RY + 0.1);  // thin dark seam where the halves meet
  return clamp(h);
}

// ---------------------------------------------------------------- stars (hand-placed)
const STARS = [
  // big: [x, y, glint arm length]
  { x: 14, y: 22, arm: 6, big: true }, { x: 66, y: 98, arm: 7, big: true }, { x: 60, y: 19, arm: 5, big: true },
  // medium: 2 px
  { x: 31, y: 16, med: true }, { x: 8, y: 49, med: true }, { x: 72, y: 38, med: true }, { x: 22, y: 100, med: true },
  { x: 49, y: 105, med: true }, { x: 71, y: 74, med: true }, { x: 6, y: 83, med: true },
  // small cross glints: 1 px core with 2 px arms
  { x: 45, y: 28, arm: 2, tiny: true }, { x: 34, y: 92, arm: 2, tiny: true }, { x: 78, y: 12, arm: 2, tiny: true }, { x: 10, y: 66, arm: 2, tiny: true },
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
      put(s.x, s.y, col); put(s.x + 1, s.y, mix(c.get(s.x + 1, s.y), col, 0.7)); put(s.x, s.y + 1, mix(c.get(s.x, s.y + 1), col, 0.7)); put(s.x + 1, s.y + 1, mix(c.get(s.x + 1, s.y + 1), col, 0.35));
    } else {
      put(s.x, s.y, col);
      for (let i = 1; i <= s.arm; i++) for (const [dx, dy] of [[i, 0], [-i, 0], [0, i], [0, -i]]) put(s.x + dx, s.y + dy, mix(c.get(s.x + dx, s.y + dy), col, i === 1 ? 0.55 : 0.28));
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
    put(x, y, t < 0.12 ? warm : t < 0.4 ? cool : mix(cool, '#6a5aa8', 0.55 + 0.3 * r()), 1);
    placed++;
  }
}

export default {
  meta: { id: 'cape-void', name: 'Void Cape', kind: 'cape', cents: 599, blurb: 'A black hole rift in violet-black cloth, ringed in light, under a scatter of stars.' },
  cape: { scale: S, frames: 1 },
  paint() {
    const c = newCapeArt(S);
    // ------- cloth: fold light + nebula clusters + glow around the rift, one value 0..1 per pixel, then picked on the ramp
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const p = disc(x, y);
      const fold = foldT(x, y);
      const neb = fbm(x * 0.045, y * 0.04, 5, 4);                     // broad clouds
      const nebCl = smoothstep(0.5, 0.8, neb) * (1 - smoothstep(30, 54, p.r) * 0.0);
      const far = smoothstep(18, 56, p.r);                           // 0 at the rift .. 1 far from it
      let base = 0.12 + fold * 0.3 + (0.5 - y / H) * 0.1;            // the cloth itself
      base -= 0.1 * smoothstep(0.5, 1.1, Math.hypot((x - 40) / 46, (y - 64) / 80));
      let t = base * 1.0;
      const idx = Math.floor(clamp(t) * 9);
      let col = cloth[clamp(idx, 1, 6)];
      // nebula: halftone clusters of GLOW steps over the cloth, strongest away from the rift's own glow
      const nv = clamp(nebCl * 0.8 * (0.55 + 0.45 * far) + fold * 0.12 * nebCl);
      if (nv > 0.05) {
        const gi = Math.floor(nv * (GLOW.length - 1) + (((nv * (GLOW.length - 1)) % 1) > cluster(x, y, 4) ? 1 : 0));
        col = mix(col, GLOW[Math.min(GLOW.length - 1, gi)], 0.55 + 0.25 * fold);
      }
      // rift glow: radial falloff, cluster dithered, swirled by a spiral so it reads as flowing, not a round blob
      const swirl = 0.5 + 0.5 * Math.sin(p.ang * 2 + Math.log(Math.max(p.r, 1)) * 3.1);
      const g = Math.pow(1 - smoothstep(HOLE, 46, p.r), 2.0) * (0.55 + 0.45 * swirl);
      if (g > 0.03) {
        const gv = g * 0.78, gi = Math.floor(gv * (HEAT.length - 2) + (((gv * (HEAT.length - 2)) % 1) > cluster(x + 1, y + 2, 3) ? 1 : 0));
        col = mix(col, HEAT[Math.min(HEAT.length - 3, gi)], clamp(0.35 + g * 0.55));
      }
      c.set(x, y, col);
    }

    // ------- collar: darker gathered band under a seam
    for (let y = 1; y < 7; y++) for (let x = 0; x < W; x++) c.set(x, y, mix(cloth[1 + ((x >> 3) & 1)], cloth[0], 0.35));
    for (let x = 0; x < W; x++) { c.set(x, 7, cloth[0]); c.set(x, 8, ((x >> 1) & 1) ? '#3a2d63' : '#2a2049'); }

    // ------- the rift proper
    // lensed far half of the disc: a bright cap over the top of the hole (and a thin one under it)
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const p = disc(x, y);
      if (p.r < HOLE || p.r > 19.5) continue;
      const top = p.dy < 0 ? 1 : 0.38;
      const arc = (1 - (p.r - HOLE) / (19.5 - HOLE)) ** 1.9 * top * (1 - Math.min(1, Math.abs(p.dx) / 17) ** 2.2);
      if (arc <= 0.03) continue;
      const h = clamp(arc * 0.95 * (1 + 0.15 * (-p.dx / 17)));
      const base = c.get(x, y), v = HEAT[Math.floor(h * (HEAT.length - 1) + ((h * (HEAT.length - 1)) % 1 > cluster(x, y, 3) ? 1 : 0)) % HEAT.length];
      c.set(x, y, mix(base, v, clamp(0.25 + h * 0.85)));
    }
    // back half of the disc beyond the hole, then the hole itself, then the photon ring, then the near half over the hole
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const p = disc(x, y), h = discHeat(p, false);
      if (h >= 0 && p.r >= HOLE) c.set(x, y, heat(h, cluster(x, y, 3)));
    }
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const p = disc(x, y);
      if (p.r < HOLE) c.set(x, y, p.r < HOLE - 3 ? '#030208' : mix('#030208', '#0e0a1c', smoothstep(HOLE - 3, HOLE, p.r) * 0.6));
      // photon ring: thin and hot, brightest top-left
      else if (p.r < HOLE + 1.6) {
        const lit = 0.55 + 0.45 * clamp((-p.dx - p.dy) / (HOLE * 1.4) * 0.5 + 0.5);
        c.set(x, y, p.r < HOLE + 0.6 ? mix('#e4d8ff', '#ffffff', lit) : mix(c.get(x, y), '#b79cf2', 0.72));
      }
    }
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const p = disc(x, y), h = discHeat(p, true);
      if (h >= 0) c.set(x, y, heat(h * 1.05, cluster(x, y, 3)));
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
