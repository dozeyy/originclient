// Pixel-art toolkit for the Origin cosmetics (see cosmetics/FORMAT.md).
//
// Everything here is integer-pixel, anti-alias-free and deterministic: the same
// item source always produces the same bytes. No dependencies (PNG via node:zlib).
//
// Colours are '#rgb' / '#rrggbb' / '#rrggbbaa' strings or [r,g,b,a?] arrays.
// Canvas coordinates: x right, y DOWN, (0,0) = top-left pixel. A "pixel centre"
// is (x + 0.5, y + 0.5); shapes that take real-valued centres (ellipse, disc)
// are symmetric about that centre, so cx = 20 is the seam between pixels 19 and 20.
import zlib from 'node:zlib';

// ------------------------------------------------------------------ colour
const parsed = new Map();
export function rgba(c) {
  if (Array.isArray(c)) return c.length === 4 ? c : [c[0], c[1], c[2], 255];
  let v = parsed.get(c);
  if (v) return v;
  let h = String(c).replace('#', '');
  if (h.length === 3 || h.length === 4) h = [...h].map(x => x + x).join('');
  if (h.length !== 6 && h.length !== 8) throw new Error(`bad colour ${c}`);
  v = [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), h.length === 8 ? parseInt(h.slice(6, 8), 16) : 255];
  parsed.set(c, v);
  return v;
}
const b8 = v => Math.max(0, Math.min(255, Math.round(v)));
export const toHex = c => { const [r, g, b, a = 255] = rgba(c); return '#' + [r, g, b].map(v => b8(v).toString(16).padStart(2, '0')).join('') + (a === 255 ? '' : b8(a).toString(16).padStart(2, '0')); };
export const mix = (a, b, t) => { const A = rgba(a), B = rgba(b); return A.map((v, i) => b8(v + (B[i] - v) * t)); };
export const clamp = (v, lo = 0, hi = 1) => Math.max(lo, Math.min(hi, v));
export const lerp = (a, b, t) => a + (b - a) * t;
export const smoothstep = (a, b, v) => { const t = clamp((v - a) / (b - a)); return t * t * (3 - 2 * t); };

export function rgb2hsl(c) {
  const [r, g, b] = rgba(c).map(v => v / 255);
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, d = mx - mn;
  if (d < 1e-9) return [0, 0, l];
  const s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
  let h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}
export function hsl2rgb(h, s, l, a = 255) {
  h = ((h % 360) + 360) % 360;
  const f = n => { const k = (n + h / 30) % 12; return l - s * Math.min(l, 1 - l) * Math.max(-1, Math.min(k - 3, 9 - k, 1)); };
  return [b8(f(0) * 255), b8(f(8) * 255), b8(f(4) * 255), a];
}
const hueDiff = (from, to) => ((to - from + 540) % 360) - 180;

// A hue-shifted ramp of n colours, darkest first, light-to-dark around `base`
// (which sits in the middle). Shadows slide toward `shadowHue` (cool), lights
// toward `lightHue` (warm). Returns '#rrggbb' strings.
//   shift  how far the hue travels toward the target (0 = no hue shift, mono)
//   tint   minimum saturation injected at the ends so greys still pick up warm/cool
//   lo/hi  lightness (0..1) of the darkest/lightest step
export function ramp(base, n = 5, o = {}) {
  const { shadowHue = 252, lightHue = 48, shift = 0.4, tint = 0.05, satLo = 1.08, satHi = 0.86, lo, hi, maxTurn = 48 } = o;
  const [h, s, l] = rgb2hsl(base);
  const L0 = lo ?? clamp(l * 0.34, 0.035, 0.28), L1 = hi ?? clamp(l + (1 - l) * 0.72, 0, 0.94);
  const mid = (n - 1) / 2, out = [];
  for (let i = 0; i < n; i++) {
    const u = n === 1 ? 0 : (i - mid) / mid, a = Math.abs(u);
    const li = u < 0 ? lerp(l, L0, a) : lerp(l, L1, a);
    const target = u < 0 ? shadowHue : lightHue;
    const turn = clamp(hueDiff(h, target) * shift * a, -maxTurn, maxTurn);
    let si = s * (u < 0 ? lerp(1, satLo, a) : lerp(1, satHi, a));
    si = clamp(Math.max(si, tint * a), 0, 1);
    const hh = s < 0.04 ? target : h;     // greys take the target hue outright (only visible through `tint`)
    out.push(toHex(hsl2rgb(hh + (s < 0.04 ? 0 : turn), si, li)));
  }
  return out;
}
// A ramp through explicit colours, resampled to n steps (linear in RGB).
export function rampThrough(stops, n = stops.length) {
  const out = [];
  for (let i = 0; i < n; i++) {
    const p = n === 1 ? 0 : (i / (n - 1)) * (stops.length - 1), k = Math.min(stops.length - 2, Math.floor(p));
    out.push(toHex(stops.length === 1 ? stops[0] : mix(stops[k], stops[k + 1], p - k)));
  }
  return out;
}
export const darken = (c, k, o = {}) => ramp(c, 3, { ...o, lo: o.lo ?? clamp(rgb2hsl(c)[2] * (1 - k), 0.02, 1) })[0];

