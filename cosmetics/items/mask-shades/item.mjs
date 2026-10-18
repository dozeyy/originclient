// Chrome Shades: Y2K bug-eye wraparound sunglasses. Two big rounded-oval mirror lenses (a pink -> cyan -> lime
// iridescent sheen over dark smoke, with two slanted sky reflections and a bright catch-light along the lower
// edge) sit recessed in thin polished-chrome rims. A curved chrome bridge joins them, a hinge block at each
// outer end and a short chrome arm wraps to the side of the head and bends down behind the ear. Every few
// seconds a soft glint glides across both lenses (additive glow planes; the rest of the loop the glass is still).
//
// Built for its own silhouette (nothing like the one-piece Visor Mask): each lens is stacked row cubes of a
// superellipse (rows 0.25 px high, edges on 1/8 px), so the oval reads at 1x AND up close, and the rim is a
// real ring with a recessed lens inside it. Each lens bone is yawed 8 degrees so the pair wraps the face.
//
// Light: top-front-left of the PLAYER (+x, +y, +z). Chrome is painted as a reflection of a tiny environment:
// across a ring/bar the texels read sky (bright) / horizon (a dark line) / ground bounce, which is what makes
// a 2 to 3 texel stroke look like polished metal rather than grey plastic. Texels are painted from their
// position in the cube frame, so the lens gradient and rim shading run continuously across the row cubes.
import { clamp, smoothstep, mix, toHex } from '../../lib/px.mjs';
import { newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const FRAMES = 16, FPS = 8, SWEEP_FRAMES = 8;

// ---- ramps -----------------------------------------------------------------------------------------------
// polished chrome, 7 steps: cool near-black -> mid steel -> a warm-white specular (only the sky reflection)
const C = ['#0b0d12', '#232731', '#454c5a', '#79828f', '#b0b8c3', '#e4e8ee', '#fff7ea'];
// dark smoke glass under the mirror coat
const K = ['#05060a', '#0a0d15', '#10151f', '#171e2b', '#202a3a'];
// the iridescent sheen: pink -> cyan -> lime (the brand's only hue lives here)
const PINK = '#ff52ac', CYAN = '#40e4ff', LIME = '#b8ff52';
const sheenAt = h => (h < 0.5 ? mix(PINK, CYAN, h * 2) : mix(CYAN, LIME, (h - 0.5) * 2));
const pick = (R, v) => R[clamp(Math.round(v), 0, R.length - 1)];
const SWEEP = '#e0e0e0';

// ---- geometry (head frame: head cube x -4..4, y 0..8, z -4..4) ------------------------------------------
const CX = 2.25, CY = 3.7, A = 1.85, B = 1.5, P = 2.2, TH = 0.3125;       // lens centre, outer radii, superellipse power, rim width
const YAW = 8;                                                            // each lens turned this far toward the temple
const RZ = [3.875, 4.625], GZ = [3.875, 4.375];                           // rim depth (front 0.625 proud of the face), lens plate (recessed 0.25)
const sup = (u, w) => (Math.abs(u) ** P + Math.abs(w) ** P) ** (1 / P);
const rho = (lx, ly, a = A, b = B) => sup(lx / a, ly / b);
const inOuter = (lx, ly) => rho(lx, ly) < 1;
const inInner = (lx, ly, grow = 0) => rho(lx, ly, A - TH + grow, B - TH + grow) < 1;

const parts = [];
const g8 = v => Math.round(v * 8) / 8;                                   // every edge sits on a texel (1/8 px)
const part = (role, from, to, extra = {}) => { const c = { from: from.map(g8), to: to.map(g8), uv: [0, 0], role, ...extra }; parts.push(c); return c; };
// Stacked rows of a 2D mask: for each 1/8 px row, the runs of 1/8 px cells whose centre is inside (a row cube is a
// texel and a half tall: it overlaps the one above by a texel so no crack shows).
function rows(mask, y0, y1) {
  const out = [];
  for (let ya = y0; ya < y1 - 1e-9; ya += px) {
    const yc = ya + px / 2 - CY;
    let start = null;
    for (let lx = -2.125; lx <= 2.125 + 1e-9; lx += px) {
      const on = mask(lx + px / 2, yc);
      if (on && start === null) start = lx;
      if (!on && start !== null) { out.push([start, lx, ya, ya + px + (ya + px < y1 - 1e-9 ? px : 0), Math.round((ya - y0) / px)]); start = null; }
    }
  }
  return out;
}
for (const sx of [1, -1]) {
  const bone = sx > 0 ? 'lensL' : 'lensR';
  // the lens plate runs 1/8 px under the rim so no crack shows; the rim is the ring between the two ellipses
  // the overlapping rows alternate 0.004 px in depth so their coplanar fronts never z-fight
  for (const [a, b, ya, yb, k] of rows((lx, ly) => inInner(lx, ly, px), CY - B, CY + B)) part('glass', [sx * CX + a, ya, GZ[0]], [sx * CX + b, yb, GZ[1]], { sx, bone, dz: (k % 2) * 0.004 });
  for (const [a, b, ya, yb, k] of rows((lx, ly) => inOuter(lx, ly) && !inInner(lx, ly), CY - B, CY + B)) part('rim', [sx * CX + a, ya, RZ[0]], [sx * CX + b, yb, RZ[1]], { sx, bone, dz: (k % 2) * 0.004 });
}
// frame bone: curved bridge, hinge blocks, short arms
part('chrome', [-0.75, 3.95, 4.15], [0.75, 4.3, 4.65], { bone: 'frame', bridge: true });
part('chrome', [-0.375, 4.3, 4.2], [0.375, 4.5, 4.65], { bone: 'frame', bridge: true });
for (const sx of [1, -1]) {
  const m = (a, b) => (sx > 0 ? [a, b] : [-b, -a]);
  const [h0, h1] = m(3.95, 4.5), [a0, a1] = m(4.0, 4.4);
  part('chrome', [h0, 3.4, 3.1], [h1, 4.05, 4.4], { sx, bone: 'frame', hinge: true });
  part('chrome', [a0, 3.55, 0.9], [a1, 4.0, 3.2], { sx, bone: 'frame', arm: true });
  part('chrome', [a0, 3.2, -0.1], [a1, 3.85, 1.0], { sx, bone: 'frame', arm: true });
}

// ---- sweep planes: one texture region over both lenses, each lens plane shows its half -----------------------
const SW = { x0: -4.25, x1: 4.25, y0: CY - B, y1: CY + B };
const SWH = Math.round((SW.y1 - SW.y0) / px) * px;

// ---- atlas ------------------------------------------------------------------------------------------------------
// Each cube draws only the faces that can be seen (a lens plate: its front only; a rim: front + the four edges),
// every face in its own rectangle with a 1 texel gutter that repeats the face's edge colour. Row cubes are
// thin strips, so without the gutter the sampler picked up the neighbouring strip at a row seam and drew hairline
// cracks across the lenses.
const NEED = { glass: ['south'], rim: ['south', 'up', 'down', 'east', 'west'], chrome: ['north', 'east', 'south', 'west', 'up', 'down'] };
const GUT = 1;
const faceDims = (c, f) => {
  const [w, h, d] = [0, 1, 2].map(i => Math.round((c.to[i] - c.from[i]) * S));
  return { north: [w, h], south: [w, h], east: [d, h], west: [d, h], up: [w, d], down: [w, d] }[f];
};
const items = [];
parts.forEach((c, i) => { for (const f of NEED[c.role]) { const [fw, fh] = faceDims(c, f); items.push({ c, f, fw, fh, i }); } });
items.sort((a, b) => b.fh - a.fh || b.fw - a.fw || a.i - b.i);
function pack(W) {
  let x = 0, y = 0, rowH = 0;
  for (const it of items) {
    const cw = it.fw + 2 * GUT, ch = it.fh + 2 * GUT;
    if (x + cw > W) { x = 0; y += rowH; rowH = 0; }
    it.x = x + GUT; it.y = y + GUT; x += cw; rowH = Math.max(rowH, ch);
  }
  return y + rowH;
}
const SWHT = Math.round(SWH * S);                                          // the sweep region's height in texels
let best = null;
for (let W = 72; W <= 320; W += 8) {
  const H = pack(W), rows = Math.ceil((H + 2 + SWHT + GUT) / S);
  if (W * rows * S * 4 * FRAMES <= 4 * 1024 * 1024 && (!best || W * rows < best.W * best.rows)) best = { W, rows };
}
if (!best) throw new Error('mask-shades: no texture size fits the 4 MB budget');
const HPACK = pack(best.W);
const TEX = [best.W / S, best.rows];
for (const it of items) {
  (it.c.faces ??= {})[it.f] = [it.x / S, it.y / S, (it.x + it.fw) / S, (it.y + it.fh) / S];
}
const SWU = [GUT / S, (HPACK + 2) / S];                                   // the sweep region in model px (below the faces, 2 texels clear)
const planeCube = sx => {
  const x0 = sx > 0 ? 0.25 : -4.25, x1 = x0 + 4.0, u0 = x0 - SW.x0;
  return { from: [x0, SW.y0, 4.45], to: [x1, SW.y0 + SWH, 4.45], glow: true, faces: { south: [SWU[0] + u0, SWU[1], SWU[0] + u0 + 4.0, SWU[1] + SWH] } };
};

// ---- geometry helpers ---------------------------------------------------------------------------------------
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
// lens-local polar info at a point: ring param, distance in from the outer curve, rim width along the ray, outward normal
function ringAt(lx, ly) {
  const r = Math.hypot(lx, ly) || 1e-6, ro = rho(lx, ly) || 1e-6, ri = rho(lx, ly, A - TH, B - TH) || 1e-6;
  const R = r / ro, Rin = r / ri;                                        // radius of the outer / inner curve along this ray
  const u = lx / A, w = ly / B;
  const gx = Math.sign(lx) * Math.abs(u) ** (P - 1) / A, gy = Math.sign(ly) * Math.abs(w) ** (P - 1) / B, g = Math.hypot(gx, gy) || 1e-6;
  return { dOut: R - r, dIn: r - Rin, width: Math.max(R - Rin, 1e-3), nx: gx / g, ny: gy / g, r, R };
}

// ---- chrome rim ---------------------------------------------------------------------------------------------
// A rounded wire: across its width the surface normal tilts from outward (outer edge) through straight-ahead to
// inward (inner edge). Phong against the top-front-left light, quantised onto the chrome ramp, then a ground
// bounce lifts the shadow side (chrome is never flat black) and a 1 texel outline closes the silhouette.
const LDIR = (() => { const l = [0.45, 0.62, 0.64], n = Math.hypot(...l); return l.map(v => v / n); })();
function chromeLit(nx, ny, nz) {
  const lit = nx * LDIR[0] + ny * LDIR[1] + nz * LDIR[2];
  let s = lit > 0.86 ? 6 : lit > 0.7 ? 5 : lit > 0.52 ? 4 : lit > 0.32 ? 3 : lit > 0.12 ? 2 : 1;
  if (ny < -0.15 && s < 4) s += 1.1 * smoothstep(-0.15, -0.7, ny);       // ground bounce on the underside
  return s;
}
function paintRim(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    let col;
    if (f === 'south') {
      const lx = X - c.sx * CX, ly = Y - CY, g = ringAt(lx, ly);
      const t = clamp(g.dOut / g.width), tp = 1 - 2 * t;                  // +1 outer edge .. -1 inner edge
      const z = Math.sqrt(Math.max(0, 1 - tp * tp));
      let s = chromeLit(g.nx * tp, g.ny * tp, z);
      if (g.dOut < px * 0.99) s = Math.min(s, g.nx * 0.45 + g.ny * 0.9 > 0.3 ? 2.2 : 1);   // outline, softer on the lit side
      col = pick(C, s);
    } else col = pick(C, { up: 4, east: 3, north: 1, south: 3, west: 2, down: 1 }[f]);
    v.set(x, y, col);
  }
}

