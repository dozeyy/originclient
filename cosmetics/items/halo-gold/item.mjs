// Golden Halo: a classic polished gold ring floating above the head, tipped 9 degrees front-up (the
// way halos are drawn, and so it opens toward every eye-level view instead of reading as a flat
// pancake), a slow float, and one glint that slides round the ring every three seconds.
//
// The ring is a 32-sided torus of small box segments (0.75 px tube, 6 texels across). Gold is shaded
// by a fixed ROW TABLE, not a per-texel normal: every row of the tube cross-section gets one step of a
// 4-step hue-shifted gold ramp (red-brown underside -> orange -> amber -> lemon top rim), nudged by one
// step on the side away from the light, plus a 1 px pale specular line on the lit outer top edge. Same
// row -> same colour on every segment, so the bands run clean round the ring (no mottling).
// No glow plane: an additive plane seen edge-on collapses into a streak trailing off the ring.
// Clearance (FORMAT.md): halo, y 10..13 with the tilt, the rock and the float; tube 0.75 px (limit 1.5).
import { newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, TEX = [24, 8];
const YC = 11.5, R = 4.0, T = 0.75, N = 32;                     // ring centre y, centre-line radius, tube size, segments
const L = 0.875;                                                // segment length: the outer chord, rounded up to whole texels
const TILT = -9, FLOAT = 0.2, ROCK = 2;                         // front-up tilt (deg), float (px), side-to-side rock (deg)
const FRAMES = 24, FPS = 8;                                     // the glint goes round once a loop (3 s)
const rad = d => (d * Math.PI) / 180;
{
  const a = rad(Math.abs(TILT) + ROCK), top = (R / Math.cos(Math.PI / N)) * Math.sin(a) + (T / 2) * (Math.cos(a) + Math.sin(a)) + FLOAT;
  if (top > 1.5) throw new Error(`halo-gold: reaches ${top.toFixed(2)} px from the centre (max 1.5)`);
}

// ---- geometry: segment k sits at ring angle a_k, built along z at x = R and turned about y -------
// Each segment draws only its four long faces (the ends are buried in the neighbours), each face packed
// with a 1-texel border that repeats its edge texels, so no atlas neighbour can bleed into a seam.
const turn = a => ({ origin: [0, YC, 0], angle: [0, +a.toFixed(4), 0] });
const FACE_DIM = { up: [T, L], down: [T, L], east: [L, T], west: [L, T] };   // [width, height] in model px
const segs = [];
{
  const px = 1 / S;
  let u = 0, v = 0, rowH = 0;
  for (let k = 0; k < N; k++) {
    const a = (360 * k) / N, faces = {};
    for (const [f, [w, h]] of Object.entries(FACE_DIM)) {
      if (u + w + 2 * px > TEX[0] + 1e-9) { u = 0; v += rowH; rowH = 0; }
      faces[f] = [u + px, v + px, u + px + w, v + px + h];
      u += w + 2 * px; rowH = Math.max(rowH, h + 2 * px);
    }
    segs.push({ from: [R - T / 2, YC - T / 2, -L / 2], to: [R + T / 2, YC + T / 2, L / 2], uv: [0, 0], emissive: true, rotation: turn(a), faces, _a: a });
  }
  if (v + rowH > TEX[1] + 1e-9) throw new Error(`halo-gold: segments need ${(v + rowH).toFixed(2)} px of texture height`);
}

// ---- shading -----------------------------------------------------------------------------------
const norm = v => { const l = Math.hypot(...v) || 1; return v.map(x => x / l); };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const rotX = (v, a) => { const c = Math.cos(rad(a)), s = Math.sin(rad(a)); return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c]; };
const LV = norm([0.42, 0.72, 0.55]);                            // top, front (+z), the player's left (+x)

