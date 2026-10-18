// Mirror Visor: a Y2K sport sun visor. A graphite fabric band wraps the head (no crown: the top of the
// head stays open) with a ribbed knit, stitched hems and breathing vents; the band stands tallest over
// the forehead and tapers toward a slim strap at the back with a chrome slider. Out the front goes a
// curved brim of mirrored plastic, 72 one-texel strips fanned from one hinge so the plan is an arc and the
// edges droop more than the middle. Its top is a mirror: a dark horizon, a white softbox streak, and a
// pink -> violet -> cyan iridescent sheen banded across it. The Origin mark (lib/marks, drawn pixel by
// pixel at the size it appears) is printed in reflective silver on the band front.
// Once every 2 s a pale light sweep runs along the brim (baked into texture frames, loop is seamless).
//
// Light: top-front-left of the PLAYER (+x, +y, +z). Every texel is painted from its position in the cube's
// own (pre-rotation) frame so the sheen and the knit run continuously across neighbouring strips.
// Clearance (FORMAT.md): band 1 px proud of the head (hat layer is 0.5), top y = 8, brim tip 3.75 px
// in front of the face, nothing above y = 8, nothing wider than x +-5.
import { clamp, smoothstep, mix, toHex } from '../../lib/px.mjs';
import { drawMark } from '../../lib/marks.mjs';
import { Canvas } from '../../lib/px.mjs';
import { cubeRects, cubeFaceNames } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const FRAMES = 20, FPS = 10, SWEEP_FRAMES = 10;
const PACK_W = 256;                                                // texture width in texels

// ---- ramps -----------------------------------------------------------------------------------------------
// graphite knit, 8 steps: cool blue-violet shadows -> neutral -> a faintly warm grey. Index 0 = outline.
const F = ['#08090d', '#101218', '#181b23', '#21252f', '#2c313d', '#3a404d', '#4c5361', '#656c7a'];
// reflective silver print for the mark
const SV = ['#4c525e', '#7c828d', '#aeb2ba', '#d9dbe0', '#f6f6f4'];
// gunmetal / chrome hardware (back slider)
const M = ['#0a0b10', '#1a1d25', '#2c313c', '#454b58', '#6a7080', '#9aa0ad', '#cdd1d8', '#f4f4f2'];
// mirrored plastic, three hue ramps, 6 steps each: shadows go cool and deep, lights go warm and white.
const PINK = ['#240d3a', '#5a1a6c', '#a62e98', '#ee6fb8', '#ff9fd2', '#fff1f7'];
const VIOL = ['#150f3c', '#2f2788', '#5b52cf', '#8e86f1', '#c9c4ff', '#f6f5ff'];
const CYAN = ['#06223a', '#0b4a6a', '#1790b0', '#46d3e2', '#a0f1ef', '#effffd'];
// smoked underside of the brim
const UNDER = ['#07050f', '#100b20', '#1a1332', '#271e48', '#372f60'];
const WHITE = '#ffffff';

const fabric = v => F[clamp(Math.round(v), 0, F.length - 1)];

// ---- geometry (head frame: head cube x -4..4, y 0..8, z -4..4) ----------------------------------------------
const parts = [];
const part = (role, from, to, o = {}) => { const c = { from, to, role, faces: {}, ...o }; parts.push(c); return c; };
const OUTER = 5.0, INNER = 4.0;                                      // band: 1 px thick, 1 px proud of the head
const FRONT = { y0: 5.0, y1: 8.0 }, MID = { y0: 5.25, y1: 7.75 }, REAR = { y0: 5.5, y1: 7.5 };
// band pieces overlap by one texel along z so no crack opens at the steps; faces: outer, top, bottom, ends
part('front', [-OUTER, FRONT.y0, INNER], [OUTER, FRONT.y1, OUTER], { need: ['south', 'up', 'down', 'east', 'west'] });
for (const sx of [1, -1]) {
  const m = (a, b) => (sx > 0 ? [a, b] : [-b, -a]);
  const [x0, x1] = m(INNER, OUTER), out = sx > 0 ? 'east' : 'west';
  part('side', [x0, FRONT.y0, 0.5], [x1, FRONT.y1, INNER + px], { sx, seg: 0, need: [out, 'up', 'down'] });
  part('side', [x0, MID.y0, -2.25], [x1, MID.y1, 0.5 + px], { sx, seg: 1, need: [out, 'up', 'down'] });
  part('side', [x0, REAR.y0, -INNER - px], [x1, REAR.y1, -2.25 + px], { sx, seg: 2, need: [out, 'up', 'down'] });
}
part('back', [-OUTER, REAR.y0, -OUTER], [OUTER, REAR.y1, -INNER], { need: ['north', 'up', 'down', 'east', 'west'] });
part('slider', [-1.25, 5.875, -OUTER - 0.375], [1.25, 7.125, -OUTER], { need: ['north', 'up', 'down', 'east', 'west'] });

