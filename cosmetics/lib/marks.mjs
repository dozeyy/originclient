// The Origin mark, rasterised pixel by pixel AT THE SIZE IT APPEARS (never drawn
// big and shrunk: that is what made round 1's logo look "crunched").
//
// The mark = three stroke-only ellipses sharing one centre, rotated 0 / 60 / 120
// degrees (website/store.html #mark: rx 90/75/60, ry 32/27/22 in a 200 box).
// `open` (default 1) fattens the minor axes for tiny sizes; MARK_TUNING holds
// hand-tuned overrides for specific (size, stroke) pairs.
import { ellipsePixels, Canvas, clamp, lerp } from './px.mjs';

const RINGS = [{ rx: 90, ry: 32, rot: 0 }, { rx: 75, ry: 27, rot: 60 }, { rx: 60, ry: 22, rot: 120 }];

// Hand tuning per (size, stroke): { ry: [r1, r2, r3] semi-minor axes in px, rx scale }.
// Add an entry when a size looks wrong in tools/cosmetics/marksheet; none = computed.
export const MARK_TUNING = {};

// Geometry of the mark at `size` px across (outer edge to outer edge of the long ring).
export function markGeometry(size, stroke = 2, open = 1) {
  const tuned = MARK_TUNING[`${size}/${stroke}`];
  const r1 = size / 2 - stroke / 2;                    // centre-line radius of the long ring
  const k = r1 / 90;
  return RINGS.map((r, i) => ({ rx: r.rx * k * (tuned?.rx ?? 1), ry: tuned?.ry?.[i] ?? r.ry * k * open, rot: r.rot }));
}

// Rasterise to { w, h, cx, cy, ring: Int8Array(-1 = empty, 0..2 = ring index), nx, ny, t }.
// For every stroke pixel: its ring, the outward unit normal (nx, ny) of that ring's
// curve at the pixel, and t in [-1, 1] across the stroke (the signed distance
// from the centre line over stroke/2; + is the outside of the ellipse).
export function markMask(size, stroke = 2, o = {}) {
  const w = size + 2, h = size + 2, cx = w / 2, cy = h / 2;
  const rings = markGeometry(size, stroke, o.open ?? 1);
  const ring = new Int8Array(w * h).fill(-1), nx = new Float32Array(w * h), ny = new Float32Array(w * h), tt = new Float32Array(w * h), depth = new Float32Array(w * h).fill(-1e9);
  rings.forEach((r, i) => {
    const th = (r.rot * Math.PI) / 180, c = Math.cos(th), s = Math.sin(th);
    for (const [x, y] of ellipsePixels(cx, cy, r.rx, r.ry, { rot: r.rot, stroke })) {
      if (x < 0 || y < 0 || x >= w || y >= h) continue;
      const px = x + 0.5 - cx, py = y + 0.5 - cy, u = px * c + py * s, v = -px * s + py * c;
      const f = (u / r.rx) ** 2 + (v / r.ry) ** 2, gu = u / (r.rx * r.rx), gv = v / (r.ry * r.ry), gl = Math.hypot(gu, gv) || 1e-9;
      const d = (f - 1) / (2 * gl);
      // gradient back in screen space
      const gx = gu * c - gv * s, gy = gu * s + gv * c, g2 = Math.hypot(gx, gy) || 1e-9;
      const t = clamp(d / (stroke / 2), -1, 1), i2 = y * w + x;
      // Where rings cross, the ring whose centre line is nearer to the pixel wins.
      const score = -Math.abs(d);
      if (score > depth[i2]) { depth[i2] = score; ring[i2] = i; nx[i2] = gx / g2; ny[i2] = gy / g2; tt[i2] = t; }
    }
  });
  return { w, h, cx, cy, ring, nx, ny, t: tt, rings, stroke, size };
}

// Metal-tube shading: the stroke is treated as a rounded wire lit from the top-left.
// Returns a 0..1 brightness for a mark pixel (use to index a ramp).
export function tubeLight(nx, ny, t, light = [-0.55, -0.8, 0.45]) {
  const z = Math.sqrt(Math.max(0, 1 - t * t));
  const n = [nx * t, ny * t, z], len = Math.hypot(...n) || 1;
  const L = Math.hypot(...light);
  return clamp(0.5 + 0.5 * ((n[0] * light[0] + n[1] * light[1] + n[2] * light[2]) / (len * L)) * 1.25);
}

// Draw the mark centred at (cx, cy) on `canvas` (cx, cy = pixel-corner coords of
// the centre, so an even size sits on a seam). `paint` is a colour or
// fn({x, y, ring, t, light, edge}) -> colour | null. `light` is the tube
// brightness 0..1 already computed; `edge` is true on the outermost stroke pixels.
export function drawMark(canvas, cx, cy, size, paint, stroke = 2, o = {}) {
  const m = markMask(size, stroke, o), ox = Math.round(cx - m.cx), oy = Math.round(cy - m.cy);
  for (let y = 0; y < m.h; y++) for (let x = 0; x < m.w; x++) {
    const i = y * m.w + x;
    if (m.ring[i] < 0) continue;
    const edge = Math.abs(m.t[i]) > 0.62 || stroke <= 1.4;
    const info = { x: x + ox, y: y + oy, lx: x, ly: y, ring: m.ring[i], t: m.t[i], edge, light: tubeLight(m.nx[i], m.ny[i], m.t[i]), nx: m.nx[i], ny: m.ny[i] };
    const c = typeof paint === 'function' ? paint(info) : paint;
    if (c) canvas.set(x + ox, y + oy, c);
  }
  return m;
}

// Convenience: a mark as its own canvas, painted with a ramp (dark -> light) by tube light.
export function markCanvas(size, colors, stroke = 2) {
  const c = new Canvas(size + 2, size + 2);
  drawMark(c, (size + 2) / 2, (size + 2) / 2, size, p => colors[Math.min(colors.length - 1, Math.floor(p.light * colors.length))], stroke);
  return c;
}
