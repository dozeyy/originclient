// Visor Mask: a slim wraparound eye visor. Blue-grey tinted glass (bright top rim, sky gradient, a horizon reflection band, cool
// shadows), a stepped lower edge with a nose notch so the lens reads as a shaped visor and never a box,
// a thin gunmetal brow lip, wrap-around side glass, short temple arms and a slim elastic strap with a
// small clasp at the back. One light: a soft #E0E0E0 sweep that glides across the glass once every
// 2 s on an additive glow plane (the rest of the loop the glass is still).
//
// Light: top-front-left of the PLAYER (+x, +y, +z), the lineup convention (lib/model LIGHT).
// Texels are painted from their position in the cube frame, so the reflection and edges run
// continuously across the lens pieces. Everything hugs the head: the lens is 0.625 px proud of the
// face, the brow lip 0.75 px (z <= 4.75; FORMAT.md face limit 5).
import { clamp, smoothstep, mix, toHex } from '../../lib/px.mjs';
import { layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const TEX = [40, 16];
const FRAMES = 20, FPS = 10, SWEEP_FRAMES = 8;

// ---- ramps -----------------------------------------------------------------------------------------------
// tinted glass, 9 steps: near-black blue -> dark blue-grey (the body of the tint) -> a pale steel-blue that
// only the top rim and the horizon reflection reach. Cool throughout (shadows bluer, lights barely warm).
const G = ['#06080c', '#0b0f15', '#121923', '#1b2532', '#283748', '#3b5068', '#587490', '#8ea6bc', '#cfdbe6'];
// gunmetal: cool shadows -> neutral -> a warm-white specular (only on the top edge)
const M = ['#0a0b0e', '#181a1f', '#292c33', '#40444c', '#5f636b', '#8d8f92', '#c9c5bd'];
// strap elastic: matte, darker and flatter than the metal
const E = ['#08090b', '#111216', '#1a1c21', '#25272d', '#33353b'];
const pick = (R, v) => R[clamp(Math.round(v), 0, R.length - 1)];
const SWEEP = '#e0e0e0';

// ---- geometry (head frame: head cube x -4..4, y 0..8, z -4..4) ------------------------------------------
// Eyes sit at y 3..4. The lens spans y 2.75..4.75 (2.75..3.25 only beside the nose notch).
const parts = [];
const part = (role, from, to, extra = {}) => { const c = { from, to, uv: [0, 0], role, ...extra }; parts.push(c); return c; };
const LZ = [4.0, 4.625];
// ONE continuous band (never two lenses): full depth over the eyes, the lower edge rising in two
// steps toward the temples so the ends taper into the wrap.
// The pieces overlap by a texel (painted from world position, so overlaps are seamless): adjacent boxes
// that only touch leave hairline cracks where the skin shows through.
part('lens', [-4.375, 3.375, LZ[0]], [4.375, 4.75, LZ[1]]);
part('lens', [-3.5, 2.875, LZ[0]], [3.5, 3.5, LZ[1]]);
for (const sx of [1, -1]) {
  const m = (a, b) => (sx > 0 ? [a, b] : [-b, -a]);
  const [b0, b1] = m(3.375, 4.0);
  part('lens', [b0, 3.125, LZ[0]], [b1, 3.5, LZ[1]], { sx });
  // wraparound side glass, its front end sunk into the lens (the 1 texel left showing is a stepped chamfer)
  const [s0, s1] = m(4.0, 4.5);
  // ...then tapers toward the temple in two steps (the second overlaps the first by a texel)
  part('lensSide', [s0, 3.5, 3.25], [s1, 4.75, 4.125], { sx });
  part('lensSide', [s0, 3.75, 2.375], [s1, 4.75, 3.375], { sx, tail: true });
  // brow lip over the side glass
  const [w0, w1] = m(4.0, 4.625);
  part('brow', [w0, 4.625, 2.0], [w1, 5.0, 3.875], { sx });
  // temple arm, then the strap
  part('temple', [s0, 3.875, -0.75], [s1, 4.5, 2.5], { sx });
  const [t0, t1] = m(4.0, 4.25);
  part('strap', [t0, 4.0, -4.0], [t1, 4.375, -0.75], { sx });
}
part('brow', [-4.5, 4.625, 3.875], [4.5, 5.0, 4.75]);
part('strap', [-4.25, 4.0, -4.25], [4.25, 4.375, -4.0]);
part('clasp', [-0.625, 3.75, -4.5], [0.625, 4.625, -4.25]);
layoutCubes(parts, TEX, S);

// the sweep plane: one face just in front of the glass, under the brow lip, over the whole lens
const used = Math.max(...parts.map(c => { const [w, h, d] = [c.to[0] - c.from[0], c.to[1] - c.from[1], c.to[2] - c.from[2]]; return c.uv[1] + d + h; }));
const SW = { x0: -4.375, x1: 4.375, y0: 2.875, y1: 4.75, z: 4.6875 };
const SWU = [0, Math.ceil(used * S) / S];                              // its rectangle in the texture (model px)
TEX[1] = Math.max(TEX[1], Math.ceil(SWU[1] + (SW.y1 - SW.y0)));
const sweepCube = {
  from: [SW.x0, SW.y0, SW.z], to: [SW.x1, SW.y1, SW.z], glow: true,
  faces: { south: [SWU[0], SWU[1], SWU[0] + (SW.x1 - SW.x0), SWU[1] + (SW.y1 - SW.y0)] },
};

// is (X, Y) on the front of the lens? (the union of the lens pieces' fronts)
const lensFront = (X, Y) => parts.some(c => c.role === 'lens' && X >= c.from[0] && X < c.to[0] && Y >= c.from[1] && Y < c.to[1]);

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

// ---- glass ------------------------------------------------------------------------------------------------
// Top to bottom: a dark brow shadow, a thin bright top rim, a blue-grey sky that deepens toward the horizon,
// a crisp two-texel reflection band right above the horizon (the premium tint look), a dark edge line, then
// dark ground fading to a faint bounce near the lower edge. The horizon curves up toward the wrap (the lens
// is curved), the ends fall off darker, the +x side (facing the light) a half step brighter.
// glassStep returns a fractional ramp index, a continuous function of the world position (so it runs
// seamlessly across the lens pieces); dpick turns it into smooth colour bands.
const horizon = a => 3.4 + 0.02 * a * a;
function glassStep(a, Y) {
  const h = horizon(a), lit = 0.4 * smoothstep(-5, 5, a), end = smoothstep(2.6, 5.6, Math.abs(a));   // 0 centre .. 1 at the wrap ends
  if (Y >= 4.625) return 1;                                              // in the brow's shadow
  if (Y >= 4.5) return 7.7 - 2.2 * end + lit * 0.5;                       // the thin bright top rim
  if (Y >= h + 2 * px) {                                                 // sky: lighter under the rim, deepening toward the horizon
    const t = (4.5 - Y) / (4.5 - h - 2 * px);
    return 6.0 - 2.2 * t - 1.4 * end + lit;
  }
  if (Y >= h + px) return 7.4 - 2.4 * end + lit * 0.5;                   // the reflection band: two texels, the upper one a touch softer...
  if (Y >= h) return 8.0 - 2.8 * end + lit * 0.5;                        // ...and the lower one brightest, right on the horizon
  if (Y >= h - px) return 0.5;                                           // the horizon edge: a crisp dark line
  const g = Y - (2.875 + 0.012 * a * a);                                   // ground: dark, a faint cool bounce band near the bottom
  return (g < 0.2 ? 2.9 : g < 0.45 ? 2.1 : g < 0.7 ? 1.6 : 1.2) + lit * 0.6 - 0.4 * end;
}
// fractional ramp index -> colour: interpolated between the ramp steps and quantised to 4 sub-steps each, so a
// gradient is a run of clean parallel bands (no dither, no grain). u, w are unused (kept for the call sites).
const dpick = (R, v, u, w) => {
  const p = clamp(Math.round(v * 4) / 4, 0, R.length - 1), lo = Math.floor(p), hi = Math.min(R.length - 1, lo + 1);
  return toHex(mix(R[lo], R[hi], p - lo));
};
function paintLens(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    let col;
    if (f === 'south') {
      const below = !lensFront(X, Y - px), side = !lensFront(X - px, Y) || !lensFront(X + px, Y);
      if (below) col = G[0];                                             // the lower edge: a dark outline...
      else if (!lensFront(X, Y - 2 * px)) col = G[3];                    // ...with a 1 px bevel catching light above it
      else if (side) col = G[2];
      else col = dpick(G, glassStep(X, Y), X, Y);
    } else if (f === 'down') col = G[0];
    else if (f === 'up') col = G[1];
    else col = G[1];                                                     // ends and the face against the head
    void Z;
    v.set(x, y, col);
  }
}
// side glass: the same reflection continued around the wrap
function paintLensSide(v, f, c) {
  const sx = c.sx, outer = (sx > 0 && f === 'east') || (sx < 0 && f === 'west');
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    let col;
    if (outer) {
      const a = sx * (4.375 + (4.0 - Z));                                // continue the wrap coordinate
      const yb = c.from[1], back = Z < c.from[2] + px && c.tail;
      const lower = !c.tail && Z < 3.375 ? 3.75 : yb;                                    // under the tail piece's step
      col = back ? G[0] : Y < yb + px ? G[0] : Y < yb + 2 * px ? G[3] : dpick(G, glassStep(a, Y) - 0.6, Z, Y);
      void lower;
    } else if (f === 'south') col = Y < 3.5 + px ? G[0] : dpick(G, glassStep(sx * 4.4, Y), X, Y);     // the 1 texel chamfer
    else col = G[0];
    void X;
    v.set(x, y, col);
  }
}