// ------------------------------------------------------------------ random
// mulberry32: tiny, fast, deterministic.
export function rng(seed = 1) {
  let a = (typeof seed === 'string' ? hashStr(seed) : seed) >>> 0;
  const r = () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  r.int = (n) => Math.floor(r() * n);
  r.pick = arr => arr[Math.floor(r() * arr.length)];
  r.range = (lo, hi) => lo + r() * (hi - lo);
  return r;
}
export function hashStr(s) { let h = 2166136261; for (const ch of s) h = Math.imul(h ^ ch.charCodeAt(0), 16777619); return h >>> 0; }
// Integer-lattice hash -> [0,1)
export function hash2(x, y, seed = 0) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(seed | 0, 2246822519)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177); h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}
// Smooth value noise + fbm (deterministic in x, y, seed).
export function noise2(x, y, seed = 0) {
  const xi = Math.floor(x), yi = Math.floor(y), fx = x - xi, fy = y - yi;
  const u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
  const a = hash2(xi, yi, seed), b = hash2(xi + 1, yi, seed), c = hash2(xi, yi + 1, seed), d = hash2(xi + 1, yi + 1, seed);
  return lerp(lerp(a, b, u), lerp(c, d, u), v);
}
export function fbm(x, y, seed = 0, oct = 4) {
  let sum = 0, amp = 0.5, norm = 0;
  for (let i = 0; i < oct; i++) { sum += amp * noise2(x, y, seed + i * 17); norm += amp; x *= 2; y *= 2; amp /= 2; }
  return sum / norm;
}

// --------------------------------------------------------------- dithering
function bayerMatrix(n) {
  if (n === 2) return [[0, 2], [3, 1]];
  const m = bayerMatrix(n / 2), out = [];
  for (let y = 0; y < n; y++) { out.push([]); for (let x = 0; x < n; x++) {
    const q = m[y % (n / 2)][x % (n / 2)] * 4, qx = x >= n / 2 ? 1 : 0, qy = y >= n / 2 ? 1 : 0;
    out[y].push(q + [[0, 2], [3, 1]][qy][qx]);
  } }
  return out;
}
const BAYER = { 2: bayerMatrix(2), 4: bayerMatrix(4), 8: bayerMatrix(8) };
// Ordered-dither threshold in (0,1) at pixel (x,y). n = 2 | 4 | 8.
export const bayer = (x, y, n = 4) => (BAYER[n][((y % n) + n) % n][((x % n) + n) % n] + 0.5) / (n * n);
// Cluster-dot (halftone) threshold in (0,1): dots grow from the cell centre, so
// a gradient reads as round clusters instead of a checker. size = cell width.
export function cluster(x, y, size = 4) {
  const c = (size - 1) / 2, fx = (((x % size) + size) % size) - c, fy = (((y % size) + size) % size) - c;
  return clamp(Math.hypot(fx, fy) / (Math.hypot(c, c) + 0.001) * 0.98 + 0.01);
}
// Pick a ramp index for a continuous position t in [0,1] along the ramp,
// dithering between the two nearest steps. `th` = a threshold function value.
export function ditherIndex(t, steps, th) {
  const p = clamp(t) * (steps - 1), lo = Math.floor(p), f = p - lo;
  return Math.min(steps - 1, lo + (f > th ? 1 : 0));
}
export const ditherPick = (colors, t, x, y, n = 4) => colors[ditherIndex(t, colors.length, bayer(x, y, n))];
export const clusterPick = (colors, t, x, y, size = 4) => colors[ditherIndex(t, colors.length, cluster(x, y, size))];

