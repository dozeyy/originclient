// Model helpers: box-UV layout and per-face painting (cosmetics/FORMAT.md "Models").
//
// UNITS. Cube coordinates and `uv` are in model px (1/16 block). Texture pixels =
// model px * scale. For UV to be exact every cube dimension (to - from, NOT
// counting `inflate`) and every uv origin times `scale` must be a whole number;
// `checkModel` enforces it. Face rectangles are therefore whole texture pixels.
//
// BOX UV (vanilla / Blockbench), for a cube of size w x h d (model px) with uv
// origin (u, v) - rectangles are [x, y, width, height] in model px:
//   up    (u+d,     v,   w, d)      down  (u+d+w,   v,   w, d)
//   east  (u,       v+d, d, h)      north (u+d,     v+d, w, h)
//   west  (u+d+w,   v+d, d, h)      south (u+2d+w,  v+d, w, h)
// north = -z (the side facing the player's BACK), south = +z, east = +x (the
// player's left), west = -x, up = +y. Each face is read as seen from OUTSIDE the
// cube, with +y up in the image; so on north the image's left edge is +x, on
// south it is -x, on east it is +z (front), on west it is -z, on up the top edge
// is +z (front), on down the top edge is -z (back) - and left is +x for up/down.
import { Canvas } from './px.mjs';

export { FACES, cubeSize, cubeRects, cubeFaceNames } from '../../website/js/uv.mjs';
import { FACES, cubeSize, cubeRects, cubeFaceNames } from '../../website/js/uv.mjs';
const near = (a, b = Math.round(a)) => Math.abs(a - b) < 1e-6;

// Footprint of a cube's box UV in texture px: { x, y, w, h } (2d+2w by d+h).
export function footprint(cube, scale) {
  if (cube.faces) {
    const rs = cubeFaceNames(cube).map(f => cubeRects(cube, scale)[f]);
    const x0 = Math.min(...rs.map(r => r.x)), y0 = Math.min(...rs.map(r => r.y));
    return { x: x0, y: y0, w: Math.max(...rs.map(r => r.x + r.w)) - x0, h: Math.max(...rs.map(r => r.y + r.h)) - y0 };
  }
  const [w, h, d] = cubeSize(cube).map(v => Math.round(v * scale)), [u, v] = (cube.uv || [0, 0]).map(n => Math.round(n * scale));
  return { x: u, y: v, w: 2 * d + 2 * w, h: d + h };
}

// Assign a non-overlapping `uv` to every cube without custom `faces` (shelf
// packing, tallest first, deterministic). Mutates and returns the cubes.
// `texSize` in model px. Throws when they do not fit.
export function layoutCubes(cubes, texSize, scale, { pad = 0 } = {}) {
  const W = texSize[0] * scale, H = texSize[1] * scale;
  const todo = cubes.filter(c => !c.faces).map((c, i) => ({ c, i, f: footprint({ ...c, uv: [0, 0] }, scale) }));
  todo.sort((a, b) => b.f.h - a.f.h || b.f.w - a.f.w || a.i - b.i);
  let x = 0, y = 0, rowH = 0;
  for (const t of todo) {
    if (x + t.f.w > W) { x = 0; y += rowH + pad; rowH = 0; }
    if (y + t.f.h > H) throw new Error(`layoutCubes: cubes do not fit a ${texSize} @${scale} texture`);
    t.c.uv = [x / scale, y / scale];
    x += t.f.w + pad; rowH = Math.max(rowH, t.f.h);
  }
  return cubes;
}

// Validate a model: whole-pixel dimensions, UV inside the texture, no two cubes'
// footprints overlapping. Returns a list of problems (empty = fine).
export function checkModel(model) {
  const bad = [], { texSize, scale } = model, W = texSize[0] * scale, H = texSize[1] * scale, seen = [];
  for (const bone of model.bones) for (const [i, cube] of (bone.cubes || []).entries()) {
    const tag = `${bone.name}[${i}]`;
    if (!cube.faces && !cubeSize(cube).every(v => near(v * scale))) bad.push(`${tag}: size ${cubeSize(cube)} x scale ${scale} is not whole texture pixels`);
    if (!cube.faces && !(cube.uv || [0, 0]).every(v => near(v * scale))) bad.push(`${tag}: uv ${cube.uv} x scale is not whole pixels`);
    const rs = cube.faces ? cubeFaceNames(cube).map(f => cubeRects(cube, scale)[f]) : [footprint(cube, scale)];
    for (const r of rs) if (r.x < 0 || r.y < 0 || r.x + r.w > W || r.y + r.h > H) bad.push(`${tag}: UV outside the ${W}x${H} texture`);
    if (!cube.faces) {
      const f = footprint(cube, scale);
      for (const o of seen) if (f.x < o.f.x + o.f.w && o.f.x < f.x + f.w && f.y < o.f.y + o.f.h && o.f.y < f.y + f.h) bad.push(`${tag}: UV overlaps ${o.tag}`);
      seen.push({ tag, f });
    }
  }
  return bad;
}

// ---------------------------------------------------------------- painting
// A drawing window onto one face of a cube: coordinates are local to the face
// (x right, y down, as seen from outside), so painting reads like painting the
// face directly. Returns a Canvas view.
export function faceView(canvas, cube, scale, face) {
  const r = cubeRects(cube, scale)[face];
  return canvas.view(r.x, r.y, r.w, r.h);
}
// Paint every drawn face of a cube: fn(view, face, rect). Handy with a ramp and the
// light direction: top-front-left light means up > north/south... see lightOf().
export function paintCube(canvas, cube, scale, fn) {
  const rects = cubeRects(cube, scale);
  for (const f of cubeFaceNames(cube)) fn(canvas.view(rects[f].x, rects[f].y, rects[f].w, rects[f].h), f, rects[f]);
}
// How lit each face is for a light from the top, front and (player's) left:
// 0..1, use it to choose a ramp step: up brightest, down darkest.
export const LIGHT = { up: 1, south: 0.8, east: 0.66, north: 0.55, west: 0.4, down: 0.22 };

// A fresh transparent texture for a model: texSize * scale.
export const newTexture = model => new Canvas(model.texSize[0] * model.scale, model.texSize[1] * model.scale);

// A cube literal with sane defaults.
export const cube = (from, to, extra = {}) => ({ from, to, uv: [0, 0], ...extra });