// the brim: strips of half a pixel, hinged along the band's front face at the top of the brim (y 5.5)
const HINGE = { y: 5.5, z: OUTER }, BRIM_T = 0.375, TIP = 7.9, NSTRIP = 72, SW = 0.125, BRIM_HALF = 4.5;
const tipZ = xc => OUTER + 0.75 + (TIP - OUTER - 0.75) * Math.pow(1 - Math.pow(Math.abs(xc) / BRIM_HALF, 2.4), 0.6);
const tilt = xc => 11 + 8 * Math.pow(Math.abs(xc) / OUTER, 2);        // degrees: droops more toward the ends
for (let k = 0; k < NSTRIP; k++) {
  const x0 = -BRIM_HALF + k * SW, x1 = x0 + SW, xc = (x0 + x1) / 2;
  const zt = Math.max(OUTER + 0.5, Math.round(tipZ(xc) * S) / S);
  part('brim', [x0, HINGE.y - BRIM_T, OUTER - 0.25], [x1, HINGE.y, zt], {
    k, xc, rotation: { origin: [xc, HINGE.y, HINGE.z], angle: [tilt(xc), 0, 0] },
    need: ['up', 'down', 'south', 'east', 'west'],
  });
}

// ---- texture packing: only the faces that can be seen get pixels ----------------------------------------------
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
const packedH = pack();
const TEX = [PACK_W / S, Math.ceil(packedH / S)];
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

