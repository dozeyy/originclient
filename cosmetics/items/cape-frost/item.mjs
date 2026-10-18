// Frost Cape: deep-teal cloth that pales to blue-white toward the hem, with hand-grown frost ferns
// creeping up from the hem, a rime of dithered ice crystals under them, short icicles hanging
// off the hem stitch and a few specular glints. Static, scale 8, 1 art pixel = 1 texture pixel on
// the 80x128 outside face (one density per item).
//
// The ferns are drawn by a small recursive branch routine (stem -> paired side branches ->
// paired twigs, each pair tilted ~55 degrees and shorter toward the tip, with a slight curl),
// then lit like real ice: a cool shadow pixel offset down-right under every crystal, the
// ramp stepping brighter on the fold ridges, and a warm-white pixel on the sun-facing tips.
import { Canvas, ramp, rampThrough, clamp, smoothstep, mix, fbm, hash2, bayer, rng, toHex } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 8, W = 10 * S, H = 16 * S;
const OUTLINE = '#040f17';
const HEMY = H - 9;

// Cloth: 9 steps, deep teal (0) -> blue-white (8). Cool shadows, the top steps lean faintly warm.
const CLOTH = rampThrough(['#07161f', '#0e2a3a', '#183f54', '#2a5f78', '#4d8499', '#82aebf', '#b4d3de', '#dbebf1', '#f2f9fc'], 9);
// Ice crystals, dim -> bright; the last is the warm-white catch-light.
const ICE = ['#3f8fa0', '#79c4d2', '#b4e6ee', '#e0f7fb', '#fbffff', '#fff6e8'];
const ICE_SHADOW = '#06202e';
// Frond ramp: pale cyan at the stem -> white at the tips (the last step is the warm-white catch-light).
const FROND = ['#86d4e4', '#a6e4ef', '#c4f0f7', '#e2fafd', '#fbffff', '#fff6e8'];

// ------------------------------------------------------------------ folds
const VALLEYS = [-12, 28, 64, 104];
const valleyX = (k, y) => 40 + (VALLEYS[k] - 40) * (0.94 + 0.12 * (y / H)) + Math.sin(y * 0.045 + k * 2.1) * 1.8;
function foldProfile(p) {
  const r = 0.37;
  return p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r));
}
function foldValue(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y);
  return foldProfile(clamp((x - a) / (b - a)));
}
// Voronoi cells (~11 px, jittered): each cell gets one random tone, which reads as ice grain.
function facet(x, y) {
  const cx = Math.floor(x / 11), cy = Math.floor(y / 11);
  let best = 1e9, v = 0;
  for (let j = -1; j <= 1; j++) for (let i = -1; i <= 1; i++) {
    const gx = cx + i, gy = cy + j, px = (gx + 0.15 + 0.7 * hash2(gx, gy, 71)) * 11, py = (gy + 0.15 + 0.7 * hash2(gx, gy, 72)) * 11;
    const d = (x - px) ** 2 + (y - py) ** 2;
    if (d < best) { best = d; v = hash2(gx, gy, 73); }
  }
  return v;
}
// Cloth tone 0..1: soft folds, a vertical gradient (deep at the collar, pale at the hem), a vignette.
function clothTone(x, y) {
  const fold = foldValue(x, y);
  let t = 0.16 + fold * 0.12 + Math.pow(y / H, 2.2) * 0.46;
  t -= 0.06 * smoothstep(0.5, 1.15, Math.hypot((x - 40) / 46, (y - 60) / 80));
  t += (fbm(x * 0.1, y * 0.1, 4, 2) - 0.5) * 0.05;
  t += (facet(x, y) - 0.5) * 0.07 * smoothstep(0.1, 0.6, y / H);        // frosted-glass facets: crystal grain, not cloth
  return clamp(t);
}
// Pick a ramp step with a short ordered-dither seam between neighbours (no flat chevron bands).
function clothColor(x, y) {
  const p = clothTone(x, y) * (CLOTH.length - 1), lo = Math.floor(p), f = smoothstep(0.3, 0.7, p - lo);
  const th = bayer(x, y, 4) * 0.8 + hash2(x, y, 3) * 0.2;
  return CLOTH[clamp(lo + (f > th ? 1 : 0), 1, CLOTH.length - 1)];
}
const clothLevel = (x, y) => clamp(Math.round(clothTone(x, y) * (CLOTH.length - 1)), 1, CLOTH.length - 1);

