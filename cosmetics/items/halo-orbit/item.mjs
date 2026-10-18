// Orbit Halo: the Origin mark as an atom floating above the head. Three stroke-only elliptical rings (the
// mark: three ellipses sharing one centre, long axes turned 0/60/120 degrees) each TUMBLE on their own
// long axis, at slightly different speeds and in mixed directions, round a glowing white nucleus. The whole
// mark also turns very slowly about the vertical and bobs a hair up and down (~0.2 px, 3 s).
//
// Rings are thin FLAT chrome ribbons (0.5 px wide, 0.25 px tall): box segments laid along the ellipse at
// equal arc length, each with its long axis on local x. Because they move, the rings are staggered in SIZE
// (radially: ring 0 largest, ring 2 smallest) so two ribbons never lie in one plane at a crossing and
// never z-fight. Shading is baked and fixed to each ring (a 4-step grey ramp ending in the brand's #E0E0E0,
// by position ACROSS the ribbon, no per-texel noise): as a ring flips, its sheen travels with it. A single
// bright spark with a 2.5 px fading trail races round each ring. The only additive parts are the soft
// glows (low alpha, round, hue-free): a faint disc under the mark and a small white ball round the nucleus
// (three crossed quads, so it reads from any side). The nucleus is white (#F0F0F0..#FFFFFF), emissive.
//
// Bones: halo (bob, nucleus, glows) -> turn (slow yaw) -> ell<r> (yaw 0/60/120) -> ring<r> (spins about
// its own local x = the ring's LONG axis, via the "spin" motion channel; its rest angle just staggers the
// ring phases so a still frame already looks like an atom).
//
// Size is set by the halo height rule (FORMAT.md: halo y 10..13). A ring tumbling about its long axis
// swings its minor axis through the vertical, so its vertical reach is minor semi-axis + ribbon half-width
// (+ bob): the self-check below covers EVERY spin angle (the reach of a corner at (y,z) over all spins is
// exactly hypot(y,z)) and every ribbon corner, the nucleus, the glows and the bob.
import { clamp } from '../../lib/px.mjs';
import { newTexture } from '../../lib/model.mjs';

const S = 8, YC = 11.5, FLOAT = 0.18;                            // texels per px, centre height, bob amplitude (px)
const W = 0.5, H = 0.25, N = 56;                                 // ribbon width + height, segments per ellipse
// per ring: yaw of the long axis (deg), semi-axes (centre line; staggered so rings never share a plane),
// spin (deg/s, sign = direction), rest tilt (deg), spark laps per loop (sign = direction)
const ELLS = [
  { yaw: 0, A: 3.9, B: 1.06, speed: 62, rest: 20, laps: 1 },
  { yaw: 60, A: 3.7, B: 1.0, speed: -50, rest: 78, laps: -1 },
  { yaw: 120, A: 3.5, B: 0.94, speed: 42, rest: 136, laps: 1 },
];
const TURN = 9;                                                  // whole-mark turn about the vertical (deg/s)
const GR = 3.4, GW = 5, GALPHA = 0.14, GLOW_DROP = 0.1;          // glow disc under the mark: half size (px), texture rect (px), peak alpha, drop
const NGV = 1.25, NGH = 2.2, NGW = 3, NALPHA = 0.85;             // nucleus glow: half size of the two vertical quads / the horizontal one, texture rect, peak alpha
const BEAD = 0.8, BEAD_ROTS = [[0, 0, 0], [35, 45, 0], [0, 45, 45]];   // the nucleus: three cubes turned against each other (a rough sphere), shaded as a sphere
const FRAMES = 24, FPS = 12;                                     // 2 s loop (the sparks)
const px = 1 / S, rad = d => (d * Math.PI) / 180, TAU = 2 * Math.PI;
const norm = v => { const l = Math.hypot(...v) || 1; return v.map(x => x / l); };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const sub = (a, b) => a.map((v, i) => v - b[i]);
const rotX = (v, a) => { const c = Math.cos(rad(a)), s = Math.sin(rad(a)); return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c]; };
const rotY = (v, a) => { const c = Math.cos(rad(a)), s = Math.sin(rad(a)); return [v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c]; };
const rotZ = (v, a) => { const c = Math.cos(rad(a)), s = Math.sin(rad(a)); return [v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]]; };
const rotXYZ = (v, [ax, ay, az]) => rotX(rotY(rotZ(v, az), ay), ax);   // applied Z, then Y, then X

