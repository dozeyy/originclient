// Turns a cosmetics v2 item (item.json + texture.png, contract: cosmetics/FORMAT.md)
// into three.js objects: bones, box-UV cubes with nearest filtering, emissive and
// additive-glow parts, motion, texture frames and particles. Used by the store's
// fitting room, its card thumbnails and tools/cosmetics/shots.mjs (the judge's
// renders), so what the judge sees is what the shopper sees.
//
// One world unit = one model px (1/16 block). Items are built in their attach
// bone's frame; the caller parents the returned `group` to the player's head or
// body bone.
import * as THREE from './vendor/three.module.min.js';
import { cubeRects, cubeFaceNames, faceCorners, FACE_SHADE } from './uv.mjs';

// Exact pixel colours (like world.js): no colour management, linear output.
THREE.ColorManagement.enabled = false;

const TAU = Math.PI * 2;
const rad = d => (d * Math.PI) / 180;
const fract = x => x - Math.floor(x);
const hash = n => fract(Math.sin(n * 127.1 + 311.7) * 43758.5453);

// ----------------------------------------------------------------- loading
const loader = new THREE.TextureLoader();
export async function loadItem(base, id) {
  const item = await (await fetch(`${base}/${id}/item.json`)).json();
  const tex = await loader.loadAsync(`${base}/${id}/texture.png`);
  return { item, image: tex.image, tex };
}
export async function loadCatalog(base) {
  return (await (await fetch(`${base}/catalog.json`)).json()).items;
}

function makeTexture(src) {
  const t = new THREE.Texture(src.image);
  t.magFilter = THREE.NearestFilter;            // blocky pixels up close...
  t.minFilter = THREE.LinearMipmapLinearFilter; // ...clean at distance, like the game's mipmaps
  t.generateMipmaps = true;
  t.anisotropy = 4;
  t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping;
  t.needsUpdate = true;
  return t;
}

