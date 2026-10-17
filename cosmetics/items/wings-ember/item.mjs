// Ember Wings: obsidian-boned wings, charred leather membranes lit from inside by a
// network of glowing veins whose heat pulses outward from the shoulders.
// Animated: two segments per wing (arm + fingers, the fingers lag), a 6-frame texture
// loop for the vein pulse, and a few drifting embers. Both faces of every membrane are drawn.
//
// Texture (scale 8, 8 texture px per model px, 6 frames x 320x336):
//   row 1  membrane + vein slab of each segment (the left wing's art, the right wing flips it)
//   row 2  additive glow planes
//   below  the bone cubes (arm, fingers, claw)
// Geometry is authored for the LEFT wing (+x) in the body frame; the right is its mirror.
import { Canvas, ramp, clamp, lerp, smoothstep, hash2, noise2, bayer, ditherIndex, rgba } from '../../lib/px.mjs';
import { footprint, layoutCubes, newTexture, paintCube, LIGHT } from '../../lib/model.mjs';

const S = 8, TEX = [40, 42], FRAMES = 6, FPS = 6, PERIOD = 2.0;
const BASE_YAW = 18;                                            // wings rest swept back this far (deg)

// ---- the planar wing (model px, body frame, left wing, before the sweep) -----------
const P = {
  S: [2.4, -1.0], E: [6.6, 5.0], Wr: [10.8, 3.4],
  F: [[13.5, 0.0], [13.0, -5.4], [10.8, -9.8], [7.8, -11.6]],      // fingertips, leading edge first
  B: [3.2, -9.0],
};
const ZI = -2.9, ZO = -3.15;                                    // z of the inner and outer membranes (outer sits behind)
const TIP = 0.7;                                                // the finger bones poke this far past the membrane

