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
const INK = '#14101f', GLINT = '#fff7ee', EDGE = '#05070b';
// smoked gunmetal for the lens body: five cool tones, deep blue-black .. soft steel
const GUN = ['#0b0e14', '#161b24', '#232a37', '#343e4e', '#4d5a6e'];
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
  const am = sg.a0 + STEP / 2, y1 = topOf(am);
  let y0 = 9;                                                            // the slab box reaches the lowest texel column; the rest is cut away by alpha
  for (let k = 0; k < STEP * S; k++) y0 = Math.min(y0, bottomOf(sg.a0 + (k + 0.5) * px));
  slab('lens', sx, sg, y0, y1, 0, T);
  slab('bar', sx, sg, y1 - 0.25, y1 + 0.25, 0.0625, 0.5);
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

// ---- the lens colour --------------------------------------------------------------------------------------------
// Monochrome first: the lens body is smoked gunmetal in clean diagonal tone steps (lighter toward the player's
// +x / top, where the light is). Colour lives ONLY in two diagonal sheen bands, a wide one and a thin one, that
// run warm (peach / rose) at the top to cool (cyan) at the bottom. An animated white glint crosses it on top.
const diag = (a, Y) => a * 0.86 + (Y - 3.85) * 0.62;                       // runs perpendicular to the sheen bands
function bodyL(a, Y) {
  const top = topOf(Math.abs(a)), bot = bottomOf(Math.abs(a));
  const u = clamp((top - Y) / (top - bot));
  const end = smoothstep(3.6, 6.0, Math.abs(a));
  return 1.7 + 1.9 * smoothstep(-4.6, 4.2, diag(a, Y)) + 0.7 * (1 - u) - 0.6 * end;
}
const BANDS = [{ c: -2.0, w: 0.5, hue: 0.0 }, { c: -0.55, w: 0.14, hue: 0.12 }];   // diagonal centre, half-width, hue shift
function lensColor(a, Y, x, y, glintP) {
  const L = bodyL(a, Y), fl = Math.floor(L), fr = L - fl;
  let col = GUN[clamp(fl + (fr < 0.5 ? 0 : 1), 0, GUN.length - 1)];   // hard diagonal tone steps, no dither
  const d = diag(a, Y);
  for (const b of BANDS) {
    const off = Math.abs(d - b.c);
    if (off > b.w) continue;
    const t = clamp(0.5 - 0.17 * (Y - 3.85) + b.hue), k = t * (HUES.length - 1);
    const h = Math.round(k), hue = HR[clamp(h, 0, HUES.length - 1)];
    col = off < b.w * 0.55 ? hue[4] : mix(col, hue[3], 0.6);                  // vivid core, softer edge
  }
  if (glintP !== null) {
    const g = d * 0.93 - glintP;
    if (Math.abs(g) < 0.16) col = mix(col, GLINT, 0.7);
    else if (g < 0 && g > -0.55) col = mix(col, GLINT, 0.22);
  }
  return col;
}

// ---- painters ---------------------------------------------------------------------------------------------------
function paintLens(v, f, c, glintP) {
  const endSlab = c.sg.i === N - 1;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    const a = arcOf(c, X), am = Math.abs(a);
    let col;
    if (f === 'south' || f === 'north') {
      if (!inLens(a, Y)) continue;                                           // alpha cut-out: the true outline, one texel at a time
      if (f === 'north') { v.set(x, y, toHex(GUN[1])); continue; }
      col = lensColor(a, Y, x, y, glintP);
      const nearOuter = am > 5.7;
      const below = !inLens(a, Y - px), side = !nearOuter && (!inLens(a - px, Y) || !inLens(a + px, Y));
      if (below || side) col = EDGE;                                           // 1 texel dark outline along the bottom and round the nose cut-out
      else if (!inLens(a, Y - 2 * px) || (!nearOuter && (!inLens(a - 2 * px, Y) || !inLens(a + 2 * px, Y)))) col = mix(col, GLINT, 0.16);   // a faint lit lip inside it
      else if (!inLens(a, Y + px)) col = mix(col, GLINT, 0.45);              // lit top bevel under the bar
      v.set(x, y, toHex(col));
    } else if (endSlab && (f === 'east' || f === 'west') && inLens(a, Y)) {
      v.set(x, y, toHex(GUN[2]));                                              // the lens' outer end
    }
  }
}
// chrome bar: three texel rows on the front (specular on top, mid, a dark under-lip), pinched highlights
function paintBar(v, f, c, glintP) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    const a = arcOf(c, X);
    let s;
    if (f === 'south' || f === 'east' || f === 'west') {
      const r = Math.floor((c.to[1] - Y) / px);                           // 0 = top texel row of 4
      s = r === 0 ? 7 : r === 1 ? 5.4 : r === 2 ? 2.2 : 3.6;               // chrome brow: white spec, bright body, dark reflected horizon, a soft under-lip
      if (f !== 'south') s -= 0.9;
      s += 0.6 * smoothstep(-5.5, 5.5, a);
      // two bright bands where the curve catches the light, one near the nose, one at the temple
      if (Math.abs(a - 2.4 * Math.sign(a || 1)) < 0.28) s += 1.0;
      if (glintP !== null && Math.abs(a * 0.8 + 0.6 - glintP) < 0.3) s += 1.2;
      if (r === 3 && Math.abs(a) < 0.12) s = 1.2;                         // a centre seam
    } else if (f === 'up') s = 6;
    else if (f === 'down') s = 0.9;
    else s = 2.0;
    void Z;
    v.set(x, y, pickC(CH, s));
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
