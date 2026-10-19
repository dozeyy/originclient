// Mirror Visor: a Y2K sport sun visor. A slim gunmetal band (2.25 px tall, the same height all the way round,
// sitting flush on the head: 0.625 px of inflate) circles the head with no crown, so the top of the head stays
// open. Its four corners are chamfered by 45 degree pieces, so the wrap reads as round and not as a box. The
// band is a plain 4-tone gunmetal ramp lit from the top-left (no texture noise), with one stitch line, and on
// the front the Origin mark in 1 px silver thread: the ring geometry is lib/marks' (markGeometry), rasterised
// here as exact 1 px ellipses (one pixel per row/column along each ring) with a clean 1 px gap at every
// crossing, alternating over/under along each ring. A chrome slider sits at the back. Out the front goes a
// curved brim of SMOKED MIRROR plastic: 72 one-texel strips fanned from one hinge so the plan is an arc and
// the edges droop more than the middle. Its top is a dark gunmetal mirror (soft horizon, a muted softbox
// streak) and ONE iridescent sheen line, pink -> violet -> cyan, runs along its front edge. Hue lives only in
// that line. Once every 2 s a pale light sweep runs along the sheen line (baked into texture frames, loop is
// seamless).
//
// Light: top-front-left of the PLAYER (+x, +y, +z). The band is shaded from each texel's angle round the head,
// so the light rolls continuously over the front, the corners, the sides and the back. Every brim texel is
// painted from its position in the cube's own (pre-rotation) frame so the mirror runs continuously across
// neighbouring strips.
// Clearance (FORMAT.md): band 0.625 px proud of the head (hat layer is 0.5), top y = 6.75, brim tip 3.9 px
// in front of the face, nothing above y = 8, nothing wider than x +-5.
import { clamp, smoothstep, mix, toHex } from '../../lib/px.mjs';
import { Canvas } from '../../lib/px.mjs';
import { markGeometry } from '../../lib/marks.mjs';
import { cubeRects, cubeFaceNames } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const FRAMES = 20, FPS = 10, SWEEP_FRAMES = 10;
const PACK_W = 256;                                                // texture width in texels

// ---- ramps -----------------------------------------------------------------------------------------------
// the band: a plain 4-tone gunmetal ramp, shadow -> light (lit from the top-left). BAND[0] doubles as the outline.
const BAND = ['#161920', '#232731', '#353b49', '#4d5668'];
// reflective silver print for the mark
const SV = ['#4c525e', '#7c828d', '#aeb2ba', '#d9dbe0', '#f6f6f4'];
// gunmetal / chrome hardware (back slider)
const M = ['#0a0b10', '#1a1d25', '#2c313c', '#454b58', '#6a7080', '#9aa0ad', '#cdd1d8', '#f4f4f2'];
// smoked gunmetal mirror (the brim body), neutral with the faintest cool lean
const G = ['#07080b', '#0f1116', '#1a1d25', '#272b36', '#383d4b', '#4f5666', '#6f778a', '#98a0b2'];
// the sheen line only: three hue ramps, 6 steps each: shadows go cool and deep, lights go warm and white.
const PINK = ['#240d3a', '#5a1a6c', '#a62e98', '#ee6fb8', '#ff9fd2', '#fff1f7'];
const VIOL = ['#150f3c', '#2f2788', '#5b52cf', '#8e86f1', '#c9c4ff', '#f6f5ff'];
const CYAN = ['#06223a', '#0b4a6a', '#1790b0', '#46d3e2', '#a0f1ef', '#effffd'];
// smoked underside of the brim
const UNDER = ['#050608', '#0b0c10', '#14161c', '#1e212a', '#2b2f3a'];
const WHITE = '#ffffff';

const smoke = s => {                                                // light index -> smoked gunmetal (fractional steps mixed)
  const i = clamp(s, 0, G.length - 1), lo = Math.floor(i), hi = Math.min(G.length - 1, lo + 1);
  return toHex(mix(G[lo], G[hi], i - lo));
};

