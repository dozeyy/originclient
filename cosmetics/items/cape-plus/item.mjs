// Origin+ cape (members only, never sold): the premium sibling of cape-origin. Same hand-rasterised
// three-ring mark and fold construction, but deeper near-black cloth with a faint satin sheen, a fine
// 1 px silver trim down both sides and along the hem, a wider soft halo around the mark, and a stitched
// "+" between two thread rules below it. Monochrome throughout (#E0E0E0 is the only accent). Static, scale 8: painted
// 1 art pixel = 1 texture pixel on the 80x128 outside face (one density per item).
//
// The mark is rasterised HERE (not lib/marks) so every ring holds a constant 2 px
// stroke: per pixel we take the true distance to the ring's centre line and keep
// the pixels whose offset along the stroke's dominant axis falls in [-1, 1), which
// is exactly 2 pixels per row/column wherever the ring runs. The mark is exactly
// symmetric: the 0 degree ring is mirrored on both axes, the 60 degree ring is
// point-symmetric, and the 120 degree ring is the pixel-for-pixel mirror of the 60.
// Crossings alternate over/under along every ring; each break is a 1 px cloth gap.
import { Canvas, ramp, clamp, smoothstep, mix } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 8, W = 10 * S, H = 16 * S;
const MX = 40, MY = 62, MSIZE = 68;                    // mark: centre (on a pixel seam) and overall width
const FX = x => 2 * MX - 1 - x, FY = y => 2 * MY - 1 - y;   // mirror a pixel column / row through the centre

// 5-step cloth: lit ridge (4) -> dark valley (0). Near-neutral, a hair cool in shadow, low contrast.
// The body uses the fine 9-step version so every fold edge has in-between tones; seams, collar
// and hem use the 5 coarse steps picked from the same ramp.
const clothFine = ramp('#202227', 9, { lo: 0.025, hi: 0.2, tint: 0.02, shadowHue: 232, lightHue: 215, shift: 0.3 });
const cloth = [0, 2, 4, 6, 8].map(i => clothFine[i]);
const BODY = '#e0e0e0', EDGE = '#cfcfcf';              // thread: #E0E0E0 body, a softer inner edge pixel
const OUTLINE = cloth[0];

// ---------------------------------------------------------------- cloth folds
// Valley lines at the top of the cape (x, relative to the centre line): four folds of UNEVEN width.
// They fan out toward the hem and sway a little, so they read as hanging cloth, not stripes.
const VALLEYS = [-3, 19, 46, 62, 83];
const valleyX = (k, y) => 40 + (VALLEYS[k] - 40) * (0.95 + 0.1 * (y / H)) + Math.sin(y * 0.04 + k * 1.9) * 1.6;
// Soft lit value 0..1 across one fold: dark valley -> broad lit ridge -> long shaded slope (light from top-left).
function foldProfile(p) {
  const r = 0.38;
  return p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r));
}
function fineLevel(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a));
  const behind = 1 - smoothstep(0.55, 1.15, Math.hypot((x - MX) / 40, (y - MY) / 42));     // 1 at the mark, 0 away from it
  const prof = foldProfile(p) * (1 - 0.55 * behind) + 0.4 * 0.55 * behind;                 // folds dim and flatten behind the mark
  let t = 0.33 + prof * 0.5 + (0.5 - y / H) * 0.14;      // brighter near the collar, darker toward the hem
  t -= 0.12 * smoothstep(0.4, 1.1, Math.hypot((x - 40) / 46, (y - 64) / 80));   // soft vignette
  t -= 0.07 * behind;
  return clamp(Math.floor(clamp(t) * clothFine.length), 2, clothFine.length - 1);   // steps 0-1 are kept for outlines and seams
}
const clothLevel = (x, y) => Math.round(fineLevel(x, y) / 2);          // the same value on the coarse 5-step scale

