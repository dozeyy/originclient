// Origin store cosmetics: how every item LOOKS, plus the 3D fitting room.
//
// store.js owns WHAT is for sale (names, list prices; the service charges); this file
// owns the art, keyed by the same item id. Everything is made in code, so a new design
// is one entry in CAPES/ART and no image files are needed.
//
// This file is the SOURCE OF TRUTH for the game too:
//   - capes: tools/cosmetics/export-capes.mjs renders each painter into the cape PNGs
//     the mod draws (see capeScale / capeArt and that script's header for the format);
//   - head items: HeadModels.java ports buildOrbitHalo / buildGoldHalo / buildCrown
//     1:1. They are written against the tiny Rig API below (push / pop / box / ring),
//     which is the same vocabulary as HeadModels.Mesh, so a port is mechanical;
//   - particles: FX is plain data (colour, rate, size, life, motion) for the mod's
//     particle code to read; the store plays it with the emitter below.
//
// Animation: everything that moves is a function of time that repeats exactly every
// LOOP seconds (only whole-number cycles per loop). That's what lets the shop cards
// play a short pre-rendered loop with no visible seam.
//
// The player model follows Minecraft's own: 1 unit = 1 skin pixel, a 64x64
// skin with the standard UV layout, classic (4px) arms.
import * as THREE from './vendor/three.module.min.js';

// Exact pixel colours, like world.js: no colour management, linear output.
THREE.ColorManagement.enabled = false;

export const LOOP = 2.4;              // seconds; every animation repeats on this
const ORBIT_LOOP = 3 * LOOP;          // the Orbit Halo's slower, longer cycle
const STILL_T = 0.15 * LOOP;          // the frame used when nothing may move
const TAU = Math.PI * 2;

