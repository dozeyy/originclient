// Orbit Halo: the Origin mark floating above the head, tipped to face the camera. The mark is three
// stroke-only elliptical rings sharing one centre, turned 0/60/120 degrees (the atom); here they are three
// thin FLAT chrome ribbons lying in one plane (a trefoil rosette: no over/under, no breaks, so it cannot
// read as chain links), each ribbon 0.5 px wide and only 0.25 px tall, with a small chrome nucleus bead at
// the shared centre and a faint monochrome glow disc under the whole mark (brand glow, no hue) so it still
// reads at 8 blocks. The ellipses are eccentric (minor axis ~42% of major) so the six petals separate. The
// three ribbons sit 0.06 px apart in height only so they never z-fight where they cross. The whole mark is
// one rigid object: tipped 20 degrees front-up (so it faces the usual camera instead of collapsing edge-on)
// and swaying a slow +-5 degrees (14 s period) about its own axis. A single bright spark with a 2.5 px
// fading trail races round each ellipse.
//
// Size is set by the halo height rule (FORMAT.md: halo y 10..13): a tilted flat mark reaches
// radius x sin(tilt) above and below the centre, so the tilt and the radius are traded (checked below
// over every pose of the sway, the float and every ribbon corner and the glow disc).
//
// Ribbons: box segments laid along the ellipse at equal arc length, each with its long axis on local x.
// Shading is a fixed 4-step grey ramp ending in the brand's #E0E0E0, by position ACROSS the ribbon (no
// per-texel noise, so no checker): the front-left arcs get the highlight, the rest stays satin, the side
// walls are always the darkest step. Faces carry a 1-texel extruded border, so no atlas neighbour bleeds
// into a seam. The only additive part is the flat glow disc (low alpha, round, never a streak).
import { clamp } from '../../lib/px.mjs';
import { newTexture } from '../../lib/model.mjs';

const S = 8, YC = 11.5, FLOAT = 0.05, SWING = 5, TILT = -20;
const A = 3.7, B = 1.55, W = 0.5, H = 0.25, N = 56;               // semi-axes (centre line), ribbon width + height, segments per ellipse
const DY = [-0.06, 0, 0.06];                                     // per-ellipse height offset, only to stop z-fighting at the crossings
const GR = 3.75, GW = 5, GALPHA = 0.3;                            // glow disc: half size (px), its texture rect (px), peak alpha
const BEAD = 0.75, BEAD_ROTS = [[0, 0, 0], [35, 45, 0], [0, 45, 45]];   // the nucleus: three cubes turned against each other (a rough sphere), shaded as a sphere
// yaw of the long axis (deg), spark laps per loop (sign = direction)
const ELLS = [{ yaw: 0, laps: 1 }, { yaw: 60, laps: -1 }, { yaw: 120, laps: 1 }];
const FRAMES = 24, FPS = 12;                                     // 2 s loop
const GLOW_DROP = 0.1;                                           // the glow disc sits this far under the ribbons
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
  const ext = (W / 2) * (Math.tan(turn(dirs[(k + N - 1) % N], d) / 2) + Math.tan(turn(d, dirs[(k + 1) % N]) / 2));
  const L = Math.ceil((Math.hypot(...sub(q, p)) + ext + px) * S) / S, c = p.map((v, i) => (v + q[i]) / 2);
  const inward = norm([d[2], 0, -d[0]]), Y = cross(d, inward), Z = cross(d, Y);
  const s0 = n.s, s1 = k === N - 1 ? PERIM : NODES[k + 1].s;
  return { k, c, L, X: d, Y, Z, s: (s0 + s1) / 2, angle: eulerXYZ(d, Y, Z) };
});

