// Ember Wings (try 2): obsidian-boned dragon wings, charred leather cells that warm from char to
// ember toward the shoulder, a few cracks lit from inside near the bones, and drifting embers.
// Animated: two segments per wing (arm + fingers, the fingers lag) flap 10-15 degrees up and down
// plus a small sweep; a 6-frame texture loop pulses the lit cracks outward from the shoulder.
// Both faces of every membrane are drawn.
//
// Texture (scale 4, 4 texture px per model px, 6 frames):
//   row 1  membrane + lit-crack slabs of each segment (the left wing's art, the right wing flips it)
//   below  the bone cubes (arm, fingers, claw) and their knuckles
// Geometry is authored for the LEFT wing (+x) in the body frame; the right is its mirror.
// Clearance (FORMAT.md): nothing above y = +6 in any frame, at most 14 px either side of the spine.
import { ramp, clamp, lerp, smoothstep, hash2, noise2, bayer } from '../../lib/px.mjs';
import { footprint, layoutCubes, newTexture, paintCube, LIGHT } from '../../lib/model.mjs';

const S = 4, TEX = [40, 42], FRAMES = 6, FPS = 6, PERIOD = 1.5;
const BASE_YAW = 18;                                            // wings rest swept back this far (deg)
const REST_DROOP = -7;                                          // and drooped a little, so the up-flap stays below the head

// ---- the planar wing (model px, body frame, left wing, before the sweep) -----------
// Dropped ~4 px against try 1: the root sits low on the back and the tips hang to the hips.
const P = {
  S: [2.4, -3.0], E: [6.4, 1.2], Wr: [10.4, -0.4],
  F: [[13.4, -3.4], [12.8, -7.8], [10.6, -11.4], [7.6, -13.0]],    // fingertips, leading edge first
  B: [3.2, -10.8],
};
const ZI = -2.9, ZO = -3.15;                                    // z of the inner and outer membranes (outer sits behind)
const TIP = 1.0;                                                // the finger bones poke this far past the membrane

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
const trailO = [F1, ...scallop(F1, F2, P.Wr, 0.34), F2, ...scallop(F2, F3, P.Wr, 0.34), F3, ...scallop(F3, F4, P.Wr, 0.34), F4];
const OUT_I = [P.S, P.E, P.Wr, [P.Wr[0] + SEAM, P.Wr[1] - 0.2], [F4[0] + SEAM, F4[1]], F4, ...trailI, P.B];
const OUT_O = [P.Wr, ...trailO];
const TRAIL_I = [F4, ...trailI, P.B], TRAIL_O = trailO;
const ARM = [[P.S, P.E], [P.E, P.Wr]];
const FINGERS = P.F.map(f => [P.Wr, f]);

// Bounding rectangles (model px, multiples of 1/S) of the planes; sizes are whole texture px.
const floorS = v => Math.floor(v * S) / S, ceilS = v => Math.ceil(v * S) / S;
function boxOf(pts, m) {
  const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
  return { x0: floorS(Math.min(...xs) - m), x1: ceilS(Math.max(...xs) + m), y0: floorS(Math.min(...ys) - m), y1: ceilS(Math.max(...ys) + m) };
}
const bI = boxOf(OUT_I, 0.25), bO = boxOf(OUT_O, 0.25);
const R = { memI: { ...bI, z: ZI }, memO: { ...bO, z: ZO }, veinI: { ...bI, z: ZI }, veinO: { ...bO, z: ZO } };
for (const r of Object.values(R)) { r.w = Math.round((r.x1 - r.x0) * S); r.h = Math.round((r.y1 - r.y0) * S); }
{ // row 1: membranes and lit-crack slabs; the bone cubes go below (BONES_Y)
  let x = 0; for (const k of ['memI', 'memO', 'veinI', 'veinO']) { R[k].tx = x; R[k].ty = 0; x += R[k].w; }
  var BONES_Y = Math.max(...Object.values(R).map(r => r.h)) + 2;
}

