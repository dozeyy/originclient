// The 3D layer behind the page: a floating island in the sky, built block by
// block from Minecraft's own default textures and lit with the shaders look
// (warm low sun, cool sky fill, the game's smooth-lighting corner shadows),
// with Origin's three rings orbiting it so the island is the nucleus of the
// Origin mark.
//
// site.js owns WHAT is shown (which pose); this file owns HOW: meshing,
// materials and motion. Motion is all damped: the island floats on slow sines
// and the camera chases its pose through critically damped springs, so nothing
// starts, stops or changes direction abruptly.
import * as THREE from './vendor/three.module.min.js';

// Textures are drawn with their exact pixel colours, like the game (no gamma
// round trip): colour management off, linear output, raw textures.
THREE.ColorManagement.enabled = false;

const BLOCKS = 'assets/blocks/';

// Minecraft's fixed face brightness: up 1.0, north/south 0.8, east/west 0.6, down 0.5.
const FACES = [
  { n: [1, 0, 0], shade: 0.6, c: [[1, 0, 1], [1, 0, 0], [1, 1, 0], [1, 1, 1]] },
  { n: [-1, 0, 0], shade: 0.6, c: [[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]] },
  { n: [0, 1, 0], shade: 1.0, c: [[0, 1, 1], [1, 1, 1], [1, 1, 0], [0, 1, 0]] },
  { n: [0, -1, 0], shade: 0.5, c: [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]] },
  { n: [0, 0, 1], shade: 0.8, c: [[0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]] },
  { n: [0, 0, -1], shade: 0.8, c: [[1, 0, 0], [0, 0, 0], [0, 1, 0], [1, 1, 0]] },
];
// Smooth-lighting corner darkening by how many of the 3 neighbours are solid.
const AO = [1, 0.8, 0.66, 0.54];

// block type -> texture per face [+x, -x, +y, -y, +z, -z]
const KINDS = {
  grass: ['grass_side', 'grass_side', 'grass_top', 'dirt', 'grass_side', 'grass_side'],
  dirt: 'dirt', stone: 'stone', gravel: 'gravel', cobblestone: 'cobblestone',
  diamond_ore: 'diamond_ore', emerald_ore: 'emerald_ore', glowstone: 'glowstone', oak_planks: 'oak_planks',
  oak_log: ['oak_log', 'oak_log', 'oak_log_top', 'oak_log_top', 'oak_log', 'oak_log'],
  crafting_table: ['crafting_table_side', 'crafting_table_side', 'crafting_table_top', 'oak_planks', 'crafting_table_front', 'crafting_table_front'],
  oak_leaves: 'oak_leaves',
};
const SEE_THROUGH = new Set(['oak_leaves', 'water', 'short_grass', 'dandelion', 'poppy', 'torch']);
const PLANTS = new Set(['short_grass', 'dandelion', 'poppy']);
const EMISSIVE = new Set(['glowstone', 'torch']);

// Camera poses (island space; the island's top sits at y=1). shiftX/shiftY move
// the whole picture by a fraction of the screen; orbit scales the slow sway.
const POSES = {
  hero:     { pos: [42, 19, 53], target: [0.5, -4, 0.5], fov: 42, shiftX: 0.2, shiftY: 0.02, orbit: 1 },
  heroMobile: { pos: [96, 41, 120], target: [0.5, -4, 0.5], fov: 54, shiftX: 0, shiftY: 0.28, orbit: 1 },   // above the headline, small enough that its rings clear the header and the title
  heroTablet: { pos: [52, 22, 65], target: [0.5, -4, 0.5], fov: 54, shiftX: 0, shiftY: 0.21, orbit: 1 },    // same, for the bigger open space a tablet has above the headline
  versions: { pos: [-36, 18, 46], target: [0.5, -4, 0.5], fov: 44, shiftX: 0.24, shiftY: 0, orbit: 1 },
  why:      { pos: [-34, 13, 43], target: [0.5, -4, 0.5], fov: 44, shiftX: 0.2, shiftY: 0.03, orbit: 0.6 },
  // centred under "Ready when you are", whole island + rings in frame below the text
  get:      { pos: [0, 17, 100], target: [0.5, -4, 0.5], fov: 42, shiftX: 0, shiftY: -0.21, orbit: 1 },
};