// Every ribbon corner, the bead and the glow disc, every sway pose, the float and the tilt included, stay inside y 10..13.
{
  let top = 0;
  const pose = (w, yaw, sw) => rotX(rotY(rotY(sub(w, [0, YC, 0]), yaw), sw), TILT)[1];
  for (let sw = -SWING; sw <= SWING; sw += 1) {
    ELLS.forEach(({ yaw }, r) => { for (const sg of SEGS) for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) {
      const v = [0, 1, 2].map(i => sg.c[i] + sx * (sg.L / 2) * sg.X[i] + sy * (H / 2) * sg.Y[i] + sz * (W / 2) * sg.Z[i]);
      v[1] += DY[r];
      top = Math.max(top, Math.abs(pose(v, yaw, sw)));
    } });
  }
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) top = Math.max(top, Math.abs(rotX([sx * GR, -GLOW_DROP, sz * GR], TILT)[1]));   // the disc does not sway
  top = Math.max(top, BEAD * 0.87);
  if (top + FLOAT > 1.5) throw new Error(`halo-orbit: reaches ${(top + FLOAT).toFixed(3)} px from the centre (max 1.5)`);
}

// ---- texture layout: each segment is ONE atlas column, its faces stacked in tube order ---------------
const TEXW = 64;
const cubes = ELLS.map(() => []);
let texH, glowCube, glowRect;
const beads = [];
{
  let u = 0, v = 0, rowH = 0;
  const place = (w, h) => {                                     // shelf packer, model px; returns the rect's top-left
    if (u + w > TEXW + 1e-9) { v += rowH; rowH = 0; u = 0; }
    const at = [u, v]; u += w; rowH = Math.max(rowH, h); return at;
  };
  ELLS.forEach((_, r) => SEGS.forEach(sg => {
    // one column per segment: the top strip (also used for the underside) over the side strip (both walls)
    const { L } = sg, [a, b] = place(L + 2 * px, W + H + 4 * px);
    const top = [a + px, b + px, a + px + L, b + px + W], wall = [a + px, b + W + 3 * px, a + px + L, b + W + 3 * px + H];
    cubes[r].push({
      from: [sg.c[0] - L / 2, sg.c[1] + DY[r] - H / 2, sg.c[2] - W / 2], to: [sg.c[0] + L / 2, sg.c[1] + DY[r] + H / 2, sg.c[2] + W / 2], uv: [0, 0], emissive: true,
      rotation: { origin: [sg.c[0], sg.c[1] + DY[r], sg.c[2]], angle: sg.angle }, faces: { up: top, down: top, north: wall, south: wall }, _sg: sg, _r: r, _top: top,
    });
  }));
  for (const rot of BEAD_ROTS) {
    const bf = {};
    for (const f of ['up', 'down', 'north', 'south', 'east', 'west']) { const [a, b] = place(BEAD + 2 * px, BEAD + 2 * px); bf[f] = [a + px, b + px, a + px + BEAD, b + px + BEAD]; }
    beads.push({ from: [-BEAD / 2, YC - BEAD / 2, -BEAD / 2], to: [BEAD / 2, YC + BEAD / 2, BEAD / 2], uv: [0, 0], emissive: true, rotation: { origin: [0, YC, 0], angle: rot }, faces: bf, _rot: rot });
  }
  // the glow disc: ONE small texture rect stretched over the whole disc (both sides share it)
  const [ga, gb] = place(GW + 2 * px, GW + 2 * px);
  glowRect = [ga + px, gb + px, ga + px + GW, gb + px + GW];
  glowCube = { from: [-GR, YC - GLOW_DROP, -GR], to: [GR, YC - GLOW_DROP, GR], uv: [0, 0], glow: true, faces: { up: glowRect, down: glowRect }, _glow: true };
  texH = Math.ceil(v + rowH);
}
const TEX = [TEXW, texH];
if (TEX[0] * TEX[1] * S * S * 4 * FRAMES > 4 * 1024 * 1024) throw new Error(`halo-orbit: ${TEX} x ${FRAMES} frames is over the 4 MB GPU budget`);
// halo (float + tilt) -> sway (turn round the tilted axis; the whole mark is rigid) -> ellipse r (its yaw), nucleus; the glow disc is round, so it needs no sway
const strip = ({ _sg, _r, _rot, _top, _glow, ...c }) => c;
const bones = [
  { name: 'halo', pivot: [0, YC, 0], rotation: [TILT, 0, 0], cubes: [strip(glowCube)] },
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
// texel -> { u (0 outer .. 3 inner across the ribbon), wall, s (arc px), side (lit front-left arc / shadow / neither) }
function tube(c, p, face) {
  const sg = c._sg, l = sub(p, sg.c);                            // offset in the segment's own frame (local +z is outward)
  const out = rotX(rotY(sg.Z, ELLS[c._r].yaw), TILT);            // outward normal in the head frame at rest
  const lt = dot(out, LV);
  return { u: clamp(Math.floor((W / 2 - l[2]) * S), 0, 3), wall: face === 'north' || face === 'south', s: sg.s + l[0], side: lt > 0.4 ? 1 : lt < -0.3 ? -1 : 0 };
}
// 4-step grey ramp across the ribbon, outer edge -> inner edge: satin by default, the highlight only on the front-left arcs
const ACROSS = { lit: [2, 3, 3, 2], mid: [1, 2, 2, 1], dark: [0, 1, 1, 0] };

// Spark of ellipse r at frame f: the arc position of its head. `laps` whole laps per loop, a third apart.
// One white head 0.5 px long, then a 2.5 px trail that fades smoothly back into the chrome.
const spark = (r, f) => PERIM * ((ELLS[r].laps * f) / FRAMES + r / 3);
const TRAIL = 2.5;
function chrome({ u, wall, s, side }, r, f) {
  const dir = Math.sign(ELLS[r].laps), head = spark(r, f);
  const b = (((dir * (head - s)) % PERIM) + PERIM) % PERIM;      // arc px behind the head
  const col = wall ? CHROME[0] : CHROME[ACROSS[side > 0 ? 'lit' : side < 0 ? 'dark' : 'mid'][u]];
  if (!wall && (b < 0.25 || b > PERIM - 0.1)) return SPEC;       // the spark head: a pin-prick, never a block
  if (b < 0.25 + TRAIL) return mix(col, '#e0e0e0', 0.85 * (1 - (b - 0.25) / TRAIL) ** 2);
  return col;
}
// the nucleus: a small chrome ball (a cube stood on its corner, shaded as a sphere), smooth across the four steps
function bead3(c, p) {
  const l = rotXYZ(sub(p, [0, YC, 0]), c._rot), n = norm(rotX(l, TILT)), lt = dot(n, LV);
  if (lt > 0.96) return SPEC;
  const x = clamp((lt + 0.55) / 1.25, 0, 1) * 3, i = Math.min(2, Math.floor(x));
  return mix(CHROME[i], CHROME[i + 1], x - i);
}
// the glow disc: #E0E0E0 at low alpha, softest at the rim (a round, hue-free brand glow)
function glowTexel(x, y, w, h) {
  const d = Math.hypot((x + 0.5) / w - 0.5, (y + 0.5) / h - 0.5) * 2;
  const a = GALPHA * Math.max(0, 1 - d * d) ** 1.5;
  return '#e0e0e0' + Math.round(a * 255).toString(16).padStart(2, '0');
}
let cache = null;
function frame(f) {
  if (!cache) {                                                  // per-texel geometry, once
    cache = { blank: newTexture({ texSize: TEX, scale: S }), faces: [] };
    const all = [...cubes.flatMap((ring, r) => ring.map(c => [c, r])), ...beads.map(c => [c, -1]), [glowCube, -2]];
    for (const [c, r] of all) for (const face of Object.keys(c.faces)) {
      const [u0, v0, u1, v1] = c.faces[face].map(n => Math.round(n * S)), w = u1 - u0, h = v1 - v0, tx = [];
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) tx.push(r === -2 ? glowTexel(x, y, w, h) : r < 0 ? bead3(c, local(c, face, x, y)) : tube(c, local(c, face, x, y), face));
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
  meta: { id: 'halo-orbit', name: 'Orbit Halo', kind: 'halo', cents: 799, onSale: false, blurb: 'The Origin mark in orbit: three silver rings round one centre above your head, a soft glow beneath and a spark of light racing round each.' },
  model: { texSize: TEX, scale: S, bones },
  anim: { frames: FRAMES, fps: FPS, motion: [
    { bone: 'halo', channel: 'pos', axis: 'y', amp: FLOAT, period: 3, phase: 0 },
    { bone: 'sway', channel: 'rot', axis: 'y', amp: SWING, period: 14, phase: 0 },
  ] },
  paint(f) { return frame(f); },
};
