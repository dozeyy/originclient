// Frost Goggles: big ski/snow goggles that hug the whole face. ONE wide mirrored lens (cool silver-blue mirror: a
// pale sky, a curved horizon with a bright reflection band above it, dark ground, two slanted glare streaks) whose
// edges shift through an ice-cyan -> violet -> pink iridescence, set in a thick MATTE-BLACK foam frame with a nose
// arch, five vent slots along the brow and foam wings that wrap the temples. A black elastic strap (fine ribs,
// two dull woven edge stripes) runs round the head, with a small chrome tab on each side carrying the Origin mark.
//
// Built for its own silhouette (a wide soft rectangle, nothing like the Shades' two ovals or the Visor's band):
// the foam is stacked rows of a rounded rectangle minus the lens and the nose arch; the lens is the same shape in
// three depth steps (centre proud, ends wrapping back) so the mirror reads curved, not flat.
//
// Light: top-front-left of the PLAYER (+x, +y, +z). Texels are painted from their position in the cube frame, so
// the mirror, foam edges and the strap run continuously across the row cubes. Every face has its own atlas
// rectangle with a 1 texel gutter (no hairline cracks between thin strips).
import { clamp, smoothstep, mix, toHex, hash2 } from '../../lib/px.mjs';
import { newTexture, paintCube } from '../../lib/model.mjs';
import { drawMark } from '../../lib/marks.mjs';

const S = 8, px = 1 / S;

// ---- ramps -----------------------------------------------------------------------------------------------
// matte foam: near-black, cool shadows, a faint cool rim light on the top edge only (matte = low contrast)
const F = ['#040507', '#0a0c10', '#12151b', '#1b1f27', '#282d38', '#3d4452'];
// the mirror, 7 steps: deep blue shadow -> silver-blue body -> a pale near-white sky (barely warm at the top)
const M = ['#101a29', '#22344b', '#3b5572', '#5f7f9e', '#8eaac4', '#c3d6e8', '#f1f7fd'];
// elastic strap: matte black with a woven grey stripe
const E = ['#06070a', '#0d0f13', '#15181d', '#1f2228', '#2b2f37', '#5a606b'];
// polished chrome for the tab (same ramp as the Shades)
const C = ['#0b0d12', '#232731', '#454c5a', '#79828f', '#b0b8c3', '#e4e8ee', '#fff7ea'];
// iridescent edge: ice cyan -> violet -> pink
const ICE = '#62e8ff', VIOLET = '#a08cff', ROSE = '#ff80cf';
const iridAt = h => (h < 0.5 ? mix(ICE, VIOLET, h * 2) : mix(VIOLET, ROSE, (h - 0.5) * 2));
const pick = (R, v) => R[clamp(Math.round(v), 0, R.length - 1)];
const q = (v, n) => Math.round(v * n) / n;
// fractional ramp index -> colour, quantised to quarter steps (clean parallel bands, no dither)
const dpick = (R, v) => {
  const p = clamp(Math.round(v * 4) / 4, 0, R.length - 1), lo = Math.floor(p), hi = Math.min(R.length - 1, lo + 1);
  return mix(R[lo], R[hi], p - lo);
};

// ---- 2D shapes (head frame x, y; y up) ---------------------------------------------------------------------
const roundRect = (x, y, hw, y0, y1, rt, rb) => {
  if (Math.abs(x) > hw || y < y0 || y > y1) return false;
  const dx = Math.abs(x) - (hw - (y > (y0 + y1) / 2 ? rt : rb));
  const r = y > (y0 + y1) / 2 ? rt : rb, dy = y > (y0 + y1) / 2 ? y - (y1 - r) : (y0 + r) - y;
  return dx <= 0 || dy <= 0 || Math.hypot(dx, dy) <= r;
};
const inEllipse = (x, y, cx, cy, rx, ry) => ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 < 1;
const NOSE_Y = 1.85;
const foamOuter = (x, y) => roundRect(x, y, 4.3, 1.85, 5.85, 1.3, 1.0) && !inEllipse(x, y, 0, NOSE_Y, 1.05, 1.2);
const lensIn = (x, y, g = 0) => roundRect(x, y, 3.65 + g, 2.5 - g, 5.0 + g, 0.95, 0.8) && !inEllipse(x, y, 0, NOSE_Y, 1.75 - g, 1.75 - g);
const foamAt = (x, y) => foamOuter(x, y) && !lensIn(x, y);
// the temple wing, in (z, y)
const wingIn = (z, y) => z >= 2.75 && z <= 4.5 && y >= 2.375 && y <= 5.375 && (() => {
  const r = 0.75, dz = Math.max(2.75 + r - z, z - (4.5 - r), 0), dy = Math.max(2.375 + r - y, y - (5.375 - r), 0);
  return Math.hypot(dz, dy) <= r;
})();

