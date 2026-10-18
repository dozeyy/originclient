// Golden Hour Cape: a painted sunset on folded cloth. Static, scale 8, 1 art pixel = 1 texture
// pixel on the 80x128 outside face (one density per item).
//
// How it avoids the flat-bands-and-blobs look: the sky is an 11-colour hue-shifted ramp
// (indigo -> violet -> rose -> coral -> amber -> pale gold) where each step melts into the next
// through a short ordered-dither seam, and the lookup coordinate is warped by noise, bent
// around the sun and tilted, so no seam is a straight horizontal line. A low sun with a
// stepped bloom sits half behind the far hills. Five ridge layers recede (far = light and
// warm and low contrast, near = dark and cool) with a sun-facing rim light, pine silhouettes
// on the two nearest, and a few wisps lit from below. Five uneven cloth folds are laid over
// the painting as quantised dithered shade steps, then the collar, hem and 1 px outline.
import { Canvas, clamp, smoothstep, mix, lerp, fbm, hash2, bayer, rng, rgb2hsl, hsl2rgb, toHex } from '../../lib/px.mjs';
import { newCapeArt } from '../../lib/cape.mjs';

const S = 8, W = 10 * S, H = 16 * S;
const SUNX = 45, SUNY = 76, SUNR = 7;
const OUTLINE = '#120b24';

// ------------------------------------------------------------------ sky
const SKY = ['#1a1238', '#2b1a52', '#44216a', '#6a2c78', '#962f78', '#c4436f', '#e3606a', '#f48a5a', '#fcb260', '#fed584', '#fff0b4'];
const skyColor = (x, y, t) => {
  const p = clamp(t) * (SKY.length - 1), lo = Math.floor(p), f = smoothstep(0.32, 0.68, p - lo);
  const th = bayer(x, y, 4) + (hash2(x, y, 5) - 0.5) * 0.16;
  return SKY[Math.min(SKY.length - 1, lo + (f > th ? 1 : 0))];
};
function skyT(x, y) {
  let t = (y - 6) / 80;                                   // 0 at the collar, 1 at the horizon
  t = Math.pow(clamp(t), 1.32);
  t += (fbm(x * 0.045, y * 0.1, 3) - 0.5) * 0.12;        // warp: no straight seams
  const dx = (x - SUNX) / 36, dy = (y - SUNY) / 34;
  t += Math.exp(-(dx * dx + dy * dy) * 2.0) * 0.2;       // bend around the sun
  t -= (Math.abs(x - SUNX) / 40) * 0.07;                 // violet toward the edges
  t += (x - 40) / 80 * -0.025;
  return t;
}

// ------------------------------------------------------------------ hills
// Ridge y(x) per layer: far -> near. Each is a sum of two sines + fbm so they never repeat.
const LAYERS = [
  { y: 80, amp: 5, f: 0.05, seed: 11, rim: '#ffd9a0', body: ['#f08a6c', '#dc7070', '#c85e76'] },
  { y: 88, amp: 6, f: 0.07, seed: 23, rim: '#ffb982', body: ['#c05a7c', '#a84c7c', '#8e4078'] },
  { y: 97, amp: 6, f: 0.06, seed: 37, rim: '#f09a78', body: ['#82407a', '#6e3676', '#5a2c6e'], pines: { n: 11, h: [4, 7] } },
  { y: 106, amp: 5, f: 0.08, seed: 51, rim: '#d27a7a', body: ['#4a2c66', '#3c235a', '#2e1b4c'], pines: { n: 13, h: [6, 11] } },
  { y: 115, amp: 4, f: 0.09, seed: 67, rim: '#9a5a7e', body: ['#241545', '#1d113c', '#170d32'] },
];
const ridgeY = (L, x) => L.y + (fbm(x * L.f, 0.5, L.seed, 3) - 0.5) * 2 * L.amp + Math.sin(x * L.f * 0.9 + L.seed) * L.amp * 0.35;

