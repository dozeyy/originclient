// Glitch Cape: a dark digital cloth (fine scanlines, a few data traces) with a clean Origin mark that
// glitches in two short bursts per loop: horizontal slice offsets, an RGB split, scanline flicker.
// Most of the 32 frames are calm (the mark is exactly cape-origin's rasteriser, crisp, never moving);
// a slow scan band is the only thing that drifts, from off-canvas to off-canvas, so the loop is seamless.
// Burst frames are hand-scheduled below (no random per frame: every slice is a deliberate table row).
import { Canvas, ramp, clamp, smoothstep, mix, rng } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, FRAMES = 32;
const MX = 20, MY = 31, MSIZE = 36, OPEN = 1.0;      // mark: centre on a pixel seam; overall width; minor-axis fatness
const FX = x => 2 * MX - 1 - x, FY = y => 2 * MY - 1 - y;

const clothFine = ramp('#2b2f3a', 9, { lo: 0.05, hi: 0.24, tint: 0.03, shadowHue: 232, lightHue: 205, shift: 0.3 });
const cloth = [0, 2, 4, 6, 8].map(i => clothFine[i]);
const OUTLINE = cloth[0];
const BODY = '#e0e0e0', EDGE = '#c4c4c4';
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
  const k = (MSIZE / 2 - 1) / 90, open = OPEN;
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

// ---------------------------------------------------------------- static layer
let BASE = null;
function buildBase() {
  const c = newCapeArt(S);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let i = fineLevel(x, y);
    if (y & 1) i = Math.max(1, i - 1);                      // scanlines: every second row one step darker
    c.set(x, y, clothFine[i]);
  }
  // data traces: three short stepped lines of 1 px, low contrast, fixed
  const trace = (x0, y0, pts) => { let x = x0, y = y0; for (const [dx, dy] of pts) { for (let i = 0; i < Math.abs(dx || dy); i++) { x += Math.sign(dx); y += Math.sign(dy); c.set(x, y, clothFine[(y & 1) ? 3 : 4]); } } c.set(x0, y0, clothFine[5]); c.set(x, y, clothFine[5]); };
  trace(4, 10, [[5, 0], [0, 3], [4, 0]]);
  trace(35, 15, [[-4, 0], [0, -3], [-3, 0]]);
  trace(5, 50, [[3, 0], [0, 4], [6, 0]]);
  trace(34, 47, [[-6, 0], [0, 3], [-3, 0]]);
  for (let y = 1; y < 3; y++) for (let x = 0; x < W; x++) c.set(x, y, cloth[clamp(clothLevel(x, y) - 1, 1, 2)]);
  for (let x = 0; x < W; x++) { c.set(x, 3, cloth[0]); c.set(x, 4, ((x >> 1) & 1) ? cloth[3] : cloth[2]); }
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
  for (const [k, ring] of mark.owner) if (!mark.gap.has(k)) c.set(k % W, (k / W) | 0, mark.cover[ring].get(k) === 'inner' ? EDGE : BODY);
  for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
  for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
  BASE = c;
}

// ---------------------------------------------------------------- the glitch schedule
// frame -> { split: px of RGB split, bands: [[y, height, dx]], flick: scanline darkening 0..1, dim: mark flicker }
// Two bursts: frames 9-11 (the big one) and 23-24 (a short echo), plus a 1 px split twitch on frame 17.
const SCHEDULE = {
  9:  { split: 1, bands: [[24, 3, 3]], flick: 0.18, dim: 0 },
  10: { split: 1, bands: [[16, 3, -5], [27, 6, 6], [42, 2, -4]], flick: 0.4, dim: 0.2 },
  11: { split: 1, bands: [[33, 3, -3], [20, 2, 2]], flick: 0.22, dim: 0 },
  17: { split: 1, bands: [], flick: 0, dim: 0 },
  23: { split: 1, bands: [[19, 4, 5], [36, 2, -5]], flick: 0.3, dim: 0.15 },
  24: { split: 1, bands: [[27, 3, -2]], flick: 0.14, dim: 0 },
};

function glitchFrame(src, g, f) {
  const out = src.clone();
  const row = y => {                                           // horizontal offset of row y (interior wraps, outline column stays)
    for (const [by, bh, dx] of g.bands) if (y >= by && y < by + bh) return dx;
    return 0;
  };
  const inBand = y => g.bands.some(([by, bh]) => y >= by && y < by + bh);
  for (let y = 1; y < H - 1; y++) {
    const dx = row(y), d = (y < 6 || y > H - 8) ? 0 : g.split + (inBand(y) && g.split > 1 ? 1 : 0);
    for (let x = 1; x < W - 1; x++) {
      const wrap = v => 1 + (((v - 1) % (W - 2)) + (W - 2)) % (W - 2);
      const sx = wrap(x - dx);
      const cr = src.get(wrap(sx + d), y), cg = src.get(sx, y), cb = src.get(wrap(sx - d), y);
      out.set(x, y, [cr[0], cg[1], cb[2]]);
    }
  }
  if (g.flick > 0) {
    for (let y = 1; y < H - 1; y++) {
      const roll = ((y + f * 5) % 8) < 2;                      // a rolling pair of dark lines
      const k = (y & 1 ? g.flick * 0.55 : 0) + (roll ? g.flick * 0.7 : 0);
      if (k > 0) for (let x = 1; x < W - 1; x++) { const p = out.get(x, y); out.set(x, y, mix(p, '#0a0c10', k)); }
    }
  }
  if (g.dim > 0) {                                             // mark flickers down for one frame
    for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
      const p = out.get(x, y);
      if (p[0] > 150 || p[1] > 150) out.set(x, y, mix(p, '#2b2f3a', g.dim));
    }
  }
  return out;
}

export default {
  meta: { id: 'cape-glitch', name: 'Glitch Cape', kind: 'cape', cents: 999, blurb: 'A clean Origin mark that tears and splits in brief digital bursts.' },
  cape: { scale: S, frames: FRAMES, fps: 12 },
  paint(f) {
    if (!BASE) buildBase();
    let c = BASE;
    const g = SCHEDULE[f];
    if (g) c = glitchFrame(c, g, f);
    else c = c.clone();
    // the slow scan band: one soft bright band drifting down the cape once per loop (off-canvas at both ends)
    const cy = -8 + (f / FRAMES) * (H + 16);
    for (let y = Math.max(1, Math.floor(cy - 6)); y <= Math.min(H - 2, Math.ceil(cy + 6)); y++) {
      const k = 0.07 * (1 - Math.abs(y - cy) / 6);
      if (k <= 0) continue;
      for (let x = 1; x < W - 1; x++) c.set(x, y, mix(c.get(x, y), '#9ab0c8', k));
    }
    return c;
  },
};
