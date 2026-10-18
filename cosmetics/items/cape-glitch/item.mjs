// Glitch Cape: a dark digital cloth ruled with even 1 px scanlines, a crisp Origin mark, and two short
// glitch bursts per 32-frame loop (frames 9-11 and 23-24, a 1 px split twitch on 17); the other 26 frames
// are calm. A burst tears 2-4 bands of rows sideways by 1-3 px (the cape's own edge is torn with them, so
// the silhouette breaks for those frames and reads at 8 blocks), splits RGB by 1 px and flickers the
// scanlines. The mark is cape-origin's rasteriser at this cape's density: an animated cape is 40x64 art px
// (4 px per model px), half of cape-origin's 80x128, so its 2 px stroke becomes 1 px (the same thickness on
// the cape) and the mark is drawn once, here, never resampled. A faint scan band drifts down once per loop
// (off-canvas at both ends), so the loop is seamless. Every burst row is a table entry: nothing is random.
import { Canvas, ramp, clamp, smoothstep, mix } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 4, W = 10 * S, H = 16 * S, FRAMES = 32;
const MX = 20, MY = 31, MSIZE = 36, OPEN = 1.15, STROKE = 1, GAPR2 = 2;   // mark: centre on a pixel seam; width; ring fatness; stroke px; crossing break radius^2
const FX = x => 2 * MX - 1 - x, FY = y => 2 * MY - 1 - y;

const clothFine = ramp('#2b2f3a', 9, { lo: 0.05, hi: 0.24, tint: 0.03, shadowHue: 232, lightHue: 205, shift: 0.3 });
const cloth = [0, 2, 4, 6, 8].map(i => clothFine[i]);
const OUTLINE = cloth[0];
const BODY = '#e0e0e0', HALO_EDGE = 0.2, HALO_CORNER = 0.09;   // thread colour; halo strength beside it
// the cloth body is one flat tone ruled by even scanlines: every odd row one step darker
const bodyLevel = () => 4;
const clothLevel = (x, y) => Math.round(bodyLevel(x, y) / 2);
// ---------------------------------------------------------------- the mark
// Ring 0: horizontal. Ring 1: 60 degrees. Ring 2: 120 degrees = ring 1 mirrored left/right,
// so the two tilted rings share one size (the three-ring mark, drawn symmetric).
function ringGeometry() {
  const k = (MSIZE / 2 - STROKE / 2) / 90, open = OPEN;
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
    if (dist <= STROKE / 2 + 0.2) {
      let nx = dist > 1e-6 ? (cx - bx) / dist : 0, ny = dist > 1e-6 ? (cy - by) / dist : 0;
      const out = insideF(i, cx, cy) >= 1;
      if (!out) { nx = -nx; ny = -ny; }
      const off = (out ? dist : -dist) / (Math.max(Math.abs(nx), Math.abs(ny)) || 1);
      if (off >= -STROKE / 2 && off < STROKE / 2) res = off < 0 ? 'inner' : 'outer';
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
    if (!near || nd > GAPR2 || near.over === i) continue;
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) if (owner.get((py + dy) * W + px + dx) === near.over) gap.add(k);
  }
  return { owner, cover, gap, pts, X, crossingsSolved: X.every(c => c.over !== undefined) };
}

// ---------------------------------------------------------------- static layer
let BASE = null;
function buildBase() {
  const c = newCapeArt(S);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, clothFine[bodyLevel(x, y) - (y & 1)]);
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
  for (const [k] of mark.owner) if (!mark.gap.has(k)) c.set(k % W, (k / W) | 0, BODY);
  // dim 1 px halo: the cloth beside every thread pixel catches a little of the thread's light (edge neighbours
  // stronger than corner neighbours); it never touches the thread itself, so the 1 px line stays crisp
  const glow = new Map();
  for (const [k] of mark.owner) {
    if (mark.gap.has(k)) continue;
    const px = k % W, py = (k / W) | 0;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue;
      const nk = (py + dy) * W + px + dx;
      if (mark.owner.has(nk) && !mark.gap.has(nk)) continue;
      glow.set(nk, Math.max(glow.get(nk) || 0, (dx && dy) ? HALO_CORNER : HALO_EDGE));
    }
  }
  for (const [k, g] of glow) c.set(k % W, (k / W) | 0, mix(c.get(k % W, (k / W) | 0), '#e0e0e0', g));
  for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
  for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
  BASE = c;
}

