# Origin cosmetics v2: one source per item

Will, 2026-10-17: the round-1 art was rejected ("20x better", logo looked
crunched, crown too big to see over, Golden Hour looked like Roblox). Lunar
Client is the template: if Lunar is 10/10, every Origin item must judge >= 8,
and an item that can't get there in 3 tries is dropped. HD pixel art.

This file is THE contract between the art sources, the store website, the
store service and the mod. Change it here first, then every consumer.

## Layout

```
cosmetics/
  FORMAT.md                this file
  lib/                     shared painting + model helpers (pixel grid, ramps, PNG writer, marks)
  items/<id>/
    item.mjs               the source: exports default { meta, model?, paint(), ... } (below)
  out/                     NOT committed: build scratch
tools/cosmetics/build.mjs  runs every items/<id>/item.mjs and writes the outputs below
```

`node tools/cosmetics/build.mjs` writes, for every item:

| Output | Path |
|---|---|
| store art | `website/cosmetics/<id>/item.json` + `texture.png` |
| store list | `website/cosmetics/catalog.json` (every item's meta, in shop order) |
| mod art | `src/mods/versions/1.21.1/src/client/resources/assets/originclient/cosmetics/<id>/item.json` + `texture.png` |
| mod list | `.../assets/originclient/cosmetics/catalog.json` |
| prices | `services/cosmetics/src/catalog.gen.js` (`export const CATALOG = {...}`, generated, never hand-edited) |

`--check` exits non-zero if any output is stale (CI drift gate, like shared-sync).
Item outputs are deterministic (no Math.random without a fixed seed; no dates).

## Item meta (in item.json and catalog.json)

```json
{
  "id": "wings-ember",
  "name": "Ember Wings",
  "kind": "wings",
  "slot": "back",
  "cents": 999,
  "blurb": "One line for the store card.",
  "animated": true,
  "plusOnly": false,
  "onSale": true,
  "hidesCape": true,
  "capeOffset": 0
}
```

- `id`: `[a-z0-9-]+`, prefix = kind (`cape-`, `wings-`, `pack-`, `mask-`,
  `bandana-`, `hat-`, `halo-`, `crown-`, `theme-`). Ids are forever: a player
  who bought one keeps it, so a rebuilt item keeps its id and a dropped item
  stays in the catalog with `"onSale": false` (still owned, still worn, just
  not sold).
- `kind` -> `slot`: cape -> `cape`; wings, pack -> `back`; mask, bandana ->
  `face`; hat, halo, crown -> `head`; theme -> `theme`. One item per slot.
- `cents`: USD. Every price is $5-$10 (Will, 2026-10-12). Tiers: static cape
  599, animated cape 899-999, hat/halo/crown/mask/bandana 599-799, backpack
  799-899, wings 999. `plusOnly` items are never sold (`cents` 0, `onSale: false`):
  the Origin+ cape and every menu theme (Will, 2026-10-18: themes are Origin+ menu unlocks).
- `hidesCape`: wings hide the cape (like an elytra). `capeOffset`: model px
  the cape is pushed back so it hangs behind a backpack instead of through it.

## Coordinates (both renderers convert from these)

Minecraft model pixels (1/16 block). Right-handed, **y up**, **+z = the
player's front**, **+x = the player's left**. Every item is attached to one
bone of the player, and its coordinates are relative to that bone's pivot:

- `head`: pivot = the neck (bottom centre of the head). The head cube is
  x -4..4, y 0..8, z -4..4. It follows head yaw + pitch.
- `body`: pivot = top centre of the torso. The torso is x -4..4, y -12..0,
  z -2..2 (back surface at z = -2). Follows body yaw (and the crouch lean).

`slot` picks the bone: head/face -> `head`; back -> `body`. Capes keep the
vanilla cape layout (below), not a model.

**Clearance (Will: "I don't want a big hat you can't see over")**: head items
stay within 3 px above the head top (y <= 11) and 1.5 px outside the head
sides, except halos, which float (y 10..13) and must be thin (<= 1.5 px).
Face items hug the face (z 4..5). Back items: wings span <= 14 px each side of
the spine and never rise above y = +6 (they must not cover the head from
behind); backpacks <= 5 px deep, inside x -4..4.

**Height tweaks (per player, not per item):** a player can raise or lower what
sits in each body slot (cape, head, face, back) to suit their skin, in 0.5 px
steps, within +-4 px (the Cosmetics screen's arrows). It is saved with what they
wear (service `equipped.adjust`, `{"head": 1.5, ...}`, zero slots left out) so
everyone sees it, and the renderers add it to the item's y after the bone
transform. Items are authored for the default skin; the tweak is never baked in.

## Models

```json
"model": {
  "texSize": [64, 32],
  "scale": 4,
  "bones": [
    { "name": "root", "pivot": [0, 0, 0], "rotation": [0, 0, 0],
      "cubes": [
        { "from": [-4, -10, -6], "to": [4, -2, -2], "uv": [0, 0],
          "inflate": 0, "mirror": false, "emissive": false,
          "rotation": { "origin": [0, -6, -4], "angle": [0, 0, 0] },
          "faces": { "north": [0, 0, 8, 8] } }
      ] },
    { "name": "wingL", "parent": "root", "pivot": [2, -2, -2], "rotation": [0, 20, 0], "cubes": [] }
  ]
}
```

- `texSize` is in model px; the PNG is `texSize * scale` pixels (`scale` 4 =
  HD, 4 texture px per model px; allowed 2, 4, 8). One texture per item.
- `uv` = Blockbench/vanilla **box UV** origin (model px) for that cube. A cube
  may instead give `faces` with per-face `[u0, v0, u1, v1]` (model px; u1 < u0
  mirrors). Face names: north = -z (the side facing the player's back),
  south = +z, east = +x... exactly Blockbench's. Zero-thickness planes
  (wing membranes) use `faces` with just the two faces they need.
- `emissive: true` draws full-bright (glowing parts). `glow: true` draws it
  additively on top (soft glow halos): keep glow geometry to a few cubes.
- Angles are degrees, applied Z then Y then X (Blockbench order), around the
  bone or cube `origin`.

## Capes

```json
"cape": { "scale": 8, "frames": 1, "fps": 10 }
```

The texture is the vanilla cape layout at `scale` (64x32 model px -> PNG
`64*scale` x `32*scale` per frame), frames stacked vertically. Readers derive
nothing from the PNG size: `scale` and `frames` come from item.json. Static
capes: scale 8. Animated capes: scale 4 (GPU budget <= 4 MB per item:
`64*s * 32*s * 4 bytes * frames`).

## Animation

```json
"anim": {
  "frames": 1, "fps": 10,
  "motion": [
    { "bone": "wingL", "channel": "rot", "axis": "y",
      "amp": 12, "period": 2.4, "phase": 0,
      "walkAmp": 10, "airAmp": 25 }
  ]
}
```

- Texture frames (`frames` > 1): stacked vertically in the PNG, `fps` each.
- `motion`: each entry adds `sin(2*pi*(t/period + phase)) * A` to one bone
  channel (`rot` degrees or `pos` model px), where
  `A = amp + walkAmp * walkSpeed01 + airAmp * airborne01`. `t` is seconds
  (game: ticks/20 + partial). walkSpeed01 = horizontal speed / sprint speed,
  clamped 0..1; airborne01 eases 0 -> 1 over 0.25 s when not on ground.
  Mirror bones (wingR) use the same entry with `amp` negated.
- `spin` (continuous rotation): `{ "bone": "ring0", "channel": "spin", "axis": "x",
  "speed": 62 }` turns the bone about its own `axis` at `speed` degrees per
  second (negative = the other way), added to the bone's rest rotation:
  `angle = (speed * t) mod 360`. Renderers wrap the angle (double precision) so a
  long session never loses precision. A spin has no `amp`/`period`; it repeats
  every `360/|speed|` s. Old renderers that don't know `spin` read it as a `rot`
  entry with `amp` 0, i.e. no motion (the bone just holds its rest rotation), so
  give a spun bone a rest rotation that looks fine standing still. Put spinning
  parts under a parent bone whose rest rotation sets the spin axis (a ring under a
  yaw bone spins about its own long axis).
- Reduced motion (store `prefers-reduced-motion`): renderers hold t = 0.

## Particles

```json
"fx": [ { "bone": "body", "at": [0, -6, -3], "spread": [4, 5, 1],
          "color": "#ff9a3c", "color2": "#ffd27a", "perSecond": 6,
          "life": [1.2, 2.2], "size": 0.8, "rise": 3, "drift": [0, 0, -2],
          "glow": true } ]
```

Model px and seconds. A few per second, never a cloud. Same table drives the
store and the mod.

## Themes

A theme is **Origin's main menu colours re-tinted**, never a palette with its own
rules. The main theme is the `DEF_` tokens in `OriginDeck.java` (mod menu, Cosmetics,
every deck-drawn screen) and `OriginTheme.java` (the rest), which came from the design
mock the menu was built from (`design/menu-redesign/mock/mock4.tpl.html`). An item
names three anchors and `cosmetics/lib/theme.mjs` moves every token with them (OKLCH:
hue rotated, chroma and lightness scaled), so contrast and alphas match the main theme:

```js
import { themeTokens } from '../../lib/theme.mjs';
const SPEC = { accent: '#5aa9e6', surface: '#1a2029', text: '#e9eef3' };  // + off: 'neutral' for red/pink accents
export default { meta: { ... kind: 'theme' ... }, theme: themeTokens(SPEC) };
```

item.json then holds every value, named like the Java fields; the mod only assigns them:

```json
"theme": { "deck": { "PANEL": "#13171cf0", "ACC": "#5aa9e6", ... },
           "workbench": { "BG": "#...", "ACCENT": "#...", ... } }
```

Main anchors: surface `#1d2320`, text `#f1e9d2`, accent `#5fd09b`; feeding them back
in reproduces the `DEF_` values exactly (the build fails otherwise). White hairlines,
gold NEW, info/warning colours are never themed. `off: 'neutral'` swaps the red DISABLED
bar for a quiet text-tinted one, for accents a red DISABLED would be confused with.

Recolours the owner's own Origin menus only; other players never see it. The picture
(`texture.png`) is the real menu in the theme: `node tools/cosmetics/theme-preview.mjs
<id>` renders the design mock in Chromium and commits `preview.png` +
`preview.tokens.json`; the build ships it full size to the store and 4x smaller to the
mod, and fails if the tokens changed since it was rendered.

## item.mjs

```js
import { Canvas, ramp, mark } from '../../lib/px.mjs';
export default {
  meta: { id, name, kind, cents, blurb, animated, ... },
  model: { ... },            // omit for capes and themes
  cape: { scale, frames },   // capes only
  anim: { ... }, fx: [ ... ], theme: { ... },
  paint(frame) { return canvas; }  // RGBA pixel canvas: texSize*scale (or the cape sheet) for one frame
};
```

## Art rules (what the judge checks)

1. One pixel density per item; never a downscaled vector. The Origin mark
   (three tilted rings, 0/60/120 degrees) is drawn pixel by pixel at the exact
   size it appears, from `lib/marks`, never resampled ("crunched").
2. Shading with 4-6 colour ramps per material, hue-shifted (shadows cooler,
   lights warmer), a light from the top-front-left, selective outlines on
   silhouettes, no pillow shading, no noise for its own sake.
3. Materials read as materials: cloth folds, metal specular, feather
   layering, leather stitching.
4. Scenes on capes (sunsets, skies) are painted with pixel clusters and
   dithered gradients, NOT flat bands and blobs (that was the "Roblox" look).
5. Animated items loop seamlessly; emissive parts sparingly.
6. Silhouette clean at 1x in-game scale, rich up close.

## Implementation notes (pipeline v2, clarifications found while building the pilots)
- Cube coordinates and bone pivots are absolute, Blockbench-style, in the attach bone's frame (head or body).
- Rotation is applied Z, then Y, then X (three.js Euler 'XYZ' as the site rig uses it).
- A mirrored bone negates `amp`, `walkAmp` and `airAmp` together. A mirror cube may share its partner's UV pixels.
- Cube sizes and `uv * scale` must land on whole texture pixels. Frame-stacked textures cycle the whole sheet.
- `capeOffset` belongs to the backpack (how far the cape is pushed back).
- `catalog.json` is `{ schema: 2, items }`; `item.json` is the flattened meta plus model/cape/anim/fx/theme/view.
- Items not yet redrawn stay in `cosmetics/pending.json`: they keep their service/price entries but are hidden from the store.
- `website/js/store.js` keeps a literal one-line-per-item PRICE_LIST (the service test compares it); the shop grid itself is driven by catalog.json.
- Judge sheets: `node tools/cosmetics/shots.mjs <id> [--with <id>]` writes `_sheet.jpg` per item.