// Segment k is the chord node k -> k+1, lengthened to close the outer corner, rounded up to whole texels.
// Frame: local x along the chord, local y up, local z outward (X x Y).
function eulerXYZ(X, Y, Z) {                                     // columns -> three.js 'XYZ' angles (deg)
  const y = Math.asin(clamp(Z[0], -1, 1)), x = Math.atan2(-Z[1], Z[2]), z = Math.atan2(-Y[0], X[0]);
  return [x, y, z].map(a => +((a * 180) / Math.PI).toFixed(4));
}
// One flat ellipse (long axis on x) as N segments at equal arc length.
function ring({ A, B }) {
  const P = t => [A * Math.cos(t), YC, -B * Math.sin(t)];
  const TAB = 4000, arc = [0];
  for (let i = 1; i <= TAB; i++) { const p = P((TAU * (i - 1)) / TAB), q = P((TAU * i) / TAB); arc.push(arc[i - 1] + Math.hypot(q[0] - p[0], q[2] - p[2])); }
  const perim = arc[TAB];
  const tAt = s => {                                             // parameter t at arc length s
    let lo = 0, hi = TAB;
    while (hi - lo > 1) { const m = (lo + hi) >> 1; if (arc[m] <= s) lo = m; else hi = m; }
    return (TAU * (lo + (s - arc[lo]) / (arc[hi] - arc[lo]))) / TAB;
  };
  const nodes = Array.from({ length: N }, (_, k) => { const s = (perim * k) / N; return { s, p: P(tAt(s)) }; });
  const dirs = nodes.map((n, k) => norm(sub(nodes[(k + 1) % N].p, n.p)));
  const segs = nodes.map((n, k) => {
    const p = n.p, q = nodes[(k + 1) % N].p, d = dirs[k];
    const turn = (a, b) => Math.acos(clamp(dot(a, b), -1, 1));
    const ext = (W / 2) * (Math.tan(turn(dirs[(k + N - 1) % N], d) / 2) + Math.tan(turn(d, dirs[(k + 1) % N]) / 2));
    const L = Math.ceil((Math.hypot(...sub(q, p)) + ext + px) * S) / S, c = p.map((v, i) => (v + q[i]) / 2);
    const inward = norm([d[2], 0, -d[0]]), Y = cross(d, inward), Z = cross(d, Y);
    const s0 = n.s, s1 = k === N - 1 ? perim : nodes[k + 1].s;
    return { k, c, L, X: d, Y, Z, s: (s0 + s1) / 2, angle: eulerXYZ(d, Y, Z) };
  });
  return { perim, segs };
}
const RINGS = ELLS.map(ring);

// Every ribbon corner at every spin angle, the nucleus, the glows and the bob stay inside y 10..13 (halo
// rule) and within 5.5 px sideways (1.5 px outside the head). A corner at ring-frame (y, z) sweeps a circle of
// radius hypot(y, z) round the long axis, so that is its worst vertical reach over ALL spin angles.
{
  let up = 0, side = 0;
  RINGS.forEach(({ segs }, r) => { for (const sg of segs) for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) {
    const v = [0, 1, 2].map(i => sg.c[i] + sx * (sg.L / 2) * sg.X[i] + sy * (H / 2) * sg.Y[i] + sz * (W / 2) * sg.Z[i]);
    const o = sub(v, [0, YC, 0]);
    up = Math.max(up, Math.hypot(o[1], o[2]));
    for (let a = 0; a < 360; a += 3) { const q = rotY(rotX(o, a), ELLS[r].yaw); side = Math.max(side, Math.hypot(q[0], q[2])); }
  } });
  up = Math.max(up, BEAD * 0.87, NGV);                            // the nucleus corner, and the vertical glow quads
  if (up + FLOAT > 1.5) throw new Error(`halo-orbit: reaches ${(up + FLOAT).toFixed(3)} px above/below the centre (max 1.5)`);
  if (Math.max(side, GR * Math.SQRT2, NGH * Math.SQRT2) > 5.5) throw new Error(`halo-orbit: reaches ${side.toFixed(2)} px sideways (max 5.5)`);
}

