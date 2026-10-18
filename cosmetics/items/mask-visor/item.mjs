// Visor Mask: stub (being drawn).
import { newTexture } from '../../lib/model.mjs';
const S = 8, TEX = [32, 16];
export default {
  meta: { id: 'mask-visor', name: 'Visor Mask', kind: 'mask', cents: 699, blurb: 'A slim smoked-glass visor across the eyes.' },
  model: { texSize: TEX, scale: S, bones: [{ name: 'visor', pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: [{ from: [-4.25, 2.75, 4], to: [4.25, 4.75, 4.5], uv: [0, 0] }] }] },
  paint() { const t = newTexture({ texSize: TEX, scale: S }); t.fill('#15171c'); return t; },
};
