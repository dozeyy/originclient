// Chrome Shell: a Y2K hard-shell sport backpack. A rounded, moulded shell (a rounded rectangle whose
// surface bows out like a shallow dome, built from 0.25 px terraces so it reads smooth) in two tones: polished chrome
// on top, gunmetal below, split by a slightly raked seam. A smoked-glass window sits low on the shell, a dark
// round medallion high on it carries the Origin mark (lib/marks, drawn pixel by pixel at the size it appears)
// as a raised chrome plate with its shadow cast on the medallion. Fine groove lines trace the shell's edge.
// Two black webbing straps rise from the shell over the shoulders and come down the chest with chrome
// glides and a sternum strap with a chrome clip.
//
// Chrome is painted as a MIRROR, not a gradient: every texel's surface normal comes from the dome's height
// field, the view ray is reflected off it, and the reflection looks up a small environment (a pale sky with a
// softbox, a crisp dark horizon, a dark ground with a faint bounce). The look therefore follows the shape:
// the flat top reflects sky, the lower curve crosses the horizon, the left rim catches the softbox.
// Terrace faces are all painted from the SAME continuous surface, so the steps disappear into one gloss.
//
// Light/view: the pack is seen from behind, the light comes from the top and the player's left (+x).
// Clearance (FORMAT.md): back item, inside x -4..4, deepest point z -6.625 (4.625 px off the back).
import { clamp, smoothstep, mix, toHex, Canvas } from '../../lib/px.mjs';
import { drawMark } from '../../lib/marks.mjs';
import { cubeRects, cubeFaceNames } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const PACK_W = 256;

// ---- ramps -----------------------------------------------------------------------------------------------
// polished chrome, 8 steps: blue-black shadows -> neutral steel -> a warm white only the hottest highlight reaches
const CH = ['#0a0c11', '#1a1e28', '#2e3441', '#4a5262', '#76808f', '#a7afbc', '#d6dbe4', '#fbf8f0'];
// gunmetal, 7 steps: darker and flatter, still cool in the dark, a pale steel at the very top
const GN = ['#07080c', '#11141a', '#1c2029', '#2a303b', '#3d4554', '#5a6577', '#8791a3'];
// smoked glass, 7 steps
const SM = ['#04060a', '#080c12', '#0e151e', '#16212d', '#223244', '#355069', '#5b7e98'];
// black webbing, 5 steps
const WB = ['#07080b', '#0f1115', '#171a20', '#21252d', '#2f3540'];
const SHEEN = ['#e86ab4', '#8e86f1', '#46d3e2'];                       // the lens sheen, pink -> violet -> cyan, glass only
const rampAt = (R, v) => {                                             // fractional ramp index -> colour, quarter steps
  const p = clamp(Math.round(v * 4) / 4, 0, R.length - 1), lo = Math.floor(p), hi = Math.min(R.length - 1, lo + 1);
  return toHex(mix(R[lo], R[hi], p - lo));
};

// ---- the shell: geometry (body frame: torso x -4..4, y -12..0, z -2..2; back surface z = -2) -----------------
const YC = -6.125, A = 3.75, B = 4.75, RC = 1.75;                   // outline: rounded rectangle, 7.5 x 9.5 px
const D0 = 1.75, H = 4.5, DH = H - D0, RD = 2.0;                    // rim wall height, total depth, dome inset range
const BULGE = 2.3;                                                  // px of extra centre rise the mirror sees (not geometry)
const STEP = 0.25, NK = Math.round(DH / STEP);                      // terrace count above the rim wall

// signed distance to a rounded rectangle centred on (0, YC): < 0 inside
function sdRR(x, y, a, b, r) {
  const qx = Math.abs(x) - (a - r), qy = Math.abs(y - YC) - (b - r);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
}
const inset = (x, y) => -sdRR(x, y, A, B, RC);
// dome height (depth behind the torso) at an inset, and its slope per px of inset
const surf = i => D0 + DH * Math.sqrt(Math.max(0, 1 - Math.pow(1 - clamp(i / RD), 2)));
const slope = i => {
  const t = clamp(i / RD, 0.01, 0.999999);
  return i >= RD ? 0 : Math.min(7, (DH * (1 - t)) / (RD * Math.sqrt(1 - (1 - t) * (1 - t))));
};
const surfInv = d => (d <= D0 ? 0 : d >= H ? RD : RD * (1 - Math.sqrt(1 - Math.pow((d - D0) / DH, 2))));