// ------------------------------------------------------------ colour helpers
const rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const mix = (a, b, t) => { const A = rgb(a), B = rgb(b); return A.map((v, i) => v + (B[i] - v) * t); };
const css = (c, k = 1, a = 1) => `rgba(${c.map(v => Math.max(0, Math.min(255, Math.round(v * k)))).join(',')},${a})`;
const fract = x => x - Math.floor(x);
const hash = n => fract(Math.sin(n * 12.9898 + 4.1414) * 43758.5453);
const clamp = (v, lo = 0, hi = 1) => Math.max(lo, Math.min(hi, v));
const lerp = (a, b, t) => a + (b - a) * t;
const smooth = (a, b, v) => { const t = clamp((v - a) / (b - a)); return t * t * (3 - 2 * t); };
// stops: [[t, '#hex'], ...] with t ascending 0..1
function ramp(stops, t) {
  t = Math.max(0, Math.min(1, t));
  for (let i = 1; i < stops.length; i++) {
    if (t <= stops[i][0]) {
      const [t0, a] = stops[i - 1], [t1, b] = stops[i];
      return mix(a, b, (t - t0) / (t1 - t0 || 1));
    }
  }
  return rgb(stops[stops.length - 1][1]);
}
// Seeded random, so every visitor sees the same "noise" in the art (and an
// animated cape's noise holds still from frame to frame).
function rng(seed) {
  return () => {
    seed = seed + 0x6D2B79F5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}
const seedOf = id => [...id].reduce((h, c) => Math.imul(h ^ c.charCodeAt(0), 16777619), 2166136261);
function makeCanvas(w, h) { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; }

// Smooth value noise (0..1) and fractal noise, for clouds, nebulae and cloth grain.
function vnoise(x, y, s = 0) {
  const xi = Math.floor(x), yi = Math.floor(y), xf = x - xi, yf = y - yi;
  const u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
  const h = (a, b) => hash(a * 57.13 + b * 131.7 + s * 17.9);
  return lerp(lerp(h(xi, yi), h(xi + 1, yi), u), lerp(h(xi, yi + 1), h(xi + 1, yi + 1), u), v);
}
const fbm = (x, y, s = 0) => (0.5 * vnoise(x, y, s) + 0.25 * vnoise(x * 2, y * 2, s + 1) + 0.125 * vnoise(x * 4, y * 4, s + 2) + 0.0625 * vnoise(x * 8, y * 8, s + 3)) / 0.9375;

// ------------------------------------------------------------- cape painters
// A cape's OUTSIDE face is painted in a fixed "design space" of 80x128 units
// (the vanilla 10x16 face at 8x), whatever the real canvas size: the harness
// scales the context, so painters never think about pixels. Painters take
// (ctx, ph, r): ph = loop phase 0..1 (static capes ignore it), r = seeded random.
// Real size per item: capeScale(id) (see the item table): static capes are painted
// at 8x (80x128 px), animated ones at 4x (40x64 px) so a 24-frame strip stays
// under the 4 MB GPU-texture budget. Keep details >= 1.5 units wide so they survive
// the 4x downscale.
const CW = 80, CH = 128;

function vgrad(ctx, y0, y1, stops) {
  const g = ctx.createLinearGradient(0, y0, 0, y1);
  stops.forEach(([t, c]) => g.addColorStop(t, c));
  return g;
}
function hgrad(ctx, x0, x1, stops) {
  const g = ctx.createLinearGradient(x0, 0, x1, 0);
  stops.forEach(([t, c]) => g.addColorStop(t, c));
  return g;
}
// Soft additive light.
function glow(ctx, x, y, rad, hex, a = 0.5) {
  const c = rgb(hex), g = ctx.createRadialGradient(x, y, 0, x, y, rad);
  g.addColorStop(0, `rgba(${c},${a})`);
  g.addColorStop(0.4, `rgba(${c},${a * 0.35})`);
  g.addColorStop(1, `rgba(${c},0)`);
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  ctx.fillStyle = g;
  ctx.fillRect(x - rad, y - rad, rad * 2, rad * 2);
  ctx.restore();
}
// A thin four-point star (a sparkle).
function glint(ctx, x, y, len, hex = '#ffffff', a = 1) {
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  ctx.globalAlpha = a;
  ctx.fillStyle = hex;
  ctx.beginPath();
  ctx.moveTo(x - len, y);
  ctx.quadraticCurveTo(x, y, x, y - len);
  ctx.quadraticCurveTo(x, y, x + len, y);
  ctx.quadraticCurveTo(x, y, x, y + len);
  ctx.quadraticCurveTo(x, y, x - len, y);
  ctx.fill();
  ctx.restore();
  glow(ctx, x, y, len * 0.8, hex, a * 0.4);
}
// Per-pixel colour from design-space coordinates: (X, Y) -> [r, g, b] (or null to keep).
function paintPixels(ctx, fn) {
  const w = ctx.canvas.width, h = ctx.canvas.height, k = w / CW;
  const img = ctx.getImageData(0, 0, w, h), d = img.data;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const c = fn((x + 0.5) / k, (y + 0.5) / k, d.slice((y * w + x) * 4, (y * w + x) * 4 + 3));
      if (!c) continue;
      const i = (y * w + x) * 4;
      d[i] = c[0]; d[i + 1] = c[1]; d[i + 2] = c[2]; d[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
}
// Cloth: vertical folds that fan out towards the hem, a darker neck/shoulder, a
// vignette, a fine weave and (for silks) a bright sheen on the fold crests.
// Multiplies whatever is already painted, so call it BEFORE glowing things.
function foldAt(X, Y) {
  const fan = 1 + 0.3 * (Y / CH);
  let f = 0;
  for (const [w, p, a] of [[0.24, 0.3, 0.45], [0.41, 2.0, 0.3], [0.13, 4.1, 0.25]]) {
    f += a * Math.sin((X - 40) * w * fan + p + 0.8 * Math.sin(Y * 0.03 + p * 3));
  }
  return f;
}
function fabric(ctx, { fold = 0.1, sheen = 0, weave = 0.03, vig = 0.2, shoulder = 0.22 } = {}) {
  const w = ctx.canvas.width, h = ctx.canvas.height, k = w / CW;
  const img = ctx.getImageData(0, 0, w, h), d = img.data;
  for (let y = 0; y < h; y++) {
    const Y = (y + 0.5) / k;
    for (let x = 0; x < w; x++) {
      const X = (x + 0.5) / k, f = foldAt(X, Y), t = Math.abs(X - 40) / 40;
      let m = 1 + fold * f;
      m *= 1 - vig * smooth(0.45, 1, t) - 0.1 * (Y / CH) ** 3;
      if (Y < 16) m *= 1 - shoulder * (1 - Y / 16) ** 2;
      if (weave) m *= 1 + weave * (((Math.floor(X * 1.5) + Math.floor(Y * 1.5)) % 3 === 0) ? 1 : -0.5);
      const lift = sheen * Math.max(0, f - 0.35) * 255;
      const i = (y * w + x) * 4;
      d[i] = clamp(d[i] * m + lift, 0, 255);
      d[i + 1] = clamp(d[i + 1] * m + lift, 0, 255);
      d[i + 2] = clamp(d[i + 2] * m + lift, 0, 255);
    }
  }
  ctx.putImageData(img, 0, 0);
}
// A sewn border: metallic gradient trim, a bevel (bright outer line, dark inner
// line) and a stitched seam. The exporter reads the edge strips from just inside
// the outer line (EDGE_INSET), so the trim carries round the cape's thickness.
const EDGE_INSET = 0.9;
function hem(ctx, stops, { w = 3.4, stitch = 'rgba(0,0,0,.4)', shine = 0.45 } = {}) {
  const g = ctx.createLinearGradient(0, 0, CW, CH);
  stops.forEach((c, i) => g.addColorStop(i / (stops.length - 1), c));
  ctx.save();
  ctx.strokeStyle = g; ctx.lineWidth = w;
  ctx.strokeRect(w / 2, w / 2, CW - w, CH - w);
  ctx.lineWidth = 0.6; ctx.strokeStyle = `rgba(255,255,255,${shine})`;
  ctx.strokeRect(0.3, 0.3, CW - 0.6, CH - 0.6);
  ctx.strokeStyle = 'rgba(0,0,0,.5)';
  ctx.strokeRect(w + 0.3, w + 0.3, CW - 2 * w - 0.6, CH - 2 * w - 0.6);
  if (stitch) {
    ctx.setLineDash([2.2, 1.7]); ctx.lineWidth = 0.7; ctx.strokeStyle = stitch;
    ctx.strokeRect(w * 0.55, w * 0.55, CW - w * 1.1, CH - w * 1.1);
  }
  ctx.restore();
}
// Tone-on-tone woven diamonds across the whole cloth.
function jacquard(ctx, color, pitch = 8, size = 2.2) {
  ctx.save();
  ctx.fillStyle = color;
  for (let y = 0, row = 0; y < CH + pitch; y += pitch / 2, row++) {
    for (let x = (row % 2) * pitch / 2; x < CW + pitch; x += pitch) {
      ctx.beginPath();
      ctx.moveTo(x, y - size); ctx.lineTo(x + size, y); ctx.lineTo(x, y + size); ctx.lineTo(x - size, y);
      ctx.fill();
    }
  }
  ctx.restore();
}
// The Origin mark (website/index.html #mark): three rings, 0/60/120 degrees.
const MARK = [[90, 32, 0], [75, 27, 60], [60, 22, 120]];
function markRings(ctx, cx, cy, size, fn) {
  const s = size / 200;
  for (const [rx, ry, deg] of MARK) {
    ctx.beginPath();
    ctx.ellipse(cx, cy, rx * s, ry * s, deg * Math.PI / 180, 0, TAU);
    fn(rx * s, ry * s, deg * Math.PI / 180);
  }
}
// The mark as satin embroidery: glow, cast shadow, metallic thread, stitch bands, highlight.
function threadMark(ctx, cx, cy, size, [lite, mid, dark], glowHex, w = 2.6) {
  const g = ctx.createLinearGradient(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
  g.addColorStop(0, lite); g.addColorStop(0.5, mid); g.addColorStop(1, dark);
  const trace = (dx = 0, dy = 0) => { ctx.save(); ctx.translate(dx, dy); markRings(ctx, cx, cy, size, () => ctx.stroke()); ctx.restore(); };
  ctx.save();
  ctx.lineCap = 'round';
  ctx.globalCompositeOperation = 'lighter'; ctx.strokeStyle = glowHex; ctx.globalAlpha = 0.2; ctx.lineWidth = w * 3.4; trace();
  ctx.globalCompositeOperation = 'source-over';
  ctx.globalAlpha = 0.55; ctx.strokeStyle = '#000'; ctx.lineWidth = w + 0.9; trace(0.7, 1.1);
  ctx.globalAlpha = 1; ctx.strokeStyle = g; ctx.lineWidth = w; trace();
  ctx.strokeStyle = 'rgba(0,0,0,.26)'; ctx.setLineDash([0.55, 0.95]); trace(); ctx.setLineDash([]);
  ctx.strokeStyle = 'rgba(255,255,255,.55)'; ctx.lineWidth = w * 0.26; trace(-0.35, -0.45);
  ctx.restore();
}
function ridge(r, base, amp, rough = 0.55, n = 6) {
  let pts = [base + (r() - 0.5) * amp, base + (r() - 0.5) * amp], disp = amp;
  for (let i = 0; i < n; i++) {
    const next = [];
    for (let j = 0; j < pts.length - 1; j++) next.push(pts[j], (pts[j] + pts[j + 1]) / 2 + (r() - 0.5) * disp);
    next.push(pts[pts.length - 1]);
    pts = next; disp *= rough;
  }
  return x => {
    const t = clamp(x / CW) * (pts.length - 1), i = Math.min(pts.length - 2, Math.floor(t));
    return lerp(pts[i], pts[i + 1], t - i);
  };
}
function fillRidge(ctx, fn, fill) {
  ctx.beginPath(); ctx.moveTo(0, CH);
  for (let x = 0; x <= CW; x += 1) ctx.lineTo(x, fn(x));
  ctx.lineTo(CW, CH); ctx.closePath();
  ctx.fillStyle = fill; ctx.fill();
}
function pine(ctx, x, base, h, fill) {
  const w = h * 0.42;
  ctx.fillStyle = fill;
  ctx.beginPath();
  ctx.moveTo(x, base - h);
  ctx.lineTo(x + w * 0.5, base - h * 0.5); ctx.lineTo(x + w * 0.28, base - h * 0.5);
  ctx.lineTo(x + w * 0.62, base - h * 0.15); ctx.lineTo(x + w * 0.1, base - h * 0.15);
  ctx.lineTo(x + w * 0.1, base);
  ctx.lineTo(x - w * 0.1, base); ctx.lineTo(x - w * 0.1, base - h * 0.15);
  ctx.lineTo(x - w * 0.62, base - h * 0.15); ctx.lineTo(x - w * 0.28, base - h * 0.5);
  ctx.lineTo(x - w * 0.5, base - h * 0.5);
  ctx.closePath(); ctx.fill();
}
function snowflake(ctx, cx, cy, R, color = '#ffffff', w = 1.1) {
  const draw = (col, lw, dx, dy, a) => {
    ctx.save(); ctx.translate(cx + dx, cy + dy); ctx.strokeStyle = col; ctx.lineWidth = lw; ctx.lineCap = 'round'; ctx.globalAlpha = a;
    for (let i = 0; i < 6; i++) {
      ctx.save(); ctx.rotate(i * Math.PI / 3);
      ctx.beginPath(); ctx.moveTo(R * 0.12, 0); ctx.lineTo(R, 0); ctx.stroke();
      for (const [t, l] of [[0.36, 0.34], [0.6, 0.27], [0.8, 0.16]]) {
        for (const s of [-1, 1]) {
          ctx.beginPath(); ctx.moveTo(R * t, 0); ctx.lineTo(R * t + R * l * 0.5, s * R * l * 0.87); ctx.stroke();
        }
      }
      ctx.restore();
    }
    ctx.beginPath();
    for (let i = 0; i < 6; i++) ctx.lineTo(Math.cos(i * Math.PI / 3) * R * 0.2, Math.sin(i * Math.PI / 3) * R * 0.2);
    ctx.closePath(); ctx.stroke();
    ctx.restore();
  };
  draw('#4d8fc4', w * 2.2, 0.6, 0.9, 0.35);
  draw(color, w, 0, 0, 1);
}
function frostGrow(ctx, r, x, y, ang, len, depth) {
  const x2 = x + Math.cos(ang) * len, y2 = y + Math.sin(ang) * len;
  ctx.lineWidth = Math.max(0.45, depth * 0.3);
  ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x2, y2); ctx.stroke();
  if (depth <= 0) return;
  frostGrow(ctx, r, x2, y2, ang + 0.5 + r() * 0.3, len * 0.6, depth - 1);
  frostGrow(ctx, r, x2, y2, ang - 0.5 - r() * 0.3, len * 0.6, depth - 1);
  frostGrow(ctx, r, x2, y2, ang + (r() - 0.5) * 0.2, len * 0.8, depth - 1);
}
const FIRE = [[0, '#7a1608'], [0.35, '#e0451b'], [0.7, '#ffb23e'], [1, '#fff1a8']];

const CAPES = {
  // ============================== still capes (painted at 8x: 80x128) ==============================
  origin(ctx, ph, r) {
    // Deepslate-green velvet with the Origin rings in satin mint thread.
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#1f2d26'], [0.5, '#111a15'], [1, '#070b09']]);
    ctx.fillRect(0, 0, CW, CH);
    jacquard(ctx, 'rgba(131,226,181,.06)');
    glow(ctx, 40, 58, 48, '#4bc8ae', 0.2);
    threadMark(ctx, 40, 58, 74, ['#d2ffe9', '#6fdfae', '#2c8f69'], '#4bc8ae');
    for (const [x, dy] of [[34, 0], [40, -1.6], [46, 0]]) {   // three embroidered beads under the mark
      ctx.fillStyle = '#8af0c0'; ctx.beginPath(); ctx.arc(x, 102 + dy, 1.5, 0, TAU); ctx.fill();
    }
    fabric(ctx, { fold: 0.12, sheen: 0.05, weave: 0.035 });
    glint(ctx, 62, 40, 3.4, '#d2ffe9', 0.7);
    hem(ctx, ['#e4fff3', '#6fdfae', '#2c8f69', '#a6f0cb', '#2c8f69'], { stitch: 'rgba(5,30,20,.55)' });
  },

  golden(ctx, ph, r) {
    // A golden-hour landscape: layered sky, bloom, sun behind ridges and pines.
    ctx.fillStyle = vgrad(ctx, 0, 100, [[0, '#0f1530'], [0.28, '#2b2a58'], [0.5, '#7a4a6a'], [0.72, '#d97a62'], [0.9, '#f4b074'], [1, '#fbd596']]);
    ctx.fillRect(0, 0, CW, CH);
    for (let i = 0; i < 34; i++) {
      const y = r() * 36, a = (1 - y / 40) * (0.35 + r() * 0.5);
      ctx.fillStyle = `rgba(255,240,220,${a})`; ctx.fillRect(r() * CW, y, 0.9, 0.9);
    }
    glow(ctx, 40, 94, 58, '#ffd9a0', 0.62);
    ctx.save();                                              // god rays
    ctx.globalCompositeOperation = 'lighter';
    for (let i = 0; i < 9; i++) {
      const a = -Math.PI / 2 + (i - 4) * 0.2, w = 0.05;
      ctx.fillStyle = 'rgba(255,214,150,.07)';
      ctx.beginPath(); ctx.moveTo(40, 94);
      ctx.lineTo(40 + Math.cos(a - w) * 120, 94 + Math.sin(a - w) * 120); ctx.lineTo(40 + Math.cos(a + w) * 120, 94 + Math.sin(a + w) * 120);
      ctx.fill();
    }
    ctx.restore();
    for (const [cx, cy, w, h] of [[22, 50, 34, 5], [60, 60, 40, 6], [30, 72, 46, 5], [64, 42, 22, 3.5], [14, 84, 24, 4]]) {
      ctx.save();
      ctx.fillStyle = vgrad(ctx, cy - h, cy + h, [[0, 'rgba(70,44,90,.85)'], [0.55, 'rgba(210,110,110,.8)'], [1, 'rgba(255,190,130,.9)']]);
      for (let i = 0; i < 12; i++) {
        ctx.beginPath();
        ctx.ellipse(cx + (r() - 0.5) * w, cy + (r() - 0.5) * h * 0.6, w * (0.12 + r() * 0.16), h * (0.5 + r() * 0.5), 0, 0, TAU);
        ctx.fill();
      }
      ctx.restore();
    }
    ctx.fillStyle = vgrad(ctx, 78, 106, [[0, '#fff8e0'], [1, '#ffc070']]);   // the sun
    ctx.beginPath(); ctx.arc(40, 94, 15, 0, TAU); ctx.fill();
    const far = ridge(r, 99, 10), mid = ridge(r, 105, 10), near = ridge(r, 111, 7);
    fillRidge(ctx, far, vgrad(ctx, 84, 112, [[0, '#a56a80'], [1, '#6a4262']]));
    ctx.strokeStyle = 'rgba(255,205,150,.55)'; ctx.lineWidth = 0.7;
    ctx.beginPath(); for (let x = 0; x <= CW; x++) ctx[x ? 'lineTo' : 'moveTo'](x, far(x) + 0.3); ctx.stroke();
    fillRidge(ctx, mid, vgrad(ctx, 96, 120, [[0, '#5a3a66'], [1, '#2e1f40']]));
    fillRidge(ctx, near, '#150f20');
    for (let x = 3; x < CW; x += 3.4 + r() * 4) pine(ctx, x, near(x) + 2, 6 + r() * 8, x % 2 ? '#0e0a16' : '#120d1c');
    ctx.fillStyle = vgrad(ctx, 112, CH, [[0, '#1b1430'], [1, '#0a0712']]); ctx.fillRect(0, 114, CW, 14);   // dark water
    ctx.save(); ctx.globalCompositeOperation = 'lighter';                    // the sun's reflection, broken by ripples
    ctx.fillStyle = vgrad(ctx, 114, CH, [[0, 'rgba(255,200,130,.5)'], [1, 'rgba(255,200,130,.05)']]);
    ctx.beginPath(); ctx.moveTo(30, 114); ctx.lineTo(50, 114); ctx.lineTo(46, CH); ctx.lineTo(34, CH); ctx.fill();
    ctx.globalCompositeOperation = 'destination-out';
    for (let y = 116; y < 127; y += 2.4) { ctx.fillStyle = `rgba(0,0,0,${0.35 + r() * 0.3})`; ctx.fillRect(26 + r() * 6, y, 28 - r() * 6, 0.9); }
    ctx.restore();
    for (const [x, h] of [[5, 22], [13, 15], [9, 11], [74, 24], [66, 14], [71, 10]]) pine(ctx, x, 128, h, '#07040d');   // foreground pines
    ctx.strokeStyle = '#1a1226'; ctx.lineWidth = 0.9; ctx.lineCap = 'round';  // birds
    for (const [bx, by, s] of [[56, 52, 3], [63, 47, 2.2], [50, 46, 2]]) {
      ctx.beginPath(); ctx.moveTo(bx - s, by - s * 0.4); ctx.quadraticCurveTo(bx - s * 0.4, by - s * 0.5, bx, by); ctx.quadraticCurveTo(bx + s * 0.4, by - s * 0.5, bx + s, by - s * 0.4); ctx.stroke();
    }
    fabric(ctx, { fold: 0.07, sheen: 0.06, weave: 0.02, vig: 0.16 });
    hem(ctx, ['#fff1c2', '#e0b050', '#8f5f1e', '#f6d98a', '#b97c2a'], { stitch: 'rgba(60,30,0,.5)' });
  },

  frost(ctx, ph, r) {
    // Frosted ice-silk: faceted glass, a great snowflake, creeping frost and a snow-fur hem.
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#eaf7ff'], [0.3, '#a2d6f3'], [0.65, '#5a9fd6'], [1, '#2b5f9e']]);
    ctx.fillRect(0, 0, CW, CH);
    for (let i = 0; i < 26; i++) {                              // faceted ice
      const x = r() * CW, y = r() * CH;
      ctx.fillStyle = r() < 0.5 ? `rgba(255,255,255,${0.04 + r() * 0.07})` : `rgba(20,70,130,${0.05 + r() * 0.09})`;
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + (r() - 0.5) * 44, y + (r() - 0.5) * 44); ctx.lineTo(x + (r() - 0.5) * 44, y + (r() - 0.5) * 44); ctx.fill();
    }
    glow(ctx, 40, 50, 34, '#ffffff', 0.32);
    snowflake(ctx, 40, 50, 25, '#ffffff', 1.5);
    for (const [x, y, s] of [[16, 22, 8], [64, 26, 10], [18, 86, 9], [62, 92, 11], [12, 58, 6], [68, 64, 6.5]]) snowflake(ctx, x, y, s, '#f4fbff', 0.9);
    ctx.save();                                                  // frost creeping in from the sides
    ctx.strokeStyle = 'rgba(255,255,255,.6)'; ctx.lineCap = 'round';
    for (const [x, y, a] of [[2, 104, -0.9], [2, 90, -0.3], [CW - 2, 104, Math.PI + 0.9], [CW - 2, 90, Math.PI + 0.3], [2, 8, 0.9], [CW - 2, 8, Math.PI - 0.9], [2, 30, 0.4], [CW - 2, 30, Math.PI - 0.4]]) frostGrow(ctx, r, x, y, a, 6 + r() * 3, 3);
    ctx.restore();
    fabric(ctx, { fold: 0.08, sheen: 0.08, weave: 0.02, vig: 0.14 });
    for (let i = 0; i < 20; i++) glint(ctx, 5 + r() * 70, 8 + r() * 100, 1.2 + r() * 1.6, '#ffffff', 0.5 + r() * 0.5);
    glint(ctx, 54, 34, 4.5, '#ffffff', 0.9);
    hem(ctx, ['#ffffff', '#cde9f8', '#8fc6ea', '#e9f6ff'], { stitch: 'rgba(40,100,160,.45)' });
    // snow-fur cuff along the bottom
    ctx.fillStyle = vgrad(ctx, 112, CH, [[0, '#ffffff'], [1, '#b8d8ee']]);
    ctx.fillRect(0, 118, CW, 10);
    for (let x = -1; x < CW + 3; x += 2.6) {
      const rad = 2.6 + r() * 1.6, y = 118 + r() * 2;
      ctx.fillStyle = 'rgba(120,170,215,.5)'; ctx.beginPath(); ctx.arc(x + 0.4, y + 0.9, rad, 0, TAU); ctx.fill();
      ctx.fillStyle = '#ffffff'; ctx.beginPath(); ctx.arc(x, y, rad, 0, TAU); ctx.fill();
    }
    ctx.strokeStyle = 'rgba(100,150,200,.22)'; ctx.lineWidth = 0.45;
    for (let x = 1; x < CW; x += 1.9) { ctx.beginPath(); ctx.moveTo(x, 119 + r() * 2); ctx.lineTo(x + (r() - 0.5) * 1.5, 126 + r() * 2); ctx.stroke(); }
  },

  void(ctx, ph, r) {
    // A black hole in a purple nebula: accretion disc, photon ring, lensed light.
    paintPixels(ctx, (X, Y) => {
      const base = mix('#05020c', '#13062a', Y / CH);
      const n1 = fbm(X * 0.045 + 3, Y * 0.035, 1), n2 = fbm(X * 0.03 + 9, Y * 0.05 + 4, 2);
      const pu = smooth(0.48, 0.82, n1) * 0.7, te = smooth(0.58, 0.85, n2) * 0.45;
      return [base[0] + pu * 120 + te * 10, base[1] + pu * 30 + te * 120, base[2] + pu * 210 + te * 100];
    });
    for (let i = 0; i < 90; i++) {
      const a = 0.25 + r() * 0.7;
      ctx.fillStyle = r() < 0.2 ? `rgba(200,150,255,${a})` : `rgba(255,255,255,${a})`;
      ctx.fillRect(r() * CW, r() * CH, r() < 0.15 ? 1.3 : 0.8, r() < 0.15 ? 1.3 : 0.8);
    }
    const cx = 40, cy = 60, rot = -0.32;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (let arm = 0; arm < 3; arm++) {                            // dust arms spiralling in
      for (let t = 0; t < 1; t += 0.012) {
        const ang = arm * TAU / 3 + t * 5.2, rad = 11 + t * 31;
        ctx.fillStyle = css(mix('#7a2cff', '#2fd6a0', 1 - t), 1, (1 - t) * 0.6);
        const s = 0.5 + (1 - t) * 1.0;
        ctx.fillRect(cx + Math.cos(ang) * rad * 1.0 - s / 2, cy + Math.sin(ang) * rad * 0.62 - s / 2, s, s);
      }
    }
    ctx.restore();
    const disc = (a0, a1) => {
      for (const [rx, ry, col, w, al] of [[34, 10.5, '#5a1fb0', 5, 0.35], [29, 8.8, '#a45bff', 3, 0.55], [25, 7.4, '#3fe8b8', 2.2, 0.8], [22, 6.6, '#d8fff0', 1.1, 0.95]]) {
        ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = al; ctx.strokeStyle = col; ctx.lineWidth = w;
        ctx.beginPath(); ctx.ellipse(cx, cy, rx, ry, rot, a0, a1); ctx.stroke(); ctx.restore();
      }
    };
    disc(0, TAU);
    glow(ctx, cx, cy, 20, '#7a2cff', 0.35);
    ctx.fillStyle = '#000'; ctx.beginPath(); ctx.arc(cx, cy, 10.5, 0, TAU); ctx.fill();   // the hole
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    ctx.strokeStyle = '#6fffd0'; ctx.lineWidth = 1.3; ctx.beginPath(); ctx.arc(cx, cy, 10.8, 0, TAU); ctx.stroke();
    ctx.globalAlpha = 0.3; ctx.lineWidth = 4; ctx.stroke();
    ctx.globalAlpha = 0.5; ctx.strokeStyle = '#a45bff'; ctx.lineWidth = 1;                // light bent over the top
    ctx.beginPath(); ctx.ellipse(cx, cy - 1, 17, 14, rot * 0.3, Math.PI * 1.05, Math.PI * 1.95); ctx.stroke();
    ctx.restore();
    disc(0.05, Math.PI - 0.05);                                    // the near side of the disc passes in front
    for (const [x, y, s] of [[14, 20, 3], [66, 100, 3.4], [58, 18, 2.2], [20, 106, 2.4]]) glint(ctx, x, y, s, '#d8c0ff', 0.8);
    fabric(ctx, { fold: 0.05, sheen: 0.03, weave: 0.015, vig: 0.22 });
    hem(ctx, ['#7a2cff', '#2a0f45', '#2fd6a0', '#4b1a85', '#c86bff'], { stitch: 'rgba(160,255,225,.35)', shine: 0.3 });
  },

  // Origin+ members' cape: never sold, worn while a membership is active. Graphite
  // satin, engine-turned guilloche, the three rings in polished silver, a fine "+".
  plus(ctx, ph, r) {
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#262626'], [0.5, '#141414'], [1, '#060606']]);
    ctx.fillRect(0, 0, CW, CH);
    ctx.save();                                                   // guilloche rosette
    ctx.strokeStyle = 'rgba(224,224,224,.075)'; ctx.lineWidth = 0.4;
    for (let k = 0; k < 36; k++) {
      ctx.beginPath(); ctx.ellipse(40, 58, 36, 13, k * Math.PI / 36, 0, TAU); ctx.stroke();
    }
    ctx.lineWidth = 0.35; ctx.strokeStyle = 'rgba(224,224,224,.05)';
    for (let k = 0; k < 28; k++) { ctx.beginPath(); ctx.arc(40, 58, 38 + k * 0.9, 0, TAU); ctx.stroke(); }
    ctx.restore();
    // brushed-metal streaks across the satin
    ctx.fillStyle = 'rgba(255,255,255,.018)';
    for (let i = 0; i < 90; i++) ctx.fillRect(r() * CW, r() * CH, 0.5, 6 + r() * 30);
    glow(ctx, 40, 58, 44, '#cfcfcf', 0.16);
    threadMark(ctx, 40, 58, 74, ['#ffffff', '#ececec', '#8c8c8c'], '#cfcfcf', 2.6);
    ctx.save();                                                   // the plus, beneath
    const plusStroke = (dx, dy, col, w) => { ctx.strokeStyle = col; ctx.lineWidth = w; ctx.lineCap = 'butt'; ctx.beginPath(); ctx.moveTo(40 - 7 + dx, 102 + dy); ctx.lineTo(40 + 7 + dx, 102 + dy); ctx.moveTo(40 + dx, 95 + dy); ctx.lineTo(40 + dx, 109 + dy); ctx.stroke(); };
    glow(ctx, 40, 102, 16, '#d8d8d8', 0.2);
    plusStroke(0.5, 0.9, 'rgba(0,0,0,.6)', 2.6);
    plusStroke(0, 0, hgrad(ctx, 33, 47, [[0, '#8e8e8e'], [0.5, '#ffffff'], [1, '#9a9a9a']]), 2.2);
    ctx.fillStyle = '#e0e0e0';
    for (const dx of [-14, 14]) { ctx.beginPath(); ctx.moveTo(40 + dx, 100.2); ctx.lineTo(40 + dx + 1.8, 102); ctx.lineTo(40 + dx, 103.8); ctx.lineTo(40 + dx - 1.8, 102); ctx.fill(); }
    ctx.restore();
    ctx.save();                                                   // a fine double frame with corner diamonds
    ctx.strokeStyle = 'rgba(224,224,224,.6)'; ctx.lineWidth = 0.7; ctx.strokeRect(8, 8, CW - 16, CH - 16);
    ctx.strokeStyle = 'rgba(224,224,224,.28)'; ctx.lineWidth = 0.4; ctx.strokeRect(10, 10, CW - 20, CH - 20);
    ctx.fillStyle = '#e0e0e0';
    for (const [x, y] of [[8, 8], [CW - 8, 8], [8, CH - 8], [CW - 8, CH - 8]]) { ctx.beginPath(); ctx.moveTo(x, y - 2.6); ctx.lineTo(x + 2.6, y); ctx.lineTo(x, y + 2.6); ctx.lineTo(x - 2.6, y); ctx.fill(); }
    const sg = ctx.createLinearGradient(0, 0, CW, CH * 0.7);     // a broad band of light across the satin
    for (const [t, a] of [[0, 0], [0.36, 0], [0.5, 0.1], [0.64, 0], [1, 0]]) sg.addColorStop(t, `rgba(255,255,255,${a})`);
    ctx.fillStyle = sg; ctx.fillRect(0, 0, CW, CH);
    ctx.restore();
    fabric(ctx, { fold: 0.1, sheen: 0.09, weave: 0.025, vig: 0.24 });
    glint(ctx, 61, 38, 3.2, '#ffffff', 0.7);
    hem(ctx, ['#f4f4f4', '#8a8a8a', '#e6e6e6', '#6e6e6e', '#d8d8d8'], { stitch: 'rgba(0,0,0,.5)', shine: 0.6 });
  },

  // ============================== animated capes (painted at 4x: 40x64) ==============================
  orbit(ctx, ph, r) {
    // The mark as neon tubing with comets lapping each ring, a pulsing core and ripples.
    const P = ph * TAU, pulse = 0.5 + 0.5 * Math.sin(P * 2);
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#1b261f'], [0.55, '#0d130f'], [1, '#050806']]);
    ctx.fillRect(0, 0, CW, CH);
    jacquard(ctx, 'rgba(131,226,181,.045)', 8, 2.4);
    fabric(ctx, { fold: 0.1, sheen: 0.03, weave: 0, vig: 0.22 });
    const cx = 40, cy = 58, size = 74, s = size / 200;
    for (const off of [0, 0.5]) {                                  // two ripples a loop
      const t = fract(ph + off);
      ctx.save(); ctx.globalCompositeOperation = 'lighter';
      ctx.strokeStyle = `rgba(95,208,155,${0.4 * (1 - t) ** 2})`; ctx.lineWidth = 2.2;
      ctx.beginPath(); ctx.ellipse(cx, cy, 12 + t * 54, 8 + t * 40, 0, 0, TAU); ctx.stroke();
      ctx.restore();
    }
    glow(ctx, cx, cy, 46, '#4bc8ae', 0.14 + 0.1 * pulse);
    ctx.save(); ctx.lineCap = 'round'; ctx.globalCompositeOperation = 'lighter';
    for (const [w, col, a] of [[5.6, '#2f9a7d', 0.14 + 0.12 * pulse], [2.8, '#3fbf98', 0.55], [1.5, '#a8f5d6', 0.85]]) {
      ctx.strokeStyle = col; ctx.globalAlpha = a; ctx.lineWidth = w;
      markRings(ctx, cx, cy, size, () => ctx.stroke());
    }
    ctx.restore();
    const spec = [[1, 0], [-1, 0], [2, 0.25]];                    // [turns per loop, start]
    MARK.forEach(([rx, ry, deg], i) => {
      const a = deg * Math.PI / 180, [turns, st] = spec[i];
      for (const lap of (i === 0 ? [0, 0.5] : [0])) {
        for (let k = 18; k >= 0; k--) {
          const th = TAU * (turns * ph + st + lap) - Math.sign(turns) * k * 0.11;
          const ex = rx * s * Math.cos(th), ey = ry * s * Math.sin(th);
          const x = cx + ex * Math.cos(a) - ey * Math.sin(a), y = cy + ex * Math.sin(a) + ey * Math.cos(a);
          const f = 1 - k / 19;
          ctx.save(); ctx.globalCompositeOperation = 'lighter';
          ctx.fillStyle = k === 0 ? '#ffffff' : `rgba(235,255,245,${0.9 * f ** 1.3})`;
          ctx.beginPath(); ctx.arc(x, y, k === 0 ? 3 : 0.9 + 2.2 * f, 0, TAU); ctx.fill();
          ctx.restore();
          if (k === 0) glow(ctx, x, y, 13, '#83e2b5', 0.9);
        }
      }
    });
    glow(ctx, cx, cy, 11, '#bfffe3', 0.55 + 0.35 * pulse);       // the core
    ctx.save(); ctx.translate(cx, cy); ctx.rotate(P / 2 * 2); ctx.fillStyle = '#ffffff';
    const d = 2.4 + 0.5 * pulse; ctx.beginPath(); ctx.moveTo(0, -d); ctx.lineTo(d, 0); ctx.lineTo(0, d); ctx.lineTo(-d, 0); ctx.fill(); ctx.restore();
    for (let i = 0; i < 12; i++) {                                 // dust lifting off the cloth
      const life = fract(ph * (1 + i % 2) + hash(i + 3)), x = 6 + hash(i + 40) * 68 + Math.sin(life * TAU + i) * 2.5;
      glint(ctx, x, 120 - life * 100, 1.2 + hash(i) * 1.2, '#9af2cf', Math.sin(life * Math.PI) * 0.8);
    }
    hem(ctx, ['#e4fff3', css(mix('#2c8f69', '#8af0c0', pulse)), '#2c8f69', '#a6f0cb'], { stitch: 'rgba(5,30,20,.55)', w: 3.6 });
  },

  glitch(ctx, ph, r) {
    // A corrupted screen: the mark split into red/green/blue, rain of data, scanlines, two glitch bursts a loop.
    const P = ph * TAU;
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#0a1013'], [1, '#05070a']]);
    ctx.fillRect(0, 0, CW, CH);
    ctx.strokeStyle = 'rgba(46,255,168,.05)'; ctx.lineWidth = 0.5;
    for (let g = 10; g < CW; g += 10) { ctx.beginPath(); ctx.moveTo(g, 0); ctx.lineTo(g, CH); ctx.stroke(); }
    for (let g = 10; g < CH; g += 10) { ctx.beginPath(); ctx.moveTo(0, g); ctx.lineTo(CW, g); ctx.stroke(); }
    for (let c = 0; c < 9; c++) {                                   // data rain
      const x = 5 + c * 8.6, n = 1 + (c % 3), top = fract(ph * n + hash(c + 4)) * (CH + 40) - 20;
      ctx.fillStyle = vgrad(ctx, top - 36, top, [[0, 'rgba(46,255,168,0)'], [1, `rgba(46,255,168,${0.32 + 0.3 * hash(c)})`]]);
      ctx.fillRect(x, top - 36, 2.2, 36);
      ctx.fillStyle = '#c8fff0'; ctx.fillRect(x, top, 2.2, 3);
    }
    const off = 4 + 2.4 * Math.sin(P * 3);
    ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.lineCap = 'round';
    for (const [dx, dy, col] of [[-off, 0, '#ff2e55'], [0, 0, '#2effa8'], [off, Math.sign(off) * 0.8, '#2e7bff']]) {
      ctx.strokeStyle = col; ctx.globalAlpha = 0.22; ctx.lineWidth = 6; ctx.save(); ctx.translate(dx, dy); markRings(ctx, 40, 58, 74, () => ctx.stroke()); ctx.restore();
      ctx.globalAlpha = 0.6; ctx.lineWidth = 2.2; ctx.save(); ctx.translate(dx, dy); markRings(ctx, 40, 58, 74, () => ctx.stroke()); ctx.restore();
    }
    ctx.restore();
    ctx.fillStyle = 'rgba(0,0,0,.2)';                               // scanlines
    for (let y = 0; y < CH; y += 4) ctx.fillRect(0, y, CW, 1.8);
    const band = fract(ph) * (CH + 24) - 12;                        // slow bright scan bar
    ctx.fillStyle = vgrad(ctx, band - 10, band + 10, [[0, 'rgba(120,255,210,0)'], [0.5, 'rgba(120,255,210,.11)'], [1, 'rgba(120,255,210,0)']]);
    ctx.fillRect(0, band - 10, CW, 20);
    const burst = ph % 0.5 < 0.1, seed = Math.floor(ph * 40);
    if (burst) {
      const k = ctx.canvas.width / CW;
      ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0);
      for (let i = 0; i < 6; i++) {                                 // slices of the cloth slide sideways
        const y = Math.floor(hash(seed + i) * 56) * 2, h = 3 + Math.floor(hash(seed + i + 9) * 4) * 2, dx = Math.round((hash(seed + i + 5) - 0.5) * 36);
        ctx.drawImage(ctx.canvas, 0, y * k, ctx.canvas.width, h * k, dx * k, y * k, ctx.canvas.width, h * k);
      }
      ctx.restore();
      ctx.save(); ctx.globalCompositeOperation = 'lighter';
      for (let i = 0; i < 4; i++) {
        ctx.fillStyle = ['rgba(255,46,85,.7)', 'rgba(46,255,168,.7)', 'rgba(46,123,255,.7)'][(seed + i) % 3];
        ctx.fillRect(Math.floor(hash(seed + i + 3) * 36) * 2, Math.floor(hash(seed + i + 4) * 60) * 2, 6 + hash(seed + i) * 18, 2 + (i % 2) * 2);
      }
      ctx.restore();
    }
    // CRT vignette
    const vg = ctx.createRadialGradient(40, 64, 30, 40, 64, 90); vg.addColorStop(0, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,.55)');
    ctx.fillStyle = vg; ctx.fillRect(0, 0, CW, CH);
    hem(ctx, burst ? ['#ff2e55', '#2e7bff', '#2effa8'] : ['#2effa8', '#0f3d30', '#2effa8', '#0f3d30'], { stitch: null, shine: 0.25, w: 3 });
  },

  inferno(ctx, ph, r) {
    // Charred cloth split by lava veins, with layered flames licking up from the hem and embers rising.
    const P = ph * TAU;
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#120606'], [0.55, '#1d0907'], [1, '#3a0e08']]);
    ctx.fillRect(0, 0, CW, CH);
    fabric(ctx, { fold: 0.1, sheen: 0, weave: 0, vig: 0.25 });
    ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
    const vein = (x, y, ang, len, depth, heat) => {                // lava veins: jagged, branching
      const pts = [[x, y]];
      for (let i = 0; i < 5; i++) { ang += (r() - 0.5) * 1.1; x += Math.cos(ang) * len / 5; y += Math.sin(ang) * len / 5; pts.push([x, y]); }
      for (const [w, col, a] of [[4.4, '#a02a10', 0.3], [2.4, '#ff6a2b', 0.8], [1, '#ffe39a', 0.95]]) {
        ctx.strokeStyle = col; ctx.globalAlpha = a * heat; ctx.lineWidth = w * (0.5 + 0.5 * (depth + 1) / 3);
        ctx.beginPath(); pts.forEach(([px, py], i) => ctx[i ? 'lineTo' : 'moveTo'](px, py)); ctx.stroke();
      }
      if (depth > 0) { vein(...pts[3], ang - 0.7, len * 0.55, depth - 1, heat); vein(...pts[2], ang + 0.8, len * 0.5, depth - 1, heat); }
    };
    for (let c = 0; c < 4; c++) vein(12 + c * 19 + r() * 6, CH - 40 - r() * 10, -Math.PI / 2 + (r() - 0.5) * 0.7, 34 + r() * 14, 1, 0.55 + 0.45 * Math.sin(P + c * 1.7));
    ctx.restore();
    glow(ctx, 40, CH, 76, '#ff5a1c', 0.5);
    for (const [hs, ws, stops] of [
      [1, 1, [[0, 'rgba(255,90,28,0)'], [0.4, '#c9300f'], [1, '#6e1006']]],
      [0.76, 0.72, [[0, 'rgba(255,170,60,0)'], [0.4, '#ff8a2b'], [1, '#e0451b']]],
      [0.5, 0.42, [[0, 'rgba(255,240,170,0)'], [0.45, '#ffe28a'], [1, '#ffb23e']]],
    ]) {
      for (let j = 0; j < 10; j++) {
        const bx = 3 + j * 8.2 + (hash(j) - 0.5) * 3, n = 1 + (j + (hs > 0.9 ? 0 : hs > 0.6 ? 1 : 2)) % 3, ph0 = hash(j * 3 + hs * 7) * TAU;
        const h = hs * (46 + 32 * hash(j + 7) + 10 * Math.sin(P * n + ph0)), sway = 3.4 * Math.sin(P * (1 + j % 2) + ph0) * (0.5 + 0.5 * hs);
        const w = ws * (6.5 + 2.5 * hash(j + 11)), base = CH + 4, tip = base - h;
        ctx.fillStyle = vgrad(ctx, tip, base, stops);
        ctx.beginPath();
        ctx.moveTo(bx - w, base);
        ctx.bezierCurveTo(bx - w * 1.1, base - h * 0.35, bx - w * 0.2 + sway * 0.6, base - h * 0.7, bx + sway, tip);
        ctx.bezierCurveTo(bx + w * 0.3 + sway * 0.6, base - h * 0.62, bx + w * 1.1, base - h * 0.32, bx + w, base);
        ctx.closePath(); ctx.fill();
      }
    }
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    for (let k = 0; k < 20; k++) {                                  // embers
      const life = fract(ph * (1 + k % 3) + hash(k)), x = clamp(4 + hash(k + 20) * 72 + Math.sin(life * TAU + k) * 3, 3, 77);
      ctx.fillStyle = `rgba(255,${150 + Math.round(90 * (1 - life))},60,${Math.sin(life * Math.PI)})`;
      const sz = 2 + 1.4 * (1 - life); ctx.fillRect(Math.round(x / 2) * 2, Math.round((CH - 30 - life * 96) / 2) * 2, sz, sz);
    }
    ctx.restore();
    hem(ctx, ['#ffb23e', '#7a1608', '#ff6a2b', '#3a0d08'], { stitch: 'rgba(255,200,100,.35)', shine: 0.25, w: 3.2 });
  },

  aurora(ctx, ph, r) {
    // Northern lights in rays and curtains over snowy pines, stars twinkling.
    const P = ph * TAU;
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#040a14'], [0.55, '#0a1c2a'], [1, '#12333b']]);
    ctx.fillRect(0, 0, CW, CH);
    for (let i = 0; i < 70; i++) {
      const x = r() * CW, y = r() * 80, n = r(), tw = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(P * (1 + i % 2) + n * 40)) ** 2;
      ctx.fillStyle = `rgba(220,240,255,${tw * (1 - y / 100)})`; ctx.fillRect(Math.round(x / 2) * 2, Math.round(y / 2) * 2, n > 0.9 ? 3 : 2, n > 0.9 ? 3 : 2);
    }
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    for (const [y0, amp, f, k, col, col2, len] of [
      [60, 10, 0.1, 1, '#3ee8b0', '#1b7e8f', 60], [76, 8, 0.13, -1, '#5fd09b', '#2a5fb0', 50], [46, 7, 0.17, 2, '#9a6bff', '#3a2aa0', 38]]) {
      for (let x = 0; x < CW; x += 2) {
        const yb = y0 + amp * Math.sin(x * f - P * k + y0), ray = 0.55 + 0.45 * Math.sin(x * 1.9 + P * k * 2 + y0) * Math.sin(x * 0.31 - P);
        const L = len * (0.65 + 0.35 * Math.sin(x * 0.21 + P * k + y0 * 0.2)) * (0.7 + 0.3 * ray);
        const g = ctx.createLinearGradient(0, yb - L, 0, yb);
        const c1 = rgb(col2), c2 = rgb(col);
        g.addColorStop(0, `rgba(${c1},0)`); g.addColorStop(0.65, `rgba(${c1},${0.32 * ray})`); g.addColorStop(0.95, `rgba(${c2},${0.95 * ray})`); g.addColorStop(1, `rgba(255,255,255,${0.7 * ray})`);
        ctx.fillStyle = g; ctx.fillRect(x, yb - L, 2.1, L);
      }
    }
    ctx.restore();
    glow(ctx, 40, 112, 50, '#3ee8b0', 0.2);
    const hills = ridge(r, 104, 10, 0.5);
    fillRidge(ctx, hills, vgrad(ctx, 90, CH, [[0, '#0a1a22'], [1, '#04080c']]));
    ctx.strokeStyle = 'rgba(160,255,230,.35)'; ctx.lineWidth = 0.8;
    ctx.beginPath(); for (let x = 0; x <= CW; x++) ctx[x ? 'lineTo' : 'moveTo'](x, hills(x) + 0.2); ctx.stroke();
    for (let x = 2; x < CW; x += 3 + r() * 3.5) { pine(ctx, x, hills(x) + 3, 8 + r() * 12, '#03070a'); ctx.fillStyle = 'rgba(210,240,255,.55)'; ctx.fillRect(x - 0.3, hills(x) - 5 - r() * 3, 0.9, 0.9); }
    fabric(ctx, { fold: 0.05, sheen: 0, weave: 0, vig: 0.2 });
    hem(ctx, ['#7ff5d2', '#12333b', '#9a6bff', '#12333b'], { stitch: 'rgba(160,255,230,.4)', shine: 0.3 });
  },

  starfall(ctx, ph, r) {
    // A night sky with a crescent moon, the Milky Way, shooting stars and a lit cabin.
    const P = ph * TAU;
    ctx.fillStyle = vgrad(ctx, 0, CH, [[0, '#050818'], [0.5, '#141a46'], [0.8, '#2f2060'], [1, '#4a2f6e']]);
    ctx.fillRect(0, 0, CW, CH);
    ctx.save(); ctx.translate(34, 56); ctx.rotate(-0.8);          // the Milky Way
    const mw = ctx.createLinearGradient(0, -22, 0, 22);
    mw.addColorStop(0, 'rgba(180,170,255,0)'); mw.addColorStop(0.5, 'rgba(200,190,255,.2)'); mw.addColorStop(1, 'rgba(180,170,255,0)');
    ctx.fillStyle = mw; ctx.fillRect(-90, -22, 180, 44);
    for (let i = 0; i < 90; i++) { ctx.fillStyle = `rgba(255,255,255,${0.15 + r() * 0.4})`; ctx.fillRect((r() - 0.5) * 150, (r() + r() - 1) * 18, 0.8, 0.8); }
    ctx.restore();
    for (let i = 0; i < 80; i++) {
      const x = r() * CW, y = r() * 96, n = r(), tw = 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(P * (1 + i % 2) + n * 90)) ** 2;
      ctx.fillStyle = n < 0.18 ? `rgba(190,205,255,${tw})` : `rgba(255,255,255,${tw * 0.85})`;
      ctx.fillRect(Math.round(x / 2) * 2, Math.round(y / 2) * 2, n > 0.92 ? 3 : 2, n > 0.92 ? 3 : 2);
    }
    for (const [x, y, sz] of [[14, 16, 3.2], [62, 62, 2.6], [24, 70, 2.2]]) glint(ctx, x, y, sz, '#cfd8ff', 0.5 + 0.5 * Math.sin(P + x));
    glow(ctx, 56, 24, 28, '#e8e2c0', 0.3);                          // moon
    ctx.save();
    ctx.fillStyle = vgrad(ctx, 14, 36, [[0, '#fffbe6'], [1, '#e6dfb8']]);
    ctx.beginPath(); ctx.arc(56, 25, 11, 0, TAU); ctx.arc(61.5, 22, 9.6, 0, TAU, true); ctx.fill('evenodd');
    ctx.fillStyle = 'rgba(160,150,110,.4)';
    for (const [x, y, s] of [[49, 26, 1.6], [51, 32, 1.2], [47, 21, 1]]) { ctx.beginPath(); ctx.arc(x, y, s, 0, TAU); ctx.fill(); }
    ctx.restore();
    for (let k = 0; k < 3; k++) {                                   // shooting stars: each crosses in a third of the loop
      const s = fract(ph + k / 3) * 3;
      if (s >= 1) continue;
      const hx = 84 - k * 12 - s * 78, hy = 4 + k * 26 + s * 52, a = Math.sin(s * Math.PI);
      const dx = 0.83, dy = -0.55, L = 40, g = ctx.createLinearGradient(hx, hy, hx + dx * L, hy + dy * L);
      g.addColorStop(0, `rgba(255,255,255,${a})`); g.addColorStop(0.4, `rgba(190,210,255,${a * 0.5})`); g.addColorStop(1, 'rgba(190,210,255,0)');
      ctx.save(); ctx.globalCompositeOperation = 'lighter';
      ctx.fillStyle = g; ctx.beginPath(); ctx.moveTo(hx, hy - 2); ctx.lineTo(hx + dx * L, hy + dy * L); ctx.lineTo(hx, hy + 2); ctx.fill();
      ctx.restore();
      glow(ctx, hx, hy, 9, '#cfe0ff', a * 0.9);
    }
    const back = ridge(r, 104, 9, 0.5), front = ridge(r, 114, 6, 0.5);
    fillRidge(ctx, back, vgrad(ctx, 90, CH, [[0, '#1a1a48'], [1, '#0a0b22']]));
    fillRidge(ctx, front, '#06071a');
    for (let x = 3; x < CW; x += 4 + r() * 4) pine(ctx, x, front(x) + 2, 8 + r() * 10, '#04050f');
    const cabinX = 52, cabinY = front(52);                           // a lit cabin
    ctx.fillStyle = '#04050f'; ctx.fillRect(cabinX - 4, cabinY - 5, 8, 6);
    ctx.beginPath(); ctx.moveTo(cabinX - 5.5, cabinY - 5); ctx.lineTo(cabinX, cabinY - 9.5); ctx.lineTo(cabinX + 5.5, cabinY - 5); ctx.fill();
    glow(ctx, cabinX, cabinY - 2, 9, '#ffb84a', 0.5 + 0.12 * Math.sin(P * 3));
    ctx.fillStyle = '#ffd27a'; ctx.fillRect(cabinX - 1.5, cabinY - 3.6, 3, 2.6);
    fabric(ctx, { fold: 0.05, sheen: 0, weave: 0, vig: 0.2 });
    hem(ctx, ['#e8eeff', '#1b1f4a', '#8fa6ff', '#1b1f4a'], { stitch: 'rgba(200,215,255,.4)', shine: 0.35 });
  },
};

