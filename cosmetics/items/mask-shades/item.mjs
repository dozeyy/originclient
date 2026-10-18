// Chrome Shades: Y2K bug-eye wraparound sunglasses. Two wide, flat, tapering oval lenses of smoked gunmetal mirror, each with
// ONE diagonal pink -> cyan -> lime sheen band (the only hue), in thin polished-chrome rims, a thick chrome nose bar, corner
// posts that turn the frame around the head, a small wrap lens on each side and thin chrome arms with an ear hook. Every few
// seconds a soft glint glides across both lenses (additive glow planes; the rest of the loop the glass is still).
//
// Built for its own silhouette (nothing like the one-piece Visor Mask): each lens is stacked row cubes of a
// superellipse (rows 0.25 px high, edges on 1/8 px), so the oval reads at 1x AND up close, and the rim is a
// real ring with a recessed lens inside it. Each lens bone is yawed 5 degrees so the pair wraps the face.
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
// Each lens is a wide, flat oval (about 1.8 : 1) that tapers toward the temple: its lower edge climbs, its top
// stays level, so the pair reads as bug-eye wraparounds rather than two round reading glasses. A thick chrome bar
// crosses the nose, a chrome corner post turns each lens around the head, a smaller wrap lens in its own rim sits on
// the side of the head, and a thin chrome arm runs back along the head with a hook behind the ear.
const CX = 2.1, CY = 3.625, A = 1.9, B0 = 1.5, P = 2.3, TH = 0.375;      // lens centre, outer half width / nose-end half height, superellipse power, rim width
const TAPER = 0.3, LIFT = 0.22;                                           // temple end: 30% lower half height, centre raised
const YAW = 5;                                                            // each lens turned this far toward the temple
const RZ = [4.375, 4.625], GZ = [4.375, 4.5];                           // rim depth (front 0.625 proud of the face), lens plate (recessed 0.25)
const sup = (u, w) => (Math.abs(u) ** P + Math.abs(w) ** P) ** (1 / P);
const shape = lx => { const s = clamp((lx / A + 1) / 2); return { b: B0 * (1 - TAPER * s), c: LIFT * B0 * s }; };
const rhoS = (lx, ly, grow = 0) => { const { b, c } = shape(lx); return sup(lx / (A + grow), (ly - c) / (b + grow)); };
const inOuter = (lx, ly) => rhoS(lx, ly) < 1;
const inInner = (lx, ly, grow = 0) => rhoS(lx, ly, -TH + grow) < 1;
// side wrap lens: a half oval in (z, y) on the head side, pointing back toward the arm
const ZC = 3.875, ZA = 1.7, SC = 4.0, PB = 1.0, TS = 0.375, SIDE_X = [4.0, 4.4];
const sideRho = (z, y, grow = 0) => sup(Math.max(0, ZC - z) / (ZA + grow), (y - SC) / (PB + grow));

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
// The same in (z, y) for the side lens, identical consecutive rows merged into one taller cube.
function zrows(mask, y0, y1, z0, z1) {
  const out = []; let open = new Map();
  for (let ya = y0; ya < y1 - 1e-9; ya += px) {
    const yc = ya + px / 2, row = []; let start = null;
    for (let z = z0; z <= z1 + 1e-9; z += px) {
      const on = z < z1 - 1e-9 && mask(z + px / 2, yc);
      if (on && start === null) start = z;
      if (!on && start !== null) { row.push([start, z]); start = null; }
    }
    const next = new Map();
    for (const [a, b] of row) {
      const key = a + '|' + b, prev = open.get(key);
      if (prev) { prev.yb = ya + px; next.set(key, prev); } else { const cu = { a, b, ya, yb: ya + px }; out.push(cu); next.set(key, cu); }
    }
    open = next;
  }
  return out;
}
const LY = [2.0, 5.25];
for (const sx of [1, -1]) {
  const bone = sx > 0 ? 'lensL' : 'lensR';
  // the lens plate runs 1/8 px under the rim so no crack shows; the rim is the ring between the two ovals
  // the overlapping rows alternate 0.004 px in depth so their coplanar fronts never z-fight
  for (const [a, b, ya, yb, k] of rows((lx, ly) => inInner(lx, ly, px), ...LY)) part('glass', [sx * CX + a, ya, GZ[0]], [sx * CX + b, yb, GZ[1]], { sx, bone, dz: (k % 2) * 0.004 });
  for (const [a, b, ya, yb, k] of rows((lx, ly) => inOuter(lx, ly) && !inInner(lx, ly), ...LY)) part('rim', [sx * CX + a, ya, RZ[0]], [sx * CX + b, yb, RZ[1]], { sx, bone, dz: (k % 2) * 0.004 });
  // side wrap lens (rim ring + recessed smoke glass), on the frame bone
  const sxr = (a, b) => (sx > 0 ? [a, b] : [-b, -a]);
  const [r0, r1] = sxr(SIDE_X[0], SIDE_X[1]), [g0, g1] = sxr(SIDE_X[0], SIDE_X[1] - 0.125);
  for (const cu of zrows((z, y) => sideRho(z, y, -TS + px) < 1, 2.75, 5.25, 2.1, 4.0)) part('sglass', [g0, cu.ya, cu.a], [g1, cu.yb, cu.b], { sx, bone: 'frame' });
  for (const cu of zrows((z, y) => sideRho(z, y) < 1 && sideRho(z, y, -TS) >= 1, 2.75, 5.25, 2.1, 4.0)) part('srim', [r0, cu.ya, cu.a], [r1, cu.yb, cu.b], { sx, bone: 'frame' });
}
// frame bone: thick nose bar, corner posts, thin arms with an ear hook
part('chrome', [-0.9, 3.9, 4.15], [0.9, 4.65, 4.85], { bone: 'frame', bridge: true });
for (const sx of [1, -1]) {
  const m = (a, b) => (sx > 0 ? [a, b] : [-b, -a]);
  const [h0, h1] = m(3.7, 4.45), [a0, a1] = m(4.0, 4.35);
  part('chrome', [h0, 3.3, 3.65], [h1, 4.7, 4.45], { sx, bone: 'frame', hinge: true });
  part('chrome', [a0, 3.8, 0.0], [a1, 4.2, 2.4], { sx, bone: 'frame', arm: true });
  part('chrome', [a0, 3.65, -2.6], [a1, 4.05, 0.1], { sx, bone: 'frame', arm: true });
  part('chrome', [a0, 3.15, -3.0], [a1, 4.05, -2.45], { sx, bone: 'frame', arm: true });
}