// ---- geometry ---------------------------------------------------------------------------------------------
const g8 = v => Math.round(v * 8) / 8;                                   // every edge sits on a texel (1/8 px)
const parts = [];
const part = (role, from, to, extra = {}) => { const c = { from: from.map(g8), to: to.map(g8), role, ...extra }; parts.push(c); return c; };
// Row cubes of a 2D mask: cells of 1/8 px, consecutive rows with the same runs merged into one taller cube.
// `tier(xc)` splits runs where it changes (the lens depth steps).
function runs(mask, X0, X1, Y0, Y1, tier = () => 0) {
  const out = [];
  let open = new Map();
  for (let y = Y0; y < Y1 - 1e-9; y += px) {
    const row = [];
    let start = null, t0 = null;
    for (let x = X0; x <= X1 + 1e-9; x += px) {
      const xc = x + px / 2, on = x < X1 - 1e-9 && mask(xc, y + px / 2), t = on ? tier(xc) : null;
      if (start !== null && (!on || t !== t0)) { row.push([start, x, t0]); start = null; }
      if (on && start === null) { start = x; t0 = t; }
    }
    const next = new Map();
    for (const [a, b, t] of row) {
      const key = `${a}|${b}|${t}`, prev = open.get(key);
      if (prev) { prev.yb = y + px; next.set(key, prev); } else { const cu = { a, b, t, ya: y, yb: y + px }; out.push(cu); next.set(key, cu); }
    }
    open = next;
  }
  return out;
}
const TIER_Z = [4.625, 4.5, 4.375];                                       // lens front by depth step: centre, middle, ends
const tierOf = x => (Math.abs(x) < 1.6 ? 0 : Math.abs(x) < 2.8 ? 1 : 2);
const FOAM_Z = [3.875, 4.75], LENS_BACK = 3.875;
for (const cu of runs(foamAt, -4.5, 4.5, 1.8, 5.9)) part('foam', [cu.a, cu.ya, FOAM_Z[0]], [cu.b, cu.yb, FOAM_Z[1]], { sx: 0 });
// the lens runs a texel under the foam so no gap shows between them
for (const cu of runs((x, y) => lensIn(x, y, px), -3.9, 3.9, 2.4, 5.1, tierOf)) part('glass', [cu.a - (cu.t < 2 ? px : 0), cu.ya, LENS_BACK], [cu.b + (cu.t < 2 ? px : 0), cu.yb, TIER_Z[cu.t]], { sx: 0 });   // taller tiers overlap the next one out by a texel: no crack at the step
for (const sx of [1, -1]) {
  // temple wing: rows of a rounded rectangle in (z, y), one slab thick
  for (const cu of runs(wingIn, 2.5, 4.75, 2.3, 5.45)) {
    const [x0, x1] = sx > 0 ? [4.0, 4.625] : [-4.625, -4.0];
    part('wing', [x0, cu.ya, cu.a], [x1, cu.yb, cu.b], { sx });
  }
  const [s0, s1] = sx > 0 ? [4.0, 4.375] : [-4.375, -4.0];
  part('strap', [s0, 3.375, -4.375], [s1, 4.625, 2.9], { sx });
  const [t0, t1] = sx > 0 ? [4.375, 4.625] : [-4.625, -4.375];
  part('tab', [t0, 2.75, 0.25], [t1, 5.25, 2.75], { sx });
}
part('strap', [-4.375, 3.375, -4.375], [4.375, 4.625, -4.0], { sx: 0 });