// ---- metal: brow lip, temple, clasp -------------------------------------------------------------------------
// Flat planes with a crisp specular top edge and a dark under-edge (no brushed streaks, no light frame).
function paintMetal(v, f, c) {
  const bias = { up: 1.5, south: 0.6, east: 0.4, west: -0.2, north: 0, down: -1.5 }[f] ?? 0;
  const role = c.role;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    let s = 2.6 + bias * 0.6;
    if (f === 'up') {
      s = 4 + (X > 0 ? 0.3 : 0);
      if (y === v.h - 1 || (role === 'brow' && !c.sx && y === v.h - 1)) s = 6;              // front edge of the top: the specular line
      if (y === 0) s = 3;
    } else if (f === 'down') s = 0.8;
    else {
      if (y === 0) s = f === 'south' || ((c.sx > 0 && f === 'east') || (c.sx < 0 && f === 'west')) ? 4.4 : 3.6;   // top edge catches the light
      else if (y === v.h - 1) s = 0.6;                                                          // under-edge
      else if (role === 'temple') {
        s = 2.6 + bias * 0.6 - (y > v.h / 2 ? 0.7 : 0);
        if (y === 2 && Z > -0.4 && Z < 2.2) s = 1.2;                                                // one fine groove along the arm
      } else if (role === 'clasp') {
        const ux = x / (v.w - 1), uy = y / (v.h - 1);
        s = 2.8 + bias * 0.4 - 0.9 * uy + (ux < 0.2 ? 0.4 : 0);
        if (f === 'north' && x >= 3 && x <= v.w - 4 && y === Math.floor(v.h / 2)) s = 0.5;     // a single seam across the clasp
      } else s = 2.0 + bias * 0.5;                                                              // the brow's face below the top edge
    }
    v.set(x, y, pick(M, s));
  }
}
// ---- strap: matte elastic, a lit top edge, a fine centre rib ---------------------------------------------------
function paintStrap(v, f) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s = f === 'up' ? 3 : f === 'down' ? 0 : y === 0 ? 4 : y === v.h - 1 ? 0 : y === Math.floor(v.h / 2) ? 1 : 2;
    if ((f === 'east' || f === 'west') && v.w < 4) s = 1;
    v.set(x, y, pick(E, s));
  }
}