// Trailing edge between two fingertips: a concave scallop pulled toward the wrist.
function scallop(a, b, toward, depth, n = 18) {
  const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2, dx = toward[0] - mx, dy = toward[1] - my, d = Math.hypot(dx, dy) || 1;
  const k = Math.hypot(b[0] - a[0], b[1] - a[1]) * depth, c = [mx + (dx / d) * k, my + (dy / d) * k], pts = [];
  for (let i = 1; i < n; i++) { const t = i / n; pts.push([(1 - t) ** 2 * a[0] + 2 * (1 - t) * t * c[0] + t * t * b[0], (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * c[1] + t * t * b[1]]); }
  return pts;
}
const SEAM = 0.8;                                               // the inner membrane runs this far under the outer one
const [F1, F2, F3, F4] = P.F;
const trailI = scallop(F4, P.B, P.Wr, 0.26);
const trailO = [F1, ...scallop(F1, F2, P.Wr, 0.3), F2, ...scallop(F2, F3, P.Wr, 0.3), F3, ...scallop(F3, F4, P.Wr, 0.3), F4];
const OUT_I = [P.S, P.E, P.Wr, [P.Wr[0] + SEAM, P.Wr[1] - 0.2], [F4[0] + SEAM, F4[1]], F4, ...trailI, P.B];
const OUT_O = [P.Wr, ...trailO];
const TRAIL_I = [F4, ...trailI, P.B], TRAIL_O = trailO;
const BONES = [[P.S, P.E], [P.E, P.Wr]];
const FINGERS = P.F.map(f => [P.Wr, f]);

// Bounding rectangles (model px, multiples of 1/8) of the planes; sizes are whole texture px.
const floor8 = v => Math.floor(v * S) / S, ceil8 = v => Math.ceil(v * S) / S;
function boxOf(pts, m) {
  const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
  return { x0: floor8(Math.min(...xs) - m), x1: ceil8(Math.max(...xs) + m), y0: floor8(Math.min(...ys) - m), y1: ceil8(Math.max(...ys) + m) };
}
const bI = boxOf(OUT_I, 0.125), bO = boxOf(OUT_O, 0.125), gI = boxOf(OUT_I, 1), gO = boxOf(OUT_O, 1);
const R = {
  memI: { ...bI, z: ZI }, memO: { ...bO, z: ZO }, veinI: { ...bI, z: ZI }, veinO: { ...bO, z: ZO },
  glowI: { ...gI, z: ZI + 0.4 }, glowO: { ...gO, z: ZO + 0.4 },
};
for (const r of Object.values(R)) { r.w = Math.round((r.x1 - r.x0) * S); r.h = Math.round((r.y1 - r.y0) * S); }
{ // row 1: membranes and vein slabs; row 2: glow planes; the bone cubes go below (BONES_Y)
  let x = 0; for (const k of ['memI', 'memO', 'veinI', 'veinO']) { R[k].tx = x; R[k].ty = 0; x += R[k].w; }
  const row1 = Math.max(R.memI.h, R.memO.h); x = 0;
  for (const k of ['glowI', 'glowO']) { R[k].tx = x; R[k].ty = row1; x += R[k].w; }
  var BONES_Y = row1 + Math.max(R.glowI.h, R.glowO.h) + 2;
}

// ---- cubes -------------------------------------------------------------------------
const rad2deg = r => (r * 180) / Math.PI;
function limb(name, a, b, t, z, side, extend = 0) {
  const dx = b[0] - a[0], dy = b[1] - a[1], d = Math.hypot(dx, dy), len = Math.round((d + extend) * S) / S, phi = rad2deg(Math.atan2(dy, dx));
  if (side > 0) return { name, from: [a[0], a[1] - t / 2, z - t / 2], to: [a[0] + len, a[1] + t / 2, z + t / 2], rotation: { origin: [a[0], a[1], z], angle: [0, 0, +phi.toFixed(3)] } };
  return { name, from: [-a[0] - len, a[1] - t / 2, z - t / 2], to: [-a[0], a[1] + t / 2, z + t / 2], mirror: true, rotation: { origin: [-a[0], a[1], z], angle: [0, 0, -phi.toFixed(3)] } };
}
// A flat, two-faced plane over a region. Left wing: north reads as painted, south flips; right is the reverse.
function plane(name, reg, side, extra = {}) {
  const u0 = reg.tx, u1 = reg.tx + reg.w, v0 = reg.ty, v1 = reg.ty + reg.h, q = n => n / S;
  const [a, b] = side > 0 ? [reg.x0, reg.x1] : [-reg.x1, -reg.x0];
  const t = extra.thick || 0;
  return {
    name, from: [a, reg.y0, reg.z - t / 2], to: [b, reg.y1, reg.z + t / 2],
    faces: side > 0 ? { north: [q(u0), q(v0), q(u1), q(v1)], south: [q(u1), q(v0), q(u0), q(v1)] } : { north: [q(u1), q(v0), q(u0), q(v1)], south: [q(u0), q(v0), q(u1), q(v1)] },
    ...(extra.emissive ? { emissive: true } : {}), ...(extra.glow ? { glow: true } : {}),
  };
}
const BONE_TH = { humerus: 1.25, forearm: 1, claw: 0.5, f1: 0.5, f2: 0.5, f3: 0.5, f4: 0.5 };
const CLAW_TIP = [12.0, 5.2];
const leftBones = side => [
  limb('humerus', P.S, P.E, BONE_TH.humerus, ZI, side), limb('forearm', P.E, P.Wr, BONE_TH.forearm, ZI, side),
  limb('claw', P.Wr, CLAW_TIP, BONE_TH.claw, ZO, side),
  ...FINGERS.map(([a, b], i) => limb(`f${i + 1}`, a, b, 0.5, ZO, side, TIP)),
];
// pack the bone cubes below the planes (the left wing defines the UV, the mirror reuses it)
const boneL = leftBones(+1), boneR = leftBones(-1);
layoutCubes(boneL, [TEX[0], 10], S);
boneL.forEach((c, i) => { c.uv = [c.uv[0], c.uv[1] + BONES_Y / S]; boneR[i].uv = c.uv; });
TEX[1] = Math.ceil(Math.max(...boneL.map(c => footprint(c, S).y + footprint(c, S).h)) / S);
const strip = ({ name, ...c }) => c;

function wing(side) {
  const k = side > 0 ? 'L' : 'R', bones = side > 0 ? boneL : boneR, sx = side;
  const inner = [
    plane(`mem${k}`, R.memI, side), plane(`veins${k}`, R.veinI, side, { emissive: true, thick: 0.25 }), plane(`glow${k}`, R.glowI, side, { glow: true }),
    bones[0], bones[1],
  ];
  const outer = [
    plane(`memO${k}`, R.memO, side), plane(`veinsO${k}`, R.veinO, side, { emissive: true, thick: 0.25 }), plane(`glowO${k}`, R.glowO, side, { glow: true }),
    ...bones.slice(2),
  ];
  return [
    { name: `wing${k}`, pivot: [sx * P.S[0], P.S[1], ZI], rotation: [0, sx * BASE_YAW, 0], cubes: inner.map(strip) },
    { name: `wing${k}2`, parent: `wing${k}`, pivot: [sx * P.Wr[0], P.Wr[1], ZO], rotation: [0, 0, 0], cubes: outer.map(strip) },
  ];
}
const bones = [...wing(+1), ...wing(-1)];

// ---- motion ---------------------------------------------------------------------------
const sweep = (bone, s, lag) => ({ bone, channel: 'rot', axis: 'y', amp: s * (lag ? 8 : 5), walkAmp: s * (lag ? 8 : 7), airAmp: s * (lag ? 20 : 22), period: PERIOD, phase: lag ? -0.14 : 0 });
const lift = (bone, s, lag) => ({ bone, channel: 'rot', axis: 'z', amp: s * (lag ? 4 : 2.5), walkAmp: s * (lag ? 4 : 3), airAmp: s * (lag ? 12 : 10), period: PERIOD, phase: lag ? 0.11 : 0.25 });
const motion = [
  sweep('wingL', 1, 0), sweep('wingL2', 1, 1), lift('wingL', 1, 0), lift('wingL2', 1, 1),
  sweep('wingR', -1, 0), sweep('wingR2', -1, 1), lift('wingR', -1, 0), lift('wingR2', -1, 1),
];

// ---- colour ramps ---------------------------------------------------------------------
const skin = ramp('#2b1a20', 9, { lo: 0.03, hi: 0.4, shadowHue: 290, lightHue: 12, shift: 0.55, tint: 0.1 });
const skinHot = ramp('#6b2a14', 9, { lo: 0.05, hi: 0.5, shadowHue: 345, lightHue: 30, shift: 0.5, tint: 0.3 });
const ember = ramp('#ff6418', 9, { lo: 0.16, hi: 0.94, shadowHue: 352, lightHue: 52, shift: 0.85, tint: 0.5, satHi: 1.1, satLo: 1.0 });
const rock = ramp('#3a2c33', 8, { lo: 0.03, hi: 0.5, shadowHue: 280, lightHue: 18, shift: 0.45, tint: 0.08 });

// ---- geometry helpers ---------------------------------------------------------------------
const inPoly = (pts, x, y) => {
  let c = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const [xi, yi] = pts[i], [xj, yj] = pts[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) c = !c;
  }
  return c;
};
const segDist = (x, y, a, b) => {
  const dx = b[0] - a[0], dy = b[1] - a[1], t = clamp(((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy));
  return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy));
};
const polyDist = (pts, x, y) => { let d = 1e9; for (let i = 0; i + 1 < pts.length; i++) d = Math.min(d, segDist(x, y, pts[i], pts[i + 1])); return d; };