// ---- iridescent mirror lens -------------------------------------------------------------------------------------
// h runs pink (top) -> cyan (middle) -> lime (bottom), tilted a little so the bands lean; the coat is strongest
// in the upper half and thins to smoke at the rim. Two slanted sky reflections, a thin dark lip under the rim and
// a bright catch-light along the lower edge finish it. Every value is quantised so gradients are clean bands.
const q = (v, n) => Math.round(v * n) / n;
function lensColor(lx, ly) {
  const g = ringAt(lx, ly), dIn = -g.dIn;                                 // distance in from the lens edge (>= 0 inside)
  const h = clamp(0.5 - ly * 0.4 + lx * 0.08);
  const sheen = sheenAt(q(h, 8));
  let a = 0.8 + 0.1 * (ly / B) - 0.05 * (lx / A);
  const edge = smoothstep(0.0, 0.3, dIn);                                // smoke shows through near the rim
  a *= 0.45 + 0.55 * edge;
  const smoke = pick(K, 1.2 + 2.2 * (ly + B) / (2 * B) * 0.8 + 0.6 * edge);
  let col = mix(smoke, sheen, q(a, 12));
  // sky reflections: two slanted bands up-left
  const qq = lx * 0.8 - ly * 0.6;
  if (dIn > 0.1) {
    if (qq > -0.78 && qq < -0.5) col = mix(col, '#f2f6ff', 0.56);
    else if (qq > -0.36 && qq < -0.24) col = mix(col, '#f2f6ff', 0.4);
  }
  // catch-light along the lower edge (ground bounce) and a lip of shadow under the top rim
  if (ly < -0.35 && dIn < 0.14 && dIn > 0.0 && lx > -1.0 && lx < 1.15) col = mix(col, sheenAt(q(h, 8)), 0.5), col = mix(col, '#ffffff', 0.18);
  if (ly > 0.2 && dIn < 0.14) col = mix(col, K[0], 0.7);
  if (lx > 0.9 && ly > -0.9 && dIn < 0.1) col = mix(col, K[0], 0.4);
  return toHex(col);
}
function paintGlass(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    let col;
    if (f === 'south') col = lensColor(X - c.sx * CX, Y - CY);
    else if (f === 'up') col = K[2];
    else col = K[1];
    v.set(x, y, col);
  }
}