// ------------------------------------------------------------------ ferns
// A frond is a curved stroke (heading turns by `curv` degrees per pixel). Along it, side branches
// alternate left/right (now and then a pair) at 48-64 degrees, each curling back toward the frond's
// tip direction; their length follows a feather profile (short at the root, longest low down, tapering
// to the tip) with random +-, and the long ones sprout a twig or two. Every pixel records how far it is
// from the fern's root (colour: pale cyan at the stem -> white at the tips) and whether it ends a stroke.
function put(px, x, y, dist, depth, tip = false) {
  const k = Math.round(y) * W + Math.round(x), old = px.get(k);
  if (!old || depth < old.depth || (depth === old.depth && dist > old.dist)) px.set(k, { dist, depth, tip: tip || (old && old.tip && depth === old.depth) });
  else if (tip) old.tip = true;
}
function frond(px, x0, y0, head, len, curv, rr, o) {
  let x = x0, y = y0, a = head, nextB = o.start ?? 3, side = rr() < 0.5 ? 1 : -1;
  const wide = o.wide ?? 0;
  for (let s = 0; s <= len; s += 0.5) {
    const rad = a * Math.PI / 180, dx = Math.sin(rad), dy = -Math.cos(rad), dist = o.d0 + s;
    put(px, x, y, dist, o.depth);
    if (wide && s / len < wide) put(px, x - dy, y + dx, dist, o.depth);          // stem is 2 px wide near the root, then tapers to 1
    if (o.depth > 0 && s >= nextB && len - s > 3) {
      const u = s / len, prof = Math.pow(1 - u, 0.75) * Math.min(1, 0.35 + u * 5);
      const sides = rr() < 0.2 ? [side, -side] : [side];
      side = -side;
      for (const sd of sides) {
        if (rr() < 0.14) continue;                                  // skipped now and then: no picket fence
        const Lb = Math.max(3, o.maxB * prof * rr.range(0.5, 1.25));
        frond(px, x, y, a + sd * rr.range(56, 76), Lb, -sd * rr.range(3.6, 6.2) + curv * 0.6, rr,
          { depth: o.depth - 1, maxB: Lb * 0.4, d0: dist, start: 3 });
      }
      nextB = s + rr.range(2.2, 4.4);
    }
    const ease = o.depth === 0 ? 1 - 0.85 * Math.pow(s / len, 2) : 1;                // branch ends run straight: sharp spikes, not hooks
    a += (curv * ease + (o.wob ?? 0) * Math.cos(s * 0.11 + (o.phase ?? 0)) + (o.tipCurl ?? 0) * Math.pow(s / len, 2.5)) * 0.5;   // tipCurl: the crozier curl of a fern tip
    x += dx * 0.5; y += dy * 0.5;
  }
  const rad = a * Math.PI / 180;                                                   // a straight 2 px spur past the end: a pointed tip
  for (let k = 1; k <= 2; k++) put(px, x + Math.sin(rad) * k, y - Math.cos(rad) * k, o.d0 + len + k, o.depth, k === 2);
}

// ------------------------------------------------------------------ glints
function glint(c, x, y, arm, tone = '#e6fcff') {
  c.set(x, y, '#ffffff');
  for (let i = 1; i <= arm; i++) {
    const col = i === arm ? '#8fdbea' : tone;
    c.set(x + i, y, col); c.set(x - i, y, col); c.set(x, y + i, col); c.set(x, y - i, col);
  }
  if (arm >= 3) for (const [dx, dy] of [[1, 1], [-1, 1], [1, -1], [-1, -1]]) c.set(x + dx, y + dy, '#b6eef7');
  c.set(x - 1, y - 1, arm >= 3 ? '#fff6e8' : '#d6f7fc');       // warm-shifted top-left catch-light
}