function rng(seed) {
  return () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------- the island
function generateIsland() {
  const r = rng(20261011);
  const map = new Map();
  const key = (x, y, z) => `${x},${y},${z}`;
  const set = (x, y, z, t) => map.set(key(x, y, z), t);
  const get = (x, y, z) => map.get(key(x, y, z));
  const tops = new Map();
  const edge = a => 9.4 + 1.2 * Math.sin(a * 3 + 1) + 0.7 * Math.sin(a * 5 + 2.3) + 0.45 * Math.sin(a * 7 + 0.4);

  for (let x = -13; x <= 13; x++) {
    for (let z = -13; z <= 13; z++) {
      const d = Math.hypot(x + 0.5, z + 0.5), a = Math.atan2(z + 0.5, x + 0.5), e = edge(a);
      if (d > e) continue;
      const h = (Math.hypot(x - 3, z + 3) < 3.7 ? 1 : 0) + (Math.hypot(x - 4, z + 4) < 1.9 ? 1 : 0);
      const bottom = -Math.floor(2 + (e - d) * 0.95 + r() * 1.6);
      tops.set(`${x},${z}`, h);
      for (let y = bottom; y <= h; y++) {
        const k = h - y;
        let t = k === 0 ? 'grass' : k <= 2 + (r() < 0.4 ? 1 : 0) ? 'dirt' : 'stone';
        if (t === 'stone') {
          const q = r();
          if (y < -6 && q < 0.05) t = 'diamond_ore';
          else if (q < 0.035) t = 'emerald_ore';
          else if (q < 0.09) t = 'gravel';
          else if (q < 0.13) t = 'cobblestone';
        }
        set(x, y, z, t);
      }
      // a few hanging glowstone blocks under the deepest columns
      if (bottom < -10 && r() < 0.06) set(x, bottom - 1, z, 'glowstone');
    }
  }
  // pond
  for (const [c, h] of tops) {
    const [x, z] = c.split(',').map(Number);
    if (h === 0 && Math.hypot(x + 3.5, z - 3) < 2.1) {
      set(x, 0, z, 'water');
      if (get(x, -1, z) === 'dirt' && Math.hypot(x + 3.5, z - 3) < 1.2) set(x, -1, z, 'water');
      set(x, -2, z, 'gravel');
    }
  }
  // tree on the hill
  const tx = 4, tz = -4, th = tops.get(`${tx},${tz}`);
  for (let y = th + 1; y <= th + 5; y++) set(tx, y, tz, 'oak_log');
  set(tx, th, tz, 'dirt');
  for (let y = th + 3; y <= th + 6; y++) {
    const rad = y <= th + 4 ? 2 : 1;
    for (let dx = -rad; dx <= rad; dx++) {
      for (let dz = -rad; dz <= rad; dz++) {
        const corner = Math.abs(dx) === rad && Math.abs(dz) === rad;
        if (corner && (y === th + 6 || r() < 0.5)) continue;
        if (dx === 0 && dz === 0 && y <= th + 5) continue;
        set(tx + dx, y, tz + dz, 'oak_leaves');
      }
    }
  }
  // crafting table + torch (the top layer stays all grass)
  const ct = [-2, -5];
  const cth = tops.get(`${ct[0]},${ct[1]}`);
  if (cth !== undefined) set(ct[0], cth + 1, ct[1], 'crafting_table');
  const to = [-4, -4];
  const toh = tops.get(`${to[0]},${to[1]}`);
  if (toh !== undefined) set(to[0], toh + 1, to[1], 'torch');
  // plants on free grass
  for (const [c, h] of tops) {
    const [x, z] = c.split(',').map(Number);
    if (get(x, h, z) !== 'grass' || get(x, h + 1, z)) continue;
    const q = r();
    if (q < 0.13) set(x, h + 1, z, 'short_grass');
    else if (q < 0.16) set(x, h + 1, z, 'dandelion');
    else if (q < 0.19) set(x, h + 1, z, 'poppy');
  }
  return { map, tops, get };
}

// --------------------------------------------------------------- materials
const VERT = /* glsl */`
  attribute float ao;
  attribute float face;
  varying vec2 vUv; varying float vAo; varying float vFace; varying vec3 vN; varying vec3 vW;
  void main() {
    vUv = uv; vAo = ao; vFace = face; vN = normalize(mat3(modelMatrix) * normal);
    vec4 w = modelMatrix * vec4(position, 1.0); vW = w.xyz;
    gl_Position = projectionMatrix * viewMatrix * w;
  }`;
const FRAG = /* glsl */`
  uniform sampler2D map; uniform float uCut; uniform float uAlpha;
  uniform vec3 uSun; uniform float uEmissive; uniform float uWater; uniform float uTime;
  uniform vec3 uOrb[3]; uniform vec3 uOrbCol;
  varying vec2 vUv; varying float vAo; varying float vFace; varying vec3 vN; varying vec3 vW;
  void main() {
    vec2 uv = vUv;
    if (uWater > 0.5) uv += vec2(sin(uTime * 0.6 + vW.z * 0.7) * 0.03, uTime * 0.05);
    vec4 t = texture2D(map, uv);
    if (t.a < uCut) discard;
    // Shaders look: a low warm sun, cool sky fill, soft contrast grade.
    vec3 n = normalize(vN);
    float ndl = max(dot(n, uSun), 0.0);
    vec3 sun = vec3(1.1, 0.92, 0.74);
    vec3 sky = vec3(0.36, 0.38, 0.5);
    vec3 lit = t.rgb * vAo * (sky + sun * ndl);
    // The Origin rings light the island: each orb riding a ring is a small
    // white point light, plus a faint white rim from the rings' glow.
    vec3 v = normalize(cameraPosition - vW);
    float orb = 0.0;
    for (int i = 0; i < 3; i++) {
      vec3 d = uOrb[i] - vW;
      orb += max(dot(n, normalize(d)), 0.0) * 16.0 / (1.0 + dot(d, d) * 0.55);
    }
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.22;
    lit += t.rgb * uOrbCol * (orb + rim);
    if (uWater > 0.5) {
      float spec = pow(max(dot(reflect(-uSun, n), v), 0.0), 48.0);
      lit += vec3(1.0, 0.9, 0.75) * spec * 1.4;
    }
    lit = mix(lit, lit * lit * 1.6, 0.18) * vec3(1.03, 1.0, 0.96);
    vec3 col = mix(lit, t.rgb * 1.15, uEmissive);
    gl_FragColor = vec4(col, t.a * uAlpha);
  }`;

function blockMaterial(tex, uniforms, opts = {}) {
  return new THREE.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    uniforms: {
      map: { value: tex }, uCut: { value: opts.cut ?? 0.5 }, uAlpha: { value: opts.alpha ?? 1 },
      uEmissive: { value: opts.emissive ? 1 : 0 }, uWater: { value: opts.water ? 1 : 0 },
      uSun: uniforms.uSun, uTime: uniforms.uTime,
      uOrb: uniforms.uOrb, uOrbCol: uniforms.uOrbCol,
    },
    transparent: !!opts.water, depthWrite: !opts.water, side: opts.double ? THREE.DoubleSide : THREE.FrontSide,
  });
}

