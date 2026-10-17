// Pixel Crown (try 2): a small gold circlet whose band sinks over the top of the head, with tapered
// points that each end in a ball finial, an engraved scroll + bead-row band and cut gems front,
// back and sides. Highest point is 10.75 model px (head top is 8): within the 3 px clearance.
// Static; scale 8 (8 texture px per model px). Geometry is thin slices stacked into tapers, painted
// from each texel's WORLD position so every slice shades as part of one continuous form.
import { ramp, clamp } from '../../lib/px.mjs';
import { cube, layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, TEX = [64, 32];
const px = 1 / S;

// 5-step gold: cool olive-brown shadow -> deep amber -> gold -> bright yellow -> near-white warm.
const GOLD = ['#3c3719', '#7c6120', '#cf9a1a', '#f6cd45', '#fff7d2'];
const ruby = ramp('#e0264c', 7, { lo: 0.16, hi: 0.9, shadowHue: 335, lightHue: 18, shift: 0.55 });
const ice = ramp('#4fc3e6', 7, { lo: 0.18, hi: 0.93, shadowHue: 232, lightHue: 175, shift: 0.55 });
const g = s => GOLD[clamp(Math.round(s), 0, 4)];

// Light from the top, front (+z) and the player's left (+x).
const LV = (() => { const v = [0.42, 0.72, 0.55], l = Math.hypot(...v); return v.map(a => a / l); })();
const HV = (() => { const v = [LV[0], LV[1], LV[2] + 1], l = Math.hypot(...v); return v.map(a => a / l); })();
const FACE_BIAS = { up: 1.6, south: 0.35, east: 0.0, north: -0.3, west: -0.6, down: -1.4 };

// ---- geometry (head frame: head cube is x -4..4, y 0..8, z -4..4) ----
const BAND_Y0 = 6.25, BAND_Y1 = 8.75, OUT = 4.75, T = 0.75;       // band sinks 1.75 px over the head top
const m = OUT - T / 2;                                             // wall centre line
const parts = [];
const add = (p, from, to) => parts.push({ ...p, ...cube(from, to) });

// walls: long = the axis the wall runs along; face = the outward face
const WALLS = {
  front: { long: 'x', face: 'south', from: [-OUT, BAND_Y0, 4], to: [OUT, BAND_Y1, OUT] },
  back: { long: 'x', face: 'north', from: [-OUT, BAND_Y0, -OUT], to: [OUT, BAND_Y1, -4] },
  left: { long: 'z', face: 'east', from: [4, BAND_Y0, -4], to: [OUT, BAND_Y1, 4] },
  right: { long: 'z', face: 'west', from: [-OUT, BAND_Y0, -4], to: [-4, BAND_Y1, 4] },
};
// gems per wall: lateral world coordinate, size in model px, ramp
const GEMS = {
  front: [[0, 1.25, ruby], [2.75, 1, ice], [-2.75, 1, ice]],
  back: [[0, 1.25, ruby], [2.75, 1, ice], [-2.75, 1, ice]],
  left: [],
  right: [],
};
for (const [name, w] of Object.entries(WALLS)) add({ name, role: 'wall', wall: name, ...w }, w.from, w.to);

const GEM_Y = 7.625, GEM_T = 0.375;
for (const [name, list] of Object.entries(GEMS)) {
  const w = WALLS[name];
  list.forEach(([c, s, rp], i) => {
    const h = s / 2, y0 = GEM_Y - h, y1 = GEM_Y + h;
    const box = { front: [[c - h, y0, OUT], [c + h, y1, OUT + GEM_T]], back: [[c - h, y0, -OUT - GEM_T], [c + h, y1, -OUT]],
      left: [[OUT, y0, c - h], [OUT + GEM_T, y1, c + h]], right: [[-OUT - GEM_T, y0, c - h], [-OUT, y1, c + h]] }[name];
    add({ name: `gem-${name}-${i}`, role: 'gem', gem: { face: w.face, size: Math.round(s * S), ramp: rp }, emissive: true }, ...box);
  });
}

// A ball (finial or pearl): three slices (narrow, full, narrow) shaded as ONE sphere.
function ball(tag, cx, cz, y0, D) {
  const mid = Math.max(0.25, Math.round((D * 0.4) / 0.125) * 0.125), edge = D - 0.25;
  const b = { cx, cz, cy: y0 + (0.25 + mid) / 2, r: D / 2 };
  let y = y0;
  for (const [h, w] of [[0.125, edge], [mid, D], [0.125, edge]]) {
    add({ name: `${tag}-b${parts.length}`, role: 'ball', ball: b }, [cx - w / 2, y, cz - w / 2], [cx + w / 2, y + h, cz + w / 2]);
    y += h;
  }
  return y;
}
// A tapered prong: 1/8 px slices narrowing from the base width to 1 px, then a ball finial. (cx, cz) is
// the prong centre; `facing` is the wall's outward face (decides which axis is the wide one).
function prong(tag, cx, cz, facing, { widths, ballD, square = false }) {
  const wide = facing === 'south' || facing === 'north' ? 'x' : 'z';
  let y = BAND_Y1 - 0.125;                                            // base tucks into the band
  for (const w of widths) {
    const d = square ? w : (w >= 1.5 ? 0.75 : w >= 1.125 ? 0.625 : 0.5);
    const dx = square || wide === 'x' ? w : d, dz = square || wide === 'z' ? w : d;
    add({ name: `${tag}-${parts.length}`, role: 'prong', prong: { cx, cz, wide: square ? 'both' : wide } }, [cx - dx / 2, y, cz - dz / 2], [cx + dx / 2, y + 0.125, cz + dz / 2]);
    y += 0.125;
  }
  ball(tag, cx, cz, y, ballD);
}
const TALL = { widths: [2, 2, 2, 1.875, 1.75, 1.625, 1.5, 1.375, 1.25, 1.125, 1, 1], ballD: 1 };   // 8.625 -> 10.125, ball to ~10.75
const CORNER = { widths: [1.75, 1.75, 1.625, 1.5, 1.375, 1.25, 1.125, 1, 1], ballD: 0.875, square: true };
for (const [i, [x, z, f]] of [[0, m, 'south'], [0, -m, 'north'], [m, 0, 'east'], [-m, 0, 'west']].entries()) prong(`t${i}`, x, z, f, TALL);
const cn = 4.375;
[[cn, cn], [-cn, cn], [cn, -cn], [-cn, -cn]].forEach(([x, z], i) => prong(`c${i}`, x, z, 'south', CORNER));
// pearls on the band top between the points
const fl = 2.4;
[[fl, m], [-fl, m], [fl, -m], [-fl, -m], [m, fl], [m, -fl], [-m, fl], [-m, -fl]].forEach(([x, z], i) => ball(`p${i}`, x, z, BAND_Y1 - 0.125, 0.75));

const cubes = parts.map(p => { const { name, ...rest } = p; const c = { from: rest.from, to: rest.to, uv: [0, 0] }; if (rest.emissive) c.emissive = true; return Object.assign(c, { _p: p }); });
layoutCubes(cubes, TEX, S);
const modelCubes = cubes.map(({ _p, ...c }) => c);

// ---- world position of a texel on a cube face (centre of the texel, on the face surface) ----
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
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

// ---- tapered prong slice ----
function paintProng(v, f, c, p) {
  const { cx, cz, wide } = p;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [wx, , wz] = world(c, f, x, y);
    const faceOn = wide === 'both' || (wide === 'x') === (f === 'south' || f === 'north' || f === 'up' || f === 'down');
    const lat = (f === 'south' || f === 'north' || f === 'up' || f === 'down') ? wx - cx : wz - cz;
    const half = (f === 'south' || f === 'north' || f === 'up' || f === 'down') ? (c.to[0] - c.from[0]) / 2 : (c.to[2] - c.from[2]) / 2;
    const e = half - Math.abs(lat);                                     // distance to the nearest silhouette edge
    let s = 2.2 + 1.5 * (lat / half) * (faceOn ? 1 : 0.4) + FACE_BIAS[f] * 0.55;
    if (f === 'up') s = e < px * 0.9 ? 2.6 : 3.4;
    else {
      if (e < px) s = lat >= 0 ? 4 : 0.2;                               // lit edge glints, shadow edge is the outline
      else if (e < px * 2) s += lat >= 0 ? 0.7 : -0.5;
      if (y === 0) s += 0.9;                                            // each step's top lip catches the light
      if (y === v.h - 1) s -= 0.8;
    }
    v.set(x, y, g(s));
  }
}

