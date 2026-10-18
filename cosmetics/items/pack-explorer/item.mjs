// Explorer Pack: stub (being drawn).
import { newTexture } from '../../lib/model.mjs';
const B = [[-3,-9,-5],[3,-2,-2]];
const model = { texSize: [32, 32], scale: 4, bones: [{ name: 'root', pivot: [0, 0, 0], cubes: [{ from: B[0], to: B[1], uv: [0, 0] }] }] };
export default {
  meta: { id: 'pack-explorer', name: 'Explorer Pack', kind: 'pack', cents: 849, blurb: 'Explorer Pack.', capeOffset: 3 },
  model,
  paint() { const t = newTexture(model); t.rect(0, 0, t.w, t.h, '#808080'); return t; },
};