// ---- geometry (head frame: head cube x -4..4, y 0..8, z -4..4) ----------------------------------------------
const parts = [];
const part = (role, from, to, o = {}) => { const c = { from, to, role, faces: {}, ...o }; parts.push(c); return c; };
const OUTER = 4.625, INNER = 4.0;                                    // band: flush on the head, 0.625 px thick
const BAND_Y = { y0: 4.5, y1: 6.75 };                                // one height all the way round (18 texels)
const CUT = 0.75, CW = 1.125, CT = OUTER - INNER;                    // corner chamfer: leg, width (9 texels), thickness
const FLAT = OUTER - CUT;                                            // half-length of the straight pieces
part('front', [-FLAT, BAND_Y.y0, INNER], [FLAT, BAND_Y.y1, OUTER], { need: ['south', 'up', 'down'] });
part('back', [-FLAT, BAND_Y.y0, -OUTER], [FLAT, BAND_Y.y1, -INNER], { need: ['north', 'up', 'down'] });
for (const sx of [1, -1]) {
  const [x0, x1] = sx > 0 ? [INNER, OUTER] : [-OUTER, -INNER], out = sx > 0 ? 'east' : 'west';
  part('side', [x0, BAND_Y.y0, -FLAT], [x1, BAND_Y.y1, FLAT], { sx, need: [out, 'up', 'down'] });
}
// four 45 degree corner pieces, each centred so its outer face spans the chord between the straight pieces
const CC = (FLAT + OUTER) / 2 - (CT / 2) / Math.SQRT2;
for (const [sx, sz, deg] of [[1, 1, 45], [-1, 1, -45], [1, -1, 135], [-1, -1, -135]]) {
  const cx = sx * CC, cz = sz * CC;
  part('corner', [cx - CW / 2, BAND_Y.y0, cz - CT / 2], [cx + CW / 2, BAND_Y.y1, cz + CT / 2], {
    deg, rotation: { origin: [cx, BAND_Y.y0, cz], angle: [0, deg, 0] }, need: ['south', 'up', 'down'],
  });
}
part('slider', [-1.25, 4.75, -OUTER - 0.375], [1.25, 6.5, -OUTER], { need: ['north', 'up', 'down', 'east', 'west'] });

// the brim: strips of half a pixel, hinged along the band's front face just above the band's lower edge
const HINGE = { y: BAND_Y.y0 + 0.125, z: OUTER }, BRIM_T = 0.375, TIP = 7.9, NSTRIP = 72, SW = 0.125, BRIM_HALF = 4.5;
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