const parts = [];
const part = (role, from, to, o = {}) => { const c = { from, to, role, faces: {}, ...o }; parts.push(c); return c; };
// terrace 0 is the rim wall (depth 0..D0, inset 0); terrace k > 0 sits at depth D0 + STEP*k and covers the
// insets whose true surface rounds to it. Its box is the bounding box of the rounded rectangle at that inset.
const terr = [];
for (let k = 0; k <= NK; k++) {
  const d1 = k === 0 ? D0 : D0 + STEP * k, d0 = k === 0 ? 0 : d1 - STEP;
  const i0 = k === 0 ? 0 : surfInv(d1 - STEP / 2);
  const i1 = k === NK ? 99 : surfInv(d1 + STEP / 2);
  const a = A - i0, b = B - i0;
  const c = part('shell', [-a, YC - b, -2 - d1], [a, YC + b, -2 - d0], { k, i0, i1, d0, d1, need: ['north', 'up', 'down', 'east', 'west'] });
  terr.push(c);
}
// the raised mark plate: one thin north-facing sheet just in front of the plateau, transparent but for the mark
const MARK_SIZE = 24, MARK_STROKE = 2, MARK_OPEN = 1.4, MARK_CY = -4.875;
const PLATE = (MARK_SIZE + 2) * px;                                   // 3.25 px square
const plate = part('plate', [-PLATE / 2, MARK_CY - PLATE / 2, -2 - H - 0.125], [PLATE / 2, MARK_CY + PLATE / 2, -2 - H], { need: ['north'] });

// straps (black webbing): rise from the shell top, over the shoulder, down the chest
const SX = [[1.375, 3.0], [-3.0, -1.375]];
for (const [x0, x1] of SX) {
  part('strap', [x0, -8.0, 2.0], [x1, 0.25, 2.375], { kind: 'chest', need: ['south', 'up', 'east', 'west', 'down'] });
  part('strap', [x0, 0.0, -2.375], [x1, 0.25, 2.0], { kind: 'over', need: ['up', 'east', 'west'] });
  part('strap', [x0, -1.375, -2.375], [x1, 0.25, -2.0], { kind: 'rise', need: ['north', 'east', 'west'] });
  const g0 = x0 - 0.125, g1 = x1 + 0.125;
  part('glide', [g0, -4.375, 1.9375], [g1, -3.625, 2.5], { need: ['south', 'up', 'down', 'east', 'west'] });
}
part('strap', [-1.375, -3.5, 2.0], [1.375, -2.75, 2.25], { kind: 'sternum', need: ['south', 'up', 'down'] });
part('clip', [-0.5, -3.75, 2.0625], [0.5, -2.5, 2.5], { need: ['south', 'up', 'down', 'east', 'west'] });

// ---- texture packing: only the faces that can be seen get pixels --------------------------------------------
const faceDims = (c, f) => {
  const w = Math.round((c.to[0] - c.from[0]) * S), h = Math.round((c.to[1] - c.from[1]) * S), d = Math.round((c.to[2] - c.from[2]) * S);
  return f === 'north' || f === 'south' ? [w, h] : f === 'east' || f === 'west' ? [d, h] : [w, d];
};
function pack() {
  const reqs = [];
  for (const c of parts) for (const f of c.need) reqs.push({ c, f, w: faceDims(c, f)[0], h: faceDims(c, f)[1] });
  reqs.sort((a, b) => b.h - a.h || b.w - a.w || a.c.role.localeCompare(b.c.role) || (a.c.k ?? 0) - (b.c.k ?? 0) || a.f.localeCompare(b.f));
  let x = 0, y = 0, rowH = 0;
  for (const r of reqs) {
    if (x + r.w > PACK_W) { x = 0; y += rowH; rowH = 0; }
    r.c.faces[r.f] = [x / S, y / S, (x + r.w) / S, (y + r.h) / S];
    x += r.w; rowH = Math.max(rowH, r.h);
  }
  return y + rowH;
}
const TEX = [PACK_W / S, Math.ceil(pack() / S)];
const TW = TEX[0] * S, TH = TEX[1] * S;

// texel centre -> position on the cube surface (cube frame). Face images are read from outside, +y up.
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