// ---- atlas ------------------------------------------------------------------------------------------------------
// Each cube draws only the faces that can be seen, every face in its own rectangle with a 1 texel gutter that
// repeats the face's edge colour (thin row strips otherwise pick up their atlas neighbour at a seam).
const NEED = c => ({
  foam: ['south', 'up', 'down', 'east', 'west'],
  glass: ['south', 'east', 'west'],
  wing: ['south', 'north', 'up', 'down', c.sx > 0 ? 'east' : 'west'],
  strap: ['north', 'south', 'east', 'west', 'up', 'down'],
  tab: ['north', 'south', 'east', 'west', 'up', 'down'],
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
let best = null;
for (let W = 128; W <= 512; W += 8) {
  const rows = Math.ceil(pack(W) / S);
  if (W * rows * S * 4 <= 4 * 1024 * 1024 && (!best || W * rows < best.W * best.rows)) best = { W, rows };
}
if (!best) throw new Error('mask-goggles: no texture size fits the 4 MB budget');
pack(best.W);
const TEX = [best.W / S, best.rows];
for (const it of items) (it.c.faces ??= {})[it.f] = [it.x / S, it.y / S, (it.x + it.fw) / S, (it.y + it.fh) / S];

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
// distance (model px) from a point inside the lens to its edge, searched out to 6 texels
function lensEdgeDist(X, Y) {
  let d = 6;
  for (let dx = -6; dx <= 6; dx++) for (let dy = -6; dy <= 6; dy++) {
    const r = Math.hypot(dx, dy);
    if (r < d && !lensIn(X + dx * px, Y + dy * px)) d = r;
  }
  return d * px;
}

// ---- foam -------------------------------------------------------------------------------------------------------
const SLOTS = [-2.6, -1.3, 0, 1.3, 2.6];
function paintFoam(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    let s;
    if (f !== 'south') {
      s = { up: 4, east: 3, west: 1, down: 0, north: 1 }[f];
      if (f === 'east' || f === 'west') s = (f === 'east' ? 3 : 1.4) - 0.6 * (1 - smoothstep(1.85, 5.85, Y));
    } else {
      const t = (Y - 1.85) / 4;
      s = 2 + 1.2 * t + 0.3 * (X / 4.3);                                 // a soft top-lit gradient (matte: a step across the whole face)
      s = Math.round(s * 2) / 2;
      // foam pores: sparse single texels a step lighter or darker (a material read, kept faint)
      const hv = hash2(Math.round((X + 8) * S), Math.round((Y + 8) * S), 11);
      if (hv > 0.94) s += 1; else if (hv < 0.05) s -= 1;
      // silhouette: a cool rim light along the top, a dark underside, the lit +x edge a step up
      if (!foamOuter(X, Y + px)) s = 5;
      else if (!foamOuter(X, Y - px)) s = 0;
      else if (!foamOuter(X + px, Y)) s = 4;
      else if (!foamOuter(X - px, Y)) s = 1;
      // the bezel around the lens: bottom edge catches light, top edge falls into shadow
      else if (lensIn(X, Y + px)) s = 4;
      else if (lensIn(X, Y - px)) s = 0;
      else if (lensIn(X + px, Y)) s = 3;
      else if (lensIn(X - px, Y)) s = 1;
      // vent slots along the brow: dark slot, mesh in the middle, a lit lip under it
      for (const cx of SLOTS) {
        if (Math.abs(X - cx) < 0.4) {
          const ix = Math.floor((X - cx + 0.4) * S), iy = Math.floor((Y - 5.25) * S);
          if (iy === 3) s = 4;                                           // lit lower lip
          else if (iy === -1) s = 1;                                     // shadow above
          else if (iy === 0 || iy === 2) s = 0;
          else if (iy === 1) s = (ix + iy) % 2 ? 1 : 3;                  // the mesh
        }
      }
    }
    v.set(x, y, pick(F, s));
  }
}
function paintWing(v, f, c) {
  const outer = (c.sx > 0 && f === 'east') || (c.sx < 0 && f === 'west');
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s = { up: 4, down: 0, north: 1, south: 2.5, east: 3, west: 1 }[f];
    if (outer || f === 'south') {
      s = 2 + (y === 0 ? 1.5 : y === 1 ? 0.5 : 0) - (y >= v.h - 2 ? 1 : 0);
      const [X, Y, Z] = world(c, f, x, y);
      const hv = hash2(Math.round((X + 8) * S) + Math.round(Z * S), Math.round((Y + 8) * S), 17);
      if (hv > 0.94) s += 1; else if (hv < 0.05) s -= 1;
    }
    v.set(x, y, pick(F, s));
  }
}

