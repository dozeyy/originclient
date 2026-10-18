// Origin Bandana: charcoal cloth tied over the nose and mouth. A taut front band (rolled top hem, the
// Origin mark embroidered in 1 px silver thread over the mouth), a convex drape hanging to a point under
// the chin, level side bands hugging the head (lower edge rising toward the back), and a square knot with two short tails that sway and
// stream back when the player moves.
//
// Light: top-front-left of the PLAYER (+x, +y, +z), the lineup convention (lib/model LIGHT).
// Every texel is painted from its position in the cube's own (pre-rotation) frame, so folds, hems and
// the print run continuously across faces. Drape and tails are cut out with transparent texels and the
// same shape test is used on EVERY face of those cubes, so their edges and undersides agree.
// The mark is rasterised here at the size it appears (30 px, 1 px thread): traced centre lines with
// L-corner doubles removed, ring 0 mirrored on both axes, ring 1 point-symmetric, ring 2 = ring 1
// mirrored, so it is exactly symmetric (FORMAT.md art rule 1: never resampled).
import { clamp, smoothstep, bayer, mix } from '../../lib/px.mjs';
import { layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, px = 1 / S;
const TEX = [64, 32];

// ---- ramps -----------------------------------------------------------------------------------------
// Charcoal cloth, 7 steps: cool blue-violet shadows -> neutral -> warm grey lights. Index 0 is the outline.
const CL = ['#0b0c11', '#14161d', '#1d1f27', '#282a32', '#35353c', '#46444a', '#5d5753'];
// Silver thread: the step picked follows the light on the cloth under it.
const TH = ['#86837f', '#aeaaa5', '#cfccc6', '#e0e0e0'];
const cl = v => CL[clamp(Math.round(v), 0, CL.length - 1)];
// A 0..1 value onto the cloth ramp (steps 1..6; 0 is reserved for outlines) with hard bands and a
// narrow ordered-dither seam, so folds read as clean stepped clusters, never noise.
function cloth(v, x, y) {
  const p = clamp(v) * 5 + 1, lo = Math.floor(p), f = p - lo;
  const up = f > 0.5 + (bayer(x, y, 4) - 0.5) * 0.12;
  return CL[clamp(lo + (up ? 1 : 0), 1, 6)];
}
// A fold ridge: d in -1..1 across it. + = the flank facing the light (brighter), - = the shadow flank.
const ridge = d => (Math.abs(d) >= 1 ? 0 : Math.sin(Math.PI * d) * (1 - d * d * 0.3));

// ---- geometry (head frame: head cube x -4..4, y 0..8, z -4..4; face items stay at z <= 5) ----------
const IN = 4.0, OUT = 4.625;                     // the cloth sits 0.625 proud of the face (clear of hat layers)
const BAND = { y0: -0.75, y1: 3.0 };              // front band: under the eyes (y 3..4) down past the jaw
const KNOT = { x: 0, y: 2.25, z: -4.875 };         // the band's top edge stays level (y 3.0) all the way round the head
const parts = [];
const part = (role, from, to, extra = {}) => { const c = { from, to, uv: [0, 0], role, ...extra }; parts.push(c); return c; };

// front band (x end faces tuck 0.125 inside the side bands so no two faces are coplanar)
part('front', [-4.5, BAND.y0, IN], [4.5, BAND.y1, OUT]);
// drape: 0.25 thick, hinged under the band and swung back 14 degrees so the point tucks toward the neck
const DR = { y0: -3.125, y1: -0.5, z0: 4.3125, z1: 4.5625 };
part('drape', [-OUT, DR.y0, DR.z0], [OUT, DR.y1, DR.z1], { rotation: { origin: [0, DR.y1, (DR.z0 + DR.z1) / 2], angle: [14, 0, 0] } });
// side bands: UNTILTED boxes flush against the head (x 4..4.625, no angle, so no gap opens in 3/4 or side
// views). Full band height at the face, the lower edge rising (cut out) so the cloth gathers to the back
// band's 1.5 px at the knot; the top edge stays level.
const SIDE = { z0: -OUT, z1: IN, y0: BAND.y0, y1: BAND.y1, gather: 2.25 };
for (const sx of [1, -1]) {
  const [xa, xb] = sx > 0 ? [IN, OUT] : [-OUT, -IN];
  part('side', [xa, SIDE.y0, SIDE.z0], [xb, SIDE.y1, SIDE.z1], { sx });
}
const sideT = Z => clamp((Z - SIDE.z0) / (SIDE.z1 - SIDE.z0));            // 0 at the back, 1 at the face
const sideBottom = Z => SIDE.y0 + SIDE.gather * Math.pow(1 - sideT(Z), 1.25);
// back band: 1.5 px, the height the sides gather to, centred on the knot, top flush with the band top
const BACK = { y0: KNOT.y - 0.75, y1: KNOT.y + 0.75 };
part('back', [-4.5, BACK.y0, -OUT], [4.5, BACK.y1, -IN]);
// square knot: a core and two smaller loops either side (a bow-tie silhouette from behind)
part('knot', [-0.75, KNOT.y - 0.75, -5.25], [0.75, KNOT.y + 0.75, -4.5]);
for (const sx of [1, -1]) part('loop', sx > 0 ? [0.75, KNOT.y - 0.5, -5.0] : [-1.375, KNOT.y - 0.5, -5.0], sx > 0 ? [1.375, KNOT.y + 0.5, -4.5] : [-0.75, KNOT.y + 0.5, -4.5], { sx });
// tails: thin ribbons hanging from under the knot, each in its own bone so they can sway
const TAILS = [
  { name: 'tailL', sx: 1, x: 0.5, len: 2.75, rest: [24, 0, 13] },
  { name: 'tailR', sx: -1, x: -0.5, len: 2.375, rest: [20, 0, -9] },
];
const TAIL_W = 1.0, TAIL_T = 0.25, TAIL_TOP = KNOT.y - 0.375, TAIL_Z = -4.875;
for (const t of TAILS) part('tail', [t.x - TAIL_W / 2, TAIL_TOP - t.len, TAIL_Z - TAIL_T / 2], [t.x + TAIL_W / 2, TAIL_TOP, TAIL_Z + TAIL_T / 2], { tail: t });

layoutCubes(parts, TEX, S);

// ---- shapes (cut-outs), in the cube's own frame -------------------------------------------------------
// drape: a convex point. Bottom boundary rises from the tip (x 0) to the band at the jaw corners.
const drapeBottom = X => DR.y0 + (DR.y1 - DR.y0 + 0.1) * Math.pow(Math.min(1, Math.abs(X) / OUT), 1.35);
const inDrape = (X, Y) => Y >= drapeBottom(X);
// tail: tapers 1 texel a side, the end cut on a slant (the outer edge longer)
function inTail(c, X, Y) {
  const t = c.tail, u = (X - c.from[0]) / TAIL_W, s = (TAIL_TOP - Y) / t.len;          // u 0..1 across, s 0..1 down
  const half = 0.5 - 0.125 * s, uo = t.sx > 0 ? u : 1 - u;                          // uo: 1 = the outer edge
  return Math.abs(u - 0.5) <= half && s <= 1 - 0.3 * (1 - uo);
}
const inside = (c, X, Y, Z = 0) => (c.role === 'drape' ? inDrape(X, Y) : c.role === 'tail' ? inTail(c, X, Y) : c.role === 'side' ? Y >= sideBottom(Z) : true);

// texel centre -> position on the cube surface (cube frame). Face images are read from outside, +y up.
function world(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) * px, v = (y + 0.5) * px, e = 1e-3;
  switch (f) {
    case 'south': return [x0 + u, y1 - v, z1 - e];
    case 'north': return [x1 - u, y1 - v, z0 + e];
    case 'east': return [x1 - e, y1 - v, z1 - u];
    case 'west': return [x0 + e, y1 - v, z0 + u];
    case 'up': return [x1 - u, y1 - e, z1 - v];
    default: return [x1 - u, y0 + e, z0 + v];
  }
}