// ---- sweep planes: one texture region over both lenses, each lens plane shows its half -----------------------
const SW = { x0: -4.25, x1: 4.25, y0: LY[0], y1: LY[1] };
const SWH = Math.round((SW.y1 - SW.y0) / px) * px;

// ---- atlas ------------------------------------------------------------------------------------------------------
// Each cube draws only the faces that can be seen (a lens plate: its front only; a rim: front + the four edges),
// every face in its own rectangle with a 1 texel gutter that repeats the face's edge colour. Row cubes are
// thin strips, so without the gutter the sampler picked up the neighbouring strip at a row seam and drew hairline
// cracks across the lenses.
const NEED = c => ({
  glass: ['south'], rim: ['south', 'up', 'down', 'east', 'west'], chrome: ['north', 'east', 'south', 'west', 'up', 'down'],
  sglass: [c.sx > 0 ? 'east' : 'west'], srim: [c.sx > 0 ? 'east' : 'west', 'up', 'down', 'south', 'north'],
})[c.role];
const GUT = 1;
const faceDims = (c, f) => {
  const [w, h, d] = [0, 1, 2].map(i => Math.round((c.to[i] - c.from[i]) * S));
  return { north: [w, h], south: [w, h], east: [d, h], west: [d, h], up: [w, d], down: [w, d] }[f];
};
const items = [];
parts.forEach((c, i) => { for (const f of NEED(c)) { const [fw, fh] = faceDims(c, f); items.push({ c, f, fw, fh, i }); } });
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

