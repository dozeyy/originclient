// Origin store cosmetics: how every item LOOKS, plus the 3D fitting room.
//
// store.js owns WHAT is for sale (names, prices, Tebex package ids); this file
// owns the art, keyed by the same item id. Every texture is painted in code at
// Minecraft-like resolution (capes at 4x a vanilla cape, 40x64), so the
// placeholder art needs no image files and a new design is one entry in ART.
// Real artwork later = swap a painter for an image, nothing else changes.
//
// Animation: everything that moves is a function of time that repeats exactly
// every LOOP seconds (only whole-number cycles per loop). That's what lets the
// shop cards play a short pre-rendered loop with no visible seam.
//
// The player model follows Minecraft's own: 1 unit = 1 skin pixel, a 64x64
// skin with the standard UV layout, classic (4px) arms.
import * as THREE from './vendor/three.module.min.js';

// Exact pixel colours, like world.js: no colour management, linear output.
THREE.ColorManagement.enabled = false;

export const LOOP = 2.4;              // seconds; every animation repeats on this
const STILL_T = 0.15 * LOOP;          // the frame used when nothing may move
const TAU = Math.PI * 2;

// ------------------------------------------------------------ colour helpers
const rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const mix = (a, b, t) => { const A = rgb(a), B = rgb(b); return A.map((v, i) => v + (B[i] - v) * t); };
const css = (c, k = 1, a = 1) => `rgba(${c.map(v => Math.max(0, Math.min(255, Math.round(v * k)))).join(',')},${a})`;
const fract = x => x - Math.floor(x);
const hash = n => fract(Math.sin(n * 12.9898 + 4.1414) * 43758.5453);
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

// ------------------------------------------------------------- cape painters
// Painters take (ctx, r, ph): r = seeded random, ph = loop phase 0..1 (static
// capes ignore it). They paint in 2x2 blocks so they read as Minecraft pixel
// art; only the Origin mark is drawn at full resolution (it needs the detail).
const CAPE_W = 40, CAPE_H = 64;

function cells(ctx, r, fn) {
  for (let y = 0; y < CAPE_H; y += 2) {
    for (let x = 0; x < CAPE_W; x += 2) {
      const c = fn(x / (CAPE_W - 2), y / (CAPE_H - 2), x, y);
      if (!c) continue;
      ctx.fillStyle = css(c, 0.93 + r() * 0.12);
      ctx.fillRect(x, y, 2, 2);
    }
  }
}
function border(ctx, hex) {
  ctx.fillStyle = hex;
  ctx.fillRect(0, 0, CAPE_W, 2); ctx.fillRect(0, CAPE_H - 2, CAPE_W, 2);
  ctx.fillRect(0, 0, 2, CAPE_H); ctx.fillRect(CAPE_W - 2, 0, 2, CAPE_H);
}
// The Origin mark (website/index.html #mark): three rings, 0/60/120 degrees.
const MARK = [[90, 32, 0], [75, 27, 60], [60, 22, 120]];
function originMark(ctx, cx, cy, size, hex, glowHex, glow = 0.3) {
  const s = size / 200;
  for (const [col, width, alpha] of [[glowHex, 3.4, glow], [hex, 1.4, 1]]) {
    ctx.strokeStyle = col; ctx.lineWidth = width; ctx.globalAlpha = alpha;
    for (const [rx, ry, deg] of MARK) {
      ctx.beginPath();
      ctx.ellipse(cx, cy, rx * s, ry * s, deg * Math.PI / 180, 0, TAU);
      ctx.stroke();
    }
  }
  ctx.globalAlpha = 1;
}
const deepslate = (r, v) => (r() < 0.07 ? ramp([[0, '#151a17'], [1, '#090c0a']], v) : ramp([[0, '#232b25'], [1, '#0e1110']], v));
const FIRE = [[0, '#7a1608'], [0.35, '#e0451b'], [0.7, '#ffb23e'], [1, '#fff1a8']];

