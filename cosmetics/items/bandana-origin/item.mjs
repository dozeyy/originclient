// Origin Bandana: stub (being drawn).
import { newTexture } from '../../lib/model.mjs';
const S = 8, TEX = [32, 16];
export default {
  meta: { id: 'bandana-origin', name: 'Origin Bandana', kind: 'bandana', cents: 599, blurb: 'Charcoal cloth over the nose and mouth, knotted at the back.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'bandana', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: [{ from: [-4.5, 0.5, 4], to: [4.5, 3, 4.5], uv: [0, 0] }] }] },
  paint() { const t = newTexture({ texSize: TEX, scale: S }); t.fill('#26272d'); return t; },
};