// ------------------------------------------------------------------- canvas
export class Canvas {
  constructor(w, h) {
    this.w = w; this.h = h;
    this.data = new Uint8ClampedArray(w * h * 4);
    this.stride = w; this.ox = 0; this.oy = 0;
  }
  // A window onto part of this canvas: same drawing API, coordinates local to
  // the window, writes land in the parent and are clipped to the window.
  view(x, y, w, h) {
    const v = Object.create(Canvas.prototype);
    v.w = w; v.h = h; v.data = this.data; v.stride = this.stride; v.ox = this.ox + x; v.oy = this.oy + y;
    return v;
  }
  inb(x, y) { return x >= 0 && y >= 0 && x < this.w && y < this.h; }
  idx(x, y) { return ((this.oy + y) * this.stride + this.ox + x) * 4; }
  get(x, y) { if (!this.inb(x, y)) return [0, 0, 0, 0]; const i = this.idx(x, y), d = this.data; return [d[i], d[i + 1], d[i + 2], d[i + 3]]; }
  alpha(x, y) { return this.inb(x, y) ? this.data[this.idx(x, y) + 3] : 0; }
  solid(x, y) { return this.alpha(x, y) > 0; }
  set(x, y, c) {
    x = Math.floor(x); y = Math.floor(y);
    if (!this.inb(x, y)) return this;
    const [r, g, b, a] = rgba(c), i = this.idx(x, y), d = this.data;
    d[i] = r; d[i + 1] = g; d[i + 2] = b; d[i + 3] = a;
    return this;
  }
  // Source-over blend; `k` scales the colour's own alpha.
  blend(x, y, c, k = 1) {
    x = Math.floor(x); y = Math.floor(y);
    if (!this.inb(x, y)) return this;
    const [r, g, b, a0] = rgba(c), sa = (a0 / 255) * k;
    if (sa <= 0) return this;
    const i = this.idx(x, y), d = this.data, da = d[i + 3] / 255, oa = sa + da * (1 - sa);
    d[i] = (r * sa + d[i] * da * (1 - sa)) / oa;
    d[i + 1] = (g * sa + d[i + 1] * da * (1 - sa)) / oa;
    d[i + 2] = (b * sa + d[i + 2] * da * (1 - sa)) / oa;
    d[i + 3] = oa * 255;
    return this;
  }
  // Additive (lighten) blend, for glows.
  add(x, y, c, k = 1) {
    x = Math.floor(x); y = Math.floor(y);
    if (!this.inb(x, y)) return this;
    const [r, g, b, a0] = rgba(c), s = (a0 / 255) * k, i = this.idx(x, y), d = this.data;
    d[i] = Math.min(255, d[i] + r * s); d[i + 1] = Math.min(255, d[i + 1] + g * s); d[i + 2] = Math.min(255, d[i + 2] + b * s);
    d[i + 3] = Math.max(d[i + 3], Math.min(255, 255 * s));
    return this;
  }
  fill(c = [0, 0, 0, 0]) { for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) this.set(x, y, c); return this; }
  clear() { return this.fill([0, 0, 0, 0]); }
  rect(x, y, w, h, c) { for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.set(x + i, y + j, c); return this; }
  rectBlend(x, y, w, h, c, k = 1) { for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.blend(x + i, y + j, c, k); return this; }
  frame(x, y, w, h, c) { this.rect(x, y, w, 1, c); this.rect(x, y + h - 1, w, 1, c); this.rect(x, y, 1, h, c); this.rect(x + w - 1, y, 1, h, c); return this; }
  // Per-pixel painter: fn(x, y, current) -> colour | null (null leaves the pixel).
  paint(fn, x0 = 0, y0 = 0, x1 = this.w, y1 = this.h) {
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) { const c = fn(x, y, this.get(x, y)); if (c) this.set(x, y, c); }
    return this;
  }
  // Bresenham line; `w` > 1 stamps a w x w square brush centred on the path.
  line(x0, y0, x1, y1, c, w = 1) {
    x0 = Math.round(x0); y0 = Math.round(y0); x1 = Math.round(x1); y1 = Math.round(y1);
    const dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
    let err = dx + dy;
    const lo = -Math.floor((w - 1) / 2), hi = Math.floor(w / 2);
    for (;;) {
      for (let j = lo; j <= hi; j++) for (let i = lo; i <= hi; i++) this.set(x0 + i, y0 + j, c);
      if (x0 === x1 && y0 === y1) break;
      const e2 = 2 * err;
      if (e2 >= dy) { err += dy; x0 += sx; }
      if (e2 <= dx) { err += dx; y0 += sy; }
    }
    return this;
  }
  // Plot a list of [x,y] with a colour or fn(x,y,i) -> colour.
  plot(points, c) { points.forEach(([x, y], i) => this.set(x, y, typeof c === 'function' ? c(x, y, i) : c)); return this; }
  // Filled disc centred on real (cx, cy), radius r (pixel-centre test, no AA).
  disc(cx, cy, r, c) { return this.plot(ellipsePixels(cx, cy, r, r, { fill: true }), c); }
  ellipse(cx, cy, rx, ry, c, o = {}) { return this.plot(ellipsePixels(cx, cy, rx, ry, o), c); }
  // Even-odd polygon fill; pts = [[x,y],...] in pixel-corner coordinates.
  poly(pts, c) {
    const ys = pts.map(p => p[1]), y0 = Math.max(0, Math.floor(Math.min(...ys))), y1 = Math.min(this.h - 1, Math.ceil(Math.max(...ys)));
    for (let y = y0; y <= y1; y++) {
      const yc = y + 0.5, xs = [];
      for (let i = 0; i < pts.length; i++) {
        const [ax, ay] = pts[i], [bx, by] = pts[(i + 1) % pts.length];
        if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) xs.push(ax + ((yc - ay) / (by - ay)) * (bx - ax));
      }
      xs.sort((a, b) => a - b);
      for (let k = 0; k + 1 < xs.length; k += 2) for (let x = Math.ceil(xs[k] - 0.5); x < xs[k + 1] - 0.5 + 1e-9; x++) this.set(x, y, typeof c === 'function' ? c(x, y) : c);
    }
    return this;
  }
  // Copy `src` onto this canvas at (dx, dy). Opaque pixels replace, unless blend.
  blit(src, dx = 0, dy = 0, o = {}) {
    const { flipX = false, flipY = false, blend = false, k = 1 } = o;
    for (let y = 0; y < src.h; y++) for (let x = 0; x < src.w; x++) {
      const c = src.get(flipX ? src.w - 1 - x : x, flipY ? src.h - 1 - y : y);
      if (c[3] === 0) continue;
      if (blend || k < 1) this.blend(dx + x, dy + y, c, k); else this.set(dx + x, dy + y, c);
    }
    return this;
  }
  clone() { const c = new Canvas(this.w, this.h); for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) c.set(x, y, this.get(x, y)); return c; }
  crop(x, y, w, h) { const c = new Canvas(w, h); for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) c.set(i, j, this.get(x + i, y + j)); return c; }
  // Nearest-neighbour enlarge (the only resampling this toolkit allows).
  upscale(k) { const c = new Canvas(this.w * k, this.h * k); for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) c.set(x, y, this.get((x / k) | 0, (y / k) | 0)); return c; }
  flipX() { const c = new Canvas(this.w, this.h); for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) c.set(this.w - 1 - x, y, this.get(x, y)); return c; }
  // Every pixel's colour through fn(c, x, y) -> colour (alpha 0 pixels skipped).
  map(fn) { for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) { const c = this.get(x, y); if (c[3]) this.set(x, y, fn(c, x, y)); } return this; }
  count() { let n = 0; for (let i = 3; i < this.data.length; i += 4) if (this.data[i]) n++; return n; }
  toPNG() { return encodePNG(this); }
}