function pineColumns(L, k) {
  const r = rng(L.seed * 7 + k), out = [];
  let x = r.int(5) + 1;
  while (x < W - 2) {
    const h = r.int(L.pines.h[1] - L.pines.h[0] + 1) + L.pines.h[0];
    out.push({ x, h });
    x += 4 + r.int(Math.round(W / L.pines.n) + 2);
  }
  return out;
}
// Pine silhouette rows: tier-stepped triangle, 1 px trunk of canopy at the base.
function drawPine(c, x, baseY, h, col, lit, sunSide) {
  for (let j = 0; j < h; j++) {
    const hw = Math.floor((j + 1) * 0.5) + (j % 3 === 2 ? 1 : 0);
    const y = baseY - h + 1 + j;
    for (let i = -hw; i <= hw; i++) c.set(x + i, y, col);
    if (lit && j > 0) c.set(x + (sunSide < 0 ? hw : -hw), y, lit);    // sun-facing edge catches light
  }
  c.set(x, baseY - h, lit || col);
}

// ------------------------------------------------------------------ clouds
// Wisps lit from below: stretched noise inside a tapered ellipse; the underside rows glow in the
// sky's next-warmer colours, the body is violet-shadowed, the top edge a darker sliver.
const CLOUDS = [
  { cx: 22, cy: 30, rx: 22, ry: 3.2, seed: 3 },
  { cx: 58, cy: 42, rx: 20, ry: 3.4, seed: 5 },
  { cx: 30, cy: 54, rx: 24, ry: 3.6, seed: 8 },
  { cx: 62, cy: 63, rx: 17, ry: 2.8, seed: 13 },
  { cx: 14, cy: 69, rx: 14, ry: 2.4, seed: 17 },
  { cx: 40, cy: 18, rx: 16, ry: 2.2, seed: 21 },
];
function cloudMask(cl) {
  const m = new Map();
  for (let y = Math.floor(cl.cy - cl.ry * 2.5); y <= cl.cy + cl.ry * 2.5; y++) for (let x = Math.floor(cl.cx - cl.rx - 2); x <= cl.cx + cl.rx + 2; x++) {
    const ex = (x - cl.cx) / cl.rx, ey = (y - cl.cy) / cl.ry;
    const v = 1 - ex * ex - ey * ey * (ey > 0 ? 1.25 : 0.8) + (fbm(x * 0.09, y * 0.35, cl.seed, 3) - 0.5) * 0.9;
    if (v <= 0.02) continue;
    if (v < 0.16 && v / 0.16 < bayer(x, y, 4)) continue;
    m.set(y * W + x, v);
  }
  return m;
}

// ------------------------------------------------------------------ cloth folds
const VALLEYS = [-6, 14, 38, 57, 79, 100];
const valleyX = (k, y) => 40 + (VALLEYS[k] - 40) * (0.93 + 0.14 * (y / H)) + Math.sin(y * 0.05 + k * 2.3) * 2;
function foldProfile(p) {            // 0 valley -> broad lit ridge near p = .36 -> long shaded slope
  const r = 0.36;
  return p < r ? 0.5 - 0.5 * Math.cos(Math.PI * p / r) : 0.5 + 0.5 * Math.cos(Math.PI * (p - r) / (1 - r));
}
function foldValue(x, y) {
  let k = 0; while (k < VALLEYS.length - 2 && x >= valleyX(k + 1, y)) k++;
  const a = valleyX(k, y), b = valleyX(k + 1, y), p = clamp((x - a) / (b - a));
  return foldProfile(p);
}
// 7 quantised shade steps, no dither (the painting already carries the texture). Shadows slide
// cooler (toward violet) and richer, lights slide warmer, so the folds never grey the colours.
const FOLD_STEPS = 7;
const foldStep = (x, y) => clamp(Math.round(foldValue(x, y) * (FOLD_STEPS - 1)), 0, FOLD_STEPS - 1);
function shadeCloth(col, step, amp) {
  const u = (step - 2) / 2.5;                      // step 2 = neutral; 0 = deepest valley; 6 = lit crest
  const [h, sa, l] = rgb2hsl(col);
  if (u < 0) {
    const k = -u * amp;
    return toHex(hsl2rgb(h + (((270 - h + 540) % 360) - 180) * 0.22 * k, clamp(sa * (1 + 0.18 * k)), l * (1 - 0.34 * k)));
  }
  const k = Math.min(1, u) * amp;
  return toHex(hsl2rgb(h + (((42 - h + 540) % 360) - 180) * 0.1 * k, sa, clamp(l + (1 - l) * 0.13 * k)));
}