// ---------------------------------------------------------- wing feathers
// Each wing has two feathered panels (inner, by the shoulder; outer, past the
// elbow). Feathers hang from the top edge (the bone): long flight feathers
// laid edge over edge, a short scalloped row of coverts over them, and a
// bright leading edge, like a real bird or Lunar-style angel wing.
function paintFeathers(ctx, W, H, p, r, lengthAt) {
  const fw = 3;
  for (let x0 = 0; x0 < W; x0 += fw - 1) {
    const L = Math.max(4, Math.round(H * lengthAt(Math.min(1, x0 / Math.max(1, W - fw)))));
    for (let y = 0; y < L; y++) {
      const tip = y >= L - 2 ? 1 : 0;           // pointed feather tips
      for (let dx = tip; dx < fw - tip; dx++) {
        const x = x0 + dx;
        if (x >= W) continue;
        let k = 0.9 + r() * 0.14;
        if (dx === 0) k *= 0.68;                // dark edge where feathers overlap
        if (dx === 1 && !tip) k *= 1.12;        // shaft highlight
        ctx.fillStyle = css(mix(p.mid, p.tip, y / L), k);
        ctx.fillRect(x, y, 1, 1);
      }
    }
  }
  const cov = Math.round(H * 0.32);
  for (let x = 0; x < W; x++) {
    const bottom = cov - (x % fw === 1 ? 0 : 1);
    for (let y = 0; y < bottom; y++) {
      let k = 0.92 + r() * 0.12;
      if (y === bottom - 1) k *= 0.72;
      ctx.fillStyle = css(mix(p.lead, p.mid, (y / cov) * 0.8), k);
      ctx.fillRect(x, y, 1, 1);
    }
  }
  ctx.fillStyle = css(rgb(p.lead), 1.12);
  ctx.fillRect(0, 0, W, 2);
}
// Panel sizes in model units (1 unit = 1 skin pixel); textures are 2x.
const WING_INNER = { w: 9, h: 10, length: f => 0.62 + 0.3 * f };
const WING_OUTER = { w: 11, h: 16, length: f => 0.74 + 0.26 * Math.sin(f * Math.PI * 0.85) };

