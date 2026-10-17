// Origin store 3D: the player mannequin, the fitting room and the shop-card
// thumbnails. What an item LOOKS like is not in here any more: every item is a
// pair of files (website/cosmetics/<id>/item.json + texture.png) written by
// tools/cosmetics/build.mjs from cosmetics/items/<id>/item.mjs, and js/rig.js
// turns them into 3D. Contract: cosmetics/FORMAT.md.
//
// The player model follows Minecraft's own: 1 unit = 1 skin pixel, a 64x64
// skin with the standard UV layout, classic (4px) arms.
import * as THREE from './vendor/three.module.min.js';
import { loadItem, loadCatalog, buildRig, loopSeconds } from './rig.js';

export { loadCatalog, loopSeconds };
const TAU = Math.PI * 2;
export const STILL_T = 0.36;           // the moment shown when nothing may move

const makeCanvas = (w, h) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; };
const rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const css = (c, k = 1) => `rgb(${c.map(v => Math.max(0, Math.min(255, Math.round(v * k)))).join(',')})`;
function rng(seed) {
  return () => {
    seed = seed + 0x6D2B79F5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

// ----------------------------------------------------------- item sources
const sources = new Map();       // id -> Promise<{ item, image }>
const resolved = new Map();      // id -> { item, image }, once loaded (for the synchronous thumbnail path)
let BASE = null;
const defaultBase = () => BASE || new URL('cosmetics', document.baseURI).href;
export function getSource(id, base = defaultBase()) {
  if (!sources.has(id)) sources.set(id, loadItem(base, id).then(s => { resolved.set(id, s); return s; }, err => { sources.delete(id); throw err; }));
  return sources.get(id);
}
export function setBase(base) { BASE = base; }

// ----------------------------------------------------------- shaded geometry
// Minecraft-style fixed face light, baked into vertex colours. BoxGeometry's
// faces come in the order +x, -x, +y, -y, +z, -z, 4 vertices each.
const FACE_SHADE = [0.72, 0.72, 1, 0.5, 0.95, 0.95];
function shadedBox(w, h, d) {
  const g = new THREE.BoxGeometry(w, h, d), col = [];
  for (let f = 0; f < 6; f++) for (let i = 0; i < 4; i++) col.push(FACE_SHADE[f], FACE_SHADE[f], FACE_SHADE[f]);
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  return g;
}
function disposeTree(obj) {
  obj.traverse(o => {
    if (!o.isMesh) return;
    o.geometry.dispose();
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
function pixelTexture(source) {
  const t = new THREE.CanvasTexture(source);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.generateMipmaps = false;
  return t;
}

// A neutral stand-in until a real skin loads: clay mannequin in an Origin hoodie.
let mannequin = null;
function mannequinSkin() {
  if (mannequin) return mannequin;
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
  mannequin = pixelTexture(c);
  return mannequin;
}

// The player. Cosmetics hang on two bones (FORMAT.md): `head` (pivot = the neck) and
// `body` (pivot = top centre of the torso).
function buildPlayer() {
  const root = new THREE.Group();
  const body = new THREE.Group();          // skin meshes, rebuilt when the skin changes
  const headBone = new THREE.Group(), bodyBone = new THREE.Group();
  headBone.position.set(0, 24, 0);
  bodyBone.position.set(0, 24, 0);
  root.add(body, headBone, bodyBone);
  const limbs = {};

  function setSkin(tex, slim = false) {
    disposeTree(body); body.clear();
    disposeTree(headBone.userData.skin || new THREE.Group());
    headBone.userData.skin?.removeFromParent();
    const img = tex.image, W = img.width || 64, H = img.height || 64;
    const legacy = H === W / 2;       // pre-1.8 64x32 skins: no overlays, mirrored limbs
    const s = W / 64;                 // HD skins scale the whole layout
    const headSkin = new THREE.Group();
    headBone.add(headSkin);
    headBone.userData.skin = headSkin;
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
      if (p.name === 'head') { holder.position.set(0, 4, 0); headSkin.add(holder); } else body.add(holder);
    }
  }
  setSkin(mannequinSkin());

  // worn: rigs from rig.js. hidesCape / capeOffset follow FORMAT.md.
  let worn = [];
  function setWorn(rigs) {
    for (const r of worn) r.group.removeFromParent();
    worn = rigs;
    const hides = rigs.some(r => r.item.hidesCape);
    const offset = Math.max(0, ...rigs.filter(r => !r.item.cape).map(r => r.item.capeOffset || 0));
    for (const r of rigs) {
      const slot = r.item.slot;
      (slot === 'head' || slot === 'face' ? headBone : bodyBone).add(r.group);
      if (r.item.cape) { r.group.visible = !hides; r.setCapeOffset(offset); }
    }
  }
  // ctx: { t, walk 0..1, air 0..1, still, headYaw, headPitch } (angles in degrees)
  function pose(ctx) {
    const breathe = ctx.still ? 0 : 0.06 * Math.sin(ctx.t * 2.6);
    const gait = ctx.still ? 0 : Math.sin(ctx.t * 7) * 0.9 * ctx.walk;
    limbs.armR.rotation.set(breathe - gait, 0, -0.06);   // arms hang slightly out from the body
    limbs.armL.rotation.set(-breathe + gait, 0, 0.06);
    limbs.legR.rotation.set(gait, 0, 0);
    limbs.legL.rotation.set(-gait, 0, 0);
    headBone.rotation.set(((ctx.headPitch || 0) * Math.PI) / 180, ((ctx.headYaw || 0) * Math.PI) / 180, 0);
    for (const r of worn) r.update(ctx);
  }
  return { root, setSkin, setWorn, pose };
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

// ------------------------------------------------------------------- stage
// Yaw 0 = the player faces the camera; PI = back to the camera.
function aim(camera, v) {
  camera.position.set(0, v.look + v.lift, v.dist);
  camera.lookAt(0, v.look, 0);
}
// Framing that best shows an item (item.view overrides).
export function viewFor(item, thumb = false) {
  if (item?.view) return { ...item.view };
  const kind = item?.kind;
  if (thumb) {
    if (kind === 'cape') return { yaw: Math.PI - 0.55, look: 18, dist: 56, lift: 4 };
    if (kind === 'wings') return { yaw: Math.PI - 0.45, look: 19, dist: 74, lift: 8 };
    if (kind === 'pack') return { yaw: Math.PI - 0.5, look: 19, dist: 52, lift: 5 };
    if (kind === 'mask' || kind === 'bandana') return { yaw: 0.4, look: 29.5, dist: 34, lift: 4 };
    return { yaw: 0.55, look: 32, dist: 42, lift: 10 };
  }
  if (kind === 'cape') return { yaw: Math.PI - 0.6, look: 18, dist: 80, lift: 6 };
  if (kind === 'wings') return { yaw: Math.PI - 0.5, look: 20, dist: 92, lift: 10 };
  if (kind === 'pack') return { yaw: Math.PI - 0.5, look: 19, dist: 72, lift: 8 };
  if (kind === 'mask' || kind === 'bandana') return { yaw: 0.35, look: 26, dist: 62, lift: 6 };
  if (kind) return { yaw: 0.5, look: 24, dist: 80, lift: 8 };
  return { yaw: 0.5, look: 18, dist: 80, lift: 6 };
}

// A WebGL stage with the mannequin on it. Throws if WebGL is unavailable.
//   wear(ids)  loads and puts on these items (any mix, one per slot)
//   draw(v)    renders: v = { yaw, look, dist, lift, fov?, t, walk?, air?, still? }
export function createStage(canvas, { base, pixelRatio = 1, alpha = true } = {}) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha, preserveDrawingBuffer: true });
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  renderer.setPixelRatio(pixelRatio);
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(30, 1, 1, 500);
  const player = buildPlayer();
  scene.add(player.root, floorShadow());
  const rigs = new Map();
  // Synchronous once the items are loaded (await getSource / prepare first).
  function wearNow(ids) {
    player.setWorn(ids.map(id => {
      if (!rigs.has(id)) { const s = resolved.get(id); if (!s) throw new Error(`item ${id} is not loaded`); rigs.set(id, buildRig(s.item, s)); }
      return rigs.get(id);
    }));
    return ids.map(id => resolved.get(id).item);
  }
  async function wear(ids) {
    await Promise.all(ids.map(id => getSource(id, base)));
    return wearNow(ids);
  }
  function draw(v) {
    if (v.fov) { camera.fov = v.fov; camera.updateProjectionMatrix(); }
    aim(camera, v);
    player.root.rotation.y = v.yaw;
    player.pose({ t: v.t ?? STILL_T, walk: v.walk || 0, air: v.air || 0, still: !!v.still, headYaw: v.headYaw, headPitch: v.headPitch });
    renderer.render(scene, camera);
  }
  function dispose() { for (const r of rigs.values()) r.dispose(); rigs.clear(); renderer.dispose(); renderer.forceContextLoss(); }
  return { renderer, scene, camera, player, wear, wearNow, draw, dispose, setSkin: (skin) => player.setSkin(skin?.tex || mannequinSkin(), skin?.slim) };
}

// ------------------------------------------------------------ fitting room
// The shopper's try-on: everything in `ids` at once. Drag turns the player.
export function createFittingRoom(canvas, { reducedMotion = false } = {}) {
  const st = createStage(canvas, { pixelRatio: Math.min(window.devicePixelRatio || 1, 2) });
  const { renderer, camera } = st;
  let view = viewFor(null), yaw = view.yaw, holdUntil = 0, drag = null, visible = true, raf = 0, last = performance.now();
  const cam = { ...view };
  let seq = 0;

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
    st.draw({ ...cam, yaw, t: reducedMotion ? STILL_T : now / 1000, still: reducedMotion });
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
    // ids: items to wear (one per slot); focus: the one just picked, which sets the camera
    async wear(ids, focus = ids[ids.length - 1]) {
      const mine = ++seq;
      let items;
      try { items = await st.wear(ids); } catch (err) { console.warn('Origin store: could not load an item for the preview.', err); return; }
      if (mine !== seq) return;
      const f = items[ids.indexOf(focus)];
      view = viewFor(f || null);
      holdUntil = performance.now() + 4500;
      wake();
    },
    // skin: { tex, slim } from loadSkin, or null for the mannequin
    setSkin(skin) { st.setSkin(skin); wake(); },
  };
}

// Loads a player's real skin. player: { uuid, skinUrl?, slim? } from the name
// lookup. Resolves { tex, slim }, or null (the caller keeps the mannequin).
// WebGL can only use images served with CORS headers, so this tries, in order:
// the skin file on Mojang's own texture server, then two skin mirrors.
// (Crafatar was dropped 2026-10-12: when it can't reach Mojang it answers
// with a default Steve instead of an error, so players saw Steve.)
export async function loadSkin(player) {
  const skinLoader = new THREE.TextureLoader().setCrossOrigin('anonymous');
  const urls = [
    player.skinUrl && player.skinUrl.replace(/^http:/, 'https:'),
    `https://crafthead.net/skin/${player.uuid}`,
    `https://mc-heads.net/skin/${player.uuid}`,
  ].filter(Boolean);
  for (const url of urls) {
    try {
      const tex = await Promise.race([
        skinLoader.loadAsync(url),
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
// unavailable. Items must be loaded (await getSource(id)) first. Call dispose()
// when done to free the GPU context.
export function createThumbnailer(size = 320) {
  const canvas = makeCanvas(size, size);
  const st = createStage(canvas, { pixelRatio: 1 });
  st.renderer.setSize(size, size, false);
  let wornId = null;
  const shot = (id, t, still, type) => {
    if (wornId !== id) { st.wearNow([id]); wornId = id; }
    st.draw({ ...viewFor(resolved.get(id).item, true), t, still });
    return canvas.toDataURL(type, 0.86);
  };
  return {
    still: id => shot(id, STILL_T, true, 'image/png'),
    frame: (id, i, n) => shot(id, (i / n) * loopOf(id), false, 'image/webp'),
    dispose() { st.dispose(); },
  };
}
const loopCache = new Map();
export function loopOf(id) { return loopCache.get(id) || 2.4; }
export async function prepare(ids) {
  const all = await Promise.all(ids.map(id => getSource(id)));
  all.forEach((s, i) => loopCache.set(ids[i], loopSeconds(s.item)));
}