const CAPES = {
  // ----- still -----
  origin(ctx, r) {
    cells(ctx, r, (u, v) => deepslate(r, v));
    originMark(ctx, 20, 27, 38, '#83e2b5', '#4bc8ae');
    border(ctx, '#2e3b33');
  },
  golden(ctx, r) {
    // The website's golden-hour sky, sun and all.
    const sky = [[0, '#1b2340'], [0.34, '#4a3a63'], [0.62, '#c9795a'], [0.76, '#f2b878'], [1, '#3a2a24']];
    cells(ctx, r, (u, v, x, y) => {
      const d = Math.hypot(x + 1 - 20, y + 1 - 42);
      if (d < 7) return rgb('#ffe3b0');
      if (d < 9) return mix('#ffd08a', '#f2b878', 0.5);
      if (y > 52 && Math.abs(x + 1 - 20) < 16 - (y - 52) * 1.2) return rgb('#2a1f22');
      return ramp(sky, v);
    });
    border(ctx, '#2a1f2e');
  },
  frost(ctx, r) {
    const flakes = Array.from({ length: 9 }, () => [2 + Math.floor(r() * 17) * 2, 4 + Math.floor(r() * 28) * 2]);
    cells(ctx, r, (u, v, x, y) => {
      for (const [fx, fy] of flakes) {
        const dx = Math.abs(x - fx), dy = Math.abs(y - fy);
        if ((dx === 0 && dy <= 2) || (dy === 0 && dx <= 2)) return rgb('#ffffff');
      }
      if ((x + y) % 18 === 0 && v > 0.3) return rgb('#d7eefc');
      return ramp([[0, '#e9f6ff'], [0.5, '#9fd3f2'], [1, '#3e7fb8']], v);
    });
    border(ctx, '#d7eefc');
  },
  void(ctx, r) {
    cells(ctx, r, (u, v, x, y) => {
      const dx = Math.abs(x + 1 - 20), dy = Math.abs(y + 1 - 28);
      if (dx + dy < 4) return rgb('#0b0612');
      if (dx + dy < 8) return rgb('#2fd6a0');
      if (dx + dy < 10) return rgb('#1b6e58');
      if (r() < 0.06) return rgb(r() < 0.5 ? '#c86bff' : '#7a2cff');
      return ramp([[0, '#0b0612'], [1, '#1d0b2e']], v);
    });
    border(ctx, '#3a1a55');
  },

  // ----- animated -----
  orbit(ctx, r, ph) {
    // The Origin mark with a spark racing round each ring and a breathing glow.
    const pulse = 0.5 + 0.5 * Math.sin(ph * TAU * 2);
    cells(ctx, r, (u, v) => deepslate(r, v));
    originMark(ctx, 20, 27, 38, '#83e2b5', '#4bc8ae', 0.18 + 0.3 * pulse);
    const s = 38 / 200;
    MARK.forEach(([rx, ry, deg], i) => {
      const dir = i % 2 ? -1 : 1, a = deg * Math.PI / 180;
      for (let k = 6; k >= 0; k--) {             // fading trail, then the head
        const th = TAU * (dir * ph + i / 3) - dir * k * 0.13;
        const ex = rx * s * Math.cos(th), ey = ry * s * Math.sin(th);
        const x = 20 + ex * Math.cos(a) - ey * Math.sin(a), y = 27 + ex * Math.sin(a) + ey * Math.cos(a);
        const size = k === 0 ? 3 : 2;
        ctx.fillStyle = k === 0 ? '#ffffff' : `rgba(131,226,181,${0.85 - k * 0.12})`;
        ctx.fillRect(Math.round(x - size / 2), Math.round(y - size / 2), size, size);
      }
    });
    border(ctx, css(mix('#2e3b33', '#5fd09b', pulse * 0.6)));
  },
  glitch(ctx, r, ph) {
    // The mark split into red/green/blue, rolling scanlines, two glitch bursts a loop.
    ctx.fillStyle = '#07080a';
    ctx.fillRect(0, 0, CAPE_W, CAPE_H);
    const roll = Math.floor(ph * 32);
    for (let y = 0; y < CAPE_H; y += 2) {
      if ((y / 2 + roll) % 4 === 0) { ctx.fillStyle = 'rgba(90,255,200,.07)'; ctx.fillRect(0, y, CAPE_W, 2); }
    }
    const off = Math.round(1.6 * Math.sin(ph * TAU * 3));
    ctx.globalCompositeOperation = 'lighter';
    originMark(ctx, 20 - off, 27, 38, '#ff2e55', '#ff2e55', 0.12);
    originMark(ctx, 20, 27, 38, '#2effa8', '#2effa8', 0.12);
    originMark(ctx, 20 + off, 27 + Math.sign(off), 38, '#2e7bff', '#2e7bff', 0.12);
    ctx.globalCompositeOperation = 'source-over';
    const burst = ph % 0.5 < 0.08;
    if (burst) {
      const seed = Math.floor(ph * 40);
      for (let k = 0; k < 5; k++) {
        const y = Math.floor(hash(seed + k) * 30) * 2, h = 2 + Math.floor(hash(seed + k + 9) * 3) * 2;
        const band = ctx.getImageData(0, y, CAPE_W, h);
        ctx.putImageData(band, Math.round((hash(seed + k + 5) - 0.5) * 14), y);
      }
      ctx.fillStyle = hash(seed) < 0.5 ? '#ff2e55' : '#2effa8';
      ctx.fillRect(Math.floor(hash(seed + 3) * 16) * 2, Math.floor(hash(seed + 4) * 30) * 2, 6, 2);
    }
    border(ctx, burst ? '#ff2e55' : '#1d2a26');
  },
  inferno(ctx, r, ph) {
    // Flickering flames and embers rising off them.
    const P = ph * TAU;
    cells(ctx, r, (u, v, x) => {
      const c = x / 2;
      const h = 0.42 + 0.22 * hash(c + 1) + 0.09 * Math.sin(P * 3 + c * 1.3) + 0.05 * Math.sin(P * 2 - c * 0.7);
      const lick = 0.07 * hash(c * 7 + Math.floor(fract(ph * 3 + hash(c)) * 8));
      const start = 1 - h - lick;
      if (v > start) return ramp(FIRE, (v - start) / (h + lick));
      return ramp([[0, '#120606'], [1, '#3a0d08']], v);
    });
    for (let k = 0; k < 14; k++) {
      const life = fract(ph * (1 + (k % 3)) + hash(k));
      const x = Math.max(2, Math.min(36, Math.round(hash(k + 20) * 17 + Math.sin(life * TAU + k) * 1.5) * 2));
      ctx.fillStyle = `rgba(255,${150 + Math.round(90 * (1 - life))},60,${1 - life})`;
      ctx.fillRect(x, Math.floor((1 - life) * 30) * 2, 2, 2);
    }
    border(ctx, '#2a0a06');
  },
  aurora(ctx, r, ph) {
    // Northern lights that wave, under twinkling stars.
    const P = ph * TAU;
    cells(ctx, r, (u, v, x, y) => {
      const n = hash(x * 31 + y * 7);
      if (v < 0.35 && n < 0.04) return mix('#3a5a70', '#ffffff', 0.5 + 0.5 * Math.sin(P * 2 + n * 900));
      const base = ramp([[0, '#06121a'], [1, '#0b1f2a']], v);
      let glow = 0;
      for (const [cx, amp, f, w, k] of [[12, 6, 7, 4, 1], [26, 5, 5, 3.5, -1], [34, 4, 9, 2.5, 2]]) {
        const centre = cx + amp * Math.sin(v * f + cx + P * k);
        glow += Math.exp(-(((x + 1 - centre) / w) ** 2)) * (0.35 + 0.65 * Math.sin(v * Math.PI)) * (0.75 + 0.25 * Math.sin(P + cx));
      }
      const band = ramp([[0, '#3ee8b0'], [0.6, '#5fd09b'], [1, '#9a6bff']], v);
      return base.map((c, i) => c + (band[i] - c) * Math.min(1, glow));
    });
    border(ctx, '#123040');
  },
  starfall(ctx, r, ph) {
    // A night sky with a crescent moon and shooting stars.
    const P = ph * TAU;
    cells(ctx, r, (u, v, x, y) => {
      const m = Math.hypot(x + 1 - 29, y + 1 - 11), cut = Math.hypot(x + 1 - 31.5, y + 1 - 9.5);
      if (m < 5 && cut >= 4.5) return rgb('#f4efd8');
      if (y > 54 - 3 * Math.sin(x * 0.25)) return rgb('#0b0a16');
      const n = hash(x * 13 + y * 7);
      if (n > 0.965) return mix('#3a4270', '#ffffff', 0.45 + 0.55 * (0.5 + 0.5 * Math.sin(P * 2 + n * 90)));
      return ramp([[0, '#070a1c'], [0.6, '#1b1f4a'], [1, '#3a2a5e']], v);
    });
    for (let k = 0; k < 3; k++) {
      const s = fract(ph + k / 3) * 3;          // each star crosses in a third of the loop
      if (s >= 1) continue;
      const hx = 38 - k * 5 - s * 30, hy = 2 + k * 13 + s * 22;
      for (let j = 7; j >= 0; j--) {
        ctx.fillStyle = j === 0 ? '#ffffff' : `rgba(200,215,255,${(1 - j / 8) * (1 - s * 0.5)})`;
        ctx.fillRect(Math.round(hx + j * 1.6), Math.round(hy - j * 1.2), 2, 2);
      }
    }
    border(ctx, '#1b1f4a');
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
// plays a loop). sparks: particle colours given off while worn.
const ART = {
  'cape-orbit': { kind: 'cape', paint: CAPES.orbit, edge: '#2e3b33', animated: true, sparks: ['#83e2b5', '#4bc8ae', '#ffffff'] },
  'cape-glitch': { kind: 'cape', paint: CAPES.glitch, edge: '#1d2a26', animated: true, sparks: ['#ff2e55', '#2effa8', '#2e7bff'] },
  'cape-inferno': { kind: 'cape', paint: CAPES.inferno, edge: '#2a0a06', animated: true, sparks: ['#ffb23e', '#ff6a2b', '#fff1a8'], rise: true },
  'cape-aurora': { kind: 'cape', paint: CAPES.aurora, edge: '#123040', animated: true, sparks: ['#3ee8b0', '#9a6bff'] },
  'cape-starfall': { kind: 'cape', paint: CAPES.starfall, edge: '#1b1f4a', animated: true, sparks: ['#ffffff', '#bcd2ff'] },
  'cape-origin': { kind: 'cape', paint: CAPES.origin, edge: '#2e3b33' },
  'cape-golden': { kind: 'cape', paint: CAPES.golden, edge: '#2a1f2e' },
  'cape-frost': { kind: 'cape', paint: CAPES.frost, edge: '#b9dcf2' },
  'cape-void': { kind: 'cape', paint: CAPES.void, edge: '#3a1a55' },
  // Wings are built but NOT on sale (Will, 2026-10-12: "no wings for now").
  // To sell them again, add their ids back to ITEMS in store.js and the Wings
  // tab in store.html.
  'wings-ember': { kind: 'wings', animated: true, palette: { lead: '#fff1a8', mid: '#ff8a2b', tip: '#7a1608', bone: '#ffcf6b' }, sparks: ['#ffb23e', '#ff6a2b'], rise: true },
  'wings-void': { kind: 'wings', animated: true, palette: { lead: '#e6c4ff', mid: '#7a3cd0', tip: '#160826', bone: '#b07aff' }, sparks: ['#c86bff', '#7a2cff'] },
  'wings-frost': { kind: 'wings', animated: true, palette: { lead: '#ffffff', mid: '#cdeeff', tip: '#4f93c9', bone: '#ffffff' }, sparks: ['#ffffff', '#cdeeff'] },
  'halo-orbit': { kind: 'head', build: buildOrbitHalo },
  'halo-gold': { kind: 'head', build: buildGoldHalo },
  'crown-pixel': { kind: 'head', build: buildCrown },
};
export const kindOf = id => ART[id]?.kind;
export const isAnimated = id => !!ART[id]?.animated;

// Live art canvases, one per item (and per wing panel). Animated capes are
// repainted into the same canvas each frame.
const canvases = new Map();
function capeCanvas(id, ph) {
  let c = canvases.get(id);
  if (!c) { c = makeCanvas(CAPE_W, CAPE_H); c.ph = null; canvases.set(id, c); }
  if (c.ph !== ph) {
    const ctx = c.getContext('2d');
    ctx.clearRect(0, 0, CAPE_W, CAPE_H);
    ART[id].paint(ctx, rng(seedOf(id)), ph);
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

// The item's 2D artwork as a canvas, for the no-WebGL fallback: a cape's
// still frame, a wing pair laid flat, or null for head items (pure geometry).
export function flatArt(id) {
  const a = ART[id];
  if (a?.kind === 'cape') {
    const c = makeCanvas(CAPE_W, CAPE_H);
    c.getContext('2d').drawImage(capeCanvas(id, STILL_T / LOOP), 0, 0);
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
function glowRing(radius, tube, hex, glowHex) {
  const g = new THREE.Group();
  g.add(new THREE.Mesh(new THREE.TorusGeometry(radius, tube, 8, 64), new THREE.MeshBasicMaterial({ color: hex })));
  g.add(new THREE.Mesh(new THREE.TorusGeometry(radius, tube * 2.6, 8, 64), new THREE.MeshBasicMaterial({
    color: glowHex, transparent: true, opacity: 0.22, blending: THREE.AdditiveBlending, depthWrite: false,
  })));
  return g;
}

// Pixel-square particles (Minecraft's own particles are squares). spawn(i)
// gives particle i's start point, drift(life, i) its offset as it ages 0..1.
// Every particle's age is a whole-number multiple of the loop, so it repeats.
function sparks(colors, count, spawn, drift) {
  const pos = new Float32Array(count * 3), col = new Float32Array(count * 3);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  const points = new THREE.Points(geo, new THREE.PointsMaterial({
    size: 0.8, vertexColors: true, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false,
  }));
  points.frustumCulled = false;               // positions change every frame
  const palette = colors.map(h => rgb(h).map(v => v / 255));
  points.userData.update = ph => {
    for (let i = 0; i < count; i++) {
      const life = fract(ph * (1 + (i % 2)) + hash(i + 3));
      const [x, y, z] = spawn(i), [dx, dy, dz] = drift(life, i);
      pos.set([x + dx, y + dy, z + dz], i * 3);
      const c = palette[i % palette.length], k = Math.sin(life * Math.PI);   // fade in, fade out
      col.set([c[0] * k, c[1] * k, c[2] * k], i * 3);
    }
    geo.attributes.position.needsUpdate = true;
    geo.attributes.color.needsUpdate = true;
  };
  return points;
}

// ------------------------------------------------------------ head cosmetics
function buildOrbitHalo(g) {
  // The Origin mark as an atom over the head: three rings 60 degrees apart.
  const atom = new THREE.Group();
  atom.position.set(0, 36, 0);
  [5.4, 4.7, 4.0].forEach((radius, i) => {
    const pivot = new THREE.Group();
    pivot.rotation.y = i * Math.PI / 3;
    const ring = glowRing(radius, 0.28, '#83e2b5', '#4bc8ae');
    ring.rotation.x = Math.PI / 2 - 0.42;
    pivot.add(ring);
    atom.add(pivot);
  });
  g.add(atom);
  return (t, still) => { atom.rotation.y = still ? 0.3 : t * 0.7; };
}
function buildGoldHalo(g) {
  const ring = glowRing(4.4, 0.5, '#ffd56b', '#ffb23e');
  ring.rotation.x = Math.PI / 2;
  g.add(ring);
  return (t, still) => { ring.position.set(0, 35.2 + (still ? 0 : 0.35 * Math.sin(t * 2)), 0); };
}
function buildCrown(g) {
  const gold = '#f2c14e', dark = '#c9932c';
  const crown = new THREE.Group();
  crown.add(block(9, 2, 0.8, gold, 0, 32, 4.3), block(9, 2, 0.8, gold, 0, 32, -4.3));
  crown.add(block(0.8, 2, 9, dark, 4.3, 32, 0), block(0.8, 2, 9, dark, -4.3, 32, 0));
  for (const p of [-4, 0, 4]) {
    const tall = p === 0 ? 3 : 2;
    crown.add(block(1.2, tall, 1.2, gold, p, 33 + tall / 2, 4.3), block(1.2, tall, 1.2, gold, p, 33 + tall / 2, -4.3));
    if (p !== 0) continue;
    crown.add(block(1.2, 2, 1.2, dark, 4.3, 34, p), block(1.2, 2, 1.2, dark, -4.3, 34, p));
  }
  crown.add(block(1.4, 1.4, 0.5, '#e0485b', 0, 32, 4.85));
  crown.add(block(0.5, 1.4, 1.4, '#5b8be0', 4.85, 32, 0), block(0.5, 1.4, 1.4, '#5b8be0', -4.85, 32, 0));
  crown.add(block(1.4, 1.4, 0.5, '#5fd09b', 0, 32, -4.85));
  crown.rotation.z = 0.05;
  g.add(crown);
  return () => {};
}

// --------------------------------------------------------------- the cape
function buildCape(id, g) {
  const a = ART[id];
  const outerTex = pixelTexture(id, capeCanvas(id, STILL_T / LOOP));
  // The inside face: the same art, darkened. Cached like the texture it feeds.
  const innerKey = `${id}:inner`;
  if (!canvases.has(innerKey)) canvases.set(innerKey, makeCanvas(CAPE_W, CAPE_H));
  const innerCanvas = canvases.get(innerKey);
  const innerTex = pixelTexture(innerKey, innerCanvas);
  const paintInner = () => {
    const ctx = innerCanvas.getContext('2d');
    ctx.clearRect(0, 0, CAPE_W, CAPE_H);
    ctx.drawImage(canvases.get(id), 0, 0);
    ctx.fillStyle = 'rgba(0,0,0,.45)';
    ctx.fillRect(0, 0, CAPE_W, CAPE_H);
    innerTex.needsUpdate = true;
  };
  paintInner();
  const outer = new THREE.MeshBasicMaterial({ map: outerTex, vertexColors: true });
  const inner = new THREE.MeshBasicMaterial({ map: innerTex, vertexColors: true });
  const edge = new THREE.MeshBasicMaterial({ color: a.edge, vertexColors: true });
  // faces +x, -x, +y, -y, +z (against the back), -z (the outside)
  const cape = new THREE.Mesh(shadedBox(10, 16, 1), [edge, edge, edge, edge, inner, outer]);
  cape.position.set(0, -8, -0.5);
  const pivot = new THREE.Group();
  pivot.position.set(0, 24, -2);
  pivot.add(cape);
  g.add(pivot);

  // Particles lift off the outside of the cape and drift away behind.
  const fx = a.sparks && sparks(a.sparks, 26,
    i => [(hash(i + 11) - 0.5) * 9, -2 - hash(i + 17) * 14, -1.2],
    (life, i) => [Math.sin(life * TAU + i) * 0.6, life * (a.rise ? 6 : 3), -life * 3.5]);
  if (fx) pivot.add(fx);

  let painted = null;
  return (t, still) => {
    const ph = fract(t / LOOP);
    pivot.rotation.x = 0.17 + (still ? 0 : 0.05 * Math.sin(ph * TAU));
    if (a.animated && painted !== ph) {
      capeCanvas(id, ph);
      outerTex.needsUpdate = true;
      paintInner();
      painted = ph;
    }
    fx?.userData.update(ph);
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

  const fx = a.sparks && sparks(a.sparks, 14,
    i => [1 + hash(i + 31) * 9, -3 - hash(i + 37) * 11, 0],
    (life, i) => [Math.sin(life * TAU + i) * 0.5, (a.rise ? 1 : -1) * life * 5, -life * 2]);
  if (fx) elbow.add(fx);

  // Two wingbeats per loop. Upstroke: swept back, raised, folded at the
  // elbow. Downstroke: spread wide, the primaries flung out and down.
  const pose = (t, still) => {
    const ph = fract(t / LOOP);
    const beat = still ? -0.35 : Math.sin(ph * TAU * 2);
    const lag = still ? -0.35 : Math.sin(ph * TAU * 2 - 0.7);   // the elbow follows through
    shoulder.rotation.set(0, 0.62 + 0.38 * beat, 0.42 + 0.3 * beat);
    elbow.rotation.set(0, 0.2 + 0.45 * Math.max(0, lag), -0.75 - 0.3 * lag);
    fx?.userData.update(ph);
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
  g.userData.update = a.kind === 'cape' ? buildCape(id, g) : a.kind === 'wings' ? buildWings(id, g) : a.build(g);
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

  function setSkin(tex) {
    disposeTree(body);
    body.clear();
    const img = tex.image, W = img.width || 64, H = img.height || 64;
    const legacy = H === W / 2;       // pre-1.8 64x32 skins: no overlays, mirrored limbs
    const s = W / 64;                 // HD skins scale the whole layout
    for (const p of PARTS) {
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
  head: { yaw: 0.5, look: 21, dist: 80, lift: 8 },
};
const THUMBS = {
  cape: { yaw: Math.PI - 0.55, look: 18, dist: 56, lift: 4 },
  wings: { yaw: Math.PI - 0.45, look: 21, dist: 82, lift: 8 },
  head: { yaw: 0.55, look: 31, dist: 38, lift: 14 },
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
    setSkin(tex) { player.setSkin(tex || mannequinSkin()); wake(); },
  };
}

// Loads a player's real skin by UUID. Resolves null on any failure, and the
// caller keeps the mannequin. The skin hosts must send CORS headers (WebGL
// can't read an image without them), so try two.
export async function loadSkin(uuid) {
  const loader = new THREE.TextureLoader().setCrossOrigin('anonymous');
  for (const url of [`https://crafatar.com/skins/${uuid}`, `https://mc-heads.net/skin/${uuid}`]) {
    try {
      const tex = await loader.loadAsync(url);
      tex.magFilter = THREE.NearestFilter;
      tex.minFilter = THREE.NearestFilter;
      tex.generateMipmaps = false;
      return tex;
    } catch { /* try the next host */ }
  }
  return null;
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
    frame: (id, i, n) => shot(id, (i / n) * LOOP, false, 'image/webp'),
    dispose() { player.wear(null); renderer.dispose(); renderer.forceContextLoss(); },
  };
}
