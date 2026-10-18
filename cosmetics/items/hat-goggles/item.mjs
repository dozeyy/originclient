// Pilot Goggles: retro-futurist aviator goggles pushed up onto the forehead. Two round lenses of iridescent mirror
// (a pink -> violet -> blue -> cyan -> green -> lime sheen that runs across BOTH lenses, a crisp dark horizon in the
// reflection, a white crescent of light) in polished-chrome rims, a padded gunmetal nose bridge with a stitched
// seam, riveted gunmetal brackets, and a black webbing strap with a reflective centre thread and a slide buckle at
// the back of the head. Every few seconds a diagonal glint sweeps across the glass and ends in a small
// four-point sparkle (an additive plane sunk behind the rims, so it can never leak past the lens).
//
// Construction: each cup is a 16-sided chrome tube (flat zero-thickness facets, so it is round from every
// side), a flat chrome rim ring (an annulus cut out of a square plane) and a mirror disc sunk one texel behind it.
// Round shapes are transparent cut-outs in planes, never boxy. Every face lives in one small packed atlas
// (32 x ~10 model px), which keeps the 24 animation frames small. The cup assembly leans back 8 degrees (pushed up
// and resting on the head); the strap stays level.
//
// Light: top-front-left of the PLAYER (+x, +y, +z); +x is image-right on a south-facing face.
// Clearance: y <= 8.4, z <= 5.47, |x| <= 5.0.
import { clamp, smoothstep, mix, toHex, hash2, ramp, Canvas } from '../../lib/px.mjs';

const S = 8, px = 1 / S;
const FRAMES = 24, FPS = 12, SWEEP = 10;                 // 2 s loop: a 0.8 s sweep, a short sparkle, then still
const TILT = -8, TILT_PIVOT = [0, 6.75, 4.6];

// ---- ramps -----------------------------------------------------------------------------------------------
// polished chrome, 8 steps: blue-black -> steel -> bright sky -> white
const CHR = ['#12151d', '#262b38', '#454c5d', '#727b8d', '#a3acbc', '#cdd3de', '#eceff5', '#ffffff'];
// gunmetal (the Visor's): cool shadows -> neutral -> a warm-white specular
const GUN = ['#0a0b0e', '#181a1f', '#292c33', '#40444c', '#5f636b', '#8d8f92', '#c9c5bd'];
// black webbing
const WEB = ['#050608', '#0c0e11', '#14171b', '#1e2228', '#2b3039', '#3c424d'];
// the mirror sheen: one hue-shifted ramp per colour band, 6 tones each (dark -> bright)
const hueRamp = (c, o = {}) => ramp(c, 6, { lo: 0.1, hi: 0.86, shift: 0.5, ...o });
const SPEC = [
  hueRamp('#ff5fb4', { shadowHue: 300, lightHue: 12 }),    // pink
  hueRamp('#b56bff', { shadowHue: 270, lightHue: 330 }),   // violet
  hueRamp('#4f9dff', { shadowHue: 245, lightHue: 200 }),   // azure
  hueRamp('#2fe3f0', { shadowHue: 215, lightHue: 160 }),   // cyan
  hueRamp('#4ff2a2', { shadowHue: 190, lightHue: 110 }),   // mint
  hueRamp('#c6ff4a', { shadowHue: 150, lightHue: 80 }),    // lime
];
const TONES = SPEC[0].length;
// band (0..5, fractional) x tone (0..5, fractional), quantised to half steps so a gradient is clean parallel bands
function specCol(band, tone) {
  const b = clamp(band, 0, SPEC.length - 1), lo = Math.floor(b), hi = Math.min(SPEC.length - 1, lo + 1), f = Math.round((b - lo) * 2) / 2;
  const t = clamp(Math.round(tone * 2) / 2, 0, TONES - 1), tl = Math.floor(t), th = Math.min(TONES - 1, tl + 1), tf = t - tl;
  const at = r => mix(SPEC[r][tl], SPEC[r][th], tf);
  return toHex(mix(at(lo), at(hi), f));
}
const ramped = (R, v) => { const p = clamp(Math.round(v * 2) / 2, 0, R.length - 1), lo = Math.floor(p), hi = Math.min(R.length - 1, lo + 1); return toHex(mix(R[lo], R[hi], p - lo)); };
const chrome = v => ramped(CHR, v), gun = v => ramped(GUN, v), web = v => ramped(WEB, v);

