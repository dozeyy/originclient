// Orbit Halo: the Origin mark floating above the head, tipped to face the camera. The mark is three
// stroke-only elliptical rings sharing one centre, turned 0/60/120 degrees (the atom); here they are three
// equal thin chrome wire ellipses lying in ONE plane at ONE height (no vertical offsets), with a small
// chrome nucleus bead at the shared centre. Where two cross, one passes UNDER the other: the under wire
// simply stops 0.5 px short of the over wire's edge on both sides (a 1 px gap, as in the cape art), and
// which one goes under alternates round the mark (the one with the lower sin(2 x polar angle)). The whole
// mark is one rigid object: tipped 30 degrees front-up (so it faces the usual camera instead of
// collapsing edge-on) and swaying a slow +-8 degrees (14 s period) about its own axis. A single bright spark with a
// 2.5 px fading trail races round each ellipse.
//
// Size is set by the halo height rule (FORMAT.md: halo y 10..13): a tilted flat mark reaches
// radius x sin(tilt) above and below the centre, so the tilt and the radius are traded (checked below
// over every pose of the sway, the float and every tube corner).
//
// Tubes: box segments laid along the ellipse at equal arc length, each with its long axis on local x, so
// all four drawn faces are wide strips (one atlas column per segment, see FACE_ORDER) and the texture is
// clean horizontal chrome bands. Chrome is shaded by a fixed ROW TABLE, not a per-texel normal: every row
// of the tube cross-section gets one step of a 4-step mirror-chrome ramp ending in the brand's #E0E0E0
// (bright sky rim, a sharp dark horizon band under it, the ground reflected lighter below, a dark
// underside). Same row -> same colour on every segment: no checker. Faces carry a 1-texel extruded border,
// so no atlas neighbour bleeds into a seam. No glow planes at all: an additive plane seen edge-on
// collapses into a streak.
import { clamp } from '../../lib/px.mjs';
import { newTexture } from '../../lib/model.mjs';

const S = 8, YC = 11.5, FLOAT = 0.05, SWING = 8, TILT = -30;
const A = 2.45, B = 1.15, T = 0.375, N = 72;                       // semi-axes (centre line), tube, segments per ellipse
const GAP = 0.2;                                                 // clear px each side of the over wire (0.375 wire + 2 x 0.2 = a ~0.8 px break)
const BEAD = 0.8, BEAD_ROTS = [[0, 0, 0], [35, 45, 0], [0, 45, 45]];   // the nucleus: three cubes turned against each other (a rough sphere), shaded as a sphere
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
const rotZ = (v, a) => { const c = Math.cos(rad(a)), s = Math.sin(rad(a)); return [v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]]; };
const rotXYZ = (v, [ax, ay, az]) => rotX(rotY(rotZ(v, az), ay), ax);   // applied Z, then Y, then X

// ---- the flat ellipse ---------------------------------------------------------------------------------
const P = t => [A * Math.cos(t), YC, -B * Math.sin(t)];           // centre line in the ellipse's own frame (long axis on x)
const TAB = 4000, arc = [0];
for (let i = 1; i <= TAB; i++) { const p = P((TAU * (i - 1)) / TAB), q = P((TAU * i) / TAB); arc.push(arc[i - 1] + Math.hypot(q[0] - p[0], q[2] - p[2])); }
const PERIM = arc[TAB];
const tAt = s => {                                               // parameter t at arc length s
  let lo = 0, hi = TAB;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (arc[m] <= s) lo = m; else hi = m; }
  return (TAU * (lo + (s - arc[lo]) / (arc[hi] - arc[lo]))) / TAB;
};
const NODES = Array.from({ length: N }, (_, k) => { const s = (PERIM * k) / N; return { s, p: P(tAt(s)) }; });

