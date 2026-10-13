# The island in the sky

Source: `website/js/world.js` (three.js 0.170.0, vendored at `website/js/vendor/`).

## What it is

A roughly round island about 20 units across, generated from one fixed seed
(`20261011`) so every visitor sees the same island. Its top surface sits at y=1.

- **Top layer: all grass** (Will's rule). A small two-step hill on one side.
- **Dirt layers, then stone** below, with a few scattered ore-like accents (rare, deep ones deeper).
- **An underside that tapers to a point:** column depth
  `2 + (edge - dist) * 0.95 + rand * 1.6`, deepest in the middle.
- **A wavy edge, not a circle:** `9.4 + 1.2·sin(3a+1) + 0.7·sin(5a+2.3) + 0.45·sin(7a+0.4)`.
- **A few glowing blocks hanging under** the deepest columns.
- **Props, one of each and readable at a glance:** a small pond, a tree on the
  hill, a workbench, a torch, and tufts and flowers on ~19% of free grass.

Keep it small and legible. It is a logo nucleus, not a world.

## How it's rendered

**Colour pipeline: exact pixels.** No gamma round trip, so the art on screen is
the art in the file.
```js
THREE.ColorManagement.enabled = false;
renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
tex.colorSpace = THREE.NoColorSpace;
tex.magFilter = THREE.NearestFilter;              // crisp texels up close
tex.minFilter = THREE.NearestMipmapLinearFilter;  // no shimmer far away
tex.wrapS = tex.wrapT = THREE.RepeatWrapping;     // see the water note below
```

**Meshing: one face per visible side, grouped by texture.**
- A face is emitted only if its neighbour is empty or see-through (leaves,
  water, plants, torch). Leaf-to-leaf faces are skipped.
- Faces are grouped per texture into one `BufferGeometry` each, so the whole
  island is a few dozen draw calls.
- **Fixed per-face shade** (`face` attribute): top 1.0, front/back 0.8,
  left/right 0.6, bottom 0.5.
- **Soft corner shadows** (`ao` attribute) from the 3 neighbours around each
  corner in the face's plane: `[1, .8, .66, .54]` for 0/1/2/3 occluded (both side
  neighbours occluded = 3). Leaves occlude.
- **Flip the quad's diagonal** when `ao[0]+ao[2] < ao[1]+ao[3]` so the corner
  shadows interpolate evenly.
- Glowing blocks get AO 1 and shade 1.

**Special shapes:** plants are two crossed double-sided quads; the torch is a
thin stick (`7/16..9/16`, height `10/16`) cut from the middle of its texture;
water is a top face only, lowered to `14/16`, alpha 0.82, `depthWrite: false`,
`renderOrder = 2`.

## The surface shader (plain ↔ shaders)

One `ShaderMaterial` for every surface; `uShaders` (0..1) mixes two looks:

- **Plain:** `tex.rgb * faceShade * ao`.
- **Shaders (default on):**
  - Low warm sun `uSun = normalize(-0.45, 0.72, 0.52)`, colour `(1.12, .9, .68)`.
  - Cool sky fill `(0.34, 0.38, 0.56)`: `lit = tex * ao * (sky + sun * N·L)`.
  - **Orb light:** each of the 3 ring orbs is a point light,
    `max(N·D,0) * 16 / (1 + |d|² * .55)`, colour `uOrbCol = (0.36, 0.95, 0.78)` (ion jade).
  - Jade **rim**: `pow(1 - N·V, 3) * 0.22`.
  - Water: sun specular `pow(R·V, 48) * 1.4`, warm `(1, .85, .6)`; UVs wobble
    `sin(t*.6 + z*.7)*.03` and scroll `t*.05`.
  - Grade: `mix(lit, lit*lit*1.6, .18) * (1.04, 1.0, .94)`, a touch of contrast and warmth.
- Glowing surfaces mix toward `tex * 1.15` regardless.

`uShaders` eases toward its target with `1 - exp(-dt * 2.5)` (about 1 s), so the
switch fades rather than snaps. The CSS sky (`--sunset`) crossfades over `.9s`
alongside it.

**Water note:** water UVs scroll past 0..1. With clamp wrapping the edge texels
smear into lines across the pond, so every texture uses `RepeatWrapping`.

## Around the island

- **Glow sprites** on glowing blocks: additive radial-gradient sprite (128 px
  canvas, stops 1 → .55 at 18% → 0), torch `#ffb84d` scale 2.2, big glow block
  `#ffd27a` scale 3.4, opacity `.45 + .35 * uShaders`.
- **Dust motes:** 260 points (140 on mobile), cream `#f1e9d2`, size .12,
  opacity .5, additive, drifting up at .35 units/s in a 60×40×60 box and wrapping.
- **The sky is CSS, not 3D.** The canvas is transparent (`alpha: true`, clear
  alpha 0) over `.backdrop`: the dark tiled pattern + dark gradient, with the
  golden-hour gradient on `::after` at `opacity: var(--sunset)`:
  `#1b2340 → #4a3a63 (34%) → #c9795a (62%) → #f2b878 (74%) → #3a2a24`, plus a warm
  radial sun glow at 30% 62%. This is what makes the no-WebGL fallback free.
- `.world-shade` (a dark radial veil, `opacity: var(--shade)`) dims the island
  under text-heavy scenes. Per-scene amounts live in `SCENES` in site.js.

## Performance guards

- Pixel ratio capped: `min(dpr, mobile ? 1.5 : 1.75)`.
- `dt` clamped to 0.05 s. Rendering pauses while the tab is hidden, and the clock
  is re-zeroed on return so nothing jumps.
- Island geometry is static. Only uniforms, ring transforms and dust update per frame.

## Changing the island

- New surface type: add its texture to `build_assets.py`, re-run it, add the kind
  to `KINDS` (a string, or a 6-face array `[+x, -x, +y, -y, +z, -z]`), place it in
  `generateIsland()`.
- See-through / plant / glowing: add it to the matching Set.
- Keep the seeded `rng`. Never use `Math.random()` for layout.