// ---- the chrome shader -------------------------------------------------------------------------------------
// Index into CHR for a unit normal (x right, y up, z toward the viewer): a polished-metal environment with bright sky
// above, a crisp dark horizon, dim ground with a faint bounce, and a tight specular streak toward the light.
const LV = (() => { const v = [0.45, 0.8, 0.4], l = Math.hypot(...v); return v.map(a => a / l); })();
const HV = (() => { const v = [LV[0], LV[1], LV[2] + 1], l = Math.hypot(...v); return v.map(a => a / l); })();
function chromeIdx(nx, ny, nz) {
  const h = -0.1;
  let s;
  if (ny > h + 0.07) s = 2.7 + 3.0 * smoothstep(h, 0.9, ny);                             // sky
  else if (ny > h) s = 0.6;                                                               // the horizon line
  else s = 1.2 + 1.3 * smoothstep(-0.55, -1, ny) + 0.5 * smoothstep(-0.3, -0.05, ny);    // ground, a faint bounce
  s += 0.55 * nx;                                                                         // the lit side
  const sp = Math.max(0, nx * HV[0] + ny * HV[1] + nz * HV[2]);
  return s + 2.4 * Math.pow(sp, 36);
}

// ---- geometry --------------------------------------------------------------------------------------------
const CY = 6.75, CX = 2.2;                               // lens centre height; lens centre half-spacing
const WZ0 = 4.25, WZ1 = 5.25;                            // the tube's z range
const CHORD = 0.625, N = 16, APO = CHORD / (2 * Math.tan(Math.PI / N));    // 16 facets, 5 texels wide
const RIM_R = 12.7, HOLE_R = 9.5;                        // texels: outer edge of the rim, edge of the opening
const RIM_Z = 5.25, GLASS_Z = 5.125, GLOW_Z = 5.1875;
const BONES = { lens: [], strap: [] };
const faces = [];                                        // { cube, name, w, h, fn, rect }
const mk = (from, to, extra = {}) => ({ from, to, ...extra });
const sizeOf = (c, name) => {
  const w = (c.to[0] - c.from[0]) * S, h = (c.to[1] - c.from[1]) * S, d = (c.to[2] - c.from[2]) * S;
  return { south: [w, h], north: [w, h], east: [d, h], west: [d, h], up: [w, d], down: [w, d] }[name].map(Math.round);
};
function face(bone, cube, name, fn) {
  if (!BONES[bone].includes(cube)) BONES[bone].push(cube);
  const [w, h] = sizeOf(cube, name);
  faces.push({ cube, name, fn, w, h });
}