// ---- the mark -----------------------------------------------------------------------------------------
// Three stroke-only ellipses at 0 / 60 / 120 degrees sharing one centre (rx 90/75/60, ry 32/27/22, the
// website mark), rasterised here at the size it is stitched (MW texels = 4 model px). Each texel gets an
// anti-aliased COVERAGE from its centre's exact distance to the ring's centre line (full inside HALF, a
// 1 texel soft edge), so strokes are an even width, diagonals have no stair-step doubles and crossings are
// clean. The grid is point-symmetric. The rings weave: at each crossing one strand runs over (full) and the
// other dives under (its coverage fades to a third next to the over strand), alternating by quadrant.
const MW = 32, HALF = 0.55, EDGE = 0.9, UNDER = 1.0;
const RINGS = [{ rx: 90, ry: 32, rot: 0 }, { rx: 75, ry: 27, rot: 60 }, { rx: 60, ry: 22, rot: 120 }];
const MARK = (() => {
  const k = (MW / 2 - HALF - 0.5) / 90, N = 2880;
  const poly = RINGS.map(r => {
    const th = (r.rot * Math.PI) / 180, c = Math.cos(th), sn = Math.sin(th), pts = [];
    for (let i = 0; i < N; i++) { const t = (i / N) * 2 * Math.PI, u = r.rx * k * Math.cos(t), v = r.ry * k * Math.sin(t); pts.push([u * c - v * sn, u * sn + v * c]); }
    return pts;
  });
  const cov = new Float32Array(MW * MW), tone = new Float32Array(MW * MW);          // cov 0..1; tone 0..1 = how lit the thread is
  const smooth = t => t * t * (3 - 2 * t);
  for (let y = 0; y < MW; y++) for (let x = 0; x < MW; x++) {
    const px0 = x + 0.5 - MW / 2, py0 = y + 0.5 - MW / 2;
    const near = poly.map(pts => { let d = 1e9, q = null; for (const p of pts) { const e = (p[0] - px0) ** 2 + (p[1] - py0) ** 2; if (e < d) { d = e; q = p; } } return { d: Math.sqrt(d), q }; });
    const quad = Math.floor((Math.atan2(py0, px0) + Math.PI) / (Math.PI / 2)) & 1;
    let best = 0, bi = -1;
    near.forEach((n, i) => {
      let c = 1 - smooth(clamp((n.d - HALF) / EDGE));                                 // anti-aliased stroke coverage
      for (let j = 0; j < 3; j++) {
        if (j === i || near[j].d > HALF + UNDER) continue;
        const over = ((i + j + quad) & 1) ? j : i;                                    // the pair's over strand here
        if (over !== i) c *= 0.3 + 0.7 * smooth(clamp((near[j].d - HALF) / UNDER));   // under: fades beside the over strand
      }
      if (c > best) { best = c; bi = i; }
    });
    if (bi < 0) continue;
    const n = near[bi], nxv = px0 - n.q[0], nyv = py0 - n.q[1], len = Math.hypot(nxv, nyv) || 1;
    cov[y * MW + x] = best;
    // a rounded cord: bright on the upper-left flank, mid body, darker lower-right flank
    tone[y * MW + x] = clamp(0.62 + 0.38 * ((-nxv * 0.55 - nyv * 0.8) / len) * smooth(clamp(n.d / HALF)) - 0.22 * (1 - smooth(clamp(n.d / HALF))) * 0);
  }
  return { cov, tone };
})();
const MARK_CY = 1.0625;                                // centre row 15.5 of the 30-row band (hem above, jaw fold below); x centre 0 = a seam
const markIdx = (X, Y) => { const ix = Math.floor(X * S + MW / 2), iy = Math.floor((MARK_CY - Y) * S + MW / 2); return ix < 0 || iy < 0 || ix >= MW || iy >= MW ? -1 : iy * MW + ix; };
const markCov = (X, Y) => { const i = markIdx(X, Y); return i < 0 ? 0 : MARK.cov[i]; };
const markTone = (X, Y) => { const i = markIdx(X, Y); return i < 0 ? 0 : MARK.tone[i]; };
// thread colour for a 0..1 tone (darkest step .. #e0e0e0), interpolated so the cord shading is smooth
const threadCol = t => { const p = clamp(t) * (TH.length - 1), lo = Math.floor(p); return mix(TH[lo], TH[Math.min(TH.length - 1, lo + 1)], p - lo); };

