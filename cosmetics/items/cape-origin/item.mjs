// The brand cape: dark cloth with four broad vertical folds, the Origin mark
// stitched on in silver thread, one clean stitched hem. Static, scale 8: painted
// 1 art pixel = 1 texture pixel on the 80x128 outside face (one density per item).
//
// The mark is rasterised HERE (not lib/marks) so every ring holds a constant 2 px
// stroke: per pixel we take the true distance to the ring's centre line and keep
// the pixels whose offset along the stroke's dominant axis falls in [-1, 1), which
// is exactly 2 pixels per row/column wherever the ring runs. The inner pixel is the
// darker edge. Crossings alternate over/under along every ring.
import { Canvas, ramp, clamp, smoothstep, mix } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 8, W = 10 * S, H = 16 * S;
const MX = 40, MY = 62, MSIZE = 68;                    // mark: centre and overall width

// 5-step cloth: lit ridge (4) -> dark valley (0). Near-neutral, a hair cool in shadow.
const cloth = ramp('#3a3d42', 5, { lo: 0.07, hi: 0.36, tint: 0.025, shadowHue: 232, lightHue: 215, shift: 0.3 });
const THREAD = ['#6a6a6a', '#8e8e8e', '#b6b6b6', '#e0e0e0', '#f5f5f5'];   // monochrome, #E0E0E0 is the body
const GAP = '#383838';                                  // thread dipping under another ring
const OUTLINE = cloth[0];

// ---------------------------------------------------------------- cloth folds
// Valley lines at the top of the cape (x, relative to the centre line); they fan out
// toward the hem and sway a little, so the folds read as hanging cloth, not stripes.
const VALLEYS = [-9, 16, 42, 62, 88];
const valleyX = (k, y) => 40 + (VALLEYS[k] - 40) * (0.8 + 0.3 * (y / H)) + Math.sin(y * 0.045 + k * 1.7) * 2.2;
// Lit value 0..1 across one fold: valley (dark) -> sharp lit ridge -> long shaded slope. Light from the top-left.
function foldProfile(p) {
  if (p < 0.3) return 0.1 + 0.9 * smoothstep(0, 0.3, p);
  return 1 - Math.pow(smoothstep(0.3, 1, p), 0.8) * 0.97;
}
function clothLevel(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a));
  let t = foldProfile(p);
  t = 0.17 + t * 0.7 + (0.5 - y / H) * 0.2;            // brighter near the collar, darker toward the hem
  t -= 0.14 * smoothstep(0.4, 1.1, Math.hypot((x - 40) / 46, (y - 64) / 80));   // soft vignette
  return clamp(Math.floor(clamp(t) * cloth.length), 0, cloth.length - 1);
}

