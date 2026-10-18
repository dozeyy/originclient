// Sport Headband: an off-white terry-cloth sweatband around the forehead. Real looped pile (staggered
// 4 x 3 texel loops with a lit crest and a dark crevice under each, a little per-loop variation), a rolled
// top edge with a row of fine stitching, a thin polished-chrome tape stripe round the lower third, and the
// Origin mark embroidered at the front in graphite satin thread. The ring is an octagon with 45-degree corner
// slabs (so it reads round from every side), and a stitched seam closes it at the back of the head.
//
// Light: top-front-left of the PLAYER (+x, +y, +z), the lineup convention (lib/model LIGHT). Every texel
// is shaded from where it sits on the ring (its angle round the head), so the form is smooth across the walls and
// corner slabs and quantises into clean bands. The mark is rasterised by lib/marks at the size it is stitched
// (16 texels = 2 model px) and never resampled.
// Clearance: y 4.25..7.75 (under the head top), radius <= 4.75 (0.75 px past the head).
import { clamp, smoothstep, hash2, toHex, mix } from '../../lib/px.mjs';
import { layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';
import { markMask } from '../../lib/marks.mjs';

const S = 8, px = 1 / S;
const TEX = [64, 20];

// ---- ramps ---------------------------------------------------------------------------------------------
// Off-white terry, 6 steps: cool violet-grey shadows -> neutral -> warm cream lights.
const TER = ['#5c5a6c', '#86849a', '#b3b0bd', '#d7d3d2', '#efeadf', '#fff8e6'];
// Graphite embroidery thread, darkest -> the lit crown of the stitch.
const THR = ['#0e1015', '#191c23', '#292d37', '#40465a', '#666e84'];
// Polished chrome tape: a cool dark edge, mid steel, bright sky reflection, a white glint.
const CH = ['#262b36', '#4d5565', '#838c9d', '#bcc3cf', '#e9edf2', '#ffffff'];
const terCol = v => TER[clamp(Math.round(v), 0, TER.length - 1)];
const chCol = v => CH[clamp(Math.round(v), 0, CH.length - 1)];

// ---- geometry (head frame: head cube x -4..4, y 0..8, z -4..4) -------------------------------------------
const Y0 = 4.25, Y1 = 7.75;                                  // 28 texel rows (3.5 px) at the front: just above the eyes, just under the head top
const OUT = 4.75, T = 0.75, IN = OUT - T;                   // wall thickness; inner face on the head
const H = 28;
const L = 1.125, CL = 9;                                    // corner slab: 9 texels along the ring, 6 thick
const parts = [];
const part = (role, from, to, extra = {}) => { const c = { from, to, uv: [0, 0], role, ...extra }; parts.push(c); return c; };
// Ring numbering `a0` (texels) runs from the back seam toward the player's right and round (front, left, back), so
// the loop pattern is continuous everywhere except under the seam. 292 = 4 * 73 closes it on 4 texel loops.
//   back (right half) 0..31, back-right slab 32..40, right wall 41..104, front-right slab 105..113,
//   front 114..177, front-left slab 178..186, left wall 187..250, back-left slab 251..259, back (left half) 260..291.
part('wall', [-4, Y0, IN], [4, Y1, OUT], { id: 'front', face: 'south', a0: 114, dir: 1 });
part('wall', [-4, Y0, -OUT], [4, Y1, -IN], { id: 'back', face: 'north', a0: 260, dir: 1 });
part('wall', [IN, Y0, -4], [OUT, Y1, 4], { id: 'left', face: 'east', a0: 187, dir: 1 });
part('wall', [-OUT, Y0, -4], [-IN, Y1, 4], { id: 'right', face: 'west', a0: 41, dir: 1 });
// corner slabs: an UNROTATED box in its own frame, then turned about the vertical axis through its centre.
// phi turns local +z (the outward normal) to (sin phi, cos phi) in (x, z); local +x runs along the ring.
const M = (4 + OUT) / 2, C = M - (T / 2) * Math.SQRT1_2;       // slab centre: the outer face's midpoint, less half the thickness along the diagonal
for (const [id, sx, sz, phi, a0] of [['fl', 1, 1, 45, 178], ['fr', -1, 1, -45, 105], ['bl', 1, -1, 135, 251], ['br', -1, -1, -135, 32]]) {
  part('slab', [sx * C - L / 2, Y0, sz * C - T / 2], [sx * C + L / 2, Y1, sz * C + T / 2],
    { id, face: 'south', a0, dir: 1, phi, ctr: [sx * C, sz * C], rotation: { origin: [sx * C, (Y0 + Y1) / 2, sz * C], angle: [0, phi, 0] } });
}
layoutCubes(parts, TEX, S);
const strip = ({ role, id, face, a0, dir, phi, ctr, ...c }) => c;

// texel centre -> position on the cube surface in the cube's own frame (face images read from outside, +y up)
function local(c, f, x, y) {
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
// ... and in the head frame (slabs are turned)
function world(c, f, x, y) {
  const [X, Y, Z] = local(c, f, x, y);
  if (c.phi === undefined) return [X, Y, Z];
  const [cx, cz] = c.ctr, r = (c.phi * Math.PI) / 180, s = Math.sin(r), k = Math.cos(r), dx = X - cx, dz = Z - cz;
  return [cx + dx * k + dz * s, Y, cz - dx * s + dz * k];
}

// ---- light ---------------------------------------------------------------------------------------------
const LH = (() => { const v = [0.42, 0.55], l = Math.hypot(...v); return v.map(a => a / l); })();       // horizontal light direction (x, z)
// how lit a point of the ring is: the outward normal is the angle round the head
const lit = (X, Z) => { const h = Math.hypot(X, Z) || 1; return (X / h) * LH[0] + (Z / h) * LH[1]; };     // -1 .. 1

// ---- terry pile -------------------------------------------------------------------------------------------
// Staggered loops, 4 texels wide and 3 tall (292 texels round = 73 loops, so the pattern closes): each loop has a
// lit crest on top, a body, a gap column on its shadow side and a dark underside; the next row sits half a loop
// over, so the crevices never line up into rules. A touch of per-loop variation, hashed so every build is identical.
const CREST = [[0.1, 1.0, 1.1, -0.2], [0.3, 0.6, 0.3, -0.9], [-0.8, -0.3, -0.6, -1.3]];
function pile(a, b, seed = 0) {
  const row = Math.floor(b / 3), ly = ((b % 3) + 3) % 3, ax = a + (row & 1) * 2, lx = ((ax % 4) + 4) % 4, cell = Math.floor(ax / 4);
  return CREST[ly][lx] * 0.9 + (hash2(((cell % 73) + 73) % 73, row, 11 + seed) - 0.5) * 0.9;
}

// ---- the mark ----------------------------------------------------------------------------------------------
// Rasterised by lib/marks at 24 texels (3 model px) with a 2 texel stroke (smaller sizes turn to mush), centred
// by its drawn extent on the front wall (64 x 28 texels) in the field rows 3..23 (stitching above, tape below).
const MARK_SIZE = 24, MARK = markMask(MARK_SIZE, 2);
const ext = (() => { let x0 = 99, x1 = -1, y0 = 99, y1 = -1; for (let y = 0; y < MARK.h; y++) for (let x = 0; x < MARK.w; x++) if (MARK.ring[y * MARK.w + x] >= 0) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); } return { x0, x1, y0, y1 }; })();
const MOX = 32 - Math.round((ext.x0 + ext.x1 + 1) / 2), MOY = 13 - Math.round((ext.y0 + ext.y1 + 1) / 2) + 0;
const mAt = (x, y) => { const i = (y - MOY) * MARK.w + (x - MOX); return x - MOX < 0 || y - MOY < 0 || x - MOX >= MARK.w || y - MOY >= MARK.h ? -1 : i; };
const markRing = (x, y) => { const i = mAt(x, y); return i < 0 ? -1 : MARK.ring[i]; };
// embroidery light: a rounded satin cord lit from the upper right of the face image (+x is image-right on the front)
function threadLight(i) {
  const nx = MARK.nx[i], ny = MARK.ny[i], t = MARK.t[i], z = Math.sqrt(Math.max(0, 1 - t * t));
  const n = [nx * t, ny * t, z], len = Math.hypot(...n) || 1, Lv = [0.55, -0.8, 0.45], ll = Math.hypot(...Lv);
  return clamp(0.5 + 0.5 * ((n[0] * Lv[0] + n[1] * Lv[1] + n[2] * Lv[2]) / (len * ll)) * 1.25);
}