// ---- cubes -------------------------------------------------------------------------
const rad2deg = r => (r * 180) / Math.PI;
function limb(name, grp, a, b, t, z, side, extend = 0) {
  const dx = b[0] - a[0], dy = b[1] - a[1], d = Math.hypot(dx, dy), len = Math.round((d + extend) * S) / S, phi = rad2deg(Math.atan2(dy, dx));
  if (side > 0) return { name, grp, from: [a[0], a[1] - t / 2, z - t / 2], to: [a[0] + len, a[1] + t / 2, z + t / 2], rotation: { origin: [a[0], a[1], z], angle: [0, 0, +phi.toFixed(3)] } };
  return { name, grp, from: [-a[0] - len, a[1] - t / 2, z - t / 2], to: [-a[0], a[1] + t / 2, z + t / 2], mirror: true, rotation: { origin: [-a[0], a[1], z], angle: [0, 0, -phi.toFixed(3)] } };
}
// A joint knuckle: a small axis-aligned bone cube centred on a point.
function knuckle(name, grp, c, z, side, k = 1.25) {
  const h = k / 2;
  if (side > 0) return { name, grp, from: [c[0] - h, c[1] - h, z - h], to: [c[0] + h, c[1] + h, z + h] };
  return { name, grp, from: [-c[0] - h, c[1] - h, z - h], to: [-c[0] + h, c[1] + h, z + h], mirror: true };
}
// A flat, two-faced plane over a region. Left wing: north reads as painted, south flips; right is the reverse.
function plane(name, reg, side, extra = {}) {
  const u0 = reg.tx, u1 = reg.tx + reg.w, v0 = reg.ty, v1 = reg.ty + reg.h, q = n => n / S;
  const [a, b] = side > 0 ? [reg.x0, reg.x1] : [-reg.x1, -reg.x0];
  const t = extra.thick || 0;
  return {
    name, from: [a, reg.y0, reg.z - t / 2], to: [b, reg.y1, reg.z + t / 2],
    faces: side > 0 ? { north: [q(u0), q(v0), q(u1), q(v1)], south: [q(u1), q(v0), q(u0), q(v1)] } : { north: [q(u1), q(v0), q(u0), q(v1)], south: [q(u0), q(v0), q(u1), q(v1)] },
    ...(extra.emissive ? { emissive: true } : {}),
  };
}
const BONE_TH = { humerus: 1.5, forearm: 1.25, claw: 0.75, f1: 0.75, f2: 0.75, f3: 0.75, f4: 0.75 };
const CLAW_TIP = [11.5, 1.5];
const along = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t)];
const leftBones = side => [
  limb('humerus', 'in', P.S, P.E, BONE_TH.humerus, ZI, side), limb('forearm', 'in', P.E, P.Wr, BONE_TH.forearm, ZI, side),
  knuckle('kS', 'in', P.S, ZI, side, 1.75), knuckle('kE', 'in', P.E, ZI, side, 1.75),
  limb('claw', 'out', P.Wr, CLAW_TIP, BONE_TH.claw, ZO, side),
  ...FINGERS.map(([a, b], i) => limb(`f${i + 1}`, 'out', a, b, BONE_TH[`f${i + 1}`], ZO, side, TIP)),
  knuckle('kW', 'out', P.Wr, ZO, side, 1.5),
  ...FINGERS.map(([a, b], i) => knuckle(`k${i + 1}`, 'out', along(a, b, 0.55), ZO, side, 1.25)),
];
// pack the bone cubes below the planes (the left wing defines the UV, the mirror reuses it)
const boneL = leftBones(+1), boneR = leftBones(-1);
layoutCubes(boneL, [TEX[0], 24], S);
boneL.forEach((c, i) => { c.uv = [c.uv[0], c.uv[1] + BONES_Y / S]; boneR[i].uv = c.uv; });
TEX[1] = Math.ceil(Math.max(...boneL.map(c => footprint(c, S).y + footprint(c, S).h)) / S);
const strip = ({ name, grp, ...c }) => c;

function wing(side) {
  const k = side > 0 ? 'L' : 'R', bones = side > 0 ? boneL : boneR, sx = side;
  const inner = [plane(`mem${k}`, R.memI, side), plane(`veins${k}`, R.veinI, side, { emissive: true, thick: 0.25 }), ...bones.filter(c => c.grp === 'in')];
  const outer = [plane(`memO${k}`, R.memO, side), plane(`veinsO${k}`, R.veinO, side, { emissive: true, thick: 0.25 }), ...bones.filter(c => c.grp === 'out')];
  return [
    { name: `wing${k}`, pivot: [sx * P.S[0], P.S[1], ZI], rotation: [0, sx * BASE_YAW, sx * REST_DROOP], cubes: inner.map(strip) },
    { name: `wing${k}2`, parent: `wing${k}`, pivot: [sx * P.Wr[0], P.Wr[1], ZO], rotation: [0, 0, 0], cubes: outer.map(strip) },
  ];
}
const bones = [...wing(+1), ...wing(-1)];

