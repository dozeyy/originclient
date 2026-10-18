// Racer Shield: a rimless single-piece sport shield (Oakley M-frame / Sutro style). One tall, bright,
// iridescent prizm mirror lens wraps from brow to cheekbone and round the temples; a slim chrome bar runs
// along its top edge and chrome earstems carry it back to the ears. Unlike the Visor Mask (a slim, smoked,
// framed visor) it is taller, mirrored, rimless and bright.
//
// The lens is a smooth curve built from 0.5 px slabs, each rotated about its front-start corner so the front
// surface is continuous and the overlap hides at the back. Every texel is painted from its position on the
// lens (arc length `a` from the nose, height `Y`), so the sheen, the highlight streaks and the edge bevel
// run seamlessly across the slabs. Hue lives only in the lens (pink-gold -> magenta -> cyan -> a lime
// whisper at the far edge); the metal is neutral chrome with a faint reflection of that sheen.
//
// Light: top-front-left of the PLAYER (+x, +y, +z). A soft white glint glides across the mirror once per
// loop (frames 0-9 of 20); the rest of the time the sheen sits still.
//
// Clearance: lens front z <= 4.9, chrome bar z <= 4.97 (face limit 5); earstems hug the head sides.
import { clamp, smoothstep, mix, toHex } from '../../lib/px.mjs';
import { layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const TEX = [40, 16];
const FRAMES = 20, FPS = 10, SWEEP_FRAMES = 11;

// ---- the lens curve -----------------------------------------------------------------------------------------
const N = 12, STEP = 0.5, ZC = 4.92, PHI_MAX = 90, PHI_POW = 2.4, T = 0.375;
const SEG = [];                                                          // one entry per slab, centre -> temple
{
  let x = 0, z = ZC;
  for (let i = 0; i < N; i++) {
    const phi = (PHI_MAX * ((i + 0.5) / N) ** PHI_POW * Math.PI) / 180;
    SEG.push({ i, a0: i * STEP, x, z, phi, deg: (phi * 180) / Math.PI });
    x += STEP * Math.cos(phi); z -= STEP * Math.sin(phi);
  }
}
const q8 = v => Math.round(v * S) / S;
// Lens outline over the arc (nose at a = 0): top edge a shallow arch, bottom edge rising toward the temples,
// with a nose notch cut up into the centre. Both are quantised to 1/8 px so neighbouring slabs differ by
// at most a texel or two.
const topOf = a => q8(5.75 - 0.021 * a * a);
const baseBottom = a => 1.875 + 0.043 * a * a;
const bottomOf = a => q8(Math.max(baseBottom(a), 3.0 - 1.2 * smoothstep(0.4, 1.45, Math.abs(a))));
const inLens = (a, Y) => Math.abs(a) < N * STEP && Y >= bottomOf(a) && Y < topOf(a);

// ---- ramps --------------------------------------------------------------------------------------------------
// Prizm hue stops, warm to cool: gold, peach, rose pink, hot magenta, violet, sky blue, cyan, mint, lime.
const HUES = ['#f8c862', '#f69c6e', '#f2739f', '#dc54c8', '#9270f2', '#4a9ef6', '#2cdeee', '#66f0ba', '#b6f65e'];
const INK = '#14101f', GLINT = '#fff7ee';
// per hue, 7 lit steps: deep tinted shadow .. the hue .. a pearly highlight (shadows cooler, lights warmer)
const HR = HUES.map(h => [mix(h, INK, 0.86), mix(h, INK, 0.68), mix(h, INK, 0.42), mix(h, INK, 0.16), h, mix(h, GLINT, 0.42), mix(h, GLINT, 0.78)]);
// chrome, cool shadows -> warm white specular
const CH = ['#0d0f14', '#1d2129', '#323843', '#505865', '#7e8795', '#b3bbc7', '#e4e8ec', '#ffffff'];
const RUB = ['#08090c', '#101216', '#191c21', '#24282e'];                  // rubber tips
const pickC = (R, v) => R[clamp(Math.round(v), 0, R.length - 1)];
function lerpStep(R, v) {                                                   // fractional ramp index -> colour
  const p = clamp(v, 0, R.length - 1), lo = Math.floor(p), hi = Math.min(R.length - 1, lo + 1);
  return mix(R[lo], R[hi], p - lo);
}

// ---- geometry -----------------------------------------------------------------------------------------------
const parts = [];
// A slab of the curve on one side. `off` pushes the front surface out along its normal, `thick` its depth.
function slab(role, sx, sg, y0, y1, off, thick, extra = {}) {
  const nx = sx * Math.sin(sg.phi), nz = Math.cos(sg.phi);
  const fx = sx * sg.x + off * nx, fz = sg.z + off * nz;
  const [xa, xb] = sx > 0 ? [fx, fx + STEP] : [fx - STEP, fx];
  parts.push({ role, sx, sg, from: [xa, y0, fz - thick], to: [xb, y1, fz], uv: [0, 0], rotation: { origin: [fx, 0, fz], angle: [0, sx * sg.deg, 0] }, ...extra });
}
for (const sx of [1, -1]) for (const sg of SEG) {
  const am = sg.a0 + STEP / 2, y0 = bottomOf(am), y1 = topOf(am);
  slab('lens', sx, sg, y0, y1, 0, T);
  slab('bar', sx, sg, y1 - 0.125, y1 + 0.25, 0.0625, 0.5);
}
// earstems: from the lens ends back past the ear, angled in to touch the head; the last stretch is a rubber tip
const END = SEG[N - 1];
const EX = END.x + STEP * Math.cos(END.phi), EZ = END.z - STEP * Math.sin(END.phi);   // front-end of the last slab
for (const sx of [1, -1]) {
  const xo = EX - 0.04, xi = xo - T;                                      // outer / inner x of the arm where it leaves the lens
  const [a, b] = sx > 0 ? [xi, xo] : [-xo, -xi];
  parts.push({ role: 'stem', sx, from: [a, 4.25, EZ - 4.25], to: [b, 4.75, EZ + 0.125], uv: [0, 0], rotation: { origin: [sx * (xi + xo) / 2, 0, EZ], angle: [0, sx * 6, 0] } });
}
layoutCubes(parts, TEX, S);

// ---- local frame helpers --------------------------------------------------------------------------------------
// texel centre -> position on the cube surface in the cube's own (pre-rotation) frame; images read from outside.
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
// local x -> signed arc length along the lens (positive toward the player's left, +x)
const arcOf = (c, X) => (c.sx > 0 ? c.sg.a0 + (X - c.from[0]) : -(c.sg.a0 + (c.to[0] - X)));

// ---- the sheen ------------------------------------------------------------------------------------------------
// hue coordinate 0..1: warm at the upper-viewer-left, cool toward the lower-right, with a slow diagonal ripple
// so the bands are not plain stripes. `tilt` drifts the whole sheen a little as the glint passes.
function hueT(a, Y, tilt) {
  const t = 0.5 + 0.062 * a - 0.115 * (Y - 3.85) + 0.035 * Math.sin(a * 0.9 + Y * 1.3) + tilt;
  return clamp(t);
}
// brightness as a fractional index 0..6 into the hue ramp
function lensL(a, Y, glintP) {
  const top = topOf(Math.abs(a)), bot = bottomOf(Math.abs(a));
  const u = (top - Y) / (top - bot);                                      // 0 at the top edge .. 1 at the bottom edge
  const end = smoothstep(3.2, 6.0, Math.abs(a));
  let L = 4.6 - 1.6 * u - 0.9 * end;                                      // bright mirror up top, a tinted gradient low
  L += 0.35 * smoothstep(-5.5, 5.5, a);                                       // the +x side faces the light
  // two soft diagonal reflections (a wide one and a thin echo), slanting like a window on the lens
  const d = a * 0.86 + (Y - 3.85) * 0.62;
  if (d > -2.45 && d < -2.1) L += 1.0;
  else if (d > -2.1 && d < -1.85) L += 0.45;
  if (d > -1.2 && d < -1.05) L += 0.6;
  // a dark horizon: the mirror reflects a ground line low on the lens
  if (u > 0.78 && u < 0.84) L -= 0.7;
  if (glintP !== null) {                                                   // the animated glint: a soft band sweeping across
    const g = a * 0.8 + (Y - 3.85) * 0.55 - glintP;
    if (g > -0.14 && g < 0.14) L += 2.0;
    else if (g > -0.5 && g < -0.14) L += 1.0;
    else if (g > -0.95 && g < -0.5) L += 0.4;
  }
  return L;
}
function lensColor(a, Y, x, y, glintP) {
  const t = hueT(a, Y, glintP === null ? 0 : 0.012 * Math.sin(glintP));
  const k = t * (HUES.length - 1), lo = Math.floor(k), f = k - lo;
  // clean hue bands with a narrow 1 texel checker where one band melts into the next (a pixel-art gradient,
  // never a field of noise)
  const cb = (x + y) & 1;
  const hi = Math.min(HUES.length - 1, f < 0.34 ? lo : f > 0.66 ? lo + 1 : lo + (cb ? 1 : 0));
  return lerpStep(HR[hi], clamp(Math.round(lensL(a, Y, glintP) * 2) / 2, 0, 6));
}

// ---- painters ---------------------------------------------------------------------------------------------------
function paintLens(v, f, c, glintP) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    const a = arcOf(c, X);
    let col;
    if (f === 'south') {
      const am = Math.abs(a);
      const topEdge = !inLens(a, Y + px), botEdge = !inLens(a, Y - px) && Y < topOf(am) - 2 * px;
      const sideL = !inLens(a - px * Math.sign(a || 1), Y) && am > 0.5;
      if (topEdge) col = mix(lensColor(a, Y, x, y, glintP), GLINT, 0.55);   // the lens' bright top bevel
      else if (!inLens(a, Y + 2 * px)) col = mix(lensColor(a, Y, x, y, glintP), GLINT, 0.22);
      else if (botEdge) col = mix(lensColor(a, Y, x, y, glintP), INK, 0.5);  // a darker lip along the bottom
      else if (!inLens(a, Y - 2 * px)) col = mix(lensColor(a, Y, x, y, glintP), INK, 0.2);
      else col = lensColor(a, Y, x, y, glintP);
      if (sideL && inLens(a, Y)) col = mix(col, GLINT, 0.4);
    } else if (f === 'down') col = HR[2][3];
    else if (f === 'up') col = HR[1][1];
    else if (f === 'north') col = mix(lensColor(a, Y, x, y, glintP), INK, 0.5);                                // the back of the lens, against the face
    else col = lensColor(a, Y, x, y, glintP);                              // slab ends: the lens colour itself, so no seam shows
    v.set(x, y, toHex(col));
  }
}
// chrome bar: three texel rows on the front (specular on top, mid, a dark under-lip), pinched highlights
function paintBar(v, f, c, glintP) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    const a = arcOf(c, X);
    let s;
    if (f === 'south' || f === 'east' || f === 'west') {
      const r = Math.floor((c.to[1] - Y) / px);                           // 0 = top texel row
      s = r === 0 ? 7 : r === 1 ? 2.6 : 5.0;                               // chrome: white top, a dark reflected horizon, a bright bounce
      if (f !== 'south') s -= 0.9;
      s += 0.6 * smoothstep(-5.5, 5.5, a);
      // two bright bands where the curve catches the light, one near the nose, one at the temple
      if (Math.abs(a - 2.4 * Math.sign(a || 1)) < 0.28) s += 1.0;
      if (glintP !== null && Math.abs(a * 0.8 + 0.6 - glintP) < 0.3) s += 1.2;
      if (r === 2 && Math.abs(a) < 0.12) s = 1.2;                         // a centre seam
    } else if (f === 'up') s = 6;
    else if (f === 'down') s = 0.9;
    else s = 2.0;
    // the bar picks up the lens sheen, faintly
    const base = pickC(CH, s), tint = HUES[Math.round(hueT(a, c.to[1] - 0.2, 0) * (HUES.length - 1))];
    void Z;
    v.set(x, y, toHex(f === 'south' ? mix(base, tint, 0.14) : base));
  }
}
function paintStem(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    const outer = (c.sx > 0 && f === 'east') || (c.sx < 0 && f === 'west');
    const inner = (c.sx > 0 && f === 'west') || (c.sx < 0 && f === 'east');
    const tip = Z < EZ - 3.2;                                              // the last stretch: rubber
    const hinge = Z > EZ - 0.3 && Z < EZ - 0.15;
    let s;
    if (tip) {
      v.set(x, y, pickC(RUB, f === 'up' ? 3 : outer ? 2 : f === 'down' ? 0 : 1));
      continue;
    }
    if (f === 'up') s = 5.4;
    else if (f === 'down') s = 0.9;
    else if (outer) s = Y > c.to[1] - 2 * px ? 7 : Y > c.to[1] - 4 * px ? 2.8 : 5.0;       // chrome: white top, dark reflected horizon, bright bounce
    else if (inner) s = 1.8;
    else if (f === 'south') s = 4.0;
    else s = 1.4;
    if (hinge && (outer || f === 'up')) s = 0.8;                            // hinge seam
    // brushed streaks: every other texel row along the arm
    if (outer && Math.floor((Z + 8) * S) % 4 === 0) s -= 0.5;
    v.set(x, y, pickC(CH, s));
  }
}
const PAINT = { lens: paintLens, bar: paintBar, stem: paintStem };
const strip = ({ role, sx, sg, ...c }) => c;

function paintFrame(frame) {
  const t = newTexture({ texSize: TEX, scale: S });
  const glintP = frame < SWEEP_FRAMES ? -6.9 + (13.8 * (frame + 0.5)) / SWEEP_FRAMES : null;
  for (const c of parts) paintCube(t, c, S, (v, f) => PAINT[c.role](v, f, c, glintP));
  return t;
}

export default {
  meta: { id: 'mask-shield', name: 'Racer Shield', kind: 'mask', cents: 699, blurb: 'A rimless single-piece sport shield: one tall prizm mirror lens, pink-gold to cyan, on a slim chrome bar. A glint runs across it now and then.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'shield', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.map(strip) }] },
  anim: { frames: FRAMES, fps: FPS },
  paint: paintFrame,
};