export default {
  meta: { id: 'cape-frost', name: 'Frost Cape', kind: 'cape', cents: 599, blurb: 'Teal cloth, frost ferns creeping up from the hem, ice glinting in the light.' },
  cape: { scale: S, frames: 1 },
  paint() {
    const c = newCapeArt(S);
    // ------- cloth
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, clothColor(x, y));

    // ------- rime: dithered ice crystals thickening toward the hem, with an irregular upper edge
    for (let y = 8; y < HEMY; y++) for (let x = 0; x < W; x++) {
      const reach = 34 + (fbm(x * 0.07, 2.5, 9, 3) - 0.5) * 30 + Math.sin(x * 0.21) * 3;
      const d = clamp(1 - (HEMY - y) / reach);
      if (d <= 0) continue;
      const th = bayer(x, y, 4) * 0.55 + hash2(x, y, 7) * 0.45;
      const lit = foldValue(x, y);
      if (d * d * 0.9 > th) {
        const idx = clamp(Math.round(d * 1.6 + lit * 0.9 - 0.4), 0, 2);
        c.set(x, y, mix(c.get(x, y), ICE[idx], 0.28 + 0.26 * d));
      }
    }

    // ------- ragged crystalline frost creeping in from the four corners
    const corners = [[0, 9, 1], [W - 1, 9, 2], [0, HEMY - 1, 3], [W - 1, HEMY - 1, 4]];
    for (const [cx, cy, n] of corners) for (let y = 9; y < HEMY; y++) for (let x = 0; x < W; x++) {
      const dx = x - cx, dy = y - cy, dist = Math.hypot(dx, dy);
      if (dist > 30) continue;
      const ang = Math.atan2(Math.abs(dy), Math.abs(dx));
      const spikes = 7 * Math.abs(((ang * 3.4 + n * 0.37) % 1) - 0.5) * 2 + 4 * (fbm(x * 0.2, y * 0.2, 50 + n, 2) - 0.5);
      const v = 15 + spikes - dist + (n > 2 ? 1 : 0);
      if (v <= 0) continue;
      const rim = v < 1.6;
      if (!rim && v < 5 && bayer(x, y, 4) * 0.7 + hash2(x, y, 9) * 0.3 > v / 5) continue;   // dithered fade inward
      c.set(x, y, rim ? ICE[3] : mix(c.get(x, y), ICE[v > 9 ? 2 : 1], 0.45 + 0.04 * Math.min(8, v)));
    }

    // ------- ferns: [x on the hem, heading, length, curve/px, longest branch, wobble]
    const rr = rng(2031), px = new Map();
    const FERNS = [
      [44, -14, 108, 0.4, 15, 0.2, 1.0], [14, 10, 80, -0.3, 13, 0.2, -1.2], [72, -26, 70, 0.55, 12, 0.2, 0.8],
      [29, 24, 30, -0.7, 8, 0.15, -1.2], [58, -22, 34, 0.7, 9, 0.15, 1.2], [2, 28, 28, -0.8, 7, 0.1, -1.4], [78, -28, 28, 0.8, 7, 0.1, 1.4],
    ];
    FERNS.forEach(([x, head, len, curv, maxB, wob, tipCurl], i) =>
      frond(px, x, HEMY - 1, head, len, curv, rr, { depth: 1, maxB, d0: 0, wide: len >= 40 ? 0.4 : 0, wob, tipCurl, phase: i * 1.9, start: 4 }));
    // crystals: shadow pass (cool, offset down-right), then pale cyan -> white by distance from the root
    for (const k of px.keys()) {
      const x = k % W, y = (k / W) | 0;
      if (!px.has((y + 1) * W + x + 1)) c.set(x + 1, y + 1, mix(c.get(x + 1, y + 1), ICE_SHADOW, 0.6));
    }
    const tips = [];
    for (const [k, v] of px) {
      const x = k % W, y = (k / W) | 0, lit = foldValue(x, y);
      let i = clamp(Math.floor(Math.pow(clamp(v.dist / 125), 0.85) * 4.4) + (lit > 0.7 ? 1 : 0), 0, 5);
      if (v.tip) { i = 5; tips.push([x, y, v.depth]); }
      c.set(x, y, FROND[i]);
    }

    // ------- glints: sparse sparkles on the frond tips (the highest ones first, so the upper cloth has some), plus two on ridges
    const high = tips.filter(t => t[2] <= 1).sort((p, q) => p[1] - q[1]);
    const used = [];
    for (const [x, y] of high) {
      if (used.length >= 7 || y < 12 || y > HEMY - 14 || x < 4 || x > W - 5 || used.some(u => Math.hypot(u[0] - x, u[1] - y) < 16)) continue;
      used.push([x, y]);
      glint(c, x, y, used.length === 1 ? 4 : used.length % 3 === 0 ? 3 : 2);
    }
    glint(c, 22, 56, 1); glint(c, 60, 40, 1);

    // ------- frosted glaze creeping in from the side edges and the top shoulder (thicker low down)
    for (let y = 9; y < HEMY; y++) for (let x = 0; x < W; x++) {
      const e = Math.min(x, W - 1 - x), reach = 3 + 4 * Math.pow(y / H, 1.4) + (hash2(y, 3, 21) - 0.5) * 2;
      if (e >= reach) continue;
      const d = 1 - e / reach, th = bayer(x, y, 4) * 0.6 + hash2(x, y, 13) * 0.4;
      if (d * 1.15 > th) c.set(x, y, mix(c.get(x, y), e === 1 ? ICE[3] : ICE[d > 0.6 ? 2 : 1], 0.35 + 0.4 * d));
    }

    // ------- collar: gathered dark band, seam, pale stitch row
    for (let y = 1; y < 7; y++) for (let x = 0; x < W; x++) c.set(x, y, CLOTH[clamp(clothLevel(x, y) - 2, 0, 3)]);
    for (let x = 0; x < W; x++) { c.set(x, 7, CLOTH[0]); c.set(x, 8, ((x >> 1) & 1) ? '#8fd0da' : CLOTH[2]); }

    // ------- hem: frosted fold-over, pale stitch, icicles hanging from it
    for (let x = 0; x < W; x++) {
      const f = foldValue(x, HEMY);
      c.set(x, HEMY, CLOTH[1]);
      c.set(x, HEMY + 1, ICE[f > 0.55 ? 3 : 2]);
      for (let y = HEMY + 2; y < H - 1; y++) {
        const g = bayer(x, y, 4) * 0.7 + hash2(x, y, 17) * 0.3 < 0.2 * (1 - (y - HEMY) / 9);       // frosted glaze on the hem lining
        c.set(x, y, g ? ICE[1] : CLOTH[clamp(2 + Math.round(f * 2), 1, 4)]);
      }
      c.set(x, HEMY + 3, (x % 4) === 3 ? CLOTH[2] : '#cdeff5');
      c.set(x, HEMY + 4, CLOTH[0]);
    }
    const ri = rng(77);
    for (let x = 2; x < W - 2; x += 2 + ri.int(3)) {
      const L = 1 + ri.int(4);
      for (let j = 0; j <= L; j++) { c.set(x, HEMY + 5 + j, j === L ? '#d9f4f8' : '#9bd6e0'); if (j < L - 1) c.set(x + 1, HEMY + 5 + j, '#2a7f93'); }
    }

    // ------- 1 px dark outline all round
    for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
    for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
    return c;
  },
};
