// Box-UV maths shared by the cosmetics art build (cosmetics/lib/model.mjs, Node)
// and the store/viewer renderer (js/rig.js, browser). One copy so they cannot
// disagree. No imports: must run in both. Contract: cosmetics/FORMAT.md.
//
// Units: cube coords and `uv` are model px; rectangles returned are TEXTURE px
// (model px * scale), whole numbers by contract.
//
// Box UV for a cube of size w x h x d at uv origin (u, v) [model px]:
//   up    (u+d,     v,   w, d)      down  (u+d+w,   v,   w, d)
//   east  (u,       v+d, d, h)      north (u+d,     v+d, w, h)
//   west  (u+d+w,   v+d, d, h)      south (u+2d+w,  v+d, w, h)
// north = -z (the player's back side), south = +z, east = +x (player's left),
// west = -x. Every face image is read as seen from OUTSIDE the cube, +y up.

export const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
export const cubeSize = c => c.to.map((v, i) => v - c.from[i]);          // [w, h, d]

export function cubeRects(cube, scale) {
  const [w, h, d] = cubeSize(cube).map(v => Math.round(v * scale));
  const [u, v] = (cube.uv || [0, 0]).map(n => Math.round(n * scale));
  const box = {
    up: [u + d, v, w, d], down: [u + d + w, v, w, d],
    east: [u, v + d, d, h], north: [u + d, v + d, w, h],
    west: [u + d + w, v + d, d, h], south: [u + 2 * d + w, v + d, w, h],
  };
  const out = {};
  for (const f of FACES) {
    let [x, y, fw, fh] = box[f];
    if (cube.mirror) { const m = { east: 'west', west: 'east' }[f]; if (m) [x, y, fw, fh] = box[m]; }
    out[f] = { x, y, w: fw, h: fh, flip: !!cube.mirror };
    if (cube.faces?.[f]) {
      const [u0, v0, u1, v1] = cube.faces[f].map(n => Math.round(n * scale));
      out[f] = { x: Math.min(u0, u1), y: Math.min(v0, v1), w: Math.abs(u1 - u0), h: Math.abs(v1 - v0), flip: u1 < u0 };
    }
  }
  return out;
}
// Faces actually drawn: all six, or only those listed in a cube's `faces`.
export const cubeFaceNames = cube => (cube.faces ? FACES.filter(f => cube.faces[f]) : FACES);

// The four corners of a face as [TL, TR, BL, BR] of the face IMAGE, in cube
// space, for a cube spanning (x0,y0,z0)-(x1,y1,z1).
export function faceCorners(face, [x0, y0, z0], [x1, y1, z1]) {
  switch (face) {
    case 'north': return [[x1, y1, z0], [x0, y1, z0], [x1, y0, z0], [x0, y0, z0]];
    case 'south': return [[x0, y1, z1], [x1, y1, z1], [x0, y0, z1], [x1, y0, z1]];
    case 'east': return [[x1, y1, z1], [x1, y1, z0], [x1, y0, z1], [x1, y0, z0]];
    case 'west': return [[x0, y1, z0], [x0, y1, z1], [x0, y0, z0], [x0, y0, z1]];
    case 'up': return [[x1, y1, z1], [x0, y1, z1], [x1, y1, z0], [x0, y1, z0]];
    case 'down': return [[x1, y0, z0], [x0, y0, z0], [x1, y0, z1], [x0, y0, z1]];
    default: throw new Error(`bad face ${face}`);
  }
}
export const FACE_NORMAL = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
// Minecraft's fixed face light, used for shading non-emissive faces.
export const FACE_SHADE = { up: 1, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6 };