// ---------------------------------------------------------------- the items
// kind: 'cape' | 'wings' | 'head'. animated: moves on its own (the shop card
// plays a loop). edge: the colour of the cape's thin edge in the 3D view.
const ART = {
  'cape-orbit': { kind: 'cape', paint: CAPES.orbit, edge: '#2c8f69', animated: true },
  'cape-glitch': { kind: 'cape', paint: CAPES.glitch, edge: '#14382d', animated: true },
  'cape-inferno': { kind: 'cape', paint: CAPES.inferno, edge: '#5a1a0a', animated: true },
  'cape-aurora': { kind: 'cape', paint: CAPES.aurora, edge: '#1d5a5a', animated: true },
  'cape-starfall': { kind: 'cape', paint: CAPES.starfall, edge: '#2a2f78', animated: true },
  'cape-origin': { kind: 'cape', paint: CAPES.origin, edge: '#3a9a76' },
  'cape-golden': { kind: 'cape', paint: CAPES.golden, edge: '#a8782a' },
  'cape-frost': { kind: 'cape', paint: CAPES.frost, edge: '#b9dcf2' },
  'cape-void': { kind: 'cape', paint: CAPES.void, edge: '#4b1a85' },
  // Not for sale (the service grants it to Origin+ members): see CAPES.plus.
  'cape-plus': { kind: 'cape', paint: CAPES.plus, edge: '#8a8a8a' },
  // Wings are built but NOT on sale (Will, 2026-10-12: "no wings for now").
  // To sell them again, add their ids back to ITEMS in store.js and the Wings
  // tab in store.html.
  'wings-ember': { kind: 'wings', animated: true, palette: { lead: '#fff1a8', mid: '#ff8a2b', tip: '#7a1608', bone: '#ffcf6b' } },
  'wings-void': { kind: 'wings', animated: true, palette: { lead: '#e6c4ff', mid: '#7a3cd0', tip: '#160826', bone: '#b07aff' } },
  'wings-frost': { kind: 'wings', animated: true, palette: { lead: '#ffffff', mid: '#cdeeff', tip: '#4f93c9', bone: '#ffffff' } },
  'halo-orbit': { kind: 'head', build: buildOrbitHalo, animated: true, loop: ORBIT_LOOP },
  'halo-gold': { kind: 'head', build: buildGoldHalo, animated: true },
  'crown-pixel': { kind: 'head', build: buildCrown, animated: true },
};
export const kindOf = id => ART[id]?.kind;
export const isAnimated = id => !!ART[id]?.animated;
// Seconds before an item's animation repeats (a whole number of LOOPs).
export const loopOf = id => ART[id]?.loop || LOOP;