// --------------------------------------------------------------- selections
// A Mask is a 0/1 Uint8Array the size of a canvas region; handy for "paint
// this material only inside that shape".
export class Mask {
  constructor(w, h) { this.w = w; this.h = h; this.d = new Uint8Array(w * h); }
  get(x, y) { return x >= 0 && y >= 0 && x < this.w && y < this.h ? this.d[y * this.w + x] : 0; }
  set(x, y, v = 1) { if (x >= 0 && y >= 0 && x < this.w && y < this.h) this.d[y * this.w + x] = v; return this; }
  fromPoints(pts) { for (const [x, y] of pts) this.set(x, y, 1); return this; }
  static fromCanvas(c) { const m = new Mask(c.w, c.h); for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) if (c.alpha(x, y)) m.d[y * c.w + x] = 1; return m; }
  // Distance (in px, 4-connected) from each set pixel to the nearest unset one.
  // Edge pixels = 1. Used for bevels and rim light.
  distance() {
    const INF = 1e9, out = new Float32Array(this.w * this.h);
    for (let i = 0; i < out.length; i++) out[i] = this.d[i] ? INF : 0;
    const at = (x, y) => (x < 0 || y < 0 || x >= this.w || y >= this.h ? 0 : out[y * this.w + x]);
    for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) { const i = y * this.w + x; if (out[i]) out[i] = Math.min(out[i], at(x - 1, y) + 1, at(x, y - 1) + 1); }
    for (let y = this.h - 1; y >= 0; y--) for (let x = this.w - 1; x >= 0; x--) { const i = y * this.w + x; if (out[i]) out[i] = Math.min(out[i], at(x + 1, y) + 1, at(x, y + 1) + 1); }
    return out;
  }
}

