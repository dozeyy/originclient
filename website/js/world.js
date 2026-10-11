// The 3D layer behind the page: a floating island in the sky, built block by
// block from Minecraft's own default textures and lit the way the game lights
// blocks (fixed per-face shade + smooth-lighting corner shadows), with an
// optional "shaders" look (warm low sun, sky fill), and Origin's three rings
// orbiting it so the island is the nucleus of the Origin mark.
//
// site.js owns WHAT is shown (which pose, shaders on/off); this file owns HOW:
// meshing, materials and camera easing.
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
  dirt: 'dirt', stone: 'stone', gravel: 'gravel', cobblestone: 'cobblestone', coarse_dirt: 'coarse_dirt',
  diamond_ore: 'diamond_ore', emerald_ore: 'emerald_ore', glowstone: 'glowstone', oak_planks: 'oak_planks',
  oak_log: ['oak_log', 'oak_log', 'oak_log_top', 'oak_log_top', 'oak_log', 'oak_log'],
  crafting_table: ['crafting_table_side', 'crafting_table_side', 'crafting_table_top', 'oak_planks', 'crafting_table_front', 'crafting_table_front'],
  oak_leaves: 'oak_leaves',
};
const SEE_THROUGH = new Set(['oak_leaves', 'water', 'short_grass', 'dandelion', 'poppy', 'torch']);
const PLANTS = new Set(['short_grass', 'dandelion', 'poppy']);
const EMISSIVE = new Set(['glowstone', 'torch']);