// ------------------------------------------------------------------ meshing
function meshIsland(island, textures, uniforms) {
  const { map, get } = island;
  const solid = (x, y, z) => { const t = get(x, y, z); return t !== undefined && !SEE_THROUGH.has(t); };
  const occl = (x, y, z) => { const t = get(x, y, z); return t !== undefined && (!SEE_THROUGH.has(t) || t === 'oak_leaves'); };
  const buckets = new Map();
  const bucket = name => {
    if (!buckets.has(name)) buckets.set(name, { pos: [], uv: [], nrm: [], ao: [], face: [], idx: [] });
    return buckets.get(name);
  };
  const quad = (b, corners, n, uvs, aos, shade) => {
    const i = b.pos.length / 3;
    for (let k = 0; k < 4; k++) {
      b.pos.push(...corners[k]); b.nrm.push(...n); b.uv.push(...uvs[k]); b.ao.push(aos[k]); b.face.push(shade);
    }
    // flip the split so corner shadows interpolate evenly (as the game does)
    if (aos[0] + aos[2] < aos[1] + aos[3]) b.idx.push(i + 1, i + 2, i + 3, i + 1, i + 3, i);
    else b.idx.push(i, i + 1, i + 2, i, i + 2, i + 3);
  };
  const UV = [[0, 0], [1, 0], [1, 1], [0, 1]];

  for (const [k, type] of map) {
    const [x, y, z] = k.split(',').map(Number);
    if (PLANTS.has(type)) {
      const b = bucket(type), s = 0.45;
      for (const [ax, az, bx, bz] of [[-s, -s, s, s], [-s, s, s, -s]]) {
        const c = [[x + 0.5 + ax, y, z + 0.5 + az], [x + 0.5 + bx, y, z + 0.5 + bz], [x + 0.5 + bx, y + 1, z + 0.5 + bz], [x + 0.5 + ax, y + 1, z + 0.5 + az]];
        quad(b, c, [0, 1, 0], UV, [1, 1, 1, 1], 1);
      }
      continue;
    }
    if (type === 'torch') {
      // the game's torch model: a 2x10 px stick cut from the middle of the texture
      const b = bucket('torch'), lo = 7 / 16, hi = 9 / 16, top = 10 / 16;
      const x0 = x + lo, x1 = x + hi, z0 = z + lo, z1 = z + hi, y1 = y + top;
      const side = [[lo, 0], [hi, 0], [hi, top], [lo, top]];
      quad(b, [[x1, y, z1], [x1, y, z0], [x1, y1, z0], [x1, y1, z1]], [1, 0, 0], side, [1, 1, 1, 1], 1);
      quad(b, [[x0, y, z0], [x0, y, z1], [x0, y1, z1], [x0, y1, z0]], [-1, 0, 0], side, [1, 1, 1, 1], 1);
      quad(b, [[x0, y, z1], [x1, y, z1], [x1, y1, z1], [x0, y1, z1]], [0, 0, 1], side, [1, 1, 1, 1], 1);
      quad(b, [[x1, y, z0], [x0, y, z0], [x0, y1, z0], [x1, y1, z0]], [0, 0, -1], side, [1, 1, 1, 1], 1);
      quad(b, [[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]], [0, 1, 0], [[lo, 8 / 16], [hi, 8 / 16], [hi, top], [lo, top]], [1, 1, 1, 1], 1);
      continue;
    }
    for (let f = 0; f < 6; f++) {
      const F = FACES[f], [nx, ny, nz] = F.n;
      const nb = get(x + nx, y + ny, z + nz);
      if (type === 'water') {
        if (f !== 2 || nb !== undefined) continue;
      } else if (nb !== undefined && !SEE_THROUGH.has(nb)) continue;
      else if (type === 'oak_leaves' && nb === 'oak_leaves') continue;
      const tex = type === 'water' ? 'water' : Array.isArray(KINDS[type]) ? KINDS[type][f] : KINDS[type];
      const b = bucket(tex);
      // the two in-plane axes of this face
      const axes = [0, 1, 2].filter(a => F.n[a] === 0);
      const aos = [], corners = [];
      for (const c of F.c) {
        const p = [x + c[0], y + c[1], z + c[2]];
        if (type === 'water' && c[1] === 1) p[1] = y + 14 / 16;
        corners.push(p);
        if (EMISSIVE.has(type)) { aos.push(1); continue; }
        const o = [x + nx, y + ny, z + nz];
        const d1 = [0, 0, 0], d2 = [0, 0, 0];
        d1[axes[0]] = c[axes[0]] ? 1 : -1;
        d2[axes[1]] = c[axes[1]] ? 1 : -1;
        const s1 = occl(o[0] + d1[0], o[1] + d1[1], o[2] + d1[2]);
        const s2 = occl(o[0] + d2[0], o[1] + d2[1], o[2] + d2[2]);
        const cc = occl(o[0] + d1[0] + d2[0], o[1] + d1[1] + d2[1], o[2] + d1[2] + d2[2]);
        aos.push(AO[s1 && s2 ? 3 : s1 + s2 + cc]);
      }
      quad(b, corners, F.n, UV, aos, EMISSIVE.has(type) ? 1 : F.shade);
    }
  }

  const group = new THREE.Group();
  for (const [name, b] of buckets) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(b.pos, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(b.nrm, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(b.uv, 2));
    g.setAttribute('ao', new THREE.Float32BufferAttribute(b.ao, 1));
    g.setAttribute('face', new THREE.Float32BufferAttribute(b.face, 1));
    g.setIndex(b.idx);
    const opts = {
      water: name === 'water', alpha: name === 'water' ? 0.82 : 1, cut: name === 'water' ? 0.01 : 0.5,
      double: PLANTS.has(name), emissive: EMISSIVE.has(name),
    };
    const mesh = new THREE.Mesh(g, blockMaterial(textures[name], uniforms, opts));
    if (name === 'water') mesh.renderOrder = 2;
    group.add(mesh);
  }
  return group;
}

function glowTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 128;
  const g = c.getContext('2d');
  const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  grad.addColorStop(0, 'rgba(255,255,255,1)');
  grad.addColorStop(0.18, 'rgba(255,255,255,.55)');
  grad.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 128, 128);
  return new THREE.CanvasTexture(c);
}

// --------------------------------------------------------------- the world
// Critically damped spring on a list of numbers: eases out of a standstill and
// into the target with no overshoot and no kink at the end, whatever the frame
// time. `omega` is the stiffness (settles in roughly 4 / omega seconds).
class Spring {
  constructor(values) { this.x = Float64Array.from(values); this.v = new Float64Array(values.length); }
  step(target, omega, dt) {
    const e = Math.exp(-omega * dt);
    for (let i = 0; i < this.x.length; i++) {
      const d = this.x[i] - target[i], k = this.v[i] + omega * d;
      this.x[i] = target[i] + (d + k * dt) * e;
      this.v[i] = (this.v[i] - omega * k * dt) * e;
    }
  }
}

const TAU = Math.PI * 2;
const DUST_VERT = /* glsl */`
  uniform float uTime; uniform float uScale; attribute float seed;
  void main() {
    vec3 p = position;
    // rises slowly, wrapping from the top of the void back to the bottom
    p.y = mod(p.y + 20.0 + uTime * 0.3, 40.0) - 20.0;
    p.x += sin(uTime * 0.2 + seed * 6.28) * 0.6;
    vec4 mv = modelViewMatrix * vec4(p, 1.0);
    gl_PointSize = 0.12 * uScale / -mv.z;
    gl_Position = projectionMatrix * mv;
  }`;

