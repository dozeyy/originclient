// The brand cape: dark woven cloth, the Origin mark embroidered in silver thread,
// a metallic stitched hem. Static, scale 8: painted 1 art pixel = 1 texture pixel
// on the 80x128 outside face (one density for the whole item).
import { Canvas, ramp, ditherIndex, bayer, clamp, smoothstep, hash2, noise2 } from '../../lib/px.mjs';
import { drawMark } from '../../lib/marks.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 8, W = 10 * S, H = 16 * S;
const MX = 40, MY = 52, MS = 62, STROKE = 4;      // mark: centre, size, stroke

const cloth = ramp('#2b3035', 8, { lo: 0.03, hi: 0.43, tint: 0.04, shadowHue: 232, lightHue: 205, shift: 0.5 });
const silver = ramp('#c4c8cd', 7, { lo: 0.16, hi: 0.97, shadowHue: 235, lightHue: 45, shift: 0.18, tint: 0.03 });
const steel = ramp('#757b84', 5, { lo: 0.08, hi: 0.62, shift: 0.3, tint: 0.04 });

// Hanging folds: they fan out from the collar and widen toward the hem. 0..1, ridge-shaped.
function folds(x, y) {
  const spread = 0.62 + 0.55 * (y / H);
  const u = (x - 40) / spread;
  const a = Math.sin(u * 0.205 + 0.8), b = Math.sin(u * 0.093 - 1.9 + y * 0.003), c = Math.sin(u * 0.41 + 2.2) * (0.35 + 0.65 * (y / H));
  const v = 0.5 + 0.34 * a + 0.2 * b + 0.12 * c;
  return clamp(v);
}
const twill = (x, y) => (((x + y) & 3) / 3) - 0.5;   // diagonal rib, one thread per pixel

export default {
  meta: { id: 'cape-origin', name: 'Origin Cape', kind: 'cape', cents: 599, blurb: 'The three rings, stitched in silver on dark woven cloth.' },
  cape: { scale: S, frames: 1, fps: 10 },
  paint() {
    const c = newCapeArt(S);
    // ------- the mark first (as a mask), so cloth lighting can react to it
    const markCv = new Canvas(W, H), info = new Map();
    drawMark(markCv, MX, MY, MS, p => { info.set(p.y * W + p.x, p); return '#fff'; }, STROKE);
    const near = new Float32Array(W * H).fill(99);                       // distance to the nearest mark pixel (halo)
    const R = 9;
    for (const [k] of info) {
      const px = k % W, py = (k / W) | 0;
      for (let y = Math.max(0, py - R); y <= Math.min(H - 1, py + R); y++) for (let x = Math.max(0, px - R); x <= Math.min(W - 1, px + R); x++) {
        const d = Math.hypot(x - px, y - py), i = y * W + x; if (d < near[i]) near[i] = d;
      }
    }
    const shadowAt = new Uint8Array(W * H);                               // the embroidery's drop shadow (down-right)
    for (const [k] of info) { const px = k % W + 2, py = ((k / W) | 0) + 2; if (px < W && py < H) shadowAt[py * W + px] = 1; for (const [dx, dy] of [[1, 2], [2, 1]]) if (px + dx - 2 < W) { const q = (py + dy - 2) * W + px + dx - 2; if (q < W * H) shadowAt[q] = 1; } }

    // ------- cloth: a lighting value per pixel, then dithered onto the ramp
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const f = folds(x, y), top = 1 - y / H, left = 1 - x / W;
      let t = 0.42 + (f - 0.5) * 0.7 + (top - 0.5) * 0.3 + (left - 0.5) * 0.16;
      t -= 0.2 * smoothstep(0.5, 1.05, Math.hypot((x - 40) / 44, (y - 62) / 78));        // vignette
      t += twill(x, y) * 0.085 + (noise2(x * 0.5, y * 0.5, 3) - 0.5) * 0.06;                // weave + slub
      const d = near[y * W + x];
      if (d < R) t += (1 - d / R) ** 2 * 0.3;                                               // soft lift around the mark
      if (shadowAt[y * W + x] && !info.has(y * W + x)) t -= 0.2;                           // stitching sits on the cloth
      c.set(x, y, cloth[ditherIndex(clamp(t), cloth.length, bayer(x, y, 4) * 0.8 + 0.1)]);
    }
    // ------- collar: gathered pleats under a stitched seam
    for (let y = 0; y < 9; y++) for (let x = 0; x < W; x++) {
      const k = y / 9, pleat = ((x >> 1) & 1) ? 0.18 : -0.12;
      const [r, g, b] = c.get(x, y), idx = cloth.indexOf('#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join(''));
      c.set(x, y, cloth[clamp(Math.round(idx + (pleat - 0.34) * 3 * (1 - k)), 0, cloth.length - 1)]);
    }
    for (let x = 0; x < W; x++) { c.set(x, 0, cloth[0]); c.set(x, 8, cloth[0]); c.set(x, 9, ((x >> 1) & 1) ? cloth[2] : cloth[1]); c.set(x, 10, ((x >> 2) & 1) ? steel[3] : cloth[2]); }
    // ------- the mark: tube-lit silver thread with a stitch sheen and a couple of glints
    drawMark(c, MX, MY, MS, p => {
      const stitch = (((p.x + p.y) >> 1) & 1) ? 0.045 : -0.045;
      const fold = (folds(p.x, p.y) - 0.5) * 0.22 + (1 - p.y / H - 0.5) * 0.12;
      const edgeDark = Math.abs(p.t) > 0.9 ? -0.13 : 0;
      const t = clamp(p.light * 0.96 + stitch + fold + edgeDark - 0.02);
      return silver[Math.min(silver.length - 1, Math.floor(t * silver.length))];
    }, STROKE);
    // glints: the brightest thread pixel in the upper-left of each half of the mark
    for (const left of [true, false]) {
      let best = null;
      for (const [k, p] of info) if ((p.x < MX) === left && p.y < MY - 2 && (!best || p.light - p.y * 0.002 > best.light - best.y * 0.002)) best = p;
      if (best) { const { x, y } = best; c.set(x, y, '#ffffff'); for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) if (info.has((y + dy) * W + x + dx)) c.set(x + dx, y + dy, silver[6]); }
    }
    // ------- hem: a metal bar with stitched cloth above it
    const HEM = 13, y0 = H - HEM;
    const hem = [
      () => cloth[0], x => (x & 1 ? steel[1] : steel[2]), () => silver[5], () => silver[4], () => steel[2],           // 0-4 piping
      () => cloth[1], x => ((x % 4) === 3 ? cloth[2] : silver[4]), () => cloth[1],                                // 5-7 stitch row
      () => cloth[2], () => cloth[0],                                                                                 // 8-9 shadow
      () => silver[6], () => silver[4], () => steel[2],                                                               // 10-12 metal edge
    ];
    for (let k = 0; k < HEM; k++) for (let x = 0; x < W; x++) c.set(x, y0 + k, hem[k](x));
    for (const gx of [9, 31, 58, 72]) { c.set(gx, y0 + 10, '#ffffff'); c.set(gx + 1, y0 + 10, silver[6]); }          // glints on the bar
    // ------- side piping, with its own shading
    for (let y = 0; y < H; y++) {
      const ink = y % 6 < 4;
      c.set(0, y, ink ? silver[5] : silver[3]); c.set(1, y, steel[3]); c.set(2, y, steel[1]);
      c.set(W - 1, y, steel[1]); c.set(W - 2, y, steel[2]); c.set(W - 3, y, cloth[1]);
    }
    return c;
  },
};