for (const sx of [1, -1]) {
  const cx = sx * CX;
  for (let k = 0; k < N; k++) {                           // the tube
    const psi = (k * 360) / N;
    const c = mk([cx - CHORD / 2, CY + APO, WZ0], [cx + CHORD / 2, CY + APO, WZ1], { rotation: { origin: [cx, CY, 0], angle: [0, 0, psi] } });
    face('lens', c, 'up', v => paintFacet(v, c, psi, cx));
  }
  const rim = mk([cx - 13 * px, CY - 13 * px, RIM_Z], [cx + 13 * px, CY + 13 * px, RIM_Z]);
  face('lens', rim, 'south', v => paintRim(v));
  const glass = mk([cx - 11 * px, CY - 11 * px, GLASS_Z], [cx + 11 * px, CY + 11 * px, GLASS_Z]);
  face('lens', glass, 'south', v => paintGlass(v, sx));
  const glow = mk([cx - 11 * px, CY - 11 * px, GLOW_Z], [cx + 11 * px, CY + 11 * px, GLOW_Z], { glow: true, _sx: sx });
  face('lens', glow, 'south', null);
}
const bridge = mk([-0.875, 6.125, 4.5], [0.875, 7.375, 5.125]);       // padded bridge between the cups
for (const f of ['south', 'up', 'down', 'east', 'west']) face('lens', bridge, f, (v, ff) => paintPad(v, ff));
for (const sx of [1, -1]) {                                          // riveted brackets from the lens to the strap
  const br = sx > 0 ? mk([3.375, 6.125, 4.375], [5.0, 7.375, 5.0]) : mk([-5.0, 6.125, 4.375], [-3.375, 7.375, 5.0]);
  for (const f of ['south', 'up', sx > 0 ? 'east' : 'west']) face('strap', br, f, (v, ff) => paintBracket(v, ff, sx));
}
const STRAP = { y0: 6.0, y1: 7.5 };                                    // 12 rows
const sideL = mk([4.625, STRAP.y0, -5.0], [5.0, STRAP.y1, 4.5]), sideR = mk([-5.0, STRAP.y0, -5.0], [-4.625, STRAP.y1, 4.5]);
const rear = mk([-4.625, STRAP.y0, -5.0], [4.625, STRAP.y1, -4.625]);
for (const [c, outer, lit] of [[sideL, 'east', 3.1], [sideR, 'west', 1.8], [rear, 'north', 2.4]]) for (const f of [outer, 'up']) face('strap', c, f, (v, ff) => paintStrap(v, ff, lit));
const buckle = mk([-0.875, 5.875, -5.25], [0.875, 7.625, -4.875]);      // slide buckle on the back strap
for (const f of ['north', 'up', 'east', 'west']) face('strap', buckle, f, (v, ff) => paintBuckle(v, ff));

// ---- pack the atlas (shelf, tallest first, deterministic) and give every cube its UV rectangles ----------------
const ATLAS_W = 256;
let ATLAS_H = 0;
{
  const todo = faces.map((f, i) => ({ f, i })).sort((a, b) => b.f.h - a.f.h || b.f.w - a.f.w || a.i - b.i);
  let x = 0, y = 0, rowH = 0;
  for (const { f } of todo) {
    if (x + f.w > ATLAS_W) { x = 0; y += rowH; rowH = 0; }
    f.rect = { x, y, w: f.w, h: f.h };
    (f.cube.faces ||= {})[f.name] = [x / S, y / S, (x + f.w) / S, (y + f.h) / S];
    x += f.w; rowH = Math.max(rowH, f.h);
  }
  ATLAS_H = Math.ceil((y + rowH) / S) * S;
}
const TEX = [ATLAS_W / S, ATLAS_H / S];
const clean = ({ _sx, ...c }) => c;

// texel centre -> position on the cube face in the cube's own frame; face images read from outside, +y up
function local(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) * px, v = (y + 0.5) * px;
  switch (f) {
    case 'south': return [x0 + u, y1 - v, z1];
    case 'north': return [x1 - u, y1 - v, z0];
    case 'east': return [x1, y1 - v, z1 - u];
    case 'west': return [x0, y1 - v, z0 + u];
    case 'up': return [x1 - u, y1, z1 - v];
    default: return [x1 - u, y0, z0 + v];
  }
}

// ---- the tube: 16 chrome facets ---------------------------------------------------------------------------------
// A facet is a flat strip but is shaded as the cylinder it approximates: the normal at each texel follows its
// position across the strip. Along the tube: a bright lip at the front, a dark groove (a bayonet ring), a flange behind.
function paintFacet(v, c, psi, cx) {
  const a = (psi * Math.PI) / 180, ca = Math.cos(a), sa = Math.sin(a);
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const [X, , Z] = local(c, 'up', x, y), tau = X - cx;
    const nx = tau * ca - APO * sa, ny = tau * sa + APO * ca, l = Math.hypot(nx, ny);
    let s = chromeIdx(nx / l, ny / l, 0);
    if (Z >= 5.125) s += 0.9;                                           // front lip
    else if (Z >= 4.625 && Z < 4.75) s = Math.min(s, 1.0) * 0.6;         // groove
    else if (Z < 4.5) s -= 0.7;                                          // rear flange, in shade
    v.set(x, y, chrome(s));
  }
}