// ---- motion ---------------------------------------------------------------------------
// flap: up/down about the shoulder, the fingers a beat behind (10-15 deg idle at the tips, more on the move).
// sweep: a small fore/aft rock a quarter-cycle later, so the tips trace an ellipse instead of a line.
const flap = (bone, s, lag) => ({ bone, channel: 'rot', axis: 'z', amp: s * (lag ? 5 : 8), walkAmp: s * (lag ? 2 : 3), airAmp: s * (lag ? 6 : 10), period: PERIOD, phase: lag ? -0.12 : 0 });
const sweep = (bone, s, lag) => ({ bone, channel: 'rot', axis: 'y', amp: s * (lag ? 3 : 4), walkAmp: s * (lag ? 2 : 3), airAmp: s * (lag ? 5 : 6), period: PERIOD, phase: lag ? 0.13 : 0.25 });
const motion = [
  flap('wingL', 1, 0), flap('wingL2', 1, 1), sweep('wingL', 1, 0), sweep('wingL2', 1, 1),
  flap('wingR', -1, 0), flap('wingR2', -1, 1), sweep('wingR', -1, 0), sweep('wingR2', -1, 1),
];

// ---- colour ramps ---------------------------------------------------------------------
// membrane cells: char -> deep maroon -> ember red -> warm ember (shadows cooler, lights warmer)
const CELL = ['#1a0d12', '#3a171c', '#6e2619', '#a8441a'];
const OUTLINE = '#0c0608';
const dimCrack = ['#6a1d16', '#8e2918', '#b43a1a'];           // the cracks that are not lit: dim ember red
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

// ---- the cell structure -----------------------------------------------------------------
// Every membrane is a fan around the wrist. Spokes (finger bones, plus a few cross-vein spokes on the
// inner membrane) cut it into sectors, and each sector is cut into bands by wobbly arcs with a
// per-sector stagger, so the cells read as leather panels between veins (never loops).
// Angles are degrees around the wrist, 0 = +x, running negative downward; the arm sits near -198.
const angOf = (p, o) => { const a = rad2deg(Math.atan2(p[1] - o[1], p[0] - o[0])); return a > 90 ? a - 360 : a; };
const SPOKE = {
  O: P.F.map((f, i) => ({ a: angOf(f, P.Wr), bone: true, id: i })),
  I: [{ a: angOf(F4, P.Wr), bone: true, id: 4 }, { a: -122, bone: false, id: 5 }, { a: -143, bone: false, id: 6 }, { a: -165, bone: false, id: 7 }, { a: -199, bone: true, id: 8 }],
};
const BANDS = {                                                 // arc radii from the wrist per sector (model px)
  O: [[3.6, 7.4], [4.4, 8.2], [3.8, 7.8]],
  I: [[4.0, 7.8], [5.2, 8.6], [3.8, 7.2], [4.8, 8.4]],
};
const ARCS_SEED = { O: 29, I: 11 };

