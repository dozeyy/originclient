// Orbit Halo: the Origin mark lying flat above the head. The mark is three stroke-only elliptical rings
// sharing one centre, turned 0/60/120 degrees (the atom); here they are three equal thin chrome ellipses
// in the halo plane (thin 0.375 px wire, 5 x 2 px semi-axes, the 0.36 aspect of the real mark), each long axis turned 60 degrees from the last. The
// whole mark is tipped 12.5 degrees front-up (as halo-gold is, so eye-level views see into it) and sways a
// slow +-30 degrees about its own axis (a swing round the tilted normal keeps the height constant, and
// 30 degrees is half the mark's 60 degree symmetry, so the sweep always looks like a turn of the mark).
// The ellipses are WOVEN: each rises and dips a hair (+-0.25 px) as sin(2 x its polar angle), which puts
// every pair over and under each other alternately at their four crossings, so nothing intersects and
// nothing sits in a flat stack. A bright 1 px spark with a short trail races round each ellipse.
//
// Tubes: box segments laid along the ellipse at equal arc length, each with its long axis on local x, so
// all four drawn faces are wide strips (one atlas column per segment, see FACE_ORDER) and the texture is
// clean horizontal chrome bands. Chrome is shaded by a fixed ROW TABLE, not a per-texel normal: every row
// of the tube cross-section gets one step of a 4-step mirror-chrome ramp ending in the brand's #E0E0E0
// (bright sky rim, a sharp dark horizon band under it, the ground reflected lighter below, a dark
// underside), nudged a step on the shadow side, plus a pale hot spot facing the light. Same row -> same
// colour on every segment: no checker. Faces carry a 1-texel extruded border, so no atlas neighbour bleeds
// into a seam. No glow planes at all: an additive plane seen edge-on collapses into a streak.
// Clearance (FORMAT.md): halo, y 10..13 including the float, the tilt, the sway, the weave and every tube
// corner (checked below over every pose).
import { clamp } from '../../lib/px.mjs';
import { newTexture } from '../../lib/model.mjs';

const S = 8, YC = 11.5, FLOAT = 0.1, SWING = 30, TILT = -12.5;
const A = 5.0, B = 2.0, T = 0.375, H = 0.25, N = 56;             // semi-axes (centre line), tube, weave height, segments
// yaw of the long axis (deg), spark laps per loop (sign = direction)
const ELLS = [{ yaw: 0, laps: 1 }, { yaw: 60, laps: -1 }, { yaw: 120, laps: 1 }];
const FRAMES = 24, FPS = 12;                                     // 2 s loop
const px = 1 / S, rad = d => (d * Math.PI) / 180, TAU = 2 * Math.PI;
const norm = v => { const l = Math.hypot(...v) || 1; return v.map(x => x / l); };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const sub = (a, b) => a.map((v, i) => v - b[i]);
const rotY = (v, a) => { const c = Math.cos(rad(a)), s = Math.sin(rad(a)); return [v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c]; };
const rotX = (v, a) => { const c = Math.cos(rad(a)), s = Math.sin(rad(a)); return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c]; };

// ---- the woven ellipse -------------------------------------------------------------------------------
// centre line in the ellipse's own frame (long axis on x). Weave: y = YC + H sin(2 theta), theta = polar angle.
const P = t => {
  const x = A * Math.cos(t), z = -B * Math.sin(t), th = Math.atan2(-z, x);
  return [x, YC + H * Math.sin(2 * th), z];
};
// equal-arc-length nodes (the ellipse's perimeter measured in the plan view)
const TAB = 4000, arc = [0];
for (let i = 1; i <= TAB; i++) { const p = P((TAU * (i - 1)) / TAB), q = P((TAU * i) / TAB); arc.push(arc[i - 1] + Math.hypot(q[0] - p[0], q[1] - p[1], q[2] - p[2])); }
const PERIM = arc[TAB];
const tAt = s => {                                               // parameter t at arc length s
  let lo = 0, hi = TAB;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (arc[m] <= s) lo = m; else hi = m; }
  return (TAU * (lo + (s - arc[lo]) / (arc[hi] - arc[lo]))) / TAB;
};
const NODES = Array.from({ length: N }, (_, k) => { const s = (PERIM * k) / N; return { s, p: P(tAt(s)) }; });