// ---- the rim: a polished chrome torus seen from the front (26 x 26 texels, round cut-out) ----------------------------
function paintRim(v) {
  const mid = (RIM_R + HOLE_R) / 2, half = (RIM_R - HOLE_R) / 2;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const dx = x + 0.5 - 13, dy = y + 0.5 - 13, r = Math.hypot(dx, dy);
    if (r > RIM_R || r < HOLE_R) continue;
    const t = (r - mid) / half, b = (64 * Math.PI / 180) * t, ux = dx / r, uy = -dy / r;     // the tube's surface tilts outward on the outer half, inward on the inner
    let s = chromeIdx(ux * Math.sin(b), uy * Math.sin(b), Math.cos(b));
    if (r > RIM_R - 1) s = Math.min(s, ux * 0.5 + uy * 0.8 > 0 ? 3.4 : 1.4);                  // outer edge: selective outline, softer toward the light
    if (r < HOLE_R + 1) s = Math.min(s, 0.9);                                                 // inner edge: contact shadow
    v.set(x, y, chrome(s < 1.5 ? s : s + 0.7));   // face shading dims painted colour, so lift the chrome
  }
}

// ---- the mirror lens (22 x 22 texels, the visible part is the 9.5 texel opening) ---------------------------------------
// Global texel x across BOTH lenses drives the hue, so the iridescence runs continuously over the pair.
function lensXY(x, y, sx) { return { dx: x + 0.5 - 11, dy: y + 0.5 - 11, xg: sx * CX * S + (x + 0.5 - 11) }; }
function paintGlass(v, sx) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const { dx, dy, xg } = lensXY(x, y, sx), rr = Math.hypot(dx, dy);
    if (rr > 10.9) continue;
    // hue: a diagonal sweep pink (upper left) -> lime (lower right), bent a little like a curved mirror
    const u = (xg * 0.8 + dy * 0.55) / 26.8, band = (u + 1) * 2.5 + 0.35 * Math.sin(0.15 * xg + 0.2 * dy) + 0.4 * (rr / 9.5) ** 2 * Math.sign(dx);
    // tone: reflected sky above a curved horizon, a bright haze band on it, a dark line, dim ground below with a faint bounce
    const hy = 1.0 + (dx * dx) / 70;
    let tone;
    if (dy < hy - 2) tone = 3.0 + 1.3 * smoothstep(-9, hy - 1, dy);
    else if (dy < hy) tone = 5.0;
    else if (dy < hy + 1) tone = 0.3;
    else tone = 1.4 + 0.9 * smoothstep(4, 9, dy - hy) - 0.4 * smoothstep(0, 3, dy - hy);
    tone += 0.35 * (dx / 9.5);
    // the rim's shadow on the glass: widest on the side facing the light (upper right)
    const toward = (dx * 0.6 - dy * 0.8) / (rr || 1), wd = 1.0 + 1.1 * Math.max(0, toward), edge = HOLE_R - rr;
    if (edge < wd) tone -= 1.8 * (1 - clamp(edge / wd));
    let col = specCol(band, tone);
    // the crescent of light, upper right, tapering at both ends
    const ang = (Math.atan2(-dy, dx) * 180) / Math.PI;
    if (ang > 22 && ang < 78) {
      const k = Math.sin(((ang - 22) / 56) * Math.PI), inner = 6.1 + 0.4 * k, outer = inner + 0.9 + 1.5 * k;
      if (rr >= inner && rr <= outer) col = toHex(mix(specCol(band, 5), '#ffffff', 0.85));
    }
    // a faint bounce arc, lower left, and one glint pixel upper left
    if (ang < -118 && ang > -152 && rr > 7.0 && rr < 8.0) col = specCol(band, tone + 1.5);
    if (Math.abs(dx + 4.5) < 0.6 && Math.abs(dy + 5.5) < 0.6) col = toHex(mix(specCol(band, 5), '#ffffff', 0.7));
    v.set(x, y, col);
  }
}