// ---- the mirror ------------------------------------------------------------------------------------------------
const norm = v => { const l = Math.hypot(...v) || 1; return v.map(a => a / l); };
const VIEW = norm([0, 0.42, 0.9]);                                     // looking from behind and slightly below: the flat top reflects sky
// What the environment shows in direction r (world, +y up): a ramp index 0..7 on the chrome ramp.
function env(r) {
  const [rx, ry] = r;
  let s;
  if (ry >= 0.66) s = 6.3 + 1.1 * smoothstep(0.66, 0.96, ry);        // zenith: near white
  else if (ry >= 0.14) s = 4.0 + 2.3 * smoothstep(0.14, 0.66, ry);   // sky
  else if (ry >= 0.04) s = 5.7;                                      // a bright band just above the horizon
  else if (ry >= -0.07) s = 0.8;                                     // the horizon: a crisp dark line
  else if (ry >= -0.5) s = 1.7 + 1.5 * smoothstep(-0.07, -0.5, ry);  // dark ground, lightening downward
  else s = 3.3 - 0.9 * smoothstep(-0.5, -1, ry);                     // warm ground bounce
  if (rx > 0.32 && rx < 0.8 && ry > 0.2 && ry < 0.78) s += 1.5 * smoothstep(0.32, 0.45, rx) * (1 - smoothstep(0.62, 0.8, rx));   // softbox on the lit side
  s -= 1.2 * smoothstep(0.78, 1, Math.abs(rx));                      // grazing edge falls off
  return s;
}
// Surface normal (world) of the dome at (x, y) at inset i (outward = toward the viewer behind = -z).
function normalAt(x, y, i) {
  const e = 1 / 64, ix = (inset(x + e, y) - inset(x - e, y)) / (2 * e), iy = (inset(x, y + e) - inset(x, y - e)) / (2 * e);
  const sp = slope(i);
  // plus a gentle overall bulge (shading only), so even the flat top bends the mirror: bold reflections, not a flat tint
  return norm([-sp * ix + (2 * BULGE * x) / (A * A), -sp * iy + (2 * BULGE * (y - YC)) / (B * B), -1]);
}
function reflectAt(x, y, i) {
  const n = normalAt(x, y, i), vn = VIEW[0] * n[0] + VIEW[1] * n[1] + VIEW[2] * n[2];
  return [VIEW[0] - 2 * vn * n[0], VIEW[1] - 2 * vn * n[1], VIEW[2] - 2 * vn * n[2]];
}

// ---- the shell's colours -----------------------------------------------------------------------------------------
const seamY = X => -6.8 - 0.13 * X;                                    // the chrome / gunmetal split, raked a little
const MED = { y: MARK_CY, r: 1.95 };                                   // the round medallion behind the mark
const WIN = { y: -8.2, a: 1.75, b: 1.0, r: 0.6 };                      // the smoked window

// the mark, as image-space texels of the plate (px(plate) = (X1 - X) * S etc.), built once
let markMap = null;
function markPixels() {
  if (markMap) return markMap;
  const g = new Map();
  drawMark({ set(x, y, c) { g.set(`${x},${y}`, c); } }, (MARK_SIZE + 2) / 2, (MARK_SIZE + 2) / 2, MARK_SIZE, p => p, MARK_STROKE, { open: MARK_OPEN });
  return (markMap = g);
}
const plateTexel = (X, Y) => [Math.floor((plate.to[0] - X) * S), Math.floor((plate.to[1] - Y) * S)];