// ---- the print: small tone-on-tone rings on a half-drop grid ------------------------------------------
// Cloth-space (a, b) in texels; returns true on a ring's 1 px stroke or its centre dot.
function printAt(a, b) {
  const P = 14, row = Math.floor(b / P), off = (row & 1) * (P / 2);
  const cx = Math.floor((a + off) / P) * P - off + P / 2, cy = row * P + P / 2;
  const dx = a + 0.5 - cx, dy = b + 0.5 - cy, r = Math.hypot(dx, dy);
  return Math.abs(r - 2.6) < 0.5 || r < 0.75;
}

// ---- painters: each returns a 0..1 light value, or a colour string to use as is -------------------------
// FRONT: taut over the nose; U-shaped sag folds that strengthen toward the cheeks; rolled top hem.
// Tension creases fan out from the nose bridge toward the ears (diagonal, strongest on the cheeks);
// the cloth over the mouth is taut and calm, so the mark sits on a quiet field.
const NOSE = [0, 3.35];
function frontLight(X, Y) {
  const ax = Math.abs(X), a = Math.atan2(NOSE[1] - Y, ax + 0.35);          // 0 = level with the nose, grows downward
  const env = smoothstep(1.3, 3.3, ax) * (X > 0 ? 1 : 0.85);             // calm over the mouth, folds on the cheeks
  let v = 0.5 + 0.06 * (X / 4.5) + 0.06 * (Y - 1.25);
  // a crease's upper flank faces the light, its lower flank falls into shadow
  v -= env * (0.34 * ridge((a - 0.5) / 0.22) + 0.26 * ridge((a - 0.95) / 0.24));
  v -= 0.1 * smoothstep(0.35, -0.5, Y);                                    // turning under the jaw
  return v;
}
function paintFront(v, f, c) {
  const W = v.w, H = v.h;
  if (f === 'up') { v.paint((x, y) => cl(y === 0 ? 5 : y === H - 1 ? 3 : 6 - (x + y) % 2 * 0)); return; }  // rolled hem top, lit
  if (f === 'down') { v.paint(() => cl(1)); return; }
  if (f !== 'south') { v.paint((x, y) => cl(y < 2 ? 5 : y < 4 ? 3 : 2)); return; }                          // thin ends
  const tex = [];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const [X, Y] = world(c, f, x, y);
    let col;
    if (y === 0) col = cl(6);                                     // hem crest catches the light
    else if (y === 1) col = cl(5);
    else if (y === 2) col = cl(2);                                // shadow under the roll
    else if (y === 3 && (x % 5) < 3 && x > 1 && x < W - 2 && Math.abs(X) > 2.0) col = TH[0];   // running stitch under the hem
    else if (y === H - 1) col = cl(1);                            // the cloth folds under the jaw into the drape
    else if (y === H - 2) col = cloth(frontLight(X, Y) - 0.22, x, y);
    else {
      const L = frontLight(X, Y);
      const base = cloth(L, x, y), cv = markCov(X, Y);
      const sh = clamp(markCov(X - px, Y + px) * 0.8);                       // a soft 1 texel shadow down-right of the thread
      const under = sh > 0 ? mix(base, cloth(L - 0.3, x, y), sh) : base;
      col = cv > 0.02 ? mix(under, threadCol(markTone(X, Y)), cv) : under;
    }
    tex.push([x, y, col]);
  }
  for (const [x, y, col] of tex) v.set(x, y, col);
}