// ---------------------------------------------------------------- the mark
// Rings as in website/store.html #mark (rx 90/75/60, ry 32/27/22), rotated 0/60/120.
function ringGeometry() {
  const k = (MSIZE / 2 - 1) / 90, open = 1.15;
  return [{ rx: 90, ry: 32, rot: 0 }, { rx: 75, ry: 27, rot: 60 }, { rx: 60, ry: 22, rot: 120 }]
    .map(r => ({ rx: r.rx * k, ry: r.ry * k * open, rot: r.rot * Math.PI / 180 }));
}
function ringPoints(r, n = 2400) {
  const c = Math.cos(r.rot), s = Math.sin(r.rot), pts = [];
  for (let i = 0; i < n; i++) {
    const t = (i / n) * Math.PI * 2, u = r.rx * Math.cos(t), v = r.ry * Math.sin(t);
    pts.push([MX + u * c - v * s, MY + u * s + v * c, t]);
  }
  return pts;
}
// Over/under: pick which ring is on top at each of the 12 crossings so that every ring
// alternates over, under, over, under around its whole length (real interlocking thread).
function crossingsAndOrder(pts) {
  const X = [];
  for (let a = 0; a < 3; a++) for (let b = a + 1; b < 3; b++) {
    const hits = [];
    for (let i = 0; i < pts[a].length; i++) {
      const p = pts[a][i], q = pts[a][(i + 1) % pts[a].length];
      // sign change of "inside ring b" test along ring a marks a crossing
      const fa = insideF(b, p[0], p[1]), fb = insideF(b, q[0], q[1]);
      if ((fa - 1) * (fb - 1) < 0) hits.push([(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, i]);
    }
    for (const h of hits) X.push({ a, b, x: h[0], y: h[1], ia: h[2] });
  }
  // index of each crossing along its two rings
  for (const c of X) c.ib = nearestIndex(pts[c.b], c.x, c.y);
  for (let r = 0; r < 3; r++) {
    const mine = X.filter(c => c.a === r || c.b === r).sort((p, q) => (p.a === r ? p.ia : p.ib) - (q.a === r ? q.ia : q.ib));
    mine.forEach((c, i) => { (c.pos ||= {})[r] = i; });
  }
  // brute force a consistent assignment: bit = 1 means ring a is over ring b
  const n = X.length;
  for (let m = 0; m < (1 << n); m++) {
    let ok = true;
    for (let r = 0; r < 3 && ok; r++) {
      const mine = X.filter(c => c.a === r || c.b === r);
      const ov = mine.map(c => ((m >> X.indexOf(c)) & 1) ? c.a === r : c.b === r);   // is ring r on top here
      const byPos = new Array(mine.length);
      mine.forEach((c, i) => { byPos[c.pos[r]] = ov[i]; });
      for (let i = 0; i < byPos.length; i++) if (byPos[i] === byPos[(i + 1) % byPos.length]) { ok = false; break; }
    }
    if (ok) { X.forEach((c, i) => { c.over = ((m >> i) & 1) ? c.a : c.b; }); return X; }
  }
  X.forEach((c, i) => { c.over = (i & 1) ? c.a : c.b; });           // fallback: simple alternate
  return X;
}
let GEO;
const insideF = (i, x, y) => {
  const r = GEO[i], c = Math.cos(r.rot), s = Math.sin(r.rot), dx = x - MX, dy = y - MY, u = dx * c + dy * s, v = -dx * s + dy * c;
  return (u / r.rx) ** 2 + (v / r.ry) ** 2;
};
const nearestIndex = (pts, x, y) => { let best = 0, bd = 1e9; pts.forEach((p, i) => { const d = (p[0] - x) ** 2 + (p[1] - y) ** 2; if (d < bd) { bd = d; best = i; } }); return best; };

// Returns Map(y*W+x -> { ring, inner, lit }) for every thread pixel, plus the shadowed (dipping) ones.
function buildMark() {
  GEO = ringGeometry();
  const pts = GEO.map(r => ringPoints(r)), X = crossingsAndOrder(pts);
  const cover = [new Map(), new Map(), new Map()];                     // per ring: pixel -> { inner, nx, ny }
  const R = MSIZE / 2 + 4;
  for (let y = Math.floor(MY - R); y <= Math.ceil(MY + R); y++) for (let x = Math.floor(MX - R); x <= Math.ceil(MX + R); x++) {
    const cx = x + 0.5, cy = y + 0.5;
    for (let i = 0; i < 3; i++) {
      let bd = 1e9, bx = 0, by = 0;
      for (const p of pts[i]) { const d = (p[0] - cx) ** 2 + (p[1] - cy) ** 2; if (d < bd) { bd = d; bx = p[0]; by = p[1]; } }
      const dist = Math.sqrt(bd); if (dist > 2.2) continue;
      let nx = dist > 1e-6 ? (cx - bx) / dist : 0, ny = dist > 1e-6 ? (cy - by) / dist : 0;
      const out = insideF(i, cx, cy) >= 1;
      const sd = out ? dist : -dist;
      if (!out) { nx = -nx; ny = -ny; }                                   // keep the normal pointing outward
      const dom = Math.max(Math.abs(nx), Math.abs(ny)) || 1, off = sd / dom;
      if (off >= -1 && off < 1) cover[i].set(y * W + x, { inner: off < 0, nx, ny });
    }
  }
  const owner = new Map();                                                // pixel -> ring that is visible there
  for (let i = 0; i < 3; i++) for (const k of cover[i].keys()) {
    if (!owner.has(k)) { owner.set(k, i); continue; }
    const j = owner.get(k), px = k % W, py = (k / W) | 0;
    let near = null, nd = 1e9;                                            // nearest crossing between the two rings
    for (const c of X) if ((c.a === i && c.b === j) || (c.a === j && c.b === i)) { const d = (c.x - px) ** 2 + (c.y - py) ** 2; if (d < nd) { nd = d; near = c; } }
    owner.set(k, near.over);
  }
  // under-thread pixels hugging the over ring dip into shadow: a 1 px gap each side
  const dip = new Set();
  for (const [k, i] of owner) {
    const px = k % W, py = (k / W) | 0;
    let near = null, nd = 1e9;
    for (const c of X) if (c.a === i || c.b === i) { const d = (c.x - px) ** 2 + (c.y - py) ** 2; if (d < nd) { nd = d; near = c; } }
    if (!near || nd > 36 || near.over === i) continue;
    const o = near.over;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) if (owner.get((py + dy) * W + px + dx) === o) { dip.add(k); }
  }
  return { owner, cover, dip };
}