// ---- texture layout: each segment is ONE atlas column, its faces stacked in tube order ---------------
const TEXW = 64;
const cubes = ELLS.map(() => []);
let texH, discCube, discRect;
const beads = [], nucGlow = [];
{
  let u = 0, v = 0, rowH = 0;
  const place = (w, h) => {                                     // shelf packer, model px; returns the rect's top-left
    if (u + w > TEXW + 1e-9) { v += rowH; rowH = 0; u = 0; }
    const at = [u, v]; u += w; rowH = Math.max(rowH, h); return at;
  };
  RINGS.forEach(({ segs }, r) => segs.forEach(sg => {
    // one column per segment: the top strip (also used for the underside) over the side strip (both walls)
    const { L } = sg, [a, b] = place(L + 2 * px, W + H + 4 * px);
    const top = [a + px, b + px, a + px + L, b + px + W], wall = [a + px, b + W + 3 * px, a + px + L, b + W + 3 * px + H];
    cubes[r].push({
      from: [sg.c[0] - L / 2, sg.c[1] - H / 2, sg.c[2] - W / 2], to: [sg.c[0] + L / 2, sg.c[1] + H / 2, sg.c[2] + W / 2], uv: [0, 0], emissive: true,
      rotation: { origin: [sg.c[0], sg.c[1], sg.c[2]], angle: sg.angle }, faces: { up: top, down: top, north: wall, south: wall }, _sg: sg, _r: r, _top: top,
    });
  }));
  for (const rot of BEAD_ROTS) {
    const bf = {};
    for (const f of ['up', 'down', 'north', 'south', 'east', 'west']) { const [a, b] = place(BEAD + 2 * px, BEAD + 2 * px); bf[f] = [a + px, b + px, a + px + BEAD, b + px + BEAD]; }
    beads.push({ from: [-BEAD / 2, YC - BEAD / 2, -BEAD / 2], to: [BEAD / 2, YC + BEAD / 2, BEAD / 2], uv: [0, 0], emissive: true, rotation: { origin: [0, YC, 0], angle: rot }, faces: bf, _rot: rot });
  }
  // the glow disc under the mark: ONE small texture rect stretched over the whole disc (both sides share it)
  const [ga, gb] = place(GW + 2 * px, GW + 2 * px);
  discRect = [ga + px, gb + px, ga + px + GW, gb + px + GW];
  discCube = { from: [-GR, YC - GLOW_DROP, -GR], to: [GR, YC - GLOW_DROP, GR], uv: [0, 0], glow: true, faces: { up: discRect, down: discRect }, _glow: 'disc' };
  // the nucleus glow: one rect shared by three crossed quads (horizontal, and two vertical), each drawn from both sides
  const [na, nb] = place(NGW + 2 * px, NGW + 2 * px);
  const nr = [na + px, nb + px, na + px + NGW, nb + px + NGW];
  nucGlow.push(
    { from: [-NGH, YC, -NGH], to: [NGH, YC, NGH], uv: [0, 0], glow: true, faces: { up: nr, down: nr }, _glow: 'nucleus' },
    { from: [-NGV, YC - NGV, 0], to: [NGV, YC + NGV, 0], uv: [0, 0], glow: true, faces: { north: nr, south: nr }, _glow: 'nucleus' },
    { from: [0, YC - NGV, -NGV], to: [0, YC + NGV, NGV], uv: [0, 0], glow: true, faces: { east: nr, west: nr }, _glow: 'nucleus' },
  );
  texH = Math.ceil(v + rowH);
}
const TEX = [TEXW, texH];
if (TEX[0] * TEX[1] * S * S * 4 * FRAMES > 4 * 1024 * 1024) throw new Error(`halo-orbit: ${TEX} x ${FRAMES} frames is over the 4 MB GPU budget`);
const strip = ({ _sg, _r, _rot, _top, _glow, ...c }) => c;
const PIV = [0, YC, 0];
const bones = [
  { name: 'halo', pivot: PIV, rotation: [0, 0, 0], cubes: [discCube, ...nucGlow, ...beads].map(strip) },   // bob; the nucleus and glows do not turn
  { name: 'turn', parent: 'halo', pivot: PIV, rotation: [0, 0, 0], cubes: [] },
  ...ELLS.flatMap((g, r) => [
    { name: `ell${r}`, parent: 'turn', pivot: PIV, rotation: [0, g.yaw, 0], cubes: [] },
    { name: `ring${r}`, parent: `ell${r}`, pivot: PIV, rotation: [g.rest, 0, 0], cubes: cubes[r].map(strip) },
  ]),
];

