// Pixel Crown: a small gold circlet (rim at the head's top edge, stepped points reaching 3 model px
// above the head at most) with a checker band and three glowing gems. Rebuilt small on purpose:
// Will could not see over the round-1 crown. Static; scale 8 (8 texture px per model px).
import { ramp, clamp, smoothstep } from '../../lib/px.mjs';
import { cube, layoutCubes, newTexture, paintCube, LIGHT } from '../../lib/model.mjs';

const S = 8;
const gold = ramp('#e8a40e', 8, { lo: 0.09, hi: 0.78, shadowHue: 6, lightHue: 48, shift: 0.55, tint: 0.5, satHi: 1.12, satLo: 1.0 });
const ruby = ramp('#e0264c', 7, { lo: 0.2, hi: 0.86, shadowHue: 335, lightHue: 18, shift: 0.55 });
const ice = ramp('#4fc3e6', 7, { lo: 0.22, hi: 0.9, shadowHue: 232, lightHue: 175, shift: 0.55 });

// ---- geometry (head frame: head cube is x -4..4, y 0..8, z -4..4) ----
const BAND_Y0 = 7.5, BAND_Y1 = 9.5, OUT = 4.75, T = 0.75;           // band 2 px tall, 0.75 px thick, 0.75 px outside the head
const parts = [
  { name: 'front', role: 'wall', long: 'z', ...cube([-OUT, BAND_Y0, 4], [OUT, BAND_Y1, OUT]) },
  { name: 'back', role: 'wall', long: 'z', ...cube([-OUT, BAND_Y0, -OUT], [OUT, BAND_Y1, -4]) },
  { name: 'left', role: 'wall', long: 'x', ...cube([4, BAND_Y0, -4], [OUT, BAND_Y1, 4]) },
  { name: 'right', role: 'wall', long: 'x', ...cube([-OUT, BAND_Y0, -4], [-4, BAND_Y1, 4]) },
];
// stepped points: a wide step then a narrow tip (cardinal), one wide step (corners)
const point = (name, x, z, w, y0, y1, d = T) => ({ name, role: 'point', ...cube([x - w / 2, y0, z - d / 2], [x + w / 2, y1, z + d / 2]) });
const m = OUT - T / 2;                                                // wall centre line
parts.push(
  point('pf1', 0, m, 1.5, 9.5, 10.25), point('pf2', 0, m, 0.75, 10.25, 11),
  point('pb1', 0, -m, 1.5, 9.5, 10.25), point('pb2', 0, -m, 0.75, 10.25, 11),
  { name: 'pl1', role: 'point', ...cube([4, 9.5, -0.75], [OUT, 10.25, 0.75]) }, { name: 'pl2', role: 'point', ...cube([4, 10.25, -0.375], [OUT, 11, 0.375]) },
  { name: 'pr1', role: 'point', ...cube([-OUT, 9.5, -0.75], [-4, 10.25, 0.75]) }, { name: 'pr2', role: 'point', ...cube([-OUT, 10.25, -0.375], [-4, 11, 0.375]) },
  // low corner studs
  point('c1', 3.875, m, 0.75, 9.5, 10.25), point('c2', -3.875, m, 0.75, 9.5, 10.25),
  point('c3', 3.875, -m, 0.75, 9.5, 10.25), point('c4', -3.875, -m, 0.75, 9.5, 10.25),
  // gems on the front wall: centre ruby, two ice stones
  { name: 'gemC', role: 'gem', ramp: ruby, gx: 0, ...cube([-0.5, 8, OUT], [0.5, 9, OUT + 0.5]) },
  { name: 'gemL', role: 'gem', ramp: ice, gx: 2.75, ...cube([2.375, 8.125, OUT], [3.125, 8.875, OUT + 0.375]) },
  { name: 'gemR', role: 'gem', ramp: ice, gx: -2.75, ...cube([-3.125, 8.125, OUT], [-2.375, 8.875, OUT + 0.375]) },
);
const cubes = parts.map(p => { const { name, role, long, ramp: r, gx, ...c } = p; return Object.assign(c, role === 'gem' ? { emissive: true } : {}, { _p: { name, role, long, ramp: r, gx } }); });
layoutCubes(cubes, [48, 24], S);
const modelCubes = cubes.map(({ _p, ...c }) => c);

// ---- painting ----
const g = n => gold[clamp(Math.round(n), 0, gold.length - 1)];
const gauss = x => Math.exp(-x * x);