// A veined sample at model point (x, y) of one membrane: elongated cells grown outward from `root`
// (a jittered grid in radius/angle), cell borders are veins that thin toward the edge.
function makeVeins(root, cellR, cellA, seed, keep = 0.62) {
  const feat = (i, j) => {
    const a = (i + 0.15 + 0.7 * hash2(i, j, seed)) * cellR, b = (j + 0.15 + 0.7 * hash2(j, i, seed + 5)) / cellA;
    return [root[0] + a * Math.cos(b), root[1] + a * Math.sin(b)];
  };
  const fn = (x, y) => {
    const dx = x - root[0], dy = y - root[1], r = Math.hypot(dx, dy), th = Math.atan2(dy, dx);
    const ci = Math.floor(r / cellR), cj = Math.floor(th * cellA);
    let d1 = 1e9, d2 = 1e9, k1 = 0, k2 = 0;
    for (let i = ci - 1; i <= ci + 1; i++) for (let j = cj - 1; j <= cj + 1; j++) {
      if (i < 0) continue;
      const f = feat(i, j), d = Math.hypot(x - f[0], y - f[1]), k = i * 97 + j;
      if (d < d1) { d2 = d1; k2 = k1; d1 = d; k1 = k; } else if (d < d2) { d2 = d; k2 = k; }
    }
    // some cell borders are left dark: fewer, more purposeful veins
    const alive = hash2(Math.min(k1, k2), Math.max(k1, k2), seed + 77) < keep;
    return { edge: alive ? (d2 - d1) / 2 : 9, d1 };
  };
  fn.root = root;
  return fn;
}
const veinI = makeVeins(P.S, 3.4, 3.0, 11, 0.8), veinO = makeVeins(P.Wr, 3.2, 4.0, 29, 0.8);