// ---------------------------------------------------------------- outlines
// Outline pass over the painted pixels of `c`.
//   mode 'outer'  draws the outline on transparent pixels beside the art
//   mode 'inner'  recolours the art's own edge pixels
// `color` is a colour, or fn(edgePixelColour, {dx,dy}) -> colour. With no
// colour the outline is the edge pixel's own colour pushed darker and cooler
// (selective outlining): edges that face the light (up/left) are only slightly
// darkened so the silhouette keeps its form, edges away from the light go dark.
//   diag  also outline diagonal neighbours (thicker corners)
export function outline(c, o = {}) {
  const { mode = 'outer', color = null, diag = false, dark = 0.62, litDark = 0.8, light = [-1, -1], only = null } = o;
  const src = c.clone(), edges = [];
  const N4 = [[1, 0], [-1, 0], [0, 1], [0, -1]], N8 = diag ? [...N4, [1, 1], [-1, -1], [1, -1], [-1, 1]] : N4;
  for (let y = -1; y <= c.h; y++) for (let x = -1; x <= c.w; x++) {
    const here = src.solid(x, y);
    if (mode === 'outer' && here) continue;
    if (mode === 'inner' && !here) continue;
    if (only && !only(x, y)) continue;
    let best = null;
    for (const [dx, dy] of N8) {
      const nx = x + dx, ny = y + dy;
      if (mode === 'outer' && src.solid(nx, ny)) { best = { src: src.get(nx, ny), dx: -dx, dy: -dy }; break; }
      if (mode === 'inner' && !src.solid(nx, ny)) { best = { src: src.get(x, y), dx, dy }; break; }
    }
    if (best) edges.push([x, y, best]);
  }
  for (const [x, y, e] of edges) {
    // (dx,dy) = the direction the edge faces (away from the art).
    const facing = e.dx * light[0] + e.dy * light[1] > 0;
    let col;
    if (typeof color === 'function') col = color(e.src, e);
    else if (color) col = color;
    else {
      const [h, s, l] = rgb2hsl(e.src);
      col = hsl2rgb(h + clamp(hueDiff(h, 255) * 0.25, -30, 30), clamp(s * 1.05), l * (facing ? litDark : dark), e.src[3]);
    }
    c.set(x, y, col);
  }
  return c;
}

