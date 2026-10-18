// Golden Halo: a classic polished gold ring floating above the head, tipped back a little so the
// front shows its top face (the angel-halo read), with a soft warm glow and a slow float.
//
// The ring is a 32-sided torus of small box segments (0.75 x 0.75 px tube). Every texel is lit as if it
// sat on a TRUE round tube: from its position we take the ring angle and the angle around the tube,
// build the surface normal, and pick from a 4-step hue-shifted gold ramp (cool red-brown shadow ->
// warm yellow light) plus a pale specular. Hard bands, no dither: clean metal.
// The glow is ONE additive plane in the ring's own plane, painted as a soft annulus.
// Clearance (FORMAT.md): halo, y 10..13, tube 0.75 px thick (limit 1.5).
import { clamp } from '../../lib/px.mjs';
import { layoutCubes, newTexture, paintCube } from '../../lib/model.mjs';

const S = 8, TEX = [48, 16];
const YC = 11.5, R = 4.0, T = 0.75, N = 32, TILT = 11;          // ring centre y, centre-line radius, tube size, segments, back tilt (deg)
const L = 0.875;                                                // segment length: the outer chord, rounded up to whole texels
const GLOW_R = 5.5;                                             // the glow plane is 11 x 11 px

// ---- geometry: segment k sits at ring angle a_k, built along z at x = R and turned about y -------
const segs = [];
for (let k = 0; k < N; k++) {
  const a = (360 * k) / N;
  segs.push({ from: [R - T / 2, YC - T / 2, -L / 2], to: [R + T / 2, YC + T / 2, L / 2], uv: [0, 0], emissive: true, rotation: { origin: [0, YC, 0], angle: [0, +a.toFixed(4), 0] }, _a: a });
}
layoutCubes(segs, TEX, S);
const GV = 5;                                                   // glow plane texture row (model px), below the segments
const glow = { from: [-GLOW_R, YC, -GLOW_R], to: [GLOW_R, YC, GLOW_R], uv: [0, 0], glow: true, faces: { up: [0, GV, 2 * GLOW_R, GV + 2 * GLOW_R], down: [0, GV, 2 * GLOW_R, GV + 2 * GLOW_R] } };

// ---- lighting ----------------------------------------------------------------------------------
const norm = v => { const l = Math.hypot(...v) || 1; return v.map(x => x / l); };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const LV = norm([0.42, 0.72, 0.55]);                            // top, front (+z), the player's left (+x)
const HV = norm([LV[0], LV[1] + 0.3, LV[2] + 1]);               // half vector for a viewer in front, a touch above
const rad = d => (d * Math.PI) / 180;
const tiltX = v => { const c = Math.cos(rad(TILT)), s = Math.sin(rad(TILT)); return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c]; };   // back edge up

// Box-UV texel -> cube-local point (centre of the texel on the face surface).
function local(c, f, x, y) {
  const [x0, y0, z0] = c.from, [x1, y1, z1] = c.to, u = (x + 0.5) / S, v = (y + 0.5) / S;
  switch (f) {
    case 'south': return [x0 + u, y1 - v, z1];
    case 'north': return [x1 - u, y1 - v, z0];
    case 'east': return [x1, y1 - v, z1 - u];
    case 'west': return [x0, y1 - v, z0 + u];
    case 'up': return [x1 - u, y1, z1 - v];
    default: return [x1 - u, y0, z0 + v];
  }
}
// The round-tube normal at a texel: ring angle from where the texel sits along the segment, tube angle
// from where it sits on the square cross-section (so the corners shade like the round tube they stand for).
function tubeNormal(c, p) {
  const th = rad(c._a) - Math.atan2(p[2], R);
  const phi = Math.atan2((p[1] - YC) / (T / 2), (p[0] - R) / (T / 2));
  const radial = [Math.cos(th), 0, -Math.sin(th)];
  return tiltX(norm([radial[0] * Math.cos(phi), Math.sin(phi), radial[2] * Math.cos(phi)]));
}

// 4-step gold, hue-shifted: shadow leans red-brown, light leans lemon. SPEC is the polished glint.
const GOLD = ['#5a2a17', '#a65a1c', '#e09a2e', '#f7d264'], SPEC = '#fff4c4';
function goldAt(n) {
  const d = Math.max(0, dot(n, LV)), sky = 0.5 + 0.5 * n[1], sp = Math.pow(Math.max(0, dot(n, HV)), 48);
  if (sp > 0.55) return SPEC;
  let v = 0.06 + 0.58 * d + 0.32 * sky;
  if (n[1] < -0.82) v += 0.16;                                  // warm bounce light right under the tube: reads as metal, not plastic
  return GOLD[v < 0.36 ? 0 : v < 0.55 ? 1 : v < 0.76 ? 2 : 3];
}

function paintGlow(t) {
  const v = t.view(0, GV * S, 2 * GLOW_R * S, 2 * GLOW_R * S), c = GLOW_R * S;
  for (let y = 0; y < v.h; y++) for (let x = 0; x < v.w; x++) {
    const r = Math.hypot(x + 0.5 - c, y + 0.5 - c) / S, d = r - R;
    const a = Math.exp(-((d / (d < 0 ? 0.55 : 0.8)) ** 2)) * 0.42;   // a soft band hugging the ring, wider outside
    if (a < 0.02) continue;
    v.set(x, y, [255, 178, 74, Math.round(a * 255)]);
  }
}

let art = null;
function paintAll() {
  if (art) return art;
  const t = newTexture({ texSize: TEX, scale: S });
  for (const c of segs) paintCube(t, c, S, (v, f) => v.paint((x, y) => goldAt(tubeNormal(c, local(c, f, x, y)))));
  paintGlow(t);
  return (art = t);
}

const strip = ({ _a, ...c }) => c;
export default {
  meta: { id: 'halo-gold', name: 'Golden Halo', kind: 'halo', cents: 599, blurb: 'A classic polished gold halo with a warm glow, floating just above your head.' },
  model: {
    texSize: TEX, scale: S,
    bones: [{ name: 'halo', pivot: [0, YC, 0], rotation: [TILT, 0, 0], cubes: [...segs.map(strip), glow] }],
  },
  // a slow float and the faintest rock; stays inside y 10..13 at the extremes
  anim: { frames: 1, fps: 1, motion: [
    { bone: 'halo', channel: 'pos', axis: 'y', amp: 0.25, period: 3.2, phase: 0 },
    { bone: 'halo', channel: 'rot', axis: 'z', amp: 2, period: 4.8, phase: 0.25 },
  ] },
  paint() { return paintAll(); },
};