// ---- painting ---------------------------------------------------------------------------------------------
// The band is contoured: full height across the front (where the mark sits) and rising at the lower edge round the
// sides to 16 rows (2 px) at the back, so it reads as a sport headband and not a bucket. The top stays level.
// `cutRows` = rows removed from the bottom at a point of the ring; every face applies the same test.
const cutRows = (X, Z) => Math.round(12 * smoothstep(35, 150, (Math.abs(Math.atan2(X, Z)) * 180) / Math.PI));
function paintBand(v, f, c) {
  const outer = f === c.face, wall = c.role === 'wall';
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    const d = lit(X, Z), cut = cutRows(X, Z), e = H - 1 - cut;               // e = last kept row
    if (f === 'down' ? cut > 0 : f !== 'up' && y > e) continue;               // cut away (transparent)
    if (f === 'up') { v.set(x, y, terCol(4.0 + 0.8 * d + pile(x + c.a0, y, 3) * 0.8)); continue; }
    if (f === 'down') { v.set(x, y, terCol(1.1 + 0.6 * d + pile(x + c.a0, y, 4) * 0.5)); continue; }
    if (!outer) { v.set(x, y, terCol(1.9 + 0.7 * d + pile(x + c.a0, y, 5) * 0.6)); continue; }          // inner face and the ends: the same pile, in shade
    // ring coordinate in texels, and the row from the top
    const a = c.a0 + (c.dir > 0 ? x : v.w - 1 - x), r = y;
    const base = 2.9 + 1.4 * d;                                             // the form: how lit this part of the ring is
    let col;
    if (r === 0) col = terCol(base + 1.5);                                  // the rolled top edge catches the light
    else if (r === 1) col = terCol(base + 0.9 + pile(a, 1, 1) * 0.5);
    else if (r === 2) col = (a % 4 === 0 || a % 4 === 1) ? terCol(base - 1.7) : terCol(base - 0.2);  // a row of stitching: dashes
    else if (e - r === 1 || e - r === 2) col = tape(e - r, a, d);           // the chrome tape follows the lower edge
    else if (e - r === 3) col = terCol(base - 1.9);                         // a thin dark seat above the flat tape
    else if (e - r === 0) col = terCol(base - 0.9 + pile(a, 27, 2) * 0.3);  // bottom edge, in shade
    else col = terCol(base + pile(a, r, 0));
    // embroidery on the front wall
    if (c.id === 'front') col = embroider(x, y, col, base);
    // the stitched seam at the back: two columns, the right panel's edge lit
    if (c.id === 'back' && r > 2 && r < e && (x === 31 || x === 32)) col = x === 31 ? terCol(base - 1.6) : terCol(base + 1.2);
    if (c.id === 'back' && r > 3 && r < e - 1 && (x === 30 || x === 33) && r % 3 === 0) col = terCol(base - 1.2);   // thread ticks either side
    v.set(x, y, col);
  }
}
// chrome tape: 2 texels. The upper is the bright sky reflection, the lower the dim steel, with a few bright
// glints along the ring (a polished curve catches the light at the corners most).
function tape(r, a, d) {
  const sheen = 2.5 + 1.4 * d + 0.35 * Math.sin(a * 0.19);
  const spark = hash2(Math.floor(a / 7), 5, 3) > 0.86 ? 1 : 0;
  return r === 2 ? chCol(sheen + 1.0 + spark) : chCol(sheen - 1.0 + spark * 0.5);
}
// thread, its soft shadow on the pile, and a pressed-flat patch under the stitching
function embroider(x, y, under, base) {
  const ring = markRing(x, y);
  if (ring >= 0) {
    const i = mAt(x, y);
    let s = threadLight(i) * (THR.length - 0.01);
    // where another ring crosses beside this one, darken the edge so the strands read as separate
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const q = markRing(x + dx, y + dy); if (q >= 0 && q !== ring) s -= 0.55; }
    return THR[clamp(Math.round(s), 0, THR.length - 1)];
  }
  // pressed pile around the stitching: shadow to the lower right, a faint lit rim to the upper left
  const sh = markRing(x - 1, y - 1) >= 0 || markRing(x - 1, y) >= 0 || markRing(x, y - 1) >= 0;
  const lite = markRing(x + 1, y + 1) >= 0 || markRing(x + 1, y) >= 0 || markRing(x, y + 1) >= 0;
  if (sh) return toHex(mix(under, TER[0], 0.55));
  if (lite) return toHex(mix(under, TER[5], 0.25));
  return under;
}

let tex = null;
function texture() {
  if (tex) return tex;
  const t = newTexture({ texSize: TEX, scale: S });
  for (const c of parts) paintCube(t, c, S, (v, f) => paintBand(v, f, c));
  return (tex = t);
}

export default {
  meta: { id: 'hat-headband', name: 'Sport Headband', kind: 'hat', cents: 599, blurb: 'An off-white terry sweatband with looped pile, a polished chrome stripe and the Origin mark stitched at the front.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'band', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.map(strip) }] },
  paint() { return texture().clone(); },
};