// Per-region sample table: for each texture pixel its model point, whether it is inside the
// membrane, and the vein profile (0 none .. 1 core) with the radius from the shoulder.
function sampleRegion(reg, outline, veinFn, bonesList, trail) {
  const n = reg.w * reg.h, inside = new Uint8Array(n), prof = new Float32Array(n), rr = new Float32Array(n), dEdge = new Float32Array(n), dBone = new Float32Array(n);
  for (let v = 0; v < reg.h; v++) for (let u = 0; u < reg.w; u++) {
    const i = v * reg.w + u, x = reg.x1 - (u + 0.5) / S, y = reg.y1 - (v + 0.5) / S;
    rr[i] = Math.hypot(x - P.S[0], y - P.S[1]);
    if (!inPoly(outline, x, y)) { dEdge[i] = -1; continue; }
    inside[i] = 1;
    dEdge[i] = polyDist(trail, x, y) * S;
    let db = 1e9; for (const [a, b] of bonesList) db = Math.min(db, segDist(x, y, a, b));
    dBone[i] = db * S;
    const { edge } = veinFn(x, y), thick = lerp(2.5, 0.9, clamp((rr[i] - 2) / 12)) / S;
    let p = edge < thick / 2 ? 1 - edge / (thick / 2) : 0;
    if (db * S > 2.8 && db * S < 4.4) p = Math.max(p, 0.5);               // a thin glowing seam beside every bone
    const rootD = Math.hypot(x - veinFn.root[0], y - veinFn.root[1]);
    prof[i] = p * smoothstep(2.2, 4.6, rootD);                              // no vein knots right at the joint
  }
  return { reg, inside, prof, rr, dEdge, dBone };
}
const samples = {
  I: sampleRegion(R.memI, OUT_I, veinI, [FINGERS[3]], TRAIL_I),
  O: sampleRegion(R.memO, OUT_O, veinO, FINGERS, TRAIL_O),
};
// the veins glow brightest where the pulse is: a wave of heat moving outward from the shoulder
const WL = 14;
const pulse = (r, f) => 0.5 + 0.5 * Math.cos(2 * Math.PI * (r / WL - f / FRAMES));

// Distance (texture px) from each pixel to the nearest vein pixel, for the warm light the veins throw on the skin.
function veinDistance(smp, maxD = 14) {
  const { reg, prof } = smp, n = reg.w * reg.h, out = new Float32Array(n).fill(maxD + 1);
  const pts = []; for (let i = 0; i < n; i++) if (prof[i] > 0.3) pts.push(i);
  for (const i of pts) {
    const px = i % reg.w, py = (i / reg.w) | 0;
    for (let y = Math.max(0, py - maxD); y <= Math.min(reg.h - 1, py + maxD); y++) for (let x = Math.max(0, px - maxD); x <= Math.min(reg.w - 1, px + maxD); x++) {
      const d = Math.hypot(x - px, y - py), j = y * reg.w + x; if (d < out[j]) out[j] = d;
    }
  }
  return out;
}
samples.I.heat = veinDistance(samples.I); samples.O.heat = veinDistance(samples.O);