// ---- ball finial: one sphere shared by three slices ----
function paintBall(v, f, c, p) {
  const b = p.ball;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const w = world(c, f, x, y);
    const d = [w[0] - b.cx, w[1] - b.cy, w[2] - b.cz], len = Math.hypot(...d) || 1, n = d.map(a => a / len);
    const lit = dot(n, LV), sp = dot(n, HV);
    let s = 1.7 + 2.0 * lit;
    if (sp > 0.93) s = 4; else if (lit > 0.6) s = Math.max(s, 3);
    if (lit < -0.25 || len > b.r * 1.28) s = Math.min(s, 0.6);          // dark limb
    v.set(x, y, g(s));
  }
}

// ---- band wall ----
// Engraved running scroll: a spiral per cell, mirrored every other cell, a drilled dot between cells.
const CELL = 11, RMAX = 4.4;
function scrollCut(x, y) {
  if (y < 4 || y > 13) return false;
  const k = Math.floor(x / CELL), lx = x + 0.5 - (k * CELL + CELL / 2), ly = y + 0.5 - 9.0;
  const r = Math.hypot(lx, ly);
  if (r <= RMAX) {
    const th = Math.atan2(ly, k % 2 ? -lx : lx);                           // mirrored cells wind the other way
    const t = (th + Math.PI) / (2 * Math.PI);
    for (const j of [0, 1, 2]) if (Math.abs(r - (0.9 + 2.3 * (t + j))) < 0.55) return true;
  }
  // drilled dot in the gap between this cell and the next
  const bx = Math.round((x + 0.5) / CELL) * CELL;
  return Math.hypot(x + 0.5 - bx, ly) < 0.95;
}
function paintWall(v, f, c, p) {
  const outer = f === p.face;
  const bias = FACE_BIAS[f] ?? 0;
  if (f === 'up' || f === 'down') { v.paint((x, y) => g(f === 'up' ? (y === 0 || y === v.h - 1 || x === 0 || x === v.w - 1 ? 3 : 3.8) : 0.8)); return; }
  const long = p.long, onLong = (long === 'x') === (f === 'south' || f === 'north');
  // plain shading for the inner face and the thin ends
  const base = (y) => {
    const t = (y + 0.5) / v.h;
    let s = 2.0 + bias * 0.5 + (t < 0.25 ? 1.0 : 0) - (t > 0.8 ? 1.0 : 0);
    if (y === 0) s = 4; if (y === v.h - 1) s = 0.4;
    return g(s);
  };
  v.paint((x, y) => base(y));
  if (!onLong || !outer) return;

  // gem centres in image x
  const [lo, hi] = long === 'x' ? [c.from[0], c.to[0]] : [c.from[2], c.to[2]];
  const toImg = u => (f === 'south' || f === 'west') ? (u - lo) * S : (hi - u) * S;
  const gems = GEMS[p.wall].map(([u, s]) => ({ x: toImg(u), r: s * S / 2 }));
  const off = clamp(bias, -0.6, 0.35) * 0.8;                            // face tone offset, keeps the lit/shaded sides apart

  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (y === 0) s = 4.2;                                               // top-edge specular line
    else if (y === 1) s = 3.4 + off;
    else if (y === 2) s = 2.6 + off;
    else if (y === 3) s = 0.5;                                          // groove under the top moulding
    else if (y === 14) s = 0.5;                                         // groove above the bead row
    else if (y >= 4 && y <= 13) {
      s = 2.15 + off;                                                   // engraved field
      if (scrollCut(x, y)) s = 0.45;                                    // groove
      else if (scrollCut(x, y - 1) || (scrollCut(x - 1, y) && y % 2 === 0)) s = 3.3 + off * 0.5;   // lit lip below/left of the groove
    } else if (y >= 15 && y <= 18) {                                    // bead row
      const bx = ((x % 5) + 5) % 5, by = y - 15;
      if (bx === 4) s = 0.4;
      else {
        const dd = Math.hypot(bx - 1.5, by - 1.5);
        if (dd > 2.1) s = 0.4;
        else s = [[3.6, 3.2, 2.2, 1.6], [3.2, 2.7, 2.1, 1.3], [2.5, 2.1, 1.6, 1.0], [1.6, 1.4, 1.0, 0.6]][by][bx] + off * 0.6;
      }
    } else s = 0.3;                                                    // y 19: bottom edge shadow
    v.set(x, y, g(s));
  }
  // gem seats: dark socket, bright bezel ring
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    for (const gm of gems) {
      const dx = Math.abs(x + 0.5 - gm.x), dy = Math.abs(y + 0.5 - 9.0);
      const n = Math.max(dx, dy, (dx + dy) * 0.78) - gm.r;
      if (n > 2.2 || n < -0.5) continue;
      v.set(x, y, g(n < 0.9 ? 0.4 : n < 1.7 ? 3.7 : 1.4));
    }
  }
}