// DRAPE: folds fan up from the point toward the jaw corners; shadowed under the band; hemmed V edge.
function drapeLight(X, Y) {
  const a = X / (Y - DR.y0 + 0.9);                       // fan coordinate around a point just below the tip
  let v = 0.42 + 0.06 * (X / 4.6) + 0.08 * ((Y - DR.y0) / 2.75);
  v += 0.32 * ridge((a - 0.62) / 0.36) + 0.26 * ridge((a + 0.48) / 0.3) + 0.2 * ridge((a - 1.45) / 0.4) + 0.18 * ridge((a + 1.3) / 0.45);
  v -= 0.24 * smoothstep(DR.y1 - 0.6, DR.y1, Y);         // in the band's shadow
  return v;
}
function paintDrape(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y, Z] = world(c, f, x, y);
    if (!inside(c, X, Y)) continue;
    if (f !== 'south' && f !== 'north') { v.set(x, y, cl(f === 'up' ? 2 : 1)); continue; }
    if (f === 'north') { v.set(x, y, cl(1)); continue; }                     // the side against the neck
    const d = Y - drapeBottom(X);                                            // height above the V edge
    const edgeN = !inside(c, X - px, Y) || !inside(c, X + px, Y) || !inside(c, X, Y - px);
    if (edgeN) { v.set(x, y, cl(0)); continue; }
    const L = drapeLight(X, Y);
    if (d < 2.5 * px && d >= px * 1.0) { v.set(x, y, cloth(L + 0.22, x, y)); continue; }     // turned hem: a lit lip
    const st = d >= 3 * px && d < 4 * px && ((x + y) % 5) < 3 && Y < DR.y1 - 0.5;
    if (st) { v.set(x, y, TH[0]); continue; }                                // running stitch along the hem
    if (Y < DR.y1 - 0.55 && printAt(Math.floor(X * S) + 207, Math.floor(-Y * S) + 203)) { v.set(x, y, cloth(L + 0.17, x, y)); continue; }
    v.set(x, y, cloth(L, x, y));
    void Z;
  }
}