// ---------------------------------------------------------------- the glitch schedule
// frame -> { split: px of RGB split, bands: [[y, height, dx]], flick: scanline darkening 0..1, dim: mark flicker }
// Band rows are shifted whole, edge included: the cape's silhouette is notched where a band slides.
const SCHEDULE = {
  9:  { split: 1, bands: [[22, 3, 2], [40, 3, -3]], flick: 0.2, dim: 0 },
  10: { split: 2, bands: [[10, 3, -3], [20, 5, 3], [31, 3, -2], [44, 4, 3]], flick: 0.45, dim: 0.25 },
  11: { split: 1, bands: [[28, 3, -2], [37, 4, 3]], flick: 0.25, dim: 0 },
  17: { split: 1, bands: [], flick: 0, dim: 0 },
  23: { split: 2, bands: [[16, 4, 3], [34, 3, -3], [48, 3, 2]], flick: 0.35, dim: 0.2 },
  24: { split: 1, bands: [[26, 4, -3], [42, 3, 2]], flick: 0.18, dim: 0 },
};
// calm frames: one faint scanline now and then catches the light (frame -> row)
const BLINK = { 2: 21, 5: 46, 13: 18, 14: 18, 20: 38, 28: 27, 30: 52 };

function glitchFrame(src, g, f) {
  const out = new Canvas(W, H);
  const band = y => { for (const [by, bh, dx] of g.bands) if (y >= by && y < by + bh) return dx; return 0; };
  for (let y = 0; y < H; y++) {
    const dx = band(y), d = (y < 6 || y > H - 8) ? 0 : g.split;
    for (let x = 0; x < W; x++) {
      const sx = x - dx;
      if (sx < 0 || sx >= W) continue;                       // slid out of / into the cape: transparent notch
      const cr = src.get(Math.min(W - 1, sx + d), y), cg = src.get(sx, y);   // red ghost d px over, the line itself cyan
      if (d && cg[0] > 150) out.set(x, y, [70, 255, 245]);                  // thread: saturated cyan
      else if (d && cr[0] > 150) out.set(x, y, [255, 40, 84]);              // its ghost: saturated red
      else out.set(x, y, [cr[0], cg[1], cg[2]]);
    }
  }
  if (g.flick > 0) {
    for (let y = 1; y < H - 1; y++) {
      const roll = ((y + f * 5) % 8) < 2;                    // a rolling pair of dark lines
      const k = (y & 1 ? g.flick * 0.55 : 0) + (roll ? g.flick * 0.7 : 0);
      if (k > 0) for (let x = 0; x < W; x++) { const p = out.get(x, y); if (p[3]) out.set(x, y, mix(p, '#0a0c10', k)); }
    }
  }
  if (g.dim > 0) {                                           // mark flickers down for a frame
    for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
      const p = out.get(x, y);
      if (p[3] && (p[0] > 150 || p[1] > 150)) out.set(x, y, mix(p, '#2b2f3a', g.dim));
    }
  }
  return out;
}

export default {
  meta: { id: 'cape-glitch', name: 'Glitch Cape', kind: 'cape', cents: 999, blurb: 'A clean Origin mark that tears and splits in brief digital bursts.' },
  cape: { scale: S, frames: FRAMES, fps: 12 },
  paint(f) {
    if (!BASE) buildBase();
    const g = SCHEDULE[f];
    const c = g ? glitchFrame(BASE, g, f) : BASE.clone();
    if (BLINK[f] !== undefined) for (let x = 1; x < W - 1; x++) c.set(x, BLINK[f], mix(c.get(x, BLINK[f]), '#9ab0c8', 0.16));
    // the slow scan band: one soft bright band drifting down the cape once per loop
    const cy = -8 + (f / FRAMES) * (H + 16);
    for (let y = Math.max(1, Math.floor(cy - 6)); y <= Math.min(H - 2, Math.ceil(cy + 6)); y++) {
      const k = 0.07 * (1 - Math.abs(y - cy) / 6);
      if (k <= 0) continue;
      for (let x = 1; x < W - 1; x++) if (c.alpha(x, y)) c.set(x, y, mix(c.get(x, y), '#9ab0c8', k));
    }
    return c;
  },
};