// How bright a rounded metal band is at normalised height yn (0 top .. 1 bottom), for a face lit L.
function tube(yn, L) {
  const t = 0.3 + 0.62 * L + 0.17 * gauss((yn - 0.2) / 0.14) + 0.1 * gauss((yn - 0.9) / 0.08) - 0.18 * smoothstep(0.55, 1, yn);
  return t * (gold.length - 1);
}

function paintWall(v, f, p, cubeDef) {
  const L = LIGHT[f];
  if (f === 'up' || f === 'down') { v.paint((x, y) => g(f === 'up' ? (y === 0 || y === v.h - 1 ? 4 : 6.4) : 1.4)); return; }
  const longFace = p.long === 'z' ? (f === 'north' || f === 'south') : (f === 'east' || f === 'west');
  v.paint((x, y) => g(tube((y + 0.5) / v.h, L)));
  if (!longFace) return;
  const gems = p.name === 'front' && f === 'south' ? [[0, 4], [2.75, 3], [-2.75, 3]] : [];
  const cx = v.w / 2, band = (y) => y >= 4 && y <= 11;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    // groove lines either side of the checker band, with a lit lip beneath the top one
    if (y === 3 || y === 12) { v.set(x, y, g(y === 3 ? 1.2 : 1.6)); continue; }
    if (y === 4) { v.set(x, y, g(5.6)); }
    let bez = 99;
    for (const [gx, hs] of gems) {
      const dx = Math.abs(x + 0.5 - (cx + gx * S)), dy = Math.abs(y + 0.5 - 8), n = Math.max(dx, dy, (dx + dy) * 0.78);
      bez = Math.min(bez, n - hs);
    }
    if (bez <= 0) continue;                                          // under the gem cube
    if (bez < 3.2) { v.set(x, y, g(bez < 1.2 ? 2.4 : bez < 2.2 ? 5.4 : 3.6)); continue; }   // dark seat, bright rim
    if (y >= 5 && y <= 10) {                                         // inlaid square studs, one model px wide
      const lx = ((x % 12) + 12) % 12 - 3, ly = y - 5;
      if (lx >= 0 && lx < 6) { v.set(x, y, g(ly === 0 ? 1.2 : ly === 5 ? 6.2 : lx === 0 ? 1.8 : 2.6)); continue; }
    }
  }
}

function paintPoint(v, f) {
  const L = LIGHT[f];
  if (f === 'up') { v.paint(() => g(6.9)); return; }
  if (f === 'down') { v.paint(() => g(1)); return; }
  v.paint((x, y) => {
    let t = 0.38 + 0.6 * L + 0.2 * (1 - (y + 0.5) / v.h) * 0.8 - 0.16 * smoothstep(0.5, 1, (y + 0.5) / v.h);
    if (y === 0) t += 1.2 / (gold.length - 1);
    if (x === 0 && f !== 'north') t += 0.5 / (gold.length - 1);
    if (x === v.w - 1) t -= 0.8 / (gold.length - 1);
    return g(t * (gold.length - 1));
  });
}

function paintGem(v, f, p) {
  const r = p.ramp, n = r.length - 1, px = i => r[clamp(Math.round(i), 0, n)];
  const L = LIGHT[f];
  if (f !== 'south') { v.paint(() => px((0.45 + 0.5 * L) * n)); return; }
  const hs = v.w / 2;
  v.paint((x, y) => {
    const u = (x + 0.5 - hs) / hs, w = (y + 0.5 - hs) / hs, c = Math.max(Math.abs(u), Math.abs(w));
    if (c > 0.62) {                                                   // bevel facets: which edge is nearer
      const e = Math.abs(u) > Math.abs(w) ? (u < 0 ? 'l' : 'r') : (w < 0 ? 't' : 'b');
      return px({ t: 0.9, l: 0.72, r: 0.38, b: 0.2 }[e] * n);
    }
    const spec = (u + w < -0.45 && u + w > -0.95) ? 1 : 0;            // a bright glint on the table's upper-left
    return px(spec ? n : (0.78 - 0.18 * (u + w)) * n * 0.9);
  });
}

export default {
  meta: { id: 'crown-pixel', name: 'Pixel Crown', kind: 'crown', cents: 699, blurb: 'A small gold circlet with a checker band and three glowing gems. Low enough to see over.' },
  model: { texSize: [48, 24], scale: S, bones: [{ name: 'crown', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: modelCubes }] },
  paint() {
    const tex = newTexture({ texSize: [48, 24], scale: S });
    for (const c of cubes) paintCube(tex, c, S, (v, f) => {
      if (c._p.role === 'wall') paintWall(v, f, c._p, c);
      else if (c._p.role === 'point') paintPoint(v, f);
      else paintGem(v, f, c._p);
    });
    return tex;
  },
};