// Box-UV texel -> cube-local point (centre of the texel on the face surface).
function local(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) / S, v = (y + 0.5) / S;
  switch (f) {
    case 'east': return [x1, y1 - v, z1 - u];
    case 'west': return [x0, y1 - v, z0 + u];
    case 'up': return [x1 - u, y1, z1 - v];
    default: return [x1 - u, y0, z0 + v];
  }
}
// texel -> { th (ring angle, rad), phi (deg round the tube: 90 top, 0 outer, -90 under, 180 inner), side, rim }
function tube(c, p) {
  const th = rad(c._a) - Math.atan2(p[2], R);
  const phi = (Math.atan2(p[1] - YC, p[0] - R) * 180) / Math.PI;
  const lt = dot(rotX([Math.cos(th), 0, -Math.sin(th)], TILT), LV);   // how much this side of the ring faces the light
  return { th, phi, side: lt > 0.2 ? 1 : lt < -0.3 ? -1 : 0, rim: lt > 0.3, hot: lt > 0.6 };
}
function row(phi) {
  if (phi >= 45 && phi <= 135) return phi < 60 ? 'rim' : 'top';
  if (phi > -45 && phi < 45) return phi > 25 ? 'outerTop' : phi >= -10 ? 'outerMid' : 'outerLow';
  if (phi <= -45 && phi >= -135) return phi > -60 ? 'bounce' : 'under';
  return phi > 0 ? 'innerTop' : 'innerLow';
}
// [base step, steps lost on the shadow side, steps gained on the lit side]
const TABLE = { rim: [3, 1, 0], top: [3, 1, 0], outerTop: [3, 1, 0], outerMid: [2, 1, 0], outerLow: [1, 1, 0], bounce: [1, 0, 0], under: [0, 0, 0], innerTop: [2, 1, 0], innerLow: [0, 0, 0] };

// 4-step gold, hue-shifted: the underside leans red-brown, the top rim leans lemon. SPEC is the polish.
const GOLD = ['#5e2c16', '#ad5f1d', '#e5a133', '#f8d872'], SPEC = '#fff8d8';
const angDist = (a, b) => { const d = Math.abs(((a - b) % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI); return Math.min(d, 2 * Math.PI - d); };
function goldAt({ th, phi, side, rim, hot }, glint) {
  const rw = row(phi), [base, dark, lite] = TABLE[rw];
  const g = angDist(th, glint) < rad(6);                         // the travelling glint: a short bright run
  if (rw === 'rim' && (rim || g)) return SPEC;                   // 1 px specular line on the lit outer top edge
  if (g && (rw === 'top' || rw === 'outerTop')) return SPEC;
  if (hot && rw === 'outerTop' && phi > 35) return SPEC;           // the hot spot facing the light
  let s = base - (side < 0 ? dark : 0) + (side > 0 ? lite : 0);
  if (g && phi > -45) s += 1;
  return GOLD[Math.max(0, Math.min(3, s))];
}

let cache = null;
function frame(f) {
  if (!cache) {
    cache = { base: newTexture({ texSize: TEX, scale: S }), faces: [] };
    for (const c of segs) paintCube(cache.base, c, S, (v, face) => {
      const tx = [];
      for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) tx.push(tube(c, local(c, face, x, y)));
      cache.faces.push({ ox: v.ox, oy: v.oy, w: v.w, h: v.h, tx });
    });
  }
  const t = cache.base.clone(), glint = rad(150) - (2 * Math.PI * f) / FRAMES;
  for (const { ox, oy, w, h, tx } of cache.faces) {
    const v = t.view(ox, oy, w, h);
    tx.forEach((g, i) => v.set(i % w, Math.floor(i / w), goldAt(g, glint)));
    const e = t.view(ox - 1, oy - 1, w + 2, h + 2);              // extrude the edge texels into the border
    for (let y = 0; y < e.h; y++) for (let x = 0; x < e.w; x++) if (x === 0 || y === 0 || x === e.w - 1 || y === e.h - 1) e.set(x, y, e.get(Math.min(Math.max(x, 1), e.w - 2), Math.min(Math.max(y, 1), e.h - 2)));
  }
  return t;
}

const strip = ({ _a, ...c }) => c;
export default {
  meta: { id: 'halo-gold', name: 'Golden Halo', kind: 'halo', cents: 599, blurb: 'A classic polished gold halo. It floats just above your head, a glint sliding round it.' },
  model: {
    texSize: TEX, scale: S,
    bones: [{ name: 'halo', pivot: [0, YC, 0], rotation: [TILT, 0, 0], cubes: segs.map(strip) }],
  },
  // a slow float and the faintest side-to-side rock; stays inside y 10..13 at the extremes
  anim: { frames: FRAMES, fps: FPS, motion: [
    { bone: 'halo', channel: 'pos', axis: 'y', amp: FLOAT, period: 3, phase: 0 },
    { bone: 'halo', channel: 'rot', axis: 'z', amp: ROCK, period: 6, phase: 0.25 },
  ] },
  paint(f) { return frame(f); },
};