// ---------------------------------------------------------------- the mark
// Ring 0: horizontal. Ring 1: 60 degrees. Ring 2: 120 degrees = ring 1 mirrored left/right,
// so the two tilted rings share one size (the three-ring mark, drawn symmetric).
function ringGeometry() {
  const k = (MSIZE / 2 - 1) / 90, open = 1.38;
  return [{ rx: 90, ry: 32, rot: 0 }, { rx: 78, ry: 29, rot: 60 }, { rx: 78, ry: 29, rot: 120 }]
    .map(r => ({ rx: r.rx * k, ry: r.ry * k * open, rot: r.rot * Math.PI / 180 }));
}
let GEO;
function ringPoints(r, n = 2400) {
  const c = Math.cos(r.rot), s = Math.sin(r.rot), pts = [];
  for (let i = 0; i < n; i++) {
    const t = (i / n) * Math.PI * 2, u = r.rx * Math.cos(t), v = r.ry * Math.sin(t);
    pts.push([MX + u * c - v * s, MY + u * s + v * c]);
  }
  return pts;
}
const insideF = (i, x, y) => {
  const r = GEO[i], c = Math.cos(r.rot), s = Math.sin(r.rot), dx = x - MX, dy = y - MY, u = dx * c + dy * s, v = -dx * s + dy * c;
  return (u / r.rx) ** 2 + (v / r.ry) ** 2;
};
const nearestIndex = (pts, x, y) => { let best = 0, bd = 1e9; pts.forEach((p, i) => { const d = (p[0] - x) ** 2 + (p[1] - y) ** 2; if (d < bd) { bd = d; best = i; } }); return best; };

// Over/under: choose which ring is on top at each of the 12 crossings so every ring
// alternates over, under, over, under around its whole length (real interlocking thread).
function crossingsAndOrder(pts) {
  const X = [];
  for (let a = 0; a < 3; a++) for (let b = a + 1; b < 3; b++) {
    for (let i = 0; i < pts[a].length; i++) {
      const p = pts[a][i], q = pts[a][(i + 1) % pts[a].length];
      if ((insideF(b, p[0], p[1]) - 1) * (insideF(b, q[0], q[1]) - 1) < 0) X.push({ a, b, x: (p[0] + q[0]) / 2, y: (p[1] + q[1]) / 2, ia: i });
    }
  }
  for (const c of X) c.ib = nearestIndex(pts[c.b], c.x, c.y);
  for (let r = 0; r < 3; r++) {
    X.filter(c => c.a === r || c.b === r).sort((p, q) => (p.a === r ? p.ia : p.ib) - (q.a === r ? q.ia : q.ib)).forEach((c, i) => { (c.pos ||= {})[r] = i; });
  }
  const n = X.length;
  for (let m = 0; m < (1 << n); m++) {           // bit set = ring a over ring b
    let ok = true;
    for (let r = 0; r < 3 && ok; r++) {
      const mine = X.filter(c => c.a === r || c.b === r), byPos = new Array(mine.length);
      mine.forEach(c => { byPos[c.pos[r]] = ((m >> X.indexOf(c)) & 1) ? c.a === r : c.b === r; });
      for (let i = 0; i < byPos.length; i++) if (byPos[i] === byPos[(i + 1) % byPos.length]) { ok = false; break; }
    }
    if (ok) { X.forEach((c, i) => { c.over = ((m >> i) & 1) ? c.a : c.b; }); return X; }
  }
  X.forEach((c, i) => { c.over = (i & 1) ? c.a : c.b; });
  return X;
}

