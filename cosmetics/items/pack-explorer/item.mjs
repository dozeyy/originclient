// Explorer Pack: a compact leather rucksack. A stuffed bag under an overhanging lid that drops into a
// front flap, two straps closed with brass buckles (their punched tongues hanging below), a lower pocket
// with a brass stud, and a rust wool bedroll strapped underneath with two leather ties. Shoulder straps
// show on the chest from the front.
//
// Everything is a plain box painted from each texel's WORLD position, lit from the top-left (a touch
// toward the back, where the pack is seen from): the stuffed bag and pocket bend their normals toward
// their edges (one-sided light, so it reads as volume, not a pillow); the bedroll is a 12-sided union of
// three boxes lit as a true cylinder, with round, spiral-rolled ends cut out of its end faces.
// 6-step hue-shifted leather (plum-brown shadows -> warm tan lights), a darker cooler strap leather,
// 5-step brass, rust wool with a cream border stripe. Stitching is a dashed cream thread inset on every
// panel. Non-emissive: the game's light and the renderer's face shade apply on top.
// Clearance (FORMAT.md): back item, inside x -4..4, deepest point z -6.25 (4.25 px off the back).
import { clamp, fbm } from '../../lib/px.mjs';
import { cube, layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, TEX = [64, 32];

// ---- ramps -------------------------------------------------------------------------------------------
const LEATHER = ['#2a1714', '#4a2a1e', '#6e3f25', '#93582f', '#b8773e', '#d89c5a'];
const STRAP = ['#1d1212', '#341f1a', '#4f2f21', '#6c432a', '#8a5a36'];
const BRASS = ['#3a2812', '#6e4f1f', '#b0862f', '#e3bf5c', '#fbe8a6'];
const WOOL = ['#2c1820', '#4b2027', '#722c2c', '#9a4232', '#bd6040', '#d9845a'];
const CREAM = ['#5e4c40', '#8f7c62', '#c2ad86', '#e6d6ae'];
const THREAD = '#cfb27c', THREAD_SH = '#3a221a';
const pick = (r, l) => r[clamp(Math.round(l), 0, r.length - 1)];

// ---- geometry (body frame: torso x -4..4, y -12..0, z -2..2; back surface z = -2) --------------------
const parts = [];
const add = (role, from, to, o = {}) => parts.push({ ...cube(from, to), _role: role, _o: o });
add('bag', [-3, -9, -5], [3, -1, -2]);
add('lid', [-3.25, -1, -5.5], [3.25, -0.25, -2]);
add('flap', [-3.25, -4.5, -5.5], [3.25, -1, -5]);
for (const s of [-1, 1]) {
  const x0 = s > 0 ? 1.5 : -2.25, x1 = x0 + 0.75;
  add('strap', [x0, -0.25, -5.75], [x1, 0, -2]);                // over the lid
  add('strap', [x0, -4.75, -5.75], [x1, -0.25, -5.5]);          // down the flap
  add('tongue', [x0, -6, -5.25], [x1, -4.5, -5]);               // the punched end, on the bag
  add('buckle', [x0 - 0.125, -5.25, -5.875], [x1 + 0.125, -4.25, -5.5]);
  add('shoulder', [s > 0 ? 1.75 : -3, -6, 2], [s > 0 ? 3 : -1.75, 0, 2.25]);   // on the chest
  add('slider', [s > 0 ? 1.625 : -3.125, -3.5, 2], [s > 0 ? 3.125 : -1.625, -3, 2.375]);
}
add('pocket', [-2.5, -8.5, -6], [2.5, -6, -5]);
add('pflap', [-2.625, -7, -6.25], [2.625, -5.75, -5]);
add('stud', [-0.25, -6.875, -6.375], [0.25, -6.375, -6.25]);
// bedroll: axis along x at (y, z) = (YC, ZC); three boxes make a 12-sided section
const YC = -10.25, ZC = -3.5, RR = 1.3;
const rollBox = (role, x0, x1, grow = 0) => [[1.25, 0.5], [0.5, 1.25], [1, 1]].forEach(([hy, hz]) =>
  add(role, [x0, YC - hy - grow, ZC - hz - grow], [x1, YC + hy + grow, ZC + hz + grow]));
rollBox('roll', -4, 4);
for (const s of [-1, 1]) rollBox('tie', s > 0 ? 2.125 : -2.625, s > 0 ? 2.625 : -2.125, 0.125);

const cubes = layoutCubes(parts, TEX, S, { pad: 1 });

// ---- lighting -----------------------------------------------------------------------------------------
const norm = v => { const l = Math.hypot(...v) || 1; return v.map(a => a / l); };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const L = norm([0.5, 0.8, -0.3]);                               // top, the player's left, a touch toward the back
const FN = { up: [0, 1, 0], down: [0, -1, 0], east: [1, 0, 0], west: [-1, 0, 0], south: [0, 0, 1], north: [0, 0, -1] };

// Box-UV texel -> world point on the face (cubes are unrotated, so cube-local = world).
function world(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) / S, v = (y + 0.5) / S;
  switch (f) {
    case 'south': return [x0 + u, y1 - v, z1];
    case 'north': return [x1 - u, y1 - v, z0];
    case 'east': return [x1, y1 - v, z1 - u];
    case 'west': return [x0, y1 - v, z0 + u];
    case 'up': return [x1 - u, y1, z1 - v];
    default: return [x1 - u, y0, z0 + v];
  }
}
// A stuffed panel: bend the face normal toward the nearest edges by k (0 = flat).
function bulge(c, f, p, k) {
  const n = FN[f].slice();
  for (let i = 0; i < 3; i++) if (!n[i]) { const lo = c.from[i], hi = c.to[i], t = (2 * (p[i] - lo)) / (hi - lo) - 1; n[i] = k * Math.sign(t) * Math.pow(Math.abs(t), 1.6); }
  return norm(n);
}
const lit = n => 0.5 + 0.5 * dot(n, L);                        // half-Lambert: side panels never crush to black