function shellColour(X, Y, i, wallOverride) {
  const iEff = wallOverride ?? i;
  const r = reflectAt(X, Y, Math.max(iEff, 0.0001));
  const e = env(r);
  const ys = seamY(X), lower = Y < ys;
  let col;
  // medallion: a dark gunmetal disc with a chrome bevel ring
  const dm = Math.hypot(X, Y - MED.y);
  if (dm < MED.r) {
    const ang = Math.atan2(-(Y - MED.y), -X);                          // 0 at the left, toward the light
    if (dm > MED.r - 0.0625) col = CH[0];                            // outer groove
    else if (dm > MED.r - 0.1875) col = rampAt(CH, 3.1 + 2.6 * Math.cos(ang - 2.4));    // bevel ring, lit from the top-left
    else {                                                             // a soft glossy disc: lit from the top-left, a pale crescent on that rim
      const dl = Math.hypot(X - 0.75, Y - (MED.y + 0.75));
      col = rampAt(GN, 1.1 + 3.1 * (1 - smoothstep(0, 2.9, dl)));
      if (dm > MED.r - 0.375 && dl < 1.45 && dl > 1.2) col = GN[5];
    }
    // the plate's shadow, down and to the right of the mark
    const [pu, pv] = plateTexel(X, Y), mk = markPixels();
    if (dm <= MED.r - 0.1875 && mk.get(`${pu - 2},${pv - 2}`) && !mk.get(`${pu},${pv}`)) col = GN[0];
    return col;
  }
  // chrome above the seam, gunmetal below
  if (!lower) col = rampAt(CH, e);
  else col = rampAt(GN, 0.3 + e * 0.66);
  // the seam: a dark groove, a lit lip above it, a little lift below
  const dy = Y - ys;
  if (Math.abs(dy) < px * 0.55) col = CH[0];
  else if (dy >= px * 0.55 && dy < px * 1.55) col = rampAt(CH, Math.min(7, e + 1.3));
  else if (dy <= -px * 0.55 && dy > -px * 1.55) col = rampAt(GN, 0.3 + e * 0.66 + 1.0);
  // groove lines tracing the edge, with a lit lip inside each
  for (const g of [0.5, 1.35]) {
    const dg = i - g;
    if (Math.abs(dg) < px * 0.55 && i > 0) col = lower ? GN[0] : CH[1];
    else if (dg >= px * 0.55 && dg < px * 1.6 && i > 0) col = lower ? rampAt(GN, 0.3 + e * 0.66 + 1.0) : rampAt(CH, Math.min(7, e + 1.0));
  }
  // two screws at the ends of the seam
  for (const sx of [-1, 1]) {
    const cx = sx * 3.0, cy = seamY(cx) - 0.0, d = Math.hypot(X - cx, Y - cy - 0.375);
    if (d < 0.19) col = d > 0.125 ? CH[0] : (X - cx) * sx < 0 ? CH[6] : CH[4];
  }
  // the smoked window with a chrome bezel
  const sw = sdRR(X, Y - (WIN.y - YC), WIN.a, WIN.b, WIN.r);
  if (sw < 0.0625) {
    if (sw > -px * 0.9) col = CH[0];
    else if (sw > -px * 1.9) col = rampAt(CH, 3.2 + 2.2 * clamp(0.5 - 0.5 * ((Y - WIN.y) / WIN.b) * 0 + 0.5 * (X > 0 ? 1 : -0.4)));
    else col = glass(X, Y);
  }
  return col;
}
// smoked glass: dark, a diagonal pale reflection, a faint pink -> cyan sheen, and the pack's contents behind it
function glass(X, Y) {
  const v = (Y - (WIN.y - WIN.b)) / (2 * WIN.b);                       // 0 bottom .. 1 top
  let l = 1.4 + 1.3 * v;
  const d = X * 0.55 + (Y - WIN.y) * 0.9;                              // slanted coordinate for the reflection
  if (d > 0.35 && d < 0.55) l += 2.4;                                  // the crisp reflection band
  else if (d >= 0.55 && d < 0.85) l += 0.9;
  else if (d > -0.15 && d <= 0.35) l += 0.3;
  // the contents: a slate "device" silhouette and its strap, lit a step above the glass
  const inDev = X > -1.05 && X < 0.55 && Y > WIN.y - 0.6 && Y < WIN.y + 0.55;
  if (inDev) l += (Math.abs(X + 0.25) > 0.78 || Math.abs(Y - WIN.y + 0.03) > 0.43) ? 0.9 : 0.35;
  if (Math.abs(Y - (WIN.y + 0.25)) < px * 0.6 && X > 0.55 && X < 1.5) l += 0.8;
  let col = rampAt(SM, l);
  // sheen: pink -> violet -> cyan across the glass, kept dim
  const t = clamp(0.5 - X / (2 * WIN.a));
  const sh = t < 0.5 ? mix(SHEEN[0], SHEEN[1], t * 2) : mix(SHEEN[1], SHEEN[2], (t - 0.5) * 2);
  col = toHex(mix(col, sh, 0.1 + 0.28 * (d > 0.35 && d < 0.85 ? 1 : 0)));
  return col;
}