// ---- chrome bars: bridge, hinge blocks, arms --------------------------------------------------------------------
function paintBar(v, f, c) {
  const outer = (c.sx > 0 && f === 'east') || (c.sx < 0 && f === 'west');
  const bias = { up: 0, south: 0.3, east: 0.5, west: -0.6, north: -0.3, down: -1.2 }[f] ?? 0;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (f === 'up') s = y === v.h - 1 ? 6 : 5;
    else if (f === 'down') s = 1;
    else {
      const yf = v.h > 1 ? y / (v.h - 1) : 0;
      if (v.h >= 5) s = yf < 0.2 ? 5.2 : yf < 0.45 ? 4.2 : yf < 0.62 ? 2 : yf < 0.9 ? 3 : 1;      // sky / horizon / ground bounce / under edge
      else s = y === 0 ? 5 : y === v.h - 1 ? 1 : 3;
      s += bias + (outer ? 0.4 : 0);
      if (y === 0 && !outer) s = Math.min(s, 4.4);
    }
    if (c.hinge && (f === 'east' || f === 'west')) {
      const [X, , Z] = world(c, f, x, y);
      if (Math.abs(Z - 3.8125) < px * 0.6) s = 0.8;                          // the hinge seam
      if (Math.abs(Z - 3.4375) < px * 0.6 && Math.abs(y - (v.h - 1) / 2) < 1.2) s = 6;   // a pin head catching the light
      void X;
    }
    v.set(x, y, pick(C, s));
  }
}