// ---- gunmetal pieces ----------------------------------------------------------------------------------------------
function rivet(v, cx, cy) {                    // a chrome dome: lit upper right, dark rim
  for (let y = -2; y <= 1; y++) for (let x = -2; x <= 1; x++) {
    const r = Math.hypot(x + 0.5, y + 0.5);
    if (r > 2.1) continue;
    v.set(cx + x, cy + y, r > 1.6 ? CHR[1] : (x + 0.5) - (y + 0.5) > 0.4 ? CHR[6] : CHR[3]);
  }
}
// the padded bridge: a quilted pad with rounded corners, a stitched inset seam, a lit top and right edge
function paintPad(v, f) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s;
    if (f === 'south') {
      const corner = (x === 0 || x === v.w - 1) && (y === 0 || y === v.h - 1);
      if (corner) continue;
      const ex = Math.min(x, v.w - 1 - x), ey = Math.min(y, v.h - 1 - y);
      s = 2.6 + 0.7 * (x / (v.w - 1)) - 0.5 * (y / (v.h - 1));
      if (y === 0) s = 4.6; else if (x === v.w - 1) s = 4.0; else if (y === v.h - 1) s = 0.7; else if (x === 0) s = 1.5;
      else if (ex === 2 && ey >= 2 || ey === 2 && ex >= 2) s = (x + y) % 2 ? 1.1 : 3.4;           // dashed stitching with a lit thread
      else if (ey === 1 || ex === 1) s -= 0.5;                                                       // the pad's rolled edge
    } else if (f === 'up') s = y === v.h - 1 ? 4.8 : 3.8;
    else if (f === 'down') s = 0.8;
    else s = f === 'east' ? 3.0 : 1.4;
    v.set(x, y, gun(s));
  }
  if (f === 'south') rivet(v, 7, 5);
}
function paintBracket(v, f, sx) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    let s = f === 'up' ? 4.0 : 2.7;
    if (f === 'south') {
      s = 2.7 + 0.7 * (x / (v.w - 1)) * (sx > 0 ? 1 : 0.6) - 0.5 * (y / (v.h - 1));
      if (y === 0) s = 5.0; else if (y === v.h - 1) s = 0.7; else if (x === (sx > 0 ? v.w - 1 : 0)) s = 1.0; else if (x === (sx > 0 ? 0 : v.w - 1)) s = 4.2;
    } else if (f === 'east' || f === 'west') s = y === 0 ? 4.6 : y === v.h - 1 ? 0.7 : 2.4;
    else if (y === v.h - 1) s = 4.9;
    v.set(x, y, gun(s));
  }
  if (f === 'south') rivet(v, sx > 0 ? 8 : 5, 5);
}
// black webbing: a lit top edge, dashed stitch lines near both edges, a fine twill, a reflective chrome centre thread
function paintStrap(v, f, lit) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    if (f === 'up') { v.set(x, y, web(2.2 + lit * 0.3)); continue; }
    let s = lit - 0.7 + (((x + y * 2) & 3) < 2 ? 0.45 : 0);
    if (y === 0) s = lit + 0.9;
    else if (y === 1 || y === 10) s = x % 3 === 0 ? lit - 1.6 : lit + 0.2;           // stitch dashes
    else if (y === 11) s = 0.4;
    else if (y === 5) { v.set(x, y, chrome(2.8 + lit * 0.5 + (hash2(x >> 2, 3, 9) > 0.8 ? 1.6 : 0))); continue; }   // reflective thread, a few glints
    else if (y === 4 || y === 6) s -= 0.9;
    v.set(x, y, web(s));
  }
}
// slide buckle: a gunmetal frame around a window onto the strap, a bar through the middle
function paintBuckle(v, f) {
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    if (f !== 'north') { v.set(x, y, gun(f === 'up' ? 4.4 : 2.0)); continue; }
    const ex = Math.min(x, v.w - 1 - x), ey = Math.min(y, v.h - 1 - y);
    let s = 2.6 + 0.5 * (1 - x / (v.w - 1)) - 0.5 * (y / (v.h - 1));
    if (ex >= 2 && ey >= 2 && !(x >= 6 && x <= 7)) { v.set(x, y, web(1.4)); continue; }            // window: the strap beneath, in shadow
    if (y === 0) s = 5.2; else if (ey === 0 || ex === 0) s = 0.9;
    else if (ey === 1 || ex === 1) s += 0.9;
    v.set(x, y, gun(s));
  }
}