// Cape texture scale = pixels per vanilla cape pixel (vanilla is 1). Static capes get
// 8x (the outside face is 80x128), animated ones 4x (40x64) so a strip of 24 frames
// stays under the 4 MB GPU-texture budget (256 x 3072 x 4 B = 3.1 MB).
export const capeScale = id => (ART[id].animated ? 4 : 8);
export const CAPE_FRAMES = 24;          // frames per LOOP in an animated cape's exported strip

// Live art canvases, one per item (and per wing panel). Animated capes are
// repainted into the same canvas each frame.
const canvases = new Map();
function capeCanvas(id, ph, scale = capeScale(id)) {
  const key = `${id}@${scale}`;
  let c = canvases.get(key);
  if (!c) { c = makeCanvas(10 * scale, 16 * scale); c.ph = null; canvases.set(key, c); }
  if (c.ph !== ph) {
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, c.width, c.height);
    ctx.scale(c.width / CW, c.width / CW);
    ART[id].paint(ctx, ph, rng(seedOf(id)));
    c.ph = ph;
  }
  return c;
}
function wingCanvas(id, panel) {
  const key = `${id}:${panel === WING_INNER ? 'inner' : 'outer'}`;
  if (!canvases.has(key)) {
    const c = makeCanvas(panel.w * 2, panel.h * 2);
    paintFeathers(c.getContext('2d'), c.width, c.height, ART[id].palette, rng(seedOf(key)), panel.length);
    canvases.set(key, c);
  }
  return canvases.get(key);
}

// For tools/cosmetics/export-capes.mjs, which turns these same paintings
// into the in-game cape textures (so the store and the game always match).
export const CAPE_IDS = Object.keys(ART).filter(id => ART[id].kind === 'cape');
export const capeEdge = id => ART[id].edge;
export { EDGE_INSET };
// A copy of the cape's outside face at phase ph (0..1), capeScale(id) px per vanilla pixel.
export function capeArt(id, ph) {
  const src = capeCanvas(id, ph), c = makeCanvas(src.width, src.height);
  c.getContext('2d').drawImage(src, 0, 0);
  return c;
}

// The item's 2D artwork as a canvas, for the no-WebGL fallback: a cape's
// still frame, a wing pair laid flat, or null for head items (pure geometry).
export function flatArt(id) {
  const a = ART[id];
  if (a?.kind === 'cape') {
    const src = capeCanvas(id, STILL_T / LOOP), c = makeCanvas(src.width, src.height);
    c.getContext('2d').drawImage(src, 0, 0);
    return c;
  }
  if (a?.kind === 'wings') {
    const inner = wingCanvas(id, WING_INNER), outer = wingCanvas(id, WING_OUTER);
    const half = inner.width + outer.width, c = makeCanvas(half * 2, outer.height), ctx = c.getContext('2d');
    for (const side of [1, -1]) {
      ctx.save();
      if (side < 0) { ctx.translate(c.width, 0); ctx.scale(-1, 1); }
      ctx.drawImage(inner, half, 0);
      ctx.drawImage(outer, half + inner.width, 0);
      ctx.restore();
    }
    return c;
  }
  return null;
}

const textures = new Map();
function pixelTexture(key, source) {
  if (textures.has(key)) return textures.get(key);
  const t = new THREE.CanvasTexture(source);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.generateMipmaps = false;
  textures.set(key, t);
  return t;
}
// Cape cloth is painted art, not pixel art: filtered, with mipmaps for the small shop cards.
function clothTexture(key, source) {
  if (textures.has(key)) return textures.get(key);
  const t = new THREE.CanvasTexture(source);
  t.magFilter = THREE.LinearFilter;
  t.minFilter = THREE.LinearMipmapLinearFilter;
  t.anisotropy = 4;
  textures.set(key, t);
  return t;
}

// ----------------------------------------------------------- shaded geometry
// Minecraft-style fixed face light, baked into vertex colours. BoxGeometry's
// faces come in the order +x, -x, +y, -y, +z, -z, 4 vertices each.
const FACE_SHADE = [0.72, 0.72, 1, 0.5, 0.95, 0.95];
function shadedBox(w, h, d, tint = '#ffffff') {
  const g = new THREE.BoxGeometry(w, h, d);
  const base = rgb(tint).map(v => v / 255);
  const col = [];
  for (let f = 0; f < 6; f++) for (let i = 0; i < 4; i++) col.push(...base.map(v => v * FACE_SHADE[f]));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  return g;
}
function block(w, h, d, hex, x, y, z) {
  const m = new THREE.Mesh(shadedBox(w, h, d, hex), new THREE.MeshBasicMaterial({ vertexColors: true }));
  m.position.set(x, y, z);
  return m;
}
const tone = (hex, k) => '#' + rgb(hex).map(v => clamp(Math.round(v * k), 0, 255).toString(16).padStart(2, '0')).join('');

// ------------------------------------------------------------------- effects
// Particles as DATA, so the mod can port them. One entry per item, a list of
// emitters. Every field:
//   anchor  where the emitter's coordinates live:
//             'cape'  origin at the neck seam of the cape, x across (+x = player's left),
//                     y down the cape (0 = top, -16 = hem), z = 0 the back, -1 the cloth's
//                     outer face;
//             'head'  the head-model space the head items use (1 unit = 1 skin pixel,
//                     y up, neck at y=24, face towards +z);
//             'wing'  the wing's own space (+x out along the bone).
//   colors  hex colours, one picked per particle
//   rate    particles spawned per second
//   life    seconds a particle lives (the store nudges it so a whole number of lives fit
//           in the item's loop: the mod may use it as is and jitter +-25%)
//   size    [at birth, at death] edge length in model units, linear over the life
//   from/to opposite corners of the spawn box (uniformly random inside), OR
//   ring    [radius, y0, y1]: spawn on a circle round the anchor's y axis
//   vel     [x, y, z] drift in model units per second
//   sway    [amplitude units, wobbles per life]: sideways (x) sine wobble
//   fade    'inout' = alpha sin(pi*age) (fades in and out) | 'out' = quick in, linear out
//   shape   'square' (Minecraft-style pixel) | 'glow' (soft round) | 'spark' (four-point star)
//   gain    brightness multiplier on the colour (default 1); everything is additive
// Keep them subtle: a few particles a second, never a cloud.
export const FX = {
  'cape-orbit': [
    { anchor: 'cape', colors: ['#83e2b5', '#4bc8ae', '#ffffff'], rate: 9, life: 1.6, size: [0.7, 0.15], from: [-4.5, -14, -1.2], to: [4.5, -1.5, -1.2], vel: [0, 0.8, -2.2], sway: [0.5, 1], fade: 'inout', shape: 'glow' },
    { anchor: 'cape', colors: ['#d2ffe9'], rate: 2, life: 1.2, size: [1.5, 0.5], from: [-4, -13, -1.2], to: [4, -3, -1.2], vel: [0, 0.3, -0.8], sway: [0, 0], fade: 'inout', shape: 'spark' },
  ],
  'cape-glitch': [
    { anchor: 'cape', colors: ['#ff2e55', '#2effa8', '#2e7bff'], rate: 7, life: 0.6, size: [0.9, 0.9], from: [-5, -15, -1.2], to: [5, -1, -1.2], vel: [0, 0, -1.5], sway: [0.4, 3], fade: 'inout', shape: 'square' },
  ],
  'cape-inferno': [
    { anchor: 'cape', colors: ['#ffb23e', '#ff6a2b', '#fff1a8'], rate: 12, life: 1.2, size: [0.8, 0.15], from: [-4.5, -16, -1.2], to: [4.5, -9, -1.2], vel: [0, 6, -2], sway: [0.7, 1.5], fade: 'out', shape: 'glow' },
    { anchor: 'cape', colors: ['#ff6a2b'], rate: 5, life: 1.2, size: [0.7, 0.7], from: [-4.5, -15, -1.2], to: [4.5, -8, -1.2], vel: [0, 5, -1.5], sway: [0.5, 2], fade: 'out', shape: 'square' },
  ],
  'cape-aurora': [
    { anchor: 'cape', colors: ['#3ee8b0', '#9a6bff', '#9af2cf'], rate: 8, life: 2.4, size: [0.8, 0.2], from: [-4.5, -14, -1.2], to: [4.5, -2, -1.2], vel: [-0.5, 1.4, -1.2], sway: [0.6, 1], fade: 'inout', shape: 'glow' },
  ],
  'cape-starfall': [
    { anchor: 'cape', colors: ['#ffffff', '#bcd2ff'], rate: 6, life: 1.2, size: [1.4, 0.3], from: [-4.5, -12, -1.2], to: [4.5, -1, -1.2], vel: [0.8, -2.2, -1.5], sway: [0, 0], fade: 'inout', shape: 'spark' },
    { anchor: 'cape', colors: ['#bcd2ff'], rate: 5, life: 2.4, size: [0.5, 0.5], from: [-4.5, -14, -1.2], to: [4.5, -2, -1.2], vel: [0.4, -1.2, -1], sway: [0.3, 1], fade: 'inout', shape: 'square' },
  ],
  'cape-origin': [
    { anchor: 'cape', colors: ['#83e2b5', '#d2ffe9'], rate: 3, life: 2.4, size: [1.2, 0.3], from: [-4, -12, -1.2], to: [4, -4, -1.2], vel: [0, 0.4, -0.6], sway: [0, 0], fade: 'inout', shape: 'spark', gain: 0.8 },
  ],
  'cape-golden': [
    { anchor: 'cape', colors: ['#ffd9a0', '#fff1c2'], rate: 4, life: 2.4, size: [0.7, 0.15], from: [-4.5, -14, -1.2], to: [4.5, -3, -1.2], vel: [0, 0.6, -0.9], sway: [0.4, 1], fade: 'inout', shape: 'glow', gain: 0.8 },
  ],
  'cape-frost': [
    { anchor: 'cape', colors: ['#ffffff', '#cdeeff'], rate: 6, life: 2.4, size: [0.6, 0.4], from: [-5, -2, -1.2], to: [5, -1, -1.2], vel: [0.3, -3.5, -0.6], sway: [0.6, 1.5], fade: 'inout', shape: 'square' },
  ],
  'cape-void': [
    { anchor: 'cape', colors: ['#c86bff', '#7a2cff', '#2fd6a0'], rate: 5, life: 2.4, size: [0.9, 0.2], from: [-4.5, -14, -1.2], to: [4.5, -2, -1.2], vel: [0, 0.8, -1.4], sway: [0.5, 1], fade: 'inout', shape: 'glow' },
  ],
  'cape-plus': [
    { anchor: 'cape', colors: ['#ffffff', '#e0e0e0'], rate: 3, life: 2.4, size: [1.2, 0.3], from: [-4, -13, -1.2], to: [4, -3, -1.2], vel: [0, 0.3, -0.5], sway: [0, 0], fade: 'inout', shape: 'spark', gain: 0.75 },
  ],
  'halo-orbit': [
    { anchor: 'head', colors: ['#83e2b5', '#4bc8ae', '#ffffff'], rate: 7, life: 1.44, size: [0.5, 0.1], ring: [4.2, 35.5, 38.5], vel: [0, 0.7, 0], sway: [0, 0], fade: 'inout', shape: 'glow' },
  ],
  'halo-gold': [
    { anchor: 'head', colors: ['#fff1b8', '#ffd56b'], rate: 5, life: 1.2, size: [1.1, 0.2], ring: [4.5, 34.8, 36.4], vel: [0, 0.8, 0], sway: [0, 0], fade: 'inout', shape: 'spark' },
  ],
  'crown-pixel': [
    { anchor: 'head', colors: ['#ffffff', '#fff1b8'], rate: 6, life: 1.2, size: [1.1, 0.2], from: [-4.5, 32.5, -4.5], to: [4.5, 39, 4.5], vel: [0, 0.9, 0], sway: [0, 0], fade: 'inout', shape: 'spark' },
  ],
  'wings-ember': [{ anchor: 'wing', colors: ['#ffb23e', '#ff6a2b'], rate: 8, life: 1.2, size: [0.8, 0.2], from: [1, -14, 0], to: [10, -3, 0], vel: [0, 4, -1.6], sway: [0.5, 1], fade: 'inout', shape: 'glow' }],
  'wings-void': [{ anchor: 'wing', colors: ['#c86bff', '#7a2cff'], rate: 8, life: 1.2, size: [0.8, 0.2], from: [1, -14, 0], to: [10, -3, 0], vel: [0, -4, -1.6], sway: [0.5, 1], fade: 'inout', shape: 'glow' }],
  'wings-frost': [{ anchor: 'wing', colors: ['#ffffff', '#cdeeff'], rate: 8, life: 1.2, size: [0.8, 0.2], from: [1, -14, 0], to: [10, -3, 0], vel: [0, -4, -1.6], sway: [0.5, 1], fade: 'inout', shape: 'glow' }],
};