// ---- the glint -----------------------------------------------------------------------------------------------
// A slanted band, a bright 2 texel core with a softer trailing band and one faint thin echo ahead. Additive, so each
// step is a grey whose brightness is what it adds to the glass; only inside the lenses.
function paintSweep(tex, frame) {
  if (frame >= SWEEP_FRAMES) return;
  const base = SWU.map(n => n * S), w = Math.round((SW.x1 - SW.x0) * S), h = Math.round(SWH * S);
  const p = -6.4 + (12.8 * (frame + 0.5)) / SWEEP_FRAMES;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const X = SW.x0 + (x + 0.5) * px, Y = SW.y1 - (y + 0.5) * px, sx = X > 0 ? 1 : -1, lx = X - sx * CX, ly = Y - CY;
    if (!inInner(lx, ly, -px) || Math.abs(X) < 0.3) continue;
    const d = X - 0.55 * (Y - CY) - p;
    let k = 0;
    if (d >= -0.125 && d < 0.125) k = 0.7;
    else if (d >= -0.5 && d < -0.125) k = 0.34;
    else if (d >= -1.0 && d < -0.5) k = 0.14;
    else if (d >= 0.375 && d < 0.5) k = 0.16;
    if (k) tex.set(base[0] + x, base[1] + y, [224, 224, 224, Math.round(k * 255)]);
  }
}