// Is pixel (x, y) in ring i's 2 px stroke? -> null | 'outer' | 'inner'. Computed for ONE pixel of each
// symmetry class and copied to the rest, so the mark is exactly symmetric.
function buildMark() {
  GEO = ringGeometry();
  const pts = GEO.map(r => ringPoints(r)), X = crossingsAndOrder(pts);
  const memo = new Map();
  const raw = (i, x, y) => {                       // true geometry at exactly this pixel
    const key = i * 1e6 + y * 1e3 + x; if (memo.has(key)) return memo.get(key);
    const cx = x + 0.5, cy = y + 0.5;
    let bd = 1e9, bx = 0, by = 0;
    for (const p of pts[i]) { const d = (p[0] - cx) ** 2 + (p[1] - cy) ** 2; if (d < bd) { bd = d; bx = p[0]; by = p[1]; } }
    const dist = Math.sqrt(bd); let res = null;
    if (dist <= 2.2) {
      let nx = dist > 1e-6 ? (cx - bx) / dist : 0, ny = dist > 1e-6 ? (cy - by) / dist : 0;
      const out = insideF(i, cx, cy) >= 1;
      if (!out) { nx = -nx; ny = -ny; }
      const off = (out ? dist : -dist) / (Math.max(Math.abs(nx), Math.abs(ny)) || 1);
      if (off >= -1 && off < 1) res = off < 0 ? 'inner' : 'outer';
    }
    memo.set(key, res); return res;
  };
  const stroke = (i, x, y) => {
    if (i === 2) return stroke(1, FX(x), y);                         // 120 degrees = 60 degrees mirrored left/right
    if (i === 1) return y < MY ? raw(1, x, y) : raw(1, FX(x), FY(y)); // 60 degrees: point-symmetric
    return raw(0, Math.min(x, FX(x)), Math.min(y, FY(y)));            // 0 degrees: mirrored on both axes
  };
  const R = Math.ceil(MSIZE / 2 + 4), cover = [new Map(), new Map(), new Map()];
  for (let y = MY - R; y <= MY + R; y++) for (let x = MX - R; x <= MX + R; x++) {
    for (let i = 0; i < 3; i++) { const s = stroke(i, x, y); if (s) cover[i].set(y * W + x, s); }
  }
  const owner = new Map();                          // pixel -> ring visible there
  for (let i = 0; i < 3; i++) for (const k of cover[i].keys()) {
    if (!owner.has(k)) { owner.set(k, i); continue; }
    const j = owner.get(k), px = k % W, py = (k / W) | 0;
    let near = null, nd = 1e9;
    for (const c of X) if ((c.a === i && c.b === j) || (c.a === j && c.b === i)) { const d = (c.x - px) ** 2 + (c.y - py) ** 2; if (d < nd) { nd = d; near = c; } }
    owner.set(k, near.over);
  }
  // the break: under-thread pixels touching (4-neighbour) the ring that passes over them are left as cloth
  const gap = new Set();
  for (const [k, i] of owner) {
    const px = k % W, py = (k / W) | 0;
    let near = null, nd = 1e9;
    for (const c of X) if (c.a === i || c.b === i) { const d = (c.x - px) ** 2 + (c.y - py) ** 2; if (d < nd) { nd = d; near = c; } }
    if (!near || nd > 36 || near.over === i) continue;
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) if (owner.get((py + dy) * W + px + dx) === near.over) gap.add(k);
  }
  return { owner, cover, gap, crossingsSolved: X.every(c => c.over !== undefined) };
}