// ---- crossings: where ellipse i passes under another, its wire is cut ----------------------------------
// For ellipse i, walk its centre line; where it crosses ellipse j (sign change of j's implicit function)
// record the arc position and the angle between the two wires. i goes under j there when
// sin(2 x polar angle in own frame) of i is the lower one (every pair alternates round the mark).
const CUTS = ELLS.map(() => []);                                 // per ellipse: [{ s, half }] arc interval (centre, half length) to leave empty
{
  const world = (i, p) => rotY(p, ELLS[i].yaw);
  const implicit = (j, w) => { const q = rotY(w, -ELLS[j].yaw); return (q[0] / A) ** 2 + (q[2] / B) ** 2 - 1; };
  const M = 6000;
  for (let i = 0; i < ELLS.length; i++) for (let j = 0; j < ELLS.length; j++) if (i !== j) {
    let prev = null;
    for (let k = 0; k <= M; k++) {
      const s = (PERIM * k) / M, p = P(tAt(s)), w = world(i, [p[0], 0, p[2]]), f = implicit(j, w);
      if (prev && Math.sign(prev.f) !== Math.sign(f)) {
        const sc = prev.s + ((s - prev.s) * Math.abs(prev.f)) / (Math.abs(prev.f) + Math.abs(f));
        const pc = P(tAt(sc)), wc = world(i, [pc[0], 0, pc[2]]);
        const phi = Math.atan2(-wc[2], wc[0]);                   // world polar angle (x = r cos, z = -r sin)
        const hi = Math.sin(2 * (phi - rad(ELLS[i].yaw))), hj = Math.sin(2 * (phi - rad(ELLS[j].yaw)));
        if (hi < hj) {
          const e = 1e-3, qa = world(i, [P(tAt(sc - e))[0], 0, P(tAt(sc - e))[2]]), qb = world(i, [P(tAt(sc + e))[0], 0, P(tAt(sc + e))[2]]);
          const d = norm(sub(qb, qa)), q = rotY(wc, -ELLS[j].yaw), g = norm([q[0] / (A * A), 0, q[2] / (B * B)]), gw = rotY(g, ELLS[j].yaw);
          const sinX = Math.max(0.5, Math.abs(d[0] * gw[2] - d[2] * gw[0]));   // angle between the wires
          CUTS[i].push({ s: ((sc % PERIM) + PERIM) % PERIM, half: (T / 2 + GAP) / sinX });
        }
      }
      prev = { s, f };
    }
  }
}
const cut = (i, s) => CUTS[i].some(c => { const d = Math.abs(((s - c.s + PERIM * 1.5) % PERIM) - PERIM / 2); return d < c.half; });

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
  const s0 = n.s, s1 = k === N - 1 ? PERIM : NODES[k + 1].s;
  return { k, c, L, X: d, Y, Z, s: (s0 + s1) / 2, angle: eulerXYZ(d, Y, Z) };
});
const KEEP = ELLS.map((_, r) => SEGS.filter(sg => !cut(r, sg.s)));

// Every tube corner and the bead, every sway pose, the float and the tilt included, stays inside y 10..13.
{
  let top = 0;
  const pose = (w, yaw, sw) => rotX(rotY(rotY(sub(w, [0, YC, 0]), yaw), sw), TILT)[1];
  for (let sw = -SWING; sw <= SWING; sw += 2) {
    ELLS.forEach(({ yaw }, r) => { for (const sg of KEEP[r]) for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) {
      const v = [0, 1, 2].map(i => sg.c[i] + sx * (sg.L / 2) * sg.X[i] + sy * (T / 2) * sg.Y[i] + sz * (T / 2) * sg.Z[i]);
      top = Math.max(top, Math.abs(pose(v, yaw, sw)));
    } });
  }
  top = Math.max(top, BEAD * 0.87);
  if (top + FLOAT > 1.5) throw new Error(`halo-orbit: reaches ${(top + FLOAT).toFixed(2)} px from the centre (max 1.5)`);
}

// ---- texture layout: each segment is ONE atlas column, its faces stacked in tube order ---------------
const TEXW = 64;
const FACE_ORDER = ['up', 'north', 'down', 'south'];
const cubes = ELLS.map(() => []);
let texH;
const beads = [];
{
  let u = 0, v = 0, rowH = 0;
  const place = (w, h) => {                                     // shelf packer, model px; returns the rect's top-left
    if (u + w > TEXW + 1e-9) { v += rowH; rowH = 0; u = 0; }
    const at = [u, v]; u += w; rowH = Math.max(rowH, h); return at;
  };
  ELLS.forEach((_, r) => KEEP[r].forEach(sg => {
    const { L } = sg, faces = {}, [a, b] = place(L + 2 * px, FACE_ORDER.length * (T + 2 * px));
    FACE_ORDER.forEach((f, i) => { const y = b + i * (T + 2 * px) + px; faces[f] = [a + px, y, a + px + L, y + T]; });
    cubes[r].push({
      from: [sg.c[0] - L / 2, sg.c[1] - T / 2, sg.c[2] - T / 2], to: [sg.c[0] + L / 2, sg.c[1] + T / 2, sg.c[2] + T / 2], uv: [0, 0], emissive: true,
      rotation: { origin: sg.c, angle: sg.angle }, faces, _sg: sg, _r: r,
    });
  }));
  for (const rot of BEAD_ROTS) {
    const bf = {};
    for (const f of ['up', 'down', 'north', 'south', 'east', 'west']) { const [a, b] = place(BEAD + 2 * px, BEAD + 2 * px); bf[f] = [a + px, b + px, a + px + BEAD, b + px + BEAD]; }
    beads.push({ from: [-BEAD / 2, YC - BEAD / 2, -BEAD / 2], to: [BEAD / 2, YC + BEAD / 2, BEAD / 2], uv: [0, 0], emissive: true, rotation: { origin: [0, YC, 0], angle: rot }, faces: bf, _rot: rot });
  }
  texH = Math.ceil(v + rowH);
}
const TEX = [TEXW, texH];
if (TEX[0] * TEX[1] * S * S * 4 * FRAMES > 4 * 1024 * 1024) throw new Error(`halo-orbit: ${TEX} x ${FRAMES} frames is over the 4 MB GPU budget`);
// halo (float + tilt) -> sway (turn round the tilted axis; the whole mark is rigid) -> ellipse r (its yaw), nucleus
const strip = ({ _sg, _r, _rot, ...c }) => c;
const bones = [
  { name: 'halo', pivot: [0, YC, 0], rotation: [TILT, 0, 0], cubes: [] },
  { name: 'sway', parent: 'halo', pivot: [0, YC, 0], rotation: [0, 0, 0], cubes: beads.map(strip) },
  ...ELLS.map((g, r) => ({ name: `ell${r}`, parent: 'sway', pivot: [0, YC, 0], rotation: [0, g.yaw, 0], cubes: cubes[r].map(strip) })),
];