export default {
  meta: { id: 'cape-golden', name: 'Golden Hour Cape', kind: 'cape', cents: 599, blurb: 'A low sun, a violet-to-gold sky and layered pine ridges, painted on the cloth.' },
  cape: { scale: S, frames: 1 },
  paint() {
    const c = newCapeArt(S);
    // sky
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) c.set(x, y, skyColor(x, y, skyT(x, y)));

    // stars in the deep violet
    const rs = rng(99);
    for (let i = 0; i < 9; i++) {
      const x = 4 + rs.int(W - 8), y = 9 + rs.int(20);
      c.set(x, y, i % 3 === 0 ? '#f0e6ff' : '#b9a6e8');
    }

    // sun: stepped bloom, rim, core (the far hills cover its lower edge later)
    for (let y = 0; y < 118; y++) for (let x = 0; x < W; x++) {
      const d = Math.hypot(x + 0.5 - SUNX, (y + 0.5 - SUNY) * 1.08);
      if (d < SUNR) { c.set(x, y, d > SUNR - 1.1 ? '#ffd27a' : d > SUNR - 2.2 ? '#ffeaa8' : '#fff8dc'); continue; }
      const k = Math.pow(clamp(1 - (d - SUNR) / 24), 2.6);
      const lvl = k * 4.2, base = Math.floor(lvl), f = lvl - base;
      const step = base + (f > bayer(x, y, 4) + (hash2(x, y, 9) - 0.5) * 0.2 ? 1 : 0);
      if (step > 0) c.set(x, y, mix(c.get(x, y), '#ffe2a0', [0, 0.1, 0.2, 0.32, 0.46][Math.min(4, step)]));
    }

    // clouds
    for (const cl of CLOUDS) {
      const m = cloudMask(cl);
      for (const [k, v] of m) {
        const x = k % W, y = (k / W) | 0;
        let below = 0; while (below < 6 && m.has((y + below + 1) * W + x)) below++;
        let above = 0; while (above < 6 && m.has((y - above - 1) * W + x)) above++;
        const t = skyT(x, y), near = Math.exp(-Math.pow((x - SUNX) / 30, 2));
        const litRows = 1 + Math.round(2.2 * near);
        let col;
        if (below < litRows) col = skyColor(x, y, t + 0.34 + 0.06 * (litRows - below));       // underside: warm light from the sun
        else if (above === 0) col = mix(skyColor(x, y, t - 0.06), '#3a1d5e', 0.4);          // top edge: shadowed sliver
        else col = mix(skyColor(x, y, t + 0.02), '#4a2468', 0.3 + 0.1 * Math.min(2, above));  // body: violet shade
        if (v < 0.3 && below >= litRows && above > 0) col = mix(col, skyColor(x, y, t), 0.5);
        c.set(x, y, col);
      }
    }

    // birds: three tiny wing flicks near the sun
    for (const [bx, by] of [[63, 72], [68, 69], [58, 68]]) { c.set(bx, by, '#3a1f52'); c.set(bx + 1, by + 1, '#3a1f52'); c.set(bx + 2, by, '#3a1f52'); }

    // hills, far -> near
    LAYERS.forEach((L, li) => {
      const ridge = [];
      for (let x = 0; x < W; x++) ridge.push(Math.round(ridgeY(L, x)));
      const sunDist = x => Math.abs(x - SUNX);
      for (let x = 0; x < W; x++) {
        const top = ridge[x], slope = (ridge[Math.min(W - 1, x + 1)] - ridge[Math.max(0, x - 1)]) / 2;
        const facing = x < SUNX ? slope > 0.1 : slope < -0.1;       // downhill toward the sun side = lit
        for (let y = top; y < H; y++) {
          const d = y - top;
          let col;
          const pick = d < 3 ? 0 : d < 6 ? (bayer(x, y, 4) < (d - 3) / 3 ? 1 : 0) : d < 10 ? 1 : d < 14 ? (bayer(x, y, 4) < (d - 10) / 4 ? 2 : 1) : 2;
          col = L.body[Math.min(2, pick)];
          const glow = Math.exp(-Math.pow(sunDist(x) / 26, 2));
          if (d === 0) col = mix(col, L.rim, 0.45 + 0.5 * glow);
          else if (d === 1 && facing) col = mix(col, L.rim, 0.3 + 0.4 * glow);
          else if (d === 2 && facing && glow > 0.35 && bayer(x, y, 4) < 0.5) col = mix(col, L.rim, 0.22);
          c.set(x, y, col);
        }
      }
      if (L.pines) for (const p of pineColumns(L, li)) {
        const base = ridge[Math.min(W - 1, p.x)] + 1;
        const lit = mix(L.body[0], L.rim, 0.5 * Math.exp(-Math.pow((p.x - SUNX) / 28, 2)) + 0.12);
        drawPine(c, p.x, base, p.h, L.body[1], toHex(lit), p.x < SUNX ? -1 : 1);
      }
    });
    // two tall foreground pines frame the scene at the edges
    for (const [x, h, base] of [[7, 21, 121], [14, 13, 121], [72, 19, 121], [66, 11, 121]]) drawPine(c, x, base, h, '#120a2c', '#4a2c68', x < SUNX ? -1 : 1);

    // ------- cloth folds over the painting (stronger toward the hem where cloth drapes)
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const st = foldStep(x, y); if (st === 2) continue;
      c.set(x, y, shadeCloth(c.get(x, y), st, 0.75 + 0.25 * (y / H)));
    }

    // ------- collar: gathered band, seam and stitch row in the sky's deepest violet
    for (let y = 0; y < 7; y++) for (let x = 0; x < W; x++) {
      const f = Math.min(3, foldStep(x, y) >> 1);
      c.set(x, y, ['#0f0922', '#170d30', '#1f1240', '#2a1a52'][f]);
    }
    for (let x = 0; x < W; x++) { c.set(x, 7, '#0f0922'); c.set(x, 8, ((x >> 1) & 1) ? '#b8794e' : '#5b3a52'); }

    // ------- hem: fold-over with an amber stitched line
    const hy = H - 9;
    for (let x = 0; x < W; x++) {
      const f = Math.min(3, foldStep(x, hy) >> 1);
      c.set(x, hy, '#0f0922');
      c.set(x, hy + 1, ['#2a1a52', '#3a2262', '#4a2c72', '#5a3a82'][f]);
      for (let y = hy + 2; y < H - 1; y++) c.set(x, y, ['#150c2e', '#1d1140', '#261650', '#321f60'][f]);
      c.set(x, hy + 3, (x % 4) === 3 ? '#1d1140' : '#d9965a');
      c.set(x, hy + 4, '#0f0922');
    }

    // ------- 1 px dark outline all round
    for (let x = 0; x < W; x++) { c.set(x, 0, OUTLINE); c.set(x, H - 1, OUTLINE); }
    for (let y = 0; y < H; y++) { c.set(0, y, OUTLINE); c.set(W - 1, y, OUTLINE); }
    return c;
  },
};