// The store's player for FX: Points whose per-particle size, colour and alpha come
// from the spec. Particle i of n lives a fraction (ph * cycles + i / n) mod 1 of its
// life; its spawn spot is hashed from (i, which cycle) so spots vary but the whole
// thing repeats exactly every loop.
const FX_SHAPES = { square: 0, glow: 1, spark: 2 };
function fxEmitter(spec, loop) {
  const cycles = Math.max(1, Math.round(loop / spec.life)), life = loop / cycles;
  const count = Math.max(2, Math.round(spec.rate * life));
  const pos = new Float32Array(count * 3), col = new Float32Array(count * 4), size = new Float32Array(count);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  geo.setAttribute('aColor', new THREE.BufferAttribute(col, 4));
  geo.setAttribute('aSize', new THREE.BufferAttribute(size, 1));
  const mat = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    uniforms: { uScale: { value: 300 }, uShape: { value: FX_SHAPES[spec.shape] ?? 0 } },
    vertexShader: `attribute vec4 aColor; attribute float aSize; uniform float uScale; varying vec4 vColor;
      void main() { vColor = aColor; vec4 mv = modelViewMatrix * vec4(position, 1.0);
        gl_PointSize = max(1.5, aSize * uScale / -mv.z); gl_Position = projectionMatrix * mv; }`,
    fragmentShader: `uniform int uShape; varying vec4 vColor;
      void main() { vec2 p = gl_PointCoord * 2.0 - 1.0; float a = 1.0;
        if (uShape == 1) { a = pow(max(0.0, 1.0 - dot(p, p)), 1.7); }
        else if (uShape == 2) { vec2 q = abs(p);
          a = max(pow(max(0.0, 1.0 - q.x), 3.0) * max(0.0, 1.0 - q.y * 7.0), pow(max(0.0, 1.0 - q.y), 3.0) * max(0.0, 1.0 - q.x * 7.0));
          a += 0.6 * pow(max(0.0, 1.0 - dot(p, p) * 2.2), 2.0); }
        gl_FragColor = vec4(vColor.rgb, vColor.a * a); }`,
  });
  const points = new THREE.Points(geo, mat);
  points.frustumCulled = false;               // positions change every frame
  points.onBeforeRender = (renderer, scene, camera) => {
    mat.uniforms.uScale.value = renderer.getDrawingBufferSize(new THREE.Vector2()).y / (2 * Math.tan(camera.fov * Math.PI / 360));
  };
  const palette = spec.colors.map(h => rgb(h).map(v => v / 255));
  const [sx, sy, sz] = spec.vel, [swayAmp, swayN] = spec.sway || [0, 0], gain = spec.gain ?? 1;
  const seed = hash(spec.rate * 7.1 + spec.life * 3.3 + spec.colors.length);
  points.userData.update = ph => {
    for (let i = 0; i < count; i++) {
      const x = ph * cycles + i / count, u = fract(x), c = ((Math.floor(x) % cycles) + cycles) % cycles;
      const q = j => hash(i * 13.1 + c * 7.7 + j * 3.3 + seed * 91);
      let px, py, pz;
      if (spec.ring) {
        const a = q(0) * TAU;
        px = spec.ring[0] * Math.cos(a); pz = spec.ring[0] * Math.sin(a); py = lerp(spec.ring[1], spec.ring[2], q(1));
      } else {
        px = lerp(spec.from[0], spec.to[0], q(0)); py = lerp(spec.from[1], spec.to[1], q(1)); pz = lerp(spec.from[2], spec.to[2], q(2));
      }
      const age = u * life;
      pos.set([px + sx * age + swayAmp * Math.sin(TAU * (swayN * u + q(3))), py + sy * age, pz + sz * age], i * 3);
      const k = (spec.fade === 'out' ? Math.min(1, u * 8) * (1 - u) : Math.sin(u * Math.PI)) * gain, cc = palette[Math.floor(q(4) * palette.length)];
      col.set([cc[0], cc[1], cc[2], k], i * 4);
      size[i] = lerp(spec.size[0], spec.size[1], u);
    }
    geo.attributes.position.needsUpdate = true;
    geo.attributes.aColor.needsUpdate = true;
    geo.attributes.aSize.needsUpdate = true;
  };
  return points;
}
// All of an item's emitters on one anchor, as one Group with userData.update(t).
function fxFor(id, anchor, loop = LOOP) {
  const list = (FX[id] || []).filter(s => s.anchor === anchor);
  if (!list.length) return null;
  const g = new THREE.Group(), pts = list.map(s => fxEmitter(s, loop));
  g.add(...pts);
  g.userData.update = t => { const ph = fract(t / loop); for (const p of pts) p.userData.update(ph); };
  return g;
}

// ------------------------------------------------------------ head cosmetics
// The three head items are written against Rig, which is deliberately the same
// vocabulary as HeadModels.Mesh in the mod, so each function below ports line for
// line (the comments name the Java call each one becomes):
//
//   rig.push(x, y, z)  -> pose.pushPose(); pose.translate(x, y, z)   (returns the group;
//                         animated parts set `group.rotation.<axis>` once per frame,
//                         which is pose.mulPose(Axis.XP/YP/ZP.rotation(angle)); ONE
//                         axis per push, nest pushes for more, outermost applies first)
//   rig.pop()          -> pose.popPose()
//   rig.box(cx, cy, cz, w, h, d, hex, opts)
//                      -> m.box(...): an axis-aligned box centred on (cx, cy, cz).
//                         opts.glow   = GLOW pass: additive, unlit; `hex` is the amount of
//                                       light ADDED (so write it already dark, e.g. #1e5a48)
//                         opts.bright = SOLID pass but full-bright (LightTexture.FULL_BRIGHT);
//                                       otherwise a SOLID box is world-lit like the player
//                         opts.rx/ry/rz = turn the box in place by this many radians
//                                       (push(cx,cy,cz); mulPose; box(0,0,0); pop)
//   rig.ring(r, tube, hex, opts)
//                      -> m.ring(r, tube, ...): a flat (horizontal) torus, same opts.glow/bright
//
// Units: 1 unit = 1 skin pixel, y up, the neck at y=24, the top of the head at y=32
// (the hat layer reaches 4.5 to the sides and 32.5 up), the face towards +z.
class Rig {
  constructor(root, { lit = false } = {}) { this.cur = root; this.stack = []; this.lit = lit; }
  push(x = 0, y = 0, z = 0) {
    const g = new THREE.Group();
    g.position.set(x, y, z);
    this.cur.add(g); this.stack.push(this.cur); this.cur = g;
    return g;
  }
  pop() { this.cur = this.stack.pop(); }
  _material(hex, o) {
    if (o.glow) return new THREE.MeshBasicMaterial({ color: hex, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false });
    return (this.lit && !o.bright) ? new THREE.MeshBasicMaterial({ vertexColors: true }) : new THREE.MeshBasicMaterial({ color: hex });
  }
  box(cx, cy, cz, w, h, d, hex, o = {}) {
    const geo = (this.lit && !o.bright && !o.glow) ? shadedBox(w, h, d, hex) : new THREE.BoxGeometry(w, h, d);
    const m = new THREE.Mesh(geo, this._material(hex, o));
    m.position.set(cx, cy, cz);
    m.rotation.set(o.rx || 0, o.ry || 0, o.rz || 0);
    this.cur.add(m);
    return m;
  }
  ring(r, tube, hex, o = {}) {
    const m = new THREE.Mesh(new THREE.TorusGeometry(r, tube, 6, 48), this._material(hex, o));
    if (this.lit && !o.bright && !o.glow) m.material = new THREE.MeshBasicMaterial({ color: hex });
    m.rotation.x = Math.PI / 2;
    this.cur.add(m);
    return m;
  }
}

// ---- Orbit Halo: an atom over the head -------------------------------------
// Parts (all centred on the atom at (0, 37, 0); every angle is a whole number of
// turns per ORBIT_LOOP = 7.2 s, so it repeats seamlessly):
//   nucleus   two white cubes (0.9), one turned 45 deg about y, spinning on x (1 turn)
//             then y (2 turns), breathing +-15% four times a loop; a 2.4 glow cube
//             round it and three hair-thin glow bars (3.4 long) through it
//   3 rings   see ORBIT_RINGS: each ring = precessing (y) -> tilted (x, wobbling) torus
//             (tube 0.24 solid, tube 0.68 glow) with 12 tick cubes round it (0.38, the 4
//             cardinal ones 0.6 and brighter) and a bead lapping it: a white 0.95 cube with
//             a 2.2x glow cube, four fading trail cubes behind it, and a small mate
//             opposite
//   2 shells  hair-thin glow tori (radius 5.6, tube 0.07) tipped +-0.7 rad, each turning once
const ORBIT_RINGS = [
  // radius, tilt from flat, precession / wobble / bead turns per loop, start angle
  { r: 4.8, tilt: 0.4, prec: 1, wob: 2, bead: 4, start: 0 },
  { r: 4.1, tilt: 0.85, prec: -2, wob: 3, bead: -5, start: TAU / 3 },
  { r: 3.4, tilt: 1.2, prec: 3, wob: 1, bead: 6, start: (2 * TAU) / 3 },
];
const MINT = '#83e2b5', MINT_GLOW = '#1e5a48', MINT_DEEP = '#5fd09b';
function buildOrbitHalo(g) {
  const w = TAU / ORBIT_LOOP;
  const rig = new Rig(g);
  rig.push(0, 37, 0);                                          // the atom
  const spinX = rig.push(), spinY = rig.push();                // nucleus: x then y
  const pulse = rig.push();
  rig.box(0, 0, 0, 0.9, 0.9, 0.9, '#ffffff');
  rig.box(0, 0, 0, 0.9, 0.9, 0.9, '#ffffff', { ry: Math.PI / 4 });
  rig.box(0, 0, 0, 2.4, 2.4, 2.4, '#1e6a58', { glow: true });
  for (const [bw, bh, bd] of [[3.4, 0.1, 0.1], [0.1, 3.4, 0.1], [0.1, 0.1, 3.4]]) rig.box(0, 0, 0, bw, bh, bd, '#14463a', { glow: true });
  rig.pop(); rig.pop(); rig.pop();

  const rings = ORBIT_RINGS.map(o => {
    const prec = rig.push();                                   // the ring's plane swings round (y)
    const tilt = rig.push();                                   // ...tipped at its own angle (x)
    rig.ring(o.r, 0.24, MINT);
    rig.ring(o.r, 0.68, MINT_GLOW, { glow: true });
    for (let k = 0; k < 12; k++) {                             // ticks, fixed to the ring
      const a = k * TAU / 12, big = k % 3 === 0;
      rig.box(o.r * Math.cos(a), 0, o.r * Math.sin(a), big ? 0.6 : 0.38, big ? 0.6 : 0.38, big ? 0.6 : 0.38, big ? '#d2ffe9' : MINT_DEEP);
    }
    const spin = rig.push();                                   // ...while the bead laps it (y)
    const dir = Math.sign(o.bead);
    [0.95, 0.75, 0.6, 0.45, 0.32].forEach((s, k) => {
      const a = -dir * k * 0.2, x = o.r * Math.cos(a), z = -o.r * Math.sin(a);
      if (k === 0) {
        rig.box(x, 0, z, s, s, s, '#ffffff');
        rig.box(x, 0, z, s * 2.2, s * 2.2, s * 2.2, MINT_GLOW, { glow: true });
      } else {
        const f = Math.round(0x83 * (1 - k * 0.18));         // trail: fading mint, added
        rig.box(x, 0, z, s, s, s, '#' + [f / 3, f, f * 0.75].map(v => Math.round(v).toString(16).padStart(2, '0')).join(''), { glow: true });
      }
    });
    rig.box(-o.r, 0, 0, 0.5, 0.5, 0.5, '#d2ffe9');             // the mate, opposite the bead
    rig.box(-o.r, 0, 0, 1.2, 1.2, 1.2, '#14463a', { glow: true });
    rig.pop(); rig.pop(); rig.pop();
    return { o, prec, tilt, spin };
  });
  const shells = [0.7, -0.7].map((tipped, i) => {
    const turn = rig.push();
    const tip = rig.push();
    tip.rotation.x = tipped;
    rig.ring(5.6, 0.07, '#1e5a48', { glow: true });
    rig.pop(); rig.pop();
    return { turn, dir: i ? -1 : 1 };
  });
  rig.pop();
  return t => {
    for (const { o, prec, tilt, spin } of rings) {
      prec.rotation.y = o.start + o.prec * w * t;
      tilt.rotation.x = o.tilt + 0.12 * Math.sin(o.wob * w * t);
      spin.rotation.y = o.bead * w * t;
    }
    for (const s of shells) s.turn.rotation.y = s.dir * w * t;
    spinX.rotation.x = w * t;
    spinY.rotation.y = 2 * w * t;
    pulse.scale.setScalar(1 + 0.15 * Math.sin(4 * w * t));
  };
}

// ---- Golden Halo: a bevelled gold ring with studs ---------------------------
// Parts (centre (0, 35.2 + bob, 0); bob = 0.35 * sin(one turn per LOOP), a slow
// 0.05 rad tilt wobble on x; ONE 12th of a turn of spin per LOOP):
//   ring body     torus r 4.5, tube 0.55 gold, with a lit top bevel (torus tube 0.3 at
//                 y +0.3, light gold) and a shaded underside (tube 0.3 at y -0.3, dark gold)
//   filigree      two hair tori, r 5.4 / 3.6, tubes 0.12 / 0.1
//   studs         12 white-gold diamonds (0.55 box turned 45 deg about y) on the crown of
//                 the ring at y +0.65; a sparkle runs round them once a LOOP (the stud
//                 under it swells to 2x); every third stud is an amber gem instead
//   rays          8 glow bars (2.6 long) radiating at r 6.6, turning with the spin
//   glow          glow tori r 4.5, tubes 1.6 and 2.6
function buildGoldHalo(g) {
  const rig = new Rig(g);
  const halo = rig.push(0, 35.2, 0);
  rig.ring(4.5, 0.55, '#f2b84a');
  rig.push(0, 0.3, 0); rig.ring(4.5, 0.3, '#ffe9a0'); rig.pop();
  rig.push(0, -0.3, 0); rig.ring(4.5, 0.3, '#c98a22'); rig.pop();
  rig.ring(5.4, 0.12, '#ffd56b');
  rig.ring(3.6, 0.1, '#ffd56b');
  rig.ring(4.5, 1.6, '#3a2810', { glow: true });
  rig.ring(4.5, 2.6, '#1a1208', { glow: true });
  const spin = rig.push();
  const studs = [];
  for (let k = 0; k < 12; k++) {
    const a = k * TAU / 12, gem = k % 3 === 0;
    const stud = rig.push(4.5 * Math.cos(a), 0.65, 4.5 * Math.sin(a));
    rig.box(0, 0, 0, 0.55, 0.55, 0.55, gem ? '#ffb23e' : '#fff6d6', { ry: Math.PI / 4 });
    rig.pop();
    studs.push(stud);
  }
  for (let k = 0; k < 8; k++) {
    const a = k * TAU / 8;
    rig.push(6.6 * Math.cos(a), 0, 6.6 * Math.sin(a)).rotation.y = -a;
    rig.box(0, 0, 0, 2.6, 0.12, 0.12, '#3a2810', { glow: true });
    rig.pop();
  }
  rig.pop(); rig.pop();
  return (t, still) => {
    const ph = fract(t / LOOP);
    halo.position.y = 35.2 + (still ? 0 : 0.35 * Math.sin(TAU * ph));
    halo.rotation.x = still ? 0 : 0.05 * Math.sin(TAU * ph + 1);
    spin.rotation.y = TAU / 12 * ph;
    studs.forEach((s, k) => {
      const d = fract(ph - k / 12), swell = d < 0.12 ? Math.sin(Math.PI * d / 0.12) : 0;
      s.scale.setScalar(1 + (still ? 0 : swell));
    });
  };
}