// SIDES: creases run back and gather toward the knot; the +x side is the lit one.
function paintSide(v, f, c) {
  const sx = c.sx, H = v.h;
  const outer = (sx > 0 && f === 'east') || (sx < 0 && f === 'west');
  if (f === 'up') { v.paint((x, y) => cl(sx > 0 ? 5 : 4)); return; }
  if (f === 'south') {                                                       // the front end shows beside the front band: same cloth + hem
    for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
      const [, Y] = world(c, f, x, y);
      v.set(x, y, y === 0 ? cl(6) : y === 1 ? cl(5) : y === 2 ? cl(2) : y >= v.h - 1 ? cl(1) : cloth(frontLight(sx * 4.5, Y), x, y));
    }
    return;
  }
  if (!outer) {                                                              // back end, underside, the face against the head
    for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) { const [, Y, Z] = world(c, f, x, y); if (inside(c, 0, Y, Z)) v.set(x, y, cl(1)); }
    return;
  }
  for (let y = 0; y < H; y++) for (let x = 0; x < v.w; x++) {
    const [, Y, Z] = world(c, f, x, y);
    if (!inside(c, 0, Y, Z)) continue;
    const t = sideT(Z), yb = sideBottom(Z), mid = (yb + SIDE.y1) / 2, half = (SIDE.y1 - yb) / 2;
    const q = (Y - mid) / half;                                               // -1 bottom .. 1 top: creases converge toward the knot
    let L = 0.44 + (sx > 0 ? 0.07 : -0.02) + 0.06 * q;
    // two broad folds (wide clusters, never thin streaks); the highlight is capped one step under the hem's
    L -= 0.3 * ridge((q - 0.32) / 0.5) + 0.24 * ridge((q + 0.45) / 0.45) * smoothstep(0.05, 0.5, t);
    L = Math.min(L, 0.7);
    L -= 0.12 * smoothstep(0.25, 0, t);                                       // gathered into the knot: deeper
    const bottomEdge = Y - px < yb;
    let col;
    if (y === 0) col = cl(sx > 0 ? 6 : 5);
    else if (y === 1) col = cl(sx > 0 ? 5 : 4);
    else if (y === 2) col = cl(2);
    else if (bottomEdge) col = cl(0);
    else if (Y - 2 * px < yb) col = cl(2);
    else col = cloth(L, x, y);
    v.set(x, y, col);
  }
}

// BACK band: radial gathers toward the knot.
function paintBack(v, f, c) {
  if (f === 'up') { v.paint(() => cl(4)); return; }
  if (f !== 'north') { v.paint(() => cl(1)); return; }
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    const ang = Math.atan2(Y - KNOT.y, Math.abs(X) + 0.2);
    let L = 0.42 + 0.05 * (X / 4.5) + 0.25 * ridge((ang - 0.18) / 0.22) + 0.2 * ridge((ang + 0.32) / 0.25);
    L -= 0.15 * smoothstep(2.2, 0.8, Math.abs(X));
    const col = y === 0 ? cl(5) : y === 1 ? cl(4) : y === v.h - 1 ? cl(0) : cloth(L, x, y);
    v.set(x, y, col);
  }
}

