// Sport Headband: a slim off-white terry sweatband hugging the forehead, just above the eyes. Soft horizontal
// ribs (crest / body / groove, 4 tones) lit from the top-front-left, a rolled top edge, a thin polished-chrome
// tape stripe round the lower edge, and a small gunmetal badge at the front carrying the Origin mark in silver
// thread, one tone per ring with a dark gap where rings cross. The ring is an octagon with 45-degree corner
// slabs (so it reads round from every side); a stitched seam closes it at the back of the head.
//
// Light: top-front-left of the PLAYER (+x, +y, +z), the lineup convention (lib/model LIGHT). Every texel is shaded
// from where it sits on the ring (its angle round the head). The mark is rasterised by lib/marks at the size it
// is stitched (24 texels = 3 model px) and never resampled.
// Clearance: band y 4.5..7.5, radius <= 4.375 (0.375 past the head); the badge face sits at z 4.5.
import { clamp, smoothstep, hash2 } from '../../lib/px.mjs';
import { layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';
import { markMask } from '../../lib/marks.mjs';

const S = 8, px = 1 / S;
const TEX = [64, 24];

// ---- ramps -------------------------------------------------------------------------------------------------
// Off-white terry, 4 tones: violet-grey shadow -> grey -> warm off-white -> cream light.
const TER = ['#6c697d', '#a8a6b3', '#dcd8d3', '#fff6e2'];
// Silver embroidery thread on the badge, dark -> lit crown of the stitch.
const SIL = ['#3b404c', '#6d7484', '#a4abb9', '#d9dde5', '#ffffff'];
// Gunmetal badge face (cool shadow -> warm specular), and polished chrome for the bezel and the tape.
const GUN = ['#0c0d11', '#171a20', '#252931', '#383d47', '#4f5560'];
const CH = ['#262b36', '#4d5565', '#838c9d', '#bcc3cf', '#e9edf2', '#ffffff'];
const terCol = v => TER[clamp(Math.round(v), 0, TER.length - 1)];
const chCol = v => CH[clamp(Math.round(v), 0, CH.length - 1)];

// ---- geometry (head frame: head cube x -4..4, y 0..8, z -4..4) ---------------------------------------------
const Y0 = 4.5, Y1 = 7.5;                                   // 24 texel rows (3 px) at the front: just above the eyes, under the head top
const OUT = 4.375, T = 0.375, IN = OUT - T;                 // 3 texels thick, inner face on the head
const H = 24;
const L = 0.875;                                            // corner slab: 7 texels along the ring
const parts = [];
const part = (role, from, to, extra = {}) => { const c = { from, to, uv: [0, 0], role, ...extra }; parts.push(c); return c; };
// `a0` (texels) runs round the ring from the back seam toward the player's right, only to keep the slow sheen continuous.
part('wall', [-4, Y0, IN], [4, Y1, OUT], { id: 'front', face: 'south', a0: 114, dir: 1 });
part('wall', [-4, Y0, -OUT], [4, Y1, -IN], { id: 'back', face: 'north', a0: 260, dir: 1 });
part('wall', [IN, Y0, -4], [OUT, Y1, 4], { id: 'left', face: 'east', a0: 187, dir: 1 });
part('wall', [-OUT, Y0, -4], [-IN, Y1, 4], { id: 'right', face: 'west', a0: 41, dir: 1 });
// corner slabs: an UNROTATED box in its own frame, then turned about the vertical axis through its centre.
const M = (4 + OUT) / 2, C = M - (T / 2) * Math.SQRT1_2;
for (const [id, sx, sz, phi, a0] of [['fl', 1, 1, 45, 178], ['fr', -1, 1, -45, 105], ['bl', 1, -1, 135, 251], ['br', -1, -1, -135, 32]]) {
  part('slab', [sx * C - L / 2, Y0, sz * C - T / 2], [sx * C + L / 2, Y1, sz * C + T / 2],
    { id, face: 'south', a0, dir: 1, phi, ctr: [sx * C, sz * C], rotation: { origin: [sx * C, (Y0 + Y1) / 2, sz * C], angle: [0, phi, 0] } });
}
// the badge: a 30 texel disc plate (only its front face is painted, the rest stays transparent), a hair taller than the band
const BADGE = { d: 30, y0: 4.125, y1: 7.875, z0: 4.25, z1: 4.5 };
part('badge', [-BADGE.d / 16, BADGE.y0, BADGE.z0], [BADGE.d / 16, BADGE.y1, BADGE.z1], { id: 'badge' });
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

// ---- light -------------------------------------------------------------------------------------------------
const LH = (() => { const v = [0.42, 0.55], l = Math.hypot(...v); return v.map(a => a / l); })();       // horizontal light direction (x, z)
const lit = (X, Z) => { const h = Math.hypot(X, Z) || 1; return (X / h) * LH[0] + (Z / h) * LH[1]; };     // -1 .. 1

// ---- the badge ---------------------------------------------------------------------------------------------
// Mark: 24 texels, 2 texel stroke, centred by drawn extent in the 30 texel disc.
const MARK_SIZE = 24, MARK = markMask(MARK_SIZE, 2);
const ext = (() => { let x0 = 99, x1 = -1, y0 = 99, y1 = -1; for (let y = 0; y < MARK.h; y++) for (let x = 0; x < MARK.w; x++) if (MARK.ring[y * MARK.w + x] >= 0) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); } return { x0, x1, y0, y1 }; })();
const MOX = BADGE.d / 2 - Math.round((ext.x0 + ext.x1 + 1) / 2), MOY = BADGE.d / 2 - Math.round((ext.y0 + ext.y1 + 1) / 2);
const mAt = (x, y) => { const i = (y - MOY) * MARK.w + (x - MOX); return x - MOX < 0 || y - MOY < 0 || x - MOX >= MARK.w || y - MOY >= MARK.h ? -1 : i; };
const markRing = (x, y) => { const i = mAt(x, y); return i < 0 ? -1 : MARK.ring[i]; };
// satin cord lit from the upper right of the face image (+x is image-right on the front)
function threadLight(i) {
  const nx = MARK.nx[i], ny = MARK.ny[i], t = MARK.t[i], z = Math.sqrt(Math.max(0, 1 - t * t));
  const n = [nx * t, ny * t, z], len = Math.hypot(...n) || 1, Lv = [0.55, -0.8, 0.45], ll = Math.hypot(...Lv);
  return clamp(0.5 + 0.5 * ((n[0] * Lv[0] + n[1] * Lv[1] + n[2] * Lv[2]) / (len * ll)) * 1.25);
}
const RING_TONE = [0.35, 0, -0.35];                          // ring 0 (long) brightest, ring 2 dimmest: strands read apart
function paintBadge(v) {
  const R = BADGE.d / 2;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const dx = x + 0.5 - R, dy = y + 0.5 - R, r = Math.hypot(dx, dy);
    if (r > R) continue;
    const ux = dx / (r || 1), uy = -dy / (r || 1), face = ux * 0.55 + uy * 0.8;          // outward normal . light (image space)
    if (r > R - 1) { v.set(x, y, face > 0 ? CH[3] : CH[0]); continue; }                  // outline: crisp bright rim toward the light, dark away
    if (r > R - 3) { v.set(x, y, chCol(2.6 + 2.1 * face)); continue; }                   // polished chrome bezel, 2 texels
    if (r > R - 4) { v.set(x, y, GUN[0]); continue; }                                    // dark seat between bezel and face
    const ring = markRing(x, y);
    if (ring >= 0) {
      let s = threadLight(mAt(x, y)) * (SIL.length - 0.01) + 0.25 + RING_TONE[ring];
      for (const [ox, oy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const q = markRing(x + ox, y + oy); if (q >= 0 && q !== ring) s -= 0.9; }   // gap where strands cross
      v.set(x, y, SIL[clamp(Math.round(s), 0, SIL.length - 1)]);
      continue;
    }
    // gunmetal face: a soft dome lit from the upper right, a hint of thread shadow to the lower right of each strand
    let g = 1.2 + 1.8 * (0.5 + 0.5 * face) * (r / (R - 4));
    if (markRing(x - 1, y - 1) >= 0 || markRing(x - 1, y) >= 0 || markRing(x, y - 1) >= 0) g -= 1.0;
    v.set(x, y, GUN[clamp(Math.round(g), 0, GUN.length - 1)]);
  }
}