// ---- shading ----------------------------------------------------------------------------------------
const LV = norm([0.42, 0.72, 0.55]);                            // nucleus light: top, front (+z), the player's left (+x)
const LR = norm([0.6, 0, 0.8]);                                 // ring light, in the ring's own frame (it tumbles, so the sheen is fixed to the ring)
const CHROME = ['#454a53', '#868b94', '#c2c5ca', '#e0e0e0'], SPEC = '#f0f0f0';
const NUC = ['#cfcfcf', '#e2e2e2', '#f2f2f2', '#ffffff'];       // the nucleus: white, shaded only a little
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
// texel -> { u (0 outer .. 3 inner across the ribbon), wall, s (arc px), side (lit / shadow / neither arc) }
function tube(c, p, face) {
  const sg = c._sg, l = sub(p, sg.c);                            // offset in the segment's own frame (local +z is outward)
  const lt = dot(sg.Z, LR);
  return { u: clamp(Math.floor((W / 2 - l[2]) * S), 0, 3), wall: face === 'north' || face === 'south', s: sg.s + l[0], side: lt > 0.4 ? 1 : lt < -0.3 ? -1 : 0 };
}
// 4-step grey ramp across the ribbon, outer edge -> inner edge: satin by default, the highlight only on the lit arc
const ACROSS = { lit: [2, 3, 3, 2], mid: [1, 2, 2, 1], dark: [0, 1, 1, 0] };

// Spark of ring r at frame f: the arc position of its head. `laps` whole laps per loop, a third apart.
// One white head 0.5 px long, then a 2.5 px trail that fades smoothly back into the chrome.
const spark = (r, f) => RINGS[r].perim * ((ELLS[r].laps * f) / FRAMES + r / 3);
const TRAIL = 2.5;
function chrome({ u, wall, s, side }, r, f) {
  const PERIM = RINGS[r].perim, dir = Math.sign(ELLS[r].laps), head = spark(r, f);
  const b = (((dir * (head - s)) % PERIM) + PERIM) % PERIM;      // arc px behind the head
  const ramp = ACROSS[side > 0 ? 'lit' : side < 0 ? 'dark' : 'mid'];
  const col = CHROME[ramp[wall ? 0 : u]];                        // a wall takes its ribbon's outer-edge step, so it blends into the stroke instead of drawing a second line
  if (!wall && (b < 0.2 || b > PERIM - 0.1)) return SPEC;       // the spark head: a pin-prick, never a block
  if (b < 0.2 + TRAIL) return mix(col, '#e0e0e0', 0.85 * (1 - (b - 0.2) / TRAIL) ** 2);
  return col;
}
// the nucleus: a small white ball (three cubes stood on their corners, shaded as a sphere), smooth across the four steps
function bead3(c, p) {
  const l = rotXYZ(sub(p, [0, YC, 0]), c._rot), lt = dot(norm(l), LV);
  if (lt > 0.9) return '#ffffff';
  const x = clamp((lt + 0.55) / 1.25, 0, 1) * 3, i = Math.min(2, Math.floor(x));
  return mix(NUC[i], NUC[i + 1], x - i);
}
// the glows: white-grey at low alpha, softest at the rim (round, hue-free). disc = brand glow under the mark; nucleus = the ball of light
function glowTexel(kind, x, y, w, h) {
  const d = Math.hypot((x + 0.5) / w - 0.5, (y + 0.5) / h - 0.5) * 2;
  const nuc = kind === 'nucleus', a = (nuc ? NALPHA : GALPHA) * Math.max(0, 1 - d * d) ** (nuc ? 2 : 1.5);
  return (nuc ? '#ffffff' : '#e0e0e0') + Math.round(a * 255).toString(16).padStart(2, '0');
}

let cache = null;
function frame(f) {
  if (!cache) {                                                  // per-texel geometry, once
    cache = { blank: newTexture({ texSize: TEX, scale: S }), faces: [] };
    const all = [...cubes.flatMap((ring, r) => ring.map(c => [c, r])), ...beads.map(c => [c, -1]), ...[discCube, ...nucGlow].map(c => [c, -2])];
    for (const [c, r] of all) for (const face of Object.keys(c.faces)) {
      const [u0, v0, u1, v1] = c.faces[face].map(n => Math.round(n * S)), w = u1 - u0, h = v1 - v0, tx = [];
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) tx.push(r === -2 ? glowTexel(c._glow, x, y, w, h) : r < 0 ? bead3(c, local(c, face, x, y)) : tube(c, local(c, face, x, y), face));
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
  meta: { id: 'halo-orbit', name: 'Orbit Halo', kind: 'halo', cents: 799, blurb: 'The Origin mark as an atom: three silver rings tumbling round a glowing white centre above your head, a spark of light racing round each.' },
  model: { texSize: TEX, scale: S, bones },
  anim: { frames: FRAMES, fps: FPS, motion: [
    { bone: 'halo', channel: 'pos', axis: 'y', amp: FLOAT, period: 3, phase: 0 },
    { bone: 'turn', channel: 'spin', axis: 'y', speed: TURN },
    ...ELLS.map((g, r) => ({ bone: `ring${r}`, channel: 'spin', axis: 'x', speed: g.speed })),
  ] },
  paint(f) { return frame(f); },
};