// Camera poses (island space; the island's top sits at y=1). shiftX/shiftY move
// the whole picture by a fraction of the screen; orbit lets it drift around.
const POSES = {
  hero:     { pos: [31, 15, 39], target: [0.5, -2, 0.5], fov: 42, shiftX: 0.2, shiftY: 0.02, orbit: 1 },
  heroMobile: { pos: [46, 24, 56], target: [0.5, -2, 0.5], fov: 54, shiftX: 0, shiftY: -0.3, orbit: 1 },
  // the window replicas float in front of the island and its rings
  install:  { pos: [26, 13, 31], target: [0.5, -2.5, 0.5], fov: 44, shiftX: 0.17, shiftY: 0.02, orbit: 1 },
  launch:   { pos: [-26, 12, 31], target: [0.5, -2.5, 0.5], fov: 44, shiftX: -0.17, shiftY: 0.02, orbit: 1 },
  versions: { pos: [-26, 14, 33], target: [0.5, -2, 0.5], fov: 44, shiftX: 0, shiftY: 0, orbit: 1 },
  shaders:  { pos: [-25, 10, 31], target: [0.5, -2, 0.5], fov: 44, shiftX: 0.2, shiftY: 0.03, orbit: 0.6 },
  get:      { pos: [0, 10, 60], target: [0.5, -2, 0.5], fov: 40, shiftX: 0, shiftY: -0.4, orbit: 1 },
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
      const bottom = -Math.floor(2 + (e - d) * 1.25 + r() * 1.8);
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
  // crafting table + torch + a little path
  const ct = [-2, -5];
  const cth = tops.get(`${ct[0]},${ct[1]}`);
  if (cth !== undefined) set(ct[0], cth + 1, ct[1], 'crafting_table');
  const to = [-4, -4];
  const toh = tops.get(`${to[0]},${to[1]}`);
  if (toh !== undefined) set(to[0], toh + 1, to[1], 'torch');
  for (const [x, z] of [[0, 1], [0, 2], [-1, 3], [1, 0], [2, -1]]) {
    const h = tops.get(`${x},${z}`);
    if (h !== undefined && get(x, h, z) === 'grass') set(x, h, z, 'coarse_dirt');
  }
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
  uniform sampler2D map; uniform float uCut; uniform float uAlpha; uniform float uShaders;
  uniform vec3 uSun; uniform float uEmissive; uniform float uWater; uniform float uTime;
  varying vec2 vUv; varying float vAo; varying float vFace; varying vec3 vN; varying vec3 vW;
  void main() {
    vec2 uv = vUv;
    if (uWater > 0.5) uv += vec2(sin(uTime * 0.6 + vW.z * 0.7) * 0.03, uTime * 0.05);
    vec4 t = texture2D(map, uv);
    if (t.a < uCut) discard;
    vec3 vanilla = t.rgb * vFace * vAo;
    // "Shaders": a low warm sun, cool sky fill, soft contrast grade.
    vec3 n = normalize(vN);
    float ndl = max(dot(n, uSun), 0.0);
    vec3 sun = vec3(1.12, 0.9, 0.68);
    vec3 sky = vec3(0.34, 0.38, 0.56);
    vec3 lit = t.rgb * vAo * (sky + sun * ndl);
    if (uWater > 0.5) {
      vec3 v = normalize(cameraPosition - vW);
      float spec = pow(max(dot(reflect(-uSun, n), v), 0.0), 48.0);
      lit += vec3(1.0, 0.85, 0.6) * spec * 1.4;
    }
    lit = mix(lit, lit * lit * 1.6, 0.18) * vec3(1.04, 1.0, 0.94);
    vec3 col = mix(vanilla, lit, uShaders);
    col = mix(col, t.rgb * 1.15, uEmissive);
    gl_FragColor = vec4(col, t.a * uAlpha);
  }`;

function blockMaterial(tex, uniforms, opts = {}) {
  return new THREE.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG,
    uniforms: {
      map: { value: tex }, uCut: { value: opts.cut ?? 0.5 }, uAlpha: { value: opts.alpha ?? 1 },
      uEmissive: { value: opts.emissive ? 1 : 0 }, uWater: { value: opts.water ? 1 : 0 },
      uShaders: uniforms.uShaders, uSun: uniforms.uSun, uTime: uniforms.uTime,
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
export async function createWorld(canvas, { mobile = false, reducedMotion = false, shaders = false } = {}) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'high-performance' });
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  renderer.setClearColor(0x000000, 0);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, mobile ? 1.5 : 1.75));

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
    textures[n] = t;
  }));

  const uniforms = {
    uShaders: { value: shaders ? 1 : 0 },
    uSun: { value: new THREE.Vector3(-0.45, 0.72, 0.52).normalize() },
    uTime: { value: 0 },
  };

  // ---- island scene
  const scene = new THREE.Scene();
  const island = generateIsland();
  const blocks = meshIsland(island, textures, uniforms);
  scene.add(blocks);

  const glowTex = glowTexture();
  const glows = new THREE.Group();
  scene.add(glows);
  for (const [k, t] of island.map) {
    if (!EMISSIVE.has(t)) continue;
    const [x, y, z] = k.split(',').map(Number);
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, color: t === 'torch' ? 0xffb84d : 0xffd27a, transparent: true, opacity: 0.55, depthWrite: false, blending: THREE.AdditiveBlending }));
    s.position.set(x + 0.5, y + (t === 'torch' ? 0.7 : 0.5), z + 0.5);
    s.scale.setScalar(t === 'torch' ? 2.2 : 3.4);
    glows.add(s);
  }

  // Origin's rings: the mark's three orbits (ellipse ratio 0.355 = a circle
  // tilted ~69 deg) at 0/60/120 deg, circling the whole island and always
  // turned to the camera so it reads as the logo from anywhere. Ion-jade glow,
  // warm-white strokes; depth-tested, so they pass behind the island.
  const rings = new THREE.Group();
  const ringMat = new THREE.MeshBasicMaterial({ color: 0xf1e9d2, transparent: true, opacity: 0.95 });
  const ringGlow = new THREE.MeshBasicMaterial({ color: 0x4bc8ae, transparent: true, opacity: 0.22, blending: THREE.AdditiveBlending, depthWrite: false });
  const spinners = [];
  [[14.4, 0], [12, 60], [9.6, 120]].forEach(([rad, deg], i) => {
    const holder = new THREE.Group();
    holder.rotation.z = THREE.MathUtils.degToRad(deg);
    const tilt = new THREE.Group();
    tilt.rotation.x = Math.acos(0.355);
    tilt.add(new THREE.Mesh(new THREE.TorusGeometry(rad, 0.07, 8, 200), ringMat));
    tilt.add(new THREE.Mesh(new THREE.TorusGeometry(rad, 0.32, 8, 200), ringGlow));
    const body = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, color: 0xbff5e3, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
    body.scale.setScalar(i === 0 ? 2.6 : 1.7);
    tilt.add(body);
    holder.add(tilt);
    rings.add(holder);
    spinners.push({ holder, body, rad, base: deg, period: [26, 34, 42][i], phase: i * 2.1 });
  });
  rings.position.set(0.5, -1.5, 0.5);   // the island is the mark's nucleus
  scene.add(rings);

  // Dust motes drifting up through the void.
  const dustN = mobile ? 140 : 260;
  const dustPos = new Float32Array(dustN * 3);
  const r = rng(5);
  for (let i = 0; i < dustN; i++) {
    dustPos[i * 3] = (r() - 0.5) * 60; dustPos[i * 3 + 1] = (r() - 0.5) * 40; dustPos[i * 3 + 2] = (r() - 0.5) * 60;
  }
  const dustGeo = new THREE.BufferGeometry();
  dustGeo.setAttribute('position', new THREE.BufferAttribute(dustPos, 3));
  const dustMat = new THREE.PointsMaterial({ color: 0xf1e9d2, size: 0.12, transparent: true, opacity: 0.5, depthWrite: false, blending: THREE.AdditiveBlending });
  const dust = new THREE.Points(dustGeo, dustMat);
  scene.add(dust);

  // ---- camera + state
  const camera = new THREE.PerspectiveCamera(42, 1, 0.05, 400);
  const cur = { pos: new THREE.Vector3(...POSES.hero.pos), target: new THREE.Vector3(...POSES.hero.target), fov: 42, shiftX: 0.2, shiftY: 0 };
  const want = { a: 'hero', b: 'hero', t: 0 };
  const pointer = { x: 0, y: 0, sx: 0, sy: 0 };
  let shadersWant = shaders ? 1 : 0, orbitAngle = 0;

  function poseVec(name) {
    const q = name === 'hero' && mobile ? POSES.heroMobile : POSES[name];
    return { pos: new THREE.Vector3(...q.pos), target: new THREE.Vector3(...q.target), fov: q.fov, shiftX: mobile ? 0 : q.shiftX, shiftY: q.shiftY, orbit: q.orbit };
  }

  function resize() {
    renderer.setSize(window.innerWidth, window.innerHeight, false);
    camera.aspect = window.innerWidth / window.innerHeight;
  }
  resize();
  window.addEventListener('resize', resize);
  window.addEventListener('pointermove', e => {
    pointer.x = e.clientX / window.innerWidth * 2 - 1;
    pointer.y = e.clientY / window.innerHeight * 2 - 1;
  }, { passive: true });

  // ---- loop
  const clock = new THREE.Clock();
  let running = true, visible = true;
  const tmpPos = new THREE.Vector3(), tmpTarget = new THREE.Vector3(), up = new THREE.Vector3(0, 1, 0);

  function step() {
    if (!running) return;
    requestAnimationFrame(step);
    if (!visible) return;
    const dt = Math.min(clock.getDelta(), 0.05), now = clock.elapsedTime;
    uniforms.uTime.value = now;
    const ease = 1 - Math.exp(-dt * (reducedMotion ? 30 : 4.2));
    uniforms.uShaders.value += (shadersWant - uniforms.uShaders.value) * (1 - Math.exp(-dt * 2.5));

    // camera: ease toward the blend of the two poses site.js asked for
    const A = poseVec(want.a), B = poseVec(want.b), t = want.t;
    tmpPos.lerpVectors(A.pos, B.pos, t);
    tmpTarget.lerpVectors(A.target, B.target, t);
    const orbit = A.orbit + (B.orbit - A.orbit) * t;
    if (!reducedMotion) orbitAngle += dt * 0.045 * orbit;
    pointer.sx += (pointer.x - pointer.sx) * (1 - Math.exp(-dt * 3));
    pointer.sy += (pointer.y - pointer.sy) * (1 - Math.exp(-dt * 3));
    // a slow orbit + pointer parallax swing the camera around the target
    const rel = tmpPos.clone().sub(tmpTarget);
    rel.applyAxisAngle(up, orbitAngle * orbit + pointer.sx * 0.12);
    rel.y += pointer.sy * -1.5;
    tmpPos.copy(tmpTarget).add(rel);
    cur.pos.lerp(tmpPos, ease);
    cur.target.lerp(tmpTarget, ease);
    cur.fov += ((A.fov + (B.fov - A.fov) * t) - cur.fov) * ease;
    cur.shiftX += ((A.shiftX + (B.shiftX - A.shiftX) * t) - cur.shiftX) * ease;
    cur.shiftY += ((A.shiftY + (B.shiftY - A.shiftY) * t) - cur.shiftY) * ease;
    camera.position.copy(cur.pos);
    camera.lookAt(cur.target);
    camera.fov = cur.fov;
    const W = window.innerWidth, H = window.innerHeight;
    camera.setViewOffset(W, H, -cur.shiftX * W, cur.shiftY * H, W, H);
    camera.updateProjectionMatrix();

    // rings face the camera and turn like the mark, a bright body riding each
    rings.quaternion.copy(camera.quaternion);
    ringGlow.opacity = 0.2 + 0.1 * uniforms.uShaders.value;
    for (const sp of spinners) {
      sp.holder.rotation.z = THREE.MathUtils.degToRad(sp.base) + (reducedMotion ? 0 : now * Math.PI * 2 / sp.period);
      const a = now * Math.PI * 2 / (sp.period * 0.4) + sp.phase;
      sp.body.position.set(Math.cos(a) * sp.rad, Math.sin(a) * sp.rad, 0);
    }
    const p = dust.geometry.attributes.position;
    for (let i = 0; i < dustN; i++) {
      let y = p.array[i * 3 + 1] + dt * 0.35;
      if (y > 20) y = -20;
      p.array[i * 3 + 1] = y;
    }
    p.needsUpdate = true;
    glows.children.forEach(g => { g.material.opacity = 0.45 + 0.35 * uniforms.uShaders.value; });

    renderer.render(scene, camera);
  }
  step();
  document.addEventListener('visibilitychange', () => { visible = !document.hidden; clock.getDelta(); });

  return {
    /** Blend between two named poses (t 0..1); the camera eases toward it. */
    setPose(a, b = a, t = 0) { want.a = a; want.b = b; want.t = t; },
    setShaders(on) { shadersWant = on ? 1 : 0; },
    destroy() { running = false; renderer.dispose(); },
  };
}