// ------------------------------------------------------------- ellipses
// Pixels of an ellipse centred on real (cx, cy), semi-axes rx, ry, rotated by
// `rot` degrees (clockwise on screen). No anti-aliasing: a pixel is on when its
// centre is within stroke/2 of the true curve (distance estimated from the
// implicit function), or inside the curve for fill. Thin strokes (<= 1.35) get
// a "pixel-perfect" pass that removes the L-shaped double pixels at bends.
//   arc [a0, a1]  keep only the part between these angles (degrees, in the
//                 ellipse's own frame, 0 = +major axis, clockwise)
export function ellipsePixels(cx, cy, rx, ry, o = {}) {
  const { rot = 0, stroke = 1, fill = false, arc = null } = o;
  const th = (rot * Math.PI) / 180, ct = Math.cos(th), st = Math.sin(th);
  const ext = Math.ceil(Math.max(rx, ry) + stroke + 2);
  const x0 = Math.floor(cx - ext), x1 = Math.ceil(cx + ext), y0 = Math.floor(cy - ext), y1 = Math.ceil(cy + ext);
  const on = new Map();
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    const px = x + 0.5 - cx, py = y + 0.5 - cy;
    const u = px * ct + py * st, v = -px * st + py * ct;
    const f = (u / rx) ** 2 + (v / ry) ** 2;
    let hit;
    if (fill) hit = f <= 1;
    else {
      const g = 2 * Math.sqrt((u / (rx * rx)) ** 2 + (v / (ry * ry)) ** 2) || 1e-9;
      hit = Math.abs((f - 1) / g) <= (stroke <= 1.35 ? 1.15 : stroke) / 2;
    }
    if (!hit) continue;
    if (arc) {
      let a = (Math.atan2(v / ry, u / rx) * 180) / Math.PI; if (a < 0) a += 360;
      const [a0, a1] = arc;
      if (a0 <= a1 ? !(a >= a0 && a <= a1) : !(a >= a0 || a <= a1)) continue;
    }
    on.set(x + ',' + y, [x, y]);
  }
  if (!fill && stroke <= 1.35) pixelPerfect(on);
  return [...on.values()];
}
// Remove the middle pixel of every L-corner (a pixel with exactly one horizontal
// and one vertical neighbour whose diagonal is also set).
export function pixelPerfect(on) {
  const has = (x, y) => on.has(x + ',' + y);
  for (const [x, y] of [...on.values()]) {
    const n = has(x, y - 1), s = has(x, y + 1), w = has(x - 1, y), e = has(x + 1, y);
    if ((n || s) && (w || e) && (n ? 1 : 0) + (s ? 1 : 0) + (w ? 1 : 0) + (e ? 1 : 0) === 2) {
      const dx = w ? -1 : 1, dy = n ? -1 : 1;
      if (has(x + dx, y + dy)) on.delete(x + ',' + y);
    }
  }
  return on;
}

// ---------------------------------------------------------------------- PNG
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
const crc32 = buf => { let c = 0xFFFFFFFF; for (const b of buf) c = CRC[(c ^ b) & 255] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; };
function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length), t = Buffer.from(type, 'ascii');
  out.writeUInt32BE(data.length, 0); t.copy(out, 4); Buffer.from(data).copy(out, 8);
  out.writeUInt32BE(crc32(Buffer.concat([t, Buffer.from(data)])), 8 + data.length);
  return out;
}
export function encodePNG(c) {
  const raw = Buffer.alloc((c.w * 4 + 1) * c.h);
  for (let y = 0; y < c.h; y++) {
    raw[y * (c.w * 4 + 1)] = 0;
    for (let x = 0; x < c.w; x++) {
      const i = c.idx(x, y), o = y * (c.w * 4 + 1) + 1 + x * 4;
      // Fully transparent pixels are normalised to 0,0,0,0 so output never depends on stale colour.
      if (c.data[i + 3] === 0) { raw[o] = raw[o + 1] = raw[o + 2] = raw[o + 3] = 0; continue; }
      raw[o] = c.data[i]; raw[o + 1] = c.data[i + 1]; raw[o + 2] = c.data[i + 2]; raw[o + 3] = c.data[i + 3];
    }
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(c.w, 0); ihdr.writeUInt32BE(c.h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
// Reads back PNGs written by encodePNG (8-bit RGBA, non-interlaced). Used by
// build.mjs --check so the drift gate compares pixels, not zlib's bytes. Also reads
// 8-bit RGB (Chromium's screenshots: theme previews), filled in as opaque RGBA.
export function decodePNG(buf) {
  let p = 8, w = 0, h = 0, bpp = 4; const idat = [];
  while (p < buf.length) {
    const len = buf.readUInt32BE(p), type = buf.toString('ascii', p + 4, p + 8), data = buf.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      if (data[8] !== 8 || (data[9] !== 6 && data[9] !== 2) || data[12] !== 0) throw new Error('unsupported PNG');
      bpp = data[9] === 6 ? 4 : 3;
    }
    if (type === 'IDAT') idat.push(data);
    p += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * bpp, c = new Canvas(w, h), prev = Buffer.alloc(stride);
  let cur = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)], line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? cur[i - bpp] : 0, b = prev[i], cc = i >= bpp ? prev[i - bpp] : 0;
      const pr = f === 0 ? 0 : f === 1 ? a : f === 2 ? b : f === 3 ? (a + b) >> 1 : (() => { const pp = a + b - cc, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - cc); return pa <= pb && pa <= pc ? a : pb <= pc ? b : cc; })();
      cur[i] = (line[i] + pr) & 255;
    }
    if (bpp === 4) c.data.set(cur, y * stride);
    else for (let x = 0; x < w; x++) c.data.set([cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2], 255], (y * w + x) * 4);
    cur.copy(prev); 
  }
  return c;
}