// ---- Pixel Crown: a jewelled gold crown with an ermine band ------------------
// R = 4.55 is the band's centre line (the skin's hat layer reaches 4.5). Four sides,
// each built in its own frame (+x along the side, +z outward) and turned by side * 90
// degrees about y. Per side:
//   band         box 9.7 x 2 x 0.6 gold at y 31.7, lit top bevel 9.9 x 0.3 x 0.7 light gold
//   ermine       box 10.0 x 1.1 x 0.9 cream at y 30.55 with four dark spots (0.35 x 0.6)
//   top rim      box 10.0 x 0.4 x 0.9 deep gold at y 33, seven cream pearls (0.5) on it
//   engravings   four diamonds (0.5 turned 45 deg) in shadow gold on the band
//   centrepiece  deep-gold diamond plate (2.5) with a gem (1.5), a lighter facet and a
//                white glint, all full-bright
//   points       centre point (1.2 x 3.2 gold, a gold diamond finial, a gem on top) and two
//                side points at x +-2.9 (0.9 x 2.4) with cream pearls
// Once: four corner posts (1.2 x 2.8 gold + a cream pearl); the velvet cap in three tiers
// (9.1 / 6.2 / 3.6 wide) with four gold ribs of 3 boxes each climbing to the orb; the
// orb (1.5), cross (0.55 x 2.0 and 1.5 x 0.55) and a ruby tip. A glint sweeps the band
// once a LOOP.
function buildCrown(g) {
  const gold = '#f2c14e', light = '#ffe08a', deep = '#c9932c', shadow = '#8a5f17', pearl = '#f4efe4';
  const velvet = ['#b02440', '#a3203a', '#8a1a30'], spot = '#2a2430';
  const gems = ['#e0485b', '#5b8be0', '#5fd09b', '#5b8be0'];   // front, left, back, right
  const R = 4.55;   // half-width of the band: the 8-wide head plus a hat's margin
  const rig = new Rig(g, { lit: true });
  const crown = rig.push();
  for (let side = 0; side < 4; side++) {
    rig.push().rotation.y = side * Math.PI / 2;
    rig.box(0, 31.7, R, 2 * R + 0.6, 2, 0.6, gold);                               // band
    rig.box(0, 32.55, R + 0.05, 2 * R + 0.8, 0.3, 0.7, light);                    // bevel
    rig.box(0, 30.55, R, 2 * R + 0.9, 1.1, 0.9, pearl);                           // ermine
    for (const x of [-3.9, -1.3, 1.3, 3.9]) rig.box(x, 30.5, R + 0.47, 0.35, 0.6, 0.1, spot);
    rig.box(0, 33, R, 2 * R + 0.9, 0.4, 0.9, deep);                               // top rim
    for (let k = -3; k <= 3; k++) rig.box(k * 1.5, 33.35, R, 0.5, 0.5, 0.5, pearl);
    for (const x of [-3.7, -2.5, 2.5, 3.7]) rig.box(x, 31.7, R + 0.32, 0.5, 0.5, 0.1, shadow, { rz: Math.PI / 4 });
    rig.box(0, 31.7, R + 0.38, 2.5, 2.5, 0.3, deep, { rz: Math.PI / 4 });          // centrepiece
    rig.box(0, 31.7, R + 0.6, 1.5, 1.5, 0.4, gems[side], { rz: Math.PI / 4, bright: true });
    rig.box(-0.25, 31.95, R + 0.85, 0.55, 0.55, 0.1, tone(gems[side], 1.35), { rz: Math.PI / 4, bright: true });
    rig.box(-0.35, 32.1, R + 0.95, 0.3, 0.3, 0.08, '#ffffff', { bright: true });
    rig.box(0, 34.8, R, 1.2, 3.2, 0.6, gold);                                     // centre point
    rig.box(0, 36.9, R, 0.9, 0.9, 0.6, gold, { rz: Math.PI / 4 });
    rig.box(0, 37.8, R, 0.6, 0.6, 0.6, gems[side], { rz: Math.PI / 4, bright: true });
    for (const x of [-2.9, 2.9]) {                                                // side points
      rig.box(x, 34.4, R, 0.9, 2.4, 0.6, gold);
      rig.box(x, 36.0, R, 0.7, 0.7, 0.7, pearl);
    }
    rig.pop();
  }
  for (const [x, z] of [[R, R], [-R, R], [R, -R], [-R, -R]]) {                    // corner posts
    rig.box(x, 34, z, 1.2, 2.8, 1.2, gold);
    rig.box(x, 32.7, z, 1.4, 0.3, 1.4, light);
    rig.box(x, 35.9, z, 0.9, 0.9, 0.9, pearl);
    for (const [px, py] of [[3.3, 35.0], [2.3, 35.8], [1.3, 36.4]]) {              // rib towards the orb
      rig.box(Math.sign(x) * px, py, Math.sign(z) * px, 0.65, 0.65, 0.65, gold);
    }
  }
  rig.box(0, 33.4, 0, 2 * R - 0.6, 1.3, 2 * R - 0.6, velvet[0]);                   // velvet cap
  rig.box(0, 34.5, 0, 6.2, 1, 6.2, velvet[1]);
  rig.box(0, 35.45, 0, 3.6, 0.9, 3.6, velvet[2]);
  rig.box(0, 36.9, 0, 1.5, 1.5, 1.5, gold);                                        // orb
  rig.box(0, 38.7, 0, 0.55, 2, 0.55, gold);                                        // cross
  rig.box(0, 38.9, 0, 1.5, 0.55, 0.55, gold);
  rig.box(0, 39.9, 0, 0.6, 0.6, 0.6, '#e0485b', { rz: Math.PI / 4, bright: true });
  const sweep = rig.push();                                                        // the travelling shine
  rig.box(0, 31.7, 0, 1.4, 3.2, 1.2, '#5a4618', { glow: true });
  rig.box(0, 31.7, 0.3, 0.16, 3.4, 0.16, '#a08a4a', { glow: true });
  rig.box(0, 31.7, 0.3, 3.4, 0.16, 0.16, '#a08a4a', { glow: true });
  rig.pop();
  rig.pop();
  return (t, still) => {
    const u = fract(t / LOOP) * 4.8, side = Math.floor(u);                       // one lap, then a rest
    if (still || u >= 4) { sweep.scale.setScalar(0.0001); return; }
    const f = u - side, x = -R + 2 * R * f, a = side * Math.PI / 2;
    sweep.position.set(x * Math.cos(a) + (R + 0.5) * Math.sin(a), 0, -x * Math.sin(a) + (R + 0.5) * Math.cos(a));
    sweep.rotation.y = a;
    sweep.scale.setScalar(Math.max(0.0001, Math.sin(Math.PI * u / 4)));
  };
}

// --------------------------------------------------------------- the cape
// The cloth is a box cut into CAPE_SEGS strips down its length, and every frame the
// strips are re-hung along a curve (a swinging, rippling chain), so the cape drapes
// and flows instead of swinging stiffly. The curve also lights the cloth: strips
// that bend away from the light go a little darker, those that catch it, brighter.
const CAPE_SEGS = 12;
const CAPE_PAINT_STEPS = 60;           // an animated cape's art is repainted this many times a loop
function buildCape(id, g) {
  const a = ART[id], scale = capeScale(id), key = `${id}@${scale}`, N = CAPE_SEGS;
  const outerTex = clothTexture(key, capeCanvas(id, STILL_T / LOOP));
  // The inside face: the same art, darkened. Cached like the texture it feeds.
  const innerKey = `${key}:inner`;
  if (!canvases.has(innerKey)) canvases.set(innerKey, makeCanvas(10 * scale, 16 * scale));
  const innerCanvas = canvases.get(innerKey);
  const innerTex = clothTexture(innerKey, innerCanvas);
  const paintInner = () => {
    const ctx = innerCanvas.getContext('2d');
    ctx.clearRect(0, 0, innerCanvas.width, innerCanvas.height);
    ctx.drawImage(canvases.get(key), 0, 0);
    ctx.fillStyle = 'rgba(6,6,10,.52)';
    ctx.fillRect(0, 0, innerCanvas.width, innerCanvas.height);
    innerTex.needsUpdate = true;
  };
  paintInner();

  const geo = new THREE.BoxGeometry(10, 16, 1, 1, N, 1);
  const rest = Float32Array.from(geo.attributes.position.array), normals = geo.attributes.normal.array;
  const count = rest.length / 3, level = new Uint8Array(count), faceShade = new Float32Array(count);
  const colour = new THREE.BufferAttribute(new Float32Array(count * 3), 3);
  geo.setAttribute('color', colour);
  for (let v = 0; v < count; v++) {
    level[v] = Math.round((8 - rest[3 * v + 1]) / 16 * N);
    const nx = normals[3 * v], ny = normals[3 * v + 1];
    faceShade[v] = Math.abs(nx) > 0.5 ? 0.72 : ny > 0.5 ? 1 : ny < -0.5 ? 0.5 : 0.95;
  }
  const outer = new THREE.MeshBasicMaterial({ map: outerTex, vertexColors: true });
  const inner = new THREE.MeshBasicMaterial({ map: innerTex, vertexColors: true });
  const edge = new THREE.MeshBasicMaterial({ color: a.edge, vertexColors: true });
  // faces +x, -x, +y, -y, +z (against the back), -z (the outside)
  const cape = new THREE.Mesh(geo, [edge, edge, edge, edge, inner, outer]);
  cape.frustumCulled = false;
  const pivot = new THREE.Group();
  pivot.position.set(0, 24, -2);
  pivot.add(cape);
  g.add(pivot);

  const lvY = new Float32Array(N + 1), lvZ = new Float32Array(N + 1), nY = new Float32Array(N + 1), nZ = new Float32Array(N + 1);
  const th = new Float32Array(N + 1), lvShade = new Float32Array(N + 1);
  const pos = geo.attributes.position;
  const hang = (ph, still) => {
    const P = ph * TAU;
    let y = 0, z = 0;
    for (let k = 0; k <= N; k++) {                  // the chain: each strip leans by th[k] from vertical
      const s = k / N;
      th[k] = 0.1 + 0.2 * s + (still ? 0 : s * 0.075 * Math.sin(P - 1.7 * s) + s * s * 0.06 * Math.sin(2 * P - 3.4 * s + 1));
      lvY[k] = y; lvZ[k] = z; nY[k] = Math.sin(th[k]); nZ[k] = -Math.cos(th[k]);
      y -= (16 / N) * Math.cos(th[k]); z -= (16 / N) * Math.sin(th[k]);
    }
    for (let k = 0; k <= N; k++) lvShade[k] = clamp(1 + 2.6 * (th[Math.min(N, k + 1)] - th[Math.max(0, k - 1)]), 0.82, 1.18);
    for (let v = 0; v < count; v++) {
      const k = level[v], t = 0.54 - rest[3 * v + 2];     // 0 on the inside face, 1 on the outside
      pos.setXYZ(v, rest[3 * v], lvY[k] + t * nY[k], lvZ[k] + t * nZ[k]);
      const c = faceShade[v] * lvShade[k];
      colour.setXYZ(v, c, c, c);
    }
    pos.needsUpdate = true;
    colour.needsUpdate = true;
  };

  // Particles lift off the outside of the cape and drift away behind (see FX).
  const fx = fxFor(id, 'cape');
  if (fx) pivot.add(fx);

  let painted = null;
  return (t, still) => {
    const ph = fract(t / LOOP);
    hang(ph, still);
    if (a.animated) {
      const q = Math.floor(ph * CAPE_PAINT_STEPS + 1e-6) / CAPE_PAINT_STEPS;
      if (painted !== q) {
        capeCanvas(id, q);
        outerTex.needsUpdate = true;
        paintInner();
        painted = q;
      }
    }
    fx?.userData.update(t);
  };
}

// --------------------------------------------------------------- the wings
// One wing (built for the player's left, +x; the right is a mirror image):
//   shoulder -> inner bone + inner feathers -> elbow -> outer bone + primaries.
// Local axes: +x out along the bone, +y up, -z behind the player.
function buildWing(id, a) {
  const p = a.palette;
  const feather = (panel, x) => {
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(panel.w, panel.h), new THREE.MeshBasicMaterial({
      map: pixelTexture(`${id}:${panel === WING_INNER ? 'inner' : 'outer'}`, wingCanvas(id, panel)),
      alphaTest: 0.5, side: THREE.DoubleSide,
    }));
    mesh.position.set(x, -panel.h / 2 + 0.4, 0);   // hangs from the bone
    return mesh;
  };
  const shoulder = new THREE.Group();
  shoulder.add(block(WING_INNER.w, 1.3, 1.3, p.bone, WING_INNER.w / 2, 0, 0));
  shoulder.add(feather(WING_INNER, WING_INNER.w / 2));
  const elbow = new THREE.Group();
  elbow.position.x = WING_INNER.w;
  elbow.add(block(1.8, 1.8, 1.8, p.bone, 0, 0, 0));
  elbow.add(block(WING_OUTER.w - 1, 1.1, 1.1, p.bone, (WING_OUTER.w - 1) / 2, 0, 0));
  elbow.add(feather(WING_OUTER, WING_OUTER.w / 2));
  shoulder.add(elbow);

  const fx = fxFor(id, 'wing');
  if (fx) elbow.add(fx);

  // Two wingbeats per loop. Upstroke: swept back, raised, folded at the
  // elbow. Downstroke: spread wide, the primaries flung out and down.
  const pose = (t, still) => {
    const ph = fract(t / LOOP);
    const beat = still ? -0.35 : Math.sin(ph * TAU * 2);
    const lag = still ? -0.35 : Math.sin(ph * TAU * 2 - 0.7);   // the elbow follows through
    shoulder.rotation.set(0, 0.62 + 0.38 * beat, 0.42 + 0.3 * beat);
    elbow.rotation.set(0, 0.2 + 0.45 * Math.max(0, lag), -0.75 - 0.3 * lag);
    fx?.userData.update(t);
  };
  return { shoulder, pose };
}
function buildWings(id, g) {
  const a = ART[id];
  const wings = [1, -1].map(side => {
    const mount = new THREE.Group();
    mount.position.set(side * 1, 21.5, -2.4);
    mount.scale.x = side;                       // the right wing mirrors the left
    const wing = buildWing(id, a);
    mount.add(wing.shoulder);
    g.add(mount);
    return wing;
  });
  return (t, still) => { for (const w of wings) w.pose(t, still); };
}