// ---- segments ---------------------------------------------------------------------------------------
// Segment k is the chord node k -> k+1, lengthened to close the outer corner, rounded up to whole texels.
// Frame: local x along the chord, local y up, local z outward (X x Y).
function eulerXYZ(X, Y, Z) {                                     // columns -> three.js 'XYZ' angles (deg)
  const y = Math.asin(clamp(Z[0], -1, 1)), x = Math.atan2(-Z[1], Z[2]), z = Math.atan2(-Y[0], X[0]);
  return [x, y, z].map(a => +((a * 180) / Math.PI).toFixed(4));
}
const dirs = NODES.map((n, k) => norm(sub(NODES[(k + 1) % N].p, n.p)));
const SEGS = NODES.map((n, k) => {
  const p = n.p, q = NODES[(k + 1) % N].p, d = dirs[k];
  const turn = (a, b) => Math.acos(clamp(dot(a, b), -1, 1));
  const ext = (T / 2) * (Math.tan(turn(dirs[(k + N - 1) % N], d) / 2) + Math.tan(turn(d, dirs[(k + 1) % N]) / 2));
  const L = Math.ceil((Math.hypot(...sub(q, p)) + ext + px) * S) / S, c = p.map((v, i) => (v + q[i]) / 2);
  const inward = norm([d[2], 0, -d[0]]), Y = cross(d, inward), Z = cross(d, Y);
  const s = n.s + (k === N - 1 ? PERIM - n.s : (NODES[k + 1].s - n.s)) / 2;
  return { k, c, L, X: d, Y, Z, s, angle: eulerXYZ(d, Y, Z) };
});

// Every tube corner, every sway pose, the float and the tilt included, stays inside y 10..13.
{
  let top = 0;
  for (const { yaw } of ELLS) for (let sw = -SWING; sw <= SWING; sw += 3) for (const sg of SEGS) for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) {
    const v = [0, 1, 2].map(i => sg.c[i] + sx * (sg.L / 2) * sg.X[i] + sy * (T / 2) * sg.Y[i] + sz * (T / 2) * sg.Z[i]);
    const w = rotX(rotY(rotY(sub(v, [0, YC, 0]), yaw), sw), TILT);
    top = Math.max(top, Math.abs(w[1]));
  }
  if (top + FLOAT > 1.5) throw new Error(`halo-orbit: reaches ${(top + FLOAT).toFixed(2)} px from the centre (max 1.5)`);
}

// ---- texture layout: each segment is ONE atlas column, its faces stacked in tube order ---------------
const TEXW = 64;
const FACE_ORDER = ['up', 'north', 'down', 'south'];
const cubes = ELLS.map(() => []);
let texH;
{
  let u = 0, v = 0, rowH = 0;
  const place = (w, h) => {                                     // shelf packer, model px; returns the rect's top-left
    if (u + w > TEXW + 1e-9) { v += rowH; rowH = 0; u = 0; }
    const at = [u, v]; u += w; rowH = Math.max(rowH, h); return at;
  };
  ELLS.forEach((_, r) => SEGS.forEach(sg => {
    const { L } = sg, faces = {}, [a, b] = place(L + 2 * px, FACE_ORDER.length * (T + 2 * px));
    FACE_ORDER.forEach((f, i) => { const y = b + i * (T + 2 * px) + px; faces[f] = [a + px, y, a + px + L, y + T]; });
    cubes[r].push({
      from: [sg.c[0] - L / 2, sg.c[1] - T / 2, sg.c[2] - T / 2], to: [sg.c[0] + L / 2, sg.c[1] + T / 2, sg.c[2] + T / 2], uv: [0, 0], emissive: true,
      rotation: { origin: sg.c, angle: sg.angle }, faces, _sg: sg, _r: r,
    });
  }));
  texH = Math.ceil(v + rowH);
}
const TEX = [TEXW, texH];
if (TEX[0] * TEX[1] * S * S * 4 * FRAMES > 4 * 1024 * 1024) throw new Error(`halo-orbit: ${TEX} x ${FRAMES} frames is over the 4 MB GPU budget`);
// halo (float + tilt) -> sway (turn round the tilted axis) -> ellipse r (its yaw)
const strip = ({ _sg, _r, ...c }) => c;
const bones = [
  { name: 'halo', pivot: [0, YC, 0], rotation: [TILT, 0, 0], cubes: [] },
  { name: 'sway', parent: 'halo', pivot: [0, YC, 0], rotation: [0, 0, 0], cubes: [] },
  ...ELLS.map((g, r) => ({ name: `ell${r}`, parent: 'sway', pivot: [0, YC, 0], rotation: [0, g.yaw, 0], cubes: cubes[r].map(strip) })),
];

// ---- shading ----------------------------------------------------------------------------------------
const LV = norm([0.42, 0.72, 0.55]);                            // top, front (+z), the player's left (+x)
const CHROME = ['#454a53', '#868b94', '#c2c5ca', '#e0e0e0'], SPEC = '#ffffff';