// ---- shading ----------------------------------------------------------------------------------------
const LV = norm([0.42, 0.72, 0.55]);                            // top, front (+z), the player's left (+x)
const CHROME = ['#454a53', '#868b94', '#c2c5ca', '#e0e0e0'], SPEC = '#ffffff';
const hex = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const mix = (a, b, f) => '#' + hex(a).map((v, i) => Math.round(v + (hex(b)[i] - v) * f).toString(16).padStart(2, '0')).join('');

// Face texel -> cube-local point (centre of the texel on the face surface), per uv.mjs orientation.
function local(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) / S, v = (y + 0.5) / S;
  switch (f) {
    case 'north': return [x1 - u, y1 - v, z0];
    case 'south': return [x0 + u, y1 - v, z1];
    case 'east': return [x1, y1 - v, z1 - u];
    case 'west': return [x0, y1 - v, z0 + u];
    case 'up': return [x1 - u, y1, z1 - v];
    default: return [x1 - u, y0, z0 + v];
  }
}
// texel -> { phi (deg round the tube: 90 top, 0 outer, -90 under, 180 inner), s (arc px), side }
function tube(c, p) {
  const sg = c._sg, l = sub(p, sg.c);                            // offset in the segment's own frame
  const phi = (Math.atan2(l[1], l[2]) * 180) / Math.PI;          // local +z is outward
  const out = rotX(rotY(sg.Z, ELLS[c._r].yaw), TILT);            // outward normal in the head frame at rest
  const lt = dot(out, LV);
  return { phi, s: sg.s + l[0], side: lt > 0.2 ? 1 : lt < -0.3 ? -1 : 0 };
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
// One white head 0.5 px long, then a 2.5 px trail that fades smoothly back into the chrome.
const spark = (r, f) => PERIM * ((ELLS[r].laps * f) / FRAMES + r / 3);
const TRAIL = 2.5;
function chrome({ phi, s, side }, r, f) {
  const dir = Math.sign(ELLS[r].laps), head = spark(r, f);
  const b = (((dir * (head - s)) % PERIM) + PERIM) % PERIM;      // arc px behind the head
  const rw = row(phi), [base, dark, lite] = TABLE[rw];
  const st = clamp(base - (side < 0 ? dark : 0) + (side > 0 ? lite : 0), 0, 2);   // satin: a step under the brand's #E0E0E0, so the spark is the brightest thing on the mark
  const col = CHROME[st];
  if (b < 0.5 || b > PERIM - 0.2) return SPEC;                   // the spark
  if (b < 0.5 + TRAIL) { const k = 1 - (b - 0.5) / TRAIL; return mix(col, SPEC, 0.85 * k * k); }
  return col;
}
// the nucleus: a small chrome ball (a cube stood on its corner, shaded as a sphere), smooth across the four steps
function bead3(c, p) {
  const l = rotXYZ(sub(p, [0, YC, 0]), c._rot), n = norm(rotX(l, TILT)), lt = dot(n, LV);
  if (lt > 0.9) return SPEC;
  const x = clamp((lt + 0.55) / 1.25, 0, 1) * 3, i = Math.min(2, Math.floor(x));
  return mix(CHROME[i], CHROME[i + 1], x - i);
}
let cache = null;
function frame(f) {
  if (!cache) {                                                  // per-texel geometry, once
    cache = { blank: newTexture({ texSize: TEX, scale: S }), faces: [] };
    const all = [...cubes.flatMap((ring, r) => ring.map(c => [c, r])), ...beads.map(c => [c, -1])];
    for (const [c, r] of all) for (const face of Object.keys(c.faces)) {
      const [u0, v0, u1, v1] = c.faces[face].map(n => Math.round(n * S)), w = u1 - u0, h = v1 - v0, tx = [];
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) tx.push(r < 0 ? bead3(c, local(c, face, x, y)) : tube(c, local(c, face, x, y)));
      cache.faces.push({ r, u0, v0, w, h, tx });
    }
  }
  const t = cache.blank.clone();
  for (const { r, u0, v0, w, h, tx } of cache.faces) {
    const v = t.view(u0, v0, w, h);
    tx.forEach((g, i) => v.set(i % w, Math.floor(i / w), r < 0 ? g : chrome(g, r, f)));
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
    { bone: 'sway', channel: 'rot', axis: 'y', amp: SWING, period: 14, phase: 0 },
  ] },
  paint(f) { return frame(f); },
};
