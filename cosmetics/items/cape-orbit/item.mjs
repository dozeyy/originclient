// Orbit Cape: Origin's signature animated cape. The mark (cape-origin's own rasteriser: constant 2 px
// stroke, 120 degree ring an exact mirror of the 60 degree ring, halo outside the silhouette only,
// 1 px cloth breaks at the over/under crossings) sits on dark folded cloth and NEVER changes between
// frames. Small bright electrons ride each ring's centre line at their own speeds with short fading
// trails, and slip under a ring wherever the thread passes beneath another. Everything is the
// #E0E0E0 family (no hue). Loop: 32 frames; every electron makes a whole number of laps, so frame 32 = frame 0.
import { Canvas, ramp, clamp, smoothstep, mix } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, FRAMES = 32;
const MX = 20, MY = 31, MSIZE = 34;                    // mark: centre on a pixel seam; overall width
const FX = x => 2 * MX - 1 - x, FY = y => 2 * MY - 1 - y;

const clothFine = ramp('#363940', 9, { lo: 0.06, hi: 0.26, tint: 0.025, shadowHue: 232, lightHue: 215, shift: 0.3 });
const cloth = [0, 2, 4, 6, 8].map(i => clothFine[i]);
const OUTLINE = cloth[0];
const RING = '#b4b4b4', RING_EDGE = '#8a8a8a';        // rings sit a step below the electrons so the beads read
// ---------------------------------------------------------------- cloth folds (scale 4: 40x64 art px)
// Same construction as cape-origin (uneven soft folds, 9-step hue-shifted ramp, light from the top-left),
// halved to the 4 px/model-px density of an animated cape.
const VALLEYS = [-1.5, 9.5, 23, 31, 41.5];
const valleyX = (k, y) => 20 + (VALLEYS[k] - 20) * (0.95 + 0.1 * (y / H)) + Math.sin(y * 0.08 + k * 1.9) * 0.8;
function foldProfile(p) {
  const r = 0.38;
  return p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r));
}
function fineLevel(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a));
  const behind = 1 - smoothstep(0.55, 1.15, Math.hypot((x - MX) / 20, (y - MY) / 21));
  const prof = foldProfile(p) * (1 - 0.55 * behind) + 0.4 * 0.55 * behind;
  let t = 0.37 + prof * 0.44 + (0.5 - y / H) * 0.14;
  t -= 0.12 * smoothstep(0.4, 1.1, Math.hypot((x - 20) / 23, (y - 32) / 40));
  t -= 0.07 * behind;
  return clamp(Math.floor(clamp(t) * clothFine.length), 2, clothFine.length - 1);
}
const clothLevel = (x, y) => Math.round(fineLevel(x, y) / 2);
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
  return { owner, cover, gap, pts, X, crossingsSolved: X.every(c => c.over !== undefined) };
}

// ---------------------------------------------------------------- static layer (identical in every frame)
let BASE = null, ARC = null;
function buildBase() {
  const c = newCapeArt(S);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, clothFine[fineLevel(x, y)]);
  // collar: darker gathered band over a seam + stitch row
  for (let y = 1; y < 3; y++) for (let x = 0; x < W; x++) c.set(x, y, cloth[clamp(clothLevel(x, y) - 1, 1, 2)]);
  for (let x = 0; x < W; x++) { c.set(x, 3, cloth[0]); c.set(x, 4, ((x >> 1) & 1) ? cloth[3] : cloth[2]); }
  // hem: crease, catch-light, one stitched line
  const hy = H - 6;
  for (let x = 0; x < W; x++) {
    c.set(x, hy, cloth[0]);
    c.set(x, hy + 1, cloth[clamp(clothLevel(x, hy) + 1, 0, 4)]);
    for (let y = hy + 2; y < H - 1; y++) c.set(x, y, cloth[clamp(clothLevel(x, y), 1, 3)]);
    c.set(x, hy + 3, ((x % 4) === 3) ? cloth[1] : '#7c7c7c');
  }
  const mark = buildMark();
  const out = new Uint8Array(W * H), stack = [[0, 0]];
  out[0] = 1;
  while (stack.length) {
    const [x, y] = stack.pop();
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = x + dx, ny = y + dy, k = ny * W + nx;
      if (nx < 0 || ny < 0 || nx >= W || ny >= H || out[k] || mark.owner.has(k)) continue;
      out[k] = 1; stack.push([nx, ny]);
    }
  }
  const R = 3;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const k = y * W + x; if (!out[k]) continue;
    let d = 99;
    for (let dy = -R; dy <= R; dy++) for (let dx = -R; dx <= R; dx++) if (mark.owner.has((y + dy) * W + x + dx)) d = Math.min(d, Math.hypot(dx, dy));
    if (d <= 1.5) c.set(x, y, mix(c.get(x, y), '#e0e0e0', 0.2));
    else if (d <= 2.5) c.set(x, y, mix(c.get(x, y), '#e0e0e0', 0.08));
  }
  for (const [k, ring] of mark.owner) if (!mark.gap.has(k)) c.set(k % W, (k / W) | 0, mark.cover[ring].get(k) === 'inner' ? RING_EDGE : RING);
  for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
  for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }

  // arc-length tables along each ring's centre line, and every stroke pixel's place on its ring
  const arcs = mark.pts.map(P => {
    const cum = [0];
    for (let i = 1; i <= P.length; i++) { const a = P[i - 1], b = P[i % P.length]; cum.push(cum[i - 1] + Math.hypot(b[0] - a[0], b[1] - a[1])); }
    return { P, cum, len: cum[P.length] };
  });
  const frac = new Map();
  for (const [k, ring] of mark.owner) {
    if (mark.gap.has(k)) continue;
    const cx = (k % W) + 0.5, cy = ((k / W) | 0) + 0.5, A = arcs[ring];
    frac.set(k, A.cum[nearestIndex(A.P, cx, cy)] / A.len);
  }
  BASE = c; ARC = { mark, arcs, frac };
}
function arcPoint(A, u) {                                 // u in [0,1) of the ring's length -> [x, y]
  const target = (((u % 1) + 1) % 1) * A.len;
  let lo = 0, hi = A.P.length;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (A.cum[m] <= target) lo = m; else hi = m; }
  const a = A.P[lo], b = A.P[(lo + 1) % A.P.length], f = (target - A.cum[lo]) / ((A.cum[lo + 1] - A.cum[lo]) || 1);
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
}