// ---- the Origin mark: crisp 1 px thread ---------------------------------------------------------------------------
// Ring geometry comes from lib/marks (three tilted ellipses, markGeometry). Each ring is rasterised as an exact
// 1 px line: a texel is on when its offset from the true ellipse, measured along the stroke's dominant axis, is
// within half a texel, which gives exactly one texel per row or column wherever the ring runs (even strokes, no
// stair-step doubles). The 12 crossings alternate over/under along every ring (found by search, as the cape
// does); the under strand is cut for GAP texels around the crossing, leaving a clean 1 px gap each side.
const MARK = (() => {
  const SIZE = 22, GAP = 1.2, N = 2400, MW = SIZE + 2;
  const poly = markGeometry(SIZE, 1, 1).map(r => {
    const th = (r.rot * Math.PI) / 180, c = Math.cos(th), s = Math.sin(th), pts = [];
    for (let i = 0; i < N; i++) { const t = (i / N) * 2 * Math.PI, u = r.rx * Math.cos(t), v = r.ry * Math.sin(t); pts.push([u * c - v * s, u * s + v * c]); }
    return pts;
  });
  // texels on each ring
  const on = [[], [], []];
  for (let y = 0; y < MW; y++) for (let x = 0; x < MW; x++) {
    const qx = x + 0.5 - MW / 2, qy = y + 0.5 - MW / 2;
    poly.forEach((pts, i) => {
      let d = 1e9, k = 0;
      for (let j = 0; j < N; j++) { const e = (pts[j][0] - qx) ** 2 + (pts[j][1] - qy) ** 2; if (e < d) { d = e; k = j; } }
      const a = pts[(k + N - 3) % N], b = pts[(k + 3) % N], tx = b[0] - a[0], ty = b[1] - a[1], tl = Math.hypot(tx, ty) || 1;
      const dom = Math.max(Math.abs(ty), Math.abs(tx)) / tl, dist = Math.sqrt(d);
      if (dist / dom < 0.5 + 1e-9 && dist < 0.8) on[i].push([x, y]);
    });
  }
  // crossings between ring pairs: points where the two centre lines pass within 0.12 texel
  const X = [];
  for (let a = 0; a < 3; a++) for (let b = a + 1; b < 3; b++) {
    const hits = [];
    for (let i = 0; i < N; i++) for (let j = 0; j < N; j++) {
      const p = poly[a][i], r = poly[b][j];
      if (Math.abs(r[0] - p[0]) > 0.3 || Math.abs(r[1] - p[1]) > 0.3) continue;
      hits.push({ i, j, x: p[0], y: p[1], d: Math.hypot(r[0] - p[0], r[1] - p[1]) });
    }
    hits.sort((u, v) => u.d - v.d);
    const used = [];
    for (const h of hits) {
      if (h.d > 0.12) break;
      if (used.some(u => Math.hypot(u.x - h.x, u.y - h.y) < 1.5)) continue;
      used.push(h); X.push({ a, b, ta: h.i, tb: h.j, x: h.x, y: h.y });
    }
  }
  // over/under: bit k set = ring a over ring b at crossing k; every ring must alternate along its path
  let best = null;
  for (let m = 0; m < (1 << X.length) && best === null; m++) {
    let ok = true;
    for (let r = 0; r < 3 && ok; r++) {
      const list = X.map((c, k) => ({ t: c.a === r ? c.ta : c.tb, over: c.a === r ? !!((m >> k) & 1) : !((m >> k) & 1), in: c.a === r || c.b === r }))
        .filter(e => e.in).sort((u, v) => u.t - v.t);
      for (let k = 0; k < list.length; k++) if (list[k].over === list[(k + 1) % list.length].over) { ok = false; break; }
    }
    if (ok) best = m;
  }
  const under = X.map((c, k) => ((((best ?? 0) >> k) & 1) ? c.b : c.a));
  const g = new Map();                                         // "x,y" -> ring
  on.forEach((list, r) => {
    for (const [x, y] of list) {
      const qx = x + 0.5 - MW / 2, qy = y + 0.5 - MW / 2;
      if (X.some((c, k) => under[k] === r && Math.hypot(c.x - qx, c.y - qy) < GAP)) continue;
      g.set(`${x},${y}`, r);
    }
  });
  let x0 = 99, x1 = -1, y0 = 99, y1 = -1;
  for (const k of g.keys()) { const [x, y] = k.split(',').map(Number); x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  return { g, x0, x1, y0, y1, w: x1 - x0 + 1, h: y1 - y0 + 1 };
})();

// ---- the band -------------------------------------------------------------------------------------------------------
// The light index comes from the texel's angle round the head: 0 deg = straight ahead, +90 = the player's left side.
const LIGHT_DEG = 35, CORNER_SPAN = 14;
const cosL = deg => Math.cos(((deg - LIGHT_DEG) * Math.PI) / 180);
function bandAngle(c, f, x, X, Z, v) {
  if (c.role === 'corner') return c.deg + (f === 'south' ? ((x + 0.5) / v.w - 0.5) * CORNER_SPAN : 0);
  return (Math.atan2(X, Z) * 180) / Math.PI;
}
function paintBand(v, f, c) {
  const front = c.role === 'front', mx0 = Math.round((v.w - MARK.w) / 2) - MARK.x0, my0 = Math.round((v.h - MARK.h) / 2) - MARK.y0;
  const at = (x, y) => MARK.g.has(`${x - mx0},${y - my0}`);
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, , Z] = world(c, f, x, y), w = cosL(bandAngle(c, f, x, X, Z, v));
    let col;
    if (f === 'up') col = BAND[clamp(Math.round(2.2 + 0.8 * w), 0, 3)];
    else if (f === 'down') col = BAND[0];
    else {
      let t = clamp(Math.round(1.0 + 1.6 * w), 0, 3);
      const yTop = y, yBot = v.h - 1 - y;
      if (yTop === 0) t = Math.min(3, t + 1);                                  // the top edge catches the light
      else if (yBot === 0) t = Math.max(0, t - 1);                             // the bottom edge turns into shadow
      else if (yTop === 4 && x % 4 < 2) t = Math.max(0, t - 1);                // the one stitch line: dashes
      col = BAND[t];
      if (front) {                                                             // the mark: silver thread, dark outline
        if (at(x, y)) col = SV[3];
        else if (at(x - 1, y) || at(x + 1, y) || at(x, y - 1) || at(x, y + 1)) col = BAND[0];
      }
    }
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

// ---- the smoked mirror brim --------------------------------------------------------------------------------------
// Sheen line: iridescence t = 0 pink .. 0.5 violet .. 1 cyan, quantised into 7 bands so the shift reads as clean
// clusters. The light index runs 0..5 on the ramps; fractional values are mixed and quantised to a quarter step.
function hueRamp(t, step) {
  const s = clamp(step, 0, 5), lo = Math.floor(s), hi = Math.min(5, lo + 1), f = s - lo;
  const at = R => mix(R[lo], R[hi], f);
  const tq = Math.round(clamp(t) * 6) / 6;
  const col = tq < 0.5 ? mix(at(PINK), at(VIOL), tq * 2) : mix(at(VIOL), at(CYAN), (tq - 0.5) * 2);
  return toHex(col);
}
const q4 = v => Math.round(v * 4) / 4;
// what the smoked mirror shows at (X, dz): a deep shadow under the band, a dim sky with one muted softbox
// streak, a dark horizon, then dim sky again toward the lip. Returns a smoke index 0..~5.
function mirrorStep(X, dz) {
  const diag = dz + 0.16 * X;                                       // the streak leans: the reflection slants across the brim
  let s;
  if (dz < 0.28) s = 0.6;                                          // under the band: dark
  else if (diag < 0.72) s = 0.9 + 1.7 * smoothstep(0.28, 0.72, diag);                   // the reflected band shadow fading in
  else if (diag < 1.1) s = 2.6 + 0.8 * smoothstep(0.72, 1.1, diag);
  else if (diag < 1.34) s = 4.6;                                     // the softbox streak: the brightest smoke
  else if (diag < 1.5) s = 3.4;
  else if (diag < 1.66) s = 0.8;                                     // the horizon: a crisp dark line
  else s = 1.9 + 1.2 * smoothstep(1.66, 2.9, diag);                   // far sky toward the lip
  return q4(s);
}
const hueOf = X => clamp(0.5 - 0.5 * (X / BRIM_HALF) + 0.02);        // pink on the player's right .. cyan on the left
const SHEEN_DEPTH = 0.25;                                             // the line is the front two texels of every strip
function paintBrim(v, f, c, sweep) {
  const len = c.to[2] - OUTER;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y), dz = Z - OUTER;
    let col;
    if (f === 'up') {
      const fromTip = len - dz;
      if (fromTip < SHEEN_DEPTH) {
        col = hueRamp(hueOf(X), fromTip < 0.125 ? 4.6 : 3.2);
        if (sweep) col = toHex(mix(col, WHITE, sweep.line(X)));
      } else {
        col = smoke(mirrorStep(X, dz) * 1.2);
        if (sweep) col = toHex(mix(col, '#e4e8f0', sweep.surf(X, dz)));
      }
    } else if (f === 'down') {
      col = UNDER[clamp(Math.round(1.4 + 1.4 * smoothstep(0.2, 3, dz) - (Z > c.to[2] - 0.2 ? 0.9 : 0)), 0, 4)];
    } else if (f === 'south') {
      // the front rim of the lip: the sheen line's top row, then smoked body and a dark under-edge
      if (y === 0) {
        col = hueRamp(hueOf(X), 4.0);
        if (sweep) col = toHex(mix(col, WHITE, sweep.line(X)));
      } else col = smoke(y === v.h - 1 ? 0.8 : 2.0);
    } else {
      // the walls between strips (visible where the arc steps): the surface's own smoke, a step darker, so
      // the steps read as one continuous mirror rather than separate slats
      col = smoke(Math.max(0.5, mirrorStep(X, dz) - (y === 0 ? 0.2 : 0.9)));
    }
    v.set(x, y, col);
  }
}