// ---- polished chrome -------------------------------------------------------------------------------------------
// Chrome reads as a tiny environment: sky above, a hard dark horizon, ground bounce below (see rimTexel and paintBar).
// numeric outward normal and distance in from the outer edge of a scalar shape field (rho < 1 inside)
function edgeInfo(field, u, w) {
  const h = 0.01, gu = (field(u + h, w) - field(u - h, w)) / (2 * h), gw = (field(u, w + h) - field(u, w - h)) / (2 * h);
  const g = Math.hypot(gu, gw) || 1e-6;
  return { dOut: (1 - field(u, w)) / g, nu: gu / g, nw: gw / g };
}
const lensEdge = (lx, ly) => edgeInfo((u, w) => rhoS(u, w), lx, ly);
const sideEdge = (z, y) => edgeInfo((u, w) => sideRho(u, w), z, y);
// A 3 texel wire, outside in: one dark outline texel, then a smooth steel ramp that follows the light (bright where the
// wire faces up and to the left, mid at the sides, darker underneath), a step darker on the inner texel. One highlight.
// k = how many texels in from the pixel silhouette (0 = the outline texel): measured on the texel grid, not on the
// smooth curve, so the outline follows the stair steps exactly instead of breaking into a checker.
const texDepth = (inside, u, w) => { for (let n = 1; n <= 2; n++) if (![[n, 0], [-n, 0], [0, n], [0, -n]].every(([a, b]) => inside(u + a * px, w + b * px))) return n - 1; return 2; };
function rimTexel(g, hl, k) {
  const lit = 0.85 * g.nw + 0.5 * hl * g.nu;
  if (k === 0) return pick(C, 0.5);                                       // the one outline, outside only
  if (k === 1 && lit > 0.93) return pick(C, 6);                           // the single top-left highlight
  return pick(C, clamp(3.2 + 1.4 * lit, 2.2, 4.6) - (k === 2 ? 0.25 : 0));   // otherwise a smooth ramp, a step darker inside
}
function paintRim(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    v.set(x, y, f === 'south' ? rimTexel(lensEdge(X - c.sx * CX, Y - CY), HLX, texDepth(inOuter, X - c.sx * CX, Y - CY)) : pick(C, 0.8));   // every stair-step face is the dark contour, so the outline is one clean line
  }
}
function paintSideRim(v, f, c) {
  const out = c.sx > 0 ? 'east' : 'west';
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [, Y, Z] = world(c, f, x, y);
    v.set(x, y, f === out ? rimTexel(sideEdge(Z, Y), 1, texDepth((z, y) => sideRho(z, y) < 1, Z, Y)) : pick(C, 1.5));
  }
}