const PAINT = { rim: paintRim, glass: paintGlass, chrome: paintBar };
const strip = ({ role, sx, bone, bridge, hinge, arm, dz = 0, ...c }) => (dz ? { ...c, from: [c.from[0], c.from[1], c.from[2] + dz], to: [c.to[0], c.to[1], c.to[2] + dz] } : c);
const inBone = name => parts.filter(c => c.bone === name).map(strip);

let staticTex = null;
function staticLayer() {
  if (staticTex) return staticTex;
  const t = newTexture({ texSize: TEX, scale: S });
  for (const c of parts) paintCube(t, c, S, (v, f) => PAINT[c.role](v, f, c));
  // gutters: repeat each face's edge texels one texel outward
  for (const { x, y, fw, fh } of items) {
    for (let i = -1; i <= fw; i++) {
      const cx = Math.min(fw - 1, Math.max(0, i));
      t.set(x + i, y - 1, t.get(x + cx, y)); t.set(x + i, y + fh, t.get(x + cx, y + fh - 1));
    }
    for (let j = 0; j < fh; j++) { t.set(x - 1, y + j, t.get(x, y + j)); t.set(x + fw, y + j, t.get(x + fw - 1, y + j)); }
  }
  return (staticTex = t);
}

export default {
  meta: { id: 'mask-shades', name: 'Chrome Shades', kind: 'mask', cents: 699, blurb: 'Y2K bug-eye wraparound shades: mirrored pink, cyan and lime lenses in thin polished chrome. A glint sweeps across now and then.' },
  model: {
    texSize: TEX, scale: S,
    bones: [
      { name: 'root', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: [] },
      { name: 'lensL', parent: 'root', pivot: [CX, CY, 4.3], rotation: [0, YAW, 0], cubes: [...inBone('lensL'), planeCube(1)] },
      { name: 'lensR', parent: 'root', pivot: [-CX, CY, 4.3], rotation: [0, -YAW, 0], cubes: [...inBone('lensR'), planeCube(-1)] },
      { name: 'frame', parent: 'root', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: inBone('frame') },
    ],
  },
  anim: { frames: FRAMES, fps: FPS },
  paint(frame = 0) {
    const t = staticLayer().clone();
    paintSweep(t, frame);
    return t;
  },
};