// Face texel -> cube-local point (centre of the texel on the face surface), per uv.mjs orientation.
function local(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) / S, v = (y + 0.5) / S;
  switch (f) {
    case 'north': return [x1 - u, y1 - v, z0];
    case 'south': return [x0 + u, y1 - v, z1];
    case 'up': return [x1 - u, y1, z1 - v];
    default: return [x1 - u, y0, z0 + v];
  }
}
// texel -> { phi (deg round the tube: 90 top, 0 outer, -90 under, 180 inner), s (arc px), side, rim, hot }
function tube(c, p) {
  const sg = c._sg, l = sub(p, sg.c);                            // offset in the segment's own frame
  const phi = (Math.atan2(l[1], l[2]) * 180) / Math.PI;          // local +z is outward
  const out = rotX(rotY(sg.Z, ELLS[c._r].yaw), TILT);            // outward normal in the head frame at rest
  const lt = dot(out, LV);
  return { phi, s: sg.s + l[0], side: lt > 0.2 ? 1 : lt < -0.3 ? -1 : 0, rim: lt > 0.3, hot: lt > 0.6 };
}
function row(phi) {
  if (phi >= 45 && phi <= 135) return phi < 60 ? 'rim' : 'top';
  if (phi > -45 && phi < 45) return phi > 25 ? 'outerTop' : phi >= -10 ? 'outerMid' : 'outerLow';
  if (phi <= -45 && phi >= -135) return phi > -60 ? 'bounce' : 'under';
  return phi > 0 ? 'innerTop' : 'innerLow';
}
// mirror chrome: [base step, steps lost on the shadow side, steps gained on the lit side]
const TABLE = { rim: [3, 1, 0], top: [3, 1, 0], outerTop: [3, 1, 0], outerMid: [1, 1, 0], outerLow: [2, 1, 0], bounce: [2, 1, 0], under: [1, 1, 0], innerTop: [2, 1, 0], innerLow: [1, 1, 0] };

// Spark of ellipse r at frame f: the arc position of its head. `laps` whole laps per loop, a third apart.
const spark = (r, f) => PERIM * ((ELLS[r].laps * f) / FRAMES + r / 3);
function chrome({ phi, s, side, rim, hot }, r, f) {
  const dir = Math.sign(ELLS[r].laps), head = spark(r, f);
  const b = ((((dir * (head - s)) % PERIM) + PERIM) % PERIM), ahead = PERIM - b;   // arc px behind / ahead of the head
  if (b < 0.6 || ahead < 0.4) return SPEC;                       // the spark: 1 px of white
  const rw = row(phi), [base, dark, lite] = TABLE[rw];
  let st = base - (side < 0 ? dark : 0) + (side > 0 ? lite : 0);
  if (rw === 'rim' && rim) return SPEC;                          // 1 px specular line on the lit outer top edge
  if (hot && rw === 'outerTop' && phi > 35) return SPEC;         // and the hot spot facing the light
  if (b < 1.6) st = 3;                                           // its trail: about 3 px, fading in two steps
  else if (b < 3) st = Math.min(3, st + 1);
  return CHROME[clamp(st, 0, 3)];
}
let cache = null;
function frame(f) {
  if (!cache) {                                                  // per-texel geometry, once
    cache = { blank: newTexture({ texSize: TEX, scale: S }), faces: [] };
    for (const ring of cubes) for (const c of ring) for (const face of Object.keys(c.faces)) {
      const [u0, v0, u1, v1] = c.faces[face].map(n => Math.round(n * S)), w = u1 - u0, h = v1 - v0, tx = [];
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) tx.push(tube(c, local(c, face, x, y)));
      cache.faces.push({ r: c._r, u0, v0, w, h, tx });
    }
  }
  const t = cache.blank.clone();
  for (const { r, u0, v0, w, h, tx } of cache.faces) {
    const v = t.view(u0, v0, w, h);
    tx.forEach((g, i) => v.set(i % w, Math.floor(i / w), chrome(g, r, f)));
    const e = t.view(u0 - 1, v0 - 1, w + 2, h + 2);               // extrude the edge texels into the border
    for (let y = 0; y < e.h; y++) for (let x = 0; x < e.w; x++) if (x === 0 || y === 0 || x === e.w - 1 || y === e.h - 1) e.set(x, y, e.get(clamp(x, 1, e.w - 2), clamp(y, 1, e.h - 2)));
  }
  return t;
}

export default {
  meta: { id: 'halo-orbit', name: 'Orbit Halo', kind: 'halo', cents: 799, onSale: false, blurb: 'The Origin mark in orbit: three silver rings woven round one centre above your head, a spark of light racing round each.' },
  model: { texSize: TEX, scale: S, bones },
  anim: { frames: FRAMES, fps: FPS, motion: [
    { bone: 'halo', channel: 'pos', axis: 'y', amp: FLOAT, period: 3, phase: 0 },
    { bone: 'sway', channel: 'rot', axis: 'y', amp: SWING, period: 10, phase: 0 },
  ] },
  paint(f) { return frame(f); },
};
