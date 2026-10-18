// Orbit Halo: stub (being drawn).
import { newTexture } from '../../lib/model.mjs';
const B = [[-4,10.5,-4],[4,11,4]];
const model = { texSize: [32, 32], scale: 4, bones: [{ name: 'root', pivot: [0, 0, 0], cubes: [{ from: B[0], to: B[1], uv: [0, 0] }] }] };
export default {
  meta: { id: 'halo-orbit', name: 'Orbit Halo', kind: 'halo', cents: 799, blurb: 'Orbit Halo.' },
  model,
  paint() { const t = newTexture(model); t.rect(0, 0, t.w, t.h, '#808080'); return t; },
};
