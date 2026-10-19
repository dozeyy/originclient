// Vent Mask: a Y2K techwear respirator over the nose and mouth. A matte black moulded shell (a flat front
// that turns sharply round the cheeks, panel lines cut into it, a raised gunmetal plate on the chin with the
// Origin mark), two round brushed-chrome vent filters on the cheeks, and thin nylon straps that run along
// the head sides to a chrome buckle at the back.
//
// Build: the shell is a chain of 0.5 px slabs, each rotated about its front-start corner so the front
// surface is one continuous curve (the same trick as the Racer Shield). Every texel is painted from its
// place on that curve (arc length `a` from the centre line, height `Y`), so panel lines, the plate and
// the bevels run seamlessly across slabs. The filters are octagonal chrome barrels (8 rotated slabs) with
// a round recessed disc (circular cut-out, painted pixel by pixel) inside; each filter hangs on its own
// bone turned to face outward with the shell.
//
// The Origin mark comes from lib/marks, rasterised at the exact size it appears (20 texels = 2.5 px, stroke 1).
//
// Light: top-front-left of the PLAYER (+x, +y, +z), the lineup convention. Clearance: every part <= z 5.0
// and the straps hug the head (x 4.0..4.25).
import { clamp, smoothstep, mix, toHex, Canvas } from '../../lib/px.mjs';
import { drawMark } from '../../lib/marks.mjs';
import { layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const TEX = [64, 34];                                                    // boxes on top, 2 rows below for the filter discs (trimmed once laid out)

// ---- the shell curve ---------------------------------------------------------------------------------------
const N = 11, STEP = 0.5, ZC = 4.7, PHI_MAX = 100, PHI_POW = 3.5, T = 0.75;
const SEG = [];
{
  let x = 0, z = ZC;
  for (let i = 0; i < N; i++) {
    const phi = (PHI_MAX * ((i + 0.5) / N) ** PHI_POW * Math.PI) / 180;
    SEG.push({ i, a0: i * STEP, x, z, phi, deg: (phi * 180) / Math.PI });
    x += STEP * Math.cos(phi); z -= STEP * Math.sin(phi);
  }
}
const q8 = v => Math.round(v * S) / S;
// outline: the nose bridge peaks at the centre, the top edge falls away under the eyes toward the ears;
// a chin guard hangs below the mouth and the jaw line rises toward the ears
const topOf = a => q8(3.375 - 0.9 * smoothstep(0.4, 2.8, a));
const bottomOf = a => q8(-0.5 + 0.8 * smoothstep(1.25, 3.7, a) + 0.1 * smoothstep(3.7, 5.5, a));
const PLATE = { a: 1.5, y0: -0.375, y1: 2.625, mark: 20, my: 1.125 };
const plateY0 = a => PLATE.y0 + 0.125 * (a >= 1.125 ? 1 : 0) + 0.125 * (a >= 1.375 ? 1 : 0);
const inPlate = (a, Y) => Math.abs(a) < PLATE.a && Y >= plateY0(Math.abs(a)) && Y < PLATE.y1;
const inShell = (a, Y) => Math.abs(a) < N * STEP && Y >= bottomOf(Math.abs(a)) && Y < topOf(Math.abs(a));

// ---- ramps ----------------------------------------------------------------------------------------------------
// matte moulded plastic: near-black, cool shadows, a steel-blue sheen only on the lit edges
const BK = ['#06070a', '#0c0e12', '#13161b', '#1b1f25', '#262b33', '#363d48', '#505967', '#7d8795'];
// brushed chrome: cool shadows -> warm-white specular
const CH = ['#0d0f14', '#1d2129', '#323843', '#505865', '#7e8795', '#b3bbc7', '#e4e8ec', '#ffffff'];
// gunmetal plate
const GM = ['#0e1014', '#181b21', '#242830', '#333843', '#474e5b', '#66707f'];
// mark thread: the lightest step is the brand accent #E0E0E0
const MK = ['#52565d', '#7d818a', '#aeb1b8', '#cfd1d5', '#e0e0e0'];
// nylon strap
const NY = ['#07080a', '#0f1013', '#17191d', '#202328', '#2c3037'];
const pick = (R, v) => R[clamp(Math.round(v), 0, R.length - 1)];
const lerpStep = (R, v) => { const p = clamp(v, 0, R.length - 1), lo = Math.floor(p), hi = Math.min(R.length - 1, lo + 1); return mix(R[lo], R[hi], p - lo); };

// ---- geometry ---------------------------------------------------------------------------------------------------
const parts = [];
const add = (bone, role, from, to, extra = {}) => { const c = { bone, role, from, to, uv: [0, 0], ...extra }; parts.push(c); return c; };
function slab(role, sx, sg, y0, y1, off, thick, w = STEP) {
  const nx = sx * Math.sin(sg.phi), nz = Math.cos(sg.phi);
  const fx = sx * sg.x + off * nx, fz = sg.z + off * nz;
  const [xa, xb] = sx > 0 ? [fx, fx + w] : [fx - w, fx];
  add('vent', role, [xa, y0, fz - thick], [xb, y1, fz], { sx, sg, rotation: { origin: [fx, 0, fz], angle: [0, sx * sg.deg, 0] } });
}
for (const sx of [1, -1]) for (const sg of SEG) slab('shell', sx, sg, bottomOf(sg.a0 + STEP / 2), topOf(sg.a0 + STEP / 2), 0, T);
// the raised chin plate: three slabs a side, the lower corners chamfered
for (const sx of [1, -1]) for (const [i, w] of [[0, 0.5], [1, 0.5], [2, 0.5]]) slab('plate', sx, SEG[i], plateY0(SEG[i].a0 + w / 2), PLATE.y1, 0.125, 0.375, w);

// vent filters on the cheeks: an octagonal barrel of 8 chrome slabs round a recessed disc
const FI = 7;                                                            // the shell slab the filter sits on
const FR = { apo: 1.5, wall: 0.375, w: 1.5, prot: 0.25, sink: 0.375, fy: 1.3, turn: 0.5 };   // turn: how much of the shell's heading the filter follows
const FILTER = {};
for (const sx of [1, -1]) {
  const sg = SEG[FI], xm = sg.x + 0.25 * Math.cos(sg.phi), zm = sg.z - 0.25 * Math.sin(sg.phi);
  const cx = sx * xm, cy = FR.fy, z0 = zm - FR.sink, zf = zm + FR.prot, bone = sx > 0 ? 'filterL' : 'filterR';
  FILTER[bone] = { sx, pivot: [cx, cy, zm], rot: sx * sg.deg * FR.turn, zf };
  for (let k = 0; k < 8; k++) {
    add(bone, 'barrel', [cx - FR.w / 2, cy + FR.apo - FR.wall, z0], [cx + FR.w / 2, cy + FR.apo, zf], { k, sx, rotation: { origin: [cx, cy, z0], angle: [0, 0, 45 * k] } });
  }
}
// straps: two a side from the shell's ends back along the head, round the back; a chrome slider on each upper
// strap and one buckle at the back centre
const SY = { up: [1.75, 2.125], lo: [0.625, 1.0] };
for (const sx of [1, -1]) {
  const [a, b] = sx > 0 ? [3.875, 4.25] : [-4.25, -3.875];                          // the inner texel sinks into the head so the strap sits flush on the cheek
  for (const k of ['up', 'lo']) add('vent', 'strap', [a, SY[k][0], -4.25], [b, SY[k][1], 3.0], { sx, long: true });
  const [s0, s1] = sx > 0 ? [3.875, 4.375] : [-4.375, -3.875];
  add('vent', 'slider', [s0, SY.up[0] - 0.125, 1.0], [s1, SY.up[1] + 0.125, 1.5], { sx });
}
for (const k of ['up', 'lo']) add('vent', 'strap', [-4.25, SY[k][0], -4.25], [4.25, SY[k][1], -3.875], { sx: 0, rear: true });
add('vent', 'buckle', [-0.75, SY.lo[0] - 0.125, -4.625], [0.75, SY.up[1] + 0.125, -4.25], { sx: 0 });
layoutCubes(parts, [64, 32], S);
const USED = Math.ceil(Math.max(...parts.map(c => c.uv[1] + (c.to[2] - c.from[2]) + (c.to[1] - c.from[1]))));   // rows the boxes take
TEX[1] = USED + 3;

// the two filter discs: zero-thickness planes (south face only), their rectangles in the 2 spare rows below the boxes
const DISC = 18, DR = DISC / 2;
const discs = Object.entries(FILTER).map(([bone, f], n) => {
  const c = f.pivot, z = f.zf - 0.25, h = (DISC * px) / 2, u = n * (DISC * px + 0.125), v = USED;
  const rect = [u, v, u + DISC * px, v + DISC * px];
  return { bone, role: 'disc', sx: f.sx, rect, from: [c[0] - h, c[1] - h, z], to: [c[0] + h, c[1] + h, z], faces: { south: rect } };
});

// ---- local frame ---------------------------------------------------------------------------------------------------
function world(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) * px, v = (y + 0.5) * px;
  switch (f) {
    case 'south': return [x0 + u, y1 - v, z1];
    case 'north': return [x1 - u, y1 - v, z0];
    case 'east': return [x1, y1 - v, z1 - u];
    case 'west': return [x0, y1 - v, z0 + u];
    case 'up': return [x1 - u, y1, z1 - v];
    default: return [x1 - u, y0, z0 + v];
  }
}
const arcOf = (c, X) => (c.sx > 0 ? c.sg.a0 + (X - c.from[0]) : -(c.sg.a0 + (c.to[0] - X)));