// ---- terry painting ----------------------------------------------------------------------------------------
// The band rises at the lower edge round the sides (6 rows toward the back) so it reads as a sport headband, not a
// bucket. The top stays level. `cutRows` = rows removed from the bottom at a point of the ring.
const cutRows = (X, Z) => Math.round(6 * smoothstep(35, 150, (Math.abs(Math.atan2(X, Z)) * 180) / Math.PI));
// rib profile over 3 rows: lit crest, body, shaded groove
const RIB = [0.6, 0.1, -0.7];
function paintBand(v, f, c) {
  if (c.role === 'badge') { if (f === 'south') paintBadge(v); return; }
  const outer = f === c.face;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    const d = lit(X, Z), cut = cutRows(X, Z), e = H - 1 - cut;               // e = last kept row
    if (f === 'down' ? cut > 0 : f !== 'up' && y > e) continue;               // cut away (transparent)
    if (f === 'up') { v.set(x, y, terCol(2.2 + 0.8 * d)); continue; }
    if (f === 'down') { v.set(x, y, terCol(0.5 + 0.4 * d)); continue; }
    if (!outer) { v.set(x, y, terCol(0.9 + 0.7 * d + RIB[((y - 1) % 3 + 3) % 3] * 0.35)); continue; }   // inner face and the ends: ribs in shade
    const a = c.a0 + (c.dir > 0 ? x : v.w - 1 - x), r = y;
    const base = 1.55 + 0.95 * d + 0.18 * Math.sin(a * 0.11);                 // the form: how lit this part of the ring is
    let col;
    if (r === 0) col = terCol(base + 1.4);                                    // the rolled top edge catches the light
    else if (e - r === 3) col = terCol(base - 1.6);                           // shadow just above the tape
    else if (e - r === 2 || e - r === 1) col = tape(e - r, a, d);             // the chrome tape follows the lower edge
    else if (e - r === 0) col = terCol(base - 0.8);                           // bottom edge, in shade
    else col = terCol(base + RIB[(r - 1) % 3]);                               // soft horizontal ribs
    // the stitched seam at the back: two columns, the right panel's edge lit
    if (c.id === 'back' && r > 0 && r < e - 3 && (x === 31 || x === 32)) col = x === 31 ? terCol(base - 1.5) : terCol(base + 1.0);
    v.set(x, y, col);
  }
}
// chrome tape: 2 texels. The upper is the bright sky reflection, the lower the dim steel, with a few glints.
function tape(r, a, d) {
  const sheen = 2.4 + 1.4 * d + 0.35 * Math.sin(a * 0.19);
  const spark = hash2(Math.floor(a / 7), 5, 3) > 0.86 ? 1 : 0;
  return r === 2 ? chCol(sheen + 1.0 + spark) : chCol(sheen - 1.0 + spark * 0.5);
}

let tex = null;
function texture() {
  if (tex) return tex;
  const t = newTexture({ texSize: TEX, scale: S });
  for (const c of parts) paintCube(t, c, S, (v, f) => paintBand(v, f, c));
  return (tex = t);
}

export default {
  meta: { id: 'hat-headband', name: 'Sport Headband', kind: 'hat', cents: 599, blurb: 'A slim off-white terry sweatband with a polished chrome stripe and a gunmetal badge carrying the Origin mark in silver thread.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'band', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.map(strip) }] },
  paint() { return texture().clone(); },
};