// ---- the knit band ----------------------------------------------------------------------------------------------
// X runs across the head (+x = the light side). Light falls off toward the ends where the band turns away.
const MARK_SIZE = 24, MARK_STROKE = 2, MARK_OPEN = 1.4, MARK_CY = 6.75;
let markInfo = null;
function markAt(texX, texY) {                                        // texel offset from the mark's top-left -> info | null
  if (!markInfo) {
    const probe = { map: new Map(), set(x, y, c) { this.map.set(`${x},${y}`, c); } };
    const m = drawMark(probe, (MARK_SIZE + 2) / 2, (MARK_SIZE + 2) / 2, MARK_SIZE, p => p, MARK_STROKE, { open: MARK_OPEN });
    markInfo = { map: probe.map, m };
  }
  return markInfo.map.get(`${texX},${texY}`) || null;
}
function paintFront(v, f, c) {
  const markOx = Math.round(v.w / 2 - (MARK_SIZE + 2) / 2), markOy = Math.round((c.to[1] - MARK_CY) * S - (MARK_SIZE + 2) / 2);
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    let col;
    if (f === 'south') {
      const lx = X / OUTER;
      let l = 4.1 + 0.9 * lx - 1.6 * Math.pow(Math.abs(lx), 3) - 0.35 * ((c.to[1] - Y) / 3);
      // knit: vertical ribs, a one-step light/dark pair every other column, broken every 4th row
      l += (x + y) % 4 < 2 ? 0.28 : -0.2;                                     // fine diagonal twill
      const yFromTop = y, yFromBottom = v.h - 1 - y;
      if (yFromTop === 0) l = 5.4 + 0.5 * lx - 1.4 * Math.pow(Math.abs(lx), 3);            // top hem: a lit rolled edge
      else if (yFromTop === 1) l = 2.2;
      else if (yFromTop === 3 && x % 4 !== 3 && Math.abs(x - v.w / 2) > 16) l = 5.6 + 0.5 * lx - 1.4 * Math.pow(Math.abs(lx), 3);   // stitching: dashes
      else if (yFromTop === 4 && x % 4 !== 3 && Math.abs(x - v.w / 2) > 16) l = 2.0;                                               // ...with their shadow
      if (yFromBottom === 0) l = 0.8;
      else if (yFromBottom === 1) l = 2.4;
      // the brim sits on the bottom edge: shadow under it
      col = fabric(l);
      // the mark, silver print with a darker edge
      const mi = markAt(x - markOx, y - markOy);
      if (mi) {
        const shade = mi.edge ? mi.light * 3.2 : 2 + mi.light * 2.6;
        col = SV[clamp(Math.round(shade), 0, SV.length - 1)];
        if (mi.edge && mi.light < 0.45) col = SV[0];
      } else {
        const sh = markAt(x - markOx - 1, y - markOy - 1);                // a faint print shadow down-right
        if (sh) col = fabric(Math.max(1, Math.round(l) - 2));
      }
    } else if (f === 'up') col = fabric(x % 2 === 0 ? 5.4 : 4.6);
    else if (f === 'down') col = F[1];
    else col = fabric(2.6 + (x % 2 === 0 ? 0.3 : 0) - (y === v.h - 1 ? 1.6 : 0) + (y === 0 ? 1.4 : 0));
    v.set(x, y, col);
  }
}
// side and back bands: the same knit, with a stitched hem and a column of breathing vents
function paintSide(v, f, c) {
  const outer = f === 'east' || f === 'west' || f === 'north';
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    let col;
    if (outer) {
      const side = f === 'north' ? 0 : c.sx;
      let l = 4.0 + (side > 0 ? 0.55 : side < 0 ? -0.4 : 0) - 0.3 * ((c.to[1] - Y) / 2.5);
      if (f === 'north') l = 3.7 + 0.5 * (X / OUTER) - 1.2 * Math.pow(Math.abs(X / OUTER), 3);
      l += (x + y) % 4 < 2 ? 0.28 : -0.2;
      const yTop = y, yBot = v.h - 1 - y;
      if (yTop === 0) l = 5.2; else if (yTop === 1) l = 2.2;
      else if (yTop === 3 && x % 4 !== 3) l = 5.4; else if (yTop === 4 && x % 4 !== 3) l = 2.0;
      if (yBot === 0) l = 0.8; else if (yBot === 1) l = 2.4;
      // breathing vents: a row of 2x2 punched dots mid-band, a light texel under each lip
      if (f !== 'north' && c.seg !== 2 && x > 4 && x < v.w - 5) {
        const rowDot = Math.floor(v.h / 2) - 1, dx = x % 7, dy = y - rowDot;
        if (dx < 2 && dy >= 0 && dy < 2) l = dy === 0 ? 0.5 : 1.0;
        else if (dx === 2 && dy === 1 && false) l = 5;
        else if (dx < 2 && dy === 2) l = 5.0;
      }
      col = fabric(l);
    } else if (f === 'up') col = fabric(x % 2 === 0 ? 5.3 : 4.5);
    else if (f === 'down') col = F[1];
    else col = F[2];
    v.set(x, y, col);
  }
}
// slider: a polished chrome bar with a slit, riveted at the corners
function paintSlider(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let l = f === 'up' ? 6 : f === 'down' ? 1 : f === 'north' ? 3.6 + 1.6 * (y < v.h / 2 ? 1 : -0.5) : 3.2;
    if (f === 'north') {
      if (y === 0) l = 6.8; else if (y === v.h - 1) l = 1.2;
      else if (x === v.w - 1) l = 2.0; else if (x === 0) l = 5.6;
      if (y >= 3 && y <= v.h - 4 && x >= 4 && x <= v.w - 5) l = (y === 3 || x === 4) ? 0.8 : (y === v.h - 4 || x === v.w - 5) ? 4.8 : 0.5;   // the slit
    }
    v.set(x, y, M[clamp(Math.round(l), 0, M.length - 1)]);
  }
}