// ---- painters -----------------------------------------------------------------------------
function paintMembrane(tex, smp, seed) {
  const { reg, inside, rr, dEdge, dBone, heat } = smp, v = tex.view(reg.tx, reg.ty, reg.w, reg.h);
  for (let y = 0; y < reg.h; y++) for (let x = 0; x < reg.w; x++) {
    const i = y * reg.w + x; if (!inside[i]) continue;
    const gx = reg.tx + x, gy = reg.ty + y;
    const skinN = noise2(x * 0.22, y * 0.22, seed) * 0.5 + noise2(x * 0.7, y * 0.7, seed + 3) * 0.25;       // stretched-skin mottling
    const wrinkle = Math.sin((x + y * 0.35) * 0.55 + noise2(x * 0.08, y * 0.08, seed + 9) * 5) * 0.5 + 0.5;
    const heatK = Math.exp(-heat[i] / 4.2) * 0.8;
    let t = 0.2 + 0.2 * skinN + 0.06 * wrinkle + 0.1 * (1 - dEdge[i] / 60 > 0 ? 1 - Math.min(1, dEdge[i] / 60) : 0) - 0.05 * Math.min(1, dBone[i] / 10);
    t += 0.22 * heatK;
    const idx = ditherIndex(clamp(t), skin.length, bayer(gx, gy, 4));
    const hot = ditherIndex(clamp(heatK * 0.95), 4, bayer(gx + 1, gy + 2, 4)) > 0 && heatK > 0.28;
    v.set(x, y, hot ? skinHot[clamp(idx + 1, 0, skinHot.length - 1)] : skin[idx]);
  }
  // selective outline: the silhouette goes dark, the trailing edge scorches
  const edge = v.clone();
  for (let y = 0; y < reg.h; y++) for (let x = 0; x < reg.w; x++) {
    const i = y * reg.w + x; if (!inside[i]) continue;
    const near = (dx, dy) => { const xx = x + dx, yy = y + dy; return xx < 0 || yy < 0 || xx >= reg.w || yy >= reg.h || !inside[yy * reg.w + xx]; };
    if (near(1, 0) || near(-1, 0) || near(0, 1) || near(0, -1)) v.set(x, y, skin[0]);
    else if (near(2, 0) || near(-2, 0) || near(0, 2) || near(0, -2)) v.set(x, y, skin[1]);
  }
  void edge;
}
function paintVeins(tex, smp, f, out) {
  const { reg, inside, prof, rr, dEdge } = smp, v = tex.view(out.tx, out.ty, out.w, out.h);
  for (let y = 0; y < reg.h; y++) for (let x = 0; x < reg.w; x++) {
    const i = y * reg.w + x; if (!inside[i]) continue;
    const pu = pulse(rr[i], f);
    let b = 0;
    if (prof[i] > 0) b = (0.3 + 0.7 * pu) * (0.5 + 0.5 * prof[i]);
    // the trailing edge smoulders: a dim rim that breathes with the pulse
    if (dEdge[i] < 2.2) b = Math.max(b, 0.22 + 0.2 * pulse(rr[i], f) - dEdge[i] * 0.05);
    if (b < 0.14) continue;
    v.set(x, y, ember[clamp(Math.round(b * (ember.length - 1)), 0, ember.length - 1)]);
  }
}
function paintGlow(tex, smp, reg, f) {
  // blurred heat of the veins, spilling past the membrane edge
  const w = reg.w, h = reg.h, field = new Float32Array(w * h), sm = smp.reg;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const mx = reg.x1 - (x + 0.5) / S, my = reg.y1 - (y + 0.5) / S;
    const u = Math.floor((sm.x1 - mx) * S), vv = Math.floor((sm.y1 - my) * S);
    if (u < 0 || vv < 0 || u >= sm.w || vv >= sm.h) continue;
    const i = vv * sm.w + u; if (!smp.inside[i]) continue;
    let b = smp.prof[i] > 0 ? (0.3 + 0.7 * pulse(smp.rr[i], f)) * smp.prof[i] : 0;
    if (smp.dEdge[i] < 2.2) b = Math.max(b, 0.3);
    field[y * w + x] = b;
  }
  const blur = (src, sigma) => {
    const r = Math.ceil(sigma * 2.5), k = []; let sum = 0;
    for (let i = -r; i <= r; i++) { const g = Math.exp(-(i * i) / (2 * sigma * sigma)); k.push(g); sum += g; }
    const tmp = new Float32Array(w * h), out = new Float32Array(w * h);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { let a = 0; for (let i = -r; i <= r; i++) { const xx = x + i; if (xx >= 0 && xx < w) a += src[y * w + xx] * k[i + r]; } tmp[y * w + x] = a / sum; }
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { let a = 0; for (let i = -r; i <= r; i++) { const yy = y + i; if (yy >= 0 && yy < h) a += tmp[yy * w + x] * k[i + r]; } out[y * w + x] = a / sum; }
    return out;
  };
  const near = blur(field, 4), far = blur(field, 9), v = tex.view(reg.tx, reg.ty, reg.w, reg.h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const a = clamp(near[y * w + x] * 1.15 + far[y * w + x] * 1.0, 0, 0.5);
    if (a < 0.03) continue;
    // quantise to a few levels so the glow stays pixel-art
    const q = Math.round(a * 9) / 9, c = ember[clamp(Math.round(2 + q * 8), 0, ember.length - 1)];
    const [r, g, b] = rgba(c); v.set(x, y, [r, g, b, Math.round(q * 255)]);
  }
}
function paintBone(v, f, name) {
  const L = LIGHT[f], th = BONE_TH[name] ?? 0.5;
  v.paint((x, y) => {
    const along = f === 'east' || f === 'west' ? 0 : x;
    let t = 0.2 + 0.55 * L + 0.14 * Math.sin((along / 7) + y * 0.3) * (th > 0.6 ? 1 : 0.4);
    t -= 0.18 * smoothstep(0.55, 1, (y + 0.5) / v.h);                    // rounded: the underside falls into shadow
    if (y === 0) t += 0.12;
    const knuckle = (along % 24) < 2 && th > 0.4;                         // ring bands like jointed bone
    if (knuckle) t -= 0.14;
    return rock[clamp(Math.round(t * (rock.length - 1)), 0, rock.length - 1)];
  });
  // cracks that still glow a little
  for (let y = 1; y < v.h - 1; y++) for (let x = 0; x < v.w; x++) {
    if (f === 'east' || f === 'west' || f === 'down') continue;
    if (hash2(x >> 1, y, name.length * 13) > 0.972 && (x % 24) > 3) { v.set(x, y, ember[3]); if (th > 0.6 && x + 1 < v.w) v.set(x + 1, y, ember[2]); }
  }
}