// Returns a Group to hang on the player, with userData.update(t, still):
// t = seconds (drives animation), still = no physical motion (sway, flap).
function makeCosmetic(id) {
  const a = ART[id];
  const g = new THREE.Group();
  let update = a.kind === 'cape' ? buildCape(id, g) : a.kind === 'wings' ? buildWings(id, g) : a.build(g);
  if (a.kind === 'head') {
    const fx = fxFor(id, 'head', loopOf(id));
    if (fx) {
      g.add(fx);
      const model = update;
      update = (t, still) => { model(t, still); fx.userData.update(t); };
    }
  }
  g.userData.update = update;
  return g;
}

function disposeTree(obj) {
  obj.traverse(o => {
    if (!o.isMesh && !o.isPoints) return;
    o.geometry.dispose();
    // Materials are per-cosmetic; cached textures are shared and kept.
    for (const m of [].concat(o.material)) m.dispose();
  });
}

// ------------------------------------------------------------ player model
// [u, v] of each part's base layer and overlay layer in a 64x64 skin.
const PARTS = [
  { name: 'head', size: [8, 8, 8], at: [0, 28, 0], pivot: null, uv: [0, 0], over: [32, 0], grow: 1 },
  { name: 'body', size: [8, 12, 4], at: [0, 18, 0], pivot: null, uv: [16, 16], over: [16, 32], grow: 0.5 },
  { name: 'armR', size: [4, 12, 4], at: [-6, 22, 0], pivot: -4, uv: [40, 16], over: [40, 32], grow: 0.5 },
  { name: 'armL', size: [4, 12, 4], at: [6, 22, 0], pivot: -4, uv: [32, 48], over: [48, 48], grow: 0.5, legacy: [40, 16] },
  { name: 'legR', size: [4, 12, 4], at: [-2, 12, 0], pivot: -6, uv: [0, 16], over: [0, 32], grow: 0.5 },
  { name: 'legL', size: [4, 12, 4], at: [2, 12, 0], pivot: -6, uv: [16, 48], over: [0, 48], grow: 0.5, legacy: [0, 16] },
];

// Slim (Alex-style) skins: arms 3 pixels wide, sitting tight to the body.
const SLIM_PARTS = PARTS.map(p => (p.name.startsWith('arm') ? { ...p, size: [3, 12, 4], at: [Math.sign(p.at[0]) * 5.5, 22, 0] } : p));

// The six face rectangles of a box in Minecraft's skin layout, in BoxGeometry
// face order (+x = the player's left side, +z = the front).
function skinFaces(u, v, w, h, d) {
  return [
    [u + d + w, v + d, d, h], [u, v + d, d, h],
    [u + d, v, w, d], [u + d + w, v, w, d],
    [u + d, v + d, w, h], [u + 2 * d + w, v + d, w, h],
  ];
}
function skinUV(geo, [u, v], [w, h, d], texW, texH) {
  const uv = geo.attributes.uv;
  skinFaces(u, v, w, h, d).forEach(([x, y, fw, fh], f) => {
    // BoxGeometry vertex order per face: top-left, top-right, bottom-left, bottom-right
    const L = x / texW, R = (x + fw) / texW, T = 1 - y / texH, B = 1 - (y + fh) / texH;
    uv.setXY(4 * f, L, T); uv.setXY(4 * f + 1, R, T); uv.setXY(4 * f + 2, L, B); uv.setXY(4 * f + 3, R, B);
  });
  uv.needsUpdate = true;
}

// A neutral stand-in until a real skin loads: clay mannequin in an Origin hoodie.
function mannequinSkin() {
  if (textures.has('mannequin')) return textures.get('mannequin');
  const c = makeCanvas(64, 64), ctx = c.getContext('2d'), r = rng(42);
  const paint = (uv, size, colorAt) => {
    for (const [x0, y0, w, h] of skinFaces(...uv, ...size)) {
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        ctx.fillStyle = css(rgb(colorAt(y / h)), 0.94 + r() * 0.1);
        ctx.fillRect(x0 + x, y0 + y, 1, 1);
      }
    }
  };
  const clay = '#c9c3b2', hood = '#232b25', pants = '#171c19', shoe = '#d9d3c1';
  paint([0, 0], [8, 8, 8], () => clay);
  paint([16, 16], [8, 12, 4], () => hood);
  for (const uv of [[40, 16], [32, 48]]) paint(uv, [4, 12, 4], t => (t > 0.75 ? clay : hood));
  for (const uv of [[0, 16], [16, 48]]) paint(uv, [4, 12, 4], t => (t > 0.84 ? shoe : pants));
  // face: two dark eyes; hoodie front: mint drawstrings
  ctx.fillStyle = '#2b302c'; ctx.fillRect(9, 12, 2, 1); ctx.fillRect(13, 12, 2, 1);
  ctx.fillStyle = '#5fd09b'; ctx.fillRect(22, 20, 1, 4); ctx.fillRect(25, 20, 1, 4);
  return pixelTexture('mannequin', c);
}

function buildPlayer() {
  const root = new THREE.Group();
  const body = new THREE.Group();     // skin meshes, rebuilt when the skin changes
  const slot = new THREE.Group();     // where the worn cosmetic hangs
  root.add(body, slot);
  const limbs = {};

  function setSkin(tex, slim = false) {
    disposeTree(body);
    body.clear();
    const img = tex.image, W = img.width || 64, H = img.height || 64;
    const legacy = H === W / 2;       // pre-1.8 64x32 skins: no overlays, mirrored limbs
    const s = W / 64;                 // HD skins scale the whole layout
    for (const p of slim && !legacy ? SLIM_PARTS : PARTS) {
      const uv = legacy && p.legacy ? p.legacy : p.uv;
      const holder = new THREE.Group();
      holder.position.set(...p.at);
      const y = p.pivot ?? 0;
      const base = new THREE.Mesh(shadedBox(...p.size), new THREE.MeshBasicMaterial({ map: tex, vertexColors: true }));
      skinUV(base.geometry, uv.map(n => n * s), p.size.map(n => n * s), W, H);
      base.position.y = y;
      holder.add(base);
      if (!legacy || p.name === 'head') {
        const grown = p.size.map(n => n + p.grow);
        const over = new THREE.Mesh(shadedBox(...grown), new THREE.MeshBasicMaterial({ map: tex, vertexColors: true, alphaTest: 0.5 }));
        skinUV(over.geometry, p.over.map(n => n * s), p.size.map(n => n * s), W, H);
        over.position.y = y;
        holder.add(over);
      }
      limbs[p.name] = holder;
      body.add(holder);
    }
  }
  setSkin(mannequinSkin());

  let worn = null, wornId = null;
  function wear(id) {
    if (id === wornId) return;
    if (worn) { slot.remove(worn); disposeTree(worn); }
    worn = id && ART[id] ? makeCosmetic(id) : null;
    wornId = worn ? id : null;
    if (worn) slot.add(worn);
  }
  function pose(t, still) {
    const swing = still ? 0 : 0.06 * Math.sin(fract(t / LOOP) * TAU);
    limbs.armR.rotation.set(swing, 0, -0.06);   // arms hang slightly out from the body
    limbs.armL.rotation.set(-swing, 0, 0.06);
    worn?.userData.update(t, still);
  }
  return { root, setSkin, wear, pose };
}

// The angle (player yaw) and framing that best shows each kind of item.
// Yaw 0 = facing the camera; PI = back to the camera.
const VIEWS = {
  none: { yaw: 0.5, look: 18, dist: 80, lift: 6 },
  cape: { yaw: Math.PI - 0.6, look: 18, dist: 80, lift: 6 },
  wings: { yaw: Math.PI - 0.5, look: 20, dist: 92, lift: 10 },
  head: { yaw: 0.5, look: 24, dist: 80, lift: 8 },
};
const THUMBS = {
  cape: { yaw: Math.PI - 0.55, look: 18, dist: 56, lift: 4 },
  wings: { yaw: Math.PI - 0.45, look: 21, dist: 82, lift: 8 },
  head: { yaw: 0.55, look: 33, dist: 44, lift: 14 },
};

function aim(camera, v) {
  camera.position.set(0, v.look + v.lift, v.dist);
  camera.lookAt(0, v.look, 0);
}

function floorShadow() {
  const c = makeCanvas(64, 64), ctx = c.getContext('2d');
  const g = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
  g.addColorStop(0, 'rgba(0,0,0,.55)'); g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g; ctx.fillRect(0, 0, 64, 64);
  const m = new THREE.Mesh(new THREE.PlaneGeometry(22, 14), new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthWrite: false }));
  m.rotation.x = -Math.PI / 2;
  m.position.y = 0.01;
  return m;
}

function stage(canvas) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(30, 1, 1, 500);
  const player = buildPlayer();
  scene.add(player.root, floorShadow());
  return { renderer, scene, camera, player };
}

// ------------------------------------------------------------ fitting room
// Throws if WebGL is unavailable; the caller falls back to flat art.
export function createFittingRoom(canvas, { reducedMotion = false } = {}) {
  const { renderer, scene, camera, player } = stage(canvas);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

  let view = VIEWS.none, yaw = view.yaw, holdUntil = 0, drag = null, visible = true, raf = 0, last = performance.now();
  const cam = { ...view };

  function frame(now) {
    raf = 0;
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    if (!drag) {
      if (now < holdUntil || reducedMotion) {
        const diff = ((view.yaw - yaw + Math.PI * 3) % TAU) - Math.PI;
        yaw += reducedMotion ? diff : diff * Math.min(1, dt * 5);
      } else {
        yaw += dt * 0.35;                       // slow showroom turn
      }
    }
    for (const k of ['look', 'dist', 'lift']) cam[k] += (view[k] - cam[k]) * (reducedMotion ? 1 : Math.min(1, dt * 4));
    aim(camera, cam);
    player.root.rotation.y = yaw;
    player.pose(reducedMotion ? STILL_T : now / 1000, reducedMotion);
    renderer.render(scene, camera);
    if (visible && !document.hidden) raf = requestAnimationFrame(frame);
  }
  const wake = () => { if (!raf && visible && !document.hidden) { last = performance.now(); raf = requestAnimationFrame(frame); } };

  const resize = () => {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    // Narrow stages pull the camera back so wings never clip the edges.
    camera.fov = w / h < 0.85 ? 36 : 30;
    camera.updateProjectionMatrix();
    wake();
  };
  new ResizeObserver(resize).observe(canvas);
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; wake(); }).observe(canvas);
  document.addEventListener('visibilitychange', wake);

  // Drag (mouse or finger) turns the player.
  canvas.addEventListener('pointerdown', e => { drag = { x: e.clientX, yaw }; canvas.setPointerCapture(e.pointerId); });
  canvas.addEventListener('pointermove', e => { if (drag) yaw = drag.yaw + (e.clientX - drag.x) * 0.012; });
  const release = () => { if (drag) { drag = null; holdUntil = performance.now() + 2500; view = { ...view, yaw }; } };
  canvas.addEventListener('pointerup', release);
  canvas.addEventListener('pointercancel', release);

  resize();
  wake();
  return {
    wear(id) {
      player.wear(id);
      view = VIEWS[kindOf(id) || 'none'];
      holdUntil = performance.now() + 4500;
      wake();
    },
    // skin: { tex, slim } from loadSkin, or null for the mannequin
    setSkin(skin) { player.setSkin(skin?.tex || mannequinSkin(), skin?.slim); wake(); },
  };
}

// Loads a player's real skin. player: { uuid, skinUrl?, slim? } from the name
// lookup. Resolves { tex, slim }, or null (the caller keeps the mannequin).
// WebGL can only use images served with CORS headers, so this tries, in order:
// the skin file on Mojang's own texture server, then two skin mirrors.
// (Crafatar was dropped 2026-10-12: when it can't reach Mojang it answers
// with a default Steve instead of an error, so players saw Steve.)
export async function loadSkin(player) {
  const loader = new THREE.TextureLoader().setCrossOrigin('anonymous');
  const urls = [
    player.skinUrl && player.skinUrl.replace(/^http:/, 'https:'),
    `https://crafthead.net/skin/${player.uuid}`,
    `https://mc-heads.net/skin/${player.uuid}`,
  ].filter(Boolean);
  for (const url of urls) {
    try {
      const tex = await Promise.race([
        loader.loadAsync(url),
        new Promise((_, no) => setTimeout(() => no(new Error('timed out')), 7000)),
      ]);
      tex.magFilter = THREE.NearestFilter;
      tex.minFilter = THREE.NearestFilter;
      tex.generateMipmaps = false;
      return { tex, slim: player.slim ?? looksSlim(tex.image) };
    } catch { /* try the next source */ }
  }
  return null;
}
// When the profile didn't say: slim arms are 3px wide, which leaves the last
// two columns of the right arm's back face (x 54-55, row 20) empty.
function looksSlim(img) {
  if (!img.width || img.height !== img.width) return false;
  const c = makeCanvas(img.width, img.height), ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(img, 0, 0);
  const s = img.width / 64;
  return ctx.getImageData(Math.floor(54 * s), Math.floor(20 * s), 1, 1).data[3] === 0;
}

// Renders shop-card pictures of each item on the mannequin: a still for every
// item, and a seamless loop of frames for animated ones. Throws if WebGL is
// unavailable. Call dispose() when done to free the GPU context.
export function createThumbnailer(size = 320) {
  const canvas = makeCanvas(size, size);
  const { renderer, scene, camera, player } = stage(canvas);
  renderer.setSize(size, size, false);
  const shot = (id, t, still, type) => {
    const v = THUMBS[kindOf(id)];
    player.wear(id);
    player.root.rotation.y = v.yaw;
    player.pose(t, still);
    aim(camera, v);
    renderer.render(scene, camera);
    return canvas.toDataURL(type, 0.86);
  };
  return {
    still: id => shot(id, STILL_T, true, 'image/png'),
    frame: (id, i, n) => shot(id, (i / n) * loopOf(id), false, 'image/webp'),
    dispose() { player.wear(null); renderer.dispose(); renderer.forceContextLoss(); },
  };
}