// dashed stitch inset d texels from the face border: 0 none, 1 thread, 2 thread shadow (below the thread)
function stitch(x, y, w, h, d, sides = 'tblr') {
  const onY = y % 3 !== 2;
  const inX = x >= d && x < w - d, inY = y >= d && y < h - d;
  if (sides.includes('t') && y === d && inX && x % 3 !== 2) return 1;
  if (sides.includes('t') && y === d + 1 && inX && x % 3 !== 2) return 2;
  if (sides.includes('b') && y === h - 1 - d && inX && x % 3 !== 2) return 1;
  if (sides.includes('l') && x === d && inY && onY) return 1;
  if (sides.includes('r') && x === w - 1 - d && inY && onY) return 1;
  return 0;
}

// ---- materials ----------------------------------------------------------------------------------------
// Each returns a colour for one texel; x,y,w,h are the texel and the face size in texture px.
function leather(c, f, p, x, y, w, h, base, k) {
  const n = bulge(c, f, p, k);
  let l = base + 2.6 * (lit(n) - 0.65);
  l += 0.22 * (fbm(p[0] * 0.6 + p[2] * 0.4, p[1] * 0.6, 7, 2) - 0.5) * 2;   // a faint tone drift: it only nudges band edges
  if (f !== 'up' && f !== 'down') { if (y === 0) l += 0.9; if (y === h - 1) l -= 1.1; l += 0.7 * (0.5 - y / h); }   // lit top edge, dark under-edge, light falling off downward
  return l;
}
const isSide = f => f !== 'up' && f !== 'down';