let staticArt = null;
function staticLayer() {
  if (staticArt) return staticArt;
  const t = newTexture({ texSize: TEX, scale: S });
  paintMembrane(t, samples.I, 41); paintMembrane(t, samples.O, 73);
  for (const c of boneL) paintCube(t, { ...c, uv: c.uv }, S, (v, f) => paintBone(v, f, c.name));
  return (staticArt = t);
}

export default {
  meta: { id: 'wings-ember', name: 'Ember Wings', kind: 'wings', cents: 999, hidesCape: true, blurb: 'Obsidian wings with fire running through the veins. Hides your cape; a few embers drift off as you move.' },
  model: { texSize: TEX, scale: S, bones },
  anim: { frames: FRAMES, fps: FPS, motion },
  fx: [{ bone: 'body', at: [0, -5, -3.8], spread: [22, 14, 1], color: '#ff7a2a', color2: '#ffd27a', perSecond: 5, life: [1.4, 2.4], size: 0.5, rise: 3, drift: [0, 0, -1.2], glow: true }],
  paint(f) {
    const t = staticLayer().clone();
    paintVeins(t, samples.I, f, R.veinI); paintVeins(t, samples.O, f, R.veinO);
    paintGlow(t, samples.I, R.glowI, f); paintGlow(t, samples.O, R.glowO, f);
    return t;
  },
};