export async function createWorld(canvas, { mobile = false, reducedMotion = false } = {}) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'high-performance' });
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  renderer.setClearColor(0x000000, 0);
  let pixelRatio = Math.min(window.devicePixelRatio || 1, mobile ? 1.5 : 1.75);
  renderer.setPixelRatio(pixelRatio);

  const loader = new THREE.TextureLoader();
  const load = url => new Promise((res, rej) => loader.load(url, res, undefined, rej));
  const names = new Set(['water', 'torch', 'short_grass', 'dandelion', 'poppy']);
  for (const v of Object.values(KINDS)) (Array.isArray(v) ? v : [v]).forEach(n => names.add(n));
  const textures = {};
  await Promise.all([...names].map(async n => {
    const t = await load(`${BLOCKS}${n}.png`);
    t.magFilter = THREE.NearestFilter;
    t.minFilter = THREE.NearestMipmapLinearFilter;
    t.colorSpace = THREE.NoColorSpace;
    t.anisotropy = 4;
    // repeat, not clamp: the water's scrolling UVs leave 0..1, and clamping
    // smears the edge texels into lines across the surface
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    textures[n] = t;
  }));

  const uniforms = {
    uSun: { value: new THREE.Vector3(-0.45, 0.72, 0.52).normalize() },
    uTime: { value: 0 },
    uOrb: { value: [new THREE.Vector3(0, 999, 0), new THREE.Vector3(0, 999, 0), new THREE.Vector3(0, 999, 0)] },
    uOrbCol: { value: new THREE.Vector3(0.9, 0.9, 0.92) },
  };

  // ---- island scene
  const scene = new THREE.Scene();
  const island = generateIsland();
  const blocks = meshIsland(island, textures, uniforms);

  const glowTex = glowTexture();
  const glows = new THREE.Group();
  for (const [k, t] of island.map) {
    if (!EMISSIVE.has(t)) continue;
    const [x, y, z] = k.split(',').map(Number);
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, color: t === 'torch' ? 0xffb84d : 0xffd27a, transparent: true, opacity: 0.8, depthWrite: false, blending: THREE.AdditiveBlending }));
    s.position.set(x + 0.5, y + (t === 'torch' ? 0.7 : 0.5), z + 0.5);
    s.scale.setScalar(t === 'torch' ? 2.2 : 3.4);
    glows.add(s);
  }

  // The island floats: it hangs from a pivot at its own centre, so the slow
  // bob and sway turn it about the middle rather than swinging it round the origin.
  const bounds = new THREE.Sphere().setFromPoints([...island.map.keys()].map(k => {
    const [x, y, z] = k.split(',').map(Number);
    return new THREE.Vector3(x + 0.5, y + 0.5, z + 0.5);
  }));
  const pivot = new THREE.Group();
  pivot.position.copy(bounds.center);
  blocks.position.copy(bounds.center).negate();
  glows.position.copy(bounds.center).negate();
  pivot.add(blocks, glows);
  scene.add(pivot);

  // Origin's rings: the mark's three orbits (ellipse ratio 0.355 = a circle
  // tilted ~69 deg) at 0/60/120 deg, always turned to the camera so it reads
  // as the logo from anywhere. They circle the island's bounding sphere, so no
  // ring can ever pass through a block, whatever the camera angle.
  const inner = bounds.radius + 0.87 + 0.8;   // + half a block diagonal + clearance
  const rings = new THREE.Group();
  const ringMat = new THREE.MeshBasicMaterial({ color: 0xeeeeee, transparent: true, opacity: 0.95 });
  const ringGlow = new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.2, blending: THREE.AdditiveBlending, depthWrite: false });
  // Comet tail behind each orb: a short arc of ring that fades in toward the orb.
  const TAIL = 1.1;
  const tailMat = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    vertexShader: 'varying vec2 vUv; void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
    fragmentShader: 'varying vec2 vUv; void main() { float a = pow(vUv.x, 2.4); gl_FragColor = vec4(mix(vec3(0.6), vec3(1.0), a) * a * 0.9, 1.0); }',
  });
  const spinners = [];
  [[1.5, 0], [1.25, 60], [1, 120]].forEach(([k, deg], i) => {
    const rad = inner * k;
    const holder = new THREE.Group();
    holder.rotation.z = THREE.MathUtils.degToRad(deg);
    const tilt = new THREE.Group();
    tilt.rotation.x = Math.acos(0.355);
    tilt.add(new THREE.Mesh(new THREE.TorusGeometry(rad, 0.08, 8, 220), ringMat));
    tilt.add(new THREE.Mesh(new THREE.TorusGeometry(rad, 0.36, 8, 220), ringGlow));
    const tail = new THREE.Mesh(new THREE.TorusGeometry(rad, 0.16, 6, 64, TAIL), tailMat);
    tilt.add(tail);
    // the orb: a bright core inside a soft white halo
    const body = new THREE.Group();
    body.add(new THREE.Mesh(new THREE.SphereGeometry(0.3, 16, 12), new THREE.MeshBasicMaterial({ color: 0xffffff })));
    const halo = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, color: 0xffffff, transparent: true, opacity: 0.7, depthWrite: false, blending: THREE.AdditiveBlending }));
    halo.scale.setScalar(2.4);
    body.add(halo);
    tilt.add(body);
    holder.add(tilt);
    rings.add(holder);
    spinners.push({ holder, body, tail, rad, base: deg, period: [26, 34, 42][i], dir: [1, -1, 1][i], phase: i * 2.1 });
  });
  scene.add(rings);

  // Dust motes drifting up through the void. Their motion lives in the vertex
  // shader, so a frame costs the CPU nothing for them.
  const dustN = mobile ? 140 : 260;
  const dustPos = new Float32Array(dustN * 3), dustSeed = new Float32Array(dustN);
  const r = rng(5);
  for (let i = 0; i < dustN; i++) {
    dustPos[i * 3] = (r() - 0.5) * 60; dustPos[i * 3 + 1] = (r() - 0.5) * 40; dustPos[i * 3 + 2] = (r() - 0.5) * 60;
    dustSeed[i] = r();
  }
  const dustGeo = new THREE.BufferGeometry();
  dustGeo.setAttribute('position', new THREE.BufferAttribute(dustPos, 3));
  dustGeo.setAttribute('seed', new THREE.BufferAttribute(dustSeed, 1));
  const dustUniforms = { uTime: uniforms.uTime, uScale: { value: 400 } };
  const dustMat = new THREE.ShaderMaterial({
    uniforms: dustUniforms, vertexShader: DUST_VERT,
    fragmentShader: 'void main() { float d = length(gl_PointCoord - 0.5); if (d > 0.5) discard; gl_FragColor = vec4(vec3(0.88), 0.45 * (1.0 - d * 2.0)); }',
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  });
  const dust = new THREE.Points(dustGeo, dustMat);
  dust.frustumCulled = false;
  scene.add(dust);

  // ---- camera + state
  const camera = new THREE.PerspectiveCamera(42, 1, 0.05, 400);
  const poseValues = name => {
    const q = name === 'hero' && mobile ? (window.innerWidth > 600 ? POSES.heroTablet : POSES.heroMobile) : POSES[name];
    return [...q.pos, ...q.target, q.fov, mobile ? 0 : q.shiftX,
      // phones: sink the island under the content chapters so it never sits behind the text
      mobile && (name === 'versions' || name === 'why') ? q.shiftY - 0.24 : q.shiftY, q.orbit];
  };
  const want = { a: 'hero', b: 'hero', t: 0 };
  // One spring carries the whole camera (position, target, lens, framing, sway),
  // so a scroll jump glides there as one move. A second, softer one smooths the pointer.
  const camSpring = new Spring(poseValues('hero'));
  const pointer = { x: 0, y: 0 }, pointerSpring = new Spring([0, 0]);
  const goal = new Array(10);

  function resize() {
    renderer.setSize(window.innerWidth, window.innerHeight, false);
    camera.aspect = window.innerWidth / window.innerHeight;
    dustUniforms.uScale.value = window.innerHeight * 0.5 * pixelRatio / Math.tan(THREE.MathUtils.degToRad(21));
  }
  resize();
  window.addEventListener('resize', resize);
  window.addEventListener('pointermove', e => {
    if (e.pointerType === 'touch') return;
    pointer.x = e.clientX / window.innerWidth * 2 - 1;
    pointer.y = e.clientY / window.innerHeight * 2 - 1;
  }, { passive: true });

  // ---- loop
  const clock = new THREE.Clock();
  let raf = 0, running = true, slow = 0;
  const A = new Array(10), B = new Array(10);
  const camPos = new THREE.Vector3(), camTarget = new THREE.Vector3(), rel = new THREE.Vector3(), up = new THREE.Vector3(0, 1, 0);

  function step() {
    raf = requestAnimationFrame(step);
    const dt = Math.min(clock.getDelta(), 0.05), now = clock.elapsedTime;
    uniforms.uTime.value = now;

    // Scale the render down if this device can't hold ~40 fps (checked over a
    // second of frames, once), instead of letting the whole page stutter.
    if (pixelRatio > 1 && dt > 0.026) {
      if (++slow > 45) { pixelRatio = 1; renderer.setPixelRatio(1); resize(); }
    } else slow = Math.max(0, slow - 1);

    // camera: the spring chases the blend of the two poses site.js asked for
    const a = poseValues(want.a), b = poseValues(want.b);
    for (let i = 0; i < 10; i++) goal[i] = a[i] + (b[i] - a[i]) * want.t;
    camSpring.step(goal, reducedMotion ? 40 : 2.1, dt);
    pointerSpring.step([pointer.x, pointer.y], reducedMotion ? 40 : 1.6, dt);
    const c = camSpring.x, orbit = c[9];
    camPos.set(c[0], c[1], c[2]);
    camTarget.set(c[3], c[4], c[5]);
    // a slow sway about the target + a little pointer parallax; both are smooth sines/springs
    const sway = reducedMotion ? 0 : Math.sin(now * TAU / 52) * 0.16 * orbit;
    rel.copy(camPos).sub(camTarget).applyAxisAngle(up, sway + (reducedMotion ? 0 : pointerSpring.x[0] * 0.07));
    rel.y += reducedMotion ? 0 : pointerSpring.x[1] * -0.9 + Math.sin(now * TAU / 37) * 0.5 * orbit;
    camera.position.copy(camTarget).add(rel);
    camera.lookAt(camTarget);
    camera.fov = c[6];
    const W = window.innerWidth, H = window.innerHeight;
    camera.setViewOffset(W, H, -c[7] * W, c[8] * H, W, H);
    camera.updateProjectionMatrix();

    // the island floats: slow overlapping sines (no two share a period) so the
    // motion never visibly repeats, and a lazy turn about its own centre
    if (!reducedMotion) {
      const bob = Math.sin(now * TAU / 9) * 0.42 + Math.sin(now * TAU / 5.3 + 1) * 0.12;
      pivot.position.y = bounds.center.y + bob;
      pivot.rotation.set(Math.sin(now * TAU / 17) * 0.012, Math.sin(now * TAU / 31) * 0.045, Math.sin(now * TAU / 13 + 0.6) * 0.012);
    }
    rings.position.copy(pivot.position);

    // rings face the camera and turn like the mark, a bright body riding each
    rings.quaternion.copy(camera.quaternion);
    rings.updateMatrixWorld();
    spinners.forEach((sp, i) => {
      // rings turn like the installer's OrbitMark (26/34/42 s, middle backwards)
      sp.holder.rotation.z = THREE.MathUtils.degToRad(sp.base) + (reducedMotion ? 0 : sp.dir * now * TAU / sp.period);
      const ang = now * TAU / (sp.period * 0.4) + sp.phase;
      sp.body.position.set(Math.cos(ang) * sp.rad, Math.sin(ang) * sp.rad, 0);
      sp.tail.rotation.z = ang - TAIL;
      sp.body.getWorldPosition(uniforms.uOrb.value[i]);
    });

    renderer.render(scene, camera);
  }
  const start = () => { if (!running || raf) return; clock.getDelta(); raf = requestAnimationFrame(step); };
  const stop = () => { cancelAnimationFrame(raf); raf = 0; };
  // a hidden tab does no work at all; coming back picks up without a time jump
  document.addEventListener('visibilitychange', () => (document.hidden ? stop() : start()));
  step();

  return {
    /** Blend between two named poses (t 0..1); the camera glides toward it. */
    setPose(a, b = a, t = 0) { want.a = a; want.b = b; want.t = t; },
    destroy() { running = false; stop(); renderer.dispose(); },
  };
}