// Everything one texture pixel needs, once: which cell, how far from cracks, bones and the trailing edge.
function sampleRegion(key, reg, outline, bonesList, trail) {
  const n = reg.w * reg.h, spokes = SPOKE[key], bands = BANDS[key], seed = ARCS_SEED[key];
  const T = {
    reg, key, inside: new Uint8Array(n), edge: new Uint8Array(n), rr: new Float32Array(n), dEdge: new Float32Array(n), dBone: new Float32Array(n),
    level: new Float32Array(n), crack: new Uint8Array(n), lit: new Float32Array(n), rim: new Uint8Array(n), shade: new Float32Array(n),
  };
  for (let v = 0; v < reg.h; v++) for (let u = 0; u < reg.w; u++) {
    const i = v * reg.w + u, x = reg.x1 - (u + 0.5) / S, y = reg.y1 - (v + 0.5) / S;
    T.rr[i] = Math.hypot(x - P.S[0], y - P.S[1]);
    if (!inPoly(outline, x, y)) continue;
    T.inside[i] = 1;
    const dEdge = polyDist(trail, x, y);
    let db = 1e9; for (const [a, b] of bonesList) db = Math.min(db, segDist(x, y, a, b));
    T.dEdge[i] = dEdge; T.dBone[i] = db;

    // sector + band
    const dx = x - P.Wr[0], dy = y - P.Wr[1], r = Math.hypot(dx, dy), ang = angOf([x, y], P.Wr);
    let s = 0; while (s < spokes.length && spokes[s].a > ang) s++;      // s = index of the lower bounding spoke
    const sec = clamp(s - 1, 0, bands.length - 1), up = spokes[s - 1], lo = spokes[s];
    const arcs = (bands[sec] || []).map((rb, k) => rb + 0.5 * (noise2(ang * 0.11 + k * 7.3, sec * 3.1, seed) - 0.5) * 2);
    let band = 0; while (band < arcs.length && arcs[band] <= r) band++;
    const rIn = band === 0 ? 1.5 : arcs[band - 1], rOut = band >= arcs.length ? rIn + 6 : arcs[band];
    const u01 = clamp((r - rIn) / (rOut - rIn));

    // cracks: the non-bone spokes and the arcs, thicker near the wrist, never drawn on top of the wrist knot
    const hw = lerp(0.2, 0.13, clamp(r / 13));
    let dCrack = 1e9, cid = 0;
    arcs.forEach((rb, k) => { const d = Math.abs(r - rb); if (d < dCrack) { dCrack = d; cid = sec * 11 + k; } });
    for (const sp of [up, lo]) if (sp && !sp.bone) { const d = r * Math.abs(((ang - sp.a) * Math.PI) / 180); if (d < dCrack) { dCrack = d; cid = 100 + sp.id; } }
    const isCrack = dCrack < hw && r > 3.0 && db > 0.55;
    T.crack[i] = isCrack ? 1 : 0;
    // about 30% of the cracks are lit: those that run beside a bone, and only on chosen arcs, fading out with distance
    if (isCrack && hash2(cid, 3, seed + 5) < 0.62) T.lit[i] = smoothstep(3.4, 1.4, db);

    // cell tone, 0..1 over the 4-step ramp: warm near the shoulder side of each cell, char toward the trailing edge
    const cellHash = hash2(sec, band, seed + 21);
    const g = smoothstep(0, 9.5, dEdge);
    let L = 0.1 + 0.52 * g + 0.2 * (1 - u01) + (cellHash - 0.5) * 0.2 + (noise2(x * 1.3, y * 1.3, seed) - 0.5) * 0.1;
    L += 0.22 * Math.exp(-db / 1.6);                                     // the leather warms against a bone
    T.level[i] = clamp(L, 0, 1);
    T.shade[i] = g;
    void lo; void up;
  }
  // silhouette outline + scorched rim (texture-space, from the inside mask)
  const ins = (x, y) => x >= 0 && y >= 0 && x < reg.w && y < reg.h && T.inside[y * reg.w + x];
  for (let y = 0; y < reg.h; y++) for (let x = 0; x < reg.w; x++) {
    const i = y * reg.w + x; if (!T.inside[i]) continue;
    if (!(ins(x + 1, y) && ins(x - 1, y) && ins(x, y + 1) && ins(x, y - 1))) T.edge[i] = 1;
    else if (T.dEdge[i] < 0.5 && !(ins(x + 2, y) && ins(x - 2, y) && ins(x, y + 2) && ins(x, y - 2))) T.rim[i] = 1;
  }
  return T;
}
const samples = {
  I: sampleRegion('I', R.memI, OUT_I, [...ARM, FINGERS[3]], TRAIL_I),
  O: sampleRegion('O', R.memO, OUT_O, FINGERS, TRAIL_O),
};
// share of crack pixels that are lit (logged by the build; the brief asks for about 30%)
{
  let all = 0, lit = 0; for (const T of Object.values(samples)) for (let i = 0; i < T.crack.length; i++) if (T.crack[i]) { all++; if (T.lit[i] > 0.15) lit++; }
  if (process.env.EMBER_STATS) console.log(`wings-ember: ${lit}/${all} crack px lit (${((100 * lit) / all).toFixed(1)}%)`);
}

// the lit cracks pulse outward from the shoulder; 6 frames loop once
const WL = 14;
const pulse = (r, f) => 0.5 + 0.5 * Math.cos(2 * Math.PI * (r / WL - f / FRAMES));