function shade(c, f, x, y, w, h) {
  const p = world(c, f, x, y), role = c._role;
  switch (role) {
    case 'bag': {
      let l = leather(c, f, p, x, y, w, h, 2.7, 0.9);
      if (f === 'north' && p[1] > -4.75) l -= 1.2;              // the flap's cast shadow
      if (f === 'north' && p[1] < -8.5 && Math.abs(p[0]) < 2.75) l -= 0.8;   // under the pocket
      if (f === 'north' && Math.abs(p[0]) < 2.75 && p[1] > -6.25 && p[1] < -6) l -= 0.7;   // over the pocket flap
      if ((f === 'east' || f === 'west') && p[2] < -4.6 && p[2] > -4.85 && p[1] % 0.375 > -0.25) return THREAD;   // back seam
      if (f === 'north' && p[1] < -4.75 && p[1] > -5.0) l -= 0.4;
      // two soft sag creases low on the back panel
      if (f === 'north' && Math.abs(p[0]) > 2.75) { const cr = p[1] + 7.6 + 0.15 * Math.abs(p[0]); if (Math.abs(cr) < 0.07) l -= 1; else if (cr > 0.07 && cr < 0.2) l += 0.6; }
      return pick(LEATHER, l);
    }
    case 'lid': {
      let l = leather(c, f, p, x, y, w, h, 3.0, 0.7);
      if (f === 'up') { const s = stitch(x, y, w, h, 2, 'lr'); if (s === 1) return THREAD; }
      return pick(LEATHER, l);
    }
    case 'flap': {
      let l = leather(c, f, p, x, y, w, h, 2.9, 0.5);
      if (f === 'north') {
        const s = stitch(x, y, w, h, 2, 'blr');
        if (s === 1) return THREAD;
        // rounded bottom corners (cut out)
        const cx = Math.min(x, w - 1 - x), cy = h - 1 - y;
        if (cx + cy < 2) return null;
        if (cx + cy === 2) l -= 1;
        if (Math.abs(p[0]) > 1.4 && Math.abs(p[0]) < 2.4 && p[1] > -1.15) l -= 0.8;   // under the strap
      }
      return pick(LEATHER, l);
    }
    case 'strap': case 'tongue': case 'shoulder': {
      let l = 2.6 + 2.2 * (lit(FN[f]) - 0.65);
      l += 0.2 * (fbm(p[0] * 2, p[1] * 2 + p[2], 3, 2) - 0.5) * 2;
      if (isSide(f) && (f === 'north' || f === 'south')) {
        if (x === 0 || x === w - 1) l -= 0.9;                     // selective outline along the long edges
        else if (x === 1) l += 0.6;
        if (role === 'tongue') {                                  // punched holes, and a rounded tip
          const cy = h - 1 - y, cx = Math.min(x, w - 1 - x);
          if (cx + cy < 2) return null;
          const hy = Math.round((p[1] + 6) * S);
          if ((hy === 3 || hy === 4 || hy === 7 || hy === 8) && (x === 2 || x === 3)) return hy === 3 || hy === 7 ? STRAP[0] : STRAP[1];
        }
        if (role === 'shoulder' && (x === 2 || x === w - 3) && y % 3 !== 2) return THREAD;
      }
      return role === 'shoulder' ? pick(LEATHER, l + 0.6) : pick(STRAP, l);
    }
    case 'buckle': case 'slider': {
      if (f === 'north' || f === 'south') {
        const fr = 2, inside = x >= fr && x < w - fr && y >= fr && y < h - fr;
        if (inside) {
          if (role !== 'buckle') return null;
          const mid = Math.floor(w / 2) - 1;
          if (x === mid || x === mid + 1) return x === mid ? BRASS[4] : BRASS[2];  // the prong, lying down the strap
          return y === fr ? STRAP[1] : pick(STRAP, 3);                              // the strap passing through
        }
        let l = 2.3 + (y < h / 2 ? 0.8 : -0.5) + (x < w / 2 ? 0.3 : -0.3);
        if (x === 0 || y === 0) l += 0.9;                          // bevel catching the light
        if (x === w - 1 || y === h - 1) l -= 1.2;
        if (x === fr - 1 || y === fr - 1) l -= 0.9;                // inner edge in shadow
        return pick(BRASS, l);
      }
      return pick(BRASS, 2.2 + 2.5 * (lit(FN[f]) - 0.65));
    }
    case 'stud': {
      if (f === 'north') {
        const d = Math.hypot(x + 0.5 - w / 2, y + 0.5 - h / 2);
        if (d > w / 2) return null;
        return (x <= 1 && y <= 1) ? BRASS[4] : d > w / 2 - 1 ? BRASS[1] : BRASS[3];
      }
      return BRASS[1];
    }
    case 'pocket': {
      let l = leather(c, f, p, x, y, w, h, 2.6, 0.8);
      if (f === 'north') { if (stitch(x, y, w, h, 2, 'blr') === 1) return THREAD; if (p[1] > -7.25) l -= 1.1; }
      return pick(LEATHER, l);
    }
    case 'pflap': {
      let l = leather(c, f, p, x, y, w, h, 3.0, 0.4);
      if (f === 'north') {
        const cx = Math.min(x, w - 1 - x), cy = h - 1 - y;
        if (cx + cy < 3) return null;                              // rounded corners
        if (cx + cy === 3) l -= 1;
        if (stitch(x, y, w, h, 2, 'b') === 1) return THREAD;
        if (cy >= 2 && (cx === 2) && y % 3 !== 2) return THREAD;
      }
      return pick(LEATHER, l);
    }
    case 'roll': case 'tie': {
      const ry = p[1] - YC, rz = p[2] - ZC, r = Math.hypot(ry, rz), end = f === 'east' || f === 'west';
      if (end) {
        if (r > RR + (role === 'tie' ? 0.125 : 0)) return null;
        if (role === 'tie') return r > RR ? pick(STRAP, 1.5) : null;
        // the rolled end: a spiral of blanket layers, darker toward the core
        const a = Math.atan2(ry, rz), turn = 0.3, s = ((r - (turn * (a + Math.PI)) / (2 * Math.PI)) % turn + turn) % turn;
        if (r < 0.2) return WOOL[0];
        let l = 3.2 - 1.6 * (1 - r / RR) + 0.8 * (ry > 0 ? 0.6 : -0.4);
        if (s < 0.09) l -= 1.6;                                     // the gap between layers
        else if (s > turn - 0.09) l += 0.7;                         // each layer's lit lip
        const ramp = Math.abs(p[0]) > 0 && r > RR - 0.2 ? CREAM : WOOL;
        return ramp === CREAM ? pick(CREAM, l - 1.4) : pick(WOOL, l);
      }
      const n = norm([0, ry, rz]);
      let l = (role === 'tie' ? 2.4 : 3.1) + 3.2 * (lit(n) - 0.65);
      if (role === 'tie') { if (Math.abs(p[0] - (p[0] > 0 ? 2.375 : -2.375)) > 0.17) l -= 0.7; return pick(STRAP, l); }
      // the blanket's outer edge runs along the roll, low on the back side
      const ang = Math.atan2(ry, rz);
      if (Math.abs(ang + 2.2) < 0.12) l -= 1.5; else if (ang > -2.08 && ang < -1.85) l += 0.6;
      // shadow from the bag above and the ties beside
      if (ry > 0.9 && Math.abs(rz) < 1.1) l -= 0.9;
      const ax = Math.abs(p[0]);
      if (Math.abs(ax - 2.375) < 0.4) l -= 0.6;
      l += 0.3 * (fbm(p[0] * 3, ang * 3, 11, 2) - 0.5) * 2;     // a soft wool nap
      if (ax > 3.125 && ax < 3.625) return pick(CREAM, l - 1.2); // the blanket's border stripe
      return pick(WOOL, l);
    }
  }
  return '#ff00ff';
}

let tex = null;
function paint() {
  if (tex) return tex;
  tex = newTexture({ texSize: TEX, scale: S });
  for (const c of cubes) paintCube(tex, c, S, (v, f) => {
    for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) { const col = shade(c, f, x, y, v.w, v.h); if (col) v.set(x, y, col); }
  });
  return tex;
}

const strip = ({ _role, _o, ...c }) => c;
export default {
  meta: { id: 'pack-explorer', name: 'Explorer Pack', kind: 'pack', cents: 849, capeOffset: 4,
    blurb: 'A stitched leather rucksack with brass buckles and a wool bedroll strapped underneath.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'pack', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: cubes.map(strip) }] },
  paint() { return paint(); },
};