// ---- the glint (additive, on the glow planes) -------------------------------------------------------------------
// A slanted band sweeps across the pair; then a four-point sparkle blinks on the left lens (the player's left) upper right.
function paintGlint(tex, frame) {
  for (const f of faces) {
    if (f.name !== 'south' || !f.cube.glow) continue;
    const sx = f.cube._sx, r = f.rect;
    const star = (dx, dy, k) => {                                      // sparkle shape: arms of length k, diagonals shorter
      const ax = Math.abs(dx), ay = Math.abs(dy);
      if (ax <= 0.6 && ay <= 0.6) return 1;
      if ((ax <= 0.6 && ay <= k) || (ay <= 0.6 && ax <= k)) return 0.85 * (1 - Math.max(ax, ay) / (k + 1));
      if (Math.abs(ax - ay) <= 0.6 && ax <= k * 0.45) return 0.5;
      return 0;
    };
    for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
      const { dx, dy, xg } = lensXY(x, y, sx), rr = Math.hypot(dx, dy);
      if (rr > HOLE_R - 0.4) continue;
      let k = 0;
      if (frame < SWEEP) {
        const p = -32 + (64 * (frame + 0.5)) / SWEEP, d = xg - 0.55 * dy - p;
        if (d >= -1 && d < 1) k = 0.7; else if (d >= -3.5 && d < -1) k = 0.34; else if (d >= -7 && d < -3.5) k = 0.14; else if (d >= 3 && d < 4) k = 0.15;
      } else if (frame < SWEEP + 4 && sx > 0) {
        const arm = [3, 6, 4.5, 2][frame - SWEEP];
        k = star(dx - 4, dy + 4.5, arm) * (0.95 - 0.1 * (frame - SWEEP));
      }
      if (k) tex.set(r.x + x, r.y + y, [255, 255, 255, Math.round(clamp(k) * 255)]);
    }
  }
}

// ---- texture ----------------------------------------------------------------------------------------------------
let staticTex = null;
function staticLayer() {
  if (staticTex) return staticTex;
  const t = new Canvas(ATLAS_W, ATLAS_H);
  for (const f of faces) if (f.fn) f.fn(t.view(f.rect.x, f.rect.y, f.rect.w, f.rect.h), f.name);
  return (staticTex = t);
}

export default {
  meta: { id: 'hat-goggles', name: 'Pilot Goggles', kind: 'hat', cents: 699, blurb: 'Retro pilot goggles pushed up on your forehead: mirrored lenses with a pink-to-lime sheen in chrome rims, a padded bridge and a glint that sweeps by.' },
  model: {
    texSize: TEX, scale: S,
    bones: [
      { name: 'strap', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: BONES.strap.map(clean) },
      { name: 'lens', pivot: TILT_PIVOT, rotation: [TILT, 0, 0], cubes: BONES.lens.map(clean) },
    ],
  },
  anim: { frames: FRAMES, fps: FPS },
  paint(frame = 0) {
    const t = staticLayer().clone();
    paintGlint(t, frame);
    return t;
  },
};