// ---- painters -----------------------------------------------------------------------------
// A 4-step pick with hard bands and a narrow dithered seam, so cells read as clean clusters.
function cellColor(L, x, y) {
  const p = L * (CELL.length - 1), lo = Math.floor(p), f = smoothstep(0.34, 0.66, p - lo);
  return CELL[Math.min(CELL.length - 1, lo + (f > bayer(x, y, 4) ? 1 : 0))];
}
function paintMembrane(tex, T) {
  const { reg, inside } = T, v = tex.view(reg.tx, reg.ty, reg.w, reg.h);
  for (let y = 0; y < reg.h; y++) for (let x = 0; x < reg.w; x++) {
    const i = y * reg.w + x; if (!inside[i]) continue;
    if (T.edge[i]) { v.set(x, y, OUTLINE); continue; }
    if (T.crack[i]) { v.set(x, y, dimCrack[clamp(Math.round(T.shade[i] * 2.2), 0, 2)]); continue; }
    v.set(x, y, cellColor(T.level[i], reg.tx + x, reg.ty + y));
  }
  // heat spill: the leather right beside a lit crack is a step warmer
  const base = v.clone();
  for (let y = 1; y < reg.h - 1; y++) for (let x = 1; x < reg.w - 1; x++) {
    const i = y * reg.w + x; if (!inside[i] || T.crack[i] || T.edge[i]) continue;
    let near = 0; for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const j = (y + dy) * reg.w + x + dx; if (T.lit[j] > 0.3) near = Math.max(near, T.lit[j]); }
    if (near > 0) v.set(x, y, cellColor(clamp(T.level[i] + 0.3 * near, 0, 1), reg.tx + x, reg.ty + y));
  }
  void base;
  // scorched trailing edge: one dim ember-red row inside the outline, hotter toward the fingertips
  for (let y = 0; y < reg.h; y++) for (let x = 0; x < reg.w; x++) {
    const i = y * reg.w + x; if (!T.rim[i]) continue;
    v.set(x, y, dimCrack[clamp(Math.round(1 + 1.2 * (1 - T.shade[i]) + (hash2(x, y, 3) > 0.7 ? 0.5 : 0)), 0, 2)]);
  }
}
function paintVeins(tex, T, f, out) {
  const { reg } = T, v = tex.view(out.tx, out.ty, out.w, out.h);
  for (let y = 0; y < reg.h; y++) for (let x = 0; x < reg.w; x++) {
    const i = y * reg.w + x, k = T.lit[i]; if (!T.crack[i] || k < 0.12) continue;
    const b = clamp((0.35 + 0.65 * pulse(T.rr[i], f)) * (0.35 + 0.65 * k));
    v.set(x, y, ember[clamp(Math.round(2 + b * 5.5), 0, ember.length - 1)]);
  }
}
// Bone cube faces: dark outline all round, a lit top row, joint grooves, and a tiny ember gem in each knuckle.
function paintBone(v, f, name) {
  const L = LIGHT[f], isK = /^k/.test(name), th = BONE_TH[name] ?? 1;
  v.paint((x, y) => {
    const edge = x === 0 || y === 0 || x === v.w - 1 || y === v.h - 1;
    if (edge) return rock[0];
    let t = 0.18 + 0.6 * L;
    if (y === 1 && f !== 'down') t += 0.16;                              // lit top row
    if (y === v.h - 2 && v.h > 3) t -= 0.12;                             // underside falls into shadow
    if (!isK && v.w > v.h && (x + 3) % 6 === 0 && th > 0.7) t -= 0.14;   // faint segment grooves along the bone
    return rock[clamp(Math.round(t * (rock.length - 1)), 0, rock.length - 1)];
  });
  if (isK && f !== 'down' && f !== 'up' && v.w >= 3) {                  // ember gem at the centre of the knuckle
    const cx = (v.w - 1) >> 1, cy = (v.h - 1) >> 1;
    v.set(cx, cy, ember[4]);
    if (v.w >= 5) { v.set(cx - 1, cy, ember[1]); v.set(cx + 1, cy, ember[1]); v.set(cx, cy - 1, ember[1]); v.set(cx, cy + 1, ember[1]); v.set(cx, cy, ember[5]); }
  }
}

let staticArt = null;
function staticLayer() {
  if (staticArt) return staticArt;
  const t = newTexture({ texSize: TEX, scale: S });
  paintMembrane(t, samples.I); paintMembrane(t, samples.O);
  for (const c of boneL) paintCube(t, { ...c, uv: c.uv }, S, (v, f) => paintBone(v, f, c.name));
  return (staticArt = t);
}

export default {
  meta: { id: 'wings-ember', name: 'Ember Wings', kind: 'wings', cents: 999, hidesCape: true, blurb: 'Obsidian wings with fire running through the veins. Hides your cape; a few embers drift off as you move.' },
  model: { texSize: TEX, scale: S, bones },
  anim: { frames: FRAMES, fps: FPS, motion },
  // a slow trickle (about 4 a second, never a cloud) lifting off the membranes, kept low so they never cross the head
  fx: [{ bone: 'body', at: [0, -8, -3.8], spread: [24, 8, 1], color: '#ff7a2a', color2: '#ffd27a', perSecond: 4, life: [1.4, 2.4], size: 0.5, rise: 2.5, drift: [0, 0, -1.2], glow: true }],
  paint(f) {
    const t = staticLayer().clone();
    paintVeins(t, samples.I, f, R.veinI); paintVeins(t, samples.O, f, R.veinO);
    return t;
  },
};
