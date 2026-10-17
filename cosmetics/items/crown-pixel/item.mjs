// Pixel Crown (try 3): a small gold circlet. The band sinks 2 px over the top of the head; four flat
// spikes (one centred on each face, 2 px at the base to 1 px at the tip, one 1 px ball finial) rise from
// it, with small pearls between them. The band carries an engraved spiral scroll, an even bead row and
// cut gems front and back. Highest point 11 model px (head top is 8): the 3 px clearance limit.
// Static; scale 8. Spikes are single two-faced planes cut out with transparent texels; everything else
// is a box painted from each texel's WORLD position.
import { ramp, clamp } from '../../lib/px.mjs';
import { cube, layoutCubes, footprint, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const TEX = [64, 32];

// 5-step gold: cool olive-brown shadows -> warm gold -> pale warm highlights (near-white only for specular).
const GOLD = ['#2f2e18', '#766528', '#d6a935', '#f5d676', '#fffadb'];
const ruby = ramp('#e0264c', 7, { lo: 0.16, hi: 0.9, shadowHue: 335, lightHue: 18, shift: 0.55 });
const ice = ramp('#4fc3e6', 7, { lo: 0.18, hi: 0.93, shadowHue: 232, lightHue: 175, shift: 0.55 });
const g = s => GOLD[clamp(Math.round(s), 0, 4)];

// Light from the top, front (+z) and the player's left (+x).
const LV = (() => { const v = [0.42, 0.72, 0.55], l = Math.hypot(...v); return v.map(a => a / l); })();
const HV = (() => { const v = [LV[0], LV[1], LV[2] + 1], l = Math.hypot(...v); return v.map(a => a / l); })();
const FACE_BIAS = { up: 1.6, south: 0.35, east: 0.0, north: -0.3, west: -0.6, down: -1.4 };
// On which side of a face IMAGE the lit (+x / front) edge falls: +1 right, -1 left.
const LIT_SIDE = { south: 1, west: 1, north: -1, east: -1, up: -1, down: -1 };

// ---- geometry (head frame: head cube is x -4..4, y 0..8, z -4..4) ----
const BAND_Y0 = 6.0, BAND_Y1 = 8.5, OUT = 4.75, T = 0.75;          // band sinks 2 px over the head top
const m = OUT - T / 2;                                             // wall centre line
const boxes = [];
const add = (p, from, to) => boxes.push({ ...cube(from, to), ...(p.emissive ? { emissive: true } : {}), _p: p });

const WALLS = {
  front: { long: 'x', face: 'south', from: [-OUT, BAND_Y0, 4], to: [OUT, BAND_Y1, OUT] },
  back: { long: 'x', face: 'north', from: [-OUT, BAND_Y0, -OUT], to: [OUT, BAND_Y1, -4] },
  left: { long: 'z', face: 'east', from: [4, BAND_Y0, -4], to: [OUT, BAND_Y1, 4] },
  right: { long: 'z', face: 'west', from: [-OUT, BAND_Y0, -4], to: [-4, BAND_Y1, 4] },
};
// Gems sit on the 10-texel grid shared by the scroll and the beads (centre + two flanks). Front and back only.
const GEMS = {
  front: [[0, 1.0, ruby], [2.5, 0.75, ice], [-2.5, 0.75, ice]],
  back: [[0, 1.0, ruby], [2.5, 0.75, ice], [-2.5, 0.75, ice]],
  left: [], right: [],
};
for (const [name, w] of Object.entries(WALLS)) add({ role: 'wall', wall: name, ...w }, w.from, w.to);

const GEM_Y = 7.625, GEM_T = 0.375;
for (const [name, list] of Object.entries(GEMS)) {
  const w = WALLS[name];
  list.forEach(([c, s, rp]) => {
    const h = s / 2, y0 = GEM_Y - h, y1 = GEM_Y + h;
    const box = { front: [[c - h, y0, OUT], [c + h, y1, OUT + GEM_T]], back: [[c - h, y0, -OUT - GEM_T], [c + h, y1, -OUT]] }[name];
    if (box) add({ role: 'gem', gem: { face: w.face, size: Math.round(s * S), ramp: rp }, emissive: true }, ...box);
  });
}

// A pearl: three slices (narrow, full, narrow) shaded as ONE sphere.
function pearl(cx, cz, y0, D) {
  const mid = Math.max(0.25, Math.round((D * 0.4) / 0.125) * 0.125), edge = D - 0.25;
  const b = { cx, cz, cy: y0 + (0.25 + mid) / 2, r: D / 2 };
  let y = y0;
  for (const [h, w] of [[0.125, edge], [mid, D], [0.125, edge]]) {
    add({ role: 'ball', ball: b }, [cx - w / 2, y, cz - w / 2], [cx + w / 2, y + h, cz + w / 2]);
    y += h;
  }
}
const PEARL_Y = BAND_Y1 - 0.125;
for (const [x, z] of [[4.375, 4.375], [-4.375, 4.375], [4.375, -4.375], [-4.375, -4.375]]) pearl(x, z, PEARL_Y, 0.875);   // corners
for (const k of [2.4, -2.4]) for (const [x, z] of [[k, m], [k, -m], [m, k], [-m, k]]) pearl(x, z, PEARL_Y, 0.625);        // between the points
layoutCubes(boxes, TEX, S);

// Spikes: one flat two-faced plane per wall, centred. 2 px wide, 2.75 px tall (its foot hides inside the band).
const SPIKE_W = 2, SPIKE_H = 2.75, SPIKE_Y0 = 11 - SPIKE_H, SW = SPIKE_W * S, SH = SPIKE_H * S;
const usedBottom = Math.max(...boxes.map(c => { const f = footprint(c, S); return f.y + f.h; }));
const spikeV0 = Math.ceil(usedBottom / S);
TEX[1] = Math.max(TEX[1], Math.ceil(spikeV0 + SPIKE_H));
const spikes = ['south', 'north', 'east', 'west'].map((face, i) => {
  const wall = { south: [0, m], north: [0, -m], east: [m, 0], west: [-m, 0] }[face], [cx, cz] = wall, t = 0.0625;
  const other = { south: 'north', north: 'south', east: 'west', west: 'east' }[face];
  const rect = k => { const u = (i * 2 + k) * SPIKE_W; return [u, spikeV0, u + SPIKE_W, spikeV0 + SPIKE_H]; };
  const ns = face === 'south' || face === 'north';
  return {
    from: ns ? [cx - SPIKE_W / 2, SPIKE_Y0, cz - t] : [cx - t, SPIKE_Y0, cz - SPIKE_W / 2],
    to: ns ? [cx + SPIKE_W / 2, 11, cz + t] : [cx + t, 11, cz + SPIKE_W / 2],
    uv: [0, 0], faces: { [face]: rect(0), [other]: rect(1) }, _p: { role: 'spike' },
  };
});
const cubes = [...boxes, ...spikes];
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

// ---- spike: a cut-out silhouette (ball finial, neck, concave taper) with a dark outline ----
function sphereShade(dx, dy, r, sgn) {
  const nl = (dx / r) * sgn, ny = -dy / r, nz = Math.sqrt(Math.max(0, 1 - nl * nl - ny * ny));
  const lit = 0.42 * nl + 0.72 * ny + 0.55 * nz;
  if (lit > 0.94) return 4;                                            // the single specular texel
  return lit < -0.1 ? 0.8 : 1.4 + 2.0 * lit;
}
function spikeInside(x, y) {
  if (x < 0 || x >= SW || y < 0 || y >= SH) return false;
  const dx = x + 0.5 - SW / 2;
  if (y < 8) return Math.hypot(dx, y + 0.5 - 4) <= 4;                  // 1 px ball finial
  if (y === 8) return Math.abs(dx) <= 2;                               // neck
  return Math.abs(dx) <= 4 + 4 * Math.pow((y - 9) / (SH - 10), 1.5);   // 1 px at the tip -> 2 px at the base
}
function paintSpike(v, f) {
  const sgn = LIT_SIDE[f];
  for (let y = 0; y < SH; y++) for (let x = 0; x < SW; x++) {
    if (!spikeInside(x, y)) continue;
    const edge = !spikeInside(x - 1, y) || !spikeInside(x + 1, y) || !spikeInside(x, y - 1) || !spikeInside(x, y + 1);
    if (edge) { v.set(x, y, g(0.3)); continue; }
    const dx = x + 0.5 - SW / 2;
    if (y < 8) { v.set(x, y, g(sphereShade(dx, y + 0.5 - 4, 4, sgn))); continue; }
    const hw = 4 + 4 * Math.pow(Math.max(0, y - 9) / (SH - 10), 1.5), tl = (dx / hw) * sgn;
    let s = 2.45 + 0.95 * tl - 0.25 * (y - 9) / (SH - 10);
    if (!spikeInside(x + sgn, y) || !spikeInside(x + 2 * sgn, y) && false) s = 3.4;       // lit-side rim light
    else if (!spikeInside(x - sgn * 2, y)) s = 1.1;                     // shadow-side core shadow
    v.set(x, y, g(s));
  }
}

// ---- pearl: one sphere shared by three slices ----
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
// One 10-texel grid along the wall: scroll cells, gems and beads all sit on it, centred, so every
// pattern ends cleanly (an equal plain margin) at both corners.
const PITCH = 10, FIELD_C = 7.0, RMAX = 3.9;
const slots = w => { const n = Math.round((w - 6) / PITCH); return Array.from({ length: n }, (_, j) => w / 2 + (j - (n - 1) / 2) * PITCH); };
function scrollCut(x, y, cells) {
  if (y < 3 || y > 10) return false;
  for (const [cx, mirror] of cells) {
    const lx = x + 0.5 - cx, ly = y + 0.5 - FIELD_C, r = Math.hypot(lx, ly);
    if (r > RMAX) continue;
    const t = (Math.atan2(ly, mirror ? -lx : lx) + Math.PI) / (2 * Math.PI);
    for (const j of [0, 1]) if (Math.abs(r - (0.9 + 2.3 * (t + j))) < 0.55) return true;
  }
  return false;
}
function paintWall(v, f, c, p) {
  const bias = FACE_BIAS[f] ?? 0;
  if (f === 'up' || f === 'down') { v.paint((x, y) => g(f === 'up' ? (y === 0 || y === v.h - 1 || x === 0 || x === v.w - 1 ? 3 : 3.4) : 0.8)); return; }
  const onLong = (p.long === 'x') === (f === 'south' || f === 'north');
  const outer = f === p.face;
  // inner face and thin ends: plain tube shading
  v.paint((x, y) => { const t = (y + 0.5) / v.h; let s = 2.3 + bias * 0.4 + (t < 0.25 ? 0.8 : 0) - (t > 0.8 ? 1.0 : 0); if (y === v.h - 1) s = 0.4; return g(s); });
  if (!onLong || !outer) return;

  const [lo, hi] = p.long === 'x' ? [c.from[0], c.to[0]] : [c.from[2], c.to[2]];
  const toImg = u => (f === 'south' || f === 'west') ? (u - lo) * S : (hi - u) * S;
  const gemList = GEMS[p.wall], gemX = gemList.map(([u]) => toImg(u));
  const sgn = LIT_SIDE[f], off = clamp(bias, -0.6, 0.35) * 0.9;
  const xs = slots(v.w), mid = (xs.length - 1) / 2;
  // scroll cells: every slot that is not a gem slot; mirrored on the far half so the pattern is symmetric
  const cells = xs.filter(cx => !gemX.some(gx => Math.abs(gx - cx) < 1)).map(cx => [cx, cx > v.w / 2]);
  void mid;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (y === 0) s = 4;                                                 // top-edge specular: the only near-white line
    else if (y === 1) s = 3.3 + off * 0.4;
    else if (y === 2) s = 0.5;                                          // groove under the top moulding
    else if (y <= 10) {
      s = 2.35 + off;                                                   // engraved field
      if (scrollCut(x, y, cells)) s = 0.45;
      else if (scrollCut(x, y - 1, cells)) s = 3.3 + off * 0.5;        // lit lip under the groove
    } else if (y <= 18) {                                               // bead row: even 1 px beads, dark gaps
      s = 0.6;
      for (const cx of xs) {
        const bx = x - (cx - 4), by = y - 11;
        if (bx < 0 || bx > 7) continue;
        const dx = bx + 0.5 - 4, dy = by + 0.5 - 4, r = Math.hypot(dx, dy);
        if (r > 3.9) continue;
        s = r > 3.2 ? 0.7 : sphereShade(dx, dy, 3.6, sgn);
      }
    } else s = 0.35;                                                    // bottom edge
    v.set(x, y, g(s));
  }
  // gem seats: dark socket, bright bezel ring (kept above the bead row)
  for (let y = 1; y <= 10; y++) for (let x = 0; x < v.w; x++) {
    gemList.forEach(([, s], i) => {
      const dx = Math.abs(x + 0.5 - gemX[i]), dy = Math.abs(y + 0.5 - FIELD_C), n = Math.max(dx, dy, (dx + dy) * 0.78) - s * S / 2;
      if (n > 1.6 || n < -0.5) return;
      v.set(x, y, g(n < 0.8 ? 0.4 : 3.5));
    });
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
  meta: { id: 'crown-pixel', name: 'Pixel Crown', kind: 'crown', cents: 699, blurb: 'A small gold crown with four pearl-tipped points, an engraved scroll band and cut gems. Low enough to see over.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'crown', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: modelCubes }] },
  paint() {
    const tex = newTexture({ texSize: TEX, scale: S });
    for (const c of cubes) paintCube(tex, c, S, (v, f) => {
      const p = c._p;
      if (p.role === 'wall') paintWall(v, f, c, p);
      else if (p.role === 'spike') paintSpike(v, f);
      else if (p.role === 'ball') paintBall(v, f, c, p);
      else paintGem(v, f, c, p);
    });
    return tex;
  },
};