// ---- smoked gunmetal mirror lens ---------------------------------------------------------------------------------
// The body is a dark gunmetal mirror: a brighter steel sky over a hard horizon and a darker ground, darkening toward
// the rim. The ONLY hue is one diagonal sheen band per lens (pink -> cyan -> lime across its width, soft at both
// edges), plus a thin pale reflection streak beside it. Every value is quantised so gradients are clean bands.
const GM = ['#06080d', '#0b0f17', '#121824', '#1b2434', '#26324a', '#364660'];
const HLX = -1;                                                           // the highlight sits at the lens's top-left as seen from the front
const q = (v, n) => Math.round(v * n) / n;
const BAND = { d0: 0.15, w: 1.5 };
function lensBody(lx, ly, dIn) {
  const { c } = shape(lx), up = ly - c;
  const sky = up > -0.1;
  let lv = sky ? 3.6 + 1.4 * clamp((up + 0.1) / 1.3) - 0.9 * clamp((lx / A + 1) / 2) : 1.0 + 1.2 * clamp((up + 1.3) / 1.2);
  lv -= 1.6 * (1 - smoothstep(0.0, 0.22, dIn));                           // smoke near the rim
  if (sky && up > 0.1 && dIn < 0.1) lv -= 1;                              // lip shadow under the top rim
  return pick(GM, lv);
}
function lensColor(lx, ly) {
  const dIn = lensEdge(lx, ly).dOut - TH;
  let col = lensBody(lx, ly, dIn);
  const d = lx - 0.55 * ly - BAND.d0, u = d / BAND.w + 0.5;
  if (u > 0 && u < 1) {
    const a = q(1 - Math.abs(2 * u - 1) ** 1.5, 5) * 0.85 * smoothstep(0.0, 0.2, dIn + 0.05);
    col = mix(col, sheenAt(q(u, 6)), a);
  }
  const d2 = d - 1.3;                                                     // a thin pale streak ahead of the band
  if (d2 > -0.07 && d2 < 0.07 && dIn > 0.12) col = mix(col, '#f2f6ff', 0.34);
  return toHex(col);
}
function paintGlass(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    v.set(x, y, f === 'south' ? lensColor(X - c.sx * CX, Y - CY) : f === 'up' ? K[2] : K[1]);
  }
}
function paintSideGlass(v, f, c) {
  const out = c.sx > 0 ? 'east' : 'west';
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [, Y, Z] = world(c, f, x, y);
    let col = K[1];
    if (f === out) {
      const dIn = sideEdge(Z, Y).dOut - TS, up = Y - SC;
      let lv = up > -0.06 ? 3.0 + 1.2 * clamp(up / 0.7) : 1.0 + 1.0 * clamp((up + 0.8) / 0.75);
      lv -= 1.5 * (1 - smoothstep(0, 0.2, dIn));
      col = pick(GM, lv);
    }
    v.set(x, y, col);
  }
}

// ---- chrome bars: bridge, corner posts, arms ---------------------------------------------------------------------
// Rows top to bottom: outline, near-white highlight, mid steel, hard dark horizon, steel ground, a light bounce line.
function paintBar(v, f, c) {
  const outer = (c.sx > 0 && f === 'east') || (c.sx < 0 && f === 'west');
  const bias = { up: 0, south: 0.3, east: 0.5, west: -0.6, north: -0.3, down: -1.2 }[f] ?? 0;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (f === 'up') s = 4.2;
    else if (f === 'down') s = 1.2;
    else {
      const yf = v.h > 1 ? y / (v.h - 1) : 0;
      if (v.h >= 6) s = y === 0 ? 0.6 : yf < 0.26 ? 4.8 : yf < 0.42 ? 4 : yf < 0.62 ? 0.7 : yf < 0.86 ? 2.6 : 3.9;
      else if (v.h >= 4) s = y === 0 ? 4.6 : y === v.h - 1 ? 3.4 : yf < 0.6 ? 1.2 : 2.6;
      else s = y === 0 ? 4.6 : y === v.h - 1 ? 3.4 : 1.2;
      s += (s > 1.5 && s < 5.5 ? bias : 0) + (outer && s > 1.5 && s < 5.5 ? 0.4 : 0);
    }
    if (c.hinge && (f === 'east' || f === 'west')) {
      const [, , Z] = world(c, f, x, y);
      if (Math.abs(Z - 3.8125) < px * 0.6) s = 0.8;                          // the hinge seam
      if (Math.abs(Z - 4.1875) < px * 0.6 && Math.abs(y - (v.h - 1) / 2) < 1.2) s = 6;   // a pin head catching the light
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

const PAINT = { rim: paintRim, glass: paintGlass, chrome: paintBar, srim: paintSideRim, sglass: paintSideGlass };
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
  meta: { id: 'mask-shades', name: 'Chrome Shades', kind: 'mask', cents: 699, blurb: 'Y2K bug-eye wraparound shades: smoked gunmetal mirror lenses with one pink, cyan and lime sheen band, in polished chrome. A glint sweeps across now and then.' },
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