// ---- the sweep -----------------------------------------------------------------------------------------------------
// A pale light travelling along the sheen line: a bright core, a softer trail behind. A faint slanted echo
// crosses the smoked surface above it.
function sweepFn(frame) {
  if (frame >= SWEEP_FRAMES) return null;
  const t = (frame + 0.5) / SWEEP_FRAMES;
  const pl = -6.5 + 13 * t;                                         // along the line (x)
  const p = -7.5 + 15 * t;                                          // slanted echo across the surface (x + slant)
  return {
    line: X => { const d = X - pl; return Math.abs(d) < 0.3 ? 0.85 : d < 0 && d > -0.9 ? 0.5 : d < 0 && d > -1.5 ? 0.2 : Math.abs(d) < 0.7 ? 0.25 : 0; },
    surf: (X, dz) => { const d = X + 1.1 * dz - p; return d >= -0.25 && d < 0.25 ? 0.2 : d >= -0.75 && d < -0.25 ? 0.09 : 0; },
  };
}

const PAINT = { front: paintBand, side: paintBand, back: paintBand, corner: paintBand, slider: paintSlider };
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

const strip = ({ role, sx, k, xc, deg, need, ...c }) => c;
export default {
  meta: { id: 'hat-visor', name: 'Mirror Visor', kind: 'hat', cents: 699,
    blurb: 'A Y2K sport sun visor: a slim graphite knit band with the Origin mark in silver thread, and a smoked mirror brim with one pink-to-cyan sheen line along its edge. A light glides along it now and then.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'visor', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.map(strip) }] },
  anim: { frames: FRAMES, fps: FPS },
  paint(frame = 0) {
    if (!baseTex) baseTex = paintTexture(null);
    return frame < SWEEP_FRAMES ? paintTexture(sweepFn(frame)) : baseTex.clone();
  },
};