// ---- painting each face ------------------------------------------------------------------------------------------
// A shell face texel is drawn when it lies on the terrace's surface; a wall texel when the rounded outline
// actually reaches it (the corners are cut out, the next terrace's face shows through instead).
const insideAt = (c, X, Y) => sdRR(X, Y, A - c.i0, B - c.i0, Math.max(RC - c.i0, 0.2)) <= 0.0001;
function paintShell(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    if (f === 'north') {
      if (!insideAt(c, X, Y)) continue;
      v.set(x, y, shellColour(X, Y, inset(X, Y)));
    } else {
      // a wall: probe a hair inside the face; the outline must reach this edge for it to be solid
      const px2 = f === 'east' ? X - 1 / 32 : f === 'west' ? X + 1 / 32 : X, py2 = f === 'up' ? Y - 1 / 32 : f === 'down' ? Y + 1 / 32 : Y;
      if (!insideAt(c, px2, py2)) continue;
      const i = Math.max(Math.min(c.i0 + 0.5 * (Math.min(c.i1, RD) - c.i0), RD), 0.03);
      // the wall's own surface (steep, outward): shade it as the dome at this inset
      v.set(x, y, shellColour(px2, py2, i, i));
    }
  }
}
function paintPlate(v, f, c) {
  const mk = markPixels();
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const m = mk.get(`${x},${y}`);
    if (!m) continue;
    // chrome tube: the stroke is a rounded wire lit from the top-left (lib/marks tubeLight), edge pixels a step darker
    let l = 1.6 + m.light * 5.4;
    if (m.edge) l = Math.max(0.6, l - 1.6);
    v.set(x, y, rampAt(CH, l));
  }
}
// webbing: horizontal weave rows, stitched edges (a dashed thread inset 2 texels from each long edge), lit toward +x
function paintStrap(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let l = 2.2 + (f === 'up' ? 1.2 : f === 'down' ? -1.2 : 0) + (y % 3 === 0 ? 0.35 : -0.15);
    const [X] = world(c, f, x, y);
    if (f === 'south' || f === 'north') {
      l += 0.5 * (X > 0 ? 1 : -0.3) * 0.5;
      const edge = Math.min(x, v.w - 1 - x);
      if (edge === 0) l = 0.7; else if (edge === 1) l = 3.6;
      else if (edge === 3 && y % 4 < 2) l = 3.9;
      if (c.kind === 'sternum' && (y === 0 || y === v.h - 1)) l = y === 0 ? 3.6 : 0.8;
      if (c.kind === 'chest' && y > v.h - 3) l = 0.8;
    } else l = f === 'up' ? 3.2 : 1.4;
    v.set(x, y, rampAt(WB, l));
  }
}
// chrome hardware (glides, the sternum clip): flat plates with a bright top edge and a dark under-edge, a slot
function paintMetal(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let l = f === 'up' ? 6.2 : f === 'down' ? 1.0 : 3.8 + 1.4 * (y < v.h / 2 ? 1 : -0.4);
    if (f === 'south') {
      if (y === 0) l = 6.9; else if (y === v.h - 1) l = 1.0; else if (x === 0) l = 5.8; else if (x === v.w - 1) l = 2.2;
      if (c.role === 'glide' && y >= 2 && y <= v.h - 3 && x >= 3 && x <= v.w - 4) l = (y === 2 || x === 3) ? 0.8 : (y === v.h - 3 || x === v.w - 4) ? 4.6 : 0.5;   // the slot the webbing runs through
      if (c.role === 'clip' && x >= 2 && x <= v.w - 3 && y >= 3 && y <= v.h - 4) l = (y === 3 || x === 2) ? 5.8 : 2.2 + 0.5 * (y > v.h / 2 ? -1 : 0);
    } else if (f === 'east') l = 4.4; else if (f === 'west') l = 2.0;
    v.set(x, y, rampAt(CH, l));
  }
}

const PAINT = { shell: paintShell, plate: paintPlate, strap: paintStrap, glide: paintMetal, clip: paintMetal };
let tex = null;
function paint() {
  if (tex) return tex;
  tex = new Canvas(TW, TH);
  for (const c of parts) {
    const rects = cubeRects({ ...c, uv: [0, 0] }, S);
    for (const f of cubeFaceNames(c)) {
      const r = rects[f];
      PAINT[c.role](tex.view(r.x, r.y, r.w, r.h), f, c);
    }
  }
  return tex;
}

const strip = ({ role, k, i0, i1, d0, d1, kind, need, ...c }) => c;
export default {
  meta: { id: 'pack-shell', name: 'Chrome Shell', kind: 'pack', cents: 899, capeOffset: 3.5,
    blurb: 'A Y2K hard-shell backpack: a polished chrome and gunmetal shell, a smoked-glass window and the Origin mark raised on the back.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'pack', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.map(strip) }] },
  paint() { return paint(); },
};