// KNOT + LOOPS: rounded lumps; a diagonal wrap crease across the core.
function paintLump(v, f, c) {
  const isCore = c.role === 'knot';
  const bias = { up: 0.18, north: 0.0, south: -0.3, east: 0.06, west: -0.06, down: -0.3 }[f];
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const nx = (x + 0.5) / v.w * 2 - 1, ny = (y + 0.5) / v.h * 2 - 1, r = Math.hypot(nx, ny);
    let L = 0.5 + bias - 0.22 * ny - 0.12 * (f === 'north' ? -nx : nx) - 0.25 * smoothstep(0.65, 1.15, r);
    if (isCore && f === 'north') {
      const w = nx * 0.9 + ny * 0.75;                                          // the wrap crossing the core
      if (Math.abs(w) < 0.14) L = 0.08; else if (w > 0.14 && w < 0.42) L += 0.18;
    }
    const corner = (x === 0 || x === v.w - 1) && (y === 0 || y === v.h - 1);
    v.set(x, y, corner ? cl(0) : cloth(L, x, y));
  }
}

// TAILS: a ribbon folded along its length (lit half / shadow half), dark silhouette.
function paintTail(v, f, c) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, Y] = world(c, f, x, y);
    if (!inside(c, X, Y)) continue;
    if (f === 'up') { v.set(x, y, cl(3)); continue; }
    if (f !== 'north' && f !== 'south') { v.set(x, y, cl(1)); continue; }
    const edge = !inside(c, X - px, Y) || !inside(c, X + px, Y) || !inside(c, X, Y - px);
    if (edge) { v.set(x, y, cl(0)); continue; }
    const u = (X - c.from[0]) / TAIL_W, s = (TAIL_TOP - Y) / c.tail.len;
    let L = 0.5 + (f === 'north' ? 0 : -0.12) + 0.3 * ridge((u - 0.5) / 0.5) * (c.tail.sx) - 0.18 * s;
    if (s < 0.12) L -= 0.25;                                                     // tucked under the knot
    v.set(x, y, cloth(L, x, y));
  }
}

const PAINT = { front: paintFront, drape: paintDrape, side: paintSide, back: paintBack, knot: paintLump, loop: paintLump, tail: paintTail };
const strip = ({ role, sx, tail, ...c }) => c;

const bones = [
  { name: 'bandana', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: parts.filter(p => p.role !== 'tail').map(strip) },
  ...TAILS.map(t => ({ name: t.name, parent: 'bandana', pivot: [t.x, TAIL_TOP, TAIL_Z], rotation: t.rest, cubes: parts.filter(p => p.tail === t).map(strip) })),
];
// the tails sway a little at rest and stream back on the move (positive x rotation = the tip goes back)
const motion = [
  { bone: 'tailL', channel: 'rot', axis: 'x', amp: 3, walkAmp: 9, airAmp: 12, period: 1.9, phase: 0 },
  { bone: 'tailR', channel: 'rot', axis: 'x', amp: 3, walkAmp: 9, airAmp: 12, period: 1.9, phase: 0.18 },
  { bone: 'tailL', channel: 'rot', axis: 'z', amp: 2, walkAmp: 3, airAmp: 4, period: 2.7, phase: 0.1 },
  { bone: 'tailR', channel: 'rot', axis: 'z', amp: -2, walkAmp: -3, airAmp: -4, period: 2.7, phase: 0.35 },
];

export default {
  meta: { id: 'bandana-origin', name: 'Origin Bandana', kind: 'bandana', cents: 599, blurb: 'Charcoal cloth over the nose and mouth, the three rings stitched in silver, knotted at the back.' },
  model: { texSize: TEX, scale: S, bones },
  anim: { frames: 1, fps: 1, motion },
  paint() {
    const tex = newTexture({ texSize: TEX, scale: S });
    for (const c of parts) paintCube(tex, c, S, (v, f) => PAINT[c.role](v, f, c));
    return tex;
  },
};