const LIGHT = [-0.6, -0.8];
function threadColor(ring, k, mark) {
  const c = mark.cover[ring].get(k), lit = 0.5 + 0.5 * (c.nx * LIGHT[0] + c.ny * LIGHT[1]);   // outward normal vs light
  const step = lit > 0.64 ? 1 : lit < 0.3 ? -1 : 0;
  return c.inner ? THREAD[clamp(1 + step, 0, 2)] : THREAD[clamp(3 + step, 2, 4)];
}

export default {
  meta: { id: 'cape-origin', name: 'Origin Cape', kind: 'cape', cents: 599, blurb: 'The three rings, stitched in silver on dark folded cloth.' },
  cape: { scale: S, frames: 1, fps: 10 },
  paint() {
    const c = newCapeArt(S);
    // ------- cloth: broad folds, 5-step ramp, no dither
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, cloth[clothLevel(x, y)]);

    // ------- collar: darker gathered band under a seam
    for (let y = 1; y < 7; y++) for (let x = 0; x < W; x++) c.set(x, y, cloth[clamp(clothLevel(x, y) - 1, 1, 3)]);
    for (let x = 0; x < W; x++) { c.set(x, 7, cloth[0]); c.set(x, 8, ((x >> 1) & 1) ? cloth[3] : cloth[2]); }   // seam + stitch row

    // ------- hem: one fold-over with a single stitched line
    const hy = H - 9;
    for (let x = 0; x < W; x++) {
      c.set(x, hy, cloth[0]);                                              // crease
      c.set(x, hy + 1, cloth[clamp(clothLevel(x, hy) + 1, 0, 4)]);        // catch-light on the folded edge
      for (let y = hy + 2; y < H - 1; y++) c.set(x, y, cloth[clamp(clothLevel(x, y) - 0, 1, 3)]);
      c.set(x, hy + 3, ((x % 4) === 3) ? cloth[1] : '#9a9a9a');           // the stitch: 3 px thread, 1 px gap
      c.set(x, hy + 4, cloth[0]);                                          // shadow under the thread
    }

    // ------- the mark: halo first, then thread
    const mark = buildMark();
    const near = (k, r) => { const px = k % W, py = (k / W) | 0; let best = 99; for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) if (mark.owner.has((py + dy) * W + px + dx)) best = Math.min(best, Math.hypot(dx, dy)); return best; };
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const k = y * W + x; if (mark.owner.has(k)) continue;
      const d = near(k, 3);
      if (d <= 1.5) c.set(x, y, mix(c.get(x, y), '#e0e0e0', 0.26));         // halo step 1
      else if (d <= 2.5) c.set(x, y, mix(c.get(x, y), '#e0e0e0', 0.1));     // halo step 2
    }
    for (const [k, ring] of mark.owner) c.set(k % W, (k / W) | 0, mark.dip.has(k) ? GAP : threadColor(ring, k, mark));

    // ------- 1 px dark outline all round (replaces the old side frame)
    for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
    for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
    return c;
  },
};
