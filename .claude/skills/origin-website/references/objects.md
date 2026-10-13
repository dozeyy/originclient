# 3D objects

Two families: the **rings** that orbit the island (`world.js`) and the **items**
shown on a turntable (`cosmetics.js`).

## The rings: the logo as an object (`world.js`)

- **Geometry:** three tori, rotated 0°, 60°, 120° about the view axis, each tipped
  by `acos(0.355)` (≈ 69°). That ratio is what makes a flat ring read as the
  logo's thin ellipse. Radii are `inner × [1.5, 1.25, 1]`.
- **Sized from the island:** `inner = boundingSphere.radius + 0.87 + 0.8` (half a
  block diagonal + clearance), centred on the sphere, so nothing can pass through
  the island at any camera angle.
- **Always facing the camera:** `rings.quaternion.copy(camera.quaternion)` every
  frame. The ring group is a billboard; the *rotation inside it* is what moves.
- **Each ring is three meshes:** a thin bright core (tube 0.08, cream
  `#f1e9d2`, opacity .95), a wide soft glow (tube 0.36, `#4bc8ae`, additive,
  opacity `.2 + .1·shaders`), and a **comet tail**, a short arc (1.1 rad) behind
  the orb with a shader that fades `pow(u, 2.4)` from teal to near-white.
- **Each ring has an orb:** a bright core sphere (r 0.3, `#f4fffb`) inside a soft
  jade halo sprite (`#6fe3c4`, scale 2.4, opacity .75). The orb's world position
  feeds `uOrb[i]` so it lights the island (see island.md).
- **Spin:** rings turn at periods 26 / 34 / 42 s, the middle one backwards,
  matching the installer's orbit mark. Orbs lap their ring at 0.4× the period with
  a phase offset of `i × 2.1`.

## Items on a turntable (`cosmetics.js`)

Everything is built from **boxes, tori and points**, with art **painted in code**.

### Building blocks
- `shadedBox(w, h, d, tint)`: `BoxGeometry` with fixed per-face shade baked into
  vertex colours (`[0.72, 0.72, 1, 0.5, 0.95, 0.95]` for +x, -x, +y, -y, +z, -z),
  drawn with `MeshBasicMaterial({ vertexColors: true })`. No lights in the scene.
- `glowRing(radius, tube, hex, glowHex)`: a solid torus plus one 2.6× fatter
  additive torus at 22% opacity, the same core-and-glow pairing as the site rings.
- `glowCube(size, hex, glowHex)`: a cube plus a 2.2× additive cube at 25%.
- `sparks(colors, count, spawn, drift)`: square additive points that fade in and
  out with `sin(life·π)`. Each particle's age is a whole number of loops, so it repeats.

### The items
- **Orbit Halo (the hero item):** an atom over the head, a pulsing glowing
  nucleus and three rings, each tumbling on its own tilt (`0.4 / 0.85 / 1.2` rad),
  precession and wobble, with a bright bead and a four-step fading trail racing
  round it. It is the logo mark in miniature. Cycle = `ORBIT_LOOP` (3 × `LOOP`).
- **Gold Halo:** one glow ring bobbing `0.35·sin(2t)`.
- **Pixel Crown:** a jewelled gold band from stacked boxes. Each of four sides is
  built in its own frame and rotated by `side × 90°`; corner and centre points,
  pearls, gems (red, blue, mint, blue), a velvet cap, an orb and a cross; plus a
  few white twinkle sparks. The band sits clear of the head by design.
- **Cape-style panels:** a thin box (`10×16×1`) hanging from a pivot, outside
  face = the art, inside face = the same art darkened 45%, edges a flat colour,
  swaying a few degrees. Animated ones repaint into the same canvas each frame and
  shed sparks.

### Art is painted, not shipped
Panel textures are drawn at 40×64 on a canvas in **2×2 cells** (so they read as
pixel art), with a seeded `rng` so the grain is identical for every visitor and
steady frame to frame. Each design is one painter in `CAPES` plus one entry in
`ART`. A real artwork later means swapping the painter for an image; nothing else
changes. Helpers: `ramp(stops, t)` for gradients, `cells()` for the cell loop,
`border()`, and `originMark()` for the three-ring mark at full resolution with
a soft glow pass.

### The stage
- A neutral stand-in figure (clay, dark hoodie, mint drawstrings) so items are
  judged on their own, not on whoever is visiting.
- Camera `fov 30` (36 on narrow stages), pulled back per item kind (`VIEWS` /
  `THUMBS`: yaw, look height, distance, lift).
- Soft elliptical floor shadow (radial-gradient plane at y=.01).
- The figure turns slowly (`yaw += dt·0.35`). **Dragging turns it**
  (`0.012 rad/px`); on release the view holds ~2.5 s, then drifts again. Choosing
  an item snaps to the angle that shows it best and holds ~4.5 s.
- `createThumbnailer()` renders shop-card stills and **seamless frame loops**
  (webp) off-screen, so cards animate with no per-card WebGL context.

## Adding an object

1. Paint or build it in `cosmetics.js`, keyed by an id in `ART`.
2. Make every moving part a whole number of cycles per `LOOP` (or `ORBIT_LOOP`).
3. Give it a `still` pose (the frame used when motion is reduced).
4. Add name, price and copy in `store.js` (what is for sale lives there, how it
   looks lives here).