// ---- the mark (lib/marks, 20 texels, 1 px stroke) -------------------------------------------------------------------
const MARKC = (() => {
  const m = PLATE.mark, c = new Canvas(m + 2, m + 2);
  drawMark(c, (m + 2) / 2, (m + 2) / 2, m, p => MK[clamp(Math.floor(p.light * MK.length), 0, MK.length - 1)], 1);
  return c;
})();
const markAt = (a, Y) => {
  const ix = Math.floor(a * S + MARKC.w / 2), iy = Math.floor((PLATE.my - Y) * S + MARKC.h / 2);
  return MARKC.alpha(ix, iy) ? MARKC.get(ix, iy) : null;
};

// ---- the shell --------------------------------------------------------------------------------------------------------
// recessed lines: a groove is a dark texel with a lit lip on its light side. All in the (a, Y) texel grid.
const cell = (a, Y) => [Math.floor(a * S + 1e-6), Math.floor(Y * S + 1e-6)];
function grooveAt(a, Y) {
  const [col, row] = cell(a, Y);
  const dark = (cc, rr) => {
    const x = (cc + 0.5) / S, y = (rr + 0.5) / S, ax = Math.abs(x);
    if (!inShell(x, y) || inPlate(x, y)) return false;
    if (Math.abs(ax - 1.75) < px / 2 && y > bottomOf(ax) + 0.25 && y < topOf(ax) - 0.125) return true;   // cheek seams beside the plate
    if (y >= PLATE.y1 && y < PLATE.y1 + px && ax < 1.75) return true;                                    // seam over the plate
    if (y >= 2.875 && y < 2.875 + px && ax > 0.375 && ax < 1.125) return true;                            // two nose vent slits
    return false;
  };
  return dark(col, row) ? 'dark' : dark(col - 1, row) || dark(col, row - 1) ? 'lit' : null;
}
function shellL(c, a, Y, f) {
  const sx = c.sx, phi = c.sg.phi;
  let L = 3.5 + 1.5 * sx * Math.sin(phi) + 0.7 * smoothstep(-0.5, 3.3, Y);          // facing +x catches the light; the top is a touch brighter
  L += 0.3 * smoothstep(-5, 5, a);
  const top = topOf(Math.abs(a)), bot = bottomOf(Math.abs(a));
  if (Y >= top - px) L += 3.0;                                                        // lit top edge
  else if (Y >= top - 2 * px) L += 1.1;
  if (Y < bot + px) L -= 1.4;                                                         // shaded lower edge
  // a soft moulded ridge down the centre (the nose-to-chin crest)
  if (Math.abs(a) < 0.125) L += 0.5;
  if (f !== 'south') L -= 0.4;
  return L;
}
function paintShell(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    const a = arcOf(c, X);
    let L = shellL(c, a, Y, f);
    if (f === 'south') {
      const g = grooveAt(a, Y);
      if (g === 'dark') L = 0.2;
      else if (g === 'lit') L += 2.2;
    } else if (f === 'down' || f === 'north') L = 1.2;
    else if (f === 'up') L = 4.2;
    v.set(x, y, toHex(lerpStep(BK, clamp(L, 0, 7))));
  }
}
// ---- the plate ----------------------------------------------------------------------------------------------------------
function paintPlate(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    const a = arcOf(c, X);
    let col;
    if (f === 'south') {
      // raised bevel: a bright 1 texel rim top/right-ish (light from the +x top), dark rim bottom; the field is brushed gunmetal
      const up = !inPlate(a, Y + px), dn = !inPlate(a, Y - px);
      const outerSide = !inPlate(a + px, Y), innerSide = !inPlate(a - px, Y);
      const lit = a < 0 ? innerSide : outerSide;                                     // (side nearest the light is the +x one)
      void lit;
      const sideRim = a > 0 ? outerSide : (!inPlate(a - px, Y));
      const mk = markAt(a, Y);
      if (mk) col = mk;
      else if (up) col = pick(CH, 5.3);
      else if (dn) col = pick(GM, 0.3);
      else if (sideRim) col = a > 0 ? pick(CH, 3.6) : pick(GM, 1.2);
      else {
        let g = 2.6 + 0.35 * Math.sin((Y * S + 3) * 0.9) * 0 + 0.5 * smoothstep(-1.2, 1.2, a);
        if (!inPlate(a, Y + 2 * px)) g = 4.2;                                         // inner bevel highlight under the top rim
        else if (!inPlate(a, Y - 2 * px)) g = 1.4;
        if (Math.floor(Y * S) % 3 === 0) g -= 0.35;                                   // brushed, fine horizontal lines
        // a recessed halo around the mark keeps it crisp
        let near = 0;
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) if ((dx || dy) && markAt(a + dx * px, Y + dy * px)) near = 1;
        if (near) g = 0.5;
        col = pick(GM, g);
      }
    } else if (f === 'up') col = pick(CH, 5);
    else if (f === 'down') col = pick(GM, 0);
    else col = pick(GM, 2);
    v.set(x, y, toHex(col));
  }
}
// ---- the filters ---------------------------------------------------------------------------------------------------------
// barrel slab k points its outer face along (-sin 45k, cos 45k) in the filter's plane; lit from the upper +x
const LDIR = [0.55, 0.75];
function paintBarrel(v, f, c) {
  const ang = (45 * c.k * Math.PI) / 180, n = [-Math.sin(ang), Math.cos(ang)];
  const lam = clamp(n[0] * LDIR[0] + n[1] * LDIR[1]);                                 // how lit this slab's outer face is
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (f === 'south') {                                                              // the rim seen from the front: a lit ring
      s = 3.0 + 3.4 * lam;
      if (y === 0) s += 0.8;                                                          // outer edge
      if (y === v.h - 1) s -= 1.4;                                                    // the lip into the disc
      if (x === 0 || x === v.w - 1) s -= 0.6;
    } else if (f === 'up') {                                                          // the barrel's outer wall: brushed, with a knurl band
      s = 1.6 + 3.6 * lam;
      if (x % 3 === 1) s -= 0.5;
      if (y === 0) s += 0.9;
      if (y === v.h - 1) s -= 1.1;
    } else if (f === 'down') s = 0.6;                                                 // the inside wall
    else s = 1.2 + 1.8 * lam;
    v.set(x, y, pick(CH, s));
  }
}
function paintDisc(tex, d) {
  const [u, vv] = d.rect.map(n => Math.round(n * S));
  for (let y = 0; y < DISC; y++) for (let x = 0; x < DISC; x++) {
    const dx = x + 0.5 - DR, dy = y + 0.5 - DR, r = Math.hypot(dx, dy), th = Math.atan2(dy, dx);
    if (r > DR) continue;                                                             // round cut-out
    let s, bank = CH;
    const cone = Math.cos(2 * (th + Math.PI / 4));                                    // brushed-metal conic sheen, bright toward the upper right
    if (r > 7.7) { bank = CH; s = 0.6; }                                              // the dark seat gap
    else if (r > 5.9) { s = 5.0 + 1.8 * cone; }   // outer chrome ring
    else if (r > 4.8) { s = 0.5; }                                       // inner groove
    else if (r > 2.3) {                                                               // louvred filter: six radial slots
      const k = ((th * 2) / Math.PI + 12.5) % 1, slot = Math.abs(k - 0.5) < 0.2;
      s = slot ? 0.2 : 4.4 + 1.8 * cone;
      if (slot && dy > 0 && dx < 0.5) s = 0.9;
    } else {                                                                          // hub: a little dome
      const nz = Math.sqrt(Math.max(0, 1 - (r / 2.3) ** 2)), l = clamp((dx * 0.5 + -dy * 0.75) / 2.3 * 0.8 + nz * 0.6);
      s = 2.4 + 4.4 * l;
    }
    tex.set(u + x, vv + y, toHex(pick(bank, s)));
  }
}
// ---- straps, sliders, buckle ----------------------------------------------------------------------------------------------
function paintStrap(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    let s = f === 'up' ? 3.2 : f === 'down' ? 0.4 : (Y > c.to[1] - 2 * px ? 3.6 : Y < c.from[1] + px ? 0.8 : 2.2);
    const along = c.rear ? X : Z;                                                     // fine woven ribs along the strap
    if (Math.floor((along + 10) * S) % 3 === 0) s -= 0.55;
    if (f !== 'up' && f !== 'down' && Y > c.to[1] - 2 * px) s += 0.3;
    v.set(x, y, pick(NY, s));
  }
}
function paintMetal(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    let s = { up: 6, south: 4.2, east: 4.6, west: 2.4, north: 3.2, down: 0.8 }[f] ?? 3;
    if (f !== 'up' && f !== 'down') {
      if (y === 0) s += 1.4; else if (y === v.h - 1) s -= 1.6;
      if (c.role === 'buckle' && f === 'north' && x >= 3 && x < v.w - 3 && (y === Math.floor(v.h / 2) || y === Math.floor(v.h / 2) + 1)) s = 0.6;   // the strap slot
      if (c.role === 'slider' && (f === 'east' || f === 'west') && y > 1 && y < v.h - 2 && x > 1 && x < v.w - 2) s = 0.7;                       // the strap passes through
    }
    void X; void Y; void Z;
    v.set(x, y, pick(CH, s));
  }
}
const PAINT = { shell: paintShell, plate: paintPlate, barrel: paintBarrel, strap: paintStrap, slider: paintMetal, buckle: paintMetal };

function paint() {
  const t = newTexture({ texSize: TEX, scale: S });
  for (const c of parts) paintCube(t, c, S, (v, f) => PAINT[c.role](v, f, c));
  for (const d of discs) paintDisc(t, d);
  return t;
}

// ---- model --------------------------------------------------------------------------------------------------------------------
const strip = ({ bone, role, sx, sg, k, long, rear, rect, ...c }) => c;
const bones = [
  { name: 'vent', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.filter(p => p.bone === 'vent').map(strip) },
  ...Object.entries(FILTER).map(([name, f]) => ({
    name, parent: 'vent', pivot: f.pivot, rotation: [0, f.rot, 0],
    cubes: [...parts.filter(p => p.bone === name).map(strip), ...discs.filter(d => d.bone === name).map(strip)],
  })),
];

export default {
  meta: { id: 'mask-vent', name: 'Vent Mask', kind: 'mask', cents: 699, blurb: 'A matte black techwear respirator: moulded shell, two brushed-chrome vent filters and a little Origin plate on the chin.' },
  model: { texSize: TEX, scale: S, bones },
  paint,
};