export default {
  meta: { id: 'cape-plus', name: 'Origin+ Cape', kind: 'cape', cents: 0, plusOnly: true, onSale: false, blurb: 'Members only. Satin-black cloth, silver trim, the three rings and a stitched plus.' },
  cape: { scale: S, frames: 1 },
  paint() {
    const c = newCapeArt(S);
    // ------- cloth: four soft folds, 5-step ramp, no dither
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, clothFine[fineLevel(x, y)]);

    // ------- collar: darker gathered band under a seam
    for (let y = 1; y < 7; y++) for (let x = 0; x < W; x++) c.set(x, y, cloth[clamp(clothLevel(x, y) - 1, 1, 2)]);
    for (let x = 0; x < W; x++) { c.set(x, 7, cloth[0]); c.set(x, 8, ((x >> 1) & 1) ? cloth[3] : cloth[2]); }   // seam + stitch row

    // ------- hem: one fold-over with a single stitched line
    const hy = H - 9;
    for (let x = 0; x < W; x++) {
      c.set(x, hy, cloth[0]);                                              // crease
      c.set(x, hy + 1, cloth[clamp(clothLevel(x, hy) + 1, 0, 4)]);        // catch-light on the folded edge
      for (let y = hy + 2; y < H - 1; y++) c.set(x, y, cloth[clamp(clothLevel(x, y), 1, 3)]);
      c.set(x, hy + 3, ((x % 4) === 3) ? cloth[1] : '#9a9a9a');           // the stitch: 3 px thread, 1 px gap
      c.set(x, hy + 4, cloth[0]);                                          // shadow under the thread
    }

    // ------- the mark: halo outside the silhouette only, then thread
    const mark = buildMark();
    const out = new Uint8Array(W * H), stack = [[0, 0]];                    // flood fill: what the silhouette does not enclose
    out[0] = 1;
    while (stack.length) {
      const [x, y] = stack.pop();
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const nx = x + dx, ny = y + dy, k = ny * W + nx;
        if (nx < 0 || ny < 0 || nx >= W || ny >= H || out[k] || mark.owner.has(k)) continue;
        out[k] = 1; stack.push([nx, ny]);
      }
    }
    const R = 7;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const k = y * W + x; if (!out[k]) continue;
      let d = 99;
      for (let dy = -R; dy <= R; dy++) for (let dx = -R; dx <= R; dx++) if (mark.owner.has((y + dy) * W + x + dx)) d = Math.min(d, Math.hypot(dx, dy));
      const a = d <= 1.5 ? 0.34 : d <= 2.5 ? 0.2 : d <= 3.6 ? 0.11 : d <= 5 ? 0.055 : d <= 7 ? 0.022 : 0;   // five stepped rings, outside the silhouette only
      if (a) c.set(x, y, mix(c.get(x, y), '#e0e0e0', a));
    }
    for (const [k, ring] of mark.owner) if (!mark.gap.has(k)) c.set(k % W, (k / W) | 0, mark.cover[ring].get(k) === 'inner' ? EDGE : BODY);

    // ------- the plus: a 2 px stitched cross on the seam (40, 106) between two thread rules
    const PY = 106;
    const plusPx = new Set();
    for (let i = -6; i <= 5; i++) { plusPx.add(`${40 + i},${PY - 1}`); plusPx.add(`${40 + i},${PY}`); plusPx.add(`${39},${PY + i}`); plusPx.add(`${40},${PY + i}`); }
    for (let i = 0; i < 12; i++) { plusPx.add(`${40 - 12 - i},${PY - 1}`); plusPx.add(`${40 + 11 + i},${PY - 1}`); }   // rules: 1 px, fading outward
    for (let y = PY - 12; y <= PY + 11; y++) for (let x = 40 - 26; x <= 40 + 25; x++) {          // soft halo for the plus, outside its pixels
      if (plusPx.has(`${x},${y}`)) continue;
      let d = 99;
      for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) if (plusPx.has(`${x + dx},${y + dy}`)) d = Math.min(d, Math.hypot(dx, dy));
      const a = d <= 1.5 ? 0.24 : d <= 2.5 ? 0.1 : d <= 3.2 ? 0.04 : 0;
      if (a) c.set(x, y, mix(c.get(x, y), '#e0e0e0', a));
    }
    for (let i = -6; i <= 5; i++) {
      const edge = (i === -6 || i === 5);
      for (const [x, y] of [[40 + i, PY - 1], [40 + i, PY], [39, PY + i], [40, PY + i]]) c.set(x, y, edge ? EDGE : BODY);
    }
    for (let i = 0; i < 12; i++) {                                              // rules fade 1 px thread -> dim
      const col = mix('#e0e0e0', cloth[2], i / 12 * 0.9);
      c.set(40 - 12 - i, PY - 1, col); c.set(40 + 11 + i, PY - 1, col);
    }

    // ------- fine silver trim: 1 px, inset 3 px from each side edge, lit from the top-left (dims toward the hem)
    const trimTop = 11, trimBot = H - 14;
    for (let y = trimTop; y <= trimBot; y++) {
      const f = 1 - (y - trimTop) / (trimBot - trimTop);
      c.set(3, y, mix(cloth[2], '#c4c4c4', 0.12 + 0.8 * f * f));        // fades out toward the hem: no closed frame
      c.set(W - 4, y, mix(cloth[2], '#c4c4c4', 0.1 + 0.62 * f * f));
    }
    // 1 px dark shadow just inside the trim so it reads as a seam, not a stripe
    for (let y = trimTop; y <= trimBot; y++) { c.set(4, y, cloth[0]); c.set(W - 5, y, cloth[0]); }

    // ------- 1 px dark outline all round
    for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
    for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
    return c;
  },
};