// ---------------------------------------------------------------- geometry
// One cube -> BufferGeometry with per-face UVs from the box-UV contract.
// frameTexH = the height of ONE texture frame in texture px (frames are stacked).
function cubeGeometry(cube, scale, texW, frameTexH) {
  const rects = cubeRects(cube, scale), inf = cube.inflate || 0;
  const lo = cube.from.map(v => v - inf), hi = cube.to.map(v => v + inf);
  const pos = [], uv = [], col = [], idx = [];
  const emissive = !!cube.emissive || !!cube.glow;
  for (const f of cubeFaceNames(cube)) {
    const r = rects[f], base = pos.length / 3;
    for (const p of faceCorners(f, lo, hi)) pos.push(...p);
    const u0 = r.x / texW, u1 = (r.x + r.w) / texW, vT = 1 - r.y / frameTexH, vB = 1 - (r.y + r.h) / frameTexH;
    const [L, R] = r.flip ? [u1, u0] : [u0, u1];
    uv.push(L, vT, R, vT, L, vB, R, vB);
    const s = emissive ? 1 : FACE_SHADE[f];
    for (let i = 0; i < 4; i++) col.push(s, s, s);
    idx.push(base, base + 2, base + 1, base + 2, base + 3, base + 1);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  g.setIndex(idx);
  return g;
}

// -------------------------------------------------------------- animation
const sin = (t, period, phase) => Math.sin(TAU * (t / period + phase));
// FORMAT.md: A = amp + walkAmp * walk01 + airAmp * air01; value = sin(2pi(t/period+phase)) * A
export function motionValue(m, ctx) {
  const A = (m.amp || 0) + (m.walkAmp || 0) * ctx.walk + (m.airAmp || 0) * ctx.air;
  return sin(ctx.t, m.period || 1, m.phase || 0) * A;
}
// Seconds after which every motion, texture frame cycle and loop repeats exactly.
export function loopSeconds(item) {
  const parts = [...(item.anim?.motion || []).map(m => m.period)];
  const frames = item.cape?.frames ?? item.anim?.frames ?? 1, fps = item.cape?.fps ?? item.anim?.fps ?? 10;
  if (frames > 1) parts.push(frames / fps);
  if (!parts.length) return 2.4;
  const ints = parts.map(p => Math.max(1, Math.round(p * 100)));
  const gcd = (a, b) => (b ? gcd(b, a % b) : a);
  const l = ints.reduce((a, b) => (a / gcd(a, b)) * b);
  return Math.min(12, l / 100);
}

// ------------------------------------------------------------------- particles
// Stateless, so the same time always gives the same picture (shots, loops, reduced motion).
function makeFx(spec, loop) {
  const group = new THREE.Group();
  const lifeMax = spec.life[1], P = loop >= lifeMax ? loop / Math.floor(loop / lifeMax) : lifeMax;
  const slots = Math.max(1, Math.ceil(spec.perSecond * P));
  const c1 = new THREE.Color(spec.color), c2 = new THREE.Color(spec.color2 || spec.color);
  const sprites = [];
  for (let j = 0; j < slots; j++) {
    const mat = new THREE.SpriteMaterial({ color: c1, transparent: true, depthWrite: false, blending: spec.glow ? THREE.AdditiveBlending : THREE.NormalBlending });
    const s = new THREE.Sprite(mat);
    s.visible = false;
    group.add(s); sprites.push(s);
  }
  group.position.set(0, 0, 0);
  const update = ctx => {
    const t = ctx.t;
    sprites.forEach((s, j) => {
      const off = j / spec.perSecond, c = Math.floor((t - off) / P), age = t - off - c * P;
      const seed = j * 37.7 + (((c % (Math.round(loop / P) || 1)) + (Math.round(loop / P) || 1)) % (Math.round(loop / P) || 1)) * 911.3 + (spec.seed || 0);
      const life = spec.life[0] + (spec.life[1] - spec.life[0]) * hash(seed + 1);
      if (age < 0 || age >= life) { s.visible = false; return; }
      const sp = spec.spread || [0, 0, 0], dr = spec.drift || [0, 0, 0];
      s.position.set(
        spec.at[0] + (hash(seed + 2) - 0.5) * sp[0] + dr[0] * age,
        spec.at[1] + (hash(seed + 3) - 0.5) * sp[1] + (spec.rise || 0) * age,
        spec.at[2] + (hash(seed + 4) - 0.5) * sp[2] + dr[2] * age);
      const fade = Math.min(1, age / 0.15) * (1 - age / life);
      s.material.color.copy(c1).lerp(c2, hash(seed + 5));
      s.material.opacity = Math.max(0, fade);
      const size = (spec.size || 1) * (0.7 + 0.6 * hash(seed + 6));
      s.scale.set(size, size, 1);
      s.visible = true;
    });
  };
  return { group, update, dispose: () => sprites.forEach(s => s.material.dispose()) };
}

// ------------------------------------------------------------------- items
// Build any item (not a theme). Returns { group, update(ctx), dispose(), item }.
// ctx = { t seconds, walk 0..1, air 0..1 }.
export function buildRig(item, source) {
  const tex = makeTexture(source);
  const rig = item.cape ? buildCape(item, tex) : buildModel(item, tex);
  const frames = item.cape?.frames ?? item.anim?.frames ?? 1, fps = item.cape?.fps ?? item.anim?.fps ?? 10;
  const loop = loopSeconds(item);
  const fx = (item.fx || []).map(f => ({ spec: f, ...makeFx(f, loop) }));
  const hosts = rig.hosts || {};
  for (const f of fx) (hosts[f.spec.bone] || rig.group).add(f.group);
  const inner = rig.update;
  return {
    item, group: rig.group, fxBones: fx.map(f => f.spec.bone), setCapeOffset: rig.setOffset || (() => {}),
    update(ctx) {
      if (frames > 1) {
        const k = Math.floor(ctx.t * fps) % frames;
        tex.repeat.set(1, 1 / frames);
        tex.offset.set(0, 1 - (k + 1) / frames);
      }
      inner(ctx);
      fx.forEach(f => f.update(ctx));
    },
    dispose() { tex.dispose(); rig.group.traverse(o => { if (o.isMesh) { o.geometry.dispose(); o.material.dispose(); } }); fx.forEach(f => f.dispose()); },
    texture: tex,
  };
}

function materialFor(cube, tex) {
  if (cube.glow) return new THREE.MeshBasicMaterial({ map: tex, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, alphaTest: 0.01 });
  return new THREE.MeshBasicMaterial({ map: tex, vertexColors: true, alphaTest: 0.5, side: THREE.FrontSide });
}

function buildModel(item, tex) {
  const m = item.model, frames = item.anim?.frames ?? 1;
  const texW = m.texSize[0] * m.scale, frameH = m.texSize[1] * m.scale;
  const root = new THREE.Group(), bones = new Map(), byName = new Map(m.bones.map(b => [b.name, b]));
  for (const b of m.bones) {
    const g = new THREE.Group();
    g.userData.base = { pos: new THREE.Vector3(), rot: new THREE.Euler() };
    bones.set(b.name, g);
  }
  for (const b of m.bones) {
    const g = bones.get(b.name), parent = b.parent ? byName.get(b.parent) : null;
    const pp = parent ? parent.pivot : [0, 0, 0];
    g.position.set(b.pivot[0] - pp[0], b.pivot[1] - pp[1], b.pivot[2] - pp[2]);
    g.rotation.set(rad(b.rotation?.[0] || 0), rad(b.rotation?.[1] || 0), rad(b.rotation?.[2] || 0), 'XYZ');   // Z applied first
    g.userData.base.pos.copy(g.position);
    g.userData.base.rot.copy(g.rotation);
    (parent ? bones.get(b.parent) : root).add(g);
    for (const cube of b.cubes || []) {
      const geo = cubeGeometry(cube, m.scale, texW, frameH);
      geo.translate(-b.pivot[0], -b.pivot[1], -b.pivot[2]);
      const mesh = new THREE.Mesh(geo, materialFor(cube, tex));
      if (cube.glow) mesh.renderOrder = 2;
      if (cube.rotation) {
        const o = cube.rotation.origin, h = new THREE.Group();
        h.position.set(o[0] - b.pivot[0], o[1] - b.pivot[1], o[2] - b.pivot[2]);
        mesh.position.set(-h.position.x, -h.position.y, -h.position.z);
        const a = cube.rotation.angle;
        h.rotation.set(rad(a[0]), rad(a[1]), rad(a[2]), 'XYZ');
        h.add(mesh); g.add(h);
      } else g.add(mesh);
    }
  }
  const motion = item.anim?.motion || [];
  const AX = { x: 0, y: 1, z: 2 };
  const update = ctx => {
    for (const mo of motion) {
      const g = bones.get(mo.bone); if (!g) continue;
      const v = motionValue(mo, ctx), ax = ['x', 'y', 'z'][AX[mo.axis]];
      if (mo.channel === 'pos') g.position[ax] = g.userData.base.pos[ax] + v;
      else g.rotation[ax] = g.userData.base.rot[ax] + rad(v);
    }
  };
  // reset to base first so entries on the same bone add up from rest
  const wrapped = ctx => { for (const g of bones.values()) { g.position.copy(g.userData.base.pos); g.rotation.copy(g.userData.base.rot); } update(ctx); };
  return { group: root, update: wrapped, hosts: Object.fromEntries([...bones]) };
}

// The cape: vanilla 10x16x1 box hung from the top of the back, plus sway.
function buildCape(item, tex) {
  const S = item.cape.scale;
  const cube = { from: [-5, -16, -1], to: [5, 0, 0], uv: [0, 0] };
  const geo = cubeGeometry(cube, S, 64 * S, 32 * S);
  const mesh = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ map: tex, vertexColors: true, alphaTest: 0.5, side: THREE.FrontSide }));
  const hang = new THREE.Group();            // pivot: top of the cape
  hang.add(mesh);
  const root = new THREE.Group();
  root.add(hang);
  hang.position.set(0, 0, -2 - (item.capeOffset || 0));
  const setOffset = v => { hang.position.z = -2 - v; };
  const update = ctx => {
    const sway = ctx.still ? 0 : Math.sin(ctx.t * 1.9) * 1.5 + Math.sin(ctx.t * 0.7 + 1) * 1;
    hang.rotation.x = rad(5 + sway + 30 * ctx.walk + 14 * ctx.air + (ctx.crouch ? 8 : 0));
    hang.rotation.z = ctx.still ? 0 : rad(Math.sin(ctx.t * 1.3) * 1.2 * (0.4 + ctx.walk));
  };
  return { group: root, update, hosts: { body: root }, setOffset };
}

export const isCape = item => !!item.cape;