// ---- mirror lens ------------------------------------------------------------------------------------------------
// Sky above a curved horizon (the curve is the wrap), a two texel reflection band right above it, a crisp dark
// horizon line, dark ground with a faint bounce along the lower edge; the +x end half a step brighter, both ends
// falling off. The edge ring blends in the ice -> violet -> rose iridescence; two slanted glare streaks up-left.
const horizon = X => 3.55 + 0.045 * X * X;
function lensColor(X, Y) {
  const hz = horizon(X), end = smoothstep(2.2, 3.7, Math.abs(X)), lit = 0.35 * smoothstep(-3.5, 3.5, X);
  let i;
  if (Y >= hz + 2 * px) i = 3.3 + 2.0 * smoothstep(hz, 5.0, Y) - 1.1 * end + lit;                  // sky, paler toward the top
  else if (Y >= hz + px) i = 5.1 - 1.4 * end + lit * 0.5;                                          // the reflection band, two texels:
  else if (Y >= hz) i = 5.8 - 1.7 * end + lit * 0.5;                                               // the lower one brightest
  else if (Y >= hz - px) i = 0.2;                                                                  // the horizon line
  else {
    const g = (Y - 2.5) / Math.max(0.3, hz - 2.5);                                                 // 0 at the bottom edge .. 1 at the horizon
    i = 1.3 + 0.8 * g + (g < 0.18 ? 0.8 : 0) - 0.4 * end + lit * 0.6;                              // ground, a bounce near the edge
  }
  let col = dpick(M, i);
  const qq = X * 0.8 - (Y - 3.75) * 0.6;
  const inSky = Y > hz + px;
  if (inSky && qq > -2.35 && qq < -2.0) col = mix(col, '#f6faff', 0.55);                          // glare streaks, up-left
  else if (inSky && qq > -1.75 && qq < -1.62) col = mix(col, '#f6faff', 0.4);
  const d = lensEdgeDist(X, Y);
  if (d < 0.45) {
    const k = q(1 - smoothstep(0.02, 0.45, d), 5), h = q(clamp(0.5 + X / 7.4 + (Y < 3.3 ? 0.08 : 0)), 6);
    col = mix(col, iridAt(h), 0.78 * k);
  }
  if (Y > 4.7 && d < 0.2) col = mix(col, '#050a12', 0.5);                                         // the foam's shadow along the top
  if (X > 3.0 && d < 0.14) col = mix(col, '#050a12', 0.35);
  return toHex(col);
}
function paintGlass(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    v.set(x, y, lensColor(X, Y));   // the step between depth tiers is lens-coloured too, so no dark hairline shows
  }
}

// ---- strap -------------------------------------------------------------------------------------------------------
// Matte elastic: a lit top edge, two dull woven stripes, fine ribs across it every third texel, darker underside.
function paintStrap(v, f, c) {
  const outer = f === 'east' || f === 'west' || f === 'north' || f === 'south';
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (f === 'up') s = 3;
    else if (f === 'down') s = 0;
    else {
      const edge = y === 0 ? 4 : y === v.h - 1 ? 1 : 2;
      s = edge;
      const [, , Z] = world(c, f, x, y);
      const along = f === 'east' || f === 'west' ? Math.round(Z * S) : x;
      if (outer && y > 0 && y < v.h - 1) {
        if (along % 3 === 0) s += 0.8;                                  // ribs
        if (y === 2 || y === v.h - 3) s = 5;                            // woven stripes
      }
      if (f === 'west') s -= 0.6;
    }
    v.set(x, y, pick(E, s));
  }
}

// ---- chrome tab with the Origin mark ------------------------------------------------------------------------------
// A little polished plate (sky / horizon / ground bands, dark outline) with the mark engraved dark, drawn pixel by
// pixel at the size it appears (lib/marks, never resampled).
const MARK_SIZE = 16;
function paintTab(v, f, c) {
  const outer = (c.sx > 0 && f === 'east') || (c.sx < 0 && f === 'west');
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (f === 'up') s = 5;
    else if (f === 'down') s = 1;
    else {
      const yf = y / Math.max(1, v.h - 1);
      s = v.h >= 6 ? (yf < 0.3 ? 5 : yf < 0.7 ? 4.2 : yf < 0.9 ? 3.4 : 2.2) : 3;   // a calm plate (soft sky-to-ground steps) so the mark stays the focus
      if (outer) {
        if (x === 0 || y === 0 || x === v.w - 1 || y === v.h - 1) s = (y === 0 || x === 0) ? 3 : 1;          // plate outline: lit top-left, dark bottom-right
        if (x === 1 && y > 0 && y < v.h - 1) s += 0.5;
      } else s = 2;
    }
    v.set(x, y, pick(C, s));
  }
  if (outer) {
    const cx = v.w / 2, cy = v.h / 2;
    drawMark(v, cx, cy, MARK_SIZE, p => pick(C, 0.4 + 1.4 * (1 - p.light)), 1);
  }
}

const PAINT = { foam: paintFoam, glass: paintGlass, wing: paintWing, strap: paintStrap, tab: paintTab };
const strip = ({ role, sx, ...c }) => c;

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
  meta: { id: 'mask-goggles', name: 'Frost Goggles', kind: 'mask', cents: 799, blurb: 'Big snow goggles: one wide silver-blue mirror lens with an iridescent edge, matte foam, a black strap and a small chrome Origin tab.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'goggles', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.map(strip) }] },
  paint() { return staticLayer().clone(); },
};