// ---- gems: cut stone with 3 tones, a highlight pixel and a dark rim ----
function paintGem(v, f, c, p) {
  const { face, size, ramp: r } = p.gem;
  if (f !== face) { v.paint(() => r[1]); return; }
  const n = size, cut = n >= 10 ? 2 : 1, sx = (f === 'south' || f === 'west') ? -1 : 1;
  const inside = (x, y) => x >= 0 && y >= 0 && x < n && y < n && Math.min(x, n - 1 - x) + Math.min(y, n - 1 - y) >= cut;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    if (!inside(x, y)) continue;
    const u = ((x + 0.5 - n / 2) / (n / 2)) * sx, w = (y + 0.5 - n / 2) / (n / 2);
    const rim = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1);
    let col;
    if (rim) col = r[0];
    else {
      const ring = Math.min(x, n - 1 - x, y, n - 1 - y);               // 1 = first texel inside the rim
      if (ring <= (n >= 10 ? 2 : 1)) {                                  // crown facets
        const top = w < -Math.abs(u), left = u < -Math.abs(w), right = u > Math.abs(w);
        col = top ? r[5] : left ? r[4] : right ? r[2] : r[1];
      } else col = r[3];                                                // table
    }
    // highlight glint, upper-left of the table
    const hx = Math.round(n * 0.3), hy = Math.round(n * 0.3);
    const gx = sx < 0 ? n - 1 - hx : hx;
    if ((x === gx && y === hy) || (n >= 10 && x === (sx < 0 ? gx - 1 : gx + 1) && y === hy) || (n >= 10 && x === gx && y === hy + 1)) col = r[6];
    v.set(x, y, col);
  }
}

export default {
  meta: { id: 'crown-pixel', name: 'Pixel Crown', kind: 'crown', cents: 699, blurb: 'A small gold crown with pearl-tipped points, an engraved scroll band and cut gems. Low enough to see over.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'crown', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: modelCubes }] },
  paint() {
    const tex = newTexture({ texSize: TEX, scale: S });
    for (const c of cubes) paintCube(tex, c, S, (v, f) => {
      const p = c._p;
      if (p.role === 'wall') paintWall(v, f, c, p);
      else if (p.role === 'prong') paintProng(v, f, c, p.prong);
      else if (p.role === 'ball') paintBall(v, f, c, p);
      else paintGem(v, f, c, p);
    });
    return tex;
  },
};