// ---- the mirror brim ---------------------------------------------------------------------------------------------
// Iridescence: t = 0 pink .. 0.5 violet .. 1 cyan, quantised into 7 bands so the shift reads as clean clusters.
// The light index runs 0..5 on the ramps; fractional values are mixed between steps and quantised to a quarter step.
function hueRamp(t, step) {
  const s = clamp(step, 0, 5), lo = Math.floor(s), hi = Math.min(5, lo + 1), f = s - lo;
  const at = R => mix(R[lo], R[hi], f);
  const tq = Math.round(clamp(t) * 6) / 6;
  const col = tq < 0.5 ? mix(at(PINK), at(VIOL), tq * 2) : mix(at(VIOL), at(CYAN), (tq - 0.5) * 2);
  return toHex(col);
}
const q4 = v => Math.round(v * 4) / 4;
// what the mirror shows at (X, dz): a shadowed band under the hat, a pale sky with one white softbox streak,
// a crisp dark horizon, then bright sky again toward the lip.
function mirrorStep(X, dz, len) {
  const diag = dz + 0.16 * X;                                       // the streak leans: the reflection slants across the brim
  let s;
  if (dz < 0.28) s = 0.8;                                          // under the band: dark
  else if (diag < 0.72) s = 1.2 + 2.2 * smoothstep(0.28, 0.72, diag);                   // the reflected band shadow fading in
  else if (diag < 1.1) s = 3.4 + 1.0 * smoothstep(0.72, 1.1, diag);
  else if (diag < 1.34) s = 5.6;                                     // the softbox streak: near white
  else if (diag < 1.5) s = 4.4;
  else if (diag < 1.66) s = 0.9;                                     // the horizon: a crisp dark line
  else s = 2.8 + 1.5 * smoothstep(1.66, 2.9, diag);                   // far sky toward the lip
  if (dz > len - 0.125) s = Math.max(s, 5.3);                        // the rim of the lip catches the light (one texel)
  return q4(s);
}
const hueOf = (X, dz) => clamp(0.5 - 0.5 * (X / OUTER) + 0.2 * (dz / 3) + 0.04);
function paintBrim(v, f, c, sweep) {
  const len = c.to[2] - OUTER;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y), dz = Z - OUTER;
    let col;
    if (f === 'up') {
      const s = mirrorStep(X, dz, len);
      col = hueRamp(hueOf(X, dz), s);
      // lit edges: the long edges of a strip are the bevel of the mirror
      if (sweep) col = toHex(mix(col, WHITE, sweep(X, dz)));
    } else if (f === 'down') {
      const sh = 1.6 + 1.4 * smoothstep(0.2, 3, dz) - (Z > c.to[2] - 0.2 ? 0.9 : 0);
      col = toHex(mix(UNDER[clamp(Math.round(sh), 0, 4)], hueRamp(hueOf(X, dz), 1.5 + 1.2 * smoothstep(0.5, 3, dz)), 0.18));
    } else if (f === 'south') {
      // the front rim of the lip: a bright top line, the body, a dark under-edge
      const l = y === 0 ? 4.8 : y === v.h - 1 ? 1.6 : 3.4;
      col = hueRamp(hueOf(X, dz), l);
      if (sweep && y === 0) col = toHex(mix(col, WHITE, sweep(X, dz)));
    } else {
      // the walls between strips (visible where the arc steps): the surface's own sheen, a step darker, so
      // the steps read as one continuous mirror rather than separate slats
      col = hueRamp(hueOf(X, dz), Math.max(0.6, mirrorStep(X, dz, len) - (y === 0 ? 0.3 : 1.3)));
    }
    v.set(x, y, col);
  }
}

// ---- the sweep -----------------------------------------------------------------------------------------------------
// A pale diagonal band travelling along the brim: bright 1 px core, a softer trailing band, a faint echo ahead.
function sweepFn(frame) {
  if (frame >= SWEEP_FRAMES) return null;
  const p = -7.5 + (15 * (frame + 0.5)) / SWEEP_FRAMES;            // band centre across the brim (x + slant)
  return (X, dz) => {
    const d = X + 1.1 * dz - p;                                     // slanted band
    if (d >= -0.25 && d < 0.25) return 0.62;
    if (d >= -0.75 && d < -0.25) return 0.3;
    if (d >= -1.25 && d < -0.75) return 0.12;
    if (d >= 0.5 && d < 0.75) return 0.12;
    return 0;
  };
}

const PAINT = { front: paintFront, side: paintSide, back: paintSide, slider: paintSlider };
let baseTex = null;
function paintTexture(sweep) {
  const tex = new Canvas(TW, TH);
  for (const c of parts) {
    const rects = cubeRects({ ...c, uv: [0, 0] }, S);
    for (const f of cubeFaceNames(c)) {
      const r = rects[f], view = tex.view(r.x, r.y, r.w, r.h);
      if (c.role === 'brim') paintBrim(view, f, c, sweep); else PAINT[c.role](view, f, c);
    }
  }
  return tex;
}

const strip = ({ role, sx, seg, k, xc, need, ...c }) => c;
export default {
  meta: { id: 'hat-visor', name: 'Mirror Visor', kind: 'hat', cents: 699,
    blurb: 'A Y2K sport sun visor: a graphite knit band, a curved mirror brim shifting pink to cyan, and the Origin mark on the front. A light glides along the brim now and then.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'visor', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.map(strip) }] },
  anim: { frames: FRAMES, fps: FPS },
  paint(frame = 0) {
    if (!baseTex) baseTex = paintTexture(null);
    return frame < SWEEP_FRAMES ? paintTexture(sweepFn(frame)) : baseTex.clone();
  },
};
