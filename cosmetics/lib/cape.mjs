// Cape sheet assembly: the vanilla cape layout (64x32 model px) at `scale`.
//
//   (S,0)    10S x S    top edge        (11S,0)  10S x S    bottom edge
//   (0,S)    S x 16S    left edge       (11S,S)  S x 16S    right edge
//   (S,S)    10S x 16S  OUTSIDE (seen from behind)
//   (12S,S)  10S x 16S  inside (against the back)
//
// Paint the outside as a 10S x 16S canvas (S = 8 for static capes: an 80x128 pixel
// picture), then composeCape() builds the whole sheet: the four thin edge faces
// continue the art's border colours (never flat grey), the inside is the same art
// dimmed and cooled like a lining.
import { Canvas, rgb2hsl, hsl2rgb } from './px.mjs';

export const CAPE_W = 10, CAPE_H = 16;
export const capeSize = scale => ({ w: CAPE_W * scale, h: CAPE_H * scale });
export const newCapeArt = scale => new Canvas(CAPE_W * scale, CAPE_H * scale);

export function capeRects(S) {
  return {
    top: [S, 0, 10 * S, S], bottom: [11 * S, 0, 10 * S, S],
    left: [0, S, S, 16 * S], right: [11 * S, S, S, 16 * S],
    outside: [S, S, 10 * S, 16 * S], inside: [12 * S, S, 10 * S, 16 * S],
  };
}

export function composeCape(art, scale, o = {}) {
  const S = scale, sheet = new Canvas(64 * S, 32 * S), r = capeRects(S);
  if (art.w !== 10 * S || art.h !== 16 * S) throw new Error(`cape art must be ${10 * S}x${16 * S}, got ${art.w}x${art.h}`);
  sheet.blit(art, r.outside[0], r.outside[1]);
  // edges: the art's outermost row/column, repeated across the 1-model-px thickness
  for (let y = 0; y < S; y++) for (let x = 0; x < 10 * S; x++) {
    sheet.set(r.top[0] + x, r.top[1] + y, art.get(x, 0));
    sheet.set(r.bottom[0] + x, r.bottom[1] + y, art.get(x, 16 * S - 1));
  }
  for (let y = 0; y < 16 * S; y++) for (let x = 0; x < S; x++) {
    sheet.set(r.left[0] + x, r.left[1] + y, art.get(0, y));
    sheet.set(r.right[0] + x, r.right[1] + y, art.get(10 * S - 1, y));
  }
  const lining = o.inside || (c => { const [h, s, l] = rgb2hsl(c); return hsl2rgb(h + 8, s * 0.8, l * 0.55, c[3]); });
  const flipped = art.flipX();
  for (let y = 0; y < art.h; y++) for (let x = 0; x < art.w; x++) {
    const c = flipped.get(x, y);
    if (c[3]) sheet.set(r.inside[0] + x, r.inside[1] + y, lining(c, x, y));
  }
  return sheet;
}