// ---- the sweep (frames) --------------------------------------------------------------------------------------
// A diagonal band: a bright 2 px core, a softer trailing band in two steps and one faint thin echo.
// Additive, so each step is a grey whose brightness is what it adds to the glass.
function paintSweep(tex, frame) {
  const base = SWU.map(n => n * S), w = (SW.x1 - SW.x0) * S, h = (SW.y1 - SW.y0) * S;
  if (frame >= SWEEP_FRAMES) return;
  const p = -6.2 + (12.4 * (frame + 0.5)) / SWEEP_FRAMES;               // band centre along the lens (model px)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const X = SW.x0 + (x + 0.5) * px, Y = SW.y1 - (y + 0.5) * px;
    if (!lensFront(X, Y) || !lensFront(X, Y - px) || Y >= 4.625) continue;
    const d = X + 0.55 * (Y - 3.75) - p;                                 // distance across the slanted band
    let k = 0;
    if (d >= -0.125 && d < 0.125) k = 0.6;                                // core
    else if (d >= -0.5 && d < -0.125) k = 0.3;                            // trailing soft band, two steps
    else if (d >= -1.0 && d < -0.5) k = 0.13;
    else if (d >= 0.375 && d < 0.5) k = 0.14;                             // a faint thin echo ahead
    if (k) tex.set(base[0] + x, base[1] + y, [224, 224, 224, Math.round(k * 255)]);
  }
}

const PAINT = { lens: paintLens, lensSide: paintLensSide, brow: paintMetal, temple: paintMetal, clasp: paintMetal, strap: paintStrap };
const strip = ({ role, sx, tail, ...c }) => c;

let staticTex = null;
function staticLayer() {
  if (staticTex) return staticTex;
  const t = newTexture({ texSize: TEX, scale: S });
  for (const c of parts) paintCube(t, c, S, (v, f) => PAINT[c.role](v, f, c));
  return (staticTex = t);
}

export default {
  meta: { id: 'mask-visor', name: 'Visor Mask', kind: 'mask', cents: 699, blurb: 'A slim wraparound visor of smoked glass in a gunmetal frame. A soft light sweeps across it now and then.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'visor', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: [...parts.map(strip), sweepCube] }] },
  anim: { frames: FRAMES, fps: FPS },
  paint(frame = 0) {
    const t = staticLayer().clone();
    paintSweep(t, frame);
    return t;
  },
};