// ---------------------------------------------------------------- electrons
// ring, laps per loop (sign = direction), electrons evenly spaced, start offset. Rings 1 and 2 are mirror
// images, so they also run in opposite senses on screen. Whole laps only: the loop closes by itself.
const ORBITS = [
  { ring: 0, laps: 1, n: 2, off: 0.0 },
  { ring: 1, laps: 2, n: 1, off: 0.18 },
  { ring: 2, laps: -3, n: 1, off: 0.62 },
];
const TRAIL = 9;                                          // trail length in px along the ring
const TRAIL_RAMP = ['#b4b4b4', '#c4c4c4', '#d4d4d4', '#e4e4e4', '#f2f2f2'];

export default {
  meta: { id: 'cape-orbit', name: 'Orbit Cape', kind: 'cape', cents: 999, blurb: 'Electrons circle the three rings of the Origin mark.' },
  cape: { scale: S, frames: FRAMES, fps: 12 },
  paint(f) {
    if (!BASE) buildBase();
    const c = BASE.clone(), { mark, arcs, frac } = ARC, t = f / FRAMES;
    const heads = [];
    for (const o of ORBITS) for (let e = 0; e < o.n; e++) heads.push({ ring: o.ring, dir: Math.sign(o.laps), u: ((t * o.laps + o.off + e / o.n) % 1 + 1) % 1 });
    // trails first (they are on the stroke pixels), then the beads on top
    for (const [k, s] of frac) {
      const ring = mark.owner.get(k);
      let best = 0;
      for (const h of heads) {
        if (h.ring !== ring) continue;
        const behind = (((h.u - s) * h.dir % 1) + 1) % 1 * arcs[ring].len;     // px behind the head along the ring's travel
        if (behind > 0.4 && behind < TRAIL) best = Math.max(best, (1 - behind / TRAIL) ** 1.35);
      }
      if (best > 0.04) {
        const inner = mark.cover[ring].get(k) === 'inner';
        const i = Math.min(TRAIL_RAMP.length - 1, Math.floor(best * (TRAIL_RAMP.length - (inner ? 1.4 : 0.4))));
        c.set(k % W, (k / W) | 0, TRAIL_RAMP[Math.max(0, i)]);
      }
    }
    for (const h of heads) {
      const [px, py] = arcPoint(arcs[h.ring], h.u);
      for (let y = Math.floor(py - 3); y <= Math.ceil(py + 3); y++) for (let x = Math.floor(px - 3); x <= Math.ceil(px + 3); x++) {
        if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) continue;
        const k = y * W + x, owner = mark.owner.get(k);
        if (owner !== undefined && owner !== h.ring && !mark.gap.has(k)) continue;     // passes under the ring that is over it here
        const d = Math.hypot(x + 0.5 - px, y + 0.5 - py);
        if (d <= 0.9) c.set(x, y, '#ffffff');
        else if (d <= 1.6) c.set(x, y, '#e6e6e6');
        else if (d <= 2.3) c.set(x, y, mix(c.get(x, y), '#e0e0e0', 0.26));
      }
    }
    return c;
  },
};
